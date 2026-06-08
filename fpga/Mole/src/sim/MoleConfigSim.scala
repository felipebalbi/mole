package mole

import spinal.core._

/** Spec-floor audit for [[MoleConfig]] defaults.
  *
  * Pure-Scala main, not a SpinalSim DUT. The data record has no hardware state
  * to exercise; what we want to *verify* at the `sim-config` Makefile target is
  * that the default config can actually represent the bus frequencies Mole's
  * v0.2 controller-role use cases require — I²C standard / fast / fast-plus,
  * I³C OD-low and OD-mid. The I³C PP-high target (12.5 MHz SCL = 50 MHz quarter
  * rate) is *flagged* with a `println` rather than asserted out — it is NOT
  * supported on Mole Verde's 48 MHz fabric (requires fabric > 50 MHz or a
  * fractional divider); full-rate I3C SDR is Mole Rojo (ECP5) territory per
  * ROADMAP §"Hardware tiers".
  *
  * `runMain` is the entry point because the sibling project uses the same
  * convention; `sbt -batch runMain mole.MoleConfigSim` is wired into
  * `make sim-config`. The default executable behaviour is "assert and exit 0 on
  * success; throw and exit non-zero on failure", which is exactly what CI
  * eventually wants.
  *
  * Run: `sbt "runMain mole.MoleConfigSim"`
  */
object MoleConfigSim extends App {

  val cfg = MoleConfig()

  println(s"--- MoleConfig defaults audit ---")
  println(s"fabricFreqHz             = ${cfg.fabricFreqHz.toBigDecimal} Hz")
  println(s"uartFreqHz               = ${cfg.uartFreqHz} Hz")
  println(s"quarterPeriodCyclesReset = ${cfg.quarterPeriodCyclesReset}")
  println(s"programWordCount         = ${cfg.programWordCount}")
  println(s"resultRingByteCount      = ${cfg.resultRingByteCount}")
  println(s"captureMaxBits           = ${cfg.captureMaxBits}")
  println(s"uartBaud                 = ${cfg.uartBaud}")
  println(s"role                     = ${cfg.role}")

  /** Wire bit-rate -> quarter rate (4 quarters per bit). */
  def quarterRate(bitHz: HertzNumber): HertzNumber =
    (bitHz.toBigDecimal * 4).toLong Hz

  /** Assert: `cfg` can produce a quarter-rate divider for `bitHz` that fits in
    * `MoleConfig.quarterPeriodCyclesReset`'s plausible range (>= 1, integer)
    * AND lands within ±5 % of the requested bit rate. The error band catches
    * the case where truncation pushes the achieved frequency well above the
    * target. The Phase-0 buses we actually need on Mole Verde (I2C SM/FM/FM+,
    * I3C OD up to 4 MHz) all fall inside the band at 48 MHz; the asserter
    * catches a future fabric change (or a new bus target) that silently warps a
    * bus speed. Returns the divider for the `println` log line.
    */
  def auditBus(
      label: String,
      bitHz: HertzNumber,
      errTolPct: Double = 5.0
  ): Int = {
    val qHz = quarterRate(bitHz)
    val div = cfg.quarterPeriodCyclesFor(qHz)
    val achieved = cfg.fabricFreqHz.toBigDecimal / div
    val achievedBit = achieved / 4
    val errPct =
      ((achievedBit - bitHz.toBigDecimal) / bitHz.toBigDecimal * 100).toDouble
    f"$label%-26s target ${bitHz.toBigDecimal}%.0f Hz, quarter ${qHz.toBigDecimal}%.0f Hz, div=$div%4d, achieved ${achievedBit}%.0f Hz, err=${errPct}%+.3f%%" match {
      case line =>
        assert(div >= 1, s"$label: divider $div < 1 — fabric too slow")
        assert(
          math.abs(errPct) <= errTolPct,
          s"$label: achieved $achievedBit Hz deviates ${errPct}%% from " +
            s"target ${bitHz.toBigDecimal} Hz, exceeds ±$errTolPct%% tolerance " +
            s"(fabric=${cfg.fabricFreqHz.toBigDecimal} Hz, div=$div)"
        )
        println(line)
        div
    }
  }

  // Required: every Phase-0 I²C / I³C-OD frequency Mole Verde claims to
  // support must produce an integer divider with ≤ 5 % bit-rate error.
  // At 48 MHz all five standard rates are clean integers — an improvement
  // over v0 24 MHz where I3C OD 4 MHz overshot to 6 MHz (+50 %).
  //
  // Derived dividers at 48 MHz (fabricFreqHz / (bitHz × 4)):
  //   I2C 100 kHz:  48_000_000 / (100_000 × 4) = 120
  //   I2C 400 kHz:  48_000_000 / (400_000 × 4) = 30
  //   I2C 1 MHz:    48_000_000 / (1_000_000 × 4) = 12
  //   I3C OD 2 MHz: 48_000_000 / (2_000_000 × 4) = 6
  //   I3C OD 4 MHz: 48_000_000 / (4_000_000 × 4) = 3
  val div100k = auditBus("I2C standard 100 kHz", 100 kHz)
  assert(
    div100k == 120,
    s"I2C 100 kHz divider should be 120 at 48 MHz; got $div100k"
  )

  val div400k = auditBus("I2C fast 400 kHz", 400 kHz)
  assert(
    div400k == 30,
    s"I2C 400 kHz divider should be 30 at 48 MHz; got $div400k"
  )

