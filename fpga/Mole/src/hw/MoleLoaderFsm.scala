package mole

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

/** Host-link loader: parses the v0.2 wire-format frame on the UART RX
  * stream and writes the decoded program body into the SPRAM through
  * `loaderWrite`.
  *
  * ==v0.2 wire format== (full reference: `WIRE_FORMAT.md`):
  *
  * {{{
  *   [magic_b0, magic_b1, magic_b2, magic_b3]   ← 0x0002_4D4C, 4B LE
  *   [len_b0,   len_b1,   len_b2,   len_b3  ]   ← N = body word count, 4B LE
  *   [word0_b0, word0_b1, word0_b2, word0_b3]   ← body[0], 4B LE
  *   ...
  *   [wordN-1_b0, ..., wordN-1_b3]              ← body[N-1]
  *   [crc_lo, crc_hi]                           ← CRC-16/XMODEM, 2B LE
  * }}}
  *
  *   - MAGIC and LEN together are the **8-byte preamble** consumed by
  *     this loader to validate the frame and learn the body size. They
  *     are NOT written to SPRAM.
  *   - The body is N 32-bit instruction words that land at SPRAM[0..N-1].
  *     The engine fetches from PC=0; the first body word IS the first
  *     instruction the engine runs.
  *   - CRC-16/XMODEM is computed over MAGIC + LEN + body bytes. The CRC
  *     trailer bytes themselves are NOT fed into the running CRC.
  *
  * ==State sketch==
  *
  *   - `idle` --- waiting for any byte; CRC register held at 0. Asserts
  *     `rx.ready = False` and watches `rx.valid` so the first byte of the
  *     frame is consumed by `magicB0`, not lost to a stale CRC init.
  *   - `magicB0` / `magicB1` / `magicB2` / `magicB3` --- latch the 4 magic
  *     bytes (LE); feed each into CRC. On the last byte, verify
  *     `(magicB3 ## magicB2 ## magicB1 ## magicB0) === MAGIC`. Mismatch
  *     → fault + resync.
  *   - `lenB0` / `lenB1` / `lenB2` / `lenB3` --- latch the 4 length
  *     bytes; feed each into CRC. On the last byte, validate
  *     `len ∈ [1, programWordCount]` and stash `programLengthReg`.
  *   - `wordB0` / `wordB1` / `wordB2` / `wordB3` --- latch the four bytes
  *     of one body word; feed each into CRC. Pre-compute `isLastWord` in
  *     `wordB3` to avoid a wide compare in writeWord.
  *   - `writeWord` --- present the assembled 32-bit body word on
  *     `programWrite` at address `wordIndex`. Holds until SPRAM accepts.
  *   - `crcLo` / `crcHi` --- latch the two trailer bytes (NOT fed into
  *     CRC). On match: `loaded` pulses, return to `idle`. On mismatch:
  *     `fault` pulses, go to `resync`.
  *   - `resync` --- drop every incoming byte until `uRxRaw` has been
  *     continuously high for `idleGapCycles` fabric cycles. CRC held at 0
  *     throughout. Then return to `idle`.
  *
  * Error handling: `rxFramingError` / `rxParityError` / `rxOverrun` pulse
  * for one cycle alongside `rx.valid` (per UartRx). Every consuming state
  * checks `abortNow = errorLatch || (rxErr && rx.valid)` BEFORE doing any
  * byte work, so an erroring byte is never latched, never CRC-fed, never
  * acted on; we always fault and head to resync directly. The latch
  * covers the writeWord stall window where the error pulse may already
  * have disappeared by the time we leave the stall.
  *
  * `uRxRaw` is an asynchronous chip pin. The loader synchronises it
  * internally with a 2-FF chain so resync's idle-gap counter sees a clean
  * domain-local signal.
  *
  * Back-pressure / gating: `acceptRx` is the gate from the top-level phase
  * FSM. While `acceptRx = False` the loader holds `rx.ready` low and, if
  * `acceptRx` drops mid-frame, faults + heads to resync to keep parser
  * state from drifting across externally-driven phase changes.
  *
  * @param programWordCount
  *   maximum body length in **32-bit** words. Frames with `len` strictly
  *   greater than this are rejected. Mirror of `cfg.programWordCount` and
  *   the host-side `mole_abi::MAX_PROGRAM_WORDS`.
  * @param addrWidth
  *   width of `programWrite.payload.addr`. Matches `SpramController.addrWidth`.
  *   Must be wide enough for `programWordCount - 1`.
  * @param idleGapCycles
  *   number of consecutive fabric cycles `uRxRaw` (synchronised) must
  *   remain high before `resync` returns to `idle`. The plan's
  *   recommendation is `20 * fabricCyclesPerUartBit` (= 2 UART byte times
  *   of clean idle).
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

  val lenWidth: Int = 32
  // Word index counts 0 .. programWordCount-1; compared against frameLen
  // which can equal programWordCount. log2Up(N+1) covers the worst case.
  val wordIndexWidth: Int = log2Up(programWordCount + 1)
  val idleCounterWidth: Int = log2Up(idleGapCycles + 1)

  val io = new Bundle {

    /** Byte stream from `UartRx.io.payload`. */
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
      * the engine is running or draining); `rx.ready` stays low.
      */
    val acceptRx = in Bool ()

    /** Outgoing program-write commands to `SpramController.loaderWrite`. Data
      * is **32-bit** (v0.2 ISA). Address is the **body** word index `0..N-1`
      * (the loader does not write the MAGIC/LEN preamble to SPRAM).
      */
    val programWrite = master Stream SpramWriteCmd(addrWidth, dataWidth = 32)

    /** Body length of the most recently loaded frame, in 32-bit words. Stable
      * across the `loaded` pulse so EnginePipeline.programLength has a settled
      * value when engineStart asserts.
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

  // Mirror of `mole_abi::MAGIC`. Must stay in sync with the host frame
  // builder (`mole-asm/src/frame.rs`).
  val MAGIC: BigInt = BigInt(0x0002_4d4cL)

  // 2-FF synchroniser on the raw RX pin so the resync counter sees a
  // domain-local signal. init=True because UART idles high.
  val uRxRawSync: Bool = BufferCC(io.uRxRaw, init = True)

  // Combinational error signal: any of the three pulses.
  val rxErr: Bool = io.rxFramingError || io.rxParityError || io.rxOverrun

  // Latched error: covers the writeWord stall window where the rxErr
  // pulse may have disappeared by the time we revisit a consuming state.
  val errorLatch = RegInit(False)

  // Abort right now.
  val abortNow: Bool = errorLatch || (rxErr && io.rx.valid)

  // Frame-state registers.
  val magicB0Reg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val magicB1Reg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val magicB2Reg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val lenB0Reg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val lenB1Reg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val lenB2Reg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val wordB0Reg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val wordB1Reg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val wordB2Reg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val wordB3Reg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val crcLoReg = Reg(Bits(8 bits)) init B(0, 8 bits)
  val frameLen = Reg(UInt(lenWidth bits)) init U(0, lenWidth bits)
  val programLengthReg =
    Reg(UInt(wordIndexWidth bits)) init U(0, wordIndexWidth bits)
  val wordIndex = Reg(UInt(wordIndexWidth bits)) init U(0, wordIndexWidth bits)
  // Pre-registered "this word is the last body word" predicate.
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
  io.programWrite.payload.addr := wordIndex.resize(addrWidth bits)
  // Assemble 32-bit word from the four body byte registers, little-endian.
  io.programWrite.payload.data := wordB3Reg ## wordB2Reg ## wordB1Reg ## wordB0Reg

  io.programLength := programLengthReg

  val fsm = new StateMachine {

    // ---------------- idle ---------------------------------------------------
    // Frame boundary. CRC held at 0 by io.init. We do NOT consume bytes
    // here (rx.ready = False); the first byte of the frame is handed off
    // to magicB0State which consumes it. This avoids the "init wins over
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
            goto(magicB0State)
          }
        }
      }
    }

    // ---------------- magicB0..B3 -------------------------------------------
    // Consume the 4-byte MAGIC preamble. Verify on the last byte:
    // (b3 ## b2 ## b1 ## b0) must equal MAGIC = 0x0002_4D4C.
    val magicB0State: State = new State {
      whenIsActive {
        when(abortNow) {
          io.fault := True
          goto(resyncState)
        } elsewhen (io.rx.fire) {
          magicB0Reg := io.rx.payload
          crc.io.update.valid := True
          goto(magicB1State)
        }
      }
    }
    val magicB1State: State = new State {
      whenIsActive {
        when(abortNow) {
          io.fault := True
          goto(resyncState)
        } elsewhen (io.rx.fire) {
          magicB1Reg := io.rx.payload
          crc.io.update.valid := True
          goto(magicB2State)
        }
      }
    }
    val magicB2State: State = new State {
      whenIsActive {
        when(abortNow) {
          io.fault := True
          goto(resyncState)
        } elsewhen (io.rx.fire) {
          magicB2Reg := io.rx.payload
          crc.io.update.valid := True
          goto(magicB3State)
        }
      }
    }
    val magicB3State: State = new State {
      whenIsActive {
        when(abortNow) {
          io.fault := True
          goto(resyncState)
        } elsewhen (io.rx.fire) {
          crc.io.update.valid := True
          val got =
            (io.rx.payload ## magicB2Reg ## magicB1Reg ## magicB0Reg).asUInt
          when(got =/= U(MAGIC, 32 bits)) {
            io.fault := True
            goto(resyncState)
          } otherwise {
            goto(lenB0State)
          }
        }
      }
    }

    // ---------------- lenB0..B3 ---------------------------------------------
    // Consume the 4-byte LEN preamble (body word count). Validate on the
    // last byte: 1 <= len <= programWordCount.
    val lenB0State: State = new State {
      whenIsActive {
        when(abortNow) {
          io.fault := True
          goto(resyncState)
        } elsewhen (io.rx.fire) {
          lenB0Reg := io.rx.payload
          crc.io.update.valid := True
          goto(lenB1State)
        }
      }
    }
    val lenB1State: State = new State {
      whenIsActive {
        when(abortNow) {
          io.fault := True
          goto(resyncState)
        } elsewhen (io.rx.fire) {
          lenB1Reg := io.rx.payload
          crc.io.update.valid := True
          goto(lenB2State)
        }
      }
    }
    val lenB2State: State = new State {
      whenIsActive {
        when(abortNow) {
          io.fault := True
          goto(resyncState)
        } elsewhen (io.rx.fire) {
          lenB2Reg := io.rx.payload
          crc.io.update.valid := True
          goto(lenB3State)
        }
      }
    }
    val lenB3State: State = new State {
      whenIsActive {
        when(abortNow) {
          io.fault := True
          goto(resyncState)
        } elsewhen (io.rx.fire) {
          crc.io.update.valid := True
          val newLen =
            (io.rx.payload ## lenB2Reg ## lenB1Reg ## lenB0Reg).asUInt
          frameLen := newLen
          programLengthReg := newLen.resize(wordIndexWidth bits)

          when(
            newLen === U(0, lenWidth bits) ||
              newLen > U(programWordCount, lenWidth bits)
          ) {
            io.fault := True
            goto(resyncState)
          } otherwise {
            wordIndex := U(0, wordIndexWidth bits)
            goto(wordB0State)
          }
        }
      }
    }

    // ---------------- body words wordB0..B3 ---------------------------------
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
    val wordB3State: State = new State {
      whenIsActive {
        when(abortNow) {
          io.fault := True
          goto(resyncState)
        } elsewhen (io.rx.fire) {
          wordB3Reg := io.rx.payload
          crc.io.update.valid := True
          // Pre-register the "this body word is the last" predicate, paced
          // by the slow UART byte arrival, so writeWordState's same-cycle
          // SPRAM-fire decision is a 1-bit Reg read.
          isLastWord := (wordIndex + 1) === frameLen.resize(wordIndexWidth bits)
          goto(writeWordState)
        }
      }
    }

    // ---------------- writeWord ----------------------------------------------
    // Offer the assembled 32-bit body word to SPRAM at wordIndex. On the
    // last body word, advance to crcLoState.
    val writeWordState: State = new State {
      whenIsActive {
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

    // ---------------- crcLo / crcHi ------------------------------------------
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
    // Ready in any byte-consuming state. Idle holds ready low so the first
    // byte of a frame is consumed by magicB0 after the init cycle.
    // writeWord holds ready low while SPRAM may stall.
    io.rx.ready := io.acceptRx && (
      isActive(magicB0State) ||
        isActive(magicB1State) ||
        isActive(magicB2State) ||
        isActive(magicB3State) ||
        isActive(lenB0State) ||
        isActive(lenB1State) ||
        isActive(lenB2State) ||
        isActive(lenB3State) ||
        isActive(wordB0State) ||
        isActive(wordB1State) ||
        isActive(wordB2State) ||
        isActive(wordB3State) ||
        isActive(crcLoState) ||
        isActive(crcHiState) ||
        isActive(resyncState)
    )

    // ---------------- errorLatch driver --------------------------------------
    when(isActive(idleState) || isActive(resyncState)) {
      errorLatch := False
    } elsewhen (rxErr) {
      errorLatch := True
    }

    // ---------------- acceptRx-drop catch-all --------------------------------
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
