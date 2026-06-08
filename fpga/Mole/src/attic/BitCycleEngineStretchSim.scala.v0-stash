package mole

import spinal.core._
import spinal.core.sim._
import spinal.lib._

/** Stretch-aware controller-role `EMIT_BIT` timing scenarios.
  *
  * Regression coverage for the spec at
  * `docs/superpowers/specs/2026-06-02-stretch-aware-emit-bit-design.md`. The
  * new behaviour: at every Q1->Q2 timer tick in controller role the engine
  * commits the SCL recessive symbol and then observes `observer.sclSampled`.
  * Under OD-class `BUS_MODE` it pauses the quarter timer until SCL releases
  * (bounded by `MoleConfig.stretchTimeoutCycles`); under PP-class `BUS_MODE` it
  * HALTs 0xD immediately.
  *
  * ==Cases==
  *
  *   1. `stretchQ1ToQ2_releasesBeforeTimeout` --- slave holds SCL low for ~30
  *      fabric cycles past nominal Q2 entry, then releases. Engine should pause
  *      + resume + complete bit cleanly.
  *   2. `stretchQ3ToQ0_crossBitBoundary` --- slave holds SCL low across two
  *      adjacent EMIT_BITs' nominal boundaries; next bit's Q1->Q2 catches it.
  *   3. `stretchNeverReleased_timesOutAndHalts` --- slave wedges SCL low
  *      forever; engine eventually times out with HALT 0xD, MISMATCH set. NOTE:
  *      this case runs with the DEFAULT `stretchTimeoutCycles` (2^20 ~ 44 ms
  *      simulated time) once the stretch-aware engine lands. With Verilator
  *      that is ~5 s of wall-clock. We deliberately do NOT reference a
  *      `cfg.stretchTimeoutCycles` field today because the field does not yet
  *      exist in `MoleConfig`; introducing it here would block the OTHER three
  *      cases from compiling on the current head. TODO: after Task 4 (the
  *      coder's MoleConfig change) lands, tighten this case to use
  *      `cfg.copy(stretchTimeoutCycles = 64)` for a sub-second sim.
  *   4. `stretchUnderPpMode_haltsImmediately` --- I3C-PP slave that attempts to
  *      stretch is a spec violation; engine HALTs 0xD within 200 fabric cycles,
  *      no wait-state spin.
  *
  * On the CURRENT engine head (no stretch awareness), cases 3 and 4 fail: the
  * engine sails through, HALTs status=0, and our assertions expect 0xD. Cases 1
  * and 2 pass trivially today (engine doesn't observe SCL, so it completes
  * either way). After Task 4 / Task 5 land, all four go green.
  *
  * Run: `sbt "runMain mole.BitCycleEngineStretchSim"`
  */
object BitCycleEngineStretchSim extends App {

  private val cfg = MoleConfig(
    fabricFreqHz = 24 MHz,
    quarterPeriodCyclesReset = 6,
    programWordCount = 64,
    resultRingByteCount = 64,
    captureMaxBits = 65536,
    uartBaud = 1_000_000,
    role = EngineRole.Controller
  )

  private val resultBase: Int = cfg.programWordCount
  private val resultWordCount: Int = (cfg.resultRingByteCount + 1) / 2
  private val resultLimit: Int = resultBase + resultWordCount - 1

  private lazy val compiled =
    SimConfig.withWave.compile(BitCycleEngineTargetDut(cfg))

  // --------------------------------------------------------------
  // Sim plumbing
  // --------------------------------------------------------------

  private def quiet(dut: BitCycleEngineTargetDut): Unit = {
    dut.io.start #= false
    dut.io.loaderWrite.valid #= false
    dut.io.loaderWrite.payload.addr #= 0
    dut.io.loaderWrite.payload.data #= 0
    dut.io.debugReadCmd.valid #= false
    dut.io.debugReadCmd.payload #= 0
    dut.io.bus.sda.read #= true
    dut.io.bus.scl.read #= true
  }

