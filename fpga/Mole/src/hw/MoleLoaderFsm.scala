package mole

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

/** Host-link loader: parses the wire-format frame on the UART RX stream and
  * writes the decoded program into the SPRAM through `loaderWrite`.
  *
  * Wire format (full reference: `WIRE_FORMAT.md`):
  *
  * {{{
  *   [len_lo][len_hi]
  *   [word0_b0][word0_b1][word0_b2][word0_b3]
  *   ...
  *   [wordN-1_b0][wordN-1_b1][wordN-1_b2][wordN-1_b3]
  *   [crc_lo][crc_hi]
  * }}}
  *
  *   - All multi-byte fields are little-endian.
  *   - `len` is the number of **32-bit** instruction words (v0.2 ISA); it
  *     does not include itself or the CRC trailer. Each word occupies four
  *     consecutive bytes (b0 = bits [7:0], b3 = bits [31:24]).
  *   - CRC is CRC-16/XMODEM over the payload only (`len` + words). The CRC
  *     trailer bytes are NOT fed into the running CRC.
  *
  * State sketch:
  *
  *   - `idle` --- waiting for any byte; CRC register held at 0. Asserts
  *     `rx.ready = False` and watches `rx.valid` so the first byte of the frame
  *     is consumed by `lenLo`, not lost to a stale CRC init. A UART error on
  *     the very first byte triggers an immediate fault + resync here, before
  *     the byte is consumed.
  *   - `lenLo` / `lenHi` --- latch the two length bytes; feed each into CRC.
  *     `lenHi` rejects `len == 0` or `len > programWordCount`.
  *   - `wordB0` / `wordB1` / `wordB2` / `wordB3` --- latch the four bytes of
  *     one 32-bit instruction word (little-endian); feed each into CRC.
  *   - `writeWord` --- present the assembled 32-bit word on `programWrite`.
  *     Holds until SPRAM accepts.
  *   - `crcLo` / `crcHi` --- latch the two trailer bytes. **Not fed into CRC.**
  *     `crcHi` compares `(crcHi ## crcLo)` against `crc.io.value`. On match:
  *     `loaded` pulses, return to `idle`. On mismatch: `fault` pulses, go to
  *     `resync`.
  *   - `resync` --- drop every incoming byte until `uRxRaw` has been
  *     continuously high for `idleGapCycles` fabric cycles. CRC register held
  *     at 0 throughout. Then return to `idle`.
  *
  * Error handling: `rxFramingError` / `rxParityError` / `rxOverrun` pulse for
  * one cycle alongside `rx.valid` (per UartRx). Every consuming state checks
  * `abortNow = errorLatch || (rxErr && rx.valid)` BEFORE doing any byte work,
  * so an erroring byte is never latched, never CRC-fed, never acted on; we
  * always fault and head to resync directly. The latch covers the writeWord
  * stall window where the error pulse may already have disappeared by the time
  * we leave the stall.
  *
  * `uRxRaw` is an asynchronous chip pin. The loader synchronises it internally
  * with a 2-FF chain so resync's idle-gap counter sees a clean domain-local
  * signal.
  *
  * Back-pressure / gating: `acceptRx` is the gate from the top-level phase FSM.
  * While `acceptRx = False` the loader holds `rx.ready` low and, if `acceptRx`
  * drops mid-frame, faults + heads to resync to keep parser state from drifting
  * across externally-driven phase changes.
  *
  * @param programWordCount
  *   maximum frame length, in **32-bit** words. Frames with `len` strictly
  *   greater than this are rejected. Mirror of `cfg.programWordCount` and
  *   the host-side `mole_abi::MAX_PROGRAM_WORDS`.
  * @param addrWidth
  *   width of `programWrite.payload.addr`. Matches `SpramController.addrWidth`.
  *   Must be wide enough for `programWordCount - 1`.
  * @param idleGapCycles
  *   number of consecutive fabric cycles `uRxRaw` (synchronised) must remain
  *   high before `resync` returns to `idle`. The plan's recommendation is
  *   `20 * fabricCyclesPerUartBit` (= 2 UART byte times of clean idle).
  */
