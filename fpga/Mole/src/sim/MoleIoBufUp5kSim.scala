package mole

import spinal.core._
import spinal.core.sim._

/** Audit for [[MoleIoBufUp5k]].
  *
  * Two layers of coverage:
  *
  *   1. **Sim bypass (`useBlackBox = false`) functional test**: drive every
  *      legal `(driveLow, driveHigh)` combination on both lines and assert
  *      `read` follows the wired-AND truth table from the class scaladoc.
  *      Catches a regression in the bypass-mode bus model that MoleTopSim will
  *      rely on for the engine-driven smoke programs.
  *   2. **BlackBox (`useBlackBox = true`) Verilog elaboration test**: generate
  *      Verilog for the synth path and string-search the result for `SB_IO`
  *      instantiations + `PIN_TYPE` parameter. Catches the typo class where
  *      someone fixes the BlackBox port names but breaks the generic name, or
  *      accidentally drops a generic so yosys emits a naked SB_IO with default
  *      PIN_TYPE = no output.
  *
  * The contention case (`driveLow && driveHigh`) is enforced by a SpinalHDL
  * `assert(...)` in the Component; we deliberately do NOT exercise it from this
  * sim, since a failing SpinalHDL assert aborts the run before the outer
  * try/catch can intercept it. Coverage of the never-contention invariant lives
  * in [[BitCycleEngineSmokeSim]], which sweeps every `(tx_symbol, BUS_MODE)`
  * combination the engine can emit.
  *
  * Run: `sbt "runMain mole.MoleIoBufUp5kSim"`
  */
object MoleIoBufUp5kSim extends App {

  // --------------------------------------------------------------
  // Layer 1: sim bypass functional test
  // --------------------------------------------------------------

  println("--- MoleIoBufUp5kSim: bypass functional ---")
  SimConfig.compile(MoleIoBufUp5k(useBlackBox = false)).doSim("bypass") { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    // Idle defaults.
    dut.io.bus.sda.driveLow #= false
    dut.io.bus.sda.driveHigh #= false
    dut.io.bus.scl.driveLow #= false
    dut.io.bus.scl.driveHigh #= false
    dut.clockDomain.waitSampling(2)

    final case class Case(
        label: String,
        low: Boolean,
        high: Boolean,
        read: Boolean
    )
    val cases = Seq(
      Case("released", low = false, high = false, read = true),
      Case("driveLow", low = true, high = false, read = false),
      Case("driveHigh", low = false, high = true, read = true)
    )

    for (line <- Seq("sda", "scl")) {
      for (c <- cases) {
        line match {
          case "sda" =>
            dut.io.bus.sda.driveLow #= c.low
            dut.io.bus.sda.driveHigh #= c.high
          case "scl" =>
            dut.io.bus.scl.driveLow #= c.low
            dut.io.bus.scl.driveHigh #= c.high
        }
        // Same canonical "drive, edge, settle" pattern as Crc16XmodemSim.
        // The wrapper is combinational so the register-update race does
        // not strictly apply, but burning a quiet cycle is cheap and
        // matches the pattern future readers expect.
        dut.clockDomain.waitSampling()
        dut.clockDomain.waitSampling()
        val got = line match {
          case "sda" => dut.io.bus.sda.read.toBoolean
          case "scl" => dut.io.bus.scl.read.toBoolean
        }
        assert(
          got == c.read,
          f"bypass/$line/${c.label}: " +
            f"driveLow=${c.low}, driveHigh=${c.high} -> " +
            f"read=$got, expected=${c.read}"
        )
        println(f"  $line%-3s ${c.label}%-9s  read=$got  OK")
        // Release both drivers before the next case so the next iteration
        // starts from a clean baseline.
        line match {
          case "sda" =>
            dut.io.bus.sda.driveLow #= false
            dut.io.bus.sda.driveHigh #= false
          case "scl" =>
            dut.io.bus.scl.driveLow #= false
            dut.io.bus.scl.driveHigh #= false
        }
        dut.clockDomain.waitSampling()
      }
    }
  }

