package mole

import spinal.core._
import spinal.core.sim._
import spinal.lib._

/** Target-role coverage for [[BitCycleEngineCore]]. Five tests run
  * against a single [[BitCycleEngineTargetDut]]; the DAA arbitration
  * test (sixth) lands in a follow-up commit.
  *
  * Run: sbt "runMain mole.BitCycleEngineTargetSim"
  */
object BitCycleEngineTargetSim {

  private val cfg = MoleConfig(
    fabricFreqHz = 24 MHz,
    quarterPeriodCyclesReset = 6,
    programWordCount = 64,
    resultRingByteCount = 64,
    role = EngineRole.Target
  )

  private val addrWidth: Int =
    log2Up(cfg.programWordCount + (cfg.resultRingByteCount + 1) / 2)
  private val resultBase: Int = cfg.programWordCount
  private val resultWordCount: Int = (cfg.resultRingByteCount + 1) / 2
  private val resultLimit: Int = resultBase + resultWordCount - 1

  private lazy val compiled =
    SimConfig.withWave
      .addSimulatorFlag("--x-assign")
      .addSimulatorFlag("0")
      .addSimulatorFlag("--x-initial")
      .addSimulatorFlag("0")
      .compile(BitCycleEngineTargetDut(cfg))

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

  private def drainRing(dut: BitCycleEngineTargetDut): Seq[Int] =
    (resultBase to resultLimit).map(addr => debugRead(dut, addr))

  private case class HaltWord(
      overflow: Boolean,
      mismatch: Boolean,
      status: Int
  )

  private def haltAt(ring: Seq[Int], addr: Int): HaltWord = {
    val w = ring(addr - resultBase)
    require(
      (w >>> 14) == 0x3,
      s"word at $addr is not a HALT tag (=${w >>> 14})"
    )
    HaltWord(
      overflow = ((w >> 13) & 1) != 0,
      mismatch = ((w >> 12) & 1) != 0,
      status = (w >> 8) & 0xf
    )
  }

  private def runTest(name: String)(
      body: BitCycleEngineTargetDut => Unit
  ): Unit = {
    println(s"--- BitCycleEngineTargetSim: $name ---")
    compiled.doSim(name) { dut => body(dut) }
    println(s"  $name OK")
  }

  // External-controller stim. The harness drives bus.sda.read and
  // bus.scl.read at fabric-clock granularity. One controller-driven
  // SCL half-period is `halfPeriodCycles` fabric cycles wide ---
  // ample for the engine's 2-FF synchronizer plus its FSM step
  // (the SAMPLE / DRIVE bit-on-SCL states observe edges cleanly
  // at this rate).

  private val halfPeriodCycles = 12

  private def sclLow(dut: BitCycleEngineTargetDut): Unit = {
    dut.io.bus.scl.read #= false
    dut.clockDomain.waitSampling(halfPeriodCycles)
  }

  private def sclHigh(dut: BitCycleEngineTargetDut): Unit = {
    dut.io.bus.scl.read #= true
    dut.clockDomain.waitSampling(halfPeriodCycles)
  }

  /** Controller-side bit cell: set SDA at the falling edge (like a
    * real I2C controller), pulse SCL low then high. For a
    * controller-write bit the caller passes the SDA value the
    * controller is driving; for a target-write bit the caller
    * passes `true` (controller releases SDA so the target's drive
    * wins via wired-AND on real silicon, or via the engine's own
    * drive on the sim bus which the harness reads back from the
    * `bus.sda.driveLow/driveHigh` outputs).
    */
  private def controllerBit(
      dut: BitCycleEngineTargetDut,
      bitValue: Boolean
  ): Unit = {
    dut.io.bus.scl.read #= false
    dut.io.bus.sda.read #= bitValue
    dut.clockDomain.waitSampling(halfPeriodCycles)
    dut.io.bus.scl.read #= true
    dut.clockDomain.waitSampling(halfPeriodCycles)
  }

  // --------------------------------------------------------------
  // Program-builder helpers (subset of BitCycleEngineSim; copied
  // here to keep the two sims self-contained).
  // --------------------------------------------------------------

  import Instruction._

  private def setMode(m: BusMode.E): Int = encode(SetBusMode(m))
  private def halt(status: Int): Int = encode(Halt(status))
  private def mark(label: Int): Int = encode(Mark(label))
  private def stretch(n: Int): Int = encode(StretchScl(n))
  private def emitBit(s: TxSymbol.E): Int = encode(
    EmitBit(s, expect = false, mask = false, capture = false)
  )
  private def sampleBit(
      expect: Boolean = false,
      mask: Boolean = false,
      capture: Boolean = false
  ): Int = encode(SampleBitOnScl(expect, mask, capture))
  private def driveBit(
      s: TxSymbol.E,
      expect: Boolean = false,
      mask: Boolean = false,
      capture: Boolean = false
  ): Int = encode(DriveBitOnScl(s, expect, mask, capture))

  // --------------------------------------------------------------
  // Tests
  // --------------------------------------------------------------

  /** Pure-read by the controller: target captures 8 SDA bits clocked
    * by the external controller's SCL. After eight
    * SAMPLE_BIT_ON_SCL with capture=1, the result ring should hold
    * eight CAPTURE records reflecting the bits the controller drove
    * (0x96 = 1001 0110 used as the witness pattern).
    */
  private def testTargetSampleEightBits(): Unit =
    runTest("target-sample-eight-bits") { dut =>
      val program = (0 until 8).map(_ => sampleBit(capture = true)) :+ halt(0)
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.clockDomain.waitSampling(5)
      load(dut, program)
      dut.io.start #= true
      dut.clockDomain.waitSamplingWhere(!dut.io.done.toBoolean)
      dut.io.start #= false
      // Drive 0x96 MSB first via the controller stim.
      val bits = Seq(true, false, false, true, false, true, true, false)
      for (b <- bits) controllerBit(dut, b)
      // Release SDA + give the engine a few cycles to refetch + HALT.
      dut.io.bus.sda.read #= true
      dut.io.bus.scl.read #= true
      var c = 0
      while (!dut.io.done.toBoolean && c < 50000) {
        dut.clockDomain.waitSampling()
        c += 1
      }
      assert(dut.io.done.toBoolean, "target sample: engine never halted")
      val ring = drainRing(dut)
      val captures = (2 until ring.length)
        .map(ring(_))
        .takeWhile(w => (w >>> 14) == 0x0)
      assert(
        captures.length == 8,
        s"expected 8 CAPTURE records, saw ${captures.length}"
      )
      val captured = captures.map(w => (w & 1) != 0)
      assert(
        captured == bits,
        s"captured pattern mismatch: expected $bits, saw $captured"
      )
      val h = haltAt(ring, resultLimit)
      assert(h.status == 0, s"target sample halt status nonzero (${h.status})")
    }

  // --------------------------------------------------------------
  // Entry point (extended as tests land)
  // --------------------------------------------------------------

  def main(args: Array[String]): Unit = {
    testTargetSampleEightBits()
    println("BitCycleEngineTargetSim OK")
  }
}
