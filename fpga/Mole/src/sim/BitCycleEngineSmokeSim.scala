package mole

import spinal.core._
import spinal.core.sim._
import spinal.lib._

/** Sim DUT for [[BitCycleEngineSmokeSim]] --- wraps a [[BitCycleEngineCore]]
  * with a [[SpramController]] (the `Mem`-backed sim path) and exposes the
  * loader-write port plus `start` / `done` directly to the test bench.
  *
  * The wrapper is the smallest thing that lets a test load a hand- encoded
  * program over the loader port, raise `start`, and observe the engine's bus
  * drivers per fabric cycle. It is deliberately *not* a [[MoleTop]] --- the
  * UART loader / TX-drainer wiring is Phase 2's concern, and pulling it into
  * the smoke sim would force every test to push instructions one byte at a time
  * over a baud- rate-paced UART.
  *
  * Bus IO is left as a `master(MoleBus())` (not tied off internally) so the
  * test can read every leaf signal in waveforms; the engine's `read` lines are
  * unused in Step 8 (no sampling yet) so the sim does not need to model a
  * pull-up.
  *
  * Lives in `src/sim/` --- this component never elaborates to RTL.
  */
case class BitCycleEngineSmokeDut(cfg: MoleConfig) extends Component {

  /** Width of the shared SPRAM address bus --- same derivation as
    * [[SpramController]] so the loader payload type matches.
    */
  val addrWidth: Int =
    log2Up(cfg.programWordCount + (cfg.resultRingByteCount + 1) / 2)

  val io = new Bundle {

    /** The engine's bus pads, exposed verbatim for waveform inspection. Every
      * leaf wire is observable from `SpinalSim` via `toBoolean`.
      */
    val bus = master(MoleBus())

    /** Boot-time loader write port into program memory. The test drives one
      * write per word at the start of each run, before raising `start`.
      */
    val loaderWrite = slave Stream SpramWriteCmd(addrWidth)

    /** Engine start strobe. The test holds it high until `done` falls. */
    val start = in Bool ()

    /** Engine done flag --- high while the FSM sits in Idle. Falls when the
      * program begins executing and re-rises once `HALT` finishes.
      */
    val done = out Bool ()
  }

  val engine = BitCycleEngineCore(cfg)
  val spram = SpramController(cfg, useBlackBox = false)

  // ----------------------------------------------------- Fetch path ----
  // Engine reads program memory through the SPRAM controller; one
  // read command per fetch, response comes back one cycle later as a
  // Flow.
  spram.io.readCmd << engine.io.programReadCmd
  engine.io.programReadResp << spram.io.readResp

  // ------------------------------------------------ Result-ring path ----
  spram.io.resultWrite << engine.io.resultWrite

  // ----------------------------------------------------- Loader path ----
  // External loader → controller. Both ports are declared `slave Stream`
  // from their owners' perspectives so the wrapper field-forwards
  // valid / payload outward and reads `ready` back inward.
  spram.io.loaderWrite.valid := io.loaderWrite.valid
  spram.io.loaderWrite.payload := io.loaderWrite.payload
  io.loaderWrite.ready := spram.io.loaderWrite.ready

  // ------------------------------------------------------ Bus pads ----
  io.bus <> engine.io.bus

  // ------------------------------------------------- Control plumbing ----
  engine.io.start := io.start
  io.done := engine.io.done
}

