// SPDX-FileCopyrightText: 2026 Felipe Balbi
// SPDX-License-Identifier: CERN-OHL-W-2.0

package mole

import spinal.core._
import spinal.core.sim._
import spinal.lib._

import scala.collection.mutable
import scala.util.Random

/** Audit for [[MoleDrainerFsm]].
  *
  * The drainer is purely a SPRAM-read + UART-byte-push pipeline, so the sim
  * wraps the DUT with a Mem-backed mock SPRAM (one-cycle synchronous read
  * latency, matching the real `SpramController` contract) and a sim-only write
  * port for pre-loading the ring. Each case writes a known pattern into the
  * ring, pulses `triggerDrain`, and asserts the resulting byte stream matches.
  *
  * Cases:
  *
  *   1. **happy 4-word** --- pre-load four distinct words; verify the byte
  *      stream is `[w0_lo, w0_hi, w1_lo, w1_hi, ...]` in the correct order;
  *      `drainComplete` pulses on the SAME cycle the final byte fires; `active`
  *      falls back to low; SPRAM read addresses are exactly
  *      `[base, base+1, ..., limit]`.
  *   2. **single-word ring** --- minimum-size sweep (`resultWordCount = 1`)
  *      still pulses `drainComplete` and emits exactly two bytes.
  *   3. **random throttle** --- ready fires ~30 % of cycles; verify byte order
  *      survives, no bytes lost, no Stream-protocol violations. Throttle is
  *      driven from the same fork as the monitor to eliminate fork/delta-cycle
  *      race ambiguity flagged in the loader-FSM rubber-duck pattern.
  *   4. **long deterministic stalls** --- ready forced low for 50 cycles at the
  *      front of each `send` state; covers stalls the random throttle may not
  *      exercise on a given seed.
  *   5. **re-arm** --- after one full drain, re-pulse `triggerDrain` and verify
  *      a second drain starts from `resultBase`. Catches "did we forget to
  *      reset addrReg?" bugs.
  *   6. **trigger-while-busy** --- pulse `triggerDrain` mid-drain. Verify the
  *      second pulse is ignored.
  */
object MoleDrainerFsmSim extends App {

  // Test parameters: small enough for fast turnaround. resultBase is
  // non-zero to catch any off-by-one in the address counter.
  val RESULT_BASE = 4
  val RESULT_WORD_COUNT = 4
  val ADDR_WIDTH = 4 // covers 0..15, enough for base+count = 8

  /** Sim DUT wrapper: drainer plus a Mem-backed mock SPRAM with a sim-only
    * write port. Mirrors the real `SpramController` single-port read/write
    * semantics (one-cycle synchronous read latency).
    */
  case class DrainerSimDut(
      resultBase: Int,
      resultWordCount: Int,
      addrWidth: Int
  ) extends Component {
    val io = new Bundle {
      val triggerDrain = in Bool ()
      val txData = master Stream Bits(8 bits)
      val drainComplete = out Bool ()
      val active = out Bool ()
      // Sim-only pre-load port; not part of the real top-level wiring.
      val simWriteValid = in Bool ()
      val simWriteAddr = in UInt (addrWidth bits)
      val simWriteData = in Bits (32 bits)
      // Exposed for the sim address monitor (the real wiring has this
      // only on the SpramController side).
      val readCmdValid = out Bool ()
      val readCmdAddr = out UInt (addrWidth bits)
    }

    val drainer = MoleDrainerFsm(resultBase, resultWordCount, addrWidth)
    val mem = Mem(Bits(32 bits), 1 << addrWidth)

    // Sim-only pre-load. Single port: simWriteValid wins when high,
    // drainer's read path is idle during pre-load by convention.
    when(io.simWriteValid) {
      mem.write(io.simWriteAddr, io.simWriteData)
    }

    // Drainer <-> wiring.
    drainer.io.triggerDrain := io.triggerDrain
    io.txData <> drainer.io.txData
    io.drainComplete := drainer.io.drainComplete
    io.active := drainer.io.active

    // Expose read command for sim monitoring.
    io.readCmdValid := drainer.io.readCmd.valid
    io.readCmdAddr := drainer.io.readCmd.payload

    // SPRAM read: priority-1 always-ready, one-cycle synchronous read
    // matching the real controller.
    drainer.io.readCmd.ready := True
    drainer.io.readResp.payload := mem.readSync(
      address = drainer.io.readCmd.payload,
      enable = drainer.io.readCmd.fire
    )
    drainer.io.readResp.valid := RegNext(drainer.io.readCmd.fire) init False
  }

