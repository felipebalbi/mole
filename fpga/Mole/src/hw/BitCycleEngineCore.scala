package mole

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

/** The Mole bit-cycle engine --- Phase 1 complete variant.
  *
  * Decodes the full v0 ISA --- including the runtime role-switch opcode
  * ([[Opcode.setRole]]) and both target-role opcodes ([[Opcode.sampleBitOnScl]]
  * / [[Opcode.driveBitOnScl]]) --- and traps every reserved v0.5 slot to halt.
  * Drives the [[MoleBus]] at quarter-bit pacing programmable per [[BusMode]]
  * via the four [[LoadTiming]] divider registers, captures `MISMATCH_FLAG` /
  * `TIMEOUT_FLAG` / `START_FLAG` / `STOP_FLAG` per AGENTS §3.15, writes a
  * length-prefixed sequence of CAPTURE, MARK and HALT records into the result
  * ring, and traps any malformed instruction (reserved opcode, reserved cond
  * code, reserved tx_symbol, invalid `SET_BUS_MODE` wire value) to a HALT with
  * a distinguished status.
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
  *   - Result-ring readback / host visibility (Phase 2).
  *   - AGENTS §3.13 target-role PP-on-SCL lint (defense-in-depth, post-Step
  *     21).
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
  // HALT (3 words). Defaults give 2048 words; checked here so a config that
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

  // SDA output pipeline: one fabric cycle of `tHD;DAT` data-hold
  // between the FSM-side `sdaDrive*` regs and the pad.
  //
  // Per UM10204 rev 7 (NXP I2C-bus specification) the controller
  // must hold SDA stable for `tHD;DAT >= 0 ns` after SCL falls in
  // every supported I2C mode (Std, Fast, Fast+). The spec min is
  // zero but several mainstream slaves (NXP LPI2C on MCXA266,
  // FlexComm on RT685-EVK) run a strict START/STOP edge detector
  // clocked off their own input synchroniser and will mis-classify
  // a same-edge SDA-change-with-SCL-fall as a spurious START /
  // STOP, dropping the transfer mid-byte. TMP108's input glitch
  // filter is wide enough to swallow the race, which is why
  // `tmp108.moleasm` works against the engine even when
  // `i2c-soak.moleasm` does not.
  //
  // The pre-fix engine wrote `sdaDrive*` and `sclDrive*` from the
  // same `decodeState` cycle (FSM body), so SCL fell and SDA
  // changed on the same fabric `posedge clk`. Pad-skew +
  // asymmetric SDA/SCL pad timing on the slave then let SDA's
  // edge race ahead of SCL on the wire.
  //
  // The fix lives at the pad boundary, NOT inside the FSM: a
  // single-cycle pipeline reg sits between `sdaDrive*` and
  // `io.bus.sda.*`. SCL stays unpipelined. The result is that
  // SCL reaches the pad one fabric cycle ahead of SDA for every
  // possible SDA transition (EMIT_BIT bit-to-bit, EMIT_QUARTER
  // between quarters, STRETCH_SCL release, idle release) ---
  // ~41.67 ns @ 24 MHz fabric, comfortably above 0 ns in every
  // supported mode and small enough to keep `tSU;DAT` well above
  // its min too.
  //
  // Putting the pipeline at the pad boundary rather than inside
  // the FSM is deliberately *timing-friendly*: no extra mux
  // levels on the FSM's critical path, just two registered FFs
  // with no combinational logic between source and pipeline ---
  // trivially clean for nextpnr's 24 MHz timing closure on the
  // UP5K-SG48 (which already runs at ~2 % slack per the Makefile
  // seed-pin note). See `BitCycleEngineEmitBitDataHoldSim` for
  // the regression that asserts the SCL-leads-SDA ordering on
  // bit-to-bit transitions.
  //
  // The sample point for MISMATCH / CAPTURE is unaffected: those
  // read from the *observer* (`sdaSampled`), which is fed by
  // `io.bus.sda.read` (a pad input, independent of this output
  // pipeline). At default `quarterPeriodCyclesReset = 6`, Q2
  // sample fires ~24 fabric cycles after Q0 entry; the 1-cycle
  // pad-output delay plus 2-cycle 2-FF input synchroniser still
  // leaves ~21 cycles of margin.
  //
  // The contention assert below still reads the source regs (the
  // FSM's *intent*) rather than the pipelined pad outputs ---
  // catching a malformed (low && high) at the source is what we
  // want; the pipeline cannot manufacture contention that the
  // source did not commit to one cycle earlier.
  io.bus.sda.driveLow := RegNext(sdaDriveLow) init (False)
  io.bus.sda.driveHigh := RegNext(sdaDriveHigh) init (False)
  io.bus.scl.driveLow := sclDriveLow
  io.bus.scl.driveHigh := sclDriveHigh

  // Defense-in-depth for INV-BUS-NO-CONTENTION
  // (`fpga/Mole/AGENTS.md` §"Open-drain primitive: custom MoleBus").
  //
  // `SymbolDecoder` makes contention structurally impossible for every
  // (BUS_MODE, tx_symbol) pair, but this assert catches any future
  // writer that bypasses the decoder and sets both driver enables
  // directly on these registers. The pad-level assert in
  // `MoleIoBufUp5k` is the second line of defense and does NOT fire
  // under `useBlackBox = true` on silicon (SB_IO interprets the
  // illegal combination as PP-drive-high), so the engine-side check
  // is the canonical sim-time guard.
  //
  // SpinalHDL `assert(...)` in non-formal context emits a sim-only
  // check; it does NOT synthesise into the bitstream, so there is no
  // area cost on the FPGA.
  assert(
    !(sdaDriveLow && sdaDriveHigh),
    "BitCycleEngineCore: SDA bus contention " +
      "(driveLow && driveHigh both set)"
  )
  assert(
    !(sclDriveLow && sclDriveHigh),
    "BitCycleEngineCore: SCL bus contention " +
      "(driveLow && driveHigh both set)"
  )

  // ------------------------------------------------------------------
  // Engine state
  // ------------------------------------------------------------------

  /** Active bus mode --- the engine's only protocol context. One writer
    * (`SET_BUS_MODE`), two readers ([[SymbolDecoder]] and, post-Step 11, the
    * timing-divider mux). Reset value `i2c` per ROADMAP §"Bus mode register"
    * (safe default: OD release on idle bus).
    */
  val busModeReg = Reg(BusMode()) init (BusMode.i2c)

  /** Active engine role. `False` = Controller, `True` = Target. Boot default
    * taken from `cfg.role` so a program that never issues `SET_ROLE` keeps the
    * pre-Step-21 compile-time-style behaviour. Run-time mutable via
    * [[Opcode.setRole]]; both halves of the FSM (`SclWaveformGen`-paced
    * controller emit, external-SCL-paced target sample/drive) elaborate
    * unconditionally so a single bitstream can play either role.
    *
    * Deliberate departure from `fpga/Mole/AGENTS.md`'s "compile-time toggles
    * via Scala `if`" idiom: the role-selection register *must* be runtime-
    * mutable to deliver a single dual-role image (one firmware, two
    * personalities). Other `MoleConfig` toggles still follow the
    * Scala-time-`if` convention.
    *
    * Not reset on `io.start`: role is configuration state (same lifecycle as
    * `busModeReg`), not a per-run sticky flag. A program that issues `SET_ROLE`
    * once at the top carries that role across subsequent `io.start` pulses
    * until another `SET_ROLE` overrides it.
    */
  val roleReg = Reg(Bool()) init (Bool(cfg.role == EngineRole.Target))

  /** Program counter (next instruction to fetch). */
  val pc = Reg(UInt(pcWidth bits)) init (0)

  /** Latched instruction word from the last completed fetch. */
  val instrReg = Reg(Bits(Instruction.WORD_WIDTH bits)) init (0)

  /** Combinational opcode view of [[instrReg]] --- sliced directly from the top
    * 5 bits. Every other operand field in the FSM is sliced from `instrReg` by
    * hand (e.g. `instrReg(10 downto 9)` for the tx_symbol field of EMIT_BIT,
    * `instrReg(10 downto 8)` for the mode field of SET_BUS_MODE). Keeping the
    * opcode on the same convention means there is one slicing style across the
    * whole fetch path and no Bundle field-ordering surprise sitting between the
    * encoder and the decoder.
    */
  val opcode = Opcode()
  opcode.assignFromBits(instrReg(15 downto 11))

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

  // ------------------------------------------------------------------
  // Bus observer --- factored into [[BusObserver]] (an Area, not a
  // Component, so the engine's hierarchy stays flat). Exposes both
  // pure line-direction edges (sda/sclFalling, sda/sclRising) and
  // the I2C / I3C bus events (startEdge / stopEdge = SDA edge while
  // SCL is held high). The SCL-direction edges are what target-role
  // SAMPLE_BIT_ON_SCL (rising) and DRIVE_BIT_ON_SCL (falling) will
  // pace off when Step 19's FSM states land.
  //
  // The named aliases below keep the rest of the FSM bit-identical
  // to the pre-refactor source --- only the *origin* of the signals
  // changed. Target-role consumers added in the next commit will
  // read `observer.sclRising` / `observer.sclFalling` directly.
  // ------------------------------------------------------------------

  val observer = BusObserver(io.bus.sda.read, io.bus.scl.read)
  val sdaSampled = observer.sdaSampled
  val sclSampled = observer.sclSampled
  val sdaSampledPrev = observer.sdaSampledPrev
  val sclSampledPrev = observer.sclSampledPrev
  val sdaFalling = observer.sdaFalling
  val sdaRising = observer.sdaRising
  val startEdge = observer.startEdge
  val stopEdge = observer.stopEdge

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
  val waitTimeoutQs = Reg(UInt(7 bits)) init (0)
  val waitTimeoutInfinite = Reg(Bool()) init (False)
  val stretchQs = Reg(UInt(11 bits)) init (0)

  // ------------------------------------------------------------------
  // Stretch-wait countdown counter (see ROADMAP §"Stretch-aware Q2
  // entry" and docs/superpowers/specs/2026-06-02-stretch-aware-
  // emit-bit-design.md §11.6).
  //
  // 21-bit raw fabric-cycle counter loaded on entry to
  // `emitBitStretchWait` and decremented every active cycle of that
  // state. When it reaches zero the wait state HALTs with status 0xD
  // and sets TIMEOUT_FLAG + MISMATCH_FLAG. Init value is 0 because
  // the FSM never reads the reg before `onEntry` writes it. Width is
  // 21 (not 20) so the documented default `cfg.stretchTimeoutCycles
  // = 1 << 20` (~44 ms at 24 MHz) fits as a literal; the spec's
  // "20-bit" wording predates the inclusive-default decision.
  //
  // `stretchTimeoutCtrIsZero` pipelines the `=== 0` comparison so the
  // FSM's `elsewhen` reads a single registered bit instead of a
  // 21-input OR-reduction. The combinational form fans the
  // FSM-state-active mesh through the counter's clock-enable and blew
  // nextpnr's 24 MHz timing budget by ~12 ns on UP5K-SG48 across all
  // seeds. Init=True so an out-of-state read defaults to the safe
  // "already expired, don't decrement" view. The price is one extra
  // FF and one fabric cycle of timeout latency (timeout fires at N+1
  // cycles instead of N); at the 44 ms design timeout that is noise.
  // ------------------------------------------------------------------
  val stretchTimeoutCtr = Reg(UInt(21 bits)) init (U(0, 21 bits))
  val stretchTimeoutCtrIsZero = Reg(Bool()) init (True)
  stretchTimeoutCtrIsZero := stretchTimeoutCtr === 0

  // Inline pause-flag for the stretch-wait. Set True when the
  // Q1->Q2 guard observes !sclSampled in controller/OD-class
  // mode; cleared when SCL is observed rising (release) or the
  // timeout fires. Gates the timer's `enable` and the qIdx
  // advance inside `emitBitState`. Replaces an earlier
  // dedicated `emitBitStretchWait` FSM state --- adding a new
  // state widened the `fsm_stateReg` decode mux fan-in across
  // every engine sub-block consuming the FSM state register
  // (SPRAM write-data, write-addr, programReadCmd.valid, ...),
  // which cost ~5 MHz Fmax on UP5K-SG48 and prevented closing
  // the 24 MHz timing floor at any seed. Inline gate keeps the
  // FSM state count at the pre-change 17 and recovers the
  // budget. Init False so a power-on engine outside the wait
  // never spuriously pauses.
  val waitingForStretch = Reg(Bool()) init (False)

  // ------------------------------------------------------------------
  // Quarter-bit timing registers (Step 11 `LOAD_TIMING`)
  //
  // Four 9-bit divider words, one per `BUS_MODE.mode[1:0]` slot:
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
    Reg(UInt(9 bits)) init U(cfg.quarterPeriodCyclesReset - 1, 9 bits),
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
  // Loop counter registers (Step 20 --- bounded loops)
  //
  // Two independent 8-bit architectural counters, one writer
  // (`LOAD_LOOP`) and one read-modify-writer (`DEC_BRANCH`). One
  // bit of `instrReg(10)` selects the active register on either
  // opcode; the remaining `instrReg(9 downto 8)` pad stays
  // reserved (=0) per AGENTS §3.17 for a future 16-LCR widening
  // with no wire-format break.
  //
  // Reset to 0 on power-on AND on program-start in `idleState`'s
  // `io.start` handler --- same documented program-start re-arm
  // path that covers the sticky flags, result ring, and MARK
  // timestamp. A program that begins with `DEC_BRANCH` before any
  // `LOAD_LOOP` sees the wraparound (`0 -> 0xFF -> ... -> 1 -> 0`)
  // documented in ROADMAP §"Bounded loops --- LOAD_LOOP +
  // DEC_BRANCH". `DEC_BRANCH` is flag-neutral: it must not touch
  // `mismatchFlag` / `timeoutFlag` / `startFlag` / `stopFlag`.
  // ------------------------------------------------------------------

  val lcrRegs = Vec(Reg(UInt(8 bits)) init (0), 2)

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
  // Instantiated at the full 9-bit `LOAD_TIMING` divider width. The
  // reload value is combinationally muxed out of `timingRegs` per the
  // active `BUS_MODE.mode[1:0]` slot; `LOAD_TIMING` writes to those
  // regs change the next load's period without an extra opcode.
  // ------------------------------------------------------------------

  val timer = QuarterBitTimer(maxReloadValue = (1 << 9) - 1)
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
    //
    // `forceMismatch` lets a trap path that has just written
    // `mismatchFlag := True` on the same cycle override the
    // `haltMismatchLatch := mismatchFlag` sample (which would
    // otherwise see the previously-registered value, not the
    // about-to-be-written True). Used by the stretch-aware
    // EMIT_BIT trap paths (HALT 0xD). Default False preserves
    // pre-existing behaviour for every other caller.
    def enterHalt(status: Bits, forceMismatch: Bool = False): Unit = {
      haltStatusLatch := status
      haltMismatchLatch := mismatchFlag | forceMismatch
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
          // Step 20: re-arm both loop counters. Documented
          // program-start exception (see lcrRegs block).
          lcrRegs(0) := U(0, 8 bits)
          lcrRegs(1) := U(0, 8 bits)
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
            enterHalt(instrReg(10 downto 7))
          }

          // -- SET_BUS_MODE ----------------------------------------
          //
          // Field layout: [10:8]=mode, [7:0]=reserved (=0).
          //
          // Valid wire values are `0b000` (i2c), `0b001` (i3c-OD),
          // `0b110` (i3c-PP), `0b111` (hdr-DDR) per ROADMAP §"Bus
          // mode register". Anything else is a malformed-instruction
          // trap (engine-internal status `0xF`).
          is(Opcode.setBusMode) {
            val rawMode = instrReg(10 downto 8).asUInt
            when(
              rawMode === 0 || rawMode === 1 ||
                rawMode === 6 || rawMode === 7
            ) {
              val newMode = BusMode()
              newMode.assignFromBits(instrReg(10 downto 8))
              busModeReg := newMode
              pc := pc + 1
              goto(fetchState)
            } otherwise {
              enterHalt(B"1111")
            }
          }

          // -- EMIT_BIT --------------------------------------------
          //
          // Field layout: [10:9]=tx_symbol, [8:3]=reserved (=0),
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
          //
          // The wire-level `tHD;DAT` data-hold (SDA stable for at
          // least one fabric cycle after SCL falls) is enforced by
          // a dedicated one-cycle SDA output pipeline downstream
          // --- see the `io.bus.sda.*` driver block above. The
          // engine FSM keeps the simpler "latch SDA + SCL in the
          // same cycle" shape so no extra mux levels appear on
          // the critical path.
          is(Opcode.emitBit) {
            val txRaw = instrReg(10 downto 9)
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
              // quarter dwell shows the correct level. In target
              // role the engine releases SCL entirely (slaves to
              // external controller); the runtime `when(!roleReg)`
              // gates the SclWaveformGen path so a dual-role
              // bitstream collapses to "release SCL" when
              // `roleReg` says target. SclWaveformGen and the
              // symbol decoder elaborate unconditionally --- the
              // synthesis cost is one mux per SCL driver.
              val sclSymQ0 = SclWaveformGen(U(0, 2 bits))
              val sclQ0 = SymbolDecoder(sclSymQ0, busModeReg)
              when(!roleReg) {
                sclDriveLow := sclQ0.driveLow
                sclDriveHigh := sclQ0.driveHigh
              } otherwise {
                sclDriveLow := False
                sclDriveHigh := False
              }

              qIdx := 0
              timerLoad := True
              goto(emitBitState)
            }
          }

          // -- EMIT_QUARTER ----------------------------------------
          //
          // Field layout: [10:9]=sda_symbol, [8:7]=scl_symbol,
          // [6:3]=reserved (=0), [2:0]=flag triple. Either symbol
          // field set to `0b11` (reserved) traps to the
          // malformed-instruction HALT.
          //
          // The AGENTS §3.13 target-role lint ("reject
          // scl_symbol=recessive under PP class") is a host-side
          // SDK check; the engine does not yet carry a role
          // register so the defense-in-depth flatten is deferred
          // until Step 19 lands target-role helpers.
          is(Opcode.emitQuarter) {
            val sdaRaw = instrReg(10 downto 9)
            val sclRaw = instrReg(8 downto 7)
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
          // Field layout: [10:0]=n_quarters. Force SCL low for `n`
          // quarter-bit periods; leave SDA as whatever the previous
          // wire opcode left in its drivers.
          //
          // `n = 0` is a documented no-op: skip the state entirely
          // and refetch. SDK shouldn't emit it (a zero-length
          // stretch is meaningless) but the engine accepts it
          // rather than trapping --- the encoder's 11-bit operand
          // contract has no zero-rejection clause.
          //
          // On exit, SCL is *released* to recessive decoded against
          // the current `BUS_MODE` (OD class: hiz, PP class: drive
          // high). Without this release a following `WAIT_ON
          // SCL_HIGH, t` would deadlock waiting for the engine
          // itself to stop driving low.
          is(Opcode.stretchScl) {
            val nQ = instrReg(10 downto 0).asUInt
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
            val condRaw = instrReg(10 downto 7).asUInt
            when(condRaw > 9) {
              enterHalt(B"1111")
            } otherwise {
              val condCraft = CondCode()
              condCraft.assignFromBits(instrReg(10 downto 7))
              val t = instrReg(6 downto 0).asUInt
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
          // Field layout: [10:0]=addr (11-bit absolute, wire-format
          // range 0..2047 per INV-WIRE-JMP-ADDR). The host encoder
          // enforces only the wire cap; board variants with
          // `programWordCount < 2048` have a narrower runtime valid
          // range, so the engine traps when the JMP target falls at
          // or above the actual program memory.
          //
          // The trap fires only on out-of-range targets. With
          // `programWordCount = 2048` (the default), the comparison
          // is dead code --- the operand max (2047) is the legal
          // max --- but the guard stays present so smaller program
          // memories cannot silently truncate via `.resize(pcWidth)`
          // (F-FPGA-004). The 12-bit literal width is one bit wider
          // than the 11-bit operand so the `>=` comparison is
          // unambiguous at the boundary.
          is(Opcode.jmp) {
            val jmpTarget = instrReg(10 downto 0).asUInt
            when(jmpTarget.resize(12 bits) >= U(programWordCount, 12 bits)) {
              enterHalt(B"1111")
            } otherwise {
              pc := jmpTarget.resize(pcWidth)
              goto(fetchState)
            }
          }

          // -- BRANCH_ON --------------------------------------------
          //
          // Field layout: [10:7]=cond_code, [6:0]=signed PC-rel
          // offset (7-bit two's complement, ±64). On taken,
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
            val condRaw = instrReg(10 downto 7).asUInt
            when(condRaw > 9) {
              enterHalt(B"1111")
            } otherwise {
              val condCraft = CondCode()
              condCraft.assignFromBits(instrReg(10 downto 7))
              val cTrue = evalCondFlagOnly(condCraft)
              val offsetSExt =
                instrReg(6 downto 0).asSInt.resize(pcWidth)
              val offsetU = offsetSExt.asUInt
              pc := Mux(cTrue, pc + 1 + offsetU, pc + 1)
              goto(fetchState)
            }
          }

          // -- LOAD_TIMING ------------------------------------------
          //
          // Field layout: [10:9]=reg, [8:0]=divider_word. Writes
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
            val regIdx = instrReg(10 downto 9).asUInt
            val word = instrReg(8 downto 0).asUInt
            timingRegs(regIdx) := word
            pc := pc + 1
            goto(fetchState)
          }

          // -- MARK -------------------------------------------------
          //
          // Field layout: [10:3]=label, [2:0]=reserved (=0). Writes
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

          // -- LOAD_LOOP --------------------------------------------
          //
          // Field layout: [10]=reg, [9:8]=reserved (=0),
          // [7:0]=imm8. Writes the immediate into `lcrRegs(reg)`.
          // Single-cycle; no bus effect; sticky engine flags
          // untouched. Pads bits [9:8] are not validated against
          // zero --- per Instruction.scala's decode contract,
          // stray reserved bits round-trip through `decode` rather
          // than trapping. The host SDK guarantees clean encodes.
          is(Opcode.loadLoop) {
            val regIdx = instrReg(10).asUInt
            val imm = instrReg(7 downto 0).asUInt
            lcrRegs(regIdx) := imm
            pc := pc + 1
            goto(fetchState)
          }

          // -- DEC_BRANCH -------------------------------------------
          //
          // Field layout: [10]=reg, [9:8]=reserved (=0),
          // [7:0]=signed 8-bit PC-rel offset (two's complement,
          // +/-128). Semantics, per fetch:
          //   1. lcr <- lcrRegs(reg) - 1   (8-bit wrap; 0 -> 0xFF)
          //   2. if (lcr != 0) PC <- PC + 1 + offset
          //      else          PC <- PC + 1
          // Sticky engine flags are not touched --- AGENTS §3.15.
          // Single-cycle; no bus effect; the bounded-retry pattern
          // composes cleanly with a `BRANCH_ON MISMATCH ...`
          // inside the loop body because the mismatch flag survives
          // this opcode untouched.
          is(Opcode.decBranch) {
            val regIdx = instrReg(10).asUInt
            val decremented = lcrRegs(regIdx) - 1
            lcrRegs(regIdx) := decremented
            val offsetSExt =
              instrReg(7 downto 0).asSInt.resize(pcWidth)
            val offsetU = offsetSExt.asUInt
            pc := Mux(decremented =/= 0, pc + 1 + offsetU, pc + 1)
            goto(fetchState)
          }

          // -- SAMPLE_BIT_ON_SCL / DRIVE_BIT_ON_SCL (target role) --
          //
          // Legal only when `roleReg === True` (target). With dual-
          // role bitstreams the FSM `default` arm can no longer
          // cover the controller-role rejection --- both opcodes
          // always elaborate now --- so we trap explicitly here
          // when a controller-mode program reaches them. A clean
          // HALT 0xF still surfaces a malformed program the same
          // way the pre-Step-21 `default` did.
          //
          // Both arms set up the per-instruction scratch and dispatch
          // to a dedicated FSM state. The state machine handles the
          // external-SCL edge pacing.

          // SAMPLE_BIT_ON_SCL: wait for the next external SCL
          // rising edge, sample SDA, compare against expect/mask,
          // optionally capture. No bus drive.
          is(Opcode.sampleBitOnScl) {
            when(roleReg) {
              goto(sampleBitOnSclState)
            } otherwise {
              enterHalt(B"1111")
            }
          }

          // DRIVE_BIT_ON_SCL: on the next external SCL falling
          // edge, drive SDA per tx_symbol for one external-SCL-
          // clocked bit cell; concurrently, on the rising edge
          // inside that cell, sample SDA + compare. The
          // simultaneous drive + sample is what enables I3C DAA
          // arbitration --- a target driving recessive that reads
          // dominant lost the bit and sets MISMATCH_FLAG.
          // Reserved tx_symbol (0b11) traps to halt the same way
          // EMIT_BIT does; a controller-role build executing this
          // opcode also traps to halt.
          is(Opcode.driveBitOnScl) {
            val txRaw = instrReg(10 downto 9)
            when(txRaw === B"11" || !roleReg) {
              enterHalt(B"1111")
            } otherwise {
              goto(driveBitOnSclState)
            }
          }

          // -- SET_ROLE ---------------------------------------------
          //
          // Field layout: [10]=role (0=Controller, 1=Target),
          // [9:0]=reserved (=0). No bit pattern is illegal --- the
          // 10-bit reserved field is ignored on decode.
          //
          // Bus drivers are forced off before the role write so a
          // mid-program role switch leaves the bus in a clean Hi-Z
          // state regardless of which arm was driving last (see
          // Instruction.scala `SetRole` docstring). There is no
          // "must be first" enforcement --- the SDK convention is
          // to issue `SET_ROLE` near the top of every program, but
          // the engine accepts the opcode at any PC.
          is(Opcode.setRole) {
            sdaDriveLow := False
            sdaDriveHigh := False
            sclDriveLow := False
            sclDriveHigh := False
            roleReg := instrReg(10)
            pc := pc + 1
            goto(fetchState)
          }

          // -- Everything else --
          //
          // Catches the two remaining reserved v0.5 slots
          // (FLAG_CLEAR, CAPTURE_RUN) plus any of the 0x11..0x1F
          // reserved opcode slots that the host might have
          // accidentally emitted. Both have no v0 meaning. Trap
          // to halt with status 0xF so a forward-deployed program
          // that ships with a not-yet-supported opcode surfaces
          // as a clean halt rather than a runaway bus.
          //
          // (Pre-Step-21 this arm also caught controller-mode
          // SAMPLE_BIT_ON_SCL / DRIVE_BIT_ON_SCL; those now
          // elaborate unconditionally and self-trap on
          // `!roleReg`.)
          default {
            enterHalt(B"1111")
          }
        }
      }
    }

    // -------------------------------------------------- EmitBit ----
    val emitBitState: State = new State {
      whenIsActive {
        when(waitingForStretch) {
          // ---- Stretch-wait phase ---------------------------
          // Q1->Q2 guard observed !sclSampled and committed
          // the SCL recessive symbol. Timer paused (timerEnable
          // defaults False). SDA untouched (preserves tHD;DAT
          // hold). Exit on SCL release or timeout.
          when(observer.sclRising) {
            waitingForStretch := False
            timerLoad := True
            qIdx := 2
          } elsewhen (stretchTimeoutCtrIsZero) {
            timeoutFlag := True
            mismatchFlag := True
            waitingForStretch := False
            enterHalt(B"1101", forceMismatch = True)
          } otherwise {
            stretchTimeoutCtr := stretchTimeoutCtr - 1
          }
        } otherwise {
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
              // Update SCL for the new quarter. SDA stays as latched
              // on entry to this state (held for the full bit per
              // spec). Target-role: leave SCL released.
              val sclSym = SclWaveformGen(nextQ)
              val scl = SymbolDecoder(sclSym, busModeReg)
              when(!roleReg) {
                sclDriveLow := scl.driveLow
                sclDriveHigh := scl.driveHigh
              }

              // Stretch-aware Q2 entry guard (controller role only).
              // On the Q1->Q2 tick the SCL recessive symbol was just
              // committed above; if the slave is holding SCL low we
              // must NOT advance qIdx into Q2 (sample would land in
              // slave-stretched dead time). Under OD-class BUS_MODE
              // we pause inline (waitingForStretch flag); under
              // PP-class the slave is in violation and we HALT 0xD
              // immediately. qIdx update is conditional on NOT
              // entering the stretch path.
              when(qIdx === 1 && !roleReg && !observer.sclSampled) {
                when(BusModeOps.isPpClass(busModeReg)) {
                  mismatchFlag := True
                  enterHalt(B"1101", forceMismatch = True)
                } otherwise {
                  waitingForStretch := True
                  stretchTimeoutCtr :=
                    U(cfg.stretchTimeoutCycles, 21 bits)
                  // Force the pipelined zero-flag False this cycle
                  // so the wait-phase's first tick doesn't see the
                  // pre-load (0 → flag True) and HALT immediately.
                  stretchTimeoutCtrIsZero := False
                }
              } otherwise {
                qIdx := nextQ
              }
            }
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

    // ---------------------------------------- SampleBitOnScl ----
    //
    // Target-role only at the program level (the decodeState
    // SAMPLE_BIT_ON_SCL arm traps to HALT 0xF when `!roleReg`).
    // The state itself elaborates unconditionally so a single
    // dual-role bitstream can reach it after a runtime SET_ROLE
    // target; controller-only runs simply never enter it.
    //
    // Wait for the next external SCL rising edge; sample SDA at
    // that cycle (post-2FF resolved value); compare against
    // expect/mask; optionally capture. No bus drive --- we
    // slave to the controller's SCL.
    val sampleBitOnSclState: State = new State {
      whenIsActive {
        when(observer.sclRising) {
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

    // ---------------------------------------- DriveBitOnScl -----
    //
    // Target-role only at the program level (the decodeState
    // DRIVE_BIT_ON_SCL arm traps when `!roleReg`). The three
    // sub-states elaborate unconditionally; controller-only runs
    // simply never enter them.
    //
    // Three dedicated sub-states (no phase register --- a previous
    // single-state version with a `Reg(UInt(2 bits))` phase counter
    // suffered a same-cycle stale-read bug where `onEntry { phase :=
    // 0 }` did not become visible until the next cycle, but the
    // `switch` inside `whenIsActive` ran on entry and read the prior
    // value, so every bit after the first deadlocked):
    //
    //   driveBitOnSclState     : wait for SCL falling, decode
    //                            tx_symbol, drive SDA.
    //   driveBitWaitRising     : SDA driven; on SCL rising,
    //                            evaluate MASK / CAPTURE.
    //   driveBitWaitClosing    : SDA still driven through the
    //                            high half; on the next SCL
    //                            falling, release SDA, bump PC.
    //
    // SDA stays driven from the falling edge that opens the
    // cell through the falling edge that closes it. The
    // captured / compared value reflects the wired-AND SDA at
    // the rising edge --- a target driving recessive that
    // reads dominant lost the bit to another target on the
    // wire, sets MISMATCH_FLAG, and a following BRANCH_ON
    // MISMATCH routes to the lost-arbitration handler. This is
    // the I3C DAA arbitration mechanic.
    //
    // Reserved tx_symbol (0b11) is rejected in decodeState above.
    // States are declared in reverse-goto order so each forward
    // reference (`goto(driveBitWaitClosing)`, `goto(
    // driveBitWaitRising)`) resolves cleanly without `lazy val`.
    val driveBitWaitClosing: State = new State {
      whenIsActive {
        when(observer.sclFalling) {
          sdaDriveLow := False
          sdaDriveHigh := False
          pc := pc + 1
          when(instrReg(Instruction.CAPTURE_BIT)) {
            goto(captureWriteState)
          } otherwise {
            goto(fetchState)
          }
        }
      }
    }

    val driveBitWaitRising: State = new State {
      whenIsActive {
        when(observer.sclRising) {
          when(instrReg(Instruction.MASK_BIT)) {
            mismatchFlag :=
              sdaSampled =/= instrReg(Instruction.EXPECT_BIT)
          }
          when(instrReg(Instruction.CAPTURE_BIT)) {
            captureValueReg := sdaSampled
          }
          goto(driveBitWaitClosing)
        }
      }
    }

    val driveBitOnSclState: State = new State {
      onEntry {
        sdaDriveLow := False
        sdaDriveHigh := False
      }
      whenIsActive {
        when(observer.sclFalling) {
          val txRaw = instrReg(10 downto 9)
          val sdaSym = TxSymbol()
          sdaSym.assignFromBits(txRaw)
          val sda = SymbolDecoder(sdaSym, busModeReg)
          sdaDriveLow := sda.driveLow
          sdaDriveHigh := sda.driveHigh
          goto(driveBitWaitRising)
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
          B"10" ## B(0, 6 bits) ## instrReg(10 downto 3)
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
