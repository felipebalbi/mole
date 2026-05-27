package mole

import spinal.core._
import spinal.core.sim._
import spinal.lib._

import scala.collection.mutable

/** End-to-end audit for MoleTop's USB-UART hardware flow control.
  *
  * Companion to [[MoleTopSim]]; same wrapper ([[MoleTopSimDut]]), same fabric
  * clock and baud, focused exclusively on the `io_uCts` / `io_uRts` contract.
  *
  * The four cases below cover both directions of flow control:
  *
  *   1. **CTS asserted in acceptLoad** --- after reset, before any frame is
  *      sent, `io_uCts` is the active-low `0` (asserted) and stays asserted for
  *      many cycles. Validates that the phase FSM enters `acceptLoadState`
  *      cleanly out of reset.
  *   2. **CTS deasserted during run+drain** --- after a frame loads, the phase
  *      FSM transitions through `runningState` and `drainingState` before
  *      returning to `acceptLoadState`. `io_uCts` must be `1` (deasserted)
  *      somewhere in that window and back to `0` (asserted) after the full
  *      drain. This is the spec invariant "while program is not HALTED, don't
  *      accept data": with `crtscts` enabled a host driver will stop TX'ing as
  *      soon as CTS# deasserts.
  *   3. **TX backpressure on RTS deasserted** --- holding `io_uRts` high
  *      (deasserted) before a frame is sent must prevent the drainer from ever
  *      placing a byte on `io_uTx`. The engine still runs (loader and engine
  *      are RX-side; RTS only gates TX), but no result bytes leak out. Dropping
  *      `io_uRts` then drains the full ring with a clean HALT word at the tail.
  *   4. **TX resumes after mid-drain halt** --- partial-drain N bytes, raise
  *      `io_uRts` mid-drain, wait long enough for one in-flight UART frame to
  *      complete, verify no further bytes arrive, then drop `io_uRts` and drain
  *      the remainder. Total bytes drained equals the full ring and the final
  *      HALT word is clean --- confirming `Stream.haltWhen` only gates the
  *      producer side, never drops in-flight bytes.
  *
  * Test config matches [[MoleTopSim]]: 16-word program memory, 32-byte result
  * ring, `[SetBusMode(i2c), Halt(0)]` as the canonical short program.
  */
object MoleTopFlowControlSim extends App {

  // ----------------------------------------------------------------
  // CRC + frame builder. Duplicated from MoleTopSim and
  // MoleLoaderFsmSim per the existing sim-file convention (each sim
  // is a self-contained App so the helpers can't be shared via
  // `MoleTopSim.xxx` without triggering its `extends App` body).
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

  def buildFrame(words: Seq[Int]): Seq[Int] = {
    val n = words.size
    val lenBytes = Seq(n & 0xff, (n >> 8) & 0xff)
    val wordBytes = words.flatMap(w => Seq(w & 0xff, (w >> 8) & 0xff))
    val payload = lenBytes ++ wordBytes
    val crc = crc16Xmodem(payload)
    payload ++ Seq(crc & 0xff, (crc >> 8) & 0xff)
  }

  val cfg = MoleConfig(
    fabricFreqHz = 24 MHz,
    quarterPeriodCyclesReset = 6,
    programWordCount = 16,
    resultRingByteCount = 32,
    captureMaxBits = 64,
    uartBaud = 1_000_000
  )

  val cleanHaltWord = 0xc000

  // ----------------------------------------------------------------
  // Common helpers (sim-side UART byte injection + capture).
  // ----------------------------------------------------------------

  def sendByte(dut: MoleTopSimDut, byte: Int): Unit = {
    dut.io.txData.payload #= byte
    dut.io.txData.valid #= true
    dut.clockDomain.waitSamplingWhere(dut.io.txData.ready.toBoolean)
    dut.io.txData.valid #= false
  }

  def sendFrame(dut: MoleTopSimDut, frame: Seq[Int]): Unit = {
    for (b <- frame) sendByte(dut, b)
  }

  def recvByte(dut: MoleTopSimDut, maxCycles: Int = 500_000): Int = {
    dut.io.rxData.ready #= true
    var waited = 0
    while (!dut.io.rxData.valid.toBoolean && waited < maxCycles) {
      dut.clockDomain.waitSampling()
      waited += 1
    }
    assert(
      dut.io.rxData.valid.toBoolean,
      s"recvByte: timed out after $maxCycles cycles waiting for sim UART RX"
    )
    val b = dut.io.rxData.payload.toInt
    dut.clockDomain.waitSampling()
    dut.io.rxData.ready #= false
    b
  }

