// SPDX-FileCopyrightText: 2026 Felipe Balbi
// SPDX-License-Identifier: CERN-OHL-W-2.0

package mole

// Imported from felipebalbi/icebreaker-spinalhdl-examples@98c06a8cf39f6c212d3b46d98868aca398a82186 Uart/src/sim/UartTxSim.scala.
// Modified for Mole only as documented inline (search for 'Mole').

import spinal.core._
import spinal.core.sim._

/** Standalone sim for [[UartTx]] — the full wrapper.
  *
  * Where [[TxFsmSim]] tested the FSM in isolation by faking its neighbours,
  * this sim treats `UartTx` as a black box: drive the `data` Stream in, watch
  * the `tx` line out, and recover frames the same way a real receiver would.
  * The real BaudGenerator, real TxShiftReg, and real TxFsm are all in the loop.
  *
  * What we verify
  *   1. **Idle line.** After reset, `io.tx` sits high.
  *   2. **Single-byte round trip.** Across a small pattern set (0x00, 0xFF,
  *      0xAA, 0x55, 0xAD), drive a byte through the Stream and recover the bits
  *      via mid-bit sampling on `io.tx`. Verify start=0, the LSB-first data,
  *      optional parity, and stop=1.
  *   3. **Back-to-back transmission.** Hold `data.valid` continuously high
  *      while feeding new payloads each handshake. Confirm each byte arrives
  *      intact with no missing or extra bit periods.
  *
  * 3b. **Single continuous burst.** Strictly tighter than (3): the producer
  * holds `valid` high across N frames and only swaps the payload between
  * handshakes — `valid` never falls until the last byte is accepted. Catches
  * bugs that would slip past (3) such as an FSM requiring `valid` to deassert
  * between frames, or stale-payload latching.
  *
  *   4. **Handshake timing.** `data.ready` is high in idle, drops the cycle a
  *      frame starts, and stays low until the FSM is back in idle. The byte
  *      gets accepted on the same cycle as the FSM leaves idle (which is also
  *      the cycle TxShiftReg latches the payload — see TxFsm and TxShiftReg for
  *      the load-priority contract).
  *   5. **CTS gates a NEW frame.** With `cts=0`, asserting `data.valid` must
  *      NOT trigger a transmit: `data.ready` stays low and `io.tx` stays high.
  *      Raising `cts` then lets the byte transmit normally.
  *   6. **CTS dropped MID-frame does NOT abort.** Once a frame has started,
  *      dropping `cts` to 0 must let the in-flight frame complete cleanly. CTS
  *      gates the *start* of new frames, not the in-flight one.
  *   7. **Config-matrix smoke.** Run a couple of bytes through 8N1, 8N2, 8E1,
  *      and 8O1 to confirm `cfg` is correctly threaded through to all
  *      sub-blocks.
  *   8. **`useCts = false`.** A separate elaboration drops the `cts` port
  *      entirely and confirms a byte still transmits — proving the
  *      optional-port idiom works and the gate cleanly synthesises away.
  *
  * Mid-bit sampling To recover a frame we wait for the falling edge of the
  * start bit, walk to the *middle* of the start bit (`ticksPerBit/2` cycles
  * further), then advance one full `ticksPerBit` per bit. This is exactly what
  * a real UART RX does, and it tolerates the 1-cycle pipeline delay introduced
  * by `TxFsm`'s registered txReg without any special accounting in the test.
  *
  * Run: `sbt "runMain mole.UartTxSim"`
  */
object UartTxSim {