  val div1m = auditBus("I2C fast-plus 1 MHz", 1 MHz)
  assert(div1m == 12, s"I2C 1 MHz divider should be 12 at 48 MHz; got $div1m")

  val div2m = auditBus("I3C OD 2 MHz", 2 MHz)
  assert(div2m == 6, s"I3C OD 2 MHz divider should be 6 at 48 MHz; got $div2m")

  // I3C OD 4 MHz: at 48 MHz the divider is exactly 3 (integer, clean).
  // This was problematic at v0 24 MHz where the divider truncated to 1
  // and overshot to 6 MHz (+50 %). 48 MHz resolves that issue cleanly.
  val div4m = auditBus("I3C OD 4 MHz", 4 MHz)
  assert(div4m == 3, s"I3C OD 4 MHz divider should be 3 at 48 MHz; got $div4m")

  // Flagged: I³C SDR PP-high (12.5 MHz SCL = 50 MHz quarter rate) requires
  // fabric > 50 MHz or a fractional divider. NOT supported on Mole Verde at
  // 48 MHz. Full-rate I3C SDR moves to Mole Rojo (ECP5, 100 MHz fabric).
  // Emit a warning line but do not assert out.
  val ppHighBit = 12500000 Hz
  val ppHighQ = quarterRate(ppHighBit)
  val ppHighDiv = cfg.quarterPeriodCyclesFor(ppHighQ)
  if (ppHighDiv < 1) {
    println(
      s"WARN: I3C PP 12.5 MHz needs quarter rate ${ppHighQ.toBigDecimal} Hz > fabric ${cfg.fabricFreqHz.toBigDecimal} Hz — " +
        "NOT supported on Mole Verde 48 MHz fabric; full-rate I3C SDR is Mole Rojo (ECP5) territory"
    )
  } else {
    // ppHighDiv >= 1 but still likely fractional (0.96 truncated to 0 normally
    // at 48 MHz, so this branch should not be hit unless fabricFreqHz changes).
    val ppHighAchievedBit =
      (cfg.fabricFreqHz.toBigDecimal / ppHighDiv) / 4
    val ppHighErrPct =
      ((ppHighAchievedBit - ppHighBit.toBigDecimal)
        / ppHighBit.toBigDecimal * 100).toDouble
    if (math.abs(ppHighErrPct) > 5.0) {
      println(
        f"WARN: I3C SDR 12.5 MHz lands at div=$ppHighDiv → achieved ${ppHighAchievedBit}%.0f Hz, " +
          f"err=${ppHighErrPct}%+.1f%% > 5%% — NOT supported on Mole Verde's 48 MHz fabric. " +
          "Full-rate I3C SDR is Mole Rojo (ECP5) territory."
      )
    } else {
      auditBus("I3C PP 12.5 MHz", ppHighBit)
    }
  }

  // Sanity: the reset divider must be one we could actually get from
  // an integer divider of fabric. (At 48 MHz / div=6 = 8 MHz quarter
  // = 2 MHz bit. Matches MoleConfig's documented default.)
  assert(
    cfg.quarterPeriodCyclesReset >= 1,
    s"quarterPeriodCyclesReset=${cfg.quarterPeriodCyclesReset} < 1"
  )
  val resetQuarter =
    cfg.fabricFreqHz.toBigDecimal / cfg.quarterPeriodCyclesReset
  val resetBit = resetQuarter / 4
  println(
    f"reset divider ${cfg.quarterPeriodCyclesReset}%d → quarter ${resetQuarter}%.0f Hz → bit ${resetBit}%.0f Hz"
  )

  // UART / DDS audit. Mole's host-link UART runs the RX BaudGenerator
  // at baudRate × oversample (16× is the imported sibling default,
  // textbook for UART RX). The 24-bit DDS accumulator overflows once
  // `baudRate * oversample >= clkFreqHz`; UartConfig already guards
  // that. Restate it here against uartFreqHz (the uartClk domain, 24
  // MHz) so a future MoleConfig default bump can't silently violate
  // the constraint — we'll see this assertion fire before any UART is
  // even instantiated.
  val uartOversample = 16
  val uartOsHz = cfg.uartBaud.toLong * uartOversample
  val uartClkHz = cfg.uartFreqHz.toLong
  assert(
    uartOsHz < uartClkHz,
    s"UART DDS guard: uartBaud (${cfg.uartBaud}) × oversample ($uartOversample) " +
      s"= $uartOsHz Hz must be < uartFreqHz ($uartClkHz Hz)"
  )
  val phaseInc =
    BaudGenerator.phaseIncFor(uartClkHz.toInt, uartOsHz.toInt)
  val realisedOsHz =
    (BigInt(phaseInc) * uartClkHz) >> 24
  val realisedBaud = realisedOsHz / uartOversample
  println(
    f"UART: baud=${cfg.uartBaud}%d, oversample=$uartOversample%d, " +
      f"osHz=$uartOsHz%d, phaseInc=$phaseInc%d (0x${phaseInc.toHexString}%s), " +
      f"realised osHz=$realisedOsHz, realised baud=$realisedBaud"
  )

  // Role audit: default must be Controller (today's shipping behaviour
  // and the only role exercised by Steps 1..18). Catches an accidental
  // default flip that would silently retarget every bitstream.
  assert(
    cfg.role == EngineRole.Controller,
    s"default MoleConfig.role must be Controller, was ${cfg.role}"
  )
  println(s"role audit: default = ${cfg.role}, override = Target available")

  println("MoleConfig defaults OK")
}
