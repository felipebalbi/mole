package mole

import spinal.core._

/** Engine-internal helpers around the `BUS_MODE` register.
  *
  * The [[mole.BusMode]] `SpinalEnum` and its wire encoding live in
  * `Instruction.scala` (the source of truth for the host encoder ↔ engine wire
  * contract). This file adds the *engine-side* helpers that consume the
  * register:
  *
  *   - [[isPpClass]]: read of `mode[2]` (the SCL high-half drive class
  *     selector), expressed at the enum level so callers do not poke at the raw
  *     `Bits` representation.
  *   - [[SymbolDecoder]]: the one shared combinational
  *     `(tx_symbol, BUS_MODE) → (driveLow, driveHigh)` map per ROADMAP §"TX
  *     symbol".
  *
  * Per `fpga/Mole/AGENTS.md` §"ISA is a stable contract" the engine carries
  * *only* `BUS_MODE` as protocol context; everything else lives in the
  * bitstream. These helpers exist to keep that contract one cheap mux deep ---
  * no per-pad mode registers, no protocol knowledge leaking into the decode
  * FSM.
  */
object BusModeOps {

  /** True iff `busMode` is a push-pull class (`i3c-PP` or `hdr-ddr`), i.e. its
    * `mode[2]` is `1`. Used by [[SymbolDecoder]] to decide whether `recessive`
    * means "release" (OD class) or "drive high" (PP class).
    *
    * Implemented as an enum compare rather than `busMode.asBits(2)` so the
    * intent reads at the semantic layer and is robust against a future
    * re-ordering of the enum declaration (the wire encoding stays locked, but
    * the SpinalEnumElement `.position` does not).
    */
  def isPpClass(busMode: SpinalEnumCraft[BusMode.type]): Bool =
    (busMode === BusMode.i3cPp) || (busMode === BusMode.hdrDdr)
}

/** Bundled output of [[SymbolDecoder]] --- the pair of pad-driver enables for
  * one `(tx_symbol, BUS_MODE)` decode.
  *
  * Named fields rather than a `(Bool, Bool)` tuple because Scala 2's
  * pattern-bind desugaring of `val (a, b) = SymbolDecoder(...)` interacts badly
  * with SpinalHDL's IDSL compiler-plugin rewrite of `is(...){...}` and
  * `when(...){...}` bodies --- the rewrite loses the tuple's type parameters
  * and the bound names come out as `Any`. Named-field access (`.driveLow` /
  * `.driveHigh`) bypasses pattern binding entirely and is robust against the
  * rewrite.
  *
  * The case class is small and pure-Scala; it does not elaborate to RTL on its
  * own. Spinal sees only the two `Bool` wires inside.
  */
case class SymbolDrive(driveLow: Bool, driveHigh: Bool)

/** Pure-combinational symbol decoder --- the *only* place in the engine that
  * maps a per-bit / per-quarter `tx_symbol` (plus the current `BUS_MODE`) onto
  * pad-driver enables.
  *
  * Decode table per ROADMAP §"TX symbol":
  *
  * {{{
  *               OD class (i2c, i3c-OD)    PP class (i3c-PP, hdr-ddr)
  * dominant   -> (driveLow=1, driveHigh=0)  (driveLow=1, driveHigh=0)
  * recessive  -> (0, 0) -- release / Hi-Z   (0, 1) -- PP active high
  * hiz        -> (0, 0) -- driver off       (0, 0) -- driver off
  * reserved   -> (0, 0) -- v0.5 raw_override (no v0 behaviour)
  * }}}
  *
  * Notes:
  *   - The OD-class `recessive` decode is *exactly* the same electrical state
  *     as `hiz` (both leave the pad floating; the external pull-up wins). The
  *     two symbols stay distinct in the bitstream so a future analysis pass can
  *     tell "released because the SDK said so" from "released because the SDK
  *     wanted Hi-Z".
  *   - The `reserved` (`11`) encoding is held for the v0.5 `raw_override`
  *     escape; this decoder produces Hi-Z for it in v0. The engine fetch path
  *     traps reserved-opcode words separately (see Step 8 FSM), so the only way
  *     a `reserved` reaches this decoder in v0 is via a hand-crafted bitstream
  *     --- in which case Hi-Z is the safe default.
  *   - `driveLow` and `driveHigh` are *never* both True for any
  *     `(tx_symbol, BUS_MODE)` combination, by inspection of the table above.
  *     The `MoleBus` contract calls bus contention out as illegal; this decoder
  *     is structurally incapable of producing it.
  */
