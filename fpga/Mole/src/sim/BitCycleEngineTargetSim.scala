package mole

import spinal.core._
import spinal.core.sim._
import spinal.lib._

/** Target-role coverage for [[BitCycleEngineCore]]. Six tests. Five share one
  * [[BitCycleEngineTargetDut]] compile; the DAA arbitration test uses
  * [[BitCycleEngineTwoTargetDut]] with a sim-side wired-AND model.
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

  /** Controller-side bit cell: set SDA at the falling edge (like a real I2C
    * controller), pulse SCL low then high. For a controller-write bit the
    * caller passes the SDA value the controller is driving; for a target-write
    * bit the caller passes `true` (controller releases SDA so the target's
    * drive wins via wired-AND on real silicon, or via the engine's own drive on
    * the sim bus which the harness reads back from the
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

  /** Pure-read by the controller: target captures 8 SDA bits clocked by the
    * external controller's SCL. After eight SAMPLE_BIT_ON_SCL with capture=1,
    * the result ring should hold eight CAPTURE records reflecting the bits the
    * controller drove (0x96 = 1001 0110 used as the witness pattern).
    */
  private def testTargetSampleEightBits(): Unit =
    runTest("target-sample-eight-bits") { dut =>
      val program =
        (0 until 8).map(_ => sampleBit(capture = true)) ++
          Seq(mark(0xa5), halt(0))
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
      // Captures are records between Revision (ring[0..1]) and the
      // sentinel MARK word 0 (tag 0b10, label = 0xa5 in [11:4]).
      // Stop at the sentinel so uninit ring slots (also 0x0000)
      // are not miscounted as zero-valued captures.
      val sentinel = (2 << 14) | (0xa5 << 4)
      val captures = (2 until ring.length)
        .map(ring(_))
        .takeWhile(w => w != sentinel)
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

  /** Target drives 8 SDA bits via DRIVE_BIT_ON_SCL. The engine's sdaDriveLow
    * output should pulse once per `dominant` bit (four times for the witness
    * pattern 0x96 = 1001 0110, i2c BUS_MODE where dominant -> NMOS on and
    * recessive -> release).
    */
  private def testTargetDriveEightBits(): Unit =
    runTest("target-drive-eight-bits") { dut =>
      val bits = Seq(true, false, false, true, false, true, true, false)
      val program = Seq(setMode(BusMode.i2c)) ++
        bits.map { b =>
          driveBit(if (b) TxSymbol.recessive else TxSymbol.dominant)
        } :+ halt(0)
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.clockDomain.waitSampling(5)
      load(dut, program)
      dut.io.start #= true
      dut.clockDomain.waitSamplingWhere(!dut.io.done.toBoolean)
      dut.io.start #= false
      var prevDriveLow = false
      var driveLowPulses = 0
      var monitorStop = false
      val monitor = fork {
        while (!dut.io.done.toBoolean && !monitorStop) {
          val now = dut.io.bus.sda.driveLow.toBoolean
          if (now && !prevDriveLow) driveLowPulses += 1
          prevDriveLow = now
          dut.clockDomain.waitSampling()
        }
      }
      // Pace the harness against the engine's first-DRIVE
      // dispatch. After start handshake the engine is in the
      // fetch state of SET_BUS_MODE; it needs ~6 cycles to
      // traverse setMode (fetch + fetchWait + decode) plus the
      // first DRIVE_BIT_ON_SCL (fetch + fetchWait + decode)
      // before driveBitOnSclState is active and watching for a
      // falling edge. The observer adds 2-3 more cycles of
      // synchroniser latency. If the first controllerBit fires
      // immediately, its falling edge propagates to the engine
      // while decode is still running and is silently dropped,
      // putting every subsequent bit one harness-call behind
      // the test author's mental model. Waiting halfPeriodCycles
      // (matches the inter-bit gap rhythm) lets the engine reach
      // driveBitOnSclState before the first edge arrives.
      dut.clockDomain.waitSampling(halfPeriodCycles)
      // Each DRIVE_BIT_ON_SCL is a 3-state walk:
      //   driveBitOnSclState  : await falling, drive SDA
      //   driveBitWaitRising  : await rising,  sample
      //   driveBitWaitClosing : await falling, release SDA
      // 8 bits therefore need 16 fallings + 8 risings, but the
      // controller's SCL strictly alternates F-R-F-R..., so the 8
      // risings between adjacent bits show up as "free" edges
      // that no state is waiting on (harmlessly ignored). One
      // controllerBit call produces exactly one F and one R, so 8
      // bits = 16 calls. The last call's R after bit 7's closing
      // F is the post-loop idle.
      for (_ <- 0 until 16) controllerBit(dut, bitValue = true)
      dut.io.bus.sda.read #= true
      dut.io.bus.scl.read #= true
      var c = 0
      while (!dut.io.done.toBoolean && c < 50000) {
        dut.clockDomain.waitSampling(); c += 1
      }
      monitorStop = true
      monitor.join()
      assert(dut.io.done.toBoolean, "target drive: engine never halted")
      assert(
        driveLowPulses == 4,
        s"expected 4 dominant drives, saw $driveLowPulses sdaDriveLow pulses"
      )
      val ring = drainRing(dut)
      val h = haltAt(ring, resultLimit)
      assert(h.status == 0, s"target drive halt status nonzero (${h.status})")
    }

  /** Target drives recessive with expect=recessive mask=1, but the harness
    * forces SDA low across the rising-edge sample (mimics another target on the
    * wired-AND winning the bit). The MISMATCH_FLAG must fire; visible in the
    * HALT word.
    */
  private def testTargetMismatchOnDrive(): Unit =
    runTest("target-mismatch-on-drive") { dut =>
      val program = Seq(
        setMode(BusMode.i2c),
        driveBit(
          TxSymbol.recessive,
          // Target drove recessive (released SDA, expects to see
          // the wired-AND read high). Harness forces SDA low to
          // simulate another target winning the wire; sample
          // reads 0 vs expected 1 -> MISMATCH_FLAG fires.
          expect = true,
          mask = true,
          capture = false
        ),
        halt(0)
      )
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.clockDomain.waitSampling(5)
      load(dut, program)
      dut.io.start #= true
      dut.clockDomain.waitSamplingWhere(!dut.io.done.toBoolean)
      dut.io.start #= false
      // Pace against first-DRIVE dispatch (see target-drive-eight-
      // bits for the timing breakdown). Without this wait the F
      // below races the engine's setMode + DRIVE fetch-decode
      // pipeline and is dropped, leaving the engine stuck in
      // driveBitOnSclState forever.
      dut.clockDomain.waitSampling(halfPeriodCycles)
      // Drive SDA low across the controller bit cell so the wired
      // AND reads low even though the target is releasing.
      dut.io.bus.scl.read #= false
      dut.io.bus.sda.read #= false
      dut.clockDomain.waitSampling(halfPeriodCycles)
      dut.io.bus.scl.read #= true
      dut.clockDomain.waitSampling(halfPeriodCycles)
      dut.io.bus.scl.read #= false
      dut.io.bus.sda.read #= true
      dut.clockDomain.waitSampling(halfPeriodCycles)
      dut.io.bus.scl.read #= true
      var c = 0
      while (!dut.io.done.toBoolean && c < 50000) {
        dut.clockDomain.waitSampling(); c += 1
      }
      assert(dut.io.done.toBoolean, "target mismatch: engine never halted")
      val ring = drainRing(dut)
      val h = haltAt(ring, resultLimit)
      assert(
        h.mismatch,
        "DRIVE_BIT_ON_SCL did not set MISMATCH_FLAG on wired-AND loss"
      )
    }

  /** EMIT_BIT in target role must NOT drive SCL. Monitor sclDrive* through a
    * one-bit EMIT_BIT program; both must stay False the entire run.
    */
  private def testTargetNoSclDriveFromEmit(): Unit =
    runTest("target-no-scl-drive-from-emit") { dut =>
      val program = Seq(
        setMode(BusMode.i2c),
        emitBit(TxSymbol.dominant),
        halt(0)
      )
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.clockDomain.waitSampling(5)
      load(dut, program)
      var anySclDrive = false
      var monitorStopA = false
      dut.io.start #= true
      dut.clockDomain.waitSamplingWhere(!dut.io.done.toBoolean)
      dut.io.start #= false
      // Fork AFTER the start handshake so the monitor's
      // `while (!done)` doesn't exit immediately --- done is True
      // while the engine sits in idle pre-start.
      val monitor = fork {
        while (!dut.io.done.toBoolean && !monitorStopA) {
          if (
            dut.io.bus.scl.driveLow.toBoolean ||
            dut.io.bus.scl.driveHigh.toBoolean
          ) anySclDrive = true
          dut.clockDomain.waitSampling()
        }
      }
      var c = 0
      while (!dut.io.done.toBoolean && c < 50000) {
        dut.clockDomain.waitSampling(); c += 1
      }
      monitorStopA = true
      monitor.join()
      assert(dut.io.done.toBoolean, "target no-scl-drive: engine never halted")
      assert(
        !anySclDrive,
        "EMIT_BIT in target role drove SCL --- engine should release it"
      )
    }

  /** STRETCH_SCL is the one path by which the target may actively drive SCL low
    * (canonical clock-stretch). Confirm sclDriveLow goes high during a
    * STRETCH_SCL execution.
    */
  private def testTargetStretchDrivesSclLow(): Unit =
    runTest("target-stretch-drives-scl-low") { dut =>
      val program = Seq(
        setMode(BusMode.i2c),
        stretch(8),
        halt(0)
      )
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.clockDomain.waitSampling(5)
      load(dut, program)
      var sawSclLow = false
      var monitorStopB = false
      dut.io.start #= true
      dut.clockDomain.waitSamplingWhere(!dut.io.done.toBoolean)
      dut.io.start #= false
      // Fork AFTER the start handshake (see target-no-scl-drive-
      // from-emit for the rationale).
      val monitor = fork {
        while (!dut.io.done.toBoolean && !monitorStopB) {
          if (dut.io.bus.scl.driveLow.toBoolean) sawSclLow = true
          dut.clockDomain.waitSampling()
        }
      }
      var c = 0
      while (!dut.io.done.toBoolean && c < 50000) {
        dut.clockDomain.waitSampling(); c += 1
      }
      monitorStopB = true
      monitor.join()
      assert(dut.io.done.toBoolean, "target stretch: engine never halted")
      assert(
        sawSclLow,
        "STRETCH_SCL in target role did not assert sclDriveLow"
      )
    }

  /** I3C DAA arbitration: two target engines on one wired-AND bus drive
    * competing PID bits. The "lower-PID" target drives dominant, the
    * "higher-PID" drives recessive. Wired-AND reads low; the recessive-driving
    * target sees mismatch.
    */
  private def testTargetDaaArbitration(): Unit = {
    val compiled2 = SimConfig.withWave
      .addSimulatorFlag("--x-assign")
      .addSimulatorFlag("0")
      .addSimulatorFlag("--x-initial")
      .addSimulatorFlag("0")
      .compile(BitCycleEngineTwoTargetDut(cfg))

    println("--- BitCycleEngineTargetSim: target-daa-arbitration ---")
    compiled2.doSim("target-daa-arbitration") { dut =>
      val progLow = Seq(
        setMode(BusMode.i2c),
        driveBit(
          TxSymbol.dominant,
          expect = false,
          mask = true,
          capture = false
        ),
        halt(0)
      )
      val progHigh = Seq(
        setMode(BusMode.i2c),
        driveBit(
          TxSymbol.recessive,
          expect = true,
          mask = true,
          capture = false
        ),
        halt(0)
      )
      dut.clockDomain.forkStimulus(period = 10)
      dut.io.startA #= false
      dut.io.startB #= false
      dut.io.loaderWriteA.valid #= false
      dut.io.loaderWriteB.valid #= false
      dut.io.debugReadCmdA.valid #= false
      dut.io.debugReadCmdB.valid #= false
      dut.io.busA.sda.read #= true
      dut.io.busB.sda.read #= true
      dut.io.busA.scl.read #= true
      dut.io.busB.scl.read #= true
      dut.clockDomain.waitSampling(5)

      def writeLoader(
          stream: Stream[SpramWriteCmd],
          program: Seq[Int]
      ): Unit = {
        for ((word, idx) <- program.zipWithIndex) {
          stream.valid #= true
          stream.payload.addr #= idx
          stream.payload.data #= word
          dut.clockDomain.waitSamplingWhere(stream.ready.toBoolean)
          stream.valid #= false
        }
      }
      writeLoader(dut.io.loaderWriteA, progLow)
      writeLoader(dut.io.loaderWriteB, progHigh)
      dut.clockDomain.waitSampling(2)

      import OpenDrainBusSim._
      val done = scala.collection.mutable.Set[String]()
      var monitorStopC = false

      dut.io.startA #= true
      dut.io.startB #= true
      dut.clockDomain.waitSamplingWhere(!dut.io.doneA.toBoolean)
      dut.clockDomain.waitSamplingWhere(!dut.io.doneB.toBoolean)
      dut.io.startA #= false
      dut.io.startB #= false
      // Fork the wired-AND monitor AFTER the start handshake. Both
      // engines come up with done=True (FSM in idleState), so a
      // monitor forked earlier would see done.size>=2 on its first
      // iteration and exit before any cycles ran --- the wired-AND
      // would never propagate engine-A's dominant pull-down to the
      // observer of either engine and engine A would mis-sample
      // recessive on the SCL rising edge. Same pitfall as
      // target-no-scl-drive-from-emit / target-stretch-drives-scl-
      // low.
      val monitor = fork {
        while (done.size < 2 && !monitorStopC) {
          val a = Drive(
            dut.io.busA.sda.driveLow.toBoolean,
            dut.io.busA.sda.driveHigh.toBoolean
          )
          val b = Drive(
            dut.io.busB.sda.driveLow.toBoolean,
            dut.io.busB.sda.driveHigh.toBoolean
          )
          val sdaLevel = wiredAnd(Seq(a, b)).getOrElse(true)
          dut.io.busA.sda.read #= sdaLevel
          dut.io.busB.sda.read #= sdaLevel
          if (dut.io.doneA.toBoolean) done += "A"
          if (dut.io.doneB.toBoolean) done += "B"
          dut.clockDomain.waitSampling()
        }
      }
      // Pace against first-DRIVE dispatch (see target-drive-eight-
      // bits for the timing breakdown).
      dut.clockDomain.waitSampling(halfPeriodCycles)

      def sclPulse(): Unit = {
        dut.io.busA.scl.read #= false
        dut.io.busB.scl.read #= false
        dut.clockDomain.waitSampling(halfPeriodCycles)
        dut.io.busA.scl.read #= true
        dut.io.busB.scl.read #= true
        dut.clockDomain.waitSampling(halfPeriodCycles)
      }
      // DRIVE_BIT_ON_SCL needs F-R-F (open / sample / close). Two
      // sclPulse calls give F-R-F-R; the trailing R is harmlessly
      // ignored by the engine's halt path.
      sclPulse()
      sclPulse()
      dut.io.busA.scl.read #= true
      dut.io.busB.scl.read #= true

      var c = 0
      while (done.size < 2 && c < 100000) {
        dut.clockDomain.waitSampling(); c += 1
      }
      monitorStopC = true
      monitor.join()
      assert(done.size == 2, s"engines did not halt (done=$done c=$c)")

      def readHalt(
          cmd: Stream[UInt],
          resp: Flow[Bits]
      ): (Boolean, Int) = {
        cmd.valid #= true
        cmd.payload #= resultLimit
        dut.clockDomain.waitSamplingWhere(cmd.ready.toBoolean)
        cmd.valid #= false
        dut.clockDomain.waitSamplingWhere(resp.valid.toBoolean)
        val w = resp.payload.toInt
        (((w >> 12) & 1) != 0, (w >> 8) & 0xf)
      }
      val (mismatchA, _) =
        readHalt(dut.io.debugReadCmdA, dut.io.debugReadRespA)
      val (mismatchB, _) =
        readHalt(dut.io.debugReadCmdB, dut.io.debugReadRespB)
      assert(
        !mismatchA,
        "low-PID target (A) should not see mismatch (drove dominant, read dominant)"
      )
      assert(
        mismatchB,
        "high-PID target (B) should see mismatch (drove recessive, read dominant on wired-AND)"
      )
      println("  target-daa-arbitration OK")
    }
  }

  // --------------------------------------------------------------
  // Entry point (extended as tests land)
  // --------------------------------------------------------------

  def main(args: Array[String]): Unit = {
    testTargetSampleEightBits()
    testTargetDriveEightBits()
    testTargetMismatchOnDrive()
    testTargetNoSclDriveFromEmit()
    testTargetStretchDrivesSclLow()
    testTargetDaaArbitration()
    println("BitCycleEngineTargetSim OK")
  }
}
