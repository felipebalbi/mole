package mole

import spinal.core._
import spinal.core.sim._
import spinal.lib._

/** Per-bit SDA data-hold timing audit for `EMIT_BIT`.
  *
  * Regression coverage for the bug observed on MCXA266 / RT685-EVK where the
  * I2C target never ACKs the address byte from `i2c-soak.moleasm`. The root
  * cause was that the `EMIT_BIT` decode arm in [[BitCycleEngineCore]] wrote
  * `sdaDrive*` and `sclDrive*` from the same `decodeState` cycle, so at every
  * bit-to-bit boundary SCL fell *and* SDA changed on the same fabric edge.
  * Spec-strict I2C slaves (NXP LPI2C on MCXA266, FlexComm on RT685) sample SDA
  * via a START/STOP edge detector clocked off their own input synchroniser;
  * pad-to-pad output skew + the slave's asymmetric SDA/SCL input timing let
  * SDA's edge race ahead of SCL's resolved-low on the wire, which the slave's
  * edge detector then mis-classifies as a spurious START/STOP and drops the
  * transfer mid-address.
  *
  * The TMP108 on the bring-up rig has an input glitch filter wide enough to
  * swallow the race, which is why `tmp108.moleasm` works against the same
  * engine. A spec-strict slave does not.
  *
  * The fix is to delay SDA's bit-to-bit transition by one fabric cycle relative
  * to SCL's Q3-recessive → Q0-dominant fall, giving a `tHD;DAT` hold of ~41.67
  * ns @ 24 MHz fabric. That comfortably exceeds the I2C-FM (UM10204 rev 7)
  * minimum of 0 ns in every supported mode (Standard, Fast, Fast+) without
  * eating measurably into the next bit's setup window:
  *
  * {{{
  *   mode    SCL     tHD;DAT min   achieved   tSU;DAT remaining
  *   Std    100 kHz       0 ns      41.67 ns  ~4958 ns (min 250)
  *   Fast   400 kHz       0 ns      41.67 ns  ~1208 ns (min 100)
  *   Fast+    1 MHz       0 ns      41.67 ns   ~458 ns (min  50)
  * }}}
  *
  * ==Test strategy==
  *
  * Run a controller-role program that emits two adjacent `EMIT_BIT`s with
  * *different* SDA values --- specifically `dominant` (logical 0) followed by
  * `recessive` (logical 1), which is the bit-7→bit-6 transition the `i2c-soak`
  * `addr_w = 0x40 = 0100_0000` byte starts with. Capture a per-cycle bus-driver
  * trace; identify the cycle of the inter-bit SCL falling edge (the cycle where
  * `sclDriveLow` transitions False → True after the first SCL-high region);
  * assert that on that cycle SDA still carries bit 1's
  * `(driveLow=True, driveHigh=False)` decode, *not* bit 2's `(False, False)`
  * release. The new SDA value must appear no earlier than one fabric cycle
  * later.
  *
  * To keep the sim portable across BUS_MODE classes, the test runs the same
  * assertion under both `i2c` (OD class, recessive → release `(0, 0)`) and
  * `i3c-PP` (PP class, recessive → drive-high `(0, 1)`); each surfaces the race
  * as a different `sdaDriveHigh` transition timing.
  *
  * The pre-fix engine fails the assertion: SDA's drivers change on the same
  * cycle SCL's drivers do. The post-fix engine passes it: SCL changes one
  * fabric cycle ahead of SDA.
  *
  * ==Coverage gap this fills==
  *
  * `BitCycleEngineSmokeSim` already validates the steady-state per-bit SDA
  * value (sampled at the *midpoint* of each SCL-low interval) and the SCL
  * high-half drive class. It does *not* check the cycle-by-cycle ordering of
  * SCL and SDA edges, so a same-cycle assignment passes the smoke sim
  * trivially. This sim is the dedicated regression for that ordering.
  *
  * Run: `sbt "runMain mole.BitCycleEngineEmitBitDataHoldSim"`
  */
object BitCycleEngineEmitBitDataHoldSim extends App {

  // --------------------------------------------------------------
  // Config: small program memory; default 24 MHz fabric. Quarter
  // period of 6 fabric cycles matches the smoke sim.
  // --------------------------------------------------------------

