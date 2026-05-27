package mole

import spinal.core._
import spinal.core.sim._
import spinal.lib._

/** Sim DUT for [[BitCycleEngineSim]] --- a [[BitCycleEngineSmokeDut]] extended
  * with a debug read port that takes over the SPRAM read interface while the
  * engine is idle.
  *
  * The smoke sim only needs to watch the bus drivers, so the smoke DUT does not
  * expose a result-ring readback path. The full ISA sim needs to drain the ring
  * (CAPTURE records, MARK records, HALT status word) to validate record-stream
  * content, so this DUT adds a read mux:
  *
  *   - while `engine.done` is false: engine's `programReadCmd` /
  *     `programReadResp` drive the SPRAM read port verbatim.
  *   - while `engine.done` is true: external `debugReadCmd` / `debugReadResp`
  *     win arbitration. The test drives ring addresses between programs and
  *     decodes the response stream into records.
  *
  * Both consumers receive `readResp` regardless of who issued the read; the
  * engine in idle ignores it.
  *
  * The bus pads are again left as `master(MoleBus())` so individual tests can
  * stim them (`bus.sda.read` / `bus.scl.read`) to exercise WAIT_ON cond paths,
  * mismatch detection, and capture.
  *
  * Lives in `src/sim/` --- this component never elaborates to RTL.
  */
case class BitCycleEngineFullDut(cfg: MoleConfig) extends Component {

  val addrWidth: Int =
    log2Up(cfg.programWordCount + (cfg.resultRingByteCount + 1) / 2)

  val io = new Bundle {
    val bus = master(MoleBus())
    val loaderWrite = slave Stream SpramWriteCmd(addrWidth)
    val start = in Bool ()
    val done = out Bool ()
    val debugReadCmd = slave Stream UInt(addrWidth bits)
    val debugReadResp = master Flow Bits(16 bits)
  }

  val engine = BitCycleEngineCore(cfg)
  val spram = SpramController(cfg, useBlackBox = false)

  // -------------------------------------------------- Read mux ----
  //
  // Engine fetches go to the SPRAM read port when the engine is
  // running; the debug port takes over while idle. `done` is the
  // multiplexer select; both sides see the `readResp` flow but
  // only the active consumer cares.
  when(engine.io.done) {
    spram.io.readCmd.valid := io.debugReadCmd.valid
    spram.io.readCmd.payload := io.debugReadCmd.payload
    io.debugReadCmd.ready := spram.io.readCmd.ready
    engine.io.programReadCmd.ready := False
  } otherwise {
    spram.io.readCmd.valid := engine.io.programReadCmd.valid
    spram.io.readCmd.payload := engine.io.programReadCmd.payload
    engine.io.programReadCmd.ready := spram.io.readCmd.ready
    io.debugReadCmd.ready := False
  }

  engine.io.programReadResp.valid := spram.io.readResp.valid
  engine.io.programReadResp.payload := spram.io.readResp.payload
  io.debugReadResp.valid := spram.io.readResp.valid
  io.debugReadResp.payload := spram.io.readResp.payload

  // -------------------------------------------------- Write ports ----
  spram.io.resultWrite << engine.io.resultWrite
  spram.io.loaderWrite.valid := io.loaderWrite.valid
  spram.io.loaderWrite.payload := io.loaderWrite.payload
  io.loaderWrite.ready := spram.io.loaderWrite.ready

  // -------------------------------------------------- Bus + start ----
  io.bus <> engine.io.bus
  engine.io.start := io.start
  io.done := engine.io.done
}

/** Full per-opcode coverage for [[BitCycleEngineCore]].
  *
  * One compile of [[BitCycleEngineFullDut]], many `doSim` runs. Each test loads
  * a hand-crafted program, raises `start`, waits for halt, then either inspects
  * the captured bus trace or drains the result ring through the debug read port
  * to validate record-stream content and the HALT status word.
  *
  * Tests covered:
  *
  *   - JMP forward, JMP to addr 0 (wrap-back-to-start).
  *   - BRANCH_ON ALWAYS (taken) and BRANCH_ON SDA_LOW (sticky-flag path).
  *   - LOAD_TIMING: swap divider, measure SCL-low width changes.
  *   - MARK record format + timestamps (3-word, label, monotonic ts).
  *   - STRETCH_SCL dwell length.
  *   - WAIT_ON SDA_LOW (cond-hit) + WAIT_ON TIMEOUT (timeout-hit).
  *   - CAPTURE bit on EMIT_BIT: record format + value.
  *   - MISMATCH_FLAG tracking + BRANCH_ON MISMATCH / NOT_MISMATCH.
  *   - Result-ring overflow: HALT word `overflow` bit.
  *   - HALT status field caller-defined codes.
  *   - Reserved-opcode trap: HALT status 0xF.
  *   - Invalid SET_BUS_MODE wire trap: HALT status 0xF.
  *   - Reserved tx_symbol trap on EMIT_BIT: HALT status 0xF.
  *
  * SAMPLE_BIT_ON_SCL / DRIVE_BIT_ON_SCL are deferred to Step 19.
  *
  * Run: `sbt "runMain mole.BitCycleEngineSim"`
  */
