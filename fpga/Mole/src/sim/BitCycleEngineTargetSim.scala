package mole

import spinal.core._
import spinal.core.sim._
import spinal.lib._

/** Target-role coverage for the v0.2 bit-cycle engine.
  *
  * The v0 stash under this name covered six target-role cases including DAA
  * arbitration on `BitCycleEngineTwoTargetDut`. Phase C.8 reduces the scope to
  * two cases tightly bound to what landed in C.7/C.8:
  *
  * ==Cases==
  *
  *   1. `markRecordFormatAtRingBase` --- emit one MARK with a known label,
  *      followed by a STRETCH_SCL to advance the quarter-bit timestamp, then a
  *      second MARK with a different label, then HALT. Read the ring and assert
  *      both records honour the §11 layout (header tag 0b10, label in [13:0],
  *      ts_lo / ts_hi each in [15:0] with [31:16] zero) and that the second
  *      MARK's reconstructed 32-bit timestamp is strictly greater than the
  *      first --- the §5.16 monotonicity invariant.
  *   2. `sampleBitOnScl_mismatchObservable` --- target role, expect+mask=1. Two
  *      sub-cases share one engine compile: drive SDA to the expected value
  *      under an external SCL pulse (no mismatch); then drive SDA to the
  *      opposite value (mismatch). Each sub-case ends with HALT 0; the
  *      `mismatch` bit in the HALT word ([28]) is the canonical observable.
  *
  * Verification rationale (SAMPLE_BIT_ON_SCL): the captured bit lands in R7 but
  * the smoke DUT does not expose a register-file debug read, and v0.2 SAMPLE
  * does not emit a ring CAPTURE record either. The expect/mask path is exactly
  * the observable SAMPLE was designed to feed (spec §5.6) --- a matching sample
  * clears `MISMATCH_FLAG`, a non-matching sample sets it, and the HALT word
  * snapshots the flag at halt entry.
  *
  * Run: `sbt "runMain mole.BitCycleEngineTargetSim"`
  */
object BitCycleEngineTargetSim extends App {

  private val cfg = MoleConfig(
    fabricFreqHz = 48 MHz,
    quarterPeriodCyclesReset = 6,
    programWordCount = 64,
    resultRingByteCount = 64,
    captureMaxBits = 65536,
    uartBaud = 1_000_000,
    role = EngineRole.Target
  )

  private val resultBase: Int = cfg.programWordCount
  private val resultWordCount: Int = (cfg.resultRingByteCount + 3) / 4
  private val resultLimit: Int = resultBase + resultWordCount - 1

  private lazy val compiled =
    SimConfig.withWave.compile(BitCycleEngineTargetDut(cfg))

  // ------------------------------------------------------------------
  // Sim plumbing (matches the C.7 sim template).
  // ------------------------------------------------------------------

  private def quiet(dut: BitCycleEngineTargetDut): Unit = {
    dut.io.engineStart #= false
    dut.io.programLength #= 0
    dut.io.loaderWrite.valid #= false
    dut.io.loaderWrite.payload.addr #= 0
    dut.io.loaderWrite.payload.data #= 0
    dut.io.debugReadCmd.valid #= false
    dut.io.debugReadCmd.payload #= 0
    dut.io.sda.read #= true
    dut.io.scl.read #= true
  }

  private def loaderWrite(
      dut: BitCycleEngineTargetDut,
      addr: Int,
      word: Int
  ): Unit = {
    dut.io.loaderWrite.valid #= true
    dut.io.loaderWrite.payload.addr #= addr
    dut.io.loaderWrite.payload.data #= word
    dut.clockDomain.waitSamplingWhere(dut.io.loaderWrite.ready.toBoolean)
    dut.io.loaderWrite.valid #= false
  }

  private def load(dut: BitCycleEngineTargetDut, program: Seq[Int]): Unit = {
    for ((word, idx) <- program.zipWithIndex) loaderWrite(dut, idx, word)
    dut.clockDomain.waitSampling(2)
  }

  private def debugRead(dut: BitCycleEngineTargetDut, addr: Int): Int = {
    dut.io.debugReadCmd.valid #= true
    dut.io.debugReadCmd.payload #= addr
    dut.clockDomain.waitSamplingWhere(dut.io.debugReadCmd.ready.toBoolean)
    dut.io.debugReadCmd.valid #= false
    dut.clockDomain.waitSamplingWhere(dut.io.debugReadResp.valid.toBoolean)
    dut.io.debugReadResp.payload.toInt
  }

  private case class HaltWord(
      overflow: Boolean,
      mismatch: Boolean,
      status: Int
  )

  private def readHalt(dut: BitCycleEngineTargetDut): HaltWord = {
    val halt = debugRead(dut, resultLimit)
    require(
      ((halt >>> 30) & 0x3) == 0x3,
      f"word at resultLimit=$resultLimit is not a HALT tag (=0x$halt%08x)"
    )
    HaltWord(
      overflow = ((halt >> 29) & 1) != 0,
      mismatch = ((halt >> 28) & 1) != 0,
      status = (halt >> 23) & 0x1f
    )
  }

