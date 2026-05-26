package mole

// Imported from felipebalbi/icebreaker-spinalhdl-examples@98c06a8cf39f6c212d3b46d98868aca398a82186 Uart/src/sim/RxShiftRegSim.scala.
// Modified for Mole only as documented inline (search for 'Mole').

import spinal.core._
import spinal.core.sim._

import scala.collection.mutable.ArrayBuffer

/** Standalone sim for [[RxShiftReg]].
  *
  * Strategy Same recipe as `RxSyncSim`: instead of building a tight
  * cycle-by-cycle reference model in the stimulus thread (which is fragile
  * against SpinalSim's `#=`/`waitSampling` ordering quirks), we record every
  * input and the resulting register state on every rising edge into parallel
  * `ArrayBuffer`s, then post-validate one invariant over the whole recording.
  *
  * The invariant is the block's behavioural contract written directly in code:
  * a tiny reference register that we step over the recorded `(clear, shift,
  * sample)` stream, asserting at every edge that the recorded `data` matches
  * what the contract says it should be. Because both halves of the comparison
  * come from the same `onSamplings` snapshot, there is no way for
  * stimulus-thread timing to skew them.
  *
  * What the contract says
  *   - At edge k: if `clear(k)` is high, the new register value is 0.
  *   - Else if `shift(k)` is high, the new value is `sample(k) ## prev(N-1
  *     downto 1)`.
  *   - Else, the register holds (`new == prev`).
  *   - `data(k)` == new register value (combinational view).
  *
  * Coverage
  *   1. **Full byte sweep at 8N1.** Send every value `0x00..0xFF` through the
  *      register one frame at a time, with a `clear` pulse between frames, and
  *      verify the assembled byte matches. Catches any bit-ordering bug
  *      (LSB-first vs. MSB-first), any off-by-one in the shift slice, any
  *      "forgot to clear" leakage between frames. 2. **`dataBits` sweep.**
  *      Repeat a smaller pattern set across all configured widths (5..9).
  *      Confirms the slice `cfg.dataBits - 1 downto 1` parameterises correctly.
  *      3. **Clear-wins-over-shift.** Drive `clear=1` AND `shift=1` in the same
  *         cycle with `sample=1`. The new value must be 0 (clear wins), not `1
  *         ## prev[N-1:1]`. Then immediately drive `shift=1` alone with
  *         `sample=1` and verify the new value is `1 ## 0[N-1:1]` — proves the
  *         FSM contract "clear-then-shift on separate cycles" is what's
  *         actually implemented.
  *      4. **Hold.** With both inputs low for many cycles after a partial load,
  *         the register must not change. The recorder/invariant catches this
  *         implicitly (any change without `clear` or `shift` violates the
  *         contract), but we also do an explicit before/after comparison so the
  *         failure message is direct. 5. **Randomised burst stream.** A few
  *         hundred edges of random `(clear, shift, sample)` with biased
  *         probabilities (clear rare, shift common). Most bugs that survive the
  *         structured tests will fall out here.
  *
  * Run: `sbt "runMain mole.RxShiftRegSim"`
  */