object BitCycleEngineSim {

  // --------------------------------------------------------------
  // Config
  // --------------------------------------------------------------

  private val cfg = MoleConfig(
    fabricFreqHz = 24 MHz,
    quarterPeriodCyclesReset = 6,
    programWordCount = 64,
    resultRingByteCount = 64,
    captureMaxBits = 65536,
    uartBaud = 1_000_000
  )

  private val addrWidth: Int =
    log2Up(cfg.programWordCount + (cfg.resultRingByteCount + 1) / 2)
  private val resultBase: Int = cfg.programWordCount
  private val resultWordCount: Int = (cfg.resultRingByteCount + 1) / 2
  private val resultLimit: Int = resultBase + resultWordCount - 1

  private lazy val compiled =
    SimConfig.withWave.compile(BitCycleEngineFullDut(cfg))

  // --------------------------------------------------------------
  // Helpers
  // --------------------------------------------------------------

  private def quiet(dut: BitCycleEngineFullDut): Unit = {
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
      dut: BitCycleEngineFullDut,
      addr: Int,
      word: Int
  ): Unit = {
    dut.io.loaderWrite.valid #= true
    dut.io.loaderWrite.payload.addr #= addr
    dut.io.loaderWrite.payload.data #= word
    dut.clockDomain.waitSamplingWhere(dut.io.loaderWrite.ready.toBoolean)
    dut.io.loaderWrite.valid #= false
  }

  private def load(
      dut: BitCycleEngineFullDut,
      program: Seq[Int]
  ): Unit = {
    for ((word, idx) <- program.zipWithIndex) {
      loaderWrite(dut, idx, word)
    }
    dut.clockDomain.waitSampling(2)
  }

  /** Synchronous read at `addr` on the debug port. Returns the 16-bit data
    * word. Must only be called while `done` is high.
    */
  private def debugRead(
      dut: BitCycleEngineFullDut,
      addr: Int
  ): Int = {
    dut.io.debugReadCmd.valid #= true
    dut.io.debugReadCmd.payload #= addr
    dut.clockDomain.waitSamplingWhere(
      dut.io.debugReadCmd.ready.toBoolean
    )
    dut.io.debugReadCmd.valid #= false
    dut.clockDomain.waitSamplingWhere(
      dut.io.debugReadResp.valid.toBoolean
    )
    dut.io.debugReadResp.payload.toInt
  }

  /** Drain the ring from `resultBase` to `resultLimit` and return as `Seq`.
    */
  private def drainRing(
      dut: BitCycleEngineFullDut
  ): Seq[Int] =
    (resultBase to resultLimit).map(addr => debugRead(dut, addr))

  /** Standard run prologue: stimulate, clear, load, raise start, wait done.
    */
  private def runProgram(
      dut: BitCycleEngineFullDut,
      program: Seq[Int],
      safetyLimit: Int = 50000
  ): Unit = {
    dut.clockDomain.forkStimulus(period = 10)
    quiet(dut)
    dut.clockDomain.waitSampling(5)
    load(dut, program)
    dut.io.start #= true
    dut.clockDomain.waitSamplingWhere(!dut.io.done.toBoolean)
    dut.io.start #= false
    var c = 0
    while (!dut.io.done.toBoolean && c < safetyLimit) {
      dut.clockDomain.waitSampling()
      c += 1
    }
    assert(
      dut.io.done.toBoolean,
      s"engine never halted (ran $safetyLimit cycles)"
    )
  }

  /** Convenience wrapper around `compiled.doSim`. */
  private def runTest(name: String)(
      body: BitCycleEngineFullDut => Unit
  ): Unit = {
    println(s"--- BitCycleEngineSim: $name ---")
    compiled.doSim(name) { dut =>
      body(dut)
    }
    println(s"$name OK")
  }