  private def loaderWrite(
      dut: BitCycleEngineTargetDut,
      addr: Int,
      word: Int
  ): Unit = {
    dut.io.loaderWrite.valid #= true
    dut.io.loaderWrite.payload.addr #= addr
    dut.io.loaderWrite.payload.data #= word
    dut.clockDomain.waitSamplingWhere(dut.io.loaderWrite.ready.toBoolean)
    dut.io.loaderWrite.valid #= false
  }

  private def load(dut: BitCycleEngineTargetDut, program: Seq[Int]): Unit = {
    for ((word, idx) <- program.zipWithIndex) loaderWrite(dut, idx, word)
    dut.clockDomain.waitSampling(2)
  }

  private def debugRead(dut: BitCycleEngineTargetDut, addr: Int): Int = {
    dut.io.debugReadCmd.valid #= true
    dut.io.debugReadCmd.payload #= addr
    dut.clockDomain.waitSamplingWhere(dut.io.debugReadCmd.ready.toBoolean)
    dut.io.debugReadCmd.valid #= false
    dut.clockDomain.waitSamplingWhere(dut.io.debugReadResp.valid.toBoolean)
    dut.io.debugReadResp.payload.toInt
  }

  private case class HaltWord(
      overflow: Boolean,
      mismatch: Boolean,
      status: Int
  )

  private def readHalt(dut: BitCycleEngineTargetDut): HaltWord = {
    val w = debugRead(dut, resultLimit)
    require(
      (w >>> 14) == 0x3,
      f"word at $resultLimit%d (0x$w%04x) is not a HALT tag"
    )
    HaltWord(
      overflow = ((w >> 13) & 1) != 0,
      mismatch = ((w >> 12) & 1) != 0,
      status = (w >> 8) & 0xf
    )
  }

  import Instruction._

  private def emitBit(s: TxSymbol.E): Int = encode(
    EmitBit(s, expect = false, mask = false, capture = false)
  )
  private def setMode(m: BusMode.E): Int = encode(SetBusMode(m))
  private def loadTiming(reg: Int, divider: Int): Int =
    encode(LoadTiming(reg, divider))
  private def halt(status: Int): Int = encode(Halt(status))

  // --------------------------------------------------------------
  // Stim helper: hold SCL low for N fabric cycles starting `delay`
  // cycles after start, then release. Forked in a thread so the
  // main body can observe `done`.
  // --------------------------------------------------------------

  private def forkSclStretcher(
      dut: BitCycleEngineTargetDut,
      delayCycles: Int,
      holdCycles: Int
  ): Unit = {
    fork {
      dut.clockDomain.waitSampling(delayCycles)
      dut.io.bus.scl.read #= false
      dut.clockDomain.waitSampling(holdCycles)
      dut.io.bus.scl.read #= true
    }
  }

  // Forever-low: just drop scl.read; the main loop will halt on
  // either done or safety limit.
  private def forkSclWedge(
      dut: BitCycleEngineTargetDut,
      delayCycles: Int
  ): Unit = {
    fork {
      dut.clockDomain.waitSampling(delayCycles)
      dut.io.bus.scl.read #= false
    }
  }

  private def waitDoneOrLimit(
      dut: BitCycleEngineTargetDut,
      safetyLimit: Int
  ): Int = {
    var c = 0
    while (!dut.io.done.toBoolean && c < safetyLimit) {
      dut.clockDomain.waitSampling()
      c += 1
    }
    c
  }

  // --------------------------------------------------------------
  // Case 1: stretchQ1ToQ2_releasesBeforeTimeout
  // --------------------------------------------------------------

