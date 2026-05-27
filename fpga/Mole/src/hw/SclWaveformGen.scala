package mole

import spinal.core._

/** Canonical SCL waveform generator for `EMIT_BIT` --- a pure
  * combinational function `quarterIndex(0..3) → tx_symbol`.
  *
  * Per ROADMAP §"Canonical EMIT_BIT shape" and `fpga/Mole/AGENTS.md`
  * §"Quarter-bit is the timing unit on the wire", one `EMIT_BIT` is
  * four quarter-bit cycles with SCL low in Q0/Q1 and SCL released /
  * driven high in Q2/Q3. The "low" half is `dominant` (NMOS pull);
  * the "high" half is `recessive` (released under OD class,
  * actively driven under PP class) --- the actual electrical
  * realization is the symbol decoder's problem, not this block's.
  *
  * Returning a `TxSymbol` craft (and not a `(driveLow, driveHigh)` pair)
  * is deliberate: the engine routes this output through the shared
  * `SymbolDecoder` against the current `BUS_MODE`, so the Q2/Q3
  * high-half automatically becomes OD-release under `i2c` / `i3c-OD`
  * and PP-drive-high under `i3c-PP` / `hdr-ddr` with no duplicate
  * decode logic and no per-bus-mode special-casing in this file.
  *
  * `EMIT_QUARTER` does **not** use this generator --- it carries its
  * own SCL `tx_symbol` per quarter directly in the bitstream. The
  * engine FSM selects between this generator and the bitstream
  * `scl_symbol` field based on the current opcode.
  */
object SclWaveformGen {

  /** Decode a 2-bit quarter index (`0..3`) into the canonical
    * `EMIT_BIT` SCL `tx_symbol`:
    *
    * {{{
    *   Q0 -> dominant    (SCL pulled low)
    *   Q1 -> dominant    (SCL pulled low)
    *   Q2 -> recessive   (SCL released / driven high per BUS_MODE)
    *   Q3 -> recessive   (SCL released / driven high per BUS_MODE)
    * }}}
    *
    * Returns a fresh `TxSymbol` craft on every call --- consumers wire
    * it directly into the `SymbolDecoder`.
    */
  def apply(quarterIndex: UInt): SpinalEnumCraft[TxSymbol.type] = {
    require(
      quarterIndex.getWidth >= 2,
      s"SclWaveformGen quarterIndex must be >= 2 bits wide " +
        s"(got ${quarterIndex.getWidth}); 4 quarters per bit"
    )

    val sym = TxSymbol()
    // Default to dominant (Q0/Q1). Q2/Q3 override below. Writing the
    // assignment this way (not a Mux) keeps the synthesised logic a
    // single OR rather than a true mux, and reads top-to-bottom as the
    // canonical waveform.
    sym := TxSymbol.dominant
    when(quarterIndex(1)) {
      sym := TxSymbol.recessive
    }
    sym
  }
}