  // --------------------------------------------------------------
  // Tiny program-builder DSL
  // --------------------------------------------------------------

  import Instruction._

  private def emitBit(
      sym: TxSymbol.E,
      expect: Boolean = false,
      mask: Boolean = false,
      capture: Boolean = false
  ): Int =
    encode(EmitBit(sym, expect = expect, mask = mask, capture = capture))

  private def emitQuarter(
      sda: TxSymbol.E,
      scl: TxSymbol.E,
      expect: Boolean = false,
      mask: Boolean = false,
      capture: Boolean = false
  ): Int =
    encode(
      EmitQuarter(
        sda,
        scl,
        expect = expect,
        mask = mask,
        capture = capture
      )
    )

  private def setMode(m: BusMode.E): Int = encode(SetBusMode(m))
  private def halt(status: Int): Int = encode(Halt(status))
  private def jmp(addr: Int): Int = encode(Jmp(addr))
  private def branchOn(c: CondCode.E, off: Int): Int =
    encode(BranchOn(c, off))
  private def waitOn(c: CondCode.E, t: Int): Int = encode(WaitOn(c, t))
  private def stretch(n: Int): Int = encode(StretchScl(n))
  private def loadTiming(r: Int, w: Int): Int = encode(LoadTiming(r, w))
  private def mark(label: Int): Int = encode(Mark(label))
  private def loadLoop(reg: Int, imm: Int): Int = encode(LoadLoop(reg, imm))
  private def decBranch(reg: Int, off: Int): Int = encode(DecBranch(reg, off))

  // --------------------------------------------------------------
  // HALT word decoding
  // --------------------------------------------------------------

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

  private def revisionAt(ring: Seq[Int]): (Int, Int) =
    (ring(0), ring(1))

  // --------------------------------------------------------------
  // Tests
  // --------------------------------------------------------------

  /** JMP: program jumps over a HALT-with-bad-status to a HALT-with-good-status.
    * Catches a JMP that does PC := PC + 1 instead of PC := addr.
    */
  private def testJmp(): Unit = runTest("jmp-forward") { dut =>
    val program = Seq(
      jmp(3), // addr 0: skip the next two instructions
      halt(0x1), // addr 1: should NOT run (would set status 0x1)
      halt(0x2), // addr 2: also unreachable
      halt(0x7) // addr 3: target. status 0x7 proves we landed here.
    )
    runProgram(dut, program)
    val ring = drainRing(dut)
    val h = haltAt(ring, resultLimit)
    assert(
      h.status == 0x7,
      s"JMP target wrong: expected status 0x7, saw 0x${h.status.toHexString}"
    )
    assert(!h.overflow, "unexpected overflow in JMP test")
  }

  /** BRANCH_ON ALWAYS taken --- structural test that BRANCH_ON adds the
    * sign-extended offset to PC+1. Use offset = +2 to skip a HALT-with-bad-
    * status.
    */
  private def testBranchAlwaysTaken(): Unit =
    runTest("branch-always-taken") { dut =>
      val program = Seq(
        branchOn(CondCode.always, 2), // addr 0: PC = 0 + 1 + 2 = 3
        halt(0x1), // addr 1: skipped
        halt(0x2), // addr 2: skipped
        halt(0x6) // addr 3: target
      )
      runProgram(dut, program)
      val ring = drainRing(dut)
      val h = haltAt(ring, resultLimit)
      assert(h.status == 0x6, s"saw status 0x${h.status.toHexString}")
    }

  /** BRANCH_ON SDA_LOW with stim driving SDA low --- exercises the latched
    * sample path. Drive SDA low before start; an EMIT_QUARTER(hiz, hiz)
    * registers the level; BRANCH_ON SDA_LOW must take the branch.
    */
  private def testBranchSdaLowTaken(): Unit =
    runTest("branch-sda-low-taken") { dut =>
      val program = Seq(
        setMode(BusMode.i2c),
        emitQuarter(TxSymbol.hiz, TxSymbol.hiz), // sync sample SDA
        branchOn(CondCode.sdaLow, 2), // PC = 2 + 1 + 2 = 5
        halt(0x1), // addr 3: skipped
        halt(0x2), // addr 4: skipped
        halt(0x9) // addr 5: target
      )
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      // Pre-drive SDA low so the synchronizer sees it before WAIT/sample.
      dut.io.bus.sda.read #= false
      dut.clockDomain.waitSampling(5)
      load(dut, program)
      dut.io.start #= true
      dut.clockDomain.waitSamplingWhere(!dut.io.done.toBoolean)
      dut.io.start #= false
      var c = 0
      while (!dut.io.done.toBoolean && c < 50000) {
        dut.clockDomain.waitSampling()
        c += 1
      }
      assert(dut.io.done.toBoolean, "engine never halted")
      val ring = drainRing(dut)
      val h = haltAt(ring, resultLimit)
      assert(h.status == 0x9, s"saw status 0x${h.status.toHexString}")
    }