  private def runStretchQ1ToQ2ReleasesBeforeTimeout(): Unit = {
    val label = "stretchQ1ToQ2_releasesBeforeTimeout"
    println(s"--- BitCycleEngineStretchSim: $label ---")
    compiled.doSim(label) { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.clockDomain.waitSampling(5)

      val program = Seq(
        setMode(BusMode.i2c),
        emitBit(TxSymbol.dominant),
        halt(0x0)
      )
      load(dut, program)

      dut.io.start #= true
      dut.clockDomain.waitSamplingWhere(!dut.io.done.toBoolean)
      dut.io.start #= false

      // Engine spends ~2-3 cycles fetching SET_BUS_MODE, then enters
      // EMIT_BIT. Q1->Q2 boundary lands roughly 12 cycles after
      // start (Q0 + Q1 each = 6 fabric cycles at the reset divider).
      // Stretch SCL from ~10 cycles in for 30 cycles, then release.
      forkSclStretcher(dut, delayCycles = 10, holdCycles = 30)

      val cycles = waitDoneOrLimit(dut, safetyLimit = 5000)
      assert(
        dut.io.done.toBoolean,
        s"$label: engine never halted (ran $cycles cycles)"
      )
      val h = readHalt(dut)
      assert(
        h.status == 0x0,
        f"$label: expected HALT status=0x0 (clean), got 0x${h.status}%x " +
          s"(mismatch=${h.mismatch}, overflow=${h.overflow})"
      )
      assert(
        !h.mismatch,
        s"$label: unexpected MISMATCH_FLAG after clean stretch+release"
      )
      println(s"$label OK")
    }
  }

  // --------------------------------------------------------------
  // Case 2: stretchQ3ToQ0_crossBitBoundary
  // --------------------------------------------------------------

  private def runStretchQ3ToQ0CrossBitBoundary(): Unit = {
    val label = "stretchQ3ToQ0_crossBitBoundary"
    println(s"--- BitCycleEngineStretchSim: $label ---")
    compiled.doSim(label) { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.clockDomain.waitSampling(5)

      val program = Seq(
        setMode(BusMode.i2c),
        emitBit(TxSymbol.dominant),
        emitBit(TxSymbol.recessive),
        halt(0x0)
      )
      load(dut, program)

      dut.io.start #= true
      dut.clockDomain.waitSamplingWhere(!dut.io.done.toBoolean)
      dut.io.start #= false

      // First bit ends around cycle ~30 (4 quarters * 6 cycles +
      // fetch). Hold SCL low spanning the first bit's Q3 release
      // and into the second bit's Q1->Q2; then release before the
      // (future) timeout fires.
      forkSclStretcher(dut, delayCycles = 25, holdCycles = 40)

      val cycles = waitDoneOrLimit(dut, safetyLimit = 8000)
      assert(
        dut.io.done.toBoolean,
        s"$label: engine never halted (ran $cycles cycles)"
      )
      val h = readHalt(dut)
      assert(
        h.status == 0x0,
        f"$label: expected HALT status=0x0 (clean), got 0x${h.status}%x " +
          s"(mismatch=${h.mismatch})"
      )
      assert(
        !h.mismatch,
        s"$label: unexpected MISMATCH_FLAG after cross-bit stretch+release"
      )
      println(s"$label OK")
    }
  }

  // --------------------------------------------------------------
  // Case 3: stretchNeverReleased_timesOutAndHalts
  //
  // NOTE: With the default `stretchTimeoutCycles = 2^20`, the
  // green-state sim spins ~1M fabric cycles. Verilator runs that
  // in a handful of seconds. The TDD-red state (today) bypasses
  // the spin entirely --- the engine doesn't observe SCL, so it
  // HALTs status=0 in ~50 cycles and our 0xD assertion fires.
  // --------------------------------------------------------------

