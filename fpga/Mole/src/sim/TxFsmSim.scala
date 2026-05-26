package mole

// Imported from felipebalbi/icebreaker-spinalhdl-examples@98c06a8cf39f6c212d3b46d98868aca398a82186 Uart/src/sim/TxFsmSim.scala.
// Modified for Mole only as documented inline (search for 'Mole').

import spinal.core._
import spinal.core.sim._

/** Standalone sim for [[TxFsm]].
  *
  * The DUT here is *just* the FSM, not the wider UartTx. To stand it up we need
  * a model of its two combinational neighbours:
  *
  *   - The [[BaudGenerator]]: a sim-side thread that pulses `io.tick` for one
  *     cycle every `ticksPerBit` cycles, but **only while the FSM's `busy` is
  *     high** — exactly the contract the real wrapper enforces via
  *     `baud.io.enable := fsm.io.busy`. The phase resets between frames.
  *   - The [[TxShiftReg]]: a sim-side mutable byte that: * captures `nextByte`
  *     on every cycle the FSM pulses `io.loadReg` high, * shifts right on every
  *     cycle the FSM pulses `io.shiftReg` high (load wins over shift, matching
  *     the real block), * exposes its LSB on `io.shiftRegBit` continuously.
  *
  * What we verify
  *   1. **Bit-pattern correctness.** For each of several test bytes (0x00,
  *      0xFF, 0xAA, 0x55, 0x80, 0x01, 0xAD), drive a frame and sample
  *      `io.txBit` at the *middle* of each bit period. The reconstructed
  *      sequence must match `[start=0, d0..d7, stop=1]`. Mid-bit sampling is
  *      exactly how a real UART RX recovers data, so this is the canonical
  *      correctness test. The mix of patterns catches: init-bleed bugs (0x00,
  *      0xFF), LSB/MSB swaps (0x80, 0x01), off-by-one shifts (0xAA, 0x55), and
  *      a generic mix (0xAD). 2. **`loadReg` pulse count.** Exactly one
  *      `loadReg` pulse per frame, fired the cycle the FSM accepts. 3.
  *      **`shiftReg` pulse count.** Exactly `dataBits - 1` `shiftReg` pulses
  *      per frame — the FSM suppresses the dangling shift on the final data
  *      tick (see TxFsm.scala for why). 4. **Back-to-back frames.** Holding
  *      "start" high across multiple frames must work: the FSM should consume
  *      `start` once per Idle visit and immediately launch the next frame. This
  *      is the test that would catch a regression to the old `io.start.rise()`
  *      behaviour. 5. **Stop-bit width.** With `cfg.stopBits = 1`, the line
  *      must be high for `ticksPerBit` cycles after the last data bit (modulo
  *      the 1-cycle txReg pipeline at each boundary, which mid-bit sampling
  *      tolerates by construction). 6. **Two stop bits.** A second elaboration
  *      with `cfg.stopBits = 2` runs one frame and confirms two stop-bit
  *      periods of high line. 7. **Parity (when enabled).** The expected-frame
  *      helper inserts the parity bit between the data bits and the stop
  *      bit(s), computed as `xor(data)` for Even and `xor(data) ^ 1` for Odd.
  *      The mid-bit sampler walks through it the same way it walks any other
  *      bit.
  *
  * Parity is implemented in [[TxFsm]] as an FSM-local accumulator (see the
  * "Parity" section in TxFsm's top-of-file comment), so all parity-enabled
  * configs in `main` (8E1/8E2/8O1/8O2/5E1/5E2/5O1/5O2) are expected to pass.
  *
  * Run: `sbt "runMain mole.TxFsmSim"`
  */
object TxFsmSim {

