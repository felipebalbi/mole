package mole

// Imported from felipebalbi/icebreaker-spinalhdl-examples@98c06a8cf39f6c212d3b46d98868aca398a82186 Uart/src/sim/RxFsmSim.scala.
// Modified for Mole only as documented inline (search for 'Mole').

import spinal.core._
import spinal.core.sim._

/** Standalone sim for [[RxFsm]].
  *
  * The DUT is just the FSM (which itself instantiates [[RxShiftReg]]); to stand
  * it up we drive two things from sim threads:
  *
  *   - A **free-running tick fork** that pulses `io.tick` once every
  *     `ticksPerOversample` system clocks regardless of FSM state. RX is unlike
  *     TX in this regard — the BaudGenerator is NOT gated by `busy`, because
  *     the half-bit verify after a start-bit edge needs ticks immediately.
  *   - A **UART line driver** (`sendFrame`) that walks `io.rx` through a start
  *     bit, `dataBits` data bits LSB-first, an optional parity bit, and
  *     `stopBits` stop bits, holding each level for one full bit period
  *     (`oversample × ticksPerOversample` system clocks).
  *
  * The "is the byte correct" check is done by reading the Stream payload after
  * the FSM raises `valid`. The FSM doesn't expose intermediate shift-register
  * state, so we don't try to peek at it — we drive a known byte on the line,
  * wait for `valid`, fire the handshake, and compare.
  *
  * What we verify
  *   1. **Post-reset idle.** No `valid`, no error flags, `busy` low. 2. **Byte
  *      sweep.** For each pattern in a chosen set, drive a full frame and
  *      assert that the consumer sees the expected payload with `valid` and no
  *      error flags. Patterns chosen for shift-direction coverage: 0x00, 0xFF
  *      (init bleeds), 0xAA, 0x55 (alternating), 0x80, 0x01 (LSB / MSB
  *      extremes), 0xAD (generic mix). 3. **Start-bit glitch rejection.** Drive
  *      a 1-cycle low pulse on `io.rx` while otherwise idle. The FSM must NOT
  *      raise `valid` and must return to `busy = false` without producing a
  *      frame. 4. **Framing error.** Send a frame with the stop bit forced low.
  *      Expect `framingError` high in the cycle the FSM returns to idle, and NO
  *      `valid` for that frame. 5. **Parity error.** Send a frame with a
  *      deliberately corrupted parity bit. Expect `parityError` and NO `valid`.
  *      6. **Parity OK.** Send a normal Even / Odd frame and expect NO
  *         parityError and a clean `valid`. 7. **Back-to-back frames.** Two
  *         frames with the consumer firing between them — both bytes received,
  *         no overrun, no errors. 8. **Overrun.** Two frames with `ready` held
  *         LOW between them. Expect `overrun` to fire on the second frame and
  *         `valid` to stay high.
  *
  * The whole suite runs against a sweep matrix: 8N1, 8N2, 8E1, 8O1, 5N1
  * (smaller width axis to exercise the parametrised counters).
  *
  * Run: `sbt "runMain mole.RxFsmSim"`
  */
object RxFsmSim {

