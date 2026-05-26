package mole

import spinal.core._

/** Spec-floor audit for [[MoleConfig]] defaults.
  *
  * Pure-Scala main, not a SpinalSim DUT. The data record has no
  * hardware state to exercise; what we want to *verify* at the
  * `sim-config` Makefile target is that the default config can
  * actually represent the bus frequencies Mole's v0 controller-role
  * use cases require — I²C standard / fast / fast-plus, I³C OD-low
  * and OD-mid. The I³C PP-high target (12.5 MHz SCL = 50 MHz
  * quarter rate) is *flagged* with a `println` rather than asserted
  * out — it requires either fabric > 50 MHz or a fractional divider,
  * both out of Phase-0 scope (ROADMAP §"Phased plan" gates I³C PP on
  * a later phase).
  *
  * `runMain` is the entry point because the sibling project uses the
  * same convention; `sbt -batch runMain mole.MoleConfigSim` is wired
  * into `make sim-config`. The default executable behaviour is
  * "assert and exit 0 on success; throw and exit non-zero on
  * failure", which is exactly what CI eventually wants.
  *
  * Run: `sbt "runMain mole.MoleConfigSim"`
  */
object MoleConfigSim extends App {

  val cfg = MoleConfig()

  println(s"--- MoleConfig defaults audit ---")
  println(s"fabricFreqHz             = ${cfg.fabricFreqHz.toBigDecimal} Hz")
  println(s"quarterPeriodCyclesReset = ${cfg.quarterPeriodCyclesReset}")
  println(s"programWordCount         = ${cfg.programWordCount}")
  println(s"resultRingByteCount      = ${cfg.resultRingByteCount}")
  println(s"captureMaxBits           = ${cfg.captureMaxBits}")
  println(s"uartBaud                 = ${cfg.uartBaud}")

  /** Wire bit-rate -> quarter rate (4 quarters per bit). */
  def quarterRate(bitHz: HertzNumber): HertzNumber = (bitHz.toBigDecimal * 4).toLong Hz

  /** Assert: `cfg` can produce a quarter-rate divider for `bitHz` that
    * fits in `MoleConfig.quarterPeriodCyclesReset`'s plausible range
    * (>= 1, integer). Returns the divider for the `println` log line.
    */
  def auditBus(label: String, bitHz: HertzNumber): Int = {
    val qHz = quarterRate(bitHz)
    val div = cfg.quarterPeriodCyclesFor(qHz)
    val achieved = cfg.fabricFreqHz.toBigDecimal / div
    val achievedBit = achieved / 4
    val errPct =
      ((achievedBit - bitHz.toBigDecimal) / bitHz.toBigDecimal * 100).toDouble
    f"$label%-26s target ${bitHz.toBigDecimal}%.0f Hz, quarter ${qHz.toBigDecimal}%.0f Hz, div=$div%4d, achieved ${achievedBit}%.0f Hz, err=${errPct}%+.3f%%" match {
      case line =>
        assert(div >= 1, s"$label: divider $div < 1 — fabric too slow")
        println(line)
        div
    }
  }

  // Required: every Phase-0 I²C / I³C-OD frequency must produce a
  // sane divider (>= 1, integer).
  auditBus("I2C standard 100 kHz",  100 kHz)
  auditBus("I2C fast 400 kHz",      400 kHz)
  auditBus("I2C fast-plus 1 MHz",   1 MHz)
  auditBus("I3C OD 2 MHz",          2 MHz)
  auditBus("I3C OD 4 MHz",          4 MHz)

  // Flagged: I³C PP-high (12.5 MHz SCL) requires fabric > 50 MHz or
  // a fractional divider. Phase 0 ships without it. Emit a warning
  // line but do not assert out — the design is allowed to miss
  // this target until Phase 1+.
  val ppHighBit = 12500000 Hz
  val ppHighQ   = quarterRate(ppHighBit)
  val ppHighDiv = cfg.quarterPeriodCyclesFor(ppHighQ)
  if (ppHighDiv < 1) {
    println(
      s"WARN: I3C PP 12.5 MHz needs quarter rate ${ppHighQ.toBigDecimal} Hz > fabric ${cfg.fabricFreqHz.toBigDecimal} Hz — fabric > 50 MHz or fractional divider required (deferred to Phase 1+)"
    )
  } else {
    auditBus("I3C PP 12.5 MHz", ppHighBit)
  }

  // Sanity: the reset divider must be one we could actually get from
  // an integer divider of fabric. (At 48 MHz / div=12 = 4 MHz quarter
  // = 1 MHz bit. Matches MoleConfig's documented default.)
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
  // that. Restate it here so a future MoleConfig default bump can't
  // silently violate the constraint — we'll see this assertion fire
  // before any UART is even instantiated.
  val uartOversample = 16
  val uartOsHz = cfg.uartBaud.toLong * uartOversample
  val fabricHz = cfg.fabricFreqHz.toBigDecimal.toLong
  assert(
    uartOsHz < fabricHz,
    s"UART DDS guard: uartBaud (${cfg.uartBaud}) × oversample ($uartOversample) " +
      s"= $uartOsHz Hz must be < fabricFreqHz ($fabricHz Hz)"
  )
  val phaseInc =
    BaudGenerator.phaseIncFor(fabricHz.toInt, uartOsHz.toInt)
  val realisedOsHz =
    (BigInt(phaseInc) * fabricHz) >> 24
  val realisedBaud = realisedOsHz / uartOversample
  println(
    f"UART: baud=${cfg.uartBaud}%d, oversample=$uartOversample%d, " +
      f"osHz=$uartOsHz%d, phaseInc=$phaseInc%d (0x${phaseInc.toHexString}%s), " +
      f"realised osHz=$realisedOsHz, realised baud=$realisedBaud"
  )

  println("MoleConfig defaults OK")
}
