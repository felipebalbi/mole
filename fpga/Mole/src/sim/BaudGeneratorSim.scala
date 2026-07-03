// SPDX-FileCopyrightText: 2026 Felipe Balbi
// SPDX-License-Identifier: CERN-OHL-W-2.0

package mole

// Imported from felipebalbi/icebreaker-spinalhdl-examples@98c06a8cf39f6c212d3b46d98868aca398a82186 Uart/src/sim/BaudGeneratorSim.scala.
// Modified for Mole only as documented inline (search for 'Mole').

import spinal.core._
import spinal.core.sim._

/** Standalone sim for [[BaudGenerator]].
  *
  * Strategy We deliberately pick a *small* clkFreqHz / baudRate ratio
  * (1_000_000 / 100_000 = 10) so that bit periods are short enough to simulate
  * many of them quickly, while still being non-trivial. With accWidth = 24, the
  * rounded phaseInc gives an essentially exact 10-cycle average period.
  *
  * What we check
  *   1. With `enable = false`, no ticks ever appear. 2. After raising `enable`,
  *      the *first* tick lands within [ticksPerBit, ticksPerBit + 1] cycles
  *      (DDS jitter is ±1 cycle, plus 1 cycle for the registered tick output).
  *      3. Subsequent inter-tick spacings stay within {ticksPerBit-1,
  *         ticksPerBit, ticksPerBit+1} cycles. 4. Each tick is exactly one
  *         cycle wide. 5. The *average* spacing over many ticks matches
  *         `ticksPerBit` to within a small tolerance — proving DDS accuracy. 6.
  *         Dropping `enable` cleanly stops ticks; re-enabling restarts the
  *         cadence from a known phase.
  *
  * Run: `sbt "runMain mole.BaudGeneratorSim"`
  */
object BaudGeneratorSim {
  def main(args: Array[String]): Unit = {

    // Small ratio keeps the sim fast while exercising the DDS math.
    // Mole: bumped clk from 1_000_000 to 2_000_000 (ticksPerBit
    // 10 -> 20) so the configuration satisfies Mole's added
    // require `baudRate * oversample < clkFreqHz` in UartConfig.
    val cfg = UartConfig(
      clkFreqHz = 2000000,
      baudRate = 100000 // => ticksPerBit = 20
    )
    val ticksPerBit = cfg.ticksPerBit
    val numTicks = 200 // enough samples to average out jitter

    SimConfig.withWave
      .compile(BaudGenerator(accWidth = 24))
      .doSim { dut =>
        dut.clockDomain.forkStimulus(period = 10)
        dut.io.phaseInc #= BaudGenerator.phaseIncFor(cfg, dut.accWidth)

        // ---- (1) enable low: should never tick ----------------------------
        dut.io.enable #= false
        dut.clockDomain.waitSampling(50)
        for (_ <- 0 until 50) {
          dut.clockDomain.waitSampling()
          assert(!dut.io.tick.toBoolean, "tick fired while enable=false")
        }

        // ---- (2)+(3)+(4)+(5) measure tick cadence -------------------------
        dut.io.enable #= true

        // Wait for the first tick, bounding our patience.
        var firstWait = 0
        val maxFirst = ticksPerBit + 4 // generous: includes RegNext latency
        while (!dut.io.tick.toBoolean && firstWait < maxFirst + 10) {
          dut.clockDomain.waitSampling()
          firstWait += 1
        }
        assert(
          dut.io.tick.toBoolean,
          s"no tick within $maxFirst cycles after enable"
        )
        assert(
          firstWait <= maxFirst,
          s"first tick took $firstWait cycles, expected <= $maxFirst"
        )
        println(s"first tick at cycle $firstWait")

        // Now sample inter-tick gaps.
        val gaps = scala.collection.mutable.ArrayBuffer[Int]()
        var widthErr = false
        var prev = 0
        var cycle = 0
        // sanity: the tick we just observed should fall on the next cycle's
        // sample-and-hold; advance one cycle so we're past it.
        dut.clockDomain.waitSampling()
        // Check it dropped.
        if (dut.io.tick.toBoolean) widthErr = true
        cycle = 0

        while (gaps.size < numTicks) {
          dut.clockDomain.waitSampling()
          cycle += 1
          if (dut.io.tick.toBoolean) {
            gaps += (cycle - prev)
            prev = cycle
            // Width check: must drop next cycle.
            dut.clockDomain.waitSampling()
            cycle += 1
            if (dut.io.tick.toBoolean) widthErr = true
          }
        }

        assert(!widthErr, "saw a tick wider than 1 cycle")

        // Per-gap bound: ticksPerBit ± 1 cycle.
        for (g <- gaps) {
          assert(
            g >= ticksPerBit - 1 && g <= ticksPerBit + 1,
            s"inter-tick gap $g out of [${ticksPerBit - 1}, ${ticksPerBit + 1}]"
          )
        }

        // Long-term average: should be within 0.5% of ticksPerBit.
        val avg = gaps.map(_.toDouble).sum / gaps.size
        val err = math.abs(avg - ticksPerBit) / ticksPerBit
        println(
          f"avg gap = $avg%.4f cycles (target $ticksPerBit, err = ${err * 100}%.4f%%)"
        )
        assert(err < 0.005, f"avg baud error $err too large")

        // ---- (6) disable / re-enable -------------------------------------
        dut.io.enable #= false
        dut.clockDomain.waitSampling(ticksPerBit * 3)
        for (_ <- 0 until ticksPerBit * 3) {
          dut.clockDomain.waitSampling()
          assert(!dut.io.tick.toBoolean, "tick fired after enable was lowered")
        }
        dut.io.enable #= true
        var restartWait = 0
        while (!dut.io.tick.toBoolean && restartWait < ticksPerBit + 10) {
          dut.clockDomain.waitSampling()
          restartWait += 1
        }
        assert(
          dut.io.tick.toBoolean,
          s"no tick after re-enable within ${ticksPerBit + 10} cycles"
        )
        println(s"restart tick at cycle $restartWait after re-enable")

        println("OK: BaudGenerator behaves correctly")
      }
  }
}