  // --------------------------------------------------------------
  // Layer 2: BlackBox Verilog elaboration check
  // --------------------------------------------------------------

  println("--- MoleIoBufUp5kSim: BlackBox elaboration ---")
  val report = SpinalConfig(targetDirectory = "gen/sim-iobuf")
    .generateVerilog(MoleIoBufUp5k(useBlackBox = true))

  // Locate the generated Verilog file by toplevel name (deterministic;
  // a stale .v file in the directory cannot false-pass / false-fail
  // the way `listFiles().find(_.endsWith(".v"))` would). We do not
  // try to actually compile the SB_IO model under Verilator; the
  // icestorm cell model is not in SpinalSim's path. The string-search
  // is a cheap and adequate proxy for "the BlackBox parameters
  // survived elaboration".
  val verilogFile =
    new java.io.File("gen/sim-iobuf/" + report.toplevelName + ".v")
  assert(
    verilogFile.exists(),
    s"expected Verilog file ${verilogFile.getAbsolutePath} not found"
  )
  val verilog = scala.io.Source.fromFile(verilogFile).mkString

  val sbIoCount = "SB_IO\\b".r.findAllIn(verilog).length
  // Expect at least 2 instantiations (one per line) plus possibly a
  // module declaration if SpinalHDL emitted one; >= 2 is the floor.
  assert(
    sbIoCount >= 2,
    s"expected >= 2 occurrences of 'SB_IO' in generated Verilog, got $sbIoCount" +
      s" (file: ${verilogFile.getAbsolutePath})"
  )
  println(f"  SB_IO occurrences = $sbIoCount  OK")

  // The PIN_TYPE generic must reach the Verilog. SpinalHDL has
  // serialised Bits literals as 6'b101000, 6'h28, decimal 40, and
  // (historically) packed-into-32-bit forms across versions; accept
  // every encoding that means "binary 101000". The bit-pattern is
  // the unambiguous PIN_OUTPUT_TRISTATE | PIN_INPUT encoding from
  // the icestorm wiki.
  assert(
    verilog.contains("PIN_TYPE"),
    s"PIN_TYPE generic missing from generated Verilog" +
      s" (file: ${verilogFile.getAbsolutePath})"
  )
  val pinTypeEncodings = Seq(
    "6'b101000", // SpinalHDL canonical Bits-literal form
    "6'h28", // hex form (0x28 = 0b101000)
    "'h28", // hex form without explicit width
    "101000", // bare binary digits, e.g. inside a decimal string
    "= 40" // decimal 40 with a leading equals (defparam form)
  )
  val pinTypeMatch = pinTypeEncodings.find(verilog.contains(_))
  assert(
    pinTypeMatch.isDefined,
    s"PIN_TYPE value (binary 101000) missing from generated Verilog;" +
      s" looked for one of ${pinTypeEncodings.mkString(", ")}" +
      s" (file: ${verilogFile.getAbsolutePath})"
  )
  println(f"  PIN_TYPE = 6'b101000 present (as ${pinTypeMatch.get})  OK")

  // OUTPUT_ENABLE wiring marker: the wrapper assigns
  //   OUTPUT_ENABLE = driveLow || driveHigh.
  // SpinalHDL serialises that as an OR. Searching for the port name
  // alone is enough to prove the connection survived; the OR itself
  // is exercised by the bypass-mode functional test above.
  assert(
    verilog.contains("OUTPUT_ENABLE"),
    s"OUTPUT_ENABLE port missing from generated Verilog"
  )
  println("  OUTPUT_ENABLE wired             OK")

  // D_OUT_0 port (driveHigh routed in) and D_IN_0 (live pad value
  // routed back to MoleBus.read) markers.
  assert(
    verilog.contains("D_OUT_0") && verilog.contains("D_IN_0"),
    s"D_OUT_0 / D_IN_0 port wiring missing"
  )
  println("  D_OUT_0 / D_IN_0 wired          OK")

  println("MoleIoBufUp5kSim: all cases passed")
}
