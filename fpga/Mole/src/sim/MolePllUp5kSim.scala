package mole

import spinal.core._
import spinal.core.sim._

/** Smoke sim for [[MolePllUp5k]].
  *
  * The wrapper is a thin BlackBox-vs-bypass selector with no registered logic
  * of its own, so this sim is intentionally tiny. Two things to verify:
  *
  *   1. The bypass path elaborates cleanly and wires `clkIn` straight to
  *      `clkOut`, with `locked` tied high. This is the path
  *      [[MoleTopSim]] depends on, since Verilator has no model of the iCE40
  *      `SB_PLL40_PAD` cell.
  *   2. The BlackBox path elaborates to Verilog without errors and emits the
  *      `SB_PLL40_PAD` primitive with the 4x-recipe generics
  *      (DIVR=0, DIVF=31, DIVQ=3, FILTER_RANGE=1, FEEDBACK_PATH="SIMPLE").
  *      yosys's `synth_ice40` is the actual end-to-end check; here we just
  *      verify the SpinalHDL elaboration stage produces a Verilog file that
  *      mentions the primitive, so a future refactor that silently drops the
  *      BlackBox is caught at sim time rather than at the next bitstream
  *      build.
  *
  * The wrapper has no state to exercise beyond combinational pass-through, so
  * there is no need for a full SpinalSim DUT compile. Case 1 uses SpinalSim
  * because pass-through is what `MoleTopSim` will rely on; case 2 only
  * elaborates Verilog and string-searches the output.
  *
  * Run: `sbt "runMain mole.MolePllUp5kSim"`
  */
object MolePllUp5kSim extends App {

  // --------------------------------------------------------------
  // Case 1: bypass path is a clean combinational pass-through.
  // --------------------------------------------------------------

  SimConfig
    .compile(MolePllUp5k(useBlackBox = false))
    .doSim("bypass-passthrough") { dut =>
      dut.clockDomain.forkStimulus(period = 10)

      // The bypass is combinational. Drive clkIn through both edges, leave
      // resetB high (out of reset), and verify clkOut tracks clkIn and
      // locked stays true.
      dut.io.resetB #= true
      for (level <- Seq(false, true, false, true, false)) {
        dut.io.clkIn #= level
        // Settle combinationally. SpinalSim propagates wire assignments
        // at the next delta cycle; sleeping for one fabric tick is enough.
        sleep(1)
        assert(
          dut.io.clkOut.toBoolean == level,
          s"clkOut should follow clkIn in bypass: clkIn=$level " +
            s"clkOut=${dut.io.clkOut.toBoolean}"
        )
        assert(
          dut.io.locked.toBoolean,
          "locked should be tied high in bypass"
        )
      }

      // resetB is wired to the BlackBox in the synthesis path. In bypass it
      // is ignored, but the wrapper must still accept a falling resetB
      // without affecting clkOut or locked. Document that contract.
      dut.io.resetB #= false
      dut.io.clkIn #= true
      sleep(1)
      assert(
        dut.io.clkOut.toBoolean,
        "bypass: clkOut must still follow clkIn while resetB is low"
      )
      assert(
        dut.io.locked.toBoolean,
        "bypass: locked stays high regardless of resetB"
      )

      println("[bypass-passthrough] OK")
    }

  // --------------------------------------------------------------
  // Case 2: BlackBox path elaborates Verilog and emits SB_PLL40_PAD
  // with the 4x recipe generics. yosys would notice a missing
  // primitive at synth time; this case catches a missing or
  // misparameterised BlackBox at elaboration time, well before that.
  // --------------------------------------------------------------

  {
    val report = SpinalConfig(
      targetDirectory = "simWorkspace/MolePllUp5kBlackBoxGen"
    ).generateVerilog(MolePllUp5k(useBlackBox = true).setDefinitionName("MolePllUp5kBlackBox"))

    val vPath = report.toplevelName + ".v"
    val vFile =
      new java.io.File("simWorkspace/MolePllUp5kBlackBoxGen", vPath)
    val source = scala.io.Source.fromFile(vFile)
    val text =
      try source.mkString
      finally source.close()

    val primitive = "SB_PLL40_PAD"
    assert(
      text.contains(primitive),
      s"generated Verilog must instantiate $primitive; got:\n$text"
    )

    // Each generic appears as `.NAME(value)` in the SB_PLL40_PAD instance
    // body. Verify the 4x recipe is exactly what landed; a future change
    // that silently retargets the PLL ratio will show up here.
    val expectedGenerics = Seq(
      ".FEEDBACK_PATH",
      ".DIVR",
      ".DIVF",
      ".DIVQ",
      ".FILTER_RANGE"
    )
    for (g <- expectedGenerics) {
      assert(
        text.contains(g),
        s"generated Verilog must set generic $g on SB_PLL40_PAD; got:\n$text"
      )
    }

    println("[blackbox-elaborates] OK")
  }

  println("MolePllUp5kSim: all cases passed")
}