/** Smoke sim for [[BitCycleEngineCore]] under the two SCL drive classes that
  * matter --- OD (release at the high half) and PP (actively drive at the high
  * half).
  *
  * The sim is the cheapest test that catches an engine that wires
  * `SclWaveformGen` to a stuck `BUS_MODE`, ignores `BUS_MODE.mode[2]`, or
  * accidentally swaps `driveLow` / `driveHigh`. Two runs, same 9-bit program (a
  * controller emitting the byte `0x55` plus a `hiz` ACK slot) under `i3c-OD`
  * and `i3c-PP` respectively; per-cycle bus-driver trace; structural assertions
  * on the trace.
  *
  * ==Distinguishing "pulled high" from "driven high"==
  *
  * On a real wired bus, OD release and PP drive-high both end up with the wire
  * at `VIO`. The smoke sim disambiguates them by inspecting the *engine's*
  * drivers directly (`driveLow`, `driveHigh`) rather than the post-pad read.
  * Under `i3c-OD` the engine must release SCL at the high half (`driveHigh =
  * False`); under `i3c-PP` it must drive it (`driveHigh = True`). The test
  * asserts that for every cycle between consecutive SCL-low intervals.
  *
  * ==`tx_symbol` coverage==
  *
  *   - `dominant` (logical 0) under both modes → `(driveLow=1, driveHigh=0)`.
  *   - `recessive` (logical 1) under OD → `(0, 0)` (release; sim pull-up wins
  *     on a real bus).
  *   - `recessive` (logical 1) under PP → `(0, 1)` (drive high; pull-up
  *     irrelevant).
  *   - `hiz` (ACK slot) under both modes → `(0, 0)`.
  *
  * The `0x55` bit pattern alternates `0` and `1`, so each run exercises both
  * `dominant` and `recessive` on SDA, four times each; the ACK slot adds `hiz`.
  *
  * ==Trace validation strategy==
  *
  *   1. Find every contiguous interval where `sclDriveLow` is high. The engine
  *      drives SCL low only during `Q0` and `Q1` of each `EMIT_BIT`, so the
  *      count must match the number of `EMIT_BIT`s (9) and the width must be
  *      roughly `2 * quarterPeriodCyclesReset` fabric cycles.
  *   2. Sample SDA in the middle of each SCL-low interval and compare to the
  *      symbol decoder's expected `(driveLow, driveHigh)` for the `tx_symbol`
  *      of that bit under the active `BusMode`.
  *   3. For every cycle *between* two consecutive SCL-low intervals (i.e.
  *      `Q2 + Q3` plus the 3-cycle fetch / decode overhead), assert the
  *      engine's `sclDriveHigh` matches the expected drive class (False under
  *      OD, True under PP).
  *
  * The strategy ignores the trailing tail after the 9th SCL-low interval ---
  * the regs hold their `Q3` values through the two `HALT` result-ring writes,
  * which is fine but noisy to assert on directly.
  *
  * Run: `sbt "runMain mole.BitCycleEngineSmokeSim"`
  */
object BitCycleEngineSmokeSim {

  // --------------------------------------------------------------
  // Types
  // --------------------------------------------------------------

  /** One per-cycle snapshot of the four engine bus drivers. */
  private case class BusSample(
      sdaLow: Boolean,
      sdaHigh: Boolean,
      sclLow: Boolean,
      sclHigh: Boolean
  )

  /** One contiguous interval of `sclLow = True` in the trace. Inclusive indices
    * into the trace array. `midSda` is the SDA snapshot at the interval's
    * midpoint --- SDA is held for the whole bit by spec, so any sample inside
    * is representative; the midpoint is the most conservative choice against
    * any boundary aliasing.
    */
  private case class LowInterval(start: Int, end: Int, midSda: BusSample)

  // --------------------------------------------------------------
  // Program builder
  // --------------------------------------------------------------

  /** Build the 9-bit "byte 0x55 + ACK hiz + HALT" program for the given
    * controller bus mode.
    *
    * Layout: addr 0 : SET_BUS_MODE mode addr 1-8: EMIT_BIT bit[i] of 0x55 (MSB
    * first) addr 9 : EMIT_BIT hiz (ACK slot --- target drives this on real bus)
    * addr 10 : HALT status=0
    *
    * Flag triple is all-zero on every bit (Step 8 ignores it; mismatch tracking
    * arrives in Step 10 / 11).
    */
  private def buildProgram(mode: BusMode.E): Seq[Int] = {
    import Instruction._
    val dataBits = (0 until 8).map { i =>
      val isOne = ((0x55 >> (7 - i)) & 1) != 0
      val sym = if (isOne) TxSymbol.recessive else TxSymbol.dominant
      encode(EmitBit(sym, expect = false, mask = false, capture = false))
    }
    val ackBit = encode(EmitBit(TxSymbol.hiz, false, false, false))
    Seq(encode(SetBusMode(mode))) ++ dataBits ++ Seq(ackBit, encode(Halt(0)))
  }

  /** Expected `(sdaDriveLow, sdaDriveHigh)` for `(tx_symbol, BusMode)`. Mirrors
    * [[SymbolDecoder]]; serves as the sim's pure-Scala oracle.
    */
  private def expectedSda(
      sym: TxSymbol.E,
      mode: BusMode.E
  ): (Boolean, Boolean) =
    (sym, mode) match {
      case (TxSymbol.dominant, _)               => (true, false)
      case (TxSymbol.recessive, BusMode.i2c)    => (false, false)
      case (TxSymbol.recessive, BusMode.i3cOd)  => (false, false)
      case (TxSymbol.recessive, BusMode.i3cPp)  => (false, true)
      case (TxSymbol.recessive, BusMode.hdrDdr) => (false, true)
      case (TxSymbol.hiz, _)                    => (false, false)
      case (TxSymbol.reserved, _)               => (false, false)
    }