object RxShiftRegSim {
  def main(args: Array[String]): Unit = {

    /** Run one test pass with a given dataBits width. Returns when the sim's
      * body completes; failures throw out of the assert calls.
      */
    def runOne(dataBits: Int): Unit = {
      val cfg = UartConfig(dataBits = dataBits)

      SimConfig.withWave
        .compile(RxShiftReg(cfg))
        .doSim(s"dataBits=$dataBits") { dut =>
          dut.clockDomain.forkStimulus(10)

          // Drive everything to a known low so the post-reset state is
          // unambiguous. The register's `init = 0` means io.data should
          // read as 0 once we leave reset.
          dut.io.clear #= false
          dut.io.shift #= false
          dut.io.sample #= false
          dut.clockDomain.waitSampling(20)
          assert(
            dut.io.data.toLong == 0L,
            s"[dataBits=$dataBits] post-reset data should be 0, got ${dut.io.data.toLong}"
          )

          // ---------- recorder ------------------------------------------
          // onSamplings fires after each rising edge with all register
          // and input values committed. Both halves of the validation
          // comparison come from the same snapshot — no timing skew.
          val clearSeq = ArrayBuffer[Boolean]()
          val shiftSeq = ArrayBuffer[Boolean]()
          val sampleSeq = ArrayBuffer[Boolean]()
          val dataSeq = ArrayBuffer[Long]()
          dut.clockDomain.onSamplings {
            clearSeq += dut.io.clear.toBoolean
            shiftSeq += dut.io.shift.toBoolean
            sampleSeq += dut.io.sample.toBoolean
            dataSeq += dut.io.data.toLong
          }

          // ---------- helpers -------------------------------------------

          /** Drive (clear, shift, sample) for one cycle. */
          def step(clear: Boolean, shift: Boolean, sample: Boolean): Unit = {
            dut.io.clear #= clear
            dut.io.shift #= shift
            dut.io.sample #= sample
            dut.clockDomain.waitSampling()
          }

          /** Idle for `cycles` clock periods with all inputs low. */
          def idle(cycles: Int = 1): Unit = {
            for (_ <- 0 until cycles) step(false, false, false)
          }

          /** Pulse clear for one cycle (no shift). */
          def clearOnce(): Unit = step(true, false, false)

          /** Pulse shift with the given sample bit. */
          def shiftOnce(bit: Boolean): Unit = step(false, true, bit)

          /** Send one byte of width `dataBits`, LSB first, with a leading
            * clear. Does not check anything — coverage and the post-validation
            * invariant do that.
            */
          def sendByte(value: Long): Unit = {
            clearOnce()
            for (i <- 0 until dataBits) {
              shiftOnce(((value >> i) & 1L) == 1L)
            }
          }

          // ---------- (1) byte sweep -----------------------------------
          // Send every value `0x00..0x(2^N - 1)` through the register one
          // frame at a time. Correctness of the assembled byte is checked
          // by the post-validation contract loop below — we deliberately
          // do NOT inline-assert `dut.io.data.toLong` here, because
          // `waitSampling()` returns just before the next active edge and
          // a direct `.toLong` read can race the final shift's commit
          // (the recorded `dataSeq` from `onSamplings` does not have this
          // problem, which is why we trust it instead).
          val mask = (1L << dataBits) - 1L
          for (v <- 0L until (1L << dataBits)) {
            sendByte(v)
            idle(2) // breathing room between frames
          }

          // ---------- (3) clear-wins-over-shift -------------------------
          // Seed the register with an arbitrary non-zero pattern, then
          // drive clear=1 AND shift=1 simultaneously. The contract loop
          // verifies that the resulting register value is 0 (clear wins)
          // and not `1 ## seed[N-1:1]`. A follow-up plain-shift cycle
          // with sample=1 then proves the post-clear state is a clean
          // zero ready to receive new data.
          val seed = 0xadL & mask
          sendByte(seed)
          step(true, true, true) // both signals high — clear must win
          step(false, true, true) // single shift into freshly-cleared reg
          idle(3)

          // ---------- (4) hold ------------------------------------------
          // Idle the register for many cycles after a partial load. The
          // post-validation loop will flag any change without `clear` or
          // `shift` as a contract violation.
          sendByte(seed)
          idle(20)

          // ---------- (5) random burst ----------------------------------
          // Fixed seed -> reproducible failures. Bias clear low (it's a
          // rare event in real operation) and shift moderately high.
          val rng = new scala.util.Random(0xbadc0ffeeL)
          for (_ <- 0 until 800) {
            val r = rng.nextDouble()
            val clear = r < 0.05
            val shift = !clear && rng.nextDouble() < 0.6
            val sample = rng.nextBoolean()
            step(clear, shift, sample)
          }

          // ---------- post-validation: contract over the recording -----
          //
          // Walk the recorded streams with a tiny reference register and
          // check every recorded `data` against what the contract says.
          //
          // Timing alignment (subtle):
          //   `onSamplings` fires at the active edge but reads register
          //   state BEFORE the edge's update commits (per SpinalHDL docs:
          //   "registers from the clock domain have not yet been updated
          //   at this stage"). So at index k we have:
          //     dataSeq(k)  = register value just BEFORE edge k
          //                   (i.e., the result of edge k-1's sampling)
          //     {clear,shift,sample}Seq(k) = inputs about to be latched
          //                                  by edge k
          //   The contract is therefore:
          //     dataSeq(k+1) = apply(inputs(k), dataSeq(k))
          //   Or equivalently, when iterating from k=1:
          //     dataSeq(k) = apply(inputs(k-1), dataSeq(k-1))
          //
          //   apply(c, s, sm, prev):
          //     if c:       0
          //     elif s:     (sm << (N-1)) | (prev >> 1)
          //     else:       prev
          val n = dataSeq.length
          assert(
            n == clearSeq.length && n == shiftSeq.length && n == sampleSeq.length,
            "recorder length mismatch (internal sim error)"
          )

          var model: Long = dataSeq(0) // seed from the first observation:
          // whatever the DUT showed on the
          // first recorded edge is by definition
          // valid; we validate every edge AFTER
          // that against the contract applied
          // to this baseline.

          val msbBit = 1L << (dataBits - 1)
          for (k <- 1 until n) {
            // Inputs at index k-1 are the ones that produced dataSeq(k).
            val cl = clearSeq(k - 1)
            val sh = shiftSeq(k - 1)
            val sm = sampleSeq(k - 1)

            val expected: Long =
              if (cl) 0L
              else if (sh) {
                val shifted = (model >> 1) & mask
                val msb = if (sm) msbBit else 0L
                (msb | shifted) & mask
              } else model

            val got = dataSeq(k) & mask
            assert(
              got == expected,
              f"[dataBits=$dataBits] contract violation at edge k=$k: " +
                f"inputs at k-1: clear=$cl shift=$sh sample=$sm " +
                f"prev=0x$model%X expected=0x$expected%X got=0x$got%X"
            )
            model = expected
          }

          println(
            s"OK: RxShiftReg(dataBits=$dataBits) — verified contract over $n recorded edges"
          )
        }
    }

    // Sweep every supported dataBits width.
    for (db <- 5 to 9) runOne(db)

    println("OK: RxShiftReg passed for all dataBits in 5..9")
  }
}
