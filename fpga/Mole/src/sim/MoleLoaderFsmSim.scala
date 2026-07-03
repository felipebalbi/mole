// SPDX-FileCopyrightText: 2026 Felipe Balbi
// SPDX-License-Identifier: CERN-OHL-W-2.0

package mole

import spinal.core._
import spinal.core.sim._

import scala.collection.mutable

/** Audit for [[MoleLoaderFsm]].
  *
  * Covers the cases the plan calls out and a few extra edge cases the
  * rubber-duck pass flagged:
  *
  *   1. **happy path** --- 2-word program loads cleanly. SPRAM writes at addr 0
  *      and 1; `loaded` pulses once on the final byte.
  *   2. **single-word program** --- minimum-size frame (`len = 1`) still lands
  *      one SPRAM write and pulses `loaded`.
  *   3. **`len = 0`** --- rejected at `lenHi`; `fault` pulses, no SPRAM writes,
  *      parser enters `resync`.
  *   4. **`len > programWordCount`** --- rejected at `lenHi`; same observable
  *      behaviour as case 3.
  *   5. **CRC mismatch** --- corrupted payload, `fault` pulses on the final
  *      byte (the bad CRC trailer), parser enters `resync`.
  *   6. **UART error on first byte** --- exercises the `idle` error branch
  *      added during the rubber-duck pass: the corrupt byte never enters the
  *      frame; `fault` pulses, parser is in `resync`.
  *   7. **UART error mid-frame** --- a framing error pulses alongside a payload
  *      byte; the FSM must abort without latching or CRC-feeding that byte, and
  *      must NOT later accept the rest of the frame.
  *   8. **idle-gap recovery** --- after a fault, hold the RX line high for the
  *      configured idle gap and verify the parser returns to `idle` and accepts
  *      a follow-up good frame.
  *   9. **acceptRx drop mid-frame** --- the external gate drops while a frame
  *      is in flight; the FSM must fault and head to resync rather than
  *      resuming a stale half-frame later.
  *
  * The sim uses small parameters (programWordCount = 16, idleGapCycles = 8) so
  * each case finishes quickly. The DUT is the same regardless of parameters ---
  * nothing in `MoleLoaderFsm` short-circuits on small inputs.
  */
object MoleLoaderFsmSim extends App {

  // ----------------------------------------------------------------
  // Scala-side reference CRC. Mirrors the wire-format spec
  // (CRC-16/XMODEM: poly 0x1021, init 0x0000, no reflection, no
  // XOR-out). Tested implicitly by every happy-path frame; an explicit
  // check-string vector lives in Crc16XmodemSim.
  // ----------------------------------------------------------------
  def crc16Xmodem(bytes: Seq[Int]): Int = {
    var crc = 0
    for (b <- bytes) {
      crc = crc ^ ((b & 0xff) << 8)
      for (_ <- 0 until 8) {
        if ((crc & 0x8000) != 0) crc = ((crc << 1) ^ 0x1021) & 0xffff
        else crc = (crc << 1) & 0xffff
      }
    }
    crc & 0xffff
  }

  // v0.2 MAGIC mirror — keep in sync with `mole_abi::MAGIC`.
  val MAGIC: Long = 0x0002_4d4cL

  // Assemble a complete frame for the **v0.2 loader**:
  //   magic_b0..b3       ← 4 bytes LE = 0x0002_4D4C
  //   len_b0..b3         ← 4 bytes LE = N body words
  //   body word 0..N-1   ← each word = 4 bytes LE
  //   crc_lo, crc_hi     ← CRC-16/XMODEM over MAGIC + LEN + body bytes
  def buildFrame(body: Seq[Int]): Seq[Int] = {
    val n = body.size
    val magicBytes: Seq[Int] = Seq(
      (MAGIC & 0xff).toInt,
      ((MAGIC >> 8) & 0xff).toInt,
      ((MAGIC >> 16) & 0xff).toInt,
      ((MAGIC >> 24) & 0xff).toInt
    )
    val lenBytes: Seq[Int] = Seq(
      n & 0xff,
      (n >> 8) & 0xff,
      (n >> 16) & 0xff,
      (n >> 24) & 0xff
    )
    val wordBytes: Seq[Int] = body.flatMap { w =>
      Seq(
        w & 0xff,
        (w >> 8) & 0xff,
        (w >> 16) & 0xff,
        (w >> 24) & 0xff
      )
    }
    val payload: Seq[Int] = magicBytes ++ lenBytes ++ wordBytes
    val crc = crc16Xmodem(payload)
    payload ++ Seq(crc & 0xff, (crc >> 8) & 0xff)
  }

