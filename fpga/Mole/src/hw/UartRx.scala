package mole

// Imported from felipebalbi/icebreaker-spinalhdl-examples@98c06a8cf39f6c212d3b46d98868aca398a82186 Uart/src/hw/UartRx.scala.
// Modified for Mole only as documented inline (search for 'Mole').

import spinal.core._
import spinal.lib._

/** UART receiver (RX-only half).
  *
  * Top-level shape: a serial line in, a Stream of bytes out, plus three
  * side-band error flags and an optional RTS output for hardware flow control.
  *
  * Frame format on the wire (8N1 by default):
  *   - idle : line stays high
  *   - start : single low bit, one bit period long
  *   - data : `cfg.dataBits` bits, **LSB first** (UART convention)
  *   - parity : optional, one bit (when `cfg.parity != None`)
  *   - stop : `cfg.stopBits` high bits
  *   - idle : line returns high
  *
  * Internally this composes:
  *   - an [[RxSync]] (2-FF synchronizer) so the off-chip rx pin is safely
  *     crossed into our clock domain before any logic touches it
  *   - a [[BaudGenerator]] running at `oversample × baudRate` (NOT just
  *     `baudRate`) — see the wiring note below
  *   - an [[RxFsm]] that does start-bit edge detection, half-bit verify for
  *     glitch rejection, bit-centre sampling, parity check, framing check, and
  *     overrun detection. The FSM in turn composes an [[RxShiftReg]] for byte
  *     assembly.
  *
  * The Stream handshake on `io.payload` provides natural back-pressure: the
  * FSM's `payloadValidReg` is sticky-until-`fire`, so a slow consumer can take
  * its time to grab a byte. A fast enough sender filling that delay produces
  * `io.overrun`.
  *
  * ==Two non-obvious wiring choices==
  *
  *   - **BaudGenerator runs at `baudRate × oversample`**, not `baudRate`. RxFsm
  *     counts oversample ticks per bit (16 by default), so each generator tick
  *     is one *sub-bit* sample slot. We get that by passing the generator a
  *     derived config: `cfg.copy(baudRate = baudRate * oversample)`. No new
  *     generator module needed.
  *   - **`baud.io.enable := True` (free-running)**, in contrast to [[UartTx]]
  *     which gates `enable := fsm.io.busy`. The half-bit verify after a
  *     start-bit edge needs ticks immediately; a `busy`-gated generator would
  *     have a startup delay right when we can least afford it.
  *
  * ==Error semantics==
  *
  * The three error flags pulse for one cycle alongside `valid` and clear the
  * next time the FSM visits its idle state. `valid` is sticky-until- `fire`. A
  * consumer that takes more than one cycle to acknowledge a byte must latch the
  * error flags itself on the same cycle as `valid` if it wants the error info.
  *
  * @param cfg
  *   compile-time configuration (clock freq, baud, frame format, oversample,
  *   useRts)
  */
case class UartRx(cfg: UartConfig) extends Component {
  val io = new Bundle {

    /** Serial input line, direct from an FPGA pin. Async to our system clock -
      * the wrapper passes this straight into RxSync, which crosses it into our
      * domain via two flops.
      */
    val rx = in Bool ()

    /** Byte output, ready/valid handshake. Producer side.
      *
      * `valid` rises in the cycle the FSM finishes a clean frame and stays high
      * until the consumer fires `ready`. `payload` carries the assembled byte
      * (LSB-aligned). Per-frame error flags live on the side-band ports below -
      * the consumer must latch them on the same cycle as `valid`if it wants the
      * error info, because they pulse for one cycle and clear on the FSM's next
      * visit to idle.
      */
    val payload = master Stream (Bits(cfg.dataBits bits))

    /** Pulsed for one cycle alongside `valid` when the stop bit was sampled
      * low. The byte on `payload` is still presented (you may want to discard
      * it).
      */
    val framingError = out Bool ()

    /** Pulsed for one cycle alongside `valid` when the received parity bit
      * didn't match the parity of the data bits. Only meaningful when
      * `cfg.parity != ParityType.None`; tied low otherwise (the FSM elides the
      * parity state at elaboration).
      */
    val parityError = out Bool ()

    /** Pulsed for one cycle when a new frame completes while `payload.valid` is
      * still asserted from a previous frame. The NEW byte ends up on `payload`
      * (the old one is lost). Indicates downstream isn't keeping up.
      */
    val overrun = out Bool ()

    /** Optional Request-To-Send output, telling the far end's TX that we can
      * accept bytes. Active high.
      *
      * Only present when `cfg.useRts` is true. We mirror `payload.ready`
      * directly - when the consumer can take a byte, we tell the sender it's
      * safe to send. Note this gives no lead-time before our buffer fills; for
      * stricter back-pressure the application layer should drive RTS off a
      * "FIFO almost full" signal instead. For 115200-baud bring-up with a small
      * FIFO this is fine.
      *
      * Setting `cfg.useRts = false` removes this port entirely.
      */
    val rts = cfg.useRts generate (out Bool ())

    /** DDS phase increment for the internal baud generator. Note this is the
      * RX-side increment, which needs to run at `oversample × baudRate` — use
      * `BaudGenerator.phaseIncFor(clkFreqHz, baudRate * oversample, ...)` for a
      * fixed-baud build, or shift the controller's TX phaseInc up by
      * `log2(oversample)`.
      */
    val baudPhaseInc = in UInt (BaudGenerator.defaultAccWidth bits)
  }

  val sync = RxSync()
  val baud = BaudGenerator(BaudGenerator.defaultAccWidth)
  val fsm = RxFsm(cfg)

  sync.io.asyncIn := io.rx
  baud.io.enable := True
  baud.io.phaseInc := io.baudPhaseInc
  fsm.io.tick := baud.io.tick

  fsm.io.rx := sync.io.syncOut

  io.payload << fsm.io.payload
  io.framingError := fsm.io.framingError
  io.parityError := fsm.io.parityError
  io.overrun := fsm.io.overrun

  if (cfg.useRts)
    io.rts := io.payload.ready
}

// Mole: dropped UartRxVerilog object — Mole has no top-level UART yet
// (lands with Step 13 / MoleTop). Add a thin generator object back if a
// standalone UartRx Verilog drop is ever needed for bring-up.
