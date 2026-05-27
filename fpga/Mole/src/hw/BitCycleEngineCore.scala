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
          // [2:0]=flag triple. Step 8 ignores the flag triple
          // (mismatch detection / capture land in Step 10 / 11).
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

          // -- Everything else (EMIT_QUARTER, STRETCH_SCL, WAIT_ON,
          //    JMP, BRANCH_ON, MARK, LOAD_TIMING, sample/drive,
          //    plus the 4 reserved 0xC..0xF slots) ------------------
          //
          // Step 8 traps to the same `HALT` sequence. Step 10 / 11
          // peel off the real implementations; the reserved-slot
          // trap-with-status word lands when `HALT` grows a status
          // field for it.
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
