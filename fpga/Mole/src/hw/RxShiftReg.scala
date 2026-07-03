// SPDX-FileCopyrightText: 2026 Felipe Balbi
// SPDX-License-Identifier: CERN-OHL-W-2.0

package mole

// Imported from felipebalbi/icebreaker-spinalhdl-examples@98c06a8cf39f6c212d3b46d98868aca398a82186 Uart/src/hw/RxShiftReg.scala.
// Modified for Mole only as documented inline (search for 'Mole').

import spinal.core._

/** Receive shift register: serial-in, parallel-out (LSB first).
  *
  * Mirror of [[TxShiftReg]] for the RX path. Collects bits arriving on
  * `io.sample`, one per `io.shift` pulse, into a register that — after exactly
  * `cfg.dataBits` shifts — holds the assembled byte ready for the RX FSM to
  * hand off downstream.
  *
  * Like its TX cousin, this block is deliberately dumb: it knows nothing about
  * baud rate, oversampling, start/stop bits, or how many shifts have happened.
  * The RxFsm owns all of that and just pokes this block with `clear` / `shift`
  * pulses at the right moments.
  *
  * Bit ordering — the only subtle part:
  *   - UART transmits LSB first. The first data bit on the wire is bit 0 of the
  *     byte; the last is bit (dataBits − 1).
  *   - On every `shift` pulse we do `shiftReg := io.sample ## shiftReg(N-1
  *     downto 1)`. SpinalHDL's `##` puts the left operand on the MSB side, so
  *     `io.sample` becomes the new MSB and the existing register slides one
  *     position toward the LSB (a right-shift, dropping the old bit 0).
  *   - The first sample, taken when the register is all zeros, ends up as bit 0
  *     after exactly N shifts (it has been right-shifted N − 1 times past the
  *     initial MSB position). The last sample lands directly in bit N − 1. That
  *     is precisely the LSB-first convention.
  *
  * Worked example, dataBits = 8, receiving the byte `0xAD = 10101101`
  * (transmitted on the wire as 1,0,1,1,0,1,0,1, LSB first):
  * {{{
  *   start          : 00000000
  *   shift sample=1 : 10000000
  *   shift sample=0 : 01000000
  *   shift sample=1 : 10100000
  *   shift sample=1 : 11010000
  *   shift sample=0 : 01101000
  *   shift sample=1 : 10110100
  *   shift sample=0 : 01011010
  *   shift sample=1 : 10101101  ← 0xAD, ready to hand off
  * }}}
  *
  * Behaviour:
  *   - `clear=1` on a rising edge: register is forced to all zeros. Used by the
  *     RxFsm at the start of every frame so partial bits from a previous decode
  *     (or whatever was in the register at reset) cannot pollute the new byte.
  *     **Wins over `shift` in the same cycle** — the first sample of a new
  *     frame should be loaded the cycle *after* the clear, not simultaneously
  *     with it; the FSM is expected to sequence accordingly.
  *   - `shift=1` on a rising edge: `io.sample` is captured into the MSB and the
  *     rest of the register shifts one position toward the LSB.
  *   - Both low: register holds.
  *
  * Output:
  *   - `io.data` is combinationally tied to the register, so it always shows
  *     *whatever is currently in the shifter*. **Mid-frame the value is partial
  *     garbage** — only meaningful after the FSM has issued exactly
  *     `cfg.dataBits` shift pulses since the last clear. The RxFsm enforces
  *     this by only handing off `io.data` in its DONE state. (Symmetric to how
  *     `TxShiftReg.io.bit` is "don't care" outside the Data state.)
  *
  * Reset value is `0` rather than the all-ones `TxShiftReg` uses. Reasons:
  *   - The consumer (RxFsm) is expected to pulse `clear` at frame start anyway,
  *     so the reset value is functionally a don't-care.
  *   - `0` matches what `clear` produces, keeping the two paths observationally
  *     identical and avoiding any "did this byte come from reset state or from
  *     a real frame?" ambiguity if `io.data` is sampled by accident before the
  *     first frame.
  *
  * Width is exactly `cfg.dataBits` — start / stop / parity bits live in the
  * RxFsm and are never shifted into this register.
  */
case class RxShiftReg(cfg: UartConfig) extends Component {
  val io = new Bundle {

    /** Synchronous clear. When high on a rising edge, the register is forced to
      * all zeros. The RxFsm pulses this for one cycle at the start of every
      * frame, before the first `shift` pulse, so leftover state from the
      * previous frame (or reset) cannot leak into the new byte. Wins over
      * `io.shift` in the same cycle.
      */
    val clear = in Bool ()

    /** Shift enable. When high on a rising edge (and `io.clear` is low),
      * `io.sample` is captured into bit (dataBits − 1) and the rest of the
      * register shifts one position toward bit 0. The RxFsm pulses this once
      * per bit period (at the bit-centre sample point) while in its DATA state,
      * exactly `cfg.dataBits` times per frame.
      */
    val shift = in Bool ()

    /** The bit value to shift in on the next `shift` pulse. The RxFsm drives
      * this from the synchronised RX line at the bit-centre tick.
      */
    val sample = in Bool ()

    /** Combinational view of the shift register. Width is `cfg.dataBits`. Only
      * meaningful after exactly `cfg.dataBits` shifts have happened since the
      * last `clear` — mid-frame the value is partial garbage that the RxFsm is
      * responsible for ignoring.
      */
    val data = out Bits (cfg.dataBits bits)
  }

  // Init to 0 so any accidental read pre-frame produces a deterministic
  // value rather than synth-tool-defined junk. The RxFsm always issues
  // `clear` at frame start, so this reset value has no functional role.
  val shiftReg = Reg(Bits(cfg.dataBits bits)) init (0)

  when(io.clear) {
    // Clear wins over shift. Frame-start synchronisation belongs to the
    // FSM; mixing the two in the same cycle would mean ambiguous "did
    // this sample go into a fresh register or a stale one?" semantics.
    shiftReg := 0
  } elsewhen (io.shift) {
    // `sample` becomes the new MSB; everything else slides one position
    // toward the LSB. Old bit 0 is discarded. See the worked example in
    // the component-level Scaladoc for why this assembles correctly
    // LSB-first.
    shiftReg := io.sample ## shiftReg(cfg.dataBits - 1 downto 1)
  }

  io.data := shiftReg
}
