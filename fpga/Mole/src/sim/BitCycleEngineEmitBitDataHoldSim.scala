package mole

import spinal.core._
import spinal.core.sim._
import spinal.lib._

/** Per-bit SDA data-hold timing audit for `EMIT_BIT_IMM`.
  *
  * Regression coverage for the bug observed on MCXA266 / RT685-EVK where the
  * I2C target never ACKs the address byte from `i2c-soak.moleasm`. The root
  * cause was that the EMIT_BIT decode arm in v0 `BitCycleEngineCore` wrote
  * `sdaDrive*` and `sclDrive*` from the same `decodeState` cycle, so at every
  * bit-to-bit boundary SCL fell *and* SDA changed on the same fabric edge.
  * Spec-strict I2C slaves (NXP LPI2C on MCXA266, FlexComm on RT685) sample SDA
  * via a START/STOP edge detector clocked off their own input synchroniser;
  * pad-to-pad output skew + the slave's asymmetric SDA/SCL input timing let
  * SDA's edge race ahead of SCL's resolved-low on the wire, which the slave's
  * edge detector then mis-classifies as a spurious START/STOP and drops the
  * transfer mid-address.
  *
  * The fix is to delay SDA's bit-to-bit transition by one fabric cycle relative
  * to SCL's Q3-recessive → Q0-dominant fall, giving a `tHD;DAT` hold of ~20.83
  * ns @ 48 MHz fabric (v0.2; was ~41.67 ns @ 24 MHz v0). That comfortably
  * exceeds the I2C-FM (UM10204 rev 7) minimum of 0 ns in every supported mode.
  *
  * In the v0.2 pipeline, the tHD;DAT hold is provided by the pad-boundary SDA
  * `RegNext` at `EnginePipeline.io.sda.driveLow/driveHigh` (see lines 226–227
  * of `EnginePipeline.scala`); SCL is wired through unregistered.
  *
  * ==Test strategy==
  *
  * Run a controller-role program that emits two adjacent `EMIT_BIT_IMM`s with
  * different SDA values (dominant → recessive). Capture a per-cycle bus-driver
  * trace at the dut's pad outputs (which see the post-RegNext SDA). Identify
  * the inter-bit SCL falling-edge cycle; assert that on that cycle SDA still
  * carries bit 1's value, not bit 2's.
  *
  * Run: `sbt "runMain mole.BitCycleEngineEmitBitDataHoldSim"`
  */
object BitCycleEngineEmitBitDataHoldSim extends App {

  // --------------------------------------------------------------
  // Config: v0.2 default 48 MHz fabric, 24 MHz uart. Quarter period
  // of 6 fabric cycles matches the smoke sim.
  // --------------------------------------------------------------

  private val cfg = MoleConfig(
    fabricFreqHz = 48 MHz,
    quarterPeriodCyclesReset = 6,
    programWordCount = 64,
    resultRingByteCount = 64,
    captureMaxBits = 65536,
    uartBaud = 2_000_000
  )

  private lazy val compiled =
    SimConfig.withWave.compile(BitCycleEngineSmokeDut(cfg))

  // --------------------------------------------------------------
  // Per-cycle bus driver snapshot.
  // --------------------------------------------------------------

  private case class BusSample(
      sdaLow: Boolean,
      sdaHigh: Boolean,
      sclLow: Boolean,
      sclHigh: Boolean
  )

  private def sampleBus(dut: BitCycleEngineSmokeDut): BusSample =
    BusSample(
      sdaLow = dut.io.sda.driveLow.toBoolean,
      sdaHigh = dut.io.sda.driveHigh.toBoolean,
      sclLow = dut.io.scl.driveLow.toBoolean,
      sclHigh = dut.io.scl.driveHigh.toBoolean
    )

  // --------------------------------------------------------------
  // Sim plumbing helpers.
  // --------------------------------------------------------------

  private def quiet(dut: BitCycleEngineSmokeDut): Unit = {
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

  // --------------------------------------------------------------
  // Program: SET_BUS_MODE <mode>; EMIT_BIT_IMM dominant;
  // EMIT_BIT_IMM recessive; HALT 0.
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
        EmitBitImm(
          TxSymbol.dominant,
          expect = false,
          mask = false,
          capture = false
        )
      ),
      encode(
        EmitBitImm(
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
    var phase = 0
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
  // --------------------------------------------------------------

  private def runMode(label: String, mode: BusMode.E): Unit = {
    println(s"--- BitCycleEngineEmitBitDataHoldSim: $label ---")
    compiled.doSim(label) { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.clockDomain.waitSampling(5)

      val program = buildProgram(mode)
      for ((word, idx) <- program.zipWithIndex) loaderWrite(dut, idx, word)
      dut.clockDomain.waitSampling(2)

      dut.io.programLength #= program.length
      dut.io.engineStart #= true

      val trace = collection.mutable.ArrayBuffer.empty[BusSample]
      val safetyLimit = 2000
      while (!dut.io.halted.toBoolean && trace.size < safetyLimit) {
        trace += sampleBus(dut)
        dut.clockDomain.waitSampling()
      }
      dut.io.engineStart #= false
      assert(
        dut.io.halted.toBoolean,
        s"$label: engine never halted (ran $safetyLimit cycles)"
      )
      assert(
        dut.io.haltStatus.toInt == 0,
        s"$label: unexpected halt status 0x${dut.io.haltStatus.toInt.toHexString} " +
          s"(expected 0; trace size=${trace.size})"
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
      // least one full fabric cycle after the SCL fall.
      val tHdDatMinCycles = 1
      for (k <- 0 until tHdDatMinCycles) {
        val s = trace(fallCycle + k)
        assert(
          s.sdaLow,
          f"$label: SDA changed too early (cycle ${fallCycle + k}, " +
            f"$k cycle(s) after SCL fall): need >= " +
            f"$tHdDatMinCycles cycle(s) of hold for tHD;DAT margin"
        )
      }

      // Sanity: SDA must actually switch to bit 2's value
      // eventually --- within one full quarter period of cycles
      // after the SCL fall.
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
