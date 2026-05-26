package mole

// Imported from felipebalbi/icebreaker-spinalhdl-examples@98c06a8cf39f6c212d3b46d98868aca398a82186 Uart/src/hw/UartTx.scala.
// Modified for Mole only as documented inline (search for 'Mole').

import spinal.core._
import spinal.lib._

/** UART transmitter (TX-only half).
  *
  * Top-level shape: a Stream of bytes in, a serial line out, plus an optional
  * CTS input for hardware flow control.
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
  *   - a [[BaudGenerator]] producing one tick per bit period
  *   - a [[TxShiftReg]] holding the byte being transmitted
  *   - a [[TxFsm]] stepping idle → start → data (→ parity) → stop → idle
  *
  * The Stream handshake on `io.data` provides natural back-pressure:
  * `data.ready` is held low while a frame is in flight, so the producer (or an
  * upstream FIFO) waits without losing bytes.
  *
  * @param cfg
  *   compile-time configuration (clock freq, baud, frame format, useCts)
  */
case class UartTx(cfg: UartConfig) extends Component {
  val io = new Bundle {

    /** Byte input, ready/valid handshake.
      *
      * Producer drives `data.valid` with a byte on `data.payload`. UartTx
      * asserts `data.ready` only when it is idle (and `cts` permits, when flow
      * control is enabled). A transfer occurs on any cycle where both are high;
      * from that cycle the byte is "owned" by UartTx and shifted out over the
      * next ~10 bit periods, during which `data.ready` stays low.
      */
    val data = slave Stream (Bits(cfg.dataBits bits))

    /** Serial output line. Idles high. Drops low for the start bit, then
      * carries `cfg.dataBits` data bits LSB-first, then optionally a parity
      * bit, then `cfg.stopBits` high stop bits, then returns to idle.
      */
    val tx = out Bool ()

    /** Clear-To-Send input from the far end (active high = "I can receive").
      *
      * This is the *local* TX's CTS input, fed by the *remote* RX's RTS output
      * via the cable. When low, UartTx must not start a new frame — it gates
      * `data.ready` so the producer is held off until the far end has buffer
      * space again.
      *
      * Only present when `cfg.useCts` is `true` (the default). Setting
      * `cfg.useCts = false` removes this port entirely, which is useful for
      * connections without flow control or to save a top-level FPGA pin. Once a
      * frame has started, dropping CTS does NOT abort it — CTS gates only the
      * *start* of new frames.
      */
    val cts = cfg.useCts generate (in Bool ())

    /** DDS phase increment for the internal baud generator. Wire to a constant
      * for fixed-baud builds (use [[BaudGenerator.phaseIncFor]]), or to a CSR
      * field for runtime baud (see [[UartController]]).
      */
    val baudPhaseInc = in UInt (BaudGenerator.defaultAccWidth bits)
  }

  val baud = BaudGenerator(BaudGenerator.defaultAccWidth)
  val sreg = TxShiftReg(cfg)
  val fsm = TxFsm(cfg)

  baud.io.enable := fsm.io.busy // tick only while transmitting
  baud.io.phaseInc := io.baudPhaseInc
  fsm.io.tick := baud.io.tick

  sreg.io.load := fsm.io.loadReg
  sreg.io.data := io.data.payload
  sreg.io.shift := fsm.io.shiftReg
  fsm.io.shiftRegBit := sreg.io.bit

  // Stream handshake + (optional) CTS gating. When useCts is disabled the
  // port doesn't exist; treat it as permanently asserted so the gate is a
  // no-op and synthesises away.
  val ctsOk = if (cfg.useCts) io.cts else True
  val canStart = !fsm.io.busy && ctsOk
  fsm.io.start := io.data.valid && canStart
  io.data.ready := canStart // accept the cycle FSM starts

  io.tx := fsm.io.txBit
}

// Mole: dropped UartTxVerilog object — Mole has no top-level UART yet
// (lands with Step 13 / MoleTop). Add a thin generator object back if a
// standalone UartTx Verilog drop is ever needed for bring-up.