  /** True if `mode` is a PP-class bus (engine actively drives SCL Q2/Q3 high).
    */
  private def isPpClass(mode: BusMode.E): Boolean = mode match {
    case BusMode.i3cPp | BusMode.hdrDdr => true
    case _                              => false
  }

  // --------------------------------------------------------------
  // Sim helpers
  // --------------------------------------------------------------

  /** Drive one loader-write Stream beat and wait for the handshake. */
  private def loaderWrite(
      dut: BitCycleEngineSmokeDut,
      addr: Int,
      word: Int
  ): Unit = {
    dut.io.loaderWrite.valid #= true
    dut.io.loaderWrite.payload.addr #= addr
    dut.io.loaderWrite.payload.data #= word
    dut.clockDomain.waitSamplingWhere(dut.io.loaderWrite.ready.toBoolean)
    dut.io.loaderWrite.valid #= false
  }

  /** Snapshot the four bus drivers this cycle. */
  private def sampleBus(dut: BitCycleEngineSmokeDut): BusSample =
    BusSample(
      sdaLow = dut.io.bus.sda.driveLow.toBoolean,
      sdaHigh = dut.io.bus.sda.driveHigh.toBoolean,
      sclLow = dut.io.bus.scl.driveLow.toBoolean,
      sclHigh = dut.io.bus.scl.driveHigh.toBoolean
    )

  /** Default-released every input the test drives. */
  private def quiet(dut: BitCycleEngineSmokeDut): Unit = {
    dut.io.start #= false
    dut.io.loaderWrite.valid #= false
    dut.io.loaderWrite.payload.addr #= 0
    dut.io.loaderWrite.payload.data #= 0
    // Engine never reads `bus.*.read` in Step 8, but the input still
    // needs *some* value. Pull-up-modeled "released bus reads high".
    dut.io.bus.sda.read #= true
    dut.io.bus.scl.read #= true
  }

  /** Walk a per-cycle trace and extract every contiguous interval where
    * `sclLow` is True. With Mole's quarter-bit pacing each interval corresponds
    * to the `Q0 + Q1` phase of one `EMIT_BIT`.
    */
  private def findSclLowIntervals(trace: Seq[BusSample]): Seq[LowInterval] = {
    val out = collection.mutable.ArrayBuffer.empty[LowInterval]
    var start = -1
    for ((s, idx) <- trace.zipWithIndex) {
      if (s.sclLow && start < 0) {
        start = idx
      } else if (!s.sclLow && start >= 0) {
        val end = idx - 1
        val mid = trace((start + end) / 2)
        out += LowInterval(start, end, mid)
        start = -1
      }
    }
    if (start >= 0) {
      val end = trace.size - 1
      val mid = trace((start + end) / 2)
      out += LowInterval(start, end, mid)
    }
    out.toSeq
  }

  // --------------------------------------------------------------
  // DUT compile
  // --------------------------------------------------------------

  /** Smallest sim config that still has enough address space for the 11-word
    * program + 2 result words. Total = 96 words → 7-bit address space,
    * comfortably below the 4096-instruction cap.
    */
  private def smallCfg = MoleConfig(
    fabricFreqHz = 48 MHz,
    quarterPeriodCyclesReset = 12,
    programWordCount = 64,
    resultRingByteCount = 64,
    captureMaxBits = 65536,
    uartBaud = 2_000_000
  )

  private def compileDut() =
    SimConfig.withWave.compile(BitCycleEngineSmokeDut(smallCfg))

  // --------------------------------------------------------------
  // Per-mode run
  // --------------------------------------------------------------

