package mole

import spinal.core._
import spinal.core.sim._
import spinal.lib._

import scala.collection.mutable

// **C.11.b FINDING:** This sim currently fails because the engine
// fetches the v0.2 frame preamble (MAGIC + body_len) at PC=0 as
// instructions and traps. See `MoleTopSim.scala` header comment for
// the full diagnosis and the proposed fix shape. Not in aggregate
// `make sim`; run via `make sim-top-flow-control` to reproduce.

/** End-to-end audit for [[MoleTop]]'s USB-UART hardware flow control (v0.2).
  *
  * Companion to [[MoleTopSim]]; same wrapper ([[MoleTopSimDut]]), same fabric
  * clock and baud, focused exclusively on the `io_uCts` / `io_uRts` contract.
  *
  * Four cases (matching the v0 stash):
  *
  *   1. **CTS asserted in acceptLoad** --- after reset, before any frame is
  *      sent, `io_uCts` is the active-low `0` (asserted) and stays asserted for
  *      many cycles. Validates that the phase FSM enters `acceptLoadState`
  *      cleanly out of reset.
  *   2. **CTS deasserted during run+drain** --- after a frame loads, the phase
  *      FSM transitions through `runningState` and `drainingState` before
  *      returning to `acceptLoadState`. `io_uCts` must be `1` (deasserted)
  *      somewhere in that window and back to `0` (asserted) after the full
  *      drain.
  *   3. **TX backpressure on RTS deasserted** --- holding `io_uRts` high
  *      (deasserted) before a frame is sent must prevent the drainer from ever
  *      placing a byte on `io_uTx`. The engine still runs (RTS only gates TX).
  *      Dropping `io_uRts` then drains the full ring with a clean HALT word at
  *      the tail.
  *   4. **TX resumes after mid-drain halt** --- partial-drain N bytes, raise
  *      `io_uRts` mid-drain, wait long enough for one in-flight UART frame to
  *      complete, verify no further bytes arrive, then drop `io_uRts` and drain
  *      the remainder. Total bytes drained equals the full ring and the final
  *      HALT word is clean.
  */
object MoleTopFlowControlSim extends App {
  import MoleTopSimSupport._

  val cfg = testCfg
  val cleanHalt32 = cleanHaltWord32(0)

  /** Read the 32-bit HALT word at the tail of the result ring. */
  def haltWord32At(bytes: Seq[Int], totalBytes: Int): Long = {
    val b0 = bytes(totalBytes - 4).toLong & 0xff
    val b1 = (bytes(totalBytes - 3).toLong & 0xff) << 8
    val b2 = (bytes(totalBytes - 2).toLong & 0xff) << 16
    val b3 = (bytes(totalBytes - 1).toLong & 0xff) << 24
    b0 | b1 | b2 | b3
  }

  /** Non-asserting variant of [[recvByte]]. Returns `Some(b)` if a byte arrives
    * within `maxCycles`, `None` otherwise. Used to assert **absence** of a byte
    * (Cases 3 + 4): if this returns `Some` when we expected `None`, the halt
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

  // Canonical short program every case uses.
  val shortProgram = Seq(
    Instruction.encode(Instruction.SetBusMode(BusMode.i2c)),
    Instruction.encode(Instruction.Halt(0))
  )
  val shortFrame = buildFrame(shortProgram)

  // Upper bound on the wall-clock time it takes for the engine to
  // load + run + drain a `shortFrame` with the test config. v0
  // arithmetic still holds in v0.2: loader RX ~3K cyc + engine
  // ~1K cyc + drain UART TX ~8K cyc = ~12K total; 50K is comfortable
  // headroom and matches MoleTopSim's per-case budget.
  val fullCycleBudget = 50_000

  // ----------------------------------------------------------------
  // Case 1: CTS asserted in acceptLoad.
  // ----------------------------------------------------------------
  println("--- MoleTopFlowControlSim: cts-asserted-on-reset ---")
  simConfig
    .compile(MoleTopSimDut(cfg))
    .doSim("cts-asserted-on-reset") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      doReset(dut)

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
  simConfig
    .compile(MoleTopSimDut(cfg))
    .doSim("cts-deasserted-during-run") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      doReset(dut)

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

      // After drain, the phase FSM is back in acceptLoadState.
      dut.clockDomain.waitSampling(50)
      assert(
        !dut.io.ctsOut.toBoolean,
        "cts-deasserted-during-run: io_uCts did not re-assert after drain completed"
      )

      val gotHalt = haltWord32At(received.toSeq, cfg.resultRingByteCount)
      assert(
        gotHalt == cleanHalt32,
        s"cts-deasserted-during-run: HALT 0x${gotHalt.toHexString} != 0x${cleanHalt32.toHexString}"
      )

      println(
        s"   ok: CTS toggled across run+drain, ${cfg.resultRingByteCount} bytes captured"
      )
    }

  // ----------------------------------------------------------------
  // Case 3: TX backpressure on RTS deasserted.
  // ----------------------------------------------------------------
  println("--- MoleTopFlowControlSim: tx-halts-when-rts-deasserted ---")
  simConfig
    .compile(MoleTopSimDut(cfg))
    .doSim("tx-halts-when-rts-deasserted") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      doReset(dut)

      // Host says "I'm not ready" BEFORE the engine even starts.
      // Loader RX is unaffected (RTS gates the Mole->host path). The
      // frame loads, the engine runs to HALT, the drainer fills the
      // result ring, and `io_uTx` STAYS IDLE.
      dut.io.rtsIn #= true
      sendFrame(dut, shortFrame)

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

      val gotHalt = haltWord32At(received.toSeq, cfg.resultRingByteCount)
      assert(
        gotHalt == cleanHalt32,
        s"tx-halts-when-rts-deasserted: HALT 0x${gotHalt.toHexString} != 0x${cleanHalt32.toHexString} after resuming"
      )

      println(
        s"   ok: drainer halted under RTS, resumed cleanly with ${cfg.resultRingByteCount} bytes drained"
      )
    }

  // ----------------------------------------------------------------
  // Case 4: TX resumes after mid-drain halt.
  // ----------------------------------------------------------------
  println("--- MoleTopFlowControlSim: tx-resumes-after-mid-drain-halt ---")
  simConfig
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

      // Allow one in-flight UART frame to finish (~120 cycles at
      // 2 Mbaud / 24 MHz fabric).
      tryRecvByte(dut, maxCycles = 400) match {
        case Some(b) => received += b
        case None    => ()
      }

      // From here onward NO further byte must come through for a
      // good long while.
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

      assert(
        received.size > sizeAfterStall,
        s"tx-resumes-after-mid-drain-halt: no bytes drained after RTS re-asserted"
      )

      assert(
        received.size == cfg.resultRingByteCount,
        s"tx-resumes-after-mid-drain-halt: expected ${cfg.resultRingByteCount} bytes total, drained ${received.size}"
      )
      val gotHalt = haltWord32At(received.toSeq, cfg.resultRingByteCount)
      assert(
        gotHalt == cleanHalt32,
        s"tx-resumes-after-mid-drain-halt: HALT 0x${gotHalt.toHexString} != 0x${cleanHalt32.toHexString}"
      )

      println(
        s"   ok: $firstHalf bytes pre-stall, halted, resumed, ${cfg.resultRingByteCount} bytes total, clean HALT"
      )
    }

  println("--- MoleTopFlowControlSim: all cases passed ---")

  private def fail(msg: String): Nothing = {
    assert(false, msg)
    throw new AssertionError(msg)
  }
}