  /** Non-asserting variant of [[recvByte]]. Returns `Some(b)` if a byte arrives
    * within `maxCycles`, `None` otherwise. Used to assert **absence** of a byte
    * (Test 3 + 4): if this returns `Some` when we expected `None`, the halt
    * path is leaking bytes.
    */
  def tryRecvByte(dut: MoleTopSimDut, maxCycles: Int): Option[Int] = {
    dut.io.rxData.ready #= true
    var waited = 0
    while (!dut.io.rxData.valid.toBoolean && waited < maxCycles) {
      dut.clockDomain.waitSampling()
      waited += 1
    }
    if (dut.io.rxData.valid.toBoolean) {
      val b = dut.io.rxData.payload.toInt
      dut.clockDomain.waitSampling()
      dut.io.rxData.ready #= false
      Some(b)
    } else {
      dut.io.rxData.ready #= false
      None
    }
  }

  def doReset(dut: MoleTopSimDut): Unit = {
    dut.io.externalReset #= false
    dut.io.txData.valid #= false
    dut.io.rxData.ready #= false
    dut.io.rtsIn #= false
    dut.clockDomain.waitSampling(10)
    dut.io.externalReset #= true
    dut.clockDomain.waitSampling(10)
  }

  // The canonical short program every case uses. Two instructions:
  // a bus-mode set (one quarter-bit of engine work) and a clean HALT.
  // The result ring holds Revision lo, Revision hi, then a long run
  // of zero-word entries, then the HALT word at the tail.
  val shortProgram = Seq(
    Instruction.encode(Instruction.SetBusMode(BusMode.i2c)),
    Instruction.encode(Instruction.Halt(0))
  )
  val shortFrame = buildFrame(shortProgram)

  // Upper bound on the wall-clock time it takes for the engine to
  // load + run + drain a `shortFrame` with the test config:
  //
  //   - Loader RX: ~12 bytes * 240 cyc/byte ~ 2880 cyc
  //   - Engine run: a couple of bit windows, ~1000 cyc
  //   - Drain UART TX: 32 bytes * 240 cyc/byte ~ 7680 cyc
  //
  // ~12 K cycles total. 50 K is comfortable headroom and matches
  // MoleTopSim's per-case budget.
  val fullCycleBudget = 50_000

  // ----------------------------------------------------------------
  // Case 1: CTS asserted in acceptLoad.
  // ----------------------------------------------------------------
  println("--- MoleTopFlowControlSim: cts-asserted-on-reset ---")
  SimConfig
    .compile(MoleTopSimDut(cfg))
    .doSim("cts-asserted-on-reset") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      doReset(dut)