  // ----------------------------------------------------------------
  // Helpers.
  // ----------------------------------------------------------------

  def initDut(dut: DrainerSimDut): Unit = {
    dut.io.triggerDrain #= false
    dut.io.txData.ready #= true
    dut.io.simWriteValid #= false
    dut.io.simWriteAddr #= 0
    dut.io.simWriteData #= 0
  }

  // Pre-load the ring at offsets 0..resultWordCount-1 with the given
  // 32-bit word sequence. Drives the sim-only write port one word
  // per cycle. v0.2 ring grain is 32 bits.
  def preloadRing(dut: DrainerSimDut, base: Int, words: Seq[Int]): Unit = {
    for ((w, i) <- words.zipWithIndex) {
      dut.io.simWriteValid #= true
      dut.io.simWriteAddr #= base + i
      dut.io.simWriteData #= w & 0xffffffffL
      dut.clockDomain.waitSampling()
    }
    dut.io.simWriteValid #= false
    dut.clockDomain.waitSampling(2)
  }

  // Pulse triggerDrain for exactly one cycle.
  def pulseTrigger(dut: DrainerSimDut): Unit = {
    dut.io.triggerDrain #= true
    dut.clockDomain.waitSampling()
    dut.io.triggerDrain #= false
  }

  // Convert a sequence of 32-bit words into the byte stream the drainer
  // should produce: little-endian per 32-bit word (b0 first, then b1,
  // b2, b3). v0.2 drainer grain is 32 bits.
  def expectedBytes(words: Seq[Int]): Seq[Int] =
    words.flatMap(w =>
      Seq(
        w & 0xff,
        (w >> 8) & 0xff,
        (w >> 16) & 0xff,
        (w >> 24) & 0xff
      )
    )

  /** Aggregated monitor state. Cycles are simulation cycle counts (each
    * `waitSampling` ticks the counter by 1) so we can assert cycle alignment
    * between events.
    */
  case class Monitors(
      bytes: mutable.Buffer[Int],
      byteFireCycles: mutable.Buffer[Long],
      drainCompleteCycles: mutable.Buffer[Long],
      readAddrs: mutable.Buffer[Int],
      protocolViolations: () => Int
  )

  /** Start a single fork that owns BOTH the ready driver (if a throttle
    * function is provided) and all sampling. Doing both in one fork eliminates
    * the fork/delta-cycle race the loader-FSM rubber-duck pass flagged: ready
    * and the sampled `fire` always come from the same edge.
    *
    * Pass `readyDriver = None` to leave the test in control of `txData.ready`
    * (e.g. when it just stays high).
    */
  def startMonitors(
      dut: DrainerSimDut,
      readyDriver: Option[() => Boolean] = None
  ): Monitors = {
    val bytes = mutable.Buffer[Int]()
    val byteFireCycles = mutable.Buffer[Long]()
    val drainCompleteCycles = mutable.Buffer[Long]()
    val readAddrs = mutable.Buffer[Int]()
    var violations = 0
    var prevValid = false
    var prevReady = false
    var prevPayload = 0
    var cycle = 0L

    fork {
      while (true) {
        // Drive ready FIRST so the DUT sees the new value at the
        // upcoming edge.
        readyDriver match {
          case Some(fn) => dut.io.txData.ready #= fn()
          case None     => ()
        }
        dut.clockDomain.waitSampling()
        cycle += 1
        val v = dut.io.txData.valid.toBoolean
        val r = dut.io.txData.ready.toBoolean
        val p = dut.io.txData.payload.toInt
        if (v && r) {
          bytes += p
          byteFireCycles += cycle
        }
        if (dut.io.drainComplete.toBoolean) {
          drainCompleteCycles += cycle
        }
        // The DUT exposes readCmdValid + readCmdAddr; combined with
        // SPRAM's always-True ready this is `readCmd.fire`.
        if (dut.io.readCmdValid.toBoolean) {
          readAddrs += dut.io.readCmdAddr.toInt
        }
        // Stream protocol: once valid is high with ready low, valid
        // must stay high (and payload stable) until ready fires.
        if (prevValid && !prevReady && !v) violations += 1
        if (prevValid && !prevReady && v && p != prevPayload) violations += 1
        prevValid = v
        prevReady = r
        prevPayload = p
      }
    }

    Monitors(
      bytes,
      byteFireCycles,
      drainCompleteCycles,
      readAddrs,
      () => violations
    )
  }