  private def waitHalted(
      dut: BitCycleEngineTargetDut,
      safetyLimit: Int
  ): Int = {
    var c = 0
    while (!dut.io.halted.toBoolean && c < safetyLimit) {
      dut.clockDomain.waitSampling()
      c += 1
    }
    c
  }

  import Instruction._

  // ------------------------------------------------------------------
  // Case 1: MARK record format + timestamp monotonicity.
  // ------------------------------------------------------------------

  private def runMarkRecordFormatAtRingBase(): Unit = {
    val label = "markRecordFormatAtRingBase"
    println(s"--- BitCycleEngineTargetSim: $label ---")
    compiled.doSim(label) { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.clockDomain.waitSampling(5)

      val label1 = 0x123
      val label2 = 0x2a5
      // SET_BUS_MODE i2c so STRETCH_SCL has a defined timing register;
      // STRETCH_SCL between the two MARKs advances quarterTimestampReg
      // past the first MARK's latched value so the monotonicity check is
      // meaningful (back-to-back MARKs in the same quarter-tick window
      // would otherwise record identical timestamps).
      val program = Seq(
        encode(SetBusMode(BusMode.i2c)),
        encode(Mark(label = label1)),
        encode(StretchSclImm(nQuarters = 4)),
        encode(Mark(label = label2)),
        encode(Halt(0))
      )
      load(dut, program)

      dut.io.programLength #= program.length
      dut.io.engineStart #= true

      val cycles = waitHalted(dut, safetyLimit = 4000)
      dut.io.engineStart #= false
      assert(
        dut.io.halted.toBoolean,
        s"$label: engine never halted (ran $cycles cycles)"
      )

      val h = readHalt(dut)
      assert(
        h.status == 0,
        f"$label: HALT status 0x${h.status}%02x != 0x00"
      )

      // Two MARK records, each 3 words, written consecutively from
      // ringWrPtr=0. So:
      //   resultBase+0 = mark1 header
      //   resultBase+1 = mark1 ts_lo
      //   resultBase+2 = mark1 ts_hi
      //   resultBase+3 = mark2 header
      //   resultBase+4 = mark2 ts_lo
      //   resultBase+5 = mark2 ts_hi
      val w = (0 until 6).map(off => debugRead(dut, resultBase + off))

      def checkMark(idx: Int, expectLabel: Int): Long = {
        val header = w(idx * 3)
        val tsLo = w(idx * 3 + 1)
        val tsHi = w(idx * 3 + 2)

        val tag = (header >>> 30) & 0x3
        val gotLabel = header & 0x3fff
        val headerMid = (header >>> 14) & 0xffff
        assert(
          tag == 0x2,
          f"$label: MARK#$idx header tag 0x$tag%x != 0b10"
        )
        assert(
          gotLabel == expectLabel,
          f"$label: MARK#$idx label 0x$gotLabel%x != expected 0x$expectLabel%x"
        )
        assert(
          headerMid == 0,
          f"$label: MARK#$idx header reserved bits [29:14] = 0x$headerMid%x"
        )
        assert(
          ((tsLo >>> 16) & 0xffff) == 0,
          f"$label: MARK#$idx ts_lo upper 16 bits non-zero (=0x$tsLo%08x)"
        )
        assert(
          ((tsHi >>> 16) & 0xffff) == 0,
          f"$label: MARK#$idx ts_hi upper 16 bits non-zero (=0x$tsHi%08x)"
        )
        val ts =
          ((tsHi.toLong & 0xffffL) << 16) | (tsLo.toLong & 0xffffL)
        ts
      }

      val ts1 = checkMark(idx = 0, expectLabel = label1)
      val ts2 = checkMark(idx = 1, expectLabel = label2)
      assert(
        ts2 > ts1,
        s"$label: timestamps not monotonic: ts1=$ts1, ts2=$ts2"
      )

      println(
        s"$label OK ($cycles cycles, ts1=$ts1, ts2=$ts2, delta=${ts2 - ts1})"
      )
    }
  }

  // ------------------------------------------------------------------
  // Case 2: SAMPLE_BIT_ON_SCL mismatch-bit observable.
  // ------------------------------------------------------------------

  /** Drive a single external SCL rising edge from the testbench. The engine
    * (target role) samples SDA on this edge per spec §5.6. SDA is held at
    * `sdaValue` for the duration of the pulse.
    */
  private def pulseExternalSclRising(
      dut: BitCycleEngineTargetDut,
      sdaValue: Boolean,
      preEdgeCycles: Int = 30,
      holdHighCycles: Int = 20
  ): Unit = {
    // Phase 1: SCL low while engine fetches SET_BUS_MODE + SET_ROLE +
    // SAMPLE_BIT_ON_SCL and parks waiting on the rising edge. SDA held
    // stable at the value we want sampled.
    dut.io.sda.read #= sdaValue
    dut.io.scl.read #= false
    dut.clockDomain.waitSampling(preEdgeCycles)
    // Phase 2: rising edge. Engine samples here.
    dut.io.scl.read #= true
    dut.clockDomain.waitSampling(holdHighCycles)
  }