  /** Run one `(BusMode)` case: load → start → trace → validate. */
  private def runMode(label: String, mode: BusMode.E): Unit = {
    println(s"--- BitCycleEngineSmokeSim: $label ---")
    compileDut().doSim(label) { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.clockDomain.waitSampling(5)

      // 1. Load the program into program memory.
      val program = buildProgram(mode)
      for ((word, idx) <- program.zipWithIndex) {
        loaderWrite(dut, idx, word)
      }
      dut.clockDomain.waitSampling(2)

      // 2. Start the engine and capture a per-cycle bus trace until
      //    `done` re-rises (engine returns to Idle after HALT).
      dut.io.start #= true
      dut.clockDomain.waitSamplingWhere(!dut.io.done.toBoolean)
      dut.io.start #= false

      val trace = collection.mutable.ArrayBuffer.empty[BusSample]
      // Worst-case run length: 9 bits × 48 cycles per bit + fetch /
      // decode + HALT result writes ≈ 500 cycles. 2000 is comfortable
      // slack.
      val safetyLimit = 2000
      while (!dut.io.done.toBoolean && trace.size < safetyLimit) {
        trace += sampleBus(dut)
        dut.clockDomain.waitSampling()
      }
      assert(
        dut.io.done.toBoolean,
        s"$label: engine never halted (ran $safetyLimit cycles)"
      )

      // 3. Structural assertions.
      val intervals = findSclLowIntervals(trace.toSeq)
      assert(
        intervals.size == 9,
        s"$label: expected 9 SCL-low intervals (one per EMIT_BIT), got ${intervals.size}"
      )

      // Q0 + Q1 = 2 × quarter-period cycles = 24 fabric cycles.
      // Allow ±2 cycles of slack for edge-aliased sampling at the
      // exact moment SclWaveformGen flips between Q1 and Q2.
      val widthLo = 2 * smallCfg.quarterPeriodCyclesReset - 2
      val widthHi = 2 * smallCfg.quarterPeriodCyclesReset + 2
      for ((iv, i) <- intervals.zipWithIndex) {
        val width = iv.end - iv.start + 1
        assert(
          width >= widthLo && width <= widthHi,
          s"$label: bit $i SCL-low width $width outside [$widthLo, $widthHi] cycles"
        )
      }

      // Per-bit SDA matches the expected symbol decode.
      val expectedSyms: Seq[TxSymbol.E] = {
        val data = (0 until 8).map { i =>
          if (((0x55 >> (7 - i)) & 1) != 0) TxSymbol.recessive
          else TxSymbol.dominant
        }
        data :+ TxSymbol.hiz
      }
      for (((iv, sym), i) <- intervals.zip(expectedSyms).zipWithIndex) {
        val (eLow, eHigh) = expectedSda(sym, mode)
        assert(
          iv.midSda.sdaLow == eLow && iv.midSda.sdaHigh == eHigh,
          f"$label: bit $i SDA mismatch: expected (low=$eLow, high=$eHigh), " +
            f"saw (low=${iv.midSda.sdaLow}, high=${iv.midSda.sdaHigh})"
        )
      }

      // Between consecutive SCL-low intervals, SCL is in its Q2 / Q3
      // "high" phase plus the 3-cycle fetch / decode overhead.  Under
      // OD the engine releases (sclDriveHigh = False); under PP it
      // actively drives (sclDriveHigh = True). Either way sclDriveLow
      // must be False --- one or the other, never contention.
      val expectSclHigh = isPpClass(mode)
      for (i <- 0 until intervals.size - 1) {
        val gapStart = intervals(i).end + 1
        val gapEnd = intervals(i + 1).start - 1
        for (c <- gapStart to gapEnd) {
          val s = trace(c)
          assert(
            s.sclHigh == expectSclHigh && !s.sclLow,
            f"$label: between bit $i and ${i + 1}, cycle $c: expected SCL " +
              f"(low=false, high=$expectSclHigh), saw (low=${s.sclLow}, high=${s.sclHigh})"
          )
        }
      }

      // SDA + SCL bus-contention: never both drivers high in the same
      // cycle. This is a structural property the symbol decoder is
      // supposed to guarantee; double-checking it here catches any
      // future regression that wires the decoder wrong.
      for ((s, c) <- trace.zipWithIndex) {
        assert(
          !(s.sdaLow && s.sdaHigh),
          s"$label: cycle $c: SDA contention (driveLow && driveHigh)"
        )
        assert(
          !(s.sclLow && s.sclHigh),
          s"$label: cycle $c: SCL contention (driveLow && driveHigh)"
        )
      }

      val widths = intervals.map(iv => iv.end - iv.start + 1).mkString(",")
      println(
        s"$label: ${intervals.size} bits emitted, SCL-low widths [$widths] cycles, " +
          s"between-bit sclDriveHigh=$expectSclHigh"
      )
    }
  }

  def main(args: Array[String]): Unit = {
    runMode("i3c-OD", BusMode.i3cOd)
    runMode("i3c-PP", BusMode.i3cPp)
    println("BitCycleEngineSmokeSim OK")
  }
}
