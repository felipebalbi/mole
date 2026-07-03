// SPDX-FileCopyrightText: 2026 Felipe Balbi
// SPDX-License-Identifier: CERN-OHL-W-2.0

package mole

import spinal.core._
import spinal.lib._

/** Bytewise CRC-16/XMODEM accumulator for the MoleTop UART loader.
  *
  * The wire-format CRC variant used between host and engine is
  * **CRC-16/XMODEM**: width 16, polynomial 0x1021, init 0x0000, no input or
  * output reflection, no XOR-out. The catalogue check value (CRC of the ASCII
  * string `"123456789"`) is 0x31C3. Full specification --- including reference
  * algorithms, library snippets, and Mole-specific test vectors --- lives in
  * `../../WIRE_FORMAT.md`. This file is the hardware mirror of that doc.
  *
  * The Component absorbs at most one byte per fabric cycle and holds the
  * running CRC in a registered output. The combinational byte-update step
  * unrolls all eight bit iterations at elaboration time --- about eight levels
  * of cascaded XOR/MUX, which fits comfortably under the 24 MHz fabric timing
  * budget on the iCE40 UP5K (Mole Verde).
  *
  * `MoleLoaderFsm` instantiates exactly one of these. The loader asserts
  * `io.init` during `Idle` and `Resync` (clearing the register to 0x0000) and
  * fires `io.update` once per byte that lands in the four payload states
  * (`LenLo`, `LenHi`, `WordLo`, `WordHi`) --- never in the CRC trailer states
  * (`CrcLo`, `CrcHi`). That gating discipline keeps this Component
  * protocol-agnostic: it just streams bytes in and reports the rolling CRC.
  */
case class Crc16Xmodem() extends Component {
  val io = new Bundle {

    /** Level-sensitive synchronous reset of the CRC register to 0x0000. Hold
      * high across any cycle where the running CRC should be cleared (the
      * loader holds it through `Idle` and `Resync`). Wins over `update` if both
      * fire in the same cycle --- "you cannot meaningfully initialise and
      * absorb a byte at the same time" is a contract violation, and latching
      * the initial state is the safer of the two interpretations.
      */
    val init = in Bool ()

    /** One byte per cycle, absorbed into the running CRC on the next rising
      * edge. `Flow` (not `Stream`) because the accumulator never asserts
      * back-pressure --- the byte-update is purely combinational and always
      * completes in a single cycle.
      */
    val update = slave Flow (Bits(8 bits))

    /** Current CRC register value. Combinationally tracks the register, so a
      * consumer that samples it on the same cycle as `init` sees 0x0000 a cycle
      * later, and a consumer that samples it the cycle after the final
      * `update.fire` sees the final CRC.
      */
    val value = out Bits (16 bits)
  }

  val crc = Reg(Bits(16 bits)) init (B(0x0000, 16 bits))

  when(io.init) {
    crc := B(0x0000, 16 bits)
  } elsewhen (io.update.valid) {
    crc := Crc16Xmodem.update(crc, io.update.payload)
  }

  io.value := crc
}

/** Combinational helpers for CRC-16/XMODEM.
  *
  * Pure-elaboration functions; no hardware on their own. Live in the companion
  * object so `MoleLoaderFsm` (or any future block that needs a one-shot
  * combinational CRC step) can reuse the byte-update without instantiating a
  * full [[Crc16Xmodem]] Component.
  */
object Crc16Xmodem {

  /** Polynomial in normal (non-reflected) form: x^16 + x^12 + x^5 + 1. */
  val POLY: Int = 0x1021

  /** Initial CRC register value per the XMODEM parameter set. */
  val INIT: Int = 0x0000

  /** Combinationally absorb one byte into a 16-bit CRC running value.
    *
    * Implements:
    *
    * {{{
    *   crc ^= (byte << 8);
    *   repeat 8:
    *     crc = (crc & 0x8000) ? (crc << 1) ^ 0x1021
    *                          :  crc << 1;
    * }}}
    *
    * All eight bit iterations unroll at elaboration time into cascaded MUX/XOR
    * levels. The loader can therefore absorb one byte per fabric cycle without
    * inserting wait states.
    *
    * @param crc
    *   Current 16-bit CRC running value (`Bits(16 bits)`).
    * @param byte
    *   Byte to absorb (`Bits(8 bits)`).
    * @return
    *   Updated 16-bit CRC running value (`Bits(16 bits)`).
    */
  def update(crc: Bits, byte: Bits): Bits = {
    require(
      crc.getWidth == 16,
      s"crc must be 16 bits wide, got ${crc.getWidth}"
    )
    require(
      byte.getWidth == 8,
      s"byte must be 8 bits wide, got ${byte.getWidth}"
    )

    // Step 1: XOR the byte into the high half of the CRC. Concatenation
    // `byte ## B(0, 8 bits)` builds a 16-bit value with the byte in the
    // high half and zeros in the low half --- equivalent to `byte << 8`
    // but without the widening side-effect of `<<`.
    var work: Bits = crc ^ (byte ## B(0, 8 bits))

    // Step 2: eight bit-iterations of the polynomial division. Unrolled at
    // elaboration time. `work(14 downto 0) ## B"0"` is a width-preserving
    // left shift by 1; we keep the top bit of `work` before discarding it
    // and use it to decide whether to XOR in the polynomial.
    for (_ <- 0 until 8) {
      val msb = work.msb
      val shifted: Bits = work(14 downto 0) ## B"0"
      work = Mux(msb, shifted ^ B(POLY, 16 bits), shifted)
    }

    work
  }

  /** Convenience: absorb an entire byte sequence at elaboration time. Useful in
    * sims for pre-computing expected CRCs over hard-coded byte arrays. Not for
    * synthesis --- folds at elaboration in pure Scala, no hardware.
    */
  def updateAll(initCrc: Int, bytes: Iterable[Int]): Int = {
    var c = initCrc & 0xffff
    for (b <- bytes) {
      val bb = b & 0xff
      c ^= bb << 8
      for (_ <- 0 until 8) {
        c =
          if ((c & 0x8000) != 0) ((c << 1) ^ POLY) & 0xffff
          else (c << 1) & 0xffff
      }
    }
    c
  }
}