  private def runSampleBitOnSclMatchClearsMismatch(): Unit = {
    val label = "sampleBitOnScl_match_noMismatch"
    println(s"--- BitCycleEngineTargetSim: $label ---")
    compiled.doSim(label) { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.clockDomain.waitSampling(5)

      // Expect SDA to be sampled high (recessive); drive SDA high.
      // EmitBitImm at start primes a deliberate mismatch so we can also
      // verify SAMPLE clears the flag on a match; under target role
      // EMIT_BIT_IMM with mask=1 expect=0 against a high external SDA
      // sets MISMATCH_FLAG (engine's WIRE FSM observes sdaSampled in
      // target role too --- see EnginePipeline §5.1 sample path).
      //
      // Actually: under target role EMIT_BIT_IMM doesn't generate SCL
      // and the mismatch update only fires at the sample point, which
      // may not advance without an external SCL --- safer to set the
      // mismatch via a SAMPLE_BIT_ON_SCL with an inverted expect first,
      // pulsed against a known SDA, then clear it via a second sample
      // with the correct expect. Two sample slots, one program.
      //
      // expect = sampled-value would give no mismatch; we use:
      //   sample #1: expect=0 (low), SDA driven high → MISMATCH set
      //   sample #2: expect=1 (high), SDA driven high → MISMATCH cleared
      //                                                  (write-once
      //                                                  overwrite per
      //                                                  AGENTS §3.15)
      val program = Seq(
        encode(SetBusMode(BusMode.i2c)),
        encode(SampleBitOnScl(expect = false, mask = true, capture = false)),
        encode(SampleBitOnScl(expect = true, mask = true, capture = false)),
        encode(Halt(0))
      )
      load(dut, program)

      dut.io.programLength #= program.length
      dut.io.engineStart #= true

      // Fork a stimulus thread that drives two SCL rising edges past
      // the engine, one for each SAMPLE. SDA stays high throughout.
      fork {
        pulseExternalSclRising(dut, sdaValue = true)
        pulseExternalSclRising(dut, sdaValue = true)
      }

      val cycles = waitHalted(dut, safetyLimit = 5000)
      dut.io.engineStart #= false
      assert(
        dut.io.halted.toBoolean,
        s"$label: engine never halted (ran $cycles cycles)"
      )

      val h = readHalt(dut)
      assert(
        h.status == 0,
        f"$label: HALT status 0x${h.status}%02x != 0x00"
      )
      assert(
        !h.mismatch,
        s"$label: HALT mismatch bit set --- second SAMPLE failed to " +
          "overwrite the first sample's intentional mismatch"
      )
      println(s"$label OK ($cycles cycles, mismatch=false as expected)")
    }
  }

  private def runSampleBitOnSclMismatchSetsMismatch(): Unit = {
    val label = "sampleBitOnScl_mismatch_setsMismatch"
    println(s"--- BitCycleEngineTargetSim: $label ---")
    compiled.doSim(label) { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.clockDomain.waitSampling(5)

      // Single SAMPLE with expect=1 (recessive) but SDA driven low →
      // MISMATCH_FLAG sets and survives to HALT.
      val program = Seq(
        encode(SetBusMode(BusMode.i2c)),
        encode(SampleBitOnScl(expect = true, mask = true, capture = false)),
        encode(Halt(0))
      )
      load(dut, program)

      dut.io.programLength #= program.length
      dut.io.engineStart #= true

      fork {
        pulseExternalSclRising(dut, sdaValue = false)
      }

      val cycles = waitHalted(dut, safetyLimit = 5000)
      dut.io.engineStart #= false
      assert(
        dut.io.halted.toBoolean,
        s"$label: engine never halted (ran $cycles cycles)"
      )

      val h = readHalt(dut)
      assert(
        h.status == 0,
        f"$label: HALT status 0x${h.status}%02x != 0x00 (sample wedged?)"
      )
      assert(
        h.mismatch,
        s"$label: HALT mismatch bit clear --- expected SAMPLE to set " +
          "MISMATCH_FLAG on expect=1 vs sampled-low SDA"
      )
      println(s"$label OK ($cycles cycles, mismatch=true as expected)")
    }
  }

  runMarkRecordFormatAtRingBase()
  runSampleBitOnSclMatchClearsMismatch()
  runSampleBitOnSclMismatchSetsMismatch()
  println("BitCycleEngineTargetSim: all 3 cases passed")
}