  /** WAIT_ON SDA_LOW cond-hit: bus is high, then test drops SDA mid-wait;
    * engine must clear TIMEOUT_FLAG and advance, NOT set TIMEOUT_FLAG.
    */
  private def testWaitCondHit(): Unit = runTest("wait-cond-hit") { dut =>
    val program = Seq(
      setMode(BusMode.i2c),
      waitOn(CondCode.sdaLow, 200), // generous timeout
      halt(0xa)
    )
    dut.clockDomain.forkStimulus(period = 10)
    quiet(dut)
    dut.io.bus.sda.read #= true
    dut.clockDomain.waitSampling(5)
    load(dut, program)
    dut.io.start #= true
    dut.clockDomain.waitSamplingWhere(!dut.io.done.toBoolean)
    dut.io.start #= false
    // Wait a chunk into the WAIT_ON state, then drop SDA.
    dut.clockDomain.waitSampling(80)
    dut.io.bus.sda.read #= false
    var c = 0
    while (!dut.io.done.toBoolean && c < 50000) {
      dut.clockDomain.waitSampling()
      c += 1
    }
    assert(dut.io.done.toBoolean, "engine never halted")
    val ring = drainRing(dut)
    val h = haltAt(ring, resultLimit)
    assert(h.status == 0xa, s"saw status 0x${h.status.toHexString}")
  }

  /** WAIT_ON SDA_LOW with SDA stuck high --- timeout-hit path. Use BRANCH_ON
    * TIMEOUT to pick a "timed-out" halt status.
    */
  private def testWaitTimeoutHit(): Unit =
    runTest("wait-timeout-hit") { dut =>
      val program = Seq(
        setMode(BusMode.i2c),
        waitOn(CondCode.sdaLow, 4), // short timeout
        branchOn(CondCode.timeout, 1), // taken -> halt(0xB)
        halt(0x1), // not-taken: bad
        halt(0xb) // expected
      )
      runProgram(dut, program)
      val ring = drainRing(dut)
      val h = haltAt(ring, resultLimit)
      assert(h.status == 0xb, s"saw status 0x${h.status.toHexString}")
    }

  /** MARK records: emit two MARKs with different labels and verify the ring
    * holds two 3-word records in order. Labels survive verbatim; timestamps
    * monotonically increase.
    */
  private def testMarkRecords(): Unit = runTest("mark-records") { dut =>
    val program = Seq(
      mark(0x12),
      mark(0x34),
      halt(0)
    )
    runProgram(dut, program)
    val ring = drainRing(dut)
    // Records start at index 2 of the ring (after Revision lo/hi).
    val w0 = ring(2)
    val w1 = ring(3)
    val w2 = ring(4)
    val w3 = ring(5)
    val w4 = ring(6)
    val w5 = ring(7)
    assert((w0 >>> 14) == 0x2, s"first record not MARK: w0=${w0.toHexString}")
    assert((w0 & 0xff) == 0x12, s"first MARK label wrong: ${w0 & 0xff}")
    val ts0 = ((w2 & 0xffff) << 16) | (w1 & 0xffff)
    assert((w3 >>> 14) == 0x2, s"second record not MARK: w3=${w3.toHexString}")
    assert((w3 & 0xff) == 0x34, s"second MARK label wrong: ${w3 & 0xff}")
    val ts1 = ((w5 & 0xffff) << 16) | (w4 & 0xffff)
    assert(ts1 > ts0, s"timestamps not monotonic: ts0=$ts0 ts1=$ts1")
    val h = haltAt(ring, resultLimit)
    assert(h.status == 0, "MARK test halt status non-zero")
    assert(!h.overflow, "MARK test unexpectedly overflowed")
  }