  private val cfg = MoleConfig(
    fabricFreqHz = 24 MHz,
    quarterPeriodCyclesReset = 6,
    programWordCount = 64,
    resultRingByteCount = 64,
    captureMaxBits = 65536,
    uartBaud = 1_000_000
  )

  private lazy val compiled =
    SimConfig.withWave.compile(BitCycleEngineSmokeDut(cfg))

  // --------------------------------------------------------------
  // Per-cycle bus driver snapshot (mirrors BitCycleEngineSmokeSim).
  // --------------------------------------------------------------

  private case class BusSample(
      sdaLow: Boolean,
      sdaHigh: Boolean,
      sclLow: Boolean,
      sclHigh: Boolean
  )

  private def sampleBus(dut: BitCycleEngineSmokeDut): BusSample =
    BusSample(
      sdaLow = dut.io.bus.sda.driveLow.toBoolean,
      sdaHigh = dut.io.bus.sda.driveHigh.toBoolean,
      sclLow = dut.io.bus.scl.driveLow.toBoolean,
      sclHigh = dut.io.bus.scl.driveHigh.toBoolean
    )

  // --------------------------------------------------------------
  // Sim plumbing helpers --- same shape as the smoke sim.
  // --------------------------------------------------------------

  private def quiet(dut: BitCycleEngineSmokeDut): Unit = {
    dut.io.start #= false
    dut.io.loaderWrite.valid #= false
    dut.io.loaderWrite.payload.addr #= 0
    dut.io.loaderWrite.payload.data #= 0
    dut.io.bus.sda.read #= true
    dut.io.bus.scl.read #= true
  }

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

  private def load(
      dut: BitCycleEngineSmokeDut,
      program: Seq[Int]
  ): Unit = {
    for ((word, idx) <- program.zipWithIndex) loaderWrite(dut, idx, word)
    dut.clockDomain.waitSampling(2)
  }

  // --------------------------------------------------------------
  // Program: SET_BUS_MODE <mode>; EMIT_BIT dominant; EMIT_BIT
  // recessive; HALT 0.
  //
  // Two bits is the minimum to expose the bit-to-bit boundary. The
  // pattern `dominant` → `recessive` flips SDA in both directions
  // depending on BUS_MODE:
  //   - i2c     : (1,0) → (0,0)   --- sdaDriveLow falls
  //   - i3c-PP  : (1,0) → (0,1)   --- sdaDriveLow falls AND
  //                                     sdaDriveHigh rises
  // The assertion uses sdaDriveLow on its own (common to both),
  // which fully captures the race.
  // --------------------------------------------------------------

  private def buildProgram(mode: BusMode.E): Seq[Int] = {
    import Instruction._
    Seq(
      encode(SetBusMode(mode)),
      encode(
        EmitBit(
          TxSymbol.dominant,
          expect = false,
          mask = false,
          capture = false
        )
      ),
      encode(
        EmitBit(
          TxSymbol.recessive,
          expect = false,
          mask = false,
          capture = false
        )
      ),
      encode(Halt(0))
    )
  }

  // --------------------------------------------------------------
  // Walk the trace; return the index of the EARLIEST cycle whose
  // sclDriveLow is True AFTER at least one cycle of sclDriveLow ==
  // False (i.e. the first inter-bit SCL falling-edge cycle past
  // bit 1's high half). Returns -1 if not found.
  // --------------------------------------------------------------

  private def findInterBitSclFallCycle(trace: Seq[BusSample]): Int = {
    // Skip the initial idle window (both drivers low). The first
    // sclDriveLow True is the START of bit 1; we want the SECOND
    // such region's start. Walk: in-low → in-high → in-low(2).
    var phase =
      0 // 0 = pre-bit1-low, 1 = bit1-low, 2 = high-gap, 3 = bit2-low (target)
    for ((s, idx) <- trace.zipWithIndex) {
      phase match {
        case 0 if s.sclLow  => phase = 1
        case 1 if !s.sclLow => phase = 2
        case 2 if s.sclLow  => return idx
        case _              =>
      }
    }
    -1
  }