  // Sim parameters: small enough to finish each case quickly.
  val PROGRAM_WORD_COUNT = 16
  val ADDR_WIDTH = 5 // log2Up(PROGRAM_WORD_COUNT + ringWords); set wide enough
  val IDLE_GAP_CYCLES = 8

  // ----------------------------------------------------------------
  // Per-DUT helpers. We pass the dut around explicitly to keep each
  // case's stimulus self-contained.
  // ----------------------------------------------------------------

  // Hand one byte to the loader and wait for the handshake. Errors
  // pulse alongside `valid` per UartRx; we drop both on the same edge
  // the byte fires.
  def driveByte(
      dut: MoleLoaderFsm,
      byte: Int,
      framingError: Boolean = false,
      parityError: Boolean = false,
      overrun: Boolean = false
  ): Unit = {
    dut.io.rx.payload #= byte
    dut.io.rxFramingError #= framingError
    dut.io.rxParityError #= parityError
    dut.io.rxOverrun #= overrun
    dut.io.rx.valid #= true
    // waitSamplingWhere advances at least one edge and returns on the
    // first edge where the condition is True. With acceptRx=True and
    // valid=True, ready is high in any consuming state, so this lands
    // on the cycle the byte fires.
    dut.clockDomain.waitSamplingWhere(dut.io.rx.ready.toBoolean)
    dut.io.rx.valid #= false
    dut.io.rxFramingError #= false
    dut.io.rxParityError #= false
    dut.io.rxOverrun #= false
  }

  // Initialise every DUT input to a safe idle state. UART idles high;
  // SPRAM is always ready in sim (no contention during boot); the
  // phase-FSM gate is open.
  def initDut(dut: MoleLoaderFsm): Unit = {
    dut.io.rx.valid #= false
    dut.io.rx.payload #= 0
    dut.io.rxFramingError #= false
    dut.io.rxParityError #= false
    dut.io.rxOverrun #= false
    dut.io.uRxRaw #= true
    dut.io.acceptRx #= true
    dut.io.programWrite.ready #= true
  }

  // Per-case monitors. Returned bundle exposes the accumulated state to
  // the case body so assertions can be expressed inline.
  case class Monitors(
      writes: mutable.Buffer[(BigInt, BigInt)],
      loadedCount: () => Int,
      faultCount: () => Int
  )

  def startMonitors(dut: MoleLoaderFsm): Monitors = {
    val writes = mutable.Buffer[(BigInt, BigInt)]()
    var loadedPulses = 0
    var faultPulses = 0

    fork {
      while (true) {
        dut.clockDomain.waitSampling()
        if (
          dut.io.programWrite.valid.toBoolean && dut.io.programWrite.ready.toBoolean
        ) {
          writes += ((
            dut.io.programWrite.payload.addr.toBigInt,
            dut.io.programWrite.payload.data.toBigInt
          ))
        }
        if (dut.io.loaded.toBoolean) loadedPulses += 1
        if (dut.io.fault.toBoolean) faultPulses += 1
      }
    }

    Monitors(writes, () => loadedPulses, () => faultPulses)
  }

  // After a fault, hold the line high long enough for the synchroniser
  // (2 FFs) and the idle-gap counter to expire, then verify the parser
  // is back in idle.
  def waitForIdle(dut: MoleLoaderFsm): Unit = {
    dut.io.uRxRaw #= true
    dut.clockDomain.waitSampling(IDLE_GAP_CYCLES + 4)
    assert(
      !dut.io.inResync.toBoolean,
      "expected loader to return to idle after idle gap"
    )
    assert(
      !dut.io.pendingFrame.toBoolean,
      "expected pendingFrame low in idle"
    )
  }