  /** Run the standard test plan for one wrapper config.
    *
    * Tests 5 and 6 (CTS gating) are skipped when `cfg.useCts == false` because
    * there's no port to drive.
    */
  def runTopTest(
      cfg: UartConfig,
      patterns: Seq[Int],
      backToBackBytes: Seq[Int]
  ): Unit = {
    val ticksPerBit = cfg.ticksPerBit
    val parityLabel = cfg.parity match {
      case ParityType.None => "N"
      case ParityType.Even => "E"
      case ParityType.Odd  => "O"
    }
    val cfgLabel =
      s"${cfg.dataBits}${parityLabel}${cfg.stopBits}" +
        (if (cfg.useCts) "" else " (no-cts)")

    SimConfig.withWave
      .compile(UartTx(cfg))
      .doSim { dut =>
        dut.clockDomain.forkStimulus(10)
        dut.io.baudPhaseInc #=
          BaudGenerator.phaseIncFor(cfg, BaudGenerator.defaultAccWidth)

        // ---------- helpers ----------------------------------------------

        /** Default-deassert all driven inputs. CTS, when present, defaults to
          * high so frames can start; tests that exercise the gate set it
          * explicitly.
          */
        def initInputs(): Unit = {
          dut.io.data.valid #= false
          dut.io.data.payload #= 0
          if (cfg.useCts) dut.io.cts #= true
        }

        /** Push a single byte through the Stream, blocking until the handshake
          * completes (ready ∧ valid). Drops `valid` after the accepting edge so
          * the next byte must be re-offered explicitly.
          */
        def sendByte(b: Int): Unit = {
          dut.io.data.payload #= b
          dut.io.data.valid #= true
          // Wait for the wrapper to assert ready while we hold valid.
          // ready can already be high before we get here, so check
          // post-edge.
          do { dut.clockDomain.waitSampling() }
          while (!dut.io.data.ready.toBoolean)
          dut.io.data.valid #= false
        }

        /** Push N bytes as a single continuous Stream burst: `valid` is held
          * high the whole time, the payload is swapped on each ready edge, and
          * `valid` is only dropped after the LAST byte is accepted. This
          * exercises the "producer never deasserts valid" contract of Stream
          * and catches bugs that wouldn't show up with `sendByte` (which drops
          * valid between bytes), e.g. an FSM that requires valid to fall before
          * launching the next frame, or stale-payload latching when payload
          * changes the same cycle the wrapper accepts.
          */
        def sendBurst(bytes: Seq[Int]): Unit = {
          for (b <- bytes) {
            dut.io.data.payload #= b
            dut.io.data.valid #= true
            do { dut.clockDomain.waitSampling() }
            while (!dut.io.data.ready.toBoolean)
            // intentionally NOT dropping valid here — payload simply
            // changes on the next loop iteration.
          }
          dut.io.data.valid #= false
        }

        /** Wait until `io.tx` falls (the start of the next frame) or
          * `timeoutBits` bit-periods pass. Returns true if the edge was seen;
          * false on timeout.
          */
        def waitForStartEdge(timeoutBits: Int): Boolean = {
          var cycles = 0
          val maxCycles = timeoutBits * ticksPerBit
          while (dut.io.tx.toBoolean && cycles < maxCycles) {
            dut.clockDomain.waitSampling()
            cycles += 1
          }
          !dut.io.tx.toBoolean
        }

        /** Recover one frame from the line by mid-bit sampling.
          *
          * Caller must already have observed the falling edge of the start bit
          * (i.e. `io.tx` is currently low and we're somewhere inside the
          * start-bit window). We walk to the middle of the start bit, then
          * advance one full bit period per sample.
          *
          * Returns (startBit, dataBitsLsbFirst, parityOpt, firstStopBit).
          */
        def sampleFrame(): (Boolean, Seq[Boolean], Option[Boolean], Boolean) = {
          // Walk to the middle of the start bit.
          dut.clockDomain.waitSampling(ticksPerBit / 2)
          val start = dut.io.tx.toBoolean
          // Mid of d0
          dut.clockDomain.waitSampling(ticksPerBit)
          val data = (0 until cfg.dataBits).map { _ =>
            val b = dut.io.tx.toBoolean
            dut.clockDomain.waitSampling(ticksPerBit)
            b
          }
          val parity = if (cfg.parity != ParityType.None) {
            val p = dut.io.tx.toBoolean
            dut.clockDomain.waitSampling(ticksPerBit)
            Some(p)
          } else None
          // We're now at mid of the first stop bit.
          val stop = dut.io.tx.toBoolean
          (start, data, parity, stop)
        }

        /** Reassemble the data bits (LSB-first) into an integer for comparison
          * against the expected byte.
          */
        def bitsToInt(bits: Seq[Boolean]): Int =
          bits.zipWithIndex.foldLeft(0) { case (acc, (b, i)) =>
            if (b) acc | (1 << i) else acc
          }

        /** Expected parity bit for the given data, per `cfg.parity`. */
        def expectedParity(byte: Int): Boolean = {
          val ones = (0 until cfg.dataBits).count(i => (byte & (1 << i)) != 0)
          val even = (ones % 2) == 0
          cfg.parity match {
            case ParityType.None => false
            case ParityType.Even => !even // make total ones even
            case ParityType.Odd  => even // make total ones odd
          }
        }

        /** Drive a byte and verify the recovered frame matches it. */
        def expectByte(byte: Int, label: String): Unit = {
          // Concurrent fork sends; main thread samples the line.
          val sender = fork {
            sendByte(byte)
          }
          assert(
            waitForStartEdge(timeoutBits = 16),
            s"[$label] timed out waiting for start edge for 0x${byte.toHexString}"
          )
          val (start, data, parity, stop) = sampleFrame()
          assert(
            !start,
            s"[$label] start bit not low for 0x${byte.toHexString}"
          )
          val recovered = bitsToInt(data)
          val mask = (1 << cfg.dataBits) - 1
          assert(
            recovered == (byte & mask),
            s"[$label] data mismatch for 0x${byte.toHexString}: got 0x${recovered.toHexString}"
          )
          parity.foreach { p =>
            val expected = expectedParity(byte)
            assert(
              p == expected,
              s"[$label] parity mismatch for 0x${byte.toHexString}: got $p expected $expected"
            )
          }
          assert(stop, s"[$label] stop bit not high for 0x${byte.toHexString}")
          sender.join()
        }

        // ---------- bring-up ---------------------------------------------

        initInputs()
        dut.clockDomain.waitSampling(20)

        // ---------- (1) idle high ----------------------------------------

        assert(
          dut.io.tx.toBoolean,
          s"[$cfgLabel] tx not high in idle"
        )
        assert(
          dut.io.data.ready.toBoolean,
          s"[$cfgLabel] data.ready not high in idle"
        )

        // ---------- (2) single-byte round trip ---------------------------

        for (b <- patterns) {
          expectByte(b, s"single $cfgLabel")
          // Settle a couple of bit periods between frames to make sure we
          // see a clean idle->start transition for the next one.
          dut.clockDomain.waitSampling(ticksPerBit * 2)
        }

        // ---------- (3) back-to-back transmission ------------------------

        // Producer fork holds valid high and feeds new bytes whenever the
        // wrapper asserts ready. Receiver fork samples one frame per byte
        // and checks it.
        val recovered = collection.mutable.ArrayBuffer.empty[Int]
        val producer = fork {
          for (b <- backToBackBytes) {
            sendByte(b)
          }
        }
        for (b <- backToBackBytes) {
          assert(
            waitForStartEdge(timeoutBits = 24),
            s"[b2b $cfgLabel] timed out waiting for start of 0x${b.toHexString}"
          )
          val (start, data, parity, stop) = sampleFrame()
          assert(
            !start,
            s"[b2b $cfgLabel] start not low for 0x${b.toHexString}"
          )
          assert(stop, s"[b2b $cfgLabel] stop not high for 0x${b.toHexString}")
          val rec = bitsToInt(data)
          val mask = (1 << cfg.dataBits) - 1
          assert(
            rec == (b & mask),
            s"[b2b $cfgLabel] data mismatch: got 0x${rec.toHexString} expected 0x${(b & mask).toHexString}"
          )
          parity.foreach { p =>
            assert(p == expectedParity(b), s"[b2b $cfgLabel] parity mismatch")
          }
          recovered += rec
        }
        producer.join()
        assert(
          recovered.toSeq == backToBackBytes.map(_ & ((1 << cfg.dataBits) - 1)),
          s"[b2b $cfgLabel] sequence mismatch: $recovered"
        )

        // ---------- (3b) single continuous burst -------------------------

        // Hold valid high across all N bytes (only swap payload between
        // frames). Strictly tighter than (3) — proves the wrapper accepts
        // a Stream burst the way Stream's contract intends, with no
        // valid-falling-edge required between frames.
        dut.clockDomain.waitSampling(ticksPerBit * 2)
        val burstRecovered = collection.mutable.ArrayBuffer.empty[Int]
        val burstProducer = fork {
          sendBurst(backToBackBytes)
        }
        for (b <- backToBackBytes) {
          assert(
            waitForStartEdge(timeoutBits = 24),
            s"[burst $cfgLabel] timed out waiting for start of 0x${b.toHexString}"
          )
          val (start, data, parity, stop) = sampleFrame()
          assert(
            !start,
            s"[burst $cfgLabel] start not low for 0x${b.toHexString}"
          )
          assert(
            stop,
            s"[burst $cfgLabel] stop not high for 0x${b.toHexString}"
          )
          val rec = bitsToInt(data)
          val mask = (1 << cfg.dataBits) - 1
          assert(
            rec == (b & mask),
            s"[burst $cfgLabel] data mismatch: got 0x${rec.toHexString} expected 0x${(b & mask).toHexString}"
          )
          parity.foreach { p =>
            assert(p == expectedParity(b), s"[burst $cfgLabel] parity mismatch")
          }
          burstRecovered += rec
        }
        burstProducer.join()
        assert(
          burstRecovered.toSeq == backToBackBytes
            .map(_ & ((1 << cfg.dataBits) - 1)),
          s"[burst $cfgLabel] sequence mismatch: $burstRecovered"
        )

        // ---------- (4) handshake timing ---------------------------------

        // Settle and confirm ready returns high after the b2b burst.
        dut.clockDomain.waitSampling(ticksPerBit * 2)
        assert(
          dut.io.data.ready.toBoolean,
          s"[$cfgLabel] data.ready did not return high after b2b"
        )

        // Drive a single byte via sendByte (which blocks until the
        // handshake commits, then drops valid). After sendByte returns
        // we're at the *post-edge of the handshake cycle*, but
        // `ready.toBoolean` here would still return the pre-edge value
        // (true — what made the handshake fire). Advance one more
        // sampling so we observe the cycle AFTER the handshake, where
        // the FSM is in startState and ready has gone low. (Same
        // SpinalSim read-semantics quirk that bit us in TxFsmSim.)
        val handshakeByte = 0x5a & ((1 << cfg.dataBits) - 1)
        sendByte(handshakeByte)
        dut.clockDomain.waitSampling()
        assert(
          !dut.io.data.ready.toBoolean,
          s"[$cfgLabel] ready did not drop after handshake"
        )
        // Wait for the frame to play out and confirm ready returns high.
        // Frame length: 1 start + dataBits + (parityBits) + stopBits.
        val parityBits = if (cfg.parity != ParityType.None) 1 else 0
        val frameTicks =
          (1 + cfg.dataBits + parityBits + cfg.stopBits) * ticksPerBit
        dut.clockDomain.waitSampling(frameTicks + ticksPerBit)
        assert(
          dut.io.data.ready.toBoolean,
          s"[$cfgLabel] ready did not return high after frame"
        )

        // ---------- (5) and (6): CTS tests (only if useCts) --------------

        if (cfg.useCts) {
          // (5) CTS=0 must hold off the frame.
          dut.io.cts #= false
          dut.clockDomain.waitSampling()
          assert(
            !dut.io.data.ready.toBoolean,
            s"[$cfgLabel] ready high while cts=0"
          )

          // Offer a byte from a fork. sendByte holds valid until the
          // handshake completes — which can't happen while cts=0, so
          // the fork parks here.
          val ctsByte = 0xa5 & ((1 << cfg.dataBits) - 1)
          val sender5 = fork { sendByte(ctsByte) }

          dut.clockDomain.waitSampling(ticksPerBit * 4)
          assert(
            dut.io.tx.toBoolean,
            s"[$cfgLabel] tx fell while cts=0 (frame leaked through gate)"
          )
          assert(
            !dut.io.data.ready.toBoolean,
            s"[$cfgLabel] ready rose while cts=0 (gate failed)"
          )

          // Raise CTS — handshake completes, sender5 drops valid, frame
          // transmits exactly once.
          dut.io.cts #= true
          assert(
            waitForStartEdge(timeoutBits = 4),
            s"[$cfgLabel] no start edge after raising cts"
          )
          val (s5, d5, p5, st5) = sampleFrame()
          assert(!s5 && st5, s"[$cfgLabel] CTS-released frame malformed")
          assert(
            bitsToInt(d5) == ctsByte,
            s"[$cfgLabel] CTS-released data mismatch"
          )
          p5.foreach(p =>
            assert(
              p == expectedParity(ctsByte),
              s"[$cfgLabel] CTS-released parity mismatch"
            )
          )
          sender5.join()
          dut.clockDomain.waitSampling(ticksPerBit * 2)

          // (6) CTS dropped mid-frame must NOT abort the in-flight frame.
          val midFrameByte = 0xc3 & ((1 << cfg.dataBits) - 1)
          val sender6 = fork { sendByte(midFrameByte) }
          assert(
            waitForStartEdge(timeoutBits = 8),
            s"[$cfgLabel] no start edge for mid-frame test"
          )
          // We're now near the start bit. Drop CTS partway through the
          // frame (after a couple of bit periods).
          dut.clockDomain.waitSampling(ticksPerBit * 2)
          dut.io.cts #= false
          val (s6, d6, p6, st6) = {
            // We've already consumed ~2.5 bit periods inside sampleFrame
            // semantics; redo the sampler from the current position by
            // calling it from start. Simpler: use a fresh sampleFrame
            // call that assumes we're inside the start window — but we
            // already advanced 2 bit periods so we'd be inside d1.
            // Instead: skip ahead and re-watch for the *current* frame's
            // tail. Since the FSM cannot abort, the line will eventually
            // return high (stop) and ready will reassert.
            val dataMid = collection.mutable.ArrayBuffer.empty[Boolean]
            // We've passed start + d0 + d1 boundaries already (2
            // bit-periods after edge). Sample mid of d2..d{N-1}, then
            // optional parity, then stop.
            // We need to be at mid of the *next* bit. After
            // waitForStartEdge we were at the post-edge cycle. We then
            // walked 2*ticksPerBit, so we're at the beginning of the
            // 3rd bit period. Walk to its middle.
            dut.clockDomain.waitSampling(ticksPerBit / 2)
            // Bits already consumed: start(0), d0(1). Wait, we walked 2
            // full bit periods => we're at start of the bit *index 2*
            // counting start as 0, i.e. at d1. So mid of d1.
            // Read d1..d{N-1}.
            for (_ <- 1 until cfg.dataBits) {
              dataMid += dut.io.tx.toBoolean
              dut.clockDomain.waitSampling(ticksPerBit)
            }
            val parityB = if (cfg.parity != ParityType.None) {
              val p = dut.io.tx.toBoolean
              dut.clockDomain.waitSampling(ticksPerBit)
              Some(p)
            } else None
            val stopB = dut.io.tx.toBoolean
            // We never actually verified d0; trust that the wrapper would
            // also pass it (tested elsewhere). What we DO want to verify
            // is that the frame finished correctly despite cts dropping.
            (false, dataMid.toSeq, parityB, stopB)
          }
          assert(
            st6,
            s"[$cfgLabel] mid-frame cts drop aborted the frame (stop bit not high)"
          )
          // d6 contains d1..d{N-1}; reconstruct only those bits and
          // compare against the matching slice of midFrameByte.
          val expectedTail =
            (1 until cfg.dataBits).map(i => (midFrameByte & (1 << i)) != 0)
          assert(
            d6 == expectedTail,
            s"[$cfgLabel] mid-frame data tail mismatch: got $d6 expected $expectedTail"
          )
          p6.foreach(p =>
            assert(
              p == expectedParity(midFrameByte),
              s"[$cfgLabel] mid-frame parity mismatch after cts drop"
            )
          )
          sender6.join()
          // Restore CTS for any subsequent tests that might run.
          dut.io.cts #= true
        }

        println(s"OK: UartTx behaves correctly ($cfgLabel)")
      }
  }