  /** CAPTURE record: emit one EMIT_BIT with capture=1 against known SDA; verify
    * a CAPTURE record lands at resultBase+2 with the right value.
    */
  private def testCaptureRecord(): Unit =
    runTest("capture-record") { dut =>
      val program = Seq(
        setMode(BusMode.i2c),
        emitBit(TxSymbol.hiz, capture = true),
        halt(0)
      )
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.io.bus.sda.read #= true // pull-up wins on hiz
      dut.clockDomain.waitSampling(5)
      load(dut, program)
      dut.io.start #= true
      dut.clockDomain.waitSamplingWhere(!dut.io.done.toBoolean)
      dut.io.start #= false
      var c = 0
      while (!dut.io.done.toBoolean && c < 50000) {
        dut.clockDomain.waitSampling()
        c += 1
      }
      assert(dut.io.done.toBoolean, "engine never halted")
      val ring = drainRing(dut)
      val cap = ring(2)
      assert(
        (cap >>> 14) == 0x0,
        s"first record not CAPTURE: tag=${cap >>> 14}"
      )
      assert(
        (cap & 1) == 1,
        s"capture value wrong: SDA was high, captured ${cap & 1}"
      )
      val h = haltAt(ring, resultLimit)
      assert(h.status == 0, "capture test halt status non-zero")
    }

  /** MISMATCH_FLAG tracking: emit one EMIT_BIT with expect=true, mask=true,
    * while SDA is driven low (mismatch). Verify HALT word has mismatch=true.
    */
  private def testMismatchTracking(): Unit =
    runTest("mismatch-tracking") { dut =>
      val program = Seq(
        setMode(BusMode.i2c),
        emitBit(TxSymbol.hiz, expect = true, mask = true),
        halt(0)
      )
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.io.bus.sda.read #= false // driven low; expect=true means MISMATCH
      dut.clockDomain.waitSampling(5)
      load(dut, program)
      dut.io.start #= true
      dut.clockDomain.waitSamplingWhere(!dut.io.done.toBoolean)
      dut.io.start #= false
      var c = 0
      while (!dut.io.done.toBoolean && c < 50000) {
        dut.clockDomain.waitSampling()
        c += 1
      }
      assert(dut.io.done.toBoolean, "engine never halted")
      val ring = drainRing(dut)
      val h = haltAt(ring, resultLimit)
      assert(h.mismatch, "HALT mismatchAtHalt expected True, was False")
    }

  /** BRANCH_ON MISMATCH following an EMIT_BIT that mismatched. Picks the "saw a
    * mismatch" halt status.
    */
  private def testBranchOnMismatch(): Unit =
    runTest("branch-on-mismatch") { dut =>
      val program = Seq(
        setMode(BusMode.i2c),
        emitBit(TxSymbol.hiz, expect = true, mask = true), // mismatch
        branchOn(CondCode.mismatch, 1), // taken
        halt(0x1),
        halt(0xc)
      )
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.io.bus.sda.read #= false
      dut.clockDomain.waitSampling(5)
      load(dut, program)
      dut.io.start #= true
      dut.clockDomain.waitSamplingWhere(!dut.io.done.toBoolean)
      dut.io.start #= false
      var c = 0
      while (!dut.io.done.toBoolean && c < 50000) {
        dut.clockDomain.waitSampling()
        c += 1
      }
      assert(dut.io.done.toBoolean, "engine never halted")
      val ring = drainRing(dut)
      val h = haltAt(ring, resultLimit)
      assert(h.status == 0xc, s"saw status 0x${h.status.toHexString}")
      assert(h.mismatch, "HALT mismatchAtHalt expected True")
    }