case class MoleLoaderFsm(
    programWordCount: Int,
    addrWidth: Int,
    idleGapCycles: Int
) extends Component {

  require(
    programWordCount >= 1,
    s"programWordCount=$programWordCount must be >= 1"
  )
  require(
    addrWidth >= log2Up(programWordCount),
    s"addrWidth=$addrWidth too narrow for programWordCount=$programWordCount"
  )
  require(idleGapCycles >= 1, s"idleGapCycles=$idleGapCycles must be >= 1")

  val lenWidth: Int = 16
  // Word index counts 0 .. programWordCount-1; compared against frameLen which
  // can equal programWordCount. log2Up(N+1) covers the worst case.
  val wordIndexWidth: Int = log2Up(programWordCount + 1)
  val idleCounterWidth: Int = log2Up(idleGapCycles + 1)

  val io = new Bundle {

    /** Byte stream from `UartRx.io.payload`. The loader holds `ready` low in
      * `idle` and `writeWord`, and any time `acceptRx` is low.
      */
    val rx = slave Stream Bits(8 bits)

    /** Single-cycle pulses from `UartRx`. They pulse alongside
      * `rx.payload.valid`. Their effect is unified into `abortNow` so an
      * erroring byte never advances the parser state.
      */
    val rxFramingError = in Bool ()
    val rxParityError = in Bool ()
    val rxOverrun = in Bool ()

    /** Raw RX line, sampled directly off the chip pin. The loader passes it
      * through an internal 2-FF synchroniser before using it in the resync
      * idle-gap counter.
      */
    val uRxRaw = in Bool ()

    /** Gate from the top-level phase FSM. False = the loader is closed (e.g.
      * the engine is running or draining); `rx.ready` stays low. A drop to
      * False mid-frame triggers a fault + resync.
      */
    val acceptRx = in Bool ()

    /** Outgoing program-write commands to `SpramController.loaderWrite`.
      *
      * Data is **32-bit** (v0.2 ISA): the loader assembles one instruction
      * word from four UART bytes (`b3 ## b2 ## b1 ## b0`).
      */
    val programWrite = master Stream SpramWriteCmd(addrWidth, dataWidth = 32)

    /** Length of the most recently loaded frame, in 32-bit words. Holds
      * across the `loaded` pulse and `running`/`draining` phases so the
      * pipeline's `programLength` input has a stable value. Updated when
      * `lenHi` accepts the second length byte; cleared on `idle` re-entry
      * is intentionally **not** done so the running engine keeps seeing
      * the frame it was loaded with.
      */
    val programLength = out UInt (wordIndexWidth bits)

    /** Single-cycle pulse: a frame just finished with CRC match. */
    val loaded = out Bool ()

    /** Single-cycle pulse: a frame was rejected. */
    val fault = out Bool ()

    /** Level: a frame is currently being received. */
    val pendingFrame = out Bool ()

    /** Level: loader is in `resync` waiting for the idle gap. */
    val inResync = out Bool ()
  }

  // 2-FF synchroniser on the raw RX pin so the resync counter sees a
  // domain-local signal. init=True because UART idles high.
  val uRxRawSync: Bool = BufferCC(io.uRxRaw, init = True)

  // Combinational error signal: any of the three pulses.
  val rxErr: Bool = io.rxFramingError || io.rxParityError || io.rxOverrun

  // Latched error: covers the writeWord stall window (or any future stall
  // longer than one cycle) where the rxErr pulse may have disappeared by
  // the time we revisit a consuming state.
  val errorLatch = RegInit(False)

  // Abort right now: either a fresh error pulse on a valid byte, or a
  // previously-latched error.
  val abortNow: Bool = errorLatch || (rxErr && io.rx.valid)

  // Frame-state registers.
  val lenLoReg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val lenHiReg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val wordB0Reg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val wordB1Reg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val wordB2Reg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val wordB3Reg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val crcLoReg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val frameLen = Reg(UInt(lenWidth bits)) init U(0, lenWidth bits)
  val programLengthReg =
    Reg(UInt(wordIndexWidth bits)) init U(0, wordIndexWidth bits)
  val wordIndex = Reg(UInt(wordIndexWidth bits)) init U(0, wordIndexWidth bits)
  // Pre-registered "the word about to be written is the last word of the
  // frame" predicate. Spelt out as a Reg, not a combinational
  // `wordIndex + 1 === frameLen`, because the latter is a wide add +
  // equality chain that costs Fmax on the UP5K. Pre-registering moves the
  // comparator out of the FSM's same-cycle decision and into a 1-bit Reg
  // read.
  //
  // Updated in `wordB3State` on the last-byte UART fire (one byte per
  // ~10 baud-cycles --- comparator has plenty of time to settle on its
  // own D-input net), so the SPRAM-paced `programWrite.fire` in
  // `writeWordState` only reads the registered bit.
  //
  // `resyncState.onEntry` does NOT clear this Reg; it's always overwritten
  // on the next `wordB3` fire before being read by writeWordState.
  val isLastWord = Reg(Bool()) init (False)
  val idleCounter =
    Reg(UInt(idleCounterWidth bits)) init U(0, idleCounterWidth bits)

  // CRC accumulator instance. Defaults are overridden inside the FSM.
  val crc = Crc16Xmodem()
  crc.io.init := False
  crc.io.update.valid := False
  crc.io.update.payload := io.rx.payload

  // Pulse outputs default low; states drive them high for one cycle.
  io.loaded := False
  io.fault := False

  // programWrite defaults; writeWord overrides.
  io.programWrite.valid := False
  // Body words land at SPRAM[0..N-1]. The preamble words (indices
  // 0..PREAMBLE_WORDS-1) are consumed from the UART, fed into CRC, but
  // NOT written to SPRAM --- the engine fetches from PC=0 and would
  // otherwise execute the magic word as an instruction.
  val bodyAddr =
    (wordIndex - U(Instruction.PREAMBLE_WORDS, wordIndexWidth bits))
      .resize(addrWidth bits)
  io.programWrite.payload.addr := bodyAddr
  // Assemble 32-bit word from the four byte registers, little-endian.
  io.programWrite.payload.data := wordB3Reg ## wordB2Reg ## wordB1Reg ## wordB0Reg

  io.programLength := programLengthReg

  val fsm = new StateMachine {

    // ---------------- idle ---------------------------------------------------
    // Frame boundary. CRC held at 0 by io.init. We do NOT consume bytes
    // here (rx.ready = False); the first byte of the frame is handed off
    // to lenLoState which consumes it. This avoids the "init wins over
    // update" same-cycle collision on Crc16Xmodem. We DO peek at rxErr
    // here so a corrupt first byte never enters the frame.
    val idleState: State = new State with EntryPoint {
      whenIsActive {
        crc.io.init := True
        when(io.rx.valid && io.acceptRx) {
          when(rxErr) {
            io.fault := True
            goto(resyncState)
          } otherwise {
            goto(lenLoState)
          }
        }
      }
    }

    // ---------------- lenLo --------------------------------------------------
    val lenLoState: State = new State {
      whenIsActive {
        when(abortNow) {
          io.fault := True
          goto(resyncState)
        } elsewhen (io.rx.fire) {
          lenLoReg := io.rx.payload
          crc.io.update.valid := True
          goto(lenHiState)
        }
      }
    }

    // ---------------- lenHi --------------------------------------------------
    // Latch lenHi; compute and validate the full 16-bit length.
    val lenHiState: State = new State {
      whenIsActive {
        when(abortNow) {
          io.fault := True
          goto(resyncState)
        } elsewhen (io.rx.fire) {
          lenHiReg := io.rx.payload
          crc.io.update.valid := True

          val newLen = (io.rx.payload ## lenLoReg).asUInt
          frameLen := newLen
          // programLength is the BODY word count (= total - preamble),
          // since the loader strips preamble before writing to SPRAM.
          // The engine fetches PC=0..bodyLen-1.
          programLengthReg :=
            (newLen - U(Instruction.PREAMBLE_WORDS, lenWidth bits))
              .resize(wordIndexWidth bits)

          when(newLen < U(Instruction.PREAMBLE_WORDS + 1, lenWidth bits) ||
            newLen > U(programWordCount + Instruction.PREAMBLE_WORDS, lenWidth bits)) {
            io.fault := True
            goto(resyncState)
          } otherwise {
            wordIndex := U(0, wordIndexWidth bits)
            goto(wordB0State)
          }
        }
      }
    }

    // ---------------- wordB0 (LSB) -------------------------------------------
    val wordB0State: State = new State {
      whenIsActive {
        when(abortNow) {
          io.fault := True
          goto(resyncState)
        } elsewhen (io.rx.fire) {
          wordB0Reg := io.rx.payload
          crc.io.update.valid := True
          goto(wordB1State)
        }
      }
    }

    // ---------------- wordB1 -------------------------------------------------
    val wordB1State: State = new State {
      whenIsActive {
        when(abortNow) {
          io.fault := True
          goto(resyncState)
        } elsewhen (io.rx.fire) {
          wordB1Reg := io.rx.payload
          crc.io.update.valid := True
          goto(wordB2State)
        }
      }
    }

    // ---------------- wordB2 -------------------------------------------------
    val wordB2State: State = new State {
      whenIsActive {
        when(abortNow) {
          io.fault := True
          goto(resyncState)
        } elsewhen (io.rx.fire) {
          wordB2Reg := io.rx.payload
          crc.io.update.valid := True
          goto(wordB3State)
        }
      }
    }

    // ---------------- wordB3 (MSB) -------------------------------------------
    //
    // Latch the MSB byte into wordB3Reg so writeWordState's data path is
    // stable across any SPRAM back-pressure (the loader is the lowest-
    // priority writer in SpramController.arbitration; back-pressure is
    // possible if the engine or drainer happens to be reading at the
    // same cycle).
    //
    // The pre-registered isLastWord predicate is computed here, paced by
    // the slow UART byte arrival, so writeWordState's same-cycle SPRAM-
    // fire decision is a 1-bit Reg read instead of a wide add+equality.
    val wordB3State: State = new State {
      whenIsActive {
        when(abortNow) {
          io.fault := True
          goto(resyncState)
        } elsewhen (io.rx.fire) {
          wordB3Reg := io.rx.payload
          crc.io.update.valid := True
          isLastWord := (wordIndex + 1) === frameLen.resize(
            wordIndexWidth bits
          )
          goto(writeWordState)
        }
      }
    }

    // ---------------- writeWord ----------------------------------------------
    // Offer the assembled word to SPRAM. For the first PREAMBLE_WORDS
    // words (MAGIC, body_len) we **skip the SPRAM write entirely** ---
    // they were just consumed for CRC + length parsing and are not
    // executable instructions. Body words (wordIndex >= PREAMBLE_WORDS)
    // are written to SPRAM at `bodyAddr = wordIndex - PREAMBLE_WORDS`
    // so body[0] lands at SPRAM[0].
    //
    // For preamble words, we just advance state without firing
    // programWrite (no SPRAM cycle). For body words, the standard
    // valid/ready handshake holds the word until SPRAM accepts.
    val writeWordState: State = new State {
      whenIsActive {
        val isPreamble =
          wordIndex < U(Instruction.PREAMBLE_WORDS, wordIndexWidth bits)
        when(isPreamble) {
          // Skip SPRAM write; advance immediately.
          when(isLastWord) {
            goto(crcLoState)
          } otherwise {
            wordIndex := wordIndex + 1
            goto(wordB0State)
          }
        } otherwise {
          io.programWrite.valid := True
          when(io.programWrite.fire) {
            when(isLastWord) {
              goto(crcLoState)
            } otherwise {
              wordIndex := wordIndex + 1
              goto(wordB0State)
            }
          }
        }
      }
    }

    // ---------------- crcLo --------------------------------------------------
    // Trailer bytes are NOT fed into the running CRC, but they are still
    // checked for UART integrity.
    val crcLoState: State = new State {
      whenIsActive {
        when(abortNow) {
          io.fault := True
          goto(resyncState)
        } elsewhen (io.rx.fire) {
          crcLoReg := io.rx.payload
          goto(crcHiState)
        }
      }
    }

    // ---------------- crcHi --------------------------------------------------
    val crcHiState: State = new State {
      whenIsActive {
        when(abortNow) {
          io.fault := True
          goto(resyncState)
        } elsewhen (io.rx.fire) {
          val expected = (io.rx.payload ## crcLoReg).asUInt
          when(crc.io.value.asUInt === expected) {
            io.loaded := True
            goto(idleState)
          } otherwise {
            io.fault := True
            goto(resyncState)
          }
        }
      }
    }

    // ---------------- resync -------------------------------------------------
    // Drop incoming bytes and wait for a clean idle gap before re-entering
    // idle. CRC is held at 0 throughout so the next frame starts fresh.
    val resyncState: State = new State {
      onEntry {
        idleCounter := U(0, idleCounterWidth bits)
      }
      whenIsActive {
        crc.io.init := True
        when(!uRxRawSync) {
          idleCounter := U(0, idleCounterWidth bits)
        } elsewhen (idleCounter < U(idleGapCycles - 1, idleCounterWidth bits)) {
          idleCounter := idleCounter + 1
        } otherwise {
          goto(idleState)
        }
      }
    }

    // ---------------- rx.ready -----------------------------------------------
    // Ready in any state that consumes bytes: lenLo/Hi, wordB0..B3, crcLo/Hi,
    // and resync (drop). Idle holds ready low so the first byte of a frame
    // is consumed by lenLo after the init cycle. writeWord holds ready low
    // while SPRAM may stall. acceptRx gates the whole thing.
    io.rx.ready := io.acceptRx && (
      isActive(lenLoState) ||
        isActive(lenHiState) ||
        isActive(wordB0State) ||
        isActive(wordB1State) ||
        isActive(wordB2State) ||
        isActive(wordB3State) ||
        isActive(crcLoState) ||
        isActive(crcHiState) ||
        isActive(resyncState)
    )

    // ---------------- errorLatch driver --------------------------------------
    // Cleared in idle / resync; set on any rxErr pulse during a payload
    // state. Single conditional chain so SpinalHDL has one unambiguous
    // driver.
    when(isActive(idleState) || isActive(resyncState)) {
      errorLatch := False
    } elsewhen (rxErr) {
      errorLatch := True
    }

    // ---------------- acceptRx-drop catch-all --------------------------------
    // External abort: if the phase FSM closes the loader mid-frame, fault
    // and head to resync so the parser cannot resume a stale half-frame
    // when the gate re-opens.
    always {
      when(!io.acceptRx && !isActive(idleState) && !isActive(resyncState)) {
        io.fault := True
        goto(resyncState)
      }
    }

    // ---------------- status outputs -----------------------------------------
    io.pendingFrame := !isActive(idleState) && !isActive(resyncState)
    io.inResync := isActive(resyncState)
  }
}