  /** One pass against a freshly-elaborated [[RxFsm]] DUT. Pulled out of `main`
    * so the same body runs across the config sweep.
    */
  def runFsmTest(cfg: UartConfig, patterns: Seq[Int]): Unit = {

    // System clock vs. oversample tick relationship. Choose something
    // small but >1 so the tick-fork's "wait N-1 then pulse" loop is
    // nontrivial. ticksPerOversample = 2 means a tick every 2 clocks.
    val ticksPerOversample = 2
    val bitClocks = cfg.oversample * ticksPerOversample

    val parityLabel = cfg.parity match {
      case ParityType.None => "N"
      case ParityType.Even => "E"
      case ParityType.Odd  => "O"
    }
    val cfgLabel = s"${cfg.dataBits}${parityLabel}${cfg.stopBits}"

    SimConfig.withWave
      .compile(RxFsm(cfg))
      .doSim(cfgLabel) { dut =>
        dut.clockDomain.forkStimulus(10)

        // ------------------------------------------------------------------
        // Default the FSM's inputs to the idle state. RX line idles
        // high; consumer is initially NOT ready (the per-test code
        // raises ready as needed).
        // ------------------------------------------------------------------
        dut.io.rx #= true
        dut.io.tick #= false
        dut.io.payload.ready #= false
        dut.clockDomain.waitSampling(20)

        // ------------------------------------------------------------------
        // Free-running oversample-rate tick fork. Pulses `io.tick`
        // every `ticksPerOversample` clocks. Independent of FSM state.
        // ------------------------------------------------------------------
        val tickFork = fork {
          while (true) {
            for (_ <- 0 until ticksPerOversample - 1)
              dut.clockDomain.waitSampling()
            dut.io.tick #= true
            dut.clockDomain.waitSampling()
            dut.io.tick #= false
          }
        }

        // ------------------------------------------------------------------
        // Helpers
        // ------------------------------------------------------------------

        /** Build the wire-level bit sequence for a frame: `[start=0,
          * d0..d{N-1}, parity?, 1×stopBits]`.
          *
          * @param byte
          *   data byte; only the low `cfg.dataBits` bits are used.
          * @param parityOverride
          *   if `Some(b)`, force the parity bit to that value (useful for
          *   parity-error tests). If `None`, compute the correct parity from
          *   the data bits + parity scheme.
          * @param stopOverride
          *   if `Some(seq)`, use that as the stop-bit sequence (for
          *   framing-error tests). Otherwise emit `cfg.stopBits` ones.
          */
        def frameBits(
            byte: Int,
            parityOverride: Option[Boolean] = None,
            stopOverride: Option[Seq[Boolean]] = None
        ): Seq[Boolean] = {
          val mask = (1 << cfg.dataBits) - 1
          val masked = byte & mask
          val dataSeq =
            (0 until cfg.dataBits).map(i => ((masked >> i) & 1) == 1)
          val xorAll = dataSeq.foldLeft(false)(_ ^ _)
          val paritySeq: Seq[Boolean] = cfg.parity match {
            case ParityType.None => Seq.empty
            case ParityType.Even => Seq(parityOverride.getOrElse(xorAll))
            case ParityType.Odd  => Seq(parityOverride.getOrElse(!xorAll))
          }
          val stopSeq =
            stopOverride.getOrElse(Seq.fill(cfg.stopBits)(true))
          Seq(false) ++ dataSeq ++ paritySeq ++ stopSeq
        }

        /** Drive a sequence of bits onto `io.rx`, holding each value for one
          * full bit period (`oversample × ticksPerOversample` clocks).
          *
          * Does NOT raise `io.payload.ready` — the caller manages the
          * handshake.
          */
        def driveBits(bits: Seq[Boolean]): Unit = {
          for (b <- bits) {
            dut.io.rx #= b
            dut.clockDomain.waitSampling(bitClocks)
          }
        }

        /** Hold the line idle (high) for several bit periods so the FSM's
          * stop-state cleanup observably completes before the next stimulus.
          */
        def idleLine(bitsWide: Int = 2): Unit = {
          dut.io.rx #= true
          dut.clockDomain.waitSampling(bitClocks * bitsWide)
        }

        /** Wait up to `maxClocks` clocks for a predicate to become true.
          * Returns whether it did.
          */
        def waitFor(maxClocks: Int)(cond: => Boolean): Boolean = {
          var n = 0
          while (n < maxClocks && !cond) {
            dut.clockDomain.waitSampling()
            n += 1
          }
          cond
        }

        /** Fire the consumer handshake exactly once on the cycle the FSM raises
          * `valid`, returning the captured payload (and any error flags
          * observed on that cycle). Times out after `maxClocks` clocks.
          */
        def expectPayload(
            maxClocks: Int = bitClocks * 30
        ): (Long, Boolean, Boolean, Boolean) = {
          dut.io.payload.ready #= true
          val ok = waitFor(maxClocks)(dut.io.payload.valid.toBoolean)
          assert(
            ok,
            s"[$cfgLabel] expected valid within $maxClocks clocks, never saw it"
          )
          val payload = dut.io.payload.payload.toLong
          val framing = dut.io.framingError.toBoolean
          val parity = dut.io.parityError.toBoolean
          val overrun = dut.io.overrun.toBoolean
          // One more cycle to consume the handshake.
          dut.clockDomain.waitSampling()
          dut.io.payload.ready #= false
          (payload, framing, parity, overrun)
        }

        // ------------------------------------------------------------------
        // (1) Post-reset idle.
        // ------------------------------------------------------------------
        assert(
          !dut.io.payload.valid.toBoolean,
          s"[$cfgLabel] valid high at reset"
        )
        assert(
          !dut.io.framingError.toBoolean,
          s"[$cfgLabel] framingError high at reset"
        )
        assert(
          !dut.io.parityError.toBoolean,
          s"[$cfgLabel] parityError high at reset"
        )
        assert(
          !dut.io.overrun.toBoolean,
          s"[$cfgLabel] overrun high at reset"
        )
        assert(
          !dut.io.busy.toBoolean,
          s"[$cfgLabel] busy high at reset"
        )

        // ------------------------------------------------------------------
        // (2) Byte sweep.
        // ------------------------------------------------------------------
        val mask = (1L << cfg.dataBits) - 1L
        for (p <- patterns) {
          fork {
            driveBits(frameBits(p))
            idleLine()
          }
          val (payload, framing, parity, overrun) = expectPayload()
          assert(
            (payload & mask) == (p.toLong & mask),
            f"[$cfgLabel byte-sweep] expected 0x${p & mask.toInt}%X, got 0x${payload & mask}%X"
          )
          assert(
            !framing,
            f"[$cfgLabel byte-sweep] unexpected framingError on 0x$p%X"
          )
          assert(
            !parity,
            f"[$cfgLabel byte-sweep] unexpected parityError on 0x$p%X"
          )
          assert(
            !overrun,
            f"[$cfgLabel byte-sweep] unexpected overrun on 0x$p%X"
          )
          // Wait for FSM to fully return to idle before the next frame.
          waitFor(bitClocks * 4)(!dut.io.busy.toBoolean)
          dut.clockDomain.waitSampling(bitClocks)
        }

        // ------------------------------------------------------------------
        // (3) Start-bit glitch rejection. Drop the line low for a few
        // system clocks (well under half a bit period), then back high.
        // The FSM should leave idle on the falling edge, do the half-
        // bit verify, see the line high, and return to idle WITHOUT
        // ever raising valid.
        // ------------------------------------------------------------------
        idleLine()
        // Glitch width: just a couple of clocks. Less than oversample/2
        // bit periods — guaranteed rejection.
        dut.io.rx #= false
        dut.clockDomain.waitSampling(2)
        dut.io.rx #= true
        // Wait long enough for the half-bit verify to complete and
        // the FSM to return to idle.
        dut.clockDomain.waitSampling(bitClocks * 2)
        assert(
          !dut.io.payload.valid.toBoolean,
          s"[$cfgLabel] glitch produced spurious valid"
        )
        assert(
          !dut.io.busy.toBoolean,
          s"[$cfgLabel] glitch left FSM busy"
        )
        idleLine()

        // ------------------------------------------------------------------
        // (4) Framing error: stop bit low.
        //
        // CAREFUL: the driver fork keeps writing `io.rx` until it
        // returns. We must `.join()` it before the main thread starts
        // driving `io.rx` again (e.g. via `idleLine`), otherwise both
        // threads race for the wire and SpinalSim's last-writer-wins
        // semantics give visibly nondeterministic line values. This
        // race is what previously broke 8N2 and not 8N1.
        // ------------------------------------------------------------------
        idleLine()
        val framingByte = patterns.head
        val framingDrv = fork {
          driveBits(
            frameBits(
              framingByte,
              stopOverride = Some(Seq.fill(cfg.stopBits)(false))
            )
          )
          idleLine()
        }
        // Don't fire ready — we expect NO valid. Wait for a frame's
        // worth of time and then check the FSM raised framingError
        // on the cycle it returned to idle.
        // We poll for `framingError` going high, then check valid is
        // not raised at that moment.
        var sawFraming = false
        var sawValid = false
        val framingDeadline = bitClocks * 40
        var f = 0
        dut.io.payload.ready #= false
        while (f < framingDeadline && !sawFraming) {
          dut.clockDomain.waitSampling()
          if (dut.io.framingError.toBoolean) sawFraming = true
          if (dut.io.payload.valid.toBoolean) sawValid = true
          f += 1
        }
        assert(
          sawFraming,
          s"[$cfgLabel] framing error: expected framingError pulse"
        )
        assert(
          !sawValid,
          s"[$cfgLabel] framing error: valid raised despite framing failure"
        )
        // MUST join before idleLine — see comment above.
        framingDrv.join()

        // (4b) Recover. Drain idle and try a clean frame to confirm
        // the FSM is healthy after the error.
        idleLine(4)
        fork {
          driveBits(frameBits(patterns.head))
          idleLine()
        }
        val (rec, fE, pE, oE) = expectPayload()
        assert(
          (rec & mask) == (patterns.head.toLong & mask),
          s"[$cfgLabel] recovery after framing error: bad payload"
        )
        assert(
          !fE && !pE && !oE,
          s"[$cfgLabel] recovery after framing error: spurious flags"
        )
        idleLine()

        // ------------------------------------------------------------------
        // (5) Parity error (only when parity is enabled).
        // ------------------------------------------------------------------
        if (cfg.parity != ParityType.None) {
          idleLine()
          val parityByte = patterns.head
          // Compute the correct parity bit then invert it.
          val masked = parityByte & ((1 << cfg.dataBits) - 1)
          val xorAll = (0 until cfg.dataBits)
            .map(i => ((masked >> i) & 1) == 1)
            .foldLeft(false)(_ ^ _)
          val correctParity = cfg.parity match {
            case ParityType.Even => xorAll
            case ParityType.Odd  => !xorAll
            case _               => false
          }
          val badParity = !correctParity

          val parityDrv = fork {
            driveBits(frameBits(parityByte, parityOverride = Some(badParity)))
            idleLine()
          }
          var sawParity = false
          var sawValid2 = false
          var p2 = 0
          val parityDeadline = bitClocks * 40
          dut.io.payload.ready #= false
          while (p2 < parityDeadline && !sawParity) {
            dut.clockDomain.waitSampling()
            if (dut.io.parityError.toBoolean) sawParity = true
            if (dut.io.payload.valid.toBoolean) sawValid2 = true
            p2 += 1
          }
          assert(
            sawParity,
            s"[$cfgLabel] parity error: expected parityError pulse"
          )
          assert(
            !sawValid2,
            s"[$cfgLabel] parity error: valid raised despite parity failure"
          )
          // MUST join before idleLine — driver fork still owns io.rx.
          parityDrv.join()
          idleLine(4)
        }

        // ------------------------------------------------------------------
        // (6) Parity OK — already covered by the byte sweep above
        // when parity is enabled. Skip explicit retest.
        // ------------------------------------------------------------------

        // ------------------------------------------------------------------
        // (7) Back-to-back frames with consumer firing.
        // ------------------------------------------------------------------
        idleLine(2)
        val backToBackBytes =
          if (cfg.dataBits >= 5) Seq(0x12, 0x14, 0x16) else Seq(0x12)
        // Driver thread: send all frames consecutively, only an
        // inter-frame idle of 1 stop bit's worth (no extra slack).
        fork {
          for (b <- backToBackBytes) {
            driveBits(frameBits(b))
          }
          idleLine()
        }
        for (b <- backToBackBytes) {
          val (payload, fE, pE, oE) = expectPayload(maxClocks = bitClocks * 40)
          assert(
            (payload & mask) == (b.toLong & mask),
            f"[$cfgLabel back-to-back] expected 0x$b%X, got 0x${payload & mask}%X"
          )
          assert(
            !fE && !pE && !oE,
            f"[$cfgLabel back-to-back] spurious error flags on 0x$b%X"
          )
        }
        // Wait for FSM to settle.
        waitFor(bitClocks * 4)(!dut.io.busy.toBoolean)

        // ------------------------------------------------------------------
        // (8) Overrun. Send two frames with `ready` held LOW. The
        // first frame should raise `valid` and leave it sticky; the
        // second should raise `overrun`.
        // ------------------------------------------------------------------
        idleLine(2)
        dut.io.payload.ready #= false
        val firstByte = 0x3a & mask.toInt
        val secondByte = 0x47 & mask.toInt
        val overrunDrv = fork {
          driveBits(frameBits(firstByte))
          driveBits(frameBits(secondByte))
          idleLine()
        }
        // Wait for first valid.
        assert(
          waitFor(bitClocks * 30)(dut.io.payload.valid.toBoolean),
          s"[$cfgLabel overrun] first frame did not raise valid"
        )
        // Now wait for overrun pulse (which should occur on the
        // second frame's stop). The valid signal should remain high
        // throughout (sticky until ready fires).
        var sawOverrun = false
        var validDropped = false
        var op = 0
        val overrunDeadline = bitClocks * 30
        while (op < overrunDeadline && !sawOverrun) {
          dut.clockDomain.waitSampling()
          if (dut.io.overrun.toBoolean) sawOverrun = true
          if (!dut.io.payload.valid.toBoolean) validDropped = true
          op += 1
        }
        assert(
          sawOverrun,
          s"[$cfgLabel overrun] expected overrun pulse on the second frame"
        )
        assert(
          !validDropped,
          s"[$cfgLabel overrun] valid dropped without ready firing"
        )
        // Drain: fire ready to consume whatever is on the line, then
        // make sure the FSM returns to idle.
        dut.io.payload.ready #= true
        dut.clockDomain.waitSampling(bitClocks)
        dut.io.payload.ready #= false
        // MUST join before final idleLine — driver fork still owns io.rx.
        overrunDrv.join()
        idleLine(4)

        tickFork.terminate()
        println(s"OK: RxFsm behaves correctly ($cfgLabel)")
      }
  }

