package mole

import spinal.core._
import spinal.core.sim._
import spinal.lib._

/** `LOAD_TIMING` divider-swap audit on the controller-role SCL waveform.
  *
  * The v0 stash under the same name covered the SCL-stretch-aware guard --- a
  * subject that has since moved to `BitCycleEngineStretchRoleSim`. The C.8
  * resurrection retargets this name onto the other thing it could reasonably
  * test: that `LOAD_TIMING reg, divider` actually changes the engine's quarter-
  * bit cadence at runtime.
  *
  * ==Cases==
  *
  *   1. `defaultDivider_yieldsResetWidth` --- run a 4-bit EMIT_BIT_IMM stream
  *      under `SET_BUS_MODE i2c` without touching `LOAD_TIMING`. Measure the
  *      longest contiguous SCL-low run in the bus trace; assert it matches the
  *      reset divider's `2 * (D + 1)` SCL-low budget within the per-quarter
  *      pacing tolerance (timer-pause-during-fetch absorbs a small constant).
  *   2. `loadTimingReg0_widensWaveform` --- prime `LOAD_TIMING reg=0,
  *      divider=12`, then re-issue the same EMIT_BIT_IMM stream. Assert the
  *      longest SCL-low run scales by approximately `(12 + 1) / (Dreset + 1)`
  *      versus case 1. The ratio cancels out the timer-pause overhead so the
  *      test does not depend on the absolute pipeline-overhead count.
  *
  * The test deliberately measures a ratio against case 1's observation rather
  * than asserting absolute fabric-cycle counts. The pipeline's fetch/decode
  * overhead (during which `timer.enable` is dropped) inflates the raw count by
  * a small constant, but the ratio between two divider settings is stable
  * across that constant.
  *
  * Run: `sbt "runMain mole.BitCycleEngineStretchSim"`
  */
object BitCycleEngineStretchSim extends App {

  // v0.2 default fabric / quarter-period settings, matching the C.7 sim
  // template (BitCycleEngineEmitBitDataHoldSim).
  private val cfg = MoleConfig(
    fabricFreqHz = 48 MHz,
    quarterPeriodCyclesReset = 6,
    programWordCount = 64,
    resultRingByteCount = 64,
    captureMaxBits = 65536,
    uartBaud = 1_000_000
  )

  // Reset divider value loaded into every `loadTimingRegs(i)` slot at
  // power-on (see EnginePipeline.scala line 362):
  //   `Reg(UInt(14 bits)) init (cfg.quarterPeriodCyclesReset - 1)`.
  // With quarterPeriodCyclesReset=6 the reset divider is 5, giving a
  // quarter-bit dwell of (5 + 1) = 6 fabric cycles when the timer is
  // continuously enabled.
  private val dividerReset: Int = cfg.quarterPeriodCyclesReset - 1
  private val dividerWide: Int = 12

  private lazy val compiled =
    SimConfig.withWave.compile(BitCycleEngineSmokeDut(cfg))

  // ------------------------------------------------------------------
  // Shared sim plumbing.
  // ------------------------------------------------------------------

  private case class BusSample(sdaLow: Boolean, sclLow: Boolean)

  private def sampleBus(dut: BitCycleEngineSmokeDut): BusSample =
    BusSample(
      sdaLow = dut.io.sda.driveLow.toBoolean,
      sclLow = dut.io.scl.driveLow.toBoolean
    )

  private def quiet(dut: BitCycleEngineSmokeDut): Unit = {
    dut.io.engineStart #= false
    dut.io.programLength #= 0
    dut.io.loaderWrite.valid #= false
    dut.io.loaderWrite.payload.addr #= 0
    dut.io.loaderWrite.payload.data #= 0
    dut.io.debugReadCmd.valid #= false
    dut.io.debugReadCmd.payload #= 0
    dut.io.sda.read #= true
    dut.io.scl.read #= true
  }

  private def loaderWrite(
      dut: BitCycleEngineSmokeDut,
      addr: Int,
      word: Int
  ): Unit = {
    dut.io.loaderWrite.valid #= true
    dut.io.loaderWrite.payload.addr #= addr
    dut.io.loaderWrite.payload.data #= word
    dut.clockDomain.waitSamplingWhere(dut.io.loaderWrite.ready.toBoolean)
    dut.io.loaderWrite.valid #= false
  }

  private def load(dut: BitCycleEngineSmokeDut, program: Seq[Int]): Unit = {
    for ((word, idx) <- program.zipWithIndex) loaderWrite(dut, idx, word)
    dut.clockDomain.waitSampling(2)
  }

  /** Longest contiguous run of `sclLow == true` in the trace. Returns 0 if SCL
    * never went low.
    */
  private def longestSclLowRun(trace: Seq[BusSample]): Int = {
    var best = 0
    var cur = 0
    for (s <- trace) {
      if (s.sclLow) {
        cur += 1
        if (cur > best) best = cur
      } else {
        cur = 0
      }
    }
    best
  }

