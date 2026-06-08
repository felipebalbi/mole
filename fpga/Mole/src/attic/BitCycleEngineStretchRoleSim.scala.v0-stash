package mole

import spinal.core._
import spinal.core.sim._
import spinal.lib._

/** Role-gating coverage for the stretch-aware `EMIT_BIT` guard.
  *
  * The new auto-sync at the Q1->Q2 boundary is supposed to fire ONLY in
  * controller role; target-role `EMIT_BIT` must bypass it (the engine does not
  * generate SCL there --- there's nothing to wait for).
  *
  * NOTE: This sim is a REGRESSION GUARD, not a TDD-red test. Both cases will
  * pass on the current head (pre-stretch-aware engine); they exist to catch the
  * case where the coder accidentally makes the new guard fire in target role,
  * or breaks the runtime SET_ROLE path.
  *
  * ==Cases==
  *
  *   1. `targetRole_emitBitNoStretchGuard` --- boot with `cfg.role = Target`;
  *      EMIT_BIT with SCL held low must complete quickly (no spin in the
  *      stretch wait state).
  *   2. `midProgramSetRole_switchesPathCleanly` --- Controller EMIT_BIT (with
  *      stretch + release), then SET_ROLE target, then SAMPLE_BIT_ON_SCL paced
  *      by an external SCL rising edge; both arms work in one bitstream.
  *
  * Run: `sbt "runMain mole.BitCycleEngineStretchRoleSim"`
  */
object BitCycleEngineStretchRoleSim extends App {

  // Two distinct boot-roles -> two separate compiles. Keep both
  // configs small so SPRAM elaboration is cheap.
  private val cfgController = MoleConfig(
    fabricFreqHz = 24 MHz,
    quarterPeriodCyclesReset = 6,
    programWordCount = 64,
    resultRingByteCount = 64,
    captureMaxBits = 65536,
    uartBaud = 1_000_000,
    role = EngineRole.Controller
  )

  private val cfgTarget = cfgController.copy(role = EngineRole.Target)

  private val resultBase: Int = cfgController.programWordCount
  private val resultWordCount: Int =
    (cfgController.resultRingByteCount + 1) / 2
  private val resultLimit: Int = resultBase + resultWordCount - 1

  private lazy val compiledTarget =
    SimConfig.withWave.compile(BitCycleEngineTargetDut(cfgTarget))

  private lazy val compiledController =
    SimConfig.withWave.compile(BitCycleEngineTargetDut(cfgController))

  // --------------------------------------------------------------
  // Shared sim plumbing
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

  private def emitBit(s: TxSymbol.E): Int =
    encode(EmitBit(s, expect = false, mask = false, capture = false))
  private def sampleBit(): Int =
    encode(SampleBitOnScl(expect = false, mask = false, capture = false))
  private def setMode(m: BusMode.E): Int = encode(SetBusMode(m))
  private def setRoleOp(target: Boolean): Int = encode(SetRole(target))
  private def halt(status: Int): Int = encode(Halt(status))

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
  // Case 1: targetRole_emitBitNoStretchGuard
  // --------------------------------------------------------------

  private def runTargetRoleEmitBitNoStretchGuard(): Unit = {
    val label = "targetRole_emitBitNoStretchGuard"
    println(s"--- BitCycleEngineStretchRoleSim: $label ---")
    compiledTarget.doSim(label) { dut =>
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

      // Hold SCL low throughout. In target role the engine does
      // NOT generate SCL and must NOT consult the stretch guard
      // --- otherwise it would spin until the stretch timeout
      // (~44 ms) and the safety limit would trip.
      dut.io.bus.scl.read #= false

      // Generous budget: fetch + 4 quarter ticks (~24 cycles) +
      // HALT fetch + ring write. 300 cycles is comfortable.
      val cycles = waitDoneOrLimit(dut, safetyLimit = 300)
      assert(
        dut.io.done.toBoolean,
        s"$label: engine never halted within 300 cycles --- the " +
          "stretch guard may be firing in target role"
      )
      val h = readHalt(dut)
      assert(
        h.status == 0x0,
        f"$label: expected HALT status=0x0 (clean caller halt), " +
          f"got 0x${h.status}%x (mismatch=${h.mismatch})"
      )
      assert(
        !h.mismatch,
        s"$label: unexpected MISMATCH_FLAG in target role"
      )
      println(s"$label OK")
    }
  }

  // --------------------------------------------------------------
  // Case 2: midProgramSetRole_switchesPathCleanly
  // --------------------------------------------------------------

  private def runMidProgramSetRoleSwitchesPathCleanly(): Unit = {
    val label = "midProgramSetRole_switchesPathCleanly"
    println(s"--- BitCycleEngineStretchRoleSim: $label ---")
    compiledController.doSim(label) { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.clockDomain.waitSampling(5)

      val program = Seq(
        setMode(BusMode.i2c),
        emitBit(TxSymbol.dominant),
        setRoleOp(target = true),
        sampleBit(),
        halt(0x0)
      )
      load(dut, program)

      dut.io.start #= true
      dut.clockDomain.waitSamplingWhere(!dut.io.done.toBoolean)
      dut.io.start #= false

      // Phase 1: stretch SCL low for ~20 cycles during the
      // Controller EMIT_BIT, then release.
      fork {
        dut.clockDomain.waitSampling(10)
        dut.io.bus.scl.read #= false
        dut.clockDomain.waitSampling(20)
        dut.io.bus.scl.read #= true

        // Phase 2: SAMPLE_BIT_ON_SCL paces off external SCL.
        // Give the engine a moment to fetch SET_ROLE +
        // SAMPLE_BIT_ON_SCL, then drive a single SCL rising edge.
        dut.clockDomain.waitSampling(60)
        dut.io.bus.scl.read #= false
        dut.clockDomain.waitSampling(20)
        dut.io.bus.scl.read #= true
      }

      val cycles = waitDoneOrLimit(dut, safetyLimit = 5000)
      assert(
        dut.io.done.toBoolean,
        s"$label: engine never halted (ran $cycles cycles)"
      )
      val h = readHalt(dut)
      assert(
        h.status == 0x0,
        f"$label: expected HALT status=0x0, got 0x${h.status}%x " +
          s"(mismatch=${h.mismatch})"
      )
      assert(
        !h.mismatch,
        s"$label: unexpected MISMATCH_FLAG after clean role switch"
      )
      println(s"$label OK")
    }
  }

  runTargetRoleEmitBitNoStretchGuard()
  runMidProgramSetRoleSwitchesPathCleanly()
  println("BitCycleEngineStretchRoleSim OK")
}