  def main(args: Array[String]): Unit = {
    val patterns8 = Seq(0x00, 0xff, 0xaa, 0x55, 0x80, 0x01, 0xad)
    val patterns5 = Seq(0x00, 0x1f, 0x15, 0x0a, 0x10, 0x01, 0x0d)

    // Use a small clkFreqHz / baudRate ratio so sims run fast. We
    // pick values where ticksPerBit = clkFreqHz / baudRate equals
    // oversample * ticksPerOversample = 16 * 2 = 32. Concretely:
    // 32 kHz / 1000 baud → ticksPerBit = 32. The exact baud rate
    // doesn't matter for the FSM (it counts ticks, not clocks), but
    // UartConfig requires `ticksPerBit >= 1`.
    val clk = 32000
    val baud = 1000

    val configs: Seq[(UartConfig, Seq[Int])] = Seq(
      // 8N1 — baseline
      (UartConfig(clk, baud, 8, 1, ParityType.None), patterns8),
      // 8N2 — exercises stop-bit count
      (UartConfig(clk, baud, 8, 2, ParityType.None), patterns8),
      // 8E1 — even parity
      (UartConfig(clk, baud, 8, 1, ParityType.Even), patterns8),
      // 8O1 — odd parity
      (UartConfig(clk, baud, 8, 1, ParityType.Odd), patterns8),
      // 5N1 — narrower data path
      (UartConfig(clk, baud, 5, 1, ParityType.None), patterns5)
    )

    for ((cfg, pats) <- configs) {
      runFsmTest(cfg, pats)
    }
  }
}