  /** LOAD_TIMING swap: load a fast divider into the i2c slot, then emit a bit
    * and measure SCL-low width. Without LOAD_TIMING the width would be ~24
    * cycles (`2 * (12+1)` = 26 with reload-init slack); with a divider of 2 it
    * drops to `2 * (2+1)` = 6 cycles. The factor-of-4 difference is well
    * outside any slack window.
    */
  private def testLoadTiming(): Unit = runTest("load-timing") { dut =>
    val program = Seq(
      setMode(BusMode.i2c),
      loadTiming(0, 2), // i2c slot, divider word = 2 -> 6-cycle Q0+Q1
      emitBit(TxSymbol.dominant),
      halt(0)
    )
    dut.clockDomain.forkStimulus(period = 10)
    quiet(dut)
    dut.clockDomain.waitSampling(5)
    load(dut, program)
    dut.io.start #= true
    dut.clockDomain.waitSamplingWhere(!dut.io.done.toBoolean)
    dut.io.start #= false
    val trace = collection.mutable.ArrayBuffer.empty[Boolean]
    var c = 0
    while (!dut.io.done.toBoolean && c < 50000) {
      trace += dut.io.bus.scl.driveLow.toBoolean
      dut.clockDomain.waitSampling()
      c += 1
    }
    assert(dut.io.done.toBoolean, "engine never halted")
    // Find the contiguous SCL-low run.
    val low = trace.toSeq.zipWithIndex.filter(_._1).map(_._2)
    assert(low.nonEmpty, "no SCL-low cycles observed")
    val width = low.last - low.head + 1
    // Q0+Q1 = 2 * (divider + 1) = 6 cycles. Allow ±2 cycles slack.
    assert(
      width >= 4 && width <= 8,
      s"LOAD_TIMING SCL-low width $width outside [4,8] (expected ~6)"
    )
  }

  /** STRETCH_SCL: hold SCL low for N quarters. Measure trace; expect width
    * roughly `N * (divider + 1)` fabric cycles.
    */
  private def testStretchScl(): Unit = runTest("stretch-scl") { dut =>
    val nQuarters = 5
    val program = Seq(
      setMode(BusMode.i2c),
      stretch(nQuarters),
      halt(0)
    )
    dut.clockDomain.forkStimulus(period = 10)
    quiet(dut)
    dut.clockDomain.waitSampling(5)
    load(dut, program)
    dut.io.start #= true
    dut.clockDomain.waitSamplingWhere(!dut.io.done.toBoolean)
    dut.io.start #= false
    val trace = collection.mutable.ArrayBuffer.empty[Boolean]
    var c = 0
    while (!dut.io.done.toBoolean && c < 50000) {
      trace += dut.io.bus.scl.driveLow.toBoolean
      dut.clockDomain.waitSampling()
      c += 1
    }
    assert(dut.io.done.toBoolean, "engine never halted")
    val low = trace.toSeq.zipWithIndex.filter(_._1).map(_._2)
    assert(low.nonEmpty, "no SCL-low cycles observed in STRETCH")
    val width = low.last - low.head + 1
    val expectedMin = nQuarters * cfg.quarterPeriodCyclesReset - 4
    val expectedMax = (nQuarters + 1) * cfg.quarterPeriodCyclesReset + 4
    assert(
      width >= expectedMin && width <= expectedMax,
      s"STRETCH_SCL width $width outside [$expectedMin,$expectedMax]"
    )
  }

  /** HALT caller-defined status: any 4-bit value in [11:8] must survive
    * verbatim into the HALT word.
    */
  private def testHaltStatusPassthrough(): Unit =
    runTest("halt-status-passthrough") { dut =>
      val program = Seq(halt(0x5))
      runProgram(dut, program)
      val ring = drainRing(dut)
      val h = haltAt(ring, resultLimit)
      assert(h.status == 0x5, s"saw 0x${h.status.toHexString}")
    }

  /** Reserved opcode trap: 0xE / 0xF lead to HALT status 0xF. Use 0xE
    * (FLAG_CLEAR, reserved v0.5, not implemented). Slots 0xC and 0xD are now
    * LOAD_LOOP / DEC_BRANCH.
    */
  private def testReservedOpcodeTrap(): Unit =
    runTest("reserved-opcode-trap") { dut =>
      // Slot 0xE is FLAG_CLEAR (reserved v0.5). All operand bits 0.
      val badInstr = 0xe << 12
      val program = Seq(badInstr, halt(0))
      runProgram(dut, program)
      val ring = drainRing(dut)
      val h = haltAt(ring, resultLimit)
      assert(
        h.status == 0xf,
        s"reserved opcode should trap to 0xF, got 0x${h.status.toHexString}"
      )
    }

  /** Invalid SET_BUS_MODE wire value: only {0,1,6,7} are legal; 2 must trap. */
  private def testInvalidBusModeTrap(): Unit =
    runTest("invalid-busmode-trap") { dut =>
      val setBusModeOp = Opcode.setBusMode.position
      val badInstr = (setBusModeOp << 12) | (2 << 9)
      val program = Seq(badInstr, halt(0))
      runProgram(dut, program)
      val ring = drainRing(dut)
      val h = haltAt(ring, resultLimit)
      assert(
        h.status == 0xf,
        s"invalid BUS_MODE should trap to 0xF, got 0x${h.status.toHexString}"
      )
    }

