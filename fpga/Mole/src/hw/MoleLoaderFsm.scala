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
  *   [len_lo][len_hi][word0_lo][word0_hi]...[wordN_lo][wordN_hi][crc_lo][crc_hi]
  * }}}
  *
  *   - All multi-byte fields are little-endian.
  *   - `len` is the number of 16-bit instruction words; it does not include
  *     itself or the CRC trailer.
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
  *   - `wordLo` / `wordHi` --- latch the two bytes of one instruction word;
  *     feed each into CRC.
  *   - `writeWord` --- present the assembled word on `programWrite`. Holds
  *     until SPRAM accepts.
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
  *   maximum frame length, in 16-bit words. Frames with `len` strictly greater
  *   than this are rejected.
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

    /** Outgoing program-write commands to `SpramController.loaderWrite`. */
    val programWrite = master Stream SpramWriteCmd(addrWidth)

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
  val wordLoReg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val wordHiReg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val crcLoReg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val frameLen = Reg(UInt(lenWidth bits)) init U(0, lenWidth bits)
  val wordIndex = Reg(UInt(wordIndexWidth bits)) init U(0, wordIndexWidth bits)
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
  io.programWrite.payload.addr := wordIndex.resize(addrWidth bits)
  io.programWrite.payload.data := wordHiReg ## wordLoReg

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

          when(newLen === U(0) || newLen > U(programWordCount, lenWidth bits)) {
            io.fault := True
            goto(resyncState)
          } otherwise {
            wordIndex := U(0, wordIndexWidth bits)
            goto(wordLoState)
          }
        }
      }
    }

    // ---------------- wordLo -------------------------------------------------
    val wordLoState: State = new State {
      whenIsActive {
        when(abortNow) {
          io.fault := True
          goto(resyncState)
        } elsewhen (io.rx.fire) {
          wordLoReg := io.rx.payload
          crc.io.update.valid := True
          goto(wordHiState)
        }
      }
    }

    // ---------------- wordHi -------------------------------------------------
    val wordHiState: State = new State {
      whenIsActive {
        when(abortNow) {
          io.fault := True
          goto(resyncState)
        } elsewhen (io.rx.fire) {
          wordHiReg := io.rx.payload
          crc.io.update.valid := True
          goto(writeWordState)
        }
      }
    }

    // ---------------- writeWord ----------------------------------------------
    // Offer the assembled word to SPRAM. abortNow short-circuits to resync
    // without driving programWrite, so a UART error during the stall does
    // not let one corrupt word land in SPRAM.
    val writeWordState: State = new State {
      whenIsActive {
        when(abortNow) {
          io.fault := True
          goto(resyncState)
        } otherwise {
          io.programWrite.valid := True
          when(io.programWrite.fire) {
            when(wordIndex + 1 === frameLen.resize(wordIndexWidth bits)) {
              goto(crcLoState)
            } otherwise {
              wordIndex := wordIndex + 1
              goto(wordLoState)
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
    // Ready in any state that consumes bytes: lenLo/Hi, wordLo/Hi, crcLo/Hi,
    // and resync (drop). Idle holds ready low so the first byte of a frame
    // is consumed by lenLo after the init cycle. writeWord holds ready low
    // while SPRAM may stall. acceptRx gates the whole thing.
    io.rx.ready := io.acceptRx && (
      isActive(lenLoState) ||
        isActive(lenHiState) ||
        isActive(wordLoState) ||
        isActive(wordHiState) ||
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