      // The phase FSM enters `acceptLoadState` straight out of reset,
      // so `io_uCts` (= !acceptRxComb) must be 0 (asserted, active-low)
      // immediately and remain so for as long as we do not send a frame.
      // Sample for many cycles to defend against late-arriving CDC
      // wobble or a missed default in `MoleTop`.
      var sawDeasserted = false
      for (_ <- 0 until 2_000) {
        if (dut.io.ctsOut.toBoolean) sawDeasserted = true
        dut.clockDomain.waitSampling()
      }
      assert(
        !sawDeasserted,
        "cts-asserted-on-reset: io_uCts deasserted (1) before any frame was sent"
      )
      println("   ok: io_uCts stayed asserted (0) for 2000 cycles post-reset")
    }

  // ----------------------------------------------------------------
  // Case 2: CTS deasserted during run+drain.
  // ----------------------------------------------------------------
  println("--- MoleTopFlowControlSim: cts-deasserted-during-run ---")
  SimConfig
    .compile(MoleTopSimDut(cfg))
    .doSim("cts-deasserted-during-run") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      doReset(dut)

      // Monitor `ctsOut` for the whole frame lifecycle. Fork it
      // BEFORE sending the frame so we don't miss the very first
      // transition out of acceptLoadState.
      var ctsDeassertedSeen = false
      var monitorDone = false
      val ctsMonitor = fork {
        while (!monitorDone) {
          if (dut.io.ctsOut.toBoolean) ctsDeassertedSeen = true
          dut.clockDomain.waitSampling()
        }
      }

      val received = mutable.Buffer.empty[Int]
      val drainFork = fork {
        while (received.size < cfg.resultRingByteCount) {
          received += recvByte(dut)
        }
      }

      sendFrame(dut, shortFrame)
      drainFork.join()
      monitorDone = true
      ctsMonitor.join()

      assert(
        ctsDeassertedSeen,
        "cts-deasserted-during-run: io_uCts never deasserted while engine was running / draining"
      )

      // After drain, the phase FSM is back in acceptLoadState. Sample
      // ctsOut over a settling window: it must read 0 (asserted) so
      // the host driver knows it can send the next frame.
      dut.clockDomain.waitSampling(50)
      assert(
        !dut.io.ctsOut.toBoolean,
        "cts-deasserted-during-run: io_uCts did not re-assert after drain completed"
      )

      val n = cfg.resultRingByteCount
      val gotHalt = (received(n - 1) << 8) | received(n - 2)
      assert(
        gotHalt == cleanHaltWord,
        s"cts-deasserted-during-run: HALT word 0x${gotHalt.toHexString} != 0x${cleanHaltWord.toHexString}"
      )

      println(
        s"   ok: CTS toggled (asserted -> deasserted -> asserted) across run+drain, $n bytes captured"
      )
    }

  // ----------------------------------------------------------------
  // Case 3: TX backpressure on RTS deasserted.
  // ----------------------------------------------------------------
  println("--- MoleTopFlowControlSim: tx-halts-when-rts-deasserted ---")
  SimConfig
    .compile(MoleTopSimDut(cfg))
    .doSim("tx-halts-when-rts-deasserted") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      doReset(dut)

      // Host says "I'm not ready" BEFORE the engine even starts.
      // Loader RX is unaffected (the loader is on the host->Mole
      // path; RTS gates the Mole->host path). The frame loads, the
      // engine runs to HALT, the drainer fills the result ring,
      // and `io_uTx` STAYS IDLE because Stream.haltWhen masks every
      // `data.valid` the drainer raises.
      dut.io.rtsIn #= true
      sendFrame(dut, shortFrame)

      // Give the engine plenty of time to load, run, and try to
      // drain. With RTS deasserted, sim-side `rxData.valid` MUST
      // never assert: if it does, the drainer is leaking bytes.
      tryRecvByte(dut, maxCycles = fullCycleBudget) match {
        case Some(b) =>
          fail(
            s"tx-halts-when-rts-deasserted: drainer leaked byte 0x${b.toHexString} while RTS was deasserted"
          )
        case None => ()
      }

      // Drop RTS. The full ring drains.
      dut.io.rtsIn #= false
      val received = mutable.Buffer.empty[Int]
      while (received.size < cfg.resultRingByteCount) {
        received += recvByte(dut)
      }

      val n = cfg.resultRingByteCount
      val gotHalt = (received(n - 1) << 8) | received(n - 2)
      assert(
        gotHalt == cleanHaltWord,
        s"tx-halts-when-rts-deasserted: HALT word 0x${gotHalt.toHexString} != 0x${cleanHaltWord.toHexString} after resuming"
      )

      println(
        s"   ok: drainer halted under RTS, resumed cleanly with $n bytes drained"
      )
    }

  // ----------------------------------------------------------------
  // Case 4: TX resumes after mid-drain halt.
  // ----------------------------------------------------------------
  println("--- MoleTopFlowControlSim: tx-resumes-after-mid-drain-halt ---")
  SimConfig
    .compile(MoleTopSimDut(cfg))
    .doSim("tx-resumes-after-mid-drain-halt") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      doReset(dut)

      val received = mutable.Buffer.empty[Int]

      // First half: drain N bytes from the ring with RTS asserted.
      val firstHalf = cfg.resultRingByteCount / 2
      val partialDrain = fork {
        while (received.size < firstHalf) {
          received += recvByte(dut)
        }
      }
      sendFrame(dut, shortFrame)
      partialDrain.join()

      // Mid-drain: assert RTS deasserted. UartTx may have one byte
      // already loaded into its shift register; that byte completes
      // on the wire (standard HW-flow-control semantics).
      dut.io.rtsIn #= true

      // Allow one in-flight UART frame to finish (~240 cycles at
      // 1 Mbaud / 24 MHz fabric). Accept at most one extra byte
      // here as expected behaviour.
      tryRecvByte(dut, maxCycles = 400) match {
        case Some(b) => received += b
        case None    => ()
      }

      // From here onward NO further byte must come through for a
      // good long while. This is the actual back-pressure assertion:
      // the drainer is held, UartTx is idle waiting for valid+ready.
      tryRecvByte(dut, maxCycles = 5_000) match {
        case Some(b) =>
          fail(
            s"tx-resumes-after-mid-drain-halt: drainer kept TX'ing under deasserted RTS (got 0x${b.toHexString})"
          )
        case None => ()
      }

      val sizeAfterStall = received.size

      // Drop RTS. The remaining bytes flow out.
      dut.io.rtsIn #= false
      while (received.size < cfg.resultRingByteCount) {
        received += recvByte(dut)
      }

      // Sanity: at least one fresh byte after RTS dropped (we didn't
      // already happen to drain the full ring during the in-flight
      // window).
      assert(
        received.size > sizeAfterStall,
        s"tx-resumes-after-mid-drain-halt: no bytes drained after RTS re-asserted (something else stalled the path)"
      )

      val n = cfg.resultRingByteCount
      assert(
        received.size == n,
        s"tx-resumes-after-mid-drain-halt: expected $n bytes total, drained ${received.size}"
      )
      val gotHalt = (received(n - 1) << 8) | received(n - 2)
      assert(
        gotHalt == cleanHaltWord,
        s"tx-resumes-after-mid-drain-halt: HALT word 0x${gotHalt.toHexString} != 0x${cleanHaltWord.toHexString}"
      )

      println(
        s"   ok: $firstHalf bytes pre-stall, halted, resumed, $n bytes total, clean HALT"
      )
    }

  println("--- MoleTopFlowControlSim: all cases passed ---")

  private def fail(msg: String): Nothing = {
    assert(cond = false, msg)
    throw new AssertionError(msg)
  }
}
