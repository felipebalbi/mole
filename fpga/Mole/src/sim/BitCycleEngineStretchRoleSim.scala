package mole

import spinal.core._
import spinal.core.sim._
import spinal.lib._

/** Role-gating coverage for the stretch-aware `EMIT_BIT_*` guard.
  *
  * The auto-sync at the Q1→Q2 boundary is supposed to fire ONLY in controller
  * role; target-role `EMIT_BIT_IMM` must bypass it (the engine does not
  * generate SCL there --- there's nothing to wait for).
  *
  * ==Cases==
  *
  *   1. `targetRole_emitBitNoStretchGuard` --- boot with `cfg.role = Target`;
  *      EMIT_BIT_IMM with external SCL held low must complete quickly (no spin
  *      in the stretch wait state).
  *   2. `midProgramSetRole_switchesPathCleanly` --- Controller EMIT_BIT_IMM
  *      (with stretch + release), then SET_ROLE target, then SAMPLE_BIT_ON_SCL
  *      paced by an external SCL rising edge; both arms work in one bitstream.
  *
  * Run: `sbt "runMain mole.BitCycleEngineStretchRoleSim"`
  */
object BitCycleEngineStretchRoleSim extends App {

  // v0.2 default 48 MHz fabric, 24 MHz uart.
  private val cfgController = MoleConfig(
    fabricFreqHz = 48 MHz,
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
    (cfgController.resultRingByteCount + 3) / 4
  private val resultLimit: Int = resultBase + resultWordCount - 1

  private lazy val compiledTarget =
    SimConfig.withWave.compile(BitCycleEngineTargetDut(cfgTarget))

  private lazy val compiledController =
    SimConfig.withWave.compile(BitCycleEngineTargetDut(cfgController))

  // --------------------------------------------------------------
  // Shared sim plumbing
  // --------------------------------------------------------------

  private def quiet(dut: BitCycleEngineTargetDut): Unit = {
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

  /** Decode a v0.2 32-bit HALT ring word per spec §11: [31:30] = 0b11 (tag)
    * [29] = overflow [28] = mismatch [27:23] = status (5 bits) [22:0] =
    * reserved
    */
  private def readHalt(dut: BitCycleEngineTargetDut): HaltWord = {
    // C.7 NOTE: the v0.2 engine writes HALT at the current ringWrPtr
    // position (not at resultLimit as v0 did). For a program that emits
    // no CAPTURE/MARK records, ringWrPtr is 0 at HALT, so the HALT word
    // lands at resultBase. Scan the ring to find the HALT-tagged word.
    var foundAt = -1
    var halt: Int = 0
    var i = 0
    while (i < resultWordCount && foundAt < 0) {
      val w = debugRead(dut, resultBase + i)
      if (((w >>> 30) & 0x3) == 0x3) {
        foundAt = resultBase + i
        halt = w
      }
      i += 1
    }
    require(
      foundAt >= 0,
      f"no HALT tag found in ring [$resultBase, $resultLimit]"
    )
    HaltWord(
      overflow = ((halt >> 29) & 1) != 0,
      mismatch = ((halt >> 28) & 1) != 0,
      status = (halt >> 23) & 0x1f
    )
  }

  import Instruction._

  private def emitBit(s: TxSymbol.E): Int =
    encode(EmitBitImm(s, expect = false, mask = false, capture = false))
  private def sampleBit(): Int =
    encode(SampleBitOnScl(expect = false, mask = false, capture = false))
  private def setMode(m: BusMode.E): Int = encode(SetBusMode(m))
  private def setRoleOp(target: Boolean): Int = encode(SetRole(target))
  private def halt(status: Int): Int = encode(Halt(status))

  private def waitHaltedOrLimit(
      dut: BitCycleEngineTargetDut,
      safetyLimit: Int
  ): Int = {
    var c = 0
    while (!dut.io.halted.toBoolean && c < safetyLimit) {
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

      // Hold SCL low throughout. In target role the engine does
      // NOT generate SCL and must NOT consult the stretch guard.
      dut.io.scl.read #= false

      dut.io.programLength #= program.length
      dut.io.engineStart #= true

      val cycles = waitHaltedOrLimit(dut, safetyLimit = 300)
      dut.io.engineStart #= false
      assert(
        dut.io.halted.toBoolean,
        s"$label: engine never halted within $cycles cycles --- the " +
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
      println(s"$label OK ($cycles cycles)")
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

      dut.io.programLength #= program.length
      dut.io.engineStart #= true

      // Phase 1: stretch SCL low for ~20 cycles during the
      // Controller EMIT_BIT_IMM, then release.
      fork {
        dut.clockDomain.waitSampling(10)
        dut.io.scl.read #= false
        dut.clockDomain.waitSampling(20)
        dut.io.scl.read #= true

        // Phase 2: SAMPLE_BIT_ON_SCL paces off external SCL.
        // Give the engine a moment to fetch SET_ROLE +
        // SAMPLE_BIT_ON_SCL, then drive a single SCL rising edge.
        dut.clockDomain.waitSampling(60)
        dut.io.scl.read #= false
        dut.clockDomain.waitSampling(20)
        dut.io.scl.read #= true
      }

      val cycles = waitHaltedOrLimit(dut, safetyLimit = 5000)
      dut.io.engineStart #= false
      assert(
        dut.io.halted.toBoolean,
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
      println(s"$label OK ($cycles cycles)")
    }
  }

  runTargetRoleEmitBitNoStretchGuard()
  runMidProgramSetRoleSwitchesPathCleanly()
  println("BitCycleEngineStretchRoleSim OK")
}