  // Wait for a condition with a timeout; throws on timeout.
  def waitUntil(
      dut: DrainerSimDut,
      cond: () => Boolean,
      maxCycles: Int,
      what: String
  ): Unit = {
    var c = 0
    while (!cond() && c < maxCycles) {
      dut.clockDomain.waitSampling()
      c += 1
    }
    assert(cond(), s"$what did not happen within $maxCycles cycles")
  }

  val compiled = SimConfig.compile(
    DrainerSimDut(
      resultBase = RESULT_BASE,
      resultWordCount = RESULT_WORD_COUNT,
      addrWidth = ADDR_WIDTH
    )
  )

  // ----------------------------------------------------------------
  // 1. happy path: 4 distinct words. Verifies byte order, address
  //    sweep, AND cycle alignment of drainComplete with the final
  //    byte fire.
  // ----------------------------------------------------------------

  compiled.doSim("happy-4-word") { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    initDut(dut)
    dut.clockDomain.waitSampling(4)

    val words = Seq(0x1234, 0xabcd, 0xdead, 0xbeef)
    preloadRing(dut, RESULT_BASE, words)

    val mon = startMonitors(dut)
    pulseTrigger(dut)

    waitUntil(
      dut,
      () => mon.drainCompleteCycles.nonEmpty,
      maxCycles = 200,
      what = "drainComplete after happy path"
    )
    dut.clockDomain.waitSampling(4)

    val expected = expectedBytes(words)
    assert(
      mon.bytes.toSeq == expected,
      s"byte stream mismatch:\n  got      ${mon.bytes.toSeq.map(b => f"$b%02x").mkString(" ")}\n  expected ${expected.map(b => f"$b%02x").mkString(" ")}"
    )
    assert(
      mon.drainCompleteCycles.size == 1,
      s"expected 1 drainComplete pulse, got ${mon.drainCompleteCycles.size}"
    )

    // Cycle alignment: drainComplete must fire on the SAME cycle as
    // the final byte. This is what the rubber-duck pass flagged: the
    // old design pulsed two cycles late.
    val lastFireCycle = mon.byteFireCycles.last
    val doneCycle = mon.drainCompleteCycles.head
    assert(
      doneCycle == lastFireCycle,
      s"drainComplete cycle $doneCycle != final byte fire cycle $lastFireCycle"
    )

    // Address sweep: should be base, base+1, ..., limit (each
    // possibly repeated for the issueRead retry shape, but the first
    // occurrence sequence must match).
    val uniqueAddrs = mon.readAddrs.toSeq.distinct
    val expectedAddrs = (0 until RESULT_WORD_COUNT).map(RESULT_BASE + _)
    assert(
      uniqueAddrs == expectedAddrs,
      s"address sweep wrong:\n  got      $uniqueAddrs\n  expected $expectedAddrs"
    )

    assert(
      mon.protocolViolations() == 0,
      s"Stream protocol violations: ${mon.protocolViolations()}"
    )
    assert(!dut.io.active.toBoolean, "expected active low after drain")
    println("[happy-4-word] OK")
  }

  // ----------------------------------------------------------------
  // 2. single-word ring (separate compile because resultWordCount is
  //    an elaboration-time constructor param).
  // ----------------------------------------------------------------

  val compiledSingle = SimConfig.compile(
    DrainerSimDut(
      resultBase = RESULT_BASE,
      resultWordCount = 1,
      addrWidth = ADDR_WIDTH
    )
  )