  private def runStretchNeverReleasedTimesOutAndHalts(): Unit = {
    val label = "stretchNeverReleased_timesOutAndHalts"
    println(s"--- BitCycleEngineStretchSim: $label ---")
    compiled.doSim(label) { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.clockDomain.waitSampling(5)

      val program = Seq(
        setMode(BusMode.i2c),
        emitBit(TxSymbol.dominant),
        halt(0x0)
      )
      load(dut, program)

      dut.io.start #= true
      dut.clockDomain.waitSamplingWhere(!dut.io.done.toBoolean)
      dut.io.start #= false

      // Wedge SCL low from before the engine enters EMIT_BIT.
      forkSclWedge(dut, delayCycles = 5)

      // Safety: the post-fix engine times out at 2^20 fabric cycles
      // (~1.05M). Allow 2M to be safe. On the current head the
      // engine ignores SCL, so it HALTs within ~80 cycles --- the
      // assertion below fires fast on red.
      val safetyLimit = 1 << 21
      val cycles = waitDoneOrLimit(dut, safetyLimit)
      assert(
        dut.io.done.toBoolean,
        s"$label: engine never halted (ran $cycles cycles)"
      )
      val h = readHalt(dut)
      assert(
        h.status == 0xd,
        f"$label: expected HALT status=0xD (stretch-timeout), got " +
          f"0x${h.status}%x (mismatch=${h.mismatch}, overflow=" +
          s"${h.overflow})"
      )
      assert(
        h.mismatch,
        s"$label: expected MISMATCH_FLAG set on stretch timeout"
      )
      println(s"$label OK")
    }
  }

  // --------------------------------------------------------------
  // Case 4: stretchUnderPpMode_haltsImmediately
  // --------------------------------------------------------------

  private def runStretchUnderPpModeHaltsImmediately(): Unit = {
    val label = "stretchUnderPpMode_haltsImmediately"
    println(s"--- BitCycleEngineStretchSim: $label ---")
    compiled.doSim(label) { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.clockDomain.waitSampling(5)

      // BUS_MODE i3c-PP wire encoding is 0b110 = 6; mode[1:0]=10 -> 2.
      // Prime LOAD_TIMING reg 2 with the reset divider so the timer
      // is well-defined when PP becomes active.
      val program = Seq(
        loadTiming(2, cfg.quarterPeriodCyclesReset),
        setMode(BusMode.i3cPp),
        emitBit(TxSymbol.dominant),
        halt(0x0)
      )
      load(dut, program)

      dut.io.start #= true
      dut.clockDomain.waitSamplingWhere(!dut.io.done.toBoolean)
      dut.io.start #= false

      // Force SCL low from the start; PP-class stretching is a
      // compliance violation that the engine should surface
      // immediately (no wait-state spin).
      forkSclWedge(dut, delayCycles = 5)

      // 200 fabric cycles is generous: 3-instruction fetch + 1.5
      // bit times of quarter ticks. Post-fix the engine HALTs at
      // the Q1->Q2 boundary (~cycle 50-80). Pre-fix it sails
      // through and HALTs status=0 (also < 200 cycles).
      val cycles = waitDoneOrLimit(dut, safetyLimit = 400)
      assert(
        dut.io.done.toBoolean,
        s"$label: engine never halted within 400 cycles"
      )
      assert(
        cycles <= 200,
        s"$label: engine took $cycles cycles to HALT; expected <= 200 " +
          "(no wait-state spin under PP-class stretch)"
      )
      val h = readHalt(dut)
      assert(
        h.status == 0xd,
        f"$label: expected HALT status=0xD (PP-stretch violation), " +
          f"got 0x${h.status}%x (mismatch=${h.mismatch})"
      )
      assert(
        h.mismatch,
        s"$label: expected MISMATCH_FLAG set on PP-class stretch fault"
      )
      println(s"$label OK")
    }
  }

  runStretchQ1ToQ2ReleasesBeforeTimeout()
  runStretchQ3ToQ0CrossBitBoundary()
  runStretchNeverReleasedTimesOutAndHalts()
  runStretchUnderPpModeHaltsImmediately()
  println("BitCycleEngineStretchSim OK")
}