  val compiled = SimConfig.compile(
    MoleLoaderFsm(
      programWordCount = PROGRAM_WORD_COUNT,
      addrWidth = ADDR_WIDTH,
      idleGapCycles = IDLE_GAP_CYCLES
    )
  )

  // ----------------------------------------------------------------
  // 1. happy path: 2-word program
  // ----------------------------------------------------------------

  compiled.doSim("happy-2-word") { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    initDut(dut)
    dut.clockDomain.waitSampling(4)

    val mon = startMonitors(dut)

    val words = Seq(0x1234, 0xabcd)
    val frame = buildFrame(words)
    for (b <- frame) driveByte(dut, b)

    // Give a few cycles for state to settle back to idle.
    dut.clockDomain.waitSampling(4)

    assert(
      mon.writes.size == 2,
      s"expected 2 SPRAM writes, got ${mon.writes.size}"
    )
    assert(
      mon.writes(0) == (BigInt(0), BigInt(words(0))),
      s"word 0: ${mon.writes(0)}"
    )
    assert(
      mon.writes(1) == (BigInt(1), BigInt(words(1))),
      s"word 1: ${mon.writes(1)}"
    )
    assert(
      mon.loadedCount() == 1,
      s"expected 1 loaded pulse, got ${mon.loadedCount()}"
    )
    assert(
      mon.faultCount() == 0,
      s"expected 0 fault pulses, got ${mon.faultCount()}"
    )
    assert(!dut.io.inResync.toBoolean, "expected to end in idle, not resync")

    println("[happy-2-word] OK")
  }

  // ----------------------------------------------------------------
  // 2. single-word program (boundary: len = 1)
  // ----------------------------------------------------------------

  compiled.doSim("happy-1-word") { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    initDut(dut)
    dut.clockDomain.waitSampling(4)

    val mon = startMonitors(dut)

    val frame = buildFrame(Seq(0xdead))
    for (b <- frame) driveByte(dut, b)
    dut.clockDomain.waitSampling(4)

    assert(
      mon.writes.size == 1,
      s"expected 1 SPRAM write, got ${mon.writes.size}"
    )
    assert(
      mon.writes(0) == (BigInt(0), BigInt(0xdead)),
      s"word 0: ${mon.writes(0)}"
    )
    assert(mon.loadedCount() == 1, "expected 1 loaded pulse")
    assert(mon.faultCount() == 0, "expected 0 fault pulses")

    println("[happy-1-word] OK")
  }

  // ----------------------------------------------------------------
  // 3. len = 0 -> reject
  // ----------------------------------------------------------------

  compiled.doSim("reject-len-zero") { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    initDut(dut)
    dut.clockDomain.waitSampling(4)

    val mon = startMonitors(dut)

    // Hand-build a len=0 frame. CRC over (0, 0) only.
    val payload = Seq(0x00, 0x00)
    val crc = crc16Xmodem(payload)
    val frame = payload ++ Seq(crc & 0xff, (crc >> 8) & 0xff)
    // Only the first two bytes get through before lenHi rejects; the
    // remaining bytes are drained by resync.
    for (b <- frame) driveByte(dut, b)
    dut.clockDomain.waitSampling(4)

    assert(
      mon.writes.isEmpty,
      s"expected no SPRAM writes, got ${mon.writes.size}"
    )
    assert(mon.loadedCount() == 0, "expected 0 loaded pulses")
    assert(mon.faultCount() >= 1, "expected at least one fault pulse")
    assert(dut.io.inResync.toBoolean, "expected loader to be in resync")

    waitForIdle(dut)
    println("[reject-len-zero] OK")
  }

  // ----------------------------------------------------------------
  // 4. len > programWordCount -> reject
  // ----------------------------------------------------------------