  // --------------------------------------------------------------
  // Per-mode runner.
  //
  // The assertion is keyed on `sdaDriveLow`: under both BUS_MODEs,
  // bit 1 (dominant) decodes to `sdaDriveLow=True` and bit 2
  // (recessive) decodes to `sdaDriveLow=False`. If SDA leads or
  // tracks SCL exactly, the inter-bit SCL-fall cycle will see
  // `sdaDriveLow=False` (bit 2's value). The fix shifts SDA's
  // update one cycle later, so on the SCL-fall cycle SDA still
  // shows `sdaDriveLow=True` (bit 1's value).
  // --------------------------------------------------------------

  private def runMode(label: String, mode: BusMode.E): Unit = {
    println(s"--- BitCycleEngineEmitBitDataHoldSim: $label ---")
    compiled.doSim(label) { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.clockDomain.waitSampling(5)

      load(dut, buildProgram(mode))

      dut.io.start #= true
      dut.clockDomain.waitSamplingWhere(!dut.io.done.toBoolean)
      dut.io.start #= false

      val trace = collection.mutable.ArrayBuffer.empty[BusSample]
      val safetyLimit = 2000
      while (!dut.io.done.toBoolean && trace.size < safetyLimit) {
        trace += sampleBus(dut)
        dut.clockDomain.waitSampling()
      }
      assert(
        dut.io.done.toBoolean,
        s"$label: engine never halted (ran $safetyLimit cycles)"
      )

      val fallCycle = findInterBitSclFallCycle(trace.toSeq)
      assert(
        fallCycle >= 0,
        s"$label: never observed an inter-bit SCL fall in the trace " +
          s"(trace size ${trace.size})"
      )

      // The single key assertion: on the cycle SCL transitions to
      // dominant for bit 2, SDA must still carry bit 1's dominant
      // drive. Anything else is the same-cycle race.
      val onFall = trace(fallCycle)
      assert(
        onFall.sdaLow,
        f"$label: SDA leads/tracks SCL on the inter-bit fall " +
          f"(cycle $fallCycle): SDA = (low=${onFall.sdaLow}, " +
          f"high=${onFall.sdaHigh}) --- expected SDA to still hold " +
          f"bit 1's dominant value (sdaDriveLow=True)"
      )

      // tHD;DAT budget: SDA must remain at bit 1's value for at
      // least one full fabric cycle after the SCL fall. Read the
      // very next cycle; SDA may transition there (the canonical
      // fix shape) or later (a more conservative hold).
      val tHdDatMinCycles = 1
      for (k <- 0 until tHdDatMinCycles) {
        val s = trace(fallCycle + k)
        assert(
          s.sdaLow,
          f"$label: SDA changed too early (cycle ${fallCycle + k}, " +
            f"$k cycle(s) after SCL fall): need >= " +
            f"$tHdDatMinCycles cycle(s) of hold for tHD;DAT margin " +
            f"on Std/Fast/Fast+ I2C"
        )
      }

      // Sanity: SDA must actually switch to bit 2's value
      // eventually --- within one full quarter period of cycles
      // after the SCL fall (Q0 of bit 2 ends at cycle
      // fallCycle + quarterPeriodCyclesReset).
      val quarterCycles = cfg.quarterPeriodCyclesReset
      val window = (0 to quarterCycles).map(k => trace(fallCycle + k))
      val firstChange = window.indexWhere(!_.sdaLow)
      assert(
        firstChange > 0 && firstChange <= quarterCycles,
        f"$label: SDA never released within Q0 of bit 2 " +
          f"(cycle $fallCycle + [0..$quarterCycles]); change idx = " +
          f"$firstChange"
      )

      println(
        f"$label: inter-bit SCL fall at cycle $fallCycle, " +
          f"SDA stayed dominant through cycle " +
          f"${fallCycle + firstChange - 1}, " +
          f"released at cycle ${fallCycle + firstChange} " +
          f"(${firstChange} fabric cycle(s) after SCL fall)"
      )
    }
    println(s"$label OK")
  }

  runMode("i2c", BusMode.i2c)
  runMode("i3c-PP", BusMode.i3cPp)
  println("BitCycleEngineEmitBitDataHoldSim OK")
}