  def main(args: Array[String]): Unit = {
    val patterns8 = Seq(0x00, 0xff, 0xaa, 0x55, 0xad)
    val backToBackBytes8 = Seq(0x12, 0x34, 0x56, 0x78)

    // Small clk/baud ratio for fast sim -- ticksPerBit = 20.
    // Mole: bumped clk from 1_000_000 to 2_000_000 so the
    // configurations satisfy Mole's added require
    // `baudRate * oversample < clkFreqHz` in UartConfig.
    val clk = 2000000
    val baud = 100000

    // (7) Config-matrix smoke. The FSM and shift register have already
    // been exhaustively tested in their own sims; this loop only needs
    // to confirm the wrapper threads cfg through correctly. Each entry
    // exercises one axis of the config.
    val configs: Seq[(UartConfig, Seq[Int], Seq[Int])] = Seq(
      (
        UartConfig(clk, baud, 8, 1, ParityType.None),
        patterns8,
        backToBackBytes8
      ),
      (
        UartConfig(clk, baud, 8, 2, ParityType.None),
        patterns8,
        backToBackBytes8
      ),
      (
        UartConfig(clk, baud, 8, 1, ParityType.Even),
        patterns8,
        backToBackBytes8
      ),
      (
        UartConfig(clk, baud, 8, 1, ParityType.Odd),
        patterns8,
        backToBackBytes8
      )
    )

    for ((cfg, pats, b2b) <- configs) {
      runTopTest(cfg, pats, b2b)
    }

    // (8) useCts=false elaboration. Confirms the optional port idiom
    // works AND that the wrapper still transmits when there's no CTS
    // pin to gate on.
    val noCtsCfg =
      UartConfig(clk, baud, 8, 1, ParityType.None, useCts = false)
    runTopTest(noCtsCfg, Seq(0xa5, 0x5a), Seq(0x11, 0x22))
  }
}
