package mole

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

/** The Mole bit-cycle engine --- Phase 1 complete variant.
  *
  * Decodes the full v0 ISA except the two target-role opcodes
  * ([[Opcode.sampleBitOnScl]] / [[Opcode.driveBitOnScl]], Step 19) and the four
  * reserved v0.5 slots (trap to halt). Drives the [[MoleBus]] at quarter-bit
  * pacing programmable per [[BusMode]] via the four [[LoadTiming]] divider
  * registers, captures `MISMATCH_FLAG` / `TIMEOUT_FLAG` / `START_FLAG` /
  * `STOP_FLAG` per AGENTS §3.15, writes a length-prefixed sequence of CAPTURE,
  * MARK and HALT records into the result ring, and traps any malformed
  * instruction (reserved opcode, reserved cond code, reserved tx_symbol,
  * invalid `SET_BUS_MODE` wire value) to a HALT with a distinguished status.
  *
  * ==Architecture (one paragraph)==
  *
  * One `StateMachine` walks `Idle → Fetch → FetchWait → Decode → (Execute) →
  * Fetch` with a `Halt` tail that pushes [[Revision.wordLo]],
  * [[Revision.wordHi]] and the HALT status word into the result ring and
  * returns to `Idle`. Bus drives happen only in `EmitBit` / `EmitQuarter` /
  * `StretchScl`, all paced by [[QuarterBitTimer]] whose `reload` is muxed from
  * a 4-entry [[LoadTiming]] register file selected by the active [[BusMode]]'s
  * low 2 bits. The bus pads come from `Reg(Bool())`s at component scope so
  * every transition is a single registered edge per `fpga/Mole/AGENTS.md`
  * §"Bus-shaped FSM idiom".
  *
  * ==Bus driver model==
  *
  * `sda*` and `scl*` driver regs hold whatever value was last latched for them.
  * Between bits (during `Fetch` / `FetchWait` / `Decode` / record-write
  * substates) the regs are not touched, so SCL stays at its `Q3=recessive`
  * value through fetch overhead --- which is what an I3C / I2C receiver expects
  * (SCL high between bits). On entry to `Idle` (after `HALT`), the regs are
  * forced to all-off so the bus releases cleanly.
  *
  * ==Quarter-bit pacing==
  *
  * The shared [[QuarterBitTimer]] is loaded on entry to `EMIT_BIT` /
  * `EMIT_QUARTER` / `STRETCH_SCL` / `WAIT_ON` and `enable`d while we are in
  * those states. The `reload` value is combinationally muxed from
  * `timingRegs(busModeReg[1:0])`; switching `BUS_MODE` switches the next load's
  * period without an extra opcode.
  *
  * ==Result ring format (v0, pre-Phase-0)==
  *
  * The ring lives at `[resultBase, resultLimit]` where `resultLimit =
  * resultBase + resultWordCount - 1`. Layout:
  *
  *   - `resultBase` / `resultBase + 1` --- 32-bit [[Revision]] (always written
  *     first as part of the HALT tail).
  *   - `resultBase + 2 ..= resultLimit - 1` --- record stream. Each record is 1
  *     or 3 words tagged by the high 2 bits of word0.
  *   - `resultLimit` --- reserved for the HALT status word. Always written,
  *     always last. Reserved exclusively so an overflowing record stream can
  *     never overwrite the HALT.
  *
  * Record encodings (`tag = word[15:14]`):
  *
  *   - `00`: CAPTURE (1 word). `[13:1] = 0`, `[0] = captured SDA bit`.
  *   - `01`: reserved.
  *   - `10`: MARK (3 words). Word0: `[13:8] = 0`, `[7:0] = label`. Word1:
  *     `timestamp[15:0]`. Word2: `timestamp[31:16]`. Timestamp is a 32-bit
  *     fabric-cycle counter starting at 0 on `io.start`; wraps at ~179 s @ 24
  *     MHz.
  *   - `11`: HALT (1 word). `[13] = overflow`, `[12] = mismatchAtHalt`,
  *     `[11:8] = status`, `[7:0] = 0`.
  *
  * `mismatchAtHalt` is the final value of `MISMATCH_FLAG` at HALT entry --- it
  * does not track "any mismatch ever seen" (since a passing compare clears the
  * flag per spec). `overflow` latches True the first time the engine tried to
  * write a record that would not fit before `resultLimit`; once set it stays
  * set, and subsequent CAPTURE / MARK opcodes silently drop their writes.
  *
  * Status codes `0x0..0xC` are caller-defined via the [[Halt]] opcode's
  * `status` field. Codes `0xD..0xF` are reserved for engine traps:
  *
  *   - `0xF` --- malformed instruction (reserved opcode, reserved cond code,
  *     reserved `tx_symbol`, or invalid `SET_BUS_MODE` wire value).
  *
  * ==What is deliberately *not* implemented yet==
  *
  *   - `SAMPLE_BIT_ON_SCL`, `DRIVE_BIT_ON_SCL` (Phase 4 / Step 19).
  *   - Result-ring readback / host visibility (Phase 2).
  *   - AGENTS §3.13 target-role PP-on-SCL lint (defense-in-depth, Step 19).
  *
  * ==PC arithmetic semantics==
  *
  * `JMP` and `BRANCH_ON` wrap modulo `2^pcWidth` (the natural UInt arithmetic).
  * No runtime range check against `programWordCount`; the host SDK is
  * responsible for emitting valid targets. Out-of-range fetches manifest as
  * reads from the result-ring region of SPRAM which decode to garbage and
  * almost always trap to the malformed-instruction HALT.
  *
  * ==IO bundle==
  *
  * The engine speaks one master read port and one master write port to the
  * [[SpramController]], plus the [[MoleBus]] and a `start` / `done` pair. The
  * wrapper in Phase 2 will sit between the UART loader and this engine; for
  * Phase 1 sims drive `start` directly after pre-loading program memory.
  *
  * @param cfg
  *   Mole configuration record. Only `programWordCount`, `resultRingByteCount`,
  *   and `quarterPeriodCyclesReset` are consumed at Step 8; the rest threads
  *   through but does not change elaboration.
  */
