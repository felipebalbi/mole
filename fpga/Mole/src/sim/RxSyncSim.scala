// SPDX-FileCopyrightText: 2026 Felipe Balbi
// SPDX-License-Identifier: CERN-OHL-W-2.0

package mole

// Imported from felipebalbi/icebreaker-spinalhdl-examples@98c06a8cf39f6c212d3b46d98868aca398a82186 Uart/src/sim/RxSyncSim.scala.
// Modified for Mole only as documented inline (search for 'Mole').

import spinal.core._
import spinal.core.sim._

import scala.collection.mutable.ArrayBuffer

/** Standalone sim for [[RxSync]].
  *
  * Strategy `RxSync` is a depth-2 [[spinal.lib.BufferCC]] chain, so the
  * behavioural property we care about is exactly:
  *
  * `syncOut(k) == asyncIn(k - 2)` for every cycle k after reset, with
  * `syncOut(k) == true` for k < 2 (the `init = True` value persists until the
  * first two real edges have shifted things in).
  *
  * That single invariant captures both "exactly 2-cycle latency" and "no
  * transition is dropped, duplicated, or reordered". The cleanest way to verify
  * it without getting tangled in stimulus-thread timing semantics (`asyncIn #=
  * X` followed immediately by `waitSampling()` does not always make `X`
  * observable in FF1 within the same delta — see the long-standing "settle"
  * cycle in `TxShiftRegSim.loadByte` for the same gotcha) is to record signal
  * values inside an `onSamplings` callback, which fires post-edge with both
  * inputs and registers committed, and then post-validate the recorded
  * sequence.
  *
  * What we check
  *   1. **Post-reset idle.** With `asyncIn` driven low *during* reset,
  *      `syncOut` must remain high — proving that `init = True` wins over
  *      whatever junk happens to be on the asynchronous input pin while the
  *      board is coming out of reset. 2. **The 2-cycle invariant.** Over a
  *      deliberately diverse stimulus sequence — single rising and falling
  *      edges, 1-cycle high and low pulses, long runs of 0 and 1, and a
  *      500-cycle random stream — `syncOut(k) == asyncIn(k-2)` must hold for
  *      every recorded cycle. If latency is ever 1 or 3 cycles (regression on
  *      bufferDepth), or any transition is lost (a "clever" synchronizer that
  *      swallows sub-bit-period pulses), or any transition is duplicated, the
  *      invariant fires immediately at the offending index.
  *
  * Run: `sbt "runMain mole.RxSyncSim"`
  */