  compiled.doSim("reject-len-too-large") { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    initDut(dut)
    dut.clockDomain.waitSampling(4)

    val mon = startMonitors(dut)

    val tooLarge = PROGRAM_WORD_COUNT + 1
    val payload = Seq(tooLarge & 0xff, (tooLarge >> 8) & 0xff)
    val crc = crc16Xmodem(payload)
    val frame = payload ++ Seq(crc & 0xff, (crc >> 8) & 0xff)
    for (b <- frame) driveByte(dut, b)
    dut.clockDomain.waitSampling(4)

    assert(
      mon.writes.isEmpty,
      s"expected no SPRAM writes, got ${mon.writes.size}"
    )
    assert(mon.loadedCount() == 0, "expected 0 loaded pulses")
    assert(mon.faultCount() >= 1, "expected at least one fault pulse")
    assert(dut.io.inResync.toBoolean, "expected loader to be in resync")

    waitForIdle(dut)
    println("[reject-len-too-large] OK")
  }

  // ----------------------------------------------------------------
  // 5. CRC mismatch
  // ----------------------------------------------------------------

  compiled.doSim("reject-crc-mismatch") { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    initDut(dut)
    dut.clockDomain.waitSampling(4)

    val mon = startMonitors(dut)

    val words = Seq(0xcafe, 0x1234)
    val good = buildFrame(words)
    // Flip the high byte of the CRC trailer so the compare fails.
    val bad = good.updated(good.size - 1, (good.last ^ 0xff) & 0xff)
    for (b <- bad) driveByte(dut, b)
    dut.clockDomain.waitSampling(4)

    // The SPRAM writes still land (the loader has no way to know the CRC
    // is bad until the trailer arrives), but loaded must NOT pulse and
    // a fault must.
    assert(mon.writes.size == 2, s"writes during bad frame: ${mon.writes.size}")
    assert(mon.loadedCount() == 0, "expected 0 loaded pulses on bad CRC")
    assert(
      mon.faultCount() == 1,
      s"expected 1 fault pulse, got ${mon.faultCount()}"
    )
    assert(dut.io.inResync.toBoolean, "expected loader to be in resync")

    waitForIdle(dut)
    println("[reject-crc-mismatch] OK")
  }

  // ----------------------------------------------------------------
  // 6. UART error on the very first byte (idle-state abort path)
  // ----------------------------------------------------------------

  compiled.doSim("uart-err-first-byte") { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    initDut(dut)
    dut.clockDomain.waitSampling(4)

    val mon = startMonitors(dut)

    // Place a byte on the stream with a framing-error pulse. Idle is
    // not consuming the byte yet (ready stays low until lenLo), but the
    // idle state's `when(rxErr)` branch must fire fault and skip
    // straight to resync.
    dut.io.rx.payload #= 0xaa
    dut.io.rxFramingError #= true
    dut.io.rx.valid #= true
    dut.clockDomain.waitSampling()
    // One cycle is enough for idle to react; drop pulse + valid.
    dut.io.rxFramingError #= false
    dut.io.rx.valid #= false
    dut.clockDomain.waitSampling(4)

    assert(mon.writes.isEmpty, "expected no SPRAM writes")
    assert(mon.loadedCount() == 0, "expected 0 loaded pulses")
    assert(mon.faultCount() >= 1, "expected at least one fault pulse")
    assert(dut.io.inResync.toBoolean, "expected loader to be in resync")

    waitForIdle(dut)
    println("[uart-err-first-byte] OK")
  }

  // ----------------------------------------------------------------
  // 7. UART error mid-frame: framing error on a body word byte
  // ----------------------------------------------------------------