  /** Reserved tx_symbol (0b11) on EMIT_BIT must trap. The Scheme/Rust encoder
    * cannot emit this (the `TxSymbol.reserved` case is wired in but Step 7
    * documented it as a v0.5 slot); we hand-craft the wire word.
    */
  private def testReservedTxSymbolTrap(): Unit =
    runTest("reserved-tx-symbol-trap") { dut =>
      val emitBitOp = Opcode.emitBit.position
      val badInstr = (emitBitOp << 12) | (0x3 << 10) // tx_symbol = 0b11
      val program = Seq(badInstr, halt(0))
      runProgram(dut, program)
      val ring = drainRing(dut)
      val h = haltAt(ring, resultLimit)
      assert(
        h.status == 0xf,
        s"reserved tx_symbol should trap to 0xF, got 0x${h.status.toHexString}"
      )
    }

  /** Result-ring overflow: smallCfg has 32 result words. After Revision (2) and
    * reserved HALT slot (1), 29 record words are writable. Each MARK is 3
    * words, so 9 MARKs use 27 words; a 10th MARK does not fit and sets
    * `resultOverflow`.
    */
  private def testRingOverflow(): Unit = runTest("ring-overflow") { dut =>
    val program = (0 until 12).map(i => mark(i)) :+ halt(0)
    runProgram(dut, program)
    val ring = drainRing(dut)
    val h = haltAt(ring, resultLimit)
    assert(
      h.overflow,
      s"HALT word overflow expected True (12 MARKs into 29-word ring), " +
        s"was False (status=0x${h.status.toHexString})"
    )
  }

  /** Revision sanity: every test produces a HALT, and every HALT writes
    * Revision lo/hi at resultBase / resultBase+1. The values must match
    * `Revision.wordLo` / `Revision.wordHi`.
    */
  private def testRevisionWritten(): Unit =
    runTest("revision-written") { dut =>
      val program = Seq(halt(0))
      runProgram(dut, program)
      val ring = drainRing(dut)
      val (lo, hi) = revisionAt(ring)
      assert(
        lo == (Revision.wordLo & 0xffff),
        s"revision lo: expected ${Revision.wordLo.toHexString}, got ${lo.toHexString}"
      )
      assert(
        hi == (Revision.wordHi & 0xffff),
        s"revision hi: expected ${Revision.wordHi.toHexString}, got ${hi.toHexString}"
      )
    }

  /** LOAD_LOOP + DEC_BRANCH single-LCR count-down. Body is one MARK per
    * iteration; with `LOAD_LOOP lcr0, 4` the body should run exactly 4 times,
    * producing 4 MARK records then a HALT.
    */
  private def testLoopSingleCountDown(): Unit =
    runTest("loop-single-count-down") { dut =>
      val program = Seq(
        loadLoop(0, 4),
        mark(0x11),
        decBranch(0, -2), // back-edge to pc 1 while lcr0 != 0
        halt(0)
      )
      runProgram(dut, program)
      val ring = drainRing(dut)
      val markCount = (2 until ring.length).count { i =>
        val w = ring(i)
        (w >>> 14) == 0x2 && (w & 0xff) == 0x11
      }
      assert(
        markCount == 4,
        s"expected 4 MARKs from a 4-iter loop, saw $markCount"
      )
      val h = haltAt(ring, resultLimit)
      assert(h.status == 0, "single-LCR loop halt status non-zero")
      assert(!h.overflow, "single-LCR loop unexpectedly overflowed")
    }

  /** Nested LOAD_LOOP / DEC_BRANCH (outer lcr1=3, inner lcr0=2). Body MARK
    * lives in the inner loop, so the total trip count is 3 * 2 = 6.
    */
  private def testLoopNested(): Unit =
    runTest("loop-nested") { dut =>
      val program = Seq(
        loadLoop(1, 3), // outer = 3
        loadLoop(0, 2), // inner = 2 (re-primed each outer pass)
        mark(0x22), // body
        decBranch(0, -2), // inner back-edge
        decBranch(1, -4), // outer back-edge
        halt(0)
      )
      runProgram(dut, program)
      val ring = drainRing(dut)
      val markCount = (2 until ring.length).count { i =>
        val w = ring(i)
        (w >>> 14) == 0x2 && (w & 0xff) == 0x22
      }
      assert(
        markCount == 6,
        s"expected 3*2=6 MARKs from nested loop, saw $markCount"
      )
      val h = haltAt(ring, resultLimit)
      assert(h.status == 0, "nested loop halt status non-zero")
      assert(!h.overflow, "nested loop unexpectedly overflowed")
    }