object RxSyncSim {
  def main(args: Array[String]): Unit = {

    SimConfig.withWave
      .compile(RxSync())
      .doSim { dut =>
        dut.clockDomain.forkStimulus(10)

        // -------------------------------------------------------------
        // (1) Post-reset idle
        //
        // Drive asyncIn low while reset is still asserted. Both FFs are
        // held at init=True regardless of their input during reset, so
        // syncOut must read high. forkStimulus deasserts reset very
        // early (within ~1 sim cycle, *not* the ~10 my first cut of this
        // sim assumed), so we only have a single-cycle window to read
        // syncOut before the now-released FFs start sampling the false
        // we're driving. waitSampling(2) lands inside that window:
        // even after one post-reset edge has clocked false into FF1,
        // FF2 (== syncOut) still holds the init value.
        // -------------------------------------------------------------
        dut.io.asyncIn #= false
        dut.clockDomain.waitSampling(2)
        assert(
          dut.io.syncOut.toBoolean,
          "post-reset: syncOut should idle high regardless of asyncIn (init = True)"
        )

        // Wait well past reset so any further stimulus is honoured
        // normally, then drive asyncIn high to flush whatever propagated
        // during the reset window out of the chain. After this,
        // asyncIn = true and both FFs = true.
        dut.clockDomain.waitSampling(20)
        dut.io.asyncIn #= true
        dut.clockDomain.waitSampling(4)
        assert(
          dut.io.syncOut.toBoolean,
          "after settling at asyncIn=1, syncOut must be 1"
        )

        // -------------------------------------------------------------
        // (2) Recorder + 2-cycle invariant
        //
        // From here on, log every rising edge into two parallel buffers.
        // onSamplings runs after the edge, so:
        //   - asyncSeq(k)  = the value sampled into FF1 at edge k
        //   - syncSeq(k)   = the value latched into FF2 at edge k
        //                  = the FF1 value from before edge k
        //                  = the asyncIn value sampled at edge k-1
        // Therefore the chain's defining property over the recording is
        // simply syncSeq(k) == asyncSeq(k-1) for k >= 1 (one register of
        // observable lag inside the recorder's frame of reference) and
        // the input-to-output relationship across the *full* depth-2
        // chain is syncSeq(k) == asyncSeq(k-2) for k >= 2 — that is the
        // property we assert below.
        //
        // We don't make any per-cycle assertions during stimulus; we
        // just drive bits and let the recorder collect. Drift between
        // "what I drove" and "what the edge actually sampled" (the bug
        // that wrecked the previous version of this sim) cannot affect
        // correctness here because both halves of the comparison come
        // from the same recording.
        // -------------------------------------------------------------
        val asyncSeq = ArrayBuffer[Boolean]()
        val syncSeq = ArrayBuffer[Boolean]()
        dut.clockDomain.onSamplings {
          asyncSeq += dut.io.asyncIn.toBoolean
          syncSeq += dut.io.syncOut.toBoolean
        }

        /** Drive a constant value for `cycles` clock periods, letting the
          * recorder capture every edge.
          */
        def drive(value: Boolean, cycles: Int): Unit = {
          dut.io.asyncIn #= value
          dut.clockDomain.waitSampling(cycles)
        }

        // Diverse stimulus — each segment is a separate behavioural
        // scenario. The recorder treats them as one continuous stream;
        // the post-validation invariant catches any drop / dupe / shift
        // regardless of which segment it happens in.

        // Settle at false so we have a known baseline before exercising
        // edges.
        drive(false, 5)

        // Single rising edge: drive true for several cycles, then back
        // to false. Tests that a clean low->high transition propagates.
        drive(true, 5)

        // Single falling edge.
        drive(false, 5)

        // 1-cycle high pulse on a low baseline. The most demanding
        // "no transition lost" case — a sub-bit-period glitch the
        // synchronizer must NOT swallow (it's our caller's job to
        // reject glitches via the half-bit verify in the RX FSM, not
        // ours).
        drive(true, 1)
        drive(false, 5)

        // 1-cycle low pulse on a high baseline.
        drive(true, 5)
        drive(false, 1)
        drive(true, 5)

        // Long runs of constant value — should produce zero recorded
        // transitions on syncSeq beyond the initial settling. The
        // invariant check below proves this implicitly: if syncSeq
        // ever differs from asyncSeq[-2], a spurious transition would
        // light it up.
        drive(false, 50)
        drive(true, 50)

        // Random stream — fixed seed so any failure is reproducible.
        val rng = new scala.util.Random(0xc0ffeeL)
        for (_ <- 0 until 500) {
          drive(rng.nextBoolean(), 1)
        }

        // -------------------------------------------------------------
        // Post-validation
        // -------------------------------------------------------------
        val n = syncSeq.length
        assert(asyncSeq.length == n, "recorder length mismatch (internal)")

        // First two recorded cycles must show the init value, since
        // the chain hadn't yet had two edges to shift the recorded
        // input through.
        for (k <- 0 until math.min(2, n)) {
          assert(
            syncSeq(k),
            s"cycle $k: syncOut should still hold init=True (only $k post-record edges so far), got ${syncSeq(k)}"
          )
        }

        // The main event: 2-cycle latency, every cycle, no exceptions.
        for (k <- 2 until n) {
          val expected = asyncSeq(k - 2)
          val got = syncSeq(k)
          assert(
            got == expected,
            f"cycle $k%d: syncOut=$got but asyncIn(k-2)=$expected " +
              f"(asyncIn(k-1)=${asyncSeq(k - 1)}, asyncIn(k)=${asyncSeq(k)}). " +
              "Either latency drifted from 2 cycles or a transition was lost/duplicated."
          )
        }

        println(
          s"OK: RxSync — verified 2-cycle latency and lossless propagation across $n recorded edges"
        )
      }
  }
}