  /** Run a program to completion and return the post-start bus trace. */
  private def runAndTrace(
      dut: BitCycleEngineSmokeDut,
      program: Seq[Int],
      safetyLimit: Int = 4000
  ): Seq[BusSample] = {
    load(dut, program)
    dut.io.programLength #= program.length
    dut.io.engineStart #= true

    val trace = collection.mutable.ArrayBuffer.empty[BusSample]
    while (!dut.io.halted.toBoolean && trace.size < safetyLimit) {
      trace += sampleBus(dut)
      dut.clockDomain.waitSampling()
    }
    dut.io.engineStart #= false
    assert(
      dut.io.halted.toBoolean,
      s"engine never halted (ran $safetyLimit cycles)"
    )
    assert(
      dut.io.haltStatus.toInt == 0,
      f"unexpected halt status 0x${dut.io.haltStatus.toInt}%02x"
    )
    trace.toSeq
  }

  import Instruction._

  /** Four-bit EMIT_BIT_IMM stream under SET_BUS_MODE i2c, terminated by HALT 0.
    * Sets long enough to expose at least one quarter-low dwell that is not
    * truncated by fetch overhead at either end.
    */
  private def emitBitProgram(preface: Seq[Int]): Seq[Int] = {
    val emit = (s: TxSymbol.E) =>
      encode(EmitBitImm(s, expect = false, mask = false, capture = false))
    preface ++ Seq(
      encode(SetBusMode(BusMode.i2c)),
      emit(TxSymbol.dominant),
      emit(TxSymbol.recessive),
      emit(TxSymbol.dominant),
      emit(TxSymbol.recessive),
      encode(Halt(0))
    )
  }

  // ------------------------------------------------------------------
  // Case 1: default divider yields ~2*(Dreset+1) longest SCL-low run.
  // ------------------------------------------------------------------

  private var longestRunDefault: Int = 0

  private def runDefaultDividerYieldsResetWidth(): Unit = {
    val label = "defaultDivider_yieldsResetWidth"
    println(s"--- BitCycleEngineStretchSim: $label ---")
    compiled.doSim(label) { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.clockDomain.waitSampling(5)

      val trace = runAndTrace(dut, emitBitProgram(preface = Seq.empty))
      val run = longestSclLowRun(trace)

      // The expected nominal low width is 2 * (Dreset + 1) fabric
      // cycles. Pipeline / pad timing add up to a small constant slack;
      // the soft lower bound is the nominal value, and we cap at
      // 2 * (Dreset + 1) + slack to catch a runaway timer-enable bug.
      val expected = 2 * (dividerReset + 1)
      val slack = 4
      assert(
        run >= expected,
        s"$label: longest SCL-low run $run < expected nominal $expected " +
          s"(divider=$dividerReset; quarterPeriodCyclesReset=" +
          s"${cfg.quarterPeriodCyclesReset})"
      )
      assert(
        run <= expected + slack,
        s"$label: longest SCL-low run $run >> expected nominal $expected " +
          s"(+slack $slack); the timer may be free-running past one bit"
      )
      longestRunDefault = run
      println(
        s"$label OK (longest SCL-low run = $run; nominal = $expected)"
      )
    }
  }

  // ------------------------------------------------------------------
  // Case 2: LOAD_TIMING reg=0 divider=12 widens the SCL-low run by the
  // expected ratio against case 1.
  // ------------------------------------------------------------------

  private def runLoadTimingReg0WidensWaveform(): Unit = {
    val label = "loadTimingReg0_widensWaveform"
    println(s"--- BitCycleEngineStretchSim: $label ---")
    assert(
      longestRunDefault > 0,
      s"$label: case 1 must have run first (longestRunDefault=0)"
    )
    compiled.doSim(label) { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.clockDomain.waitSampling(5)

      val preface = Seq(encode(LoadTiming(reg = 0, divider = dividerWide)))
      val trace = runAndTrace(dut, emitBitProgram(preface))
      val run = longestSclLowRun(trace)

      val expectedRatio =
        (dividerWide + 1).toDouble / (dividerReset + 1).toDouble
      val measuredRatio = run.toDouble / longestRunDefault.toDouble

      // Allow a ±25% band on the ratio to absorb fetch-overhead bias
      // (a small additive constant on both numerator and denominator
      // shrinks the measured ratio slightly relative to the analytic
      // one; the band is wide enough to ride out that bias but tight
      // enough that a no-op LOAD_TIMING --- the regression --- would
      // flunk it at ratio ≈ 1.0).
      val ratioLo = expectedRatio * 0.75
      val ratioHi = expectedRatio * 1.25
      assert(
        measuredRatio >= ratioLo && measuredRatio <= ratioHi,
        f"$label: measured ratio $measuredRatio%.2f out of band " +
          f"[$ratioLo%.2f, $ratioHi%.2f] vs nominal $expectedRatio%.2f " +
          s"(default-run=$longestRunDefault, wide-run=$run, " +
          s"Dwide=$dividerWide, Dreset=$dividerReset)"
      )
      println(
        f"$label OK (default-run=$longestRunDefault, wide-run=$run, " +
          f"ratio=$measuredRatio%.2f vs nominal $expectedRatio%.2f)"
      )
    }
  }

  runDefaultDividerYieldsResetWidth()
  runLoadTimingReg0WidensWaveform()
  println("BitCycleEngineStretchSim: all 2 cases passed")
}