  /** DEC_BRANCH boundary: LCR=1 going in must NOT branch (post-decrement = 0);
    * LCR=2 going in must branch exactly once. Both segments share one MARK
    * label per arm so we can count records per segment.
    */
  private def testLoopBoundary(): Unit =
    runTest("loop-boundary") { dut =>
      val program = Seq(
        loadLoop(0, 1), // pc 0
        decBranch(0, -1), // pc 1: lcr0 1 -> 0, fall through
        mark(0x33), // pc 2: segment A, exactly 1 MARK
        loadLoop(0, 2), // pc 3
        mark(0x44), // pc 4: segment B body
        decBranch(0, -2), // pc 5: lcr0 2 -> 1, back-edge; 1 -> 0, fall
        halt(0) // pc 6
      )
      runProgram(dut, program)
      val ring = drainRing(dut)
      val countA = (2 until ring.length).count { i =>
        val w = ring(i)
        (w >>> 14) == 0x2 && (w & 0xff) == 0x33
      }
      val countB = (2 until ring.length).count { i =>
        val w = ring(i)
        (w >>> 14) == 0x2 && (w & 0xff) == 0x44
      }
      assert(countA == 1, s"segment A (lcr0=1, no branch) saw $countA MARKs")
      assert(countB == 2, s"segment B (lcr0=2, 1 branch) saw $countB MARKs")
      val h = haltAt(ring, resultLimit)
      assert(h.status == 0, "boundary loop halt status non-zero")
      assert(!h.overflow, "boundary loop unexpectedly overflowed")
    }

  /** DEC_BRANCH is flag-neutral (AGENTS section 3.15). Setup: a mismatching
    * EMIT_BIT sets MISMATCH_FLAG. Then DEC_BRANCH runs in a small loop; the
    * trailing BRANCH_ON MISMATCH must still take, proving DEC_BRANCH did not
    * clobber the flag.
    */
  private def testLoopFlagNeutral(): Unit =
    runTest("loop-flag-neutral") { dut =>
      val program = Seq(
        setMode(BusMode.i2c), // pc 0
        emitBit(TxSymbol.hiz, expect = false, mask = true), // pc 1: mismatch
        loadLoop(0, 3), // pc 2
        decBranch(0, -1), // pc 3: loop 3 times, flag-neutral
        branchOn(CondCode.mismatch, 1), // pc 4: take if flag survived
        halt(0x1), // pc 5: would mean flag was cleared
        halt(0x6) // pc 6: branch target; flag survived
      )
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.io.bus.sda.read #= true // SDA high -> expect=0 mismatches
      dut.clockDomain.waitSampling(5)
      load(dut, program)
      dut.io.start #= true
      dut.clockDomain.waitSamplingWhere(!dut.io.done.toBoolean)
      dut.io.start #= false
      var c = 0
      while (!dut.io.done.toBoolean && c < 50000) {
        dut.clockDomain.waitSampling()
        c += 1
      }
      assert(dut.io.done.toBoolean, "engine never halted")
      val ring = drainRing(dut)
      val h = haltAt(ring, resultLimit)
      assert(
        h.status == 0x6,
        s"DEC_BRANCH clobbered MISMATCH_FLAG: status 0x${h.status.toHexString} (expected 0x6)"
      )
      assert(
        h.mismatch,
        "HALT word should record sticky mismatch from EMIT_BIT"
      )
    }

  // --------------------------------------------------------------
  // Entry point
  // --------------------------------------------------------------

  def main(args: Array[String]): Unit = {
    testRevisionWritten()
    testHaltStatusPassthrough()
    testJmp()
    testBranchAlwaysTaken()
    testBranchSdaLowTaken()
    testWaitCondHit()
    testWaitTimeoutHit()
    testMarkRecords()
    testCaptureRecord()
    testMismatchTracking()
    testBranchOnMismatch()
    testLoadTiming()
    testStretchScl()
    testReservedOpcodeTrap()
    testInvalidBusModeTrap()
    testReservedTxSymbolTrap()
    testRingOverflow()
    testLoopSingleCountDown()
    testLoopNested()
    testLoopBoundary()
    testLoopFlagNeutral()
    println("BitCycleEngineSim OK")
  }
}
