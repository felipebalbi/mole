package mole

import spinal.core._
import spinal.core.sim._

/** Exhaustive bus-no-contention sweep for [[SymbolDecoder]].
  *
  * Audits INV-BUS-NO-CONTENTION (canonical text: `fpga/Mole/AGENTS.md`
  * §"Open-drain primitive: custom MoleBus") by iterating every legal
  * `(BUS_MODE, tx_symbol)` cell of the decode truth table from ROADMAP §"TX
  * symbol" and asserting that neither `driveLow && driveHigh` cell appears in
  * the output.
  *
  * The sweep runs in two layers:
  *
  *   1. **Pure-Scala layer.** Iterates the 4 x 4 cartesian product of
  *      wire-level `busMode` x `txSymbol` values against
  *      [[SymbolDecoder.staticDecode]]. Fast, runs in milliseconds, no
  *      simulator backend involved. This is the automation of the AGENTS.md
  *      §"Open-drain primitive" truth table --- any future `BUS_MODE` addition
  *      or `tx_symbol` change that produces a contention cell fails the sweep.
  *   2. **SpinalSim lockstep layer.** Wraps [[SymbolDecoder.apply]] in a tiny
  *      combinational DUT, drives every legal `(BUS_MODE, tx_symbol)` pair, and
  *      asserts that the live RTL output matches [[SymbolDecoder.staticDecode]]
  *      bit-for-bit. Catches the case where the two implementations drift ---
  *      the pure-Scala mirror is only as good as its agreement with the
  *      SpinalHDL one.
  *
  * Run: `sbt "runMain mole.SymbolDecoderContentionSim"`
  */
object SymbolDecoderContentionSim extends App {

  // ------------------------------------------------------------------
  // Layer 1: pure-Scala cartesian-product sweep
  // ------------------------------------------------------------------

  /** Legal wire-level `BUS_MODE` values per v0.2 encoding (sequential). i2c=0,
    * i3c-OD=1, i3c-PP=2, hdr-ddr=3. (v0 used non-sequential 0,1,6,7 --- those
    * are retired in v0.2.)
    */
  val busModeWires: Seq[Int] = Seq(0, 1, 2, 3)

  /** Wire-level `tx_symbol` values, including `reserved` (3) so the sweep
    * covers a hand-crafted bitstream that smuggles the v0.5 raw_override
    * encoding through to the decoder. v0 behaviour is Hi-Z, which trivially
    * satisfies the no-contention rule.
    */
  val txSymbolWires: Seq[Int] = 0 to 3

  def busModeName(wire: Int): String = wire match {
    case 0 => "i2c"
    case 1 => "i3c-OD"
    case 2 => "i3c-PP"
    case 3 => "hdr-ddr"
    case n => s"<illegal:$n>"
  }

  def txSymbolName(wire: Int): String = wire match {
    case 0 => "dominant"
    case 1 => "recessive"
    case 2 => "hiz"
    case 3 => "reserved"
    case n => s"<illegal:$n>"
  }

  println("--- SymbolDecoder contention sweep (pure-Scala) ---")
  for (mode <- busModeWires; sym <- txSymbolWires) {
    val out = SymbolDecoder.staticDecode(mode, sym)
    assert(
      !(out.driveLow && out.driveHigh),
      s"contention at BUS_MODE=$mode (${busModeName(mode)}) " +
        s"tx_symbol=$sym (${txSymbolName(sym)})"
    )
    println(
      f"  BUS_MODE=${busModeName(mode)}%-8s tx_symbol=${txSymbolName(sym)}%-9s " +
        f"-> driveLow=${out.driveLow} driveHigh=${out.driveHigh}  OK"
    )
  }

  // ------------------------------------------------------------------
  // Layer 2: SpinalSim lockstep against SymbolDecoder.apply
  // ------------------------------------------------------------------

  /** Thin combinational wrapper exposing [[SymbolDecoder.apply]]'s two inputs
    * and two outputs at the IO boundary so SpinalSim can drive them. No
    * registers, no clock semantics --- the lockstep test advances
    * `waitSampling` only to let combinational propagation settle in the sim
    * graph.
    */
  case class SymbolDecoderDut() extends Component {
    val io = new Bundle {
      val busMode = in(BusMode())
      val txSymbol = in(TxSymbol())
      val driveLow = out Bool ()
      val driveHigh = out Bool ()
    }
    val decoded = SymbolDecoder(io.txSymbol, io.busMode)
    io.driveLow := decoded.driveLow
    io.driveHigh := decoded.driveHigh
  }

  /** SpinalEnum element corresponding to a v0.2 wire-level `BUS_MODE` value.
    * v0.2 uses sequential codes: 0=i2c, 1=i3c-OD, 2=i3c-PP, 3=hdr-ddr.
    */
  def busModeElem(wire: Int): BusMode.E = wire match {
    case 0 => BusMode.i2c
    case 1 => BusMode.i3cOd
    case 2 => BusMode.i3cPp
    case 3 => BusMode.hdrDdr
    case n => sys.error(s"no BusMode element for wire=$n")
  }

  def txSymbolElem(wire: Int): TxSymbol.E = wire match {
    case 0 => TxSymbol.dominant
    case 1 => TxSymbol.recessive
    case 2 => TxSymbol.hiz
    case 3 => TxSymbol.reserved
    case n => sys.error(s"no TxSymbol element for wire=$n")
  }

  println("--- SymbolDecoder lockstep (SpinalSim vs staticDecode) ---")
  SimConfig.compile(SymbolDecoderDut()).doSim("lockstep") { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    dut.io.busMode #= BusMode.i2c
    dut.io.txSymbol #= TxSymbol.hiz
    dut.clockDomain.waitSampling(2)

    for (mode <- busModeWires; sym <- txSymbolWires) {
      dut.io.busMode #= busModeElem(mode)
      dut.io.txSymbol #= txSymbolElem(sym)
      // Combinational path; one sampling tick is enough for the
      // simulator to propagate the new inputs to the outputs.
      dut.clockDomain.waitSampling()

      val expect = SymbolDecoder.staticDecode(mode, sym)
      val gotLow = dut.io.driveLow.toBoolean
      val gotHigh = dut.io.driveHigh.toBoolean

      assert(
        gotLow == expect.driveLow && gotHigh == expect.driveHigh,
        s"lockstep mismatch at BUS_MODE=${busModeName(mode)} " +
          s"tx_symbol=${txSymbolName(sym)}: " +
          s"DUT=(driveLow=$gotLow, driveHigh=$gotHigh) " +
          s"static=(driveLow=${expect.driveLow}, " +
          s"driveHigh=${expect.driveHigh})"
      )
      assert(
        !(gotLow && gotHigh),
        s"DUT produced contention at BUS_MODE=${busModeName(mode)} " +
          s"tx_symbol=${txSymbolName(sym)}"
      )
      println(
        f"  BUS_MODE=${busModeName(mode)}%-8s " +
          f"tx_symbol=${txSymbolName(sym)}%-9s " +
          f"DUT=(driveLow=$gotLow, driveHigh=$gotHigh)  OK"
      )
    }
  }

  println("SymbolDecoderContentionSim: all cells contention-free")
}