  /** Test plan executed against a freshly-elaborated [[TxFsm]] DUT. Pulled out
    * of `main` so we can run it for two different configs (1 stop bit and 2
    * stop bits) without duplicating code.
    *
    * @param patterns
    *   bytes to transmit and verify in test (1)
    * @param backToBackBytes
    *   bytes to transmit in the back-to-back test (4)
    */
  def runFsmTest(
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
      s"${cfg.dataBits}${parityLabel}${cfg.stopBits}"

    SimConfig.withWave
      .compile(TxFsm(cfg))
      .doSim { dut =>
        dut.clockDomain.forkStimulus(10)

        // ------------------------------------------------------------------
        // Sim-side fake TxShiftReg. Updated on every clock edge based on
        // the FSM's combinational pulse outputs sampled in the cycle
        // that just ended. `nextByte` is what the next `loadReg` will
        // capture; the test sets it before pulsing `start`.
        // ------------------------------------------------------------------
        val mask = (1 << cfg.dataBits) - 1
        var sreg: Int = 0xff
        var nextByte: Int = 0
        def expose(): Unit = dut.io.shiftRegBit #= ((sreg & 1) == 1)
        expose()

        fork {
          while (true) {
            dut.clockDomain.waitSampling()
            // Sampled just after the rising edge — values reflect the
            // cycle that just completed. Match real-RTL register
            // semantics: load wins over shift.
            val didLoad = dut.io.loadReg.toBoolean
            val didShift = dut.io.shiftReg.toBoolean
            if (didLoad) {
              sreg = nextByte & mask
            } else if (didShift) {
              sreg = (sreg >> 1) & mask
            }
            expose()
          }
        }

        // ------------------------------------------------------------------
        // Sim-side fake BaudGenerator. Pulses `io.tick` for one cycle
        // every `ticksPerBit` cycles, but only while `io.busy` is high
        // — same contract as `baud.io.enable := busy` in the real
        // wrapper. Phase resets each time `busy` rises (matching a DDS
        // baud generator that's gated by enable from acc=0).
        // ------------------------------------------------------------------
        fork {
          dut.io.tick #= false
          // Let reset settle BEFORE the first busy poll. At sim time
          // 0 the busy register is uninitialized and `toBoolean` may
          // return True; without this wait, the loop below would
          // start a phantom burst aligned to nothing real and steal
          // ticks from the first frame. Match the main thread's
          // init wait.
          dut.clockDomain.waitSampling(20)

          // Pulse `1 + dataBits + parityBits + stopBits` ticks per
          // frame. We compute it up front rather than relying on
          // busy-level polling, because `signal.toBoolean` after
          // `waitSampling()` returns the value of the cycle that just
          // *completed* (pre-next-edge), so a level-based `while
          // (busy)` poll catches a stale "True" the cycle after the
          // FSM has already returned to idle. Same reason the
          // initial poll at sim time 0 used to fire immediately
          // (busy register uninitialized before reset settles).
          val parityBitsTick = if (cfg.parity == ParityType.None) 0 else 1
          val ticksPerFrame = 1 + cfg.dataBits + parityBitsTick + cfg.stopBits
          while (true) {
            // Re-arm: wait until we *actually observe* busy=False
            // before looking for the next rising edge. This drains
            // any stale True we'd otherwise read in the cycle after
            // the FSM left the previous frame (or before reset
            // propagated, on the very first iteration).
            while (dut.io.busy.toBoolean) dut.clockDomain.waitSampling()
            // Now wait for the next frame to start.
            while (!dut.io.busy.toBoolean) dut.clockDomain.waitSampling()

            // Pulse exactly `ticksPerFrame` ticks at ticksPerBit
            // spacing. First tick lands ticksPerBit cycles after
            // busy rose.
            for (_ <- 0 until ticksPerFrame) {
              for (_ <- 0 until ticksPerBit - 1) dut.clockDomain.waitSampling()
              dut.io.tick #= true
              dut.clockDomain.waitSampling()
              dut.io.tick #= false
            }
          }
        }

        // ------------------------------------------------------------------
        // Helpers
        // ------------------------------------------------------------------

        /** Wait for the FSM to accept a `start` request (busy goes high). Drops
          * `start` once accepted so we don't accidentally launch a second
          * frame.
          */
        def launchFrame(byte: Int): Unit = {
          nextByte = byte
          dut.io.start #= true
          while (!dut.io.busy.toBoolean) dut.clockDomain.waitSampling()
          dut.io.start #= false
        }

        /** Sample `io.txBit` at the middle of each of the `1 + dataBits +
          * parityBits + stopBits` bit periods of a frame. Caller must have just
          * launched a frame; this routine returns once sampling is done but
          * does NOT wait for `busy` to drop.
          */
        def sampleFrameMidBit(): Seq[Boolean] = {
          // We're somewhere in the first cycle or two of startState.
          // Walk to the middle of the start bit. The startState span
          // is ticksPerBit cycles starting from the cycle after `busy`
          // rose; we entered this routine some 1-2 cycles into that
          // span (whatever it took for the polling loop to notice
          // busy). Approximate: wait `ticksPerBit / 2` cycles. The
          // mid-bit window is wide (~ticksPerBit/2 cycles either
          // side), so a couple of cycles of slack don't matter.
          dut.clockDomain.waitSampling(ticksPerBit / 2)
          val parityBits = if (cfg.parity == ParityType.None) 0 else 1
          val nBits = 1 + cfg.dataBits + parityBits + cfg.stopBits
          val out = scala.collection.mutable.ArrayBuffer[Boolean]()
          for (i <- 0 until nBits) {
            out += dut.io.txBit.toBoolean
            if (i < nBits - 1) dut.clockDomain.waitSampling(ticksPerBit)
          }
          out.toSeq
        }

        /** Build the expected mid-bit sequence for `byte`: `[0, d0, d1, ...,
          * d{N-1}, parity?, 1, 1, ...]`.
          *
          * Parity bit is `xor(data bits)` for Even and `~xor(data bits)` for
          * Odd, so that the total count of 1s in (data + parity) is even or odd
          * respectively.
          */
        def expectedFrame(byte: Int): Seq[Boolean] = {
          val mask = (1 << cfg.dataBits) - 1
          val masked = byte & mask
          val dataSeq =
            (0 until cfg.dataBits).map(i => ((masked >> i) & 1) == 1)
          val xorAll = dataSeq.foldLeft(false)(_ ^ _)
          val paritySeq: Seq[Boolean] = cfg.parity match {
            case ParityType.None => Seq.empty
            case ParityType.Even => Seq(xorAll)
            case ParityType.Odd  => Seq(!xorAll)
          }
          Seq(false) ++ dataSeq ++ paritySeq ++ Seq.fill(cfg.stopBits)(true)
        }

        def fmt(bs: Seq[Boolean]): String =
          bs.map(b => if (b) '1' else '0').mkString

        /** Render a frame string with `|` separators between fields so the
          * parity-bit slot pops out visually in failure messages: e.g.
          * `0|10110100|1|1` for an 8E1 frame transmitting 0xAD.
          */
        def fmtFramed(bs: Seq[Boolean]): String = {
          val parityBits = if (cfg.parity == ParityType.None) 0 else 1
          val expected = 1 + cfg.dataBits + parityBits + cfg.stopBits
          if (bs.size != expected) return fmt(bs)
          val sb = new StringBuilder
          var idx = 0
          sb.append(if (bs(idx)) '1' else '0'); idx += 1 // start
          sb.append('|')
          for (_ <- 0 until cfg.dataBits) {
            sb.append(if (bs(idx)) '1' else '0'); idx += 1
          }
          if (parityBits == 1) {
            sb.append('|')
            sb.append(if (bs(idx)) '1' else '0'); idx += 1
          }
          sb.append('|')
          for (_ <- 0 until cfg.stopBits) {
            sb.append(if (bs(idx)) '1' else '0'); idx += 1
          }
          sb.toString
        }

        /** End-to-end: launch + sample + verify + drain. */
        def expectFrame(byte: Int, label: String = ""): Unit = {
          launchFrame(byte)
          val got = sampleFrameMidBit()
          val exp = expectedFrame(byte)
          assert(
            got == exp,
            f"[$label] frame for 0x$byte%02X (${cfgLabel}): " +
              f"got ${fmtFramed(got)} expected ${fmtFramed(exp)}"
          )
          // Drain: wait for busy to drop so the next frame starts clean.
          while (dut.io.busy.toBoolean) dut.clockDomain.waitSampling()
        }

        // ------------------------------------------------------------------
        // Init
        // ------------------------------------------------------------------
        dut.io.start #= false
        dut.io.tick #= false
        dut.clockDomain.waitSampling(20)

        assert(dut.io.txBit.toBoolean, "TX should idle high after reset")
        assert(!dut.io.busy.toBoolean, "busy should be low at startup")

        // ------------------------------------------------------------------
        // (1) Per-pattern bit-correctness sweep.
        // ------------------------------------------------------------------
        for (p <- patterns) {
          expectFrame(p, label = "pattern")
          // Small inter-frame gap — let the tick thread re-arm.
          dut.clockDomain.waitSampling(5)
        }

        // ------------------------------------------------------------------
        // (2) loadReg pulse count: exactly one per frame.
        // (3) shiftReg pulse count: exactly dataBits - 1 per frame.
        //
        // We count pulses by polling each cycle for one frame's worth
        // of activity. Use a sentinel byte so observed pulses can't
        // be confused with anything else.
        // ------------------------------------------------------------------
        var loadPulses = 0
        var shiftPulses = 0
        val counter = fork {
          while (true) {
            dut.clockDomain.waitSampling()
            if (dut.io.loadReg.toBoolean) loadPulses += 1
            if (dut.io.shiftReg.toBoolean) shiftPulses += 1
          }
        }
        loadPulses = 0
        shiftPulses = 0
        expectFrame(0x5a, label = "pulse-count")
        // Allow one extra cycle for the counting thread to observe the
        // tail of the frame.
        dut.clockDomain.waitSampling(2)
        counter.terminate()
        assert(
          loadPulses == 1,
          s"loadReg pulses: expected 1, got $loadPulses"
        )
        val expectedShifts = cfg.dataBits - 1
        assert(
          shiftPulses == expectedShifts,
          s"shiftReg pulses: expected $expectedShifts, got $shiftPulses"
        )

        // ------------------------------------------------------------------
        // (4) Back-to-back frames with `start` held high. This is the
        // test that catches the rise-edge regression.
        // ------------------------------------------------------------------
        for (b <- backToBackBytes) {
          // Set up the next byte BEFORE pulsing start so the load
          // captures it.
          nextByte = b
          dut.io.start #= true
          while (!dut.io.busy.toBoolean) dut.clockDomain.waitSampling()
          // Don't drop start — leave it high so the loop above tests
          // the held-high case across the next frame.
          val got = sampleFrameMidBit()
          val exp = expectedFrame(b)
          assert(
            got == exp,
            f"[back-to-back $cfgLabel] frame for 0x$b%02X: " +
              f"got ${fmtFramed(got)} expected ${fmtFramed(exp)}"
          )
          while (dut.io.busy.toBoolean) dut.clockDomain.waitSampling()
        }
        dut.io.start #= false

        // ------------------------------------------------------------------
        // (5)/(6) Stop-bit width sanity. Send 0x00 (so all data bits
        // are low and the high "block" at the end is unambiguously the
        // stop period), then assert the line is high for the bulk of
        // `ticksPerBit * cfg.stopBits` cycles. We trim 2 cycles either
        // side of the window to tolerate the 1-cycle txReg pipeline at
        // both edges (a real RX would never sample those boundary
        // cycles either).
        //
        // NOTE: we deliberately skip this test when parity is enabled.
        // For Even parity the parity bit of 0x00 is 0 (so it merges
        // with the data bits and the stop-bit start landmark is still
        // unambiguous), but for Odd parity it's 1 — the parity bit
        // sits between the data zeros and the stop ones, so the
        // "stop window" walk would land on the parity bit and pass
        // for the wrong reason. Stop-bit width is already covered
        // implicitly by the mid-bit sampler for parity configs.
        // ------------------------------------------------------------------
        if (cfg.parity == ParityType.None) {
          nextByte = 0x00
          dut.io.start #= true
          while (!dut.io.busy.toBoolean) dut.clockDomain.waitSampling()
          dut.io.start #= false
          // Skip start bit + dataBits data bits to land in stop.
          dut.clockDomain.waitSampling(ticksPerBit * (1 + cfg.dataBits) + 2)
          val stopWindow = ticksPerBit * cfg.stopBits - 4
          for (_ <- 0 until stopWindow) {
            assert(
              dut.io.txBit.toBoolean,
              s"stop-bit window ($cfgLabel): expected high"
            )
            dut.clockDomain.waitSampling()
          }
          while (dut.io.busy.toBoolean) dut.clockDomain.waitSampling()
        }

        println(
          s"OK: TxFsm behaves correctly ($cfgLabel)"
        )
      }
  }

