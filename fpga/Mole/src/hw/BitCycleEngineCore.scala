package mole

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

/** The Mole bit-cycle engine --- Step 8 minimal variant.
  *
  * This is the smallest engine that satisfies the Phase 1 milestone: fetch from
  * program memory, decode `EMIT_BIT` / `SET_BUS_MODE` / `HALT`, drive the
  * [[MoleBus]] at quarter-bit pacing, and emit the 32-bit [[Revision]] word
  * into the result ring when the program halts. Every other v0 opcode (the
  * eight added in Step 7's encoder) is recognised by the decode mux and traps
  * to the same `HALT` sequence so a stray opcode cannot run away with the bus;
  * the real implementations land in Steps 10 / 11.
  *
  * ==Architecture (one paragraph)==
  *
  * One `StateMachine` walks `Idle → Fetch → FetchWait → Decode → (Execute) →
  * Fetch` with a parallel `Halt` exit that pushes [[Revision.wordLo]] and
  * [[Revision.wordHi]] into the result ring and returns to `Idle`. The
  * `EMIT_BIT` execute state is the only place the bus is driven, and only at
  * quarter-bit boundaries announced by [[QuarterBitTimer]]. The bus pads come
  * from `Reg(Bool())`s at component scope so every transition is a single
  * registered edge per `fpga/Mole/AGENTS.md` §"Bus-shaped FSM idiom".
  *
  * ==Bus driver model==
  *
  * `sda*` and `scl*` driver regs hold whatever value was last latched for them.
  * Between bits (during `Fetch` / `FetchWait` / `Decode`) the regs are not
  * touched, so SCL stays at its `Q3=recessive` value through fetch overhead ---
  * which is what an I3C / I2C receiver expects (SCL high between bits). On
  * entry to `Idle` (after `HALT`), the regs are forced to all-off so the bus
  * releases cleanly.
  *
  * ==Quarter-bit pacing==
  *
  * The shared [[QuarterBitTimer]] is loaded on entry to `EMIT_BIT` and
  * `enable`d while we are in the execute state. Each tick advances `qIdx` (`0 →
  * 1 → 2 → 3`); on the `Q3` tick we move on (PC++ and refetch). Updates to the
  * SCL register happen on the cycle the timer ticks; updates to the SDA
  * register only on entry to `EMIT_BIT` (SDA is held for the whole bit by
  * spec).
  *
  * ==What is deliberately *not* implemented yet==
  *
  *   - `EMIT_QUARTER`, `STRETCH_SCL`, `WAIT_ON` (Step 10).
  *   - `JMP`, `BRANCH_ON`, `MARK`, `LOAD_TIMING` (Step 11).
  *   - `SAMPLE_BIT_ON_SCL`, `DRIVE_BIT_ON_SCL` (Phase 4 / Step 19).
  *   - Result-ring write-pointer management. Step 8 writes the two `REVISION`
  *     halves to fixed addresses `programWordCount` and `programWordCount + 1`;
  *     the proper ring pointer lands with `MARK` in Step 11.
  *   - Reserved opcode trap with status code in the `HALT` word. Step 8 traps
  *     any unknown opcode to the same Revision-then-Idle sequence with no
  *     diagnostic.
  *
  * ==IO bundle==
  *
  * The engine speaks one master read port and one master write port to the
  * [[SpramController]], plus the [[MoleBus]] and a `start` / `done` pair. The
  * wrapper in Phase 2 will sit between the UART loader and this engine; for
  * Step 8 a sim drives `start` directly after pre-loading program memory.
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

  /** First result-ring word address (the `HALT` Revision is written here and at
    * `+1`).
    */
  val resultBase: Int = programWordCount

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
  // Quarter-bit timer
  //
  // Instantiated at the full 10-bit `LOAD_TIMING` divider width so the
  // same block survives unchanged into Step 11. Step 8 always loads
  // it with `quarterPeriodCyclesReset - 1`.
  // ------------------------------------------------------------------

  val timer = QuarterBitTimer(maxReloadValue = (1 << 10) - 1)
  val timerLoad = Bool()
  val timerEnable = Bool()
  timerLoad := False
  timerEnable := False
  timer.io.reload := U(
    cfg.quarterPeriodCyclesReset - 1,
    timer.counterWidth bits
  )
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
          is(Opcode.halt) {
            goto(haltWriteLoState)
          }

          // -- SET_BUS_MODE ----------------------------------------
          //
          // Field layout: [11:9]=mode, [8:0]=reserved (=0).
          // Latch the mode reg directly from the 3-bit wire value.
          is(Opcode.setBusMode) {
            val newMode = BusMode()
            newMode.assignFromBits(instrReg(11 downto 9))
            busModeReg := newMode
            pc := pc + 1
            goto(fetchState)
          }

          // -- EMIT_BIT --------------------------------------------
          //
          // Field layout: [11:10]=tx_symbol, [9:3]=reserved (=0),
          // [2:0]=flag triple. Step 10 wires the `mask`/`expect`
          // halves into `MISMATCH_FLAG` (the sample point is
          // inside `emitBitState`, at the Q2→Q3 transition). The
          // `capture` bit lands in Step 11 alongside the result-
          // ring write pointer.
          //
          // Latch SDA from the bitstream's tx_symbol (held for all
          // 4 quarters). Latch SCL for Q0 here, then advance it on
          // each timer tick inside `emitBitState`.
          is(Opcode.emitBit) {
            val sdaSym = TxSymbol()
            sdaSym.assignFromBits(instrReg(11 downto 10))

            val sda = SymbolDecoder(sdaSym, busModeReg)
            sdaDriveLow := sda.driveLow
            sdaDriveHigh := sda.driveHigh

            // Q0 = dominant per SclWaveformGen --- pre-compute and
            // latch into the SCL regs so the very first quarter
            // dwell shows the correct level.
            val sclSymQ0 = SclWaveformGen(U(0, 2 bits))
            val sclQ0 = SymbolDecoder(sclSymQ0, busModeReg)
            sclDriveLow := sclQ0.driveLow
            sclDriveHigh := sclQ0.driveHigh

            qIdx := 0
            timerLoad := True
            goto(emitBitState)
          }

          // -- EMIT_QUARTER ----------------------------------------
          //
          // Field layout: [11:10]=sda_symbol, [9:8]=scl_symbol,
          // [7:3]=reserved (=0), [2:0]=flag triple. Both SDA and
          // SCL come from the bitstream; the state dwells for
          // exactly one quarter and then refetches.
          //
          // The AGENTS §3.13 target-role lint ("reject
          // scl_symbol=recessive under PP class") is a host-side
          // SDK check; the engine does not yet carry a role
          // register so the defense-in-depth flatten is deferred
          // until Step 19 lands target-role helpers.
          is(Opcode.emitQuarter) {
            val sdaSym = TxSymbol()
            sdaSym.assignFromBits(instrReg(11 downto 10))
            val sclSym = TxSymbol()
            sclSym.assignFromBits(instrReg(9 downto 8))

            val sda = SymbolDecoder(sdaSym, busModeReg)
            val scl = SymbolDecoder(sclSym, busModeReg)
            sdaDriveLow := sda.driveLow
            sdaDriveHigh := sda.driveHigh
            sclDriveLow := scl.driveLow
            sclDriveHigh := scl.driveHigh

            timerLoad := True
            goto(emitQuarterState)
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
          // (sticky for the BRANCH_ON in Step 11 to read).
          is(Opcode.waitOn) {
            val condRaw = instrReg(11 downto 8).asUInt
            when(condRaw > 9) {
              goto(haltWriteLoState)
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

          // -- Everything else (JMP, BRANCH_ON, MARK, LOAD_TIMING,
          //    SAMPLE_BIT_ON_SCL, DRIVE_BIT_ON_SCL, plus the 4
          //    reserved v0.5 slots) -------------------------------
          //
          // Step 10 owns EMIT_QUARTER / STRETCH_SCL / WAIT_ON above;
          // the rest land in Step 11 (control flow + bookkeeping)
          // and Step 19 (target-role helpers). Until then they fall
          // through to the same HALT trap as Step 8.
          default {
            goto(haltWriteLoState)
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
          when(qIdx === 2 && instrReg(Instruction.MASK_BIT)) {
            mismatchFlag := sdaSampled =/= instrReg(Instruction.EXPECT_BIT)
          }

          when(qIdx === 3) {
            // Bit complete. PC++ and refetch. SDA / SCL regs keep
            // their Q3 values until the next instruction overrides
            // them --- which is exactly the "SCL stays high between
            // bits" contract.
            pc := pc + 1
            goto(fetchState)
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
    // the quarter) we run the same `mask`/`expect` compare as
    // EMIT_BIT against the synchronised SDA sample, then PC++.
    val emitQuarterState: State = new State {
      whenIsActive {
        timerEnable := True
        when(timer.io.tick) {
          when(instrReg(Instruction.MASK_BIT)) {
            mismatchFlag := sdaSampled =/= instrReg(Instruction.EXPECT_BIT)
          }
          pc := pc + 1
          goto(fetchState)
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
          goto(idleState)
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