  compiledSingle.doSim("single-word-ring") { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    initDut(dut)
    dut.clockDomain.waitSampling(4)

    val words = Seq(0xc0de)
    preloadRing(dut, RESULT_BASE, words)

    val mon = startMonitors(dut)
    pulseTrigger(dut)

    waitUntil(
      dut,
      () => mon.drainCompleteCycles.nonEmpty,
      maxCycles = 100,
      what = "drainComplete after single-word drain"
    )
    dut.clockDomain.waitSampling(4)

    assert(
      mon.bytes.toSeq == expectedBytes(words),
      s"single-word byte mismatch: ${mon.bytes.toSeq}"
    )
    assert(mon.drainCompleteCycles.size == 1, "expected 1 drainComplete pulse")
    assert(
      mon.drainCompleteCycles.head == mon.byteFireCycles.last,
      "single-word drainComplete cycle mismatch"
    )
    assert(mon.protocolViolations() == 0, "no Stream violations")
    println("[single-word-ring] OK")
  }

  // ----------------------------------------------------------------
  // 3. random throttle: ~30 % ready. Throttle is driven from the
  //    monitor fork to keep ready and sampling on the same edge.
  // ----------------------------------------------------------------

  compiled.doSim("backpressure-throttle") { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    initDut(dut)
    dut.clockDomain.waitSampling(4)

    val words = Seq(0x0102, 0x0304, 0x0506, 0x0708)
    preloadRing(dut, RESULT_BASE, words)

    val rng = new Random(0xc0ffee)
    val mon =
      startMonitors(dut, readyDriver = Some(() => rng.nextInt(100) < 30))
    pulseTrigger(dut)

    // 8 bytes * ~4 cycles average between fires + SPRAM 1-cycle
    // latency per word = ~50 cycles average; use 500 for slack.
    waitUntil(
      dut,
      () => mon.drainCompleteCycles.nonEmpty,
      maxCycles = 500,
      what = "drainComplete under random throttle"
    )
    dut.clockDomain.waitSampling(4)
    dut.io.txData.ready #= true

    val expected = expectedBytes(words)
    assert(
      mon.bytes.toSeq == expected,
      s"throttled byte stream mismatch:\n  got      ${mon.bytes.toSeq.map(b => f"$b%02x").mkString(" ")}\n  expected ${expected.map(b => f"$b%02x").mkString(" ")}"
    )
    assert(
      mon.drainCompleteCycles.size == 1,
      "expected 1 drainComplete under throttle"
    )
    assert(
      mon.drainCompleteCycles.head == mon.byteFireCycles.last,
      "throttled drainComplete cycle mismatch"
    )
    assert(
      mon.protocolViolations() == 0,
      s"Stream violations under throttle: ${mon.protocolViolations()}"
    )
    println("[backpressure-throttle] OK")
  }

  // ----------------------------------------------------------------
  // 4. long deterministic stall: ready forced low for 50 cycles
  //    once the drainer has stuff to send.
  // ----------------------------------------------------------------

  compiled.doSim("long-stall") { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    initDut(dut)
    dut.clockDomain.waitSampling(4)

    val words = Seq(0x0a0b, 0x0c0d, 0x0e0f, 0x1011)
    preloadRing(dut, RESULT_BASE, words)

    // Throttle controlled by a sim-side var the monitor reads each
    // cycle, so the stall-up / stall-down transitions land between
    // monitor edges (no race).
    var stallCycles = 0L
    val mon = startMonitors(
      dut,
      readyDriver = Some(() => {
        if (stallCycles > 0) {
          stallCycles -= 1
          false
        } else {
          true
        }
      })
    )

    pulseTrigger(dut)
    // Stall as soon as the first byte is on the wire (sendLo).
    waitUntil(dut, () => mon.bytes.nonEmpty, 50, "first byte before stall")
    stallCycles = 50
    dut.clockDomain.waitSampling(55)
    // Stall again mid-stream.
    stallCycles = 50
    waitUntil(
      dut,
      () => mon.drainCompleteCycles.nonEmpty,
      maxCycles = 500,
      what = "drainComplete after long stalls"
    )
    dut.clockDomain.waitSampling(4)
    dut.io.txData.ready #= true

    val expected = expectedBytes(words)
    assert(
      mon.bytes.toSeq == expected,
      s"long-stall byte stream mismatch: ${mon.bytes.toSeq}"
    )
    assert(
      mon.drainCompleteCycles.size == 1,
      "exactly one drainComplete after stalls"
    )
    assert(
      mon.drainCompleteCycles.head == mon.byteFireCycles.last,
      "long-stall drainComplete cycle mismatch"
    )
    assert(
      mon.protocolViolations() == 0,
      s"Stream violations under stall: ${mon.protocolViolations()}"
    )
    println("[long-stall] OK")
  }