  def main(args: Array[String]): Unit = {
    val patterns8 = Seq(0x00, 0xff, 0xaa, 0x55, 0x80, 0x01, 0xad)
    val backToBackBytes8 = Seq(0x12, 0x34, 0x56, 0x78)

    // 5-bit equivalents — must fit in 5 bits, so each pattern is
    // masked (or chosen) to stay <= 0x1F. Same coverage intent:
    //   0x00, 0x1F : init-bleed
    //   0x10, 0x01 : LSB / MSB extremes
    //   0x15, 0x0a : alternating
    //   0x0d       : generic mix
    val patterns5 = Seq(0x00, 0x1f, 0x15, 0x0a, 0x10, 0x01, 0x0d)
    val backToBackBytes5 = Seq(0x12, 0x14, 0x16, 0x18)

    // Small clk/baud ratio for fast sim -- ticksPerBit = 20.
    // Mole: bumped clk from 1_000_000 to 2_000_000 so the
    // configurations satisfy Mole's added require
    // `baudRate * oversample < clkFreqHz` in UartConfig.
    val clk = 2000000
    val baud = 100000

    // Sweep matrix the user asked for. Listed roughly easiest-first so
    // failures in the simpler configs surface before the harder ones:
    //   - 8N* exercise the FSM core
    //   - 8E*/8O* exercise the parity accumulator
    //   - 5E*/5O* exercise both parity AND the dataBits axis
    val configs: Seq[(UartConfig, Seq[Int], Seq[Int])] = Seq(
      // 8N1, 8N2 — baseline (no parity, exercises stop-bit count)
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
      // 8E1, 8E2 — even parity
      (
        UartConfig(clk, baud, 8, 1, ParityType.Even),
        patterns8,
        backToBackBytes8
      ),
      (
        UartConfig(clk, baud, 8, 2, ParityType.Even),
        patterns8,
        backToBackBytes8
      ),
      // 8O1, 8O2 — odd parity
      (
        UartConfig(clk, baud, 8, 1, ParityType.Odd),
        patterns8,
        backToBackBytes8
      ),
      (
        UartConfig(clk, baud, 8, 2, ParityType.Odd),
        patterns8,
        backToBackBytes8
      ),
      // 5E1, 5E2, 5O1, 5O2 — same parity matrix at 5 data bits
      (
        UartConfig(clk, baud, 5, 1, ParityType.Even),
        patterns5,
        backToBackBytes5
      ),
      (
        UartConfig(clk, baud, 5, 2, ParityType.Even),
        patterns5,
        backToBackBytes5
      ),
      (
        UartConfig(clk, baud, 5, 1, ParityType.Odd),
        patterns5,
        backToBackBytes5
      ),
      (
        UartConfig(clk, baud, 5, 2, ParityType.Odd),
        patterns5,
        backToBackBytes5
      )
    )

    for ((cfg, pats, b2b) <- configs) {
      runFsmTest(cfg, pats, b2b)
    }
  }
}
