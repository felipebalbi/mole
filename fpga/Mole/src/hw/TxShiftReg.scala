// SPDX-FileCopyrightText: 2026 Felipe Balbi
// SPDX-License-Identifier: CERN-OHL-W-2.0

package mole

// Imported from felipebalbi/icebreaker-spinalhdl-examples@98c06a8cf39f6c212d3b46d98868aca398a82186 Uart/src/hw/TxShiftReg.scala.
// Modified for Mole only as documented inline (search for 'Mole').

import spinal.core._

/** Transmit shift register: parallel-in, serial-out (LSB first).
  *
  * Holds the byte currently being transmitted and exposes one bit at a time on
  * `io.bit`. Deliberately dumb: knows nothing about baud rate, frame format,
  * idle line, or how many bits have been shifted. The TX FSM owns all of that
  * and just pokes this block with `load` / `shift` pulses.
  *
  * Behaviour:
  *   - `load=1` on a rising edge: `data` is captured into the register; on the
  *     *next* edge `io.bit` reflects `data(0)` (the new LSB).
  *   - `shift=1` on a rising edge: register shifts right by one (bit 0
  *     discarded, MSB filled with 0); on the next edge `io.bit` reflects the
  *     new bit 0.
  *   - `load` and `shift` simultaneously high: **load wins**. The newly loaded
  *     `data(0)` is what `io.bit` will show next cycle. This is deliberate —
  *     never lose an incoming byte to a stray shift pulse.
  *   - Neither high: register holds.
  *
  * `io.bit` is combinational off the register, so the FSM sees the new value on
  * the same clock edge it samples. The MSB-fill of 0 means that after
  * `dataBits` shifts the register reads as all zeros, but the FSM stops
  * shifting at that point so it doesn't matter.
  *
  * Reset value is `0xFF` (all ones) purely as a courtesy: if `io.bit` were ever
  * read before any `load` (it shouldn't be — the FSM gates this), the line
  * would idle high, matching UART idle convention rather than emitting a
  * spurious low pulse.
  *
  * Width is exactly `cfg.dataBits` bits — start/stop bits are the FSM's
  * responsibility, never pre-loaded into the register.
  */
case class TxShiftReg(cfg: UartConfig) extends Component {
  val io = new Bundle {

    /** Parallel load enable. When high on a rising edge, `io.data` is captured
      * into the register. Wins over `io.shift` if both are high in the same
      * cycle. The TX FSM pulses this for one cycle at the start of every frame.
      */
    val load = in Bool ()

    /** Parallel data input. Sampled only on cycles where `io.load` is high;
      * otherwise ignored. Width is `cfg.dataBits`.
      */
    val data = in Bits (cfg.dataBits bits)

    /** Shift enable. When high on a rising edge (and `io.load` is low), the
      * register shifts right by one. The TX FSM pulses this once per baud tick
      * while in the Data state.
      */
    val shift = in Bool ()

    /** Current bit on the wire — combinationally tied to bit 0 of the register.
      * The TX FSM routes this to `io.tx` while in the Data state. UART
      * transmits LSB first, so bit 0 of the loaded byte goes out first, then
      * bit 1, and so on.
      */
    val bit = out Bool ()
  }

  // Init high so the (gated) `bit` output reads as UART-idle if ever
  // sampled before the first load. Functionally a don't-care.
  val shiftReg = Reg(Bits(cfg.dataBits bits)) init (0xff)

  io.bit := shiftReg(0)

  when(io.load) {
    // Load wins over shift. Never drop an incoming byte.
    shiftReg := io.data
  } elsewhen (io.shift) {
    // `>>` on Bits with an Int shift amount returns a narrower Bits
    // (`width - shift` bits), so we must explicitly resize back to
    // `cfg.dataBits` — `.resize` zero-extends the MSB, which is what
    // we want here. After `dataBits` shifts the register is all zeros;
    // fine, the FSM stops driving `shift` then.
    shiftReg := (shiftReg >> 1).resize(cfg.dataBits)
  }
}