case class BitCycleEngineCore(cfg: MoleConfig) extends Component {

  // ------------------------------------------------------------------
  // Derived widths
  // ------------------------------------------------------------------

  /** Number of words in program memory (low half of the SPRAM). */
  val programWordCount: Int = cfg.programWordCount

  /** Number of words reserved for the result ring (high half of the SPRAM).
    * Mirrors the rounding [[SpramController]] does.
    */
  val resultWordCount: Int = (cfg.resultRingByteCount + 1) / 2

  /** Total addressable words (program + result), used to size the shared SPRAM
    * address.
    */
  val totalWords: Int = programWordCount + resultWordCount

  /** Width of an SPRAM address. Matches [[SpramController]]'s `addrWidth`.
    */
  val addrWidth: Int = log2Up(totalWords)

  /** Width of the program counter --- enough to address any `programWordCount`
    * slot.
    */
  val pcWidth: Int = log2Up(programWordCount)

  /** First result-ring word address (Revision lo is written here, Revision hi
    * at `+1`, then records, then HALT at `resultLimit`).
    */
  val resultBase: Int = programWordCount

  /** Last addressable word in the result ring --- reserved exclusively for the
    * HALT status word so an overflowing record stream can never overwrite the
    * HALT.
    */
  val resultLimit: Int = resultBase + resultWordCount - 1

  /** Last word the record stream is allowed to touch. Reserves one word at the
    * top of the ring for HALT.
    */
  val recordLimit: Int = resultLimit - 1

  // Elaboration require: ring must hold at least Revision lo + Revision hi +
  // HALT (3 words). Defaults give 4096 words; checked here so a config that
  // shrinks `resultRingByteCount` past the minimum fails loudly at compile.
  require(
    resultWordCount >= 3,
    s"resultWordCount=$resultWordCount but engine needs >= 3 " +
      "(Revision lo + Revision hi + HALT)"
  )

  // ------------------------------------------------------------------
  // IO
  // ------------------------------------------------------------------

  val io = new Bundle {

    /** Bus pads. Connected directly into the SB_IO wrapper at the `MoleTop`
      * level (Step 13); for sim it goes to an `OpenDrainBus` model.
      */
    val bus = master(MoleBus())

    /** Engine fetch read command (offered to the [[SpramController]]).
      */
    val programReadCmd = master Stream UInt(addrWidth bits)

    /** Engine fetch read response (1-cycle latency after `fire`). */
    val programReadResp = slave Flow Bits(16 bits)

    /** Engine result-ring write (offered to the [[SpramController]]).
      */
    val resultWrite = master Stream SpramWriteCmd(addrWidth)

    /** Level-sensitive start request. Sampled while the engine is in `Idle`
      * (i.e. `done === True`). The wrapper drops `start` once `done` falls.
      */
    val start = in Bool ()

    /** High whenever the engine is in `Idle`. Falls on `start`, re-rises after
      * the final `HALT` result-ring write completes.
      */
    val done = out Bool ()
  }

  // ------------------------------------------------------------------
  // Bus drivers --- registered per the bus-shaped FSM idiom
  // ------------------------------------------------------------------

  val sdaDriveLow = Reg(Bool()) init (False)
  val sdaDriveHigh = Reg(Bool()) init (False)
  val sclDriveLow = Reg(Bool()) init (False)
  val sclDriveHigh = Reg(Bool()) init (False)

  io.bus.sda.driveLow := sdaDriveLow
  io.bus.sda.driveHigh := sdaDriveHigh
  io.bus.scl.driveLow := sclDriveLow
  io.bus.scl.driveHigh := sclDriveHigh

  // ------------------------------------------------------------------
  // Engine state
  // ------------------------------------------------------------------

  /** Active bus mode --- the engine's only protocol context. One writer
    * (`SET_BUS_MODE`), two readers ([[SymbolDecoder]] and, post-Step 11, the
    * timing-divider mux). Reset value `i2c` per ROADMAP §"Bus mode register"
    * (safe default: OD release on idle bus).
    */
  val busModeReg = Reg(BusMode()) init (BusMode.i2c)

  /** Program counter (next instruction to fetch). */
  val pc = Reg(UInt(pcWidth bits)) init (0)

  /** Latched instruction word from the last completed fetch. */
  val instrReg = Reg(Bits(Instruction.WORD_WIDTH bits)) init (0)

  /** Combinational opcode view of [[instrReg]] --- sliced directly from the top
    * 4 bits. Every other operand field in the FSM is sliced from `instrReg` by
    * hand (e.g. `instrReg(11 downto 10)` for the tx_symbol field of EMIT_BIT,
    * `instrReg(11 downto 9)` for the mode field of SET_BUS_MODE). Keeping the
    * opcode on the same convention means there is one slicing style across the
    * whole fetch path and no Bundle field-ordering surprise sitting between the
    * encoder and the decoder.
    */
  val opcode = Opcode()
  opcode.assignFromBits(instrReg(15 downto 12))

  /** Quarter index within the active `EMIT_BIT` (0..3). Only valid in the
    * `emitBitState`.
    */
  val qIdx = Reg(UInt(2 bits)) init (0)

  // ------------------------------------------------------------------
  // Sticky engine flags (AGENTS §3.15 / ROADMAP §"Engine flags")
  //
  // `MISMATCH_FLAG` --- set by EMIT_BIT / EMIT_QUARTER (and Step-19
  // SAMPLE_BIT_ON_SCL / DRIVE_BIT_ON_SCL) when their `mask=1` compare
  // against the sampled SDA fails; cleared when the same compare
  // passes. Sticky across non-compare opcodes so a program can emit N
  // bits then test "any failed" with a single BRANCH_ON MISMATCH
  // (Step 11).
  //
  // `TIMEOUT_FLAG` --- set by WAIT_ON when its timeout expires before
  // cond fires; cleared by WAIT_ON when cond fires within the
  // timeout. Sticky across non-WAIT_ON opcodes.
  //
  // `START_FLAG` / `STOP_FLAG` --- edge-detector outputs. Only set
  // when a WAIT_ON of the matching cond is *armed* (i.e. while in
  // `waitOnState` with `waitCondReg === startSeen / stopSeen`); edges
  // observed at any other time are dropped per ROADMAP's re-arm-on-
  // entry semantics. Cleared on entry to the next WAIT_ON of the
  // same edge cond.
  //
  // All four reset to 0 on power-on AND on program-start (in
  // `idleState`'s `io.start` handler) so they cannot leak between
  // consecutive runs over the loader port. Program-start clearing is
  // the one documented "ad-hoc" clear path; everything else respects
  // §3.15's write-once-until-overwritten rule.
  // ------------------------------------------------------------------

  val mismatchFlag = Reg(Bool()) init (False)
  val timeoutFlag = Reg(Bool()) init (False)
  val startFlag = Reg(Bool()) init (False)
  val stopFlag = Reg(Bool()) init (False)

  // ------------------------------------------------------------------
  // Bus observer --- two-FF synchronizer on the registered sample
  // lines from the open-drain pads, plus single-cycle history regs
  // for the combinational Start / Stop edge detectors.
  //
  // Sync shifts initialize to all-ones (recessive idle) so the first
  // two post-reset cycles do not synthesise a falling-edge pulse just
  // because the registers came up at 0. Same reasoning for the prev
  // regs: the bus comes up recessive on every real-world deployment;
  // initialising the edge-detector history to "high" matches that.
  //
  // Start edge: SDA falls while SCL is high. Stop edge: SDA rises
  // while SCL is high. Both pulses are 1 fabric cycle wide on the
  // sample-domain transition that produced them.
  // ------------------------------------------------------------------

  val sdaSyncShift = Reg(Bits(2 bits)) init (B"11")
  val sclSyncShift = Reg(Bits(2 bits)) init (B"11")
  sdaSyncShift := sdaSyncShift(0) ## io.bus.sda.read
  sclSyncShift := sclSyncShift(0) ## io.bus.scl.read
  val sdaSampled = sdaSyncShift(1)
  val sclSampled = sclSyncShift(1)

  val sdaSampledPrev = RegNext(sdaSampled) init (True)
  val sclSampledPrev = RegNext(sclSampled) init (True)

  val sdaFalling = sdaSampledPrev && !sdaSampled
  val sdaRising = !sdaSampledPrev && sdaSampled
  val startEdge = sdaFalling && sclSampled
  val stopEdge = sdaRising && sclSampled

  // ------------------------------------------------------------------
  // WAIT_ON / STRETCH_SCL scratch regs --- latched in `decodeState`
  // on the cycle we enter the corresponding execute state.
  //
  // The cond reg is read combinationally inside `waitOnState` to
  // build the `condTrue` mux; the timeout reg counts down in
  // quarter-bit units each `timer.io.tick`. `waitTimeoutInfinite`
  // captures the documented `timeout = 0` "wait forever" sentinel so
  // the count-down branch never decrements past it.
  // ------------------------------------------------------------------

  val waitCondReg = Reg(CondCode()) init (CondCode.always)
  val waitTimeoutQs = Reg(UInt(8 bits)) init (0)
  val waitTimeoutInfinite = Reg(Bool()) init (False)
  val stretchQs = Reg(UInt(12 bits)) init (0)

  // ------------------------------------------------------------------
  // Quarter-bit timing registers (Step 11 `LOAD_TIMING`)
  //
  // Four 10-bit divider words, one per `BUS_MODE.mode[1:0]` slot:
  //
  //   index 0 -> i2c     (mode wire 0b000)
  //   index 1 -> i3c-OD  (mode wire 0b001)
  //   index 2 -> i3c-PP  (mode wire 0b110)
  //   index 3 -> hdr-DDR (mode wire 0b111)
  //
  // The mux below feeds `timer.io.reload` combinationally so swapping
  // `BUS_MODE` takes effect on the next `EMIT_*` / `STRETCH_SCL` /
  // `WAIT_ON` without an extra opcode. `LOAD_TIMING` only fires in
  // `decodeState` where the timer is disabled, so we never race the
  // counter against a write.
  //
  // All four reset to `quarterPeriodCyclesReset - 1` so an engine
  // that never sees a `LOAD_TIMING` still ticks at the default rate.
  // ------------------------------------------------------------------

  val timingRegs = Vec(
    Reg(UInt(10 bits)) init U(cfg.quarterPeriodCyclesReset - 1, 10 bits),
    4
  )

  // ------------------------------------------------------------------
  // Result-ring write-pointer and overflow latch (Step 11)
  //
  // `resultWp` starts at `resultBase + 2` (one past the two Revision
  // halves). Each CAPTURE / MARK record bumps it; if there is not
  // enough room left below `recordLimit` for the record, the engine
  // sets `resultOverflow` instead and drops the write. `resultOverflow`
  // is reported in the HALT word's `[13]` bit.
  //
  // The HALT word itself always lands at `U(resultLimit)` (reserved
  // slot --- never a record target), so an overflowed record stream
  // cannot corrupt the diagnostic.
  // ------------------------------------------------------------------

  val resultWp = Reg(UInt(addrWidth bits)) init U(
    resultBase + 2,
    addrWidth bits
  )
  val resultOverflow = Reg(Bool()) init (False)

  // ------------------------------------------------------------------
  // MARK timestamp (Step 11)
  //
  // Free-running 32-bit fabric-cycle counter, reset to 0 on every
  // `io.start`. Wraps at ~179 s @ 24 MHz, which is comfortably
  // longer than any single compliance program. `markTimestampLatch`
  // captures the counter value at MARK decode so the multi-word
  // record carries the value sampled at the right moment even
  // though the writes span three cycles.
  // ------------------------------------------------------------------

  val markTimestamp = Reg(UInt(32 bits)) init (0)
  val markTimestampLatch = Reg(UInt(32 bits)) init (0)

  // Default driver: increment every cycle. Overridden conditionally
  // in `idleState` on `io.start` to reset to 0 --- the later
  // assignment inside `whenIsActive` wins when its `when` fires.
  markTimestamp := markTimestamp + 1

  // ------------------------------------------------------------------
  // Capture buffer (Step 11)
  //
  // Latched in `emitBitState` (at the Q2 tick, same sample point as
  // MISMATCH) or `emitQuarterState` (at the single tick) when the
  // bitstream has `capture = 1`. The execute state then routes to
  // `captureWriteState` to push a CAPTURE record into the ring
  // before refetching.
  // ------------------------------------------------------------------

  val captureValueReg = Reg(Bool()) init (False)

  // ------------------------------------------------------------------
  // HALT bookkeeping latches (Step 11)
  //
  // Captured at HALT entry (by the [[Halt]] opcode itself, by the
  // `default` arm for reserved opcodes, by the reserved-cond traps
  // in `WAIT_ON` / `BRANCH_ON`, by the reserved-`tx_symbol` traps
  // in `EMIT_BIT` / `EMIT_QUARTER`, or by the invalid-mode trap in
  // `SET_BUS_MODE`). The values flow through `haltWriteStatusState`
  // unchanged --- latching at entry keeps the diagnostic stable
  // even if `mismatchFlag` / `resultOverflow` change during the
  // three-word HALT write tail.
  //
  // `haltMismatchLatch` is the *final* value of `MISMATCH_FLAG`
  // (i.e. "did the last mask-bearing compare fail"), not "did any
  // compare ever fail" --- the latter would need a separate
  // ever-mismatch latch.
  // ------------------------------------------------------------------

  val haltStatusLatch = Reg(Bits(4 bits)) init (B"0000")
  val haltMismatchLatch = Reg(Bool()) init (False)
  val haltOverflowLatch = Reg(Bool()) init (False)

  // ------------------------------------------------------------------
  // Quarter-bit timer
  //
  // Instantiated at the full 10-bit `LOAD_TIMING` divider width. The
  // reload value is combinationally muxed out of `timingRegs` per the
  // active `BUS_MODE.mode[1:0]` slot; `LOAD_TIMING` writes to those
  // regs change the next load's period without an extra opcode.
  // ------------------------------------------------------------------

  val timer = QuarterBitTimer(maxReloadValue = (1 << 10) - 1)
  val timerLoad = Bool()
  val timerEnable = Bool()
  timerLoad := False
  timerEnable := False
  timer.io.reload := timingRegs(busModeReg.asBits(1 downto 0).asUInt)
  timer.io.load := timerLoad
  timer.io.enable := timerEnable

  // ------------------------------------------------------------------
  // IO defaults
  //
  // Pulse-style outputs default off; states raise them when needed.
  // ------------------------------------------------------------------

  io.programReadCmd.valid := False
  io.programReadCmd.payload := pc.resize(addrWidth bits)

  io.resultWrite.valid := False
  io.resultWrite.payload.addr := U(resultBase, addrWidth bits)
  io.resultWrite.payload.data := B(0, Instruction.WORD_WIDTH bits)

  // ------------------------------------------------------------------
  // FSM
  // ------------------------------------------------------------------

  val fsm = new StateMachine {

    // ----------------------------------------------------- Helpers ----
    //
    // `enterHalt(status)` is the single entry path into the HALT
    // write sequence. Every trap path uses it so the diagnostic
    // latches (`haltStatusLatch`, `haltMismatchLatch`,
    // `haltOverflowLatch`) carry consistent values regardless of
    // which arm fired the trap.
    //
    // Status codes:
    //   - `[15:8] = (0x80 | userCode)` via the [[Halt]] opcode ---
    //     the user picks the low 4 bits.
    //   - `0xF` (engine-internal) is the trap code for malformed
    //     instructions: reserved opcode, reserved cond code,
    //     reserved tx_symbol, invalid SET_BUS_MODE wire value.
    def enterHalt(status: Bits): Unit = {
      haltStatusLatch := status
      haltMismatchLatch := mismatchFlag
      haltOverflowLatch := resultOverflow
      goto(haltWriteLoState)
    }

    // `evalCondFlagOnly` mirrors `waitOnState`'s cond mux but
    // drops the live-edge OR --- `BRANCH_ON` tests the latched
    // flag, not the in-flight edge pulse. Used by the `BRANCH_ON`
    // decode arm.
    def evalCondFlagOnly(
        c: SpinalEnumCraft[CondCode.type]
    ): Bool = {
      val out = Bool()
      out := False
      switch(c) {
        is(CondCode.always) { out := True }
        is(CondCode.mismatch) { out := mismatchFlag }
        is(CondCode.notMismatch) { out := !mismatchFlag }
        is(CondCode.startSeen) { out := startFlag }
        is(CondCode.stopSeen) { out := stopFlag }
        is(CondCode.sdaLow) { out := !sdaSampled }
        is(CondCode.sdaHigh) { out := sdaSampled }
        is(CondCode.sclHigh) { out := sclSampled }
        is(CondCode.timeout) { out := timeoutFlag }
        is(CondCode.notTimeout) { out := !timeoutFlag }
        default { out := False }
      }
      out
    }

    // ------------------------------------------------------ Idle ----
    val idleState: State = new State with EntryPoint {
      whenIsActive {
        // Force all drivers off on every cycle we sit in Idle --- the
        // bus releases between programs, regardless of whatever the
        // last bit's Q3 left in the regs.
        sdaDriveLow := False
        sdaDriveHigh := False
        sclDriveLow := False
        sclDriveHigh := False

        when(io.start) {
          pc := 0
          // Clear sticky flags on every program start (ROADMAP
          // §"Engine flags": "Reset value 0 ... cleared at program
          // start / engine reset"). This is the documented
          // program-start reset path, not an ad-hoc per-opcode
          // clear (AGENTS §3.15 forbids the latter).
          mismatchFlag := False
          timeoutFlag := False
          startFlag := False
          stopFlag := False
          // Step 11: re-arm the result-ring and the MARK
          // timestamp counter so consecutive runs see a clean
          // slate. Same documented program-start exception that
          // covers the sticky flags above.
          resultWp := U(resultBase + 2, addrWidth bits)
          resultOverflow := False
          markTimestamp := U(0, 32 bits)
          goto(fetchState)
        }
      }
    }

    // ----------------------------------------------------- Fetch ----
    val fetchState: State = new State {
      whenIsActive {
        // Offer the read every cycle until the SPRAM controller
        // accepts it. With the Phase 1 arbiter (read priority 1,
        // ready := True), `fire` happens the same cycle we arrive.
        io.programReadCmd.valid := True
        when(io.programReadCmd.fire) {
          goto(fetchWaitState)
        }
      }
    }

    // ------------------------------------------------ FetchWait ----
    //
    // The SPRAM read response is `RegNext(doRead)` on the controller
    // side, so `programReadResp.valid` is high for exactly one cycle
    // --- the cycle right after `programReadCmd.fire` ed. That is the
    // cycle we enter `fetchWaitState`. Latch on that cycle.
    val fetchWaitState: State = new State {
      whenIsActive {
        when(io.programReadResp.valid) {
          instrReg := io.programReadResp.payload
          goto(decodeState)
        }
      }
    }

    // ---------------------------------------------------- Decode ----
    val decodeState: State = new State {
      whenIsActive {
        switch(opcode) {

          // -- HALT -------------------------------------------------
          //
          // Field layout: [11:8] = caller status, [7:0] = reserved
          // (=0). Status `0xD..0xF` are reserved for engine traps;
          // the engine does not enforce that here (the caller can
          // write any 4-bit value), but the SDK should keep user
          // codes in `0x0..0xC` for distinguishability.
          is(Opcode.halt) {
            enterHalt(instrReg(11 downto 8))
          }

          // -- SET_BUS_MODE ----------------------------------------
          //
          // Field layout: [11:9]=mode, [8:0]=reserved (=0).
          //
          // Valid wire values are `0b000` (i2c), `0b001` (i3c-OD),
          // `0b110` (i3c-PP), `0b111` (hdr-DDR) per ROADMAP §"Bus
          // mode register". Anything else is a malformed-instruction
          // trap (engine-internal status `0xF`).
          is(Opcode.setBusMode) {
            val rawMode = instrReg(11 downto 9).asUInt
            when(
              rawMode === 0 || rawMode === 1 ||
                rawMode === 6 || rawMode === 7
            ) {
              val newMode = BusMode()
              newMode.assignFromBits(instrReg(11 downto 9))
              busModeReg := newMode
              pc := pc + 1
              goto(fetchState)
            } otherwise {
              enterHalt(B"1111")
            }
          }

          // -- EMIT_BIT --------------------------------------------
          //
          // Field layout: [11:10]=tx_symbol, [9:3]=reserved (=0),
          // [2:0]=flag triple. `tx_symbol = 0b11` is reserved
          // (held for a v0.5 `raw_override` escape per AGENTS
          // §3.11) and traps. Latch SDA from the bitstream's
          // tx_symbol (held for all 4 quarters). Latch SCL for Q0
          // here, then advance it on each timer tick inside
          // `emitBitState`.
          //
          // The `capture` bit is handled inside `emitBitState`
          // (latched at Q2 same as MISMATCH, routed to
          // `captureWriteState` at Q3 instead of straight to
          // `fetchState`).
          is(Opcode.emitBit) {
            val txRaw = instrReg(11 downto 10)
            when(txRaw === B"11") {
              enterHalt(B"1111")
            } otherwise {
              val sdaSym = TxSymbol()
              sdaSym.assignFromBits(txRaw)

              val sda = SymbolDecoder(sdaSym, busModeReg)
              sdaDriveLow := sda.driveLow
              sdaDriveHigh := sda.driveHigh

              // Q0 = dominant per SclWaveformGen --- pre-compute
              // and latch into the SCL regs so the very first
              // quarter dwell shows the correct level.
              val sclSymQ0 = SclWaveformGen(U(0, 2 bits))
              val sclQ0 = SymbolDecoder(sclSymQ0, busModeReg)
              sclDriveLow := sclQ0.driveLow
              sclDriveHigh := sclQ0.driveHigh

              qIdx := 0
              timerLoad := True
              goto(emitBitState)
            }
          }

          // -- EMIT_QUARTER ----------------------------------------
          //
          // Field layout: [11:10]=sda_symbol, [9:8]=scl_symbol,
          // [7:3]=reserved (=0), [2:0]=flag triple. Either symbol
          // field set to `0b11` (reserved) traps to the
          // malformed-instruction HALT.
          //
          // The AGENTS §3.13 target-role lint ("reject
          // scl_symbol=recessive under PP class") is a host-side
          // SDK check; the engine does not yet carry a role
          // register so the defense-in-depth flatten is deferred
          // until Step 19 lands target-role helpers.
          is(Opcode.emitQuarter) {
            val sdaRaw = instrReg(11 downto 10)
            val sclRaw = instrReg(9 downto 8)
            when(sdaRaw === B"11" || sclRaw === B"11") {
              enterHalt(B"1111")
            } otherwise {
              val sdaSym = TxSymbol()
              sdaSym.assignFromBits(sdaRaw)
              val sclSym = TxSymbol()
              sclSym.assignFromBits(sclRaw)

              val sda = SymbolDecoder(sdaSym, busModeReg)
              val scl = SymbolDecoder(sclSym, busModeReg)
              sdaDriveLow := sda.driveLow
              sdaDriveHigh := sda.driveHigh
              sclDriveLow := scl.driveLow
              sclDriveHigh := scl.driveHigh

              timerLoad := True
              goto(emitQuarterState)
            }
          }

          // -- STRETCH_SCL -----------------------------------------
          //
          // Field layout: [11:0]=n_quarters. Force SCL low for `n`
          // quarter-bit periods; leave SDA as whatever the previous
          // wire opcode left in its drivers.
          //
          // `n = 0` is a documented no-op: skip the state entirely
          // and refetch. SDK shouldn't emit it (a zero-length
          // stretch is meaningless) but the engine accepts it
          // rather than trapping --- the encoder's 12-bit operand
          // contract has no zero-rejection clause.
          //
          // On exit, SCL is *released* to recessive decoded against
          // the current `BUS_MODE` (OD class: hiz, PP class: drive
          // high). Without this release a following `WAIT_ON
          // SCL_HIGH, t` would deadlock waiting for the engine
          // itself to stop driving low.
          is(Opcode.stretchScl) {
            val nQ = instrReg(11 downto 0).asUInt
            when(nQ === 0) {
              pc := pc + 1
              goto(fetchState)
            } otherwise {
              stretchQs := nQ
              sclDriveLow := True
              sclDriveHigh := False
              timerLoad := True
              goto(stretchSclState)
            }
          }

          // -- WAIT_ON ---------------------------------------------
          //
          // Field layout: [11:8]=cond_code, [7:0]=timeout in
          // quarter-bit ticks. `timeout = 0` is the "wait forever"
          // sentinel; the engine never sets `TIMEOUT_FLAG` in that
          // case.
          //
          // Reserved cond codes (`0xA..0xF`, v0.5 slots) trap to
          // halt rather than entering an infinite wait state --- a
          // forward-deployed program that ships with a v0.5
          // condition into a v0 engine must surface as a clean
          // halt, not a hang.
          //
          // Re-arm on entry: WAIT_ON START_SEEN clears `startFlag`,
          // WAIT_ON STOP_SEEN clears `stopFlag`. Per ROADMAP
          // §"START_FLAG and STOP_FLAG" this is what makes flags
          // reflect "recent intentional observations" rather than
          // eternal history. Other waits leave both flags alone
          // (sticky for the BRANCH_ON below to read).
          is(Opcode.waitOn) {
            val condRaw = instrReg(11 downto 8).asUInt
            when(condRaw > 9) {
              enterHalt(B"1111")
            } otherwise {
              val condCraft = CondCode()
              condCraft.assignFromBits(instrReg(11 downto 8))
              val t = instrReg(7 downto 0).asUInt
              waitCondReg := condCraft
              waitTimeoutQs := t
              waitTimeoutInfinite := (t === 0)
              when(condCraft === CondCode.startSeen) {
                startFlag := False
              }
              when(condCraft === CondCode.stopSeen) {
                stopFlag := False
              }
              timerLoad := True
              goto(waitOnState)
            }
          }

          // -- JMP --------------------------------------------------
          //
          // Field layout: [11:0]=addr (12-bit absolute). Wraps
          // modulo `2^pcWidth` --- the SDK guarantees valid
          // targets. Out-of-range fetches manifest as reads from
          // the result-ring SPRAM region; that decodes to garbage
          // and usually traps to the malformed-instruction HALT.
          is(Opcode.jmp) {
            pc := instrReg(11 downto 0).asUInt.resize(pcWidth)
            goto(fetchState)
          }

          // -- BRANCH_ON --------------------------------------------
          //
          // Field layout: [11:8]=cond_code, [7:0]=signed PC-rel
          // offset (8-bit two's complement, ±128). On taken,
          // `pc := pc + 1 + offset`; on not-taken, `pc := pc + 1`.
          // Wraps modulo `2^pcWidth`.
          //
          // Reserved cond codes (`0xA..0xF`, v0.5 slots) trap to
          // halt --- same forward-compat reasoning as WAIT_ON.
          //
          // Cond is evaluated against the *latched* flags only
          // (`evalCondFlagOnly`) --- BRANCH_ON does not race the
          // live edge pulse the way WAIT_ON does.
          is(Opcode.branchOn) {
            val condRaw = instrReg(11 downto 8).asUInt
            when(condRaw > 9) {
              enterHalt(B"1111")
            } otherwise {
              val condCraft = CondCode()
              condCraft.assignFromBits(instrReg(11 downto 8))
              val cTrue = evalCondFlagOnly(condCraft)
              val offsetSExt =
                instrReg(7 downto 0).asSInt.resize(pcWidth)
              val offsetU = offsetSExt.asUInt
              pc := Mux(cTrue, pc + 1 + offsetU, pc + 1)
              goto(fetchState)
            }
          }

          // -- LOAD_TIMING ------------------------------------------
          //
          // Field layout: [11:10]=reg, [9:0]=divider_word. Writes
          // one of the four `timingRegs` entries; the new value is
          // visible to the next `EMIT_*` / `STRETCH_SCL` /
          // `WAIT_ON` whose `BUS_MODE.mode[1:0]` matches `reg`.
          //
          // No runtime validation of `divider_word` --- the host
          // SDK is responsible for keeping it well above the
          // 2-FF synchroniser latency so MISMATCH / CAPTURE
          // sample points still land inside their respective
          // SCL-high windows.
          is(Opcode.loadTiming) {
            val regIdx = instrReg(11 downto 10).asUInt
            val word = instrReg(9 downto 0).asUInt
            timingRegs(regIdx) := word
            pc := pc + 1
            goto(fetchState)
          }

          // -- MARK -------------------------------------------------
          //
          // Field layout: [11:4]=label, [3:0]=reserved (=0). Writes
          // a 3-word record into the ring (see file header for
          // format). If there is not enough room for 3 words below
          // `recordLimit`, set `resultOverflow` and refetch without
          // writing.
          //
          // The MARK timestamp is latched at decode (now) so the
          // value reflects the moment the user requested the mark,
          // not the moment word-2 happens to fire 3 cycles later.
          is(Opcode.mark) {
            when(
              !resultOverflow && resultWp <= U(recordLimit - 2)
            ) {
              markTimestampLatch := markTimestamp
              goto(markWriteWord0State)
            } otherwise {
              resultOverflow := True
              pc := pc + 1
              goto(fetchState)
            }
          }

          // -- Everything else (SAMPLE_BIT_ON_SCL,
          //    DRIVE_BIT_ON_SCL, plus the 4 reserved v0.5 slots) --
          //
          // Step 19 owns the target-role opcodes; the four
          // reserved v0.5 slots have no v0 meaning. Both flavours
          // trap to halt with status `0xF` so a forward-deployed
          // program that ships with a not-yet-supported opcode
          // surfaces as a clean halt rather than a runaway bus.
          default {
            enterHalt(B"1111")
          }
        }
      }
    }

    // -------------------------------------------------- EmitBit ----
    val emitBitState: State = new State {
      whenIsActive {
        timerEnable := True

        when(timer.io.tick) {
          // Sample SDA at the Q2 → Q3 transition --- the latest
          // possible point inside the SCL-high half, with the
          // synchronizer's 2-cycle delay reflecting a value well
          // inside Q2 (~10 fabric cycles after SCL went high at
          // 12 cycles per quarter). `mask = 0` is don't-care:
          // leave `MISMATCH_FLAG` sticky from its last writer.
          // Same sample point feeds CAPTURE.
          when(qIdx === 2) {
            when(instrReg(Instruction.MASK_BIT)) {
              mismatchFlag :=
                sdaSampled =/= instrReg(Instruction.EXPECT_BIT)
            }
            when(instrReg(Instruction.CAPTURE_BIT)) {
              captureValueReg := sdaSampled
            }
          }

          when(qIdx === 3) {
            // Bit complete. PC++ now; if capture is set, route
            // through `captureWriteState` to write the record
            // before refetching --- it goes straight back to
            // `fetchState` after the write fires. SDA / SCL regs
            // keep their Q3 values until the next instruction
            // overrides them --- which is exactly the "SCL stays
            // high between bits" contract.
            pc := pc + 1
            when(instrReg(Instruction.CAPTURE_BIT)) {
              goto(captureWriteState)
            } otherwise {
              goto(fetchState)
            }
          } otherwise {
            val nextQ = qIdx + 1
            qIdx := nextQ
            // Update SCL for the new quarter. SDA stays as latched on
            // entry to this state (held for the full bit per spec).
            val sclSym = SclWaveformGen(nextQ)
            val scl = SymbolDecoder(sclSym, busModeReg)
            sclDriveLow := scl.driveLow
            sclDriveHigh := scl.driveHigh
          }
        }
      }
    }

    // ---------------------------------------------- EmitQuarter ----
    //
    // Single-quarter dwell. SDA and SCL stay at whatever the
    // `decodeState` arm latched on entry. On the timer tick (end of
    // the quarter) we run the same `mask`/`expect` compare and the
    // same `capture` latch as EMIT_BIT against the synchronised SDA
    // sample, then PC++.
    val emitQuarterState: State = new State {
      whenIsActive {
        timerEnable := True
        when(timer.io.tick) {
          when(instrReg(Instruction.MASK_BIT)) {
            mismatchFlag :=
              sdaSampled =/= instrReg(Instruction.EXPECT_BIT)
          }
          when(instrReg(Instruction.CAPTURE_BIT)) {
            captureValueReg := sdaSampled
          }
          pc := pc + 1
          when(instrReg(Instruction.CAPTURE_BIT)) {
            goto(captureWriteState)
          } otherwise {
            goto(fetchState)
          }
        }
      }
    }

    // ----------------------------------------------- StretchScl ----
    //
    // SCL forced low by the `decodeState` arm; this state just
    // counts down `stretchQs` ticks. On the final tick we release
    // SCL to recessive decoded against the current `BUS_MODE` (OD
    // class: hiz, PP class: drive high) so the next opcode --- or a
    // `WAIT_ON SCL_HIGH` --- does not see the engine still pulling
    // SCL down. The release mirrors EMIT_BIT's Q3 behaviour.
    val stretchSclState: State = new State {
      whenIsActive {
        timerEnable := True
        when(timer.io.tick) {
          when(stretchQs === 1) {
            val releaseSym = TxSymbol()
            releaseSym := TxSymbol.recessive
            val scl = SymbolDecoder(releaseSym, busModeReg)
            sclDriveLow := scl.driveLow
            sclDriveHigh := scl.driveHigh
            pc := pc + 1
            goto(fetchState)
          } otherwise {
            stretchQs := stretchQs - 1
          }
        }
      }
    }

    // -------------------------------------------------- WaitOn ----
    //
    // Block until the latched cond fires or the latched timeout
    // expires. The cond evaluation is combinational against the
    // registered sample lines (`sdaSampled`, `sclSampled`) and the
    // sticky flag set; START / STOP edges OR the about-to-be-
    // registered flag with the live edge pulse so a same-cycle edge
    // exits without an extra cycle of latency.
    //
    // Cond-first / timeout-second priority: if both fire on the
    // same cycle we honour the cond and clear `TIMEOUT_FLAG`.
    val waitOnState: State = new State {
      whenIsActive {
        timerEnable := True

        // Edge flag set --- only when the *current* WAIT_ON is
        // armed for that edge. Other waits leave both flags alone
        // even if the edge fires (drops the observation, per
        // ROADMAP's re-arm semantics).
        when(waitCondReg === CondCode.startSeen && startEdge) {
          startFlag := True
        }
        when(waitCondReg === CondCode.stopSeen && stopEdge) {
          stopFlag := True
        }

        val condTrue = Bool()
        condTrue := False
        switch(waitCondReg) {
          is(CondCode.always) { condTrue := True }
          is(CondCode.mismatch) { condTrue := mismatchFlag }
          is(CondCode.notMismatch) { condTrue := !mismatchFlag }
          is(CondCode.startSeen) { condTrue := startFlag || startEdge }
          is(CondCode.stopSeen) { condTrue := stopFlag || stopEdge }
          is(CondCode.sdaLow) { condTrue := !sdaSampled }
          is(CondCode.sdaHigh) { condTrue := sdaSampled }
          is(CondCode.sclHigh) { condTrue := sclSampled }
          is(CondCode.timeout) { condTrue := timeoutFlag }
          is(CondCode.notTimeout) { condTrue := !timeoutFlag }
          default { condTrue := False }
        }

        when(condTrue) {
          timeoutFlag := False
          pc := pc + 1
          goto(fetchState)
        } elsewhen (timer.io.tick && !waitTimeoutInfinite) {
          when(waitTimeoutQs === 1) {
            timeoutFlag := True
            pc := pc + 1
            goto(fetchState)
          } otherwise {
            waitTimeoutQs := waitTimeoutQs - 1
          }
        }
      }
    }

    // ---------------------------------------------- HaltWriteLo ----
    //
    // First half of the Revision word into the result ring. Address
    // is `resultBase` (the first word above program memory). On
    // success, advance to `HaltWriteHi`.
    val haltWriteLoState: State = new State {
      whenIsActive {
        io.resultWrite.valid := True
        io.resultWrite.payload.addr := U(resultBase, addrWidth bits)
        io.resultWrite.payload.data := Revision.hwLo
        when(io.resultWrite.fire) {
          goto(haltWriteHiState)
        }
      }
    }

    // ---------------------------------------------- HaltWriteHi ----
    val haltWriteHiState: State = new State {
      whenIsActive {
        io.resultWrite.valid := True
        io.resultWrite.payload.addr := U(resultBase + 1, addrWidth bits)
        io.resultWrite.payload.data := Revision.hwHi
        when(io.resultWrite.fire) {
          goto(haltWriteStatusState)
        }
      }
    }

    // ------------------------------------------ HaltWriteStatus ----
    //
    // The HALT status word always lands at `resultLimit` --- the
    // single reserved slot at the top of the ring. Format
    // (`[15:14]=11`):
    //
    //   [15:14] = tag (11 = HALT)
    //   [13]    = overflow (latched at HALT entry)
    //   [12]    = mismatchAtHalt (latched at HALT entry; final
    //             MISMATCH_FLAG value, not "ever fired")
    //   [11:8]  = status (caller code, or 0xF for engine traps)
    //   [7:0]   = reserved (=0)
    //
    // On `fire`, return to `idleState` --- the engine is now
    // available for the next `io.start`.
    val haltWriteStatusState: State = new State {
      whenIsActive {
        io.resultWrite.valid := True
        io.resultWrite.payload.addr := U(resultLimit, addrWidth bits)
        io.resultWrite.payload.data :=
          B"11" ## haltOverflowLatch.asBits ##
            haltMismatchLatch.asBits ## haltStatusLatch ##
            B(0, 8 bits)
        when(io.resultWrite.fire) {
          goto(idleState)
        }
      }
    }

    // ----------------------------------------- CaptureWrite -------
    //
    // CAPTURE record (tag `00`, 1 word):
    //
    //   [15:14] = 00
    //   [13:1]  = reserved (=0)
    //   [0]     = captured SDA value
    //
    // If the ring is full (or already overflowed) we set
    // `resultOverflow` and skip the write so the program continues
    // running --- the HALT word's `overflow` bit flags the
    // truncation. PC was already bumped in the calling
    // `emitBit`/`emitQuarter` state.
    val captureWriteState: State = new State {
      whenIsActive {
        when(!resultOverflow && resultWp <= U(recordLimit)) {
          io.resultWrite.valid := True
          io.resultWrite.payload.addr := resultWp
          io.resultWrite.payload.data :=
            B"00" ## B(0, 13 bits) ## captureValueReg.asBits
          when(io.resultWrite.fire) {
            resultWp := resultWp + 1
            goto(fetchState)
          }
        } otherwise {
          resultOverflow := True
          goto(fetchState)
        }
      }
    }

    // ----------------------------------------- MarkWriteWord0 -----
    //
    // MARK record (tag `10`, 3 words):
    //
    //   word 0: [15:14]=10 [13:8]=reserved(=0) [7:0]=label
    //   word 1: timestamp[15:0]
    //   word 2: timestamp[31:16]
    //
    // The room check happened in `decodeState`; once we are here,
    // there is room for all three words. PC bumps in word 2.
    val markWriteWord0State: State = new State {
      whenIsActive {
        io.resultWrite.valid := True
        io.resultWrite.payload.addr := resultWp
        io.resultWrite.payload.data :=
          B"10" ## B(0, 6 bits) ## instrReg(11 downto 4)
        when(io.resultWrite.fire) {
          resultWp := resultWp + 1
          goto(markWriteWord1State)
        }
      }
    }

    val markWriteWord1State: State = new State {
      whenIsActive {
        io.resultWrite.valid := True
        io.resultWrite.payload.addr := resultWp
        io.resultWrite.payload.data :=
          markTimestampLatch(15 downto 0).asBits
        when(io.resultWrite.fire) {
          resultWp := resultWp + 1
          goto(markWriteWord2State)
        }
      }
    }

    val markWriteWord2State: State = new State {
      whenIsActive {
        io.resultWrite.valid := True
        io.resultWrite.payload.addr := resultWp
        io.resultWrite.payload.data :=
          markTimestampLatch(31 downto 16).asBits
        when(io.resultWrite.fire) {
          resultWp := resultWp + 1
          pc := pc + 1
          goto(fetchState)
        }
      }
    }
  }

  // ------------------------------------------------------------------
  // `done` --- computed from the FSM state register so a new state
  // accidentally left out of an explicit `done := ...` assignment can
  // never strand the wrapper waiting on a never-rising signal.
  // ------------------------------------------------------------------

  io.done := fsm.isActive(fsm.idleState)
}