  // ----------------------------------------------------------------
  // 5. re-arm: two consecutive drains.
  // ----------------------------------------------------------------

  compiled.doSim("re-arm-two-drains") { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    initDut(dut)
    dut.clockDomain.waitSampling(4)

    val words1 = Seq(0x1111, 0x2222, 0x3333, 0x4444)
    preloadRing(dut, RESULT_BASE, words1)

    val mon = startMonitors(dut)

    pulseTrigger(dut)
    waitUntil(
      dut,
      () => mon.drainCompleteCycles.size >= 1,
      200,
      "first drainComplete"
    )
    dut.clockDomain.waitSampling(4)
    val firstByteCount = mon.bytes.size

    val words2 = Seq(0xaaaa, 0xbbbb, 0xcccc, 0xdddd)
    preloadRing(dut, RESULT_BASE, words2)

    pulseTrigger(dut)
    waitUntil(
      dut,
      () => mon.drainCompleteCycles.size >= 2,
      200,
      "second drainComplete"
    )
    dut.clockDomain.waitSampling(4)

    val bytesFirst = mon.bytes.toSeq.take(firstByteCount)
    val bytesSecond = mon.bytes.toSeq.drop(firstByteCount)
    assert(
      bytesFirst == expectedBytes(words1),
      s"first drain bytes wrong: $bytesFirst"
    )
    assert(
      bytesSecond == expectedBytes(words2),
      s"second drain bytes wrong:\n  got      ${bytesSecond.map(b => f"$b%02x").mkString(" ")}\n  expected ${expectedBytes(words2).map(b => f"$b%02x").mkString(" ")}"
    )
    assert(
      mon.drainCompleteCycles.size == 2,
      s"expected 2 drainComplete pulses, got ${mon.drainCompleteCycles.size}"
    )
    assert(
      mon.protocolViolations() == 0,
      "no Stream violations across two drains"
    )
    println("[re-arm-two-drains] OK")
  }

  // ----------------------------------------------------------------
  // 6. trigger-while-busy: ignored.
  // ----------------------------------------------------------------

  compiled.doSim("trigger-while-busy") { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    initDut(dut)
    dut.clockDomain.waitSampling(4)

    val words = Seq(0x1100, 0x3322, 0x5544, 0x7766)
    preloadRing(dut, RESULT_BASE, words)

    val mon = startMonitors(dut)
    pulseTrigger(dut)

    waitUntil(
      dut,
      () => dut.io.active.toBoolean,
      20,
      "active high after trigger"
    )

    // Mid-drain re-trigger; should be ignored.
    for (_ <- 0 until 2) {
      pulseTrigger(dut)
      dut.clockDomain.waitSampling(3)
    }

    waitUntil(
      dut,
      () => mon.drainCompleteCycles.size >= 1,
      300,
      "drainComplete despite mid-drain triggers"
    )
    dut.clockDomain.waitSampling(4)

    val expected = expectedBytes(words)
    assert(
      mon.bytes.toSeq == expected,
      s"mid-trigger byte stream mismatch:\n  got      ${mon.bytes.toSeq.map(b => f"$b%02x").mkString(" ")}\n  expected ${expected.map(b => f"$b%02x").mkString(" ")}"
    )
    assert(
      mon.drainCompleteCycles.size == 1,
      s"expected exactly 1 drainComplete despite re-triggers, got ${mon.drainCompleteCycles.size}"
    )
    assert(mon.protocolViolations() == 0, "no Stream violations")
    assert(!dut.io.active.toBoolean, "expected active low after drain")
    println("[trigger-while-busy] OK")
  }

  println("MoleDrainerFsmSim: all cases passed")
}