  compiled.doSim("uart-err-mid-frame") { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    initDut(dut)
    dut.clockDomain.waitSampling(4)

    val mon = startMonitors(dut)

    val words = Seq(0x1111, 0x2222)
    val frame = buildFrame(words)
    // v0.2 wire layout: bytes 0-3 MAGIC, 4-7 LEN, 8-11 word 0, 12-15
    // word 1, 16-17 CRC. Inject the framing error on byte 12 (the
    // first byte of word 1, which arrives in wordB0State after one
    // full body word has been written to SPRAM).
    val errIdx = 12
    for ((b, i) <- frame.zipWithIndex.take(errIdx + 1)) {
      driveByte(dut, b, framingError = (i == errIdx))
    }
    dut.clockDomain.waitSampling(4)

    // Word 0 should have made it to SPRAM (clean writeWord cycle before
    // the error). Word 1 must NOT (abort fires before writeWord).
    assert(
      mon.writes.size == 1,
      s"expected exactly 1 SPRAM write before abort, got ${mon.writes.size}"
    )
    assert(
      mon.writes(0) == (BigInt(0), BigInt(words(0))),
      "expected word 0 to land cleanly"
    )
    assert(mon.loadedCount() == 0, "expected 0 loaded pulses")
    assert(mon.faultCount() >= 1, "expected at least one fault pulse")
    assert(dut.io.inResync.toBoolean, "expected loader to be in resync")

    waitForIdle(dut)
    println("[uart-err-mid-frame] OK")
  }

  // ----------------------------------------------------------------
  // 8. idle-gap recovery: after a fault, a follow-up clean frame loads
  // ----------------------------------------------------------------

  compiled.doSim("recover-after-fault") { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    initDut(dut)
    dut.clockDomain.waitSampling(4)

    val mon = startMonitors(dut)

    // Frame 1: bad CRC.
    val bad = {
      val good = buildFrame(Seq(0xfeed))
      good.updated(good.size - 1, (good.last ^ 0xff) & 0xff)
    }
    for (b <- bad) driveByte(dut, b)
    dut.clockDomain.waitSampling(4)
    assert(mon.faultCount() == 1, "expected fault after bad CRC")
    assert(dut.io.inResync.toBoolean, "expected resync after bad CRC")

    // The bad frame's payload write does land in SPRAM (the loader has
    // no way to know the CRC is bad until the trailer arrives). Snapshot
    // and clear so the post-recovery write count is unambiguous.
    val writesBeforeRecover = mon.writes.size
    assert(
      writesBeforeRecover == 1,
      s"expected 1 stale write from bad frame, got $writesBeforeRecover"
    )
    mon.writes.clear()

    // Wait out the idle gap.
    waitForIdle(dut)

    // Frame 2: good.
    val good = buildFrame(Seq(0xbeef, 0xcafe))
    for (b <- good) driveByte(dut, b)
    dut.clockDomain.waitSampling(4)

    assert(mon.loadedCount() == 1, "expected 1 loaded pulse after recovery")
    assert(
      mon.writes.size == 2,
      s"expected 2 fresh writes, got ${mon.writes.size}"
    )
    assert(mon.writes(0) == (BigInt(0), BigInt(0xbeef)))
    assert(mon.writes(1) == (BigInt(1), BigInt(0xcafe)))
    println("[recover-after-fault] OK")
  }

  // ----------------------------------------------------------------
  // 9. acceptRx drops mid-frame
  // ----------------------------------------------------------------

  compiled.doSim("accept-rx-drop") { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    initDut(dut)
    dut.clockDomain.waitSampling(4)

    val mon = startMonitors(dut)

    val frame = buildFrame(Seq(0xaaaa, 0x5555))
    // Drive the first two bytes (len), then drop acceptRx before the
    // third (word0_lo) arrives.
    driveByte(dut, frame(0))
    driveByte(dut, frame(1))
    assert(dut.io.pendingFrame.toBoolean, "expected pendingFrame after lenHi")
    dut.io.acceptRx #= false
    dut.clockDomain.waitSampling(4)

    assert(mon.faultCount() >= 1, "expected fault on acceptRx drop")
    assert(dut.io.inResync.toBoolean, "expected resync after acceptRx drop")

    // Re-open the gate and drain the resync.
    dut.io.acceptRx #= true
    waitForIdle(dut)

    println("[accept-rx-drop] OK")
  }

  println("MoleLoaderFsmSim: all cases passed")
}