object SymbolDecoder {

  /** Decode `(txSymbol, busMode)` to a [[SymbolDrive]] bundle of
    * `(driveLow, driveHigh)`. Both wires are fresh combinational `Bool`s; the
    * caller is expected to `:=` them into registered pad drivers per
    * `fpga/Mole/AGENTS.md` §"Bus-shaped FSM idiom".
    */
  def apply(
      txSymbol: SpinalEnumCraft[TxSymbol.type],
      busMode: SpinalEnumCraft[BusMode.type]
  ): SymbolDrive = {
    val driveLow = Bool()
    val driveHigh = Bool()

    // Defaults: driver off (matches `hiz` and `reserved` rows).
    driveLow := False
    driveHigh := False

    when(txSymbol === TxSymbol.dominant) {
      // OD class and PP class both pull the bus toward dominant via
      // the NMOS leg; PMOS stays off.
      driveLow := True
    } elsewhen (txSymbol === TxSymbol.recessive) {
      // PP class actively drives high. OD class leaves the line
      // floating (external pull-up does the work).
      when(BusModeOps.isPpClass(busMode)) {
        driveHigh := True
      }
    }
    // hiz / reserved: both defaults stay False. No bus contention
    // possible.

    SymbolDrive(driveLow, driveHigh)
  }

  /** Pure-Scala mirror of [[apply]] over the wire-level encodings of `BUS_MODE`
    * and `tx_symbol`. Returns `(driveLow, driveHigh)` as plain `Boolean`s ---
    * no SpinalHDL elaboration, no SpinalSim DUT required.
    *
    * This exists so the exhaustive cartesian-product sweep that audits
    * INV-BUS-NO-CONTENTION (`SymbolDecoderContentionSim`) can iterate the 4 x 4
    * truth table directly in Scala. The lockstep test in that sim spot-checks
    * several cells against a SpinalSim DUT wrapping [[apply]] so the two
    * implementations cannot drift.
    *
    * Wire-value contract (per `Instruction.scala` and ROADMAP §"Bus mode
    * register" / §"TX symbol"):
    *
    *   - `busModeWire`: 0 = `i2c`, 1 = `i3c-OD`, 6 = `i3c-PP`, 7 = `hdr-ddr`
    *     (other values are illegal at the wire level).
    *   - `txSymbolWire`: 0 = `dominant`, 1 = `recessive`, 2 = `hiz`, 3 =
    *     `reserved` (v0.5 `raw_override`; decoded as Hi-Z in v0).
    *
    * An unknown `busModeWire` is treated as OD-class (the safer of the two:
    * `recessive` does not assert `driveHigh`).
    */
  def staticDecode(busModeWire: Int, txSymbolWire: Int): SymbolDriveStatic = {
    val isPp = busModeWire == 6 || busModeWire == 7
    txSymbolWire match {
      case 0 => // dominant
        SymbolDriveStatic(driveLow = true, driveHigh = false)
      case 1 => // recessive
        SymbolDriveStatic(driveLow = false, driveHigh = isPp)
      case _ => // hiz, reserved, anything else
        SymbolDriveStatic(driveLow = false, driveHigh = false)
    }
  }
}

/** Pure-Scala counterpart of [[SymbolDrive]] used by
  * [[SymbolDecoder.staticDecode]]. Lives alongside the SpinalHDL bundle so the
  * contention sweep can iterate the truth table without elaborating any RTL.
  */
case class SymbolDriveStatic(driveLow: Boolean, driveHigh: Boolean)
