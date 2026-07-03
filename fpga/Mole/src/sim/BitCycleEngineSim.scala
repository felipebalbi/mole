// SPDX-FileCopyrightText: 2026 Felipe Balbi
// SPDX-License-Identifier: CERN-OHL-W-2.0

package mole

import spinal.core._
import spinal.core.sim._
import spinal.lib._

/** Cross-cut CTRL-group ISA coverage for the v0.2 bit-cycle engine.
  *
  * The v0 stash under this name carried 17 cases against `BitCycleEngineCore`,
  * many of which exercised v0-only opcodes (DEC_BRANCH, dedicated LOAD_LOOP) or
  * DATA-group features (LOAD_IMM, ADD_IMM, ...) that do not land in the engine
  * until Phase C.9. The C.8 resurrection ports the CTRL-only subset (eight
  * cases) and leaves DATA-touching cases for the C.9 sim.
  *
  * ==Cases (eight, all CTRL-only)==
  *
  *   1. `branchOnAlways_taken` --- ALWAYS branch redirects PC; tightly-bound
  *      sentinel HALTs surround the target.
  *   2. `branchOnMismatch_takenWhenSet_notTakenWhenClear` --- one program with
  *      a single MISMATCH branch reached twice: first when the flag is clear
  *      (falls through to HALT 0x0A sentinel), then after a SAMPLE that
  *      deliberately sets the flag (branches over the sentinel to HALT 0).
  *      Actually run as two separate engine sims to keep one logical observable
  *      per case.
  *   3. `waitOnTimeout_setsTimeoutFlag` --- WAIT_ON ALWAYS=never-true... no:
  *      WAIT_ON on a cond that cannot be met before the 10-bit timeout,
  *      followed by a BRANCH_ON TIMEOUT verifying the flag latched.
  *   4. `flagClear_clearsMismatch` --- intentionally set MISMATCH via a SAMPLE
  *      mis-expect, then FLAG_CLEAR bit 0, then HALT; mismatch bit clear at
  *      halt.
  *   5. `setBusMode_allFourLiveModes_noTrap` --- run SET_BUS_MODE for each of
  *      i2c, i3c-OD, i3c-PP, hdr-ddr, then HALT 0. Asserts the engine accepts
  *      every live mode without trapping.
  *   6. `setBusMode_reservedMode_traps` --- emit a reserved mode word (mode=4)
  *      and assert STATUS_TRAP.
  *   7. `setRole_roundTrip` --- controller → target → controller without trap.
  *   8. `loadTiming_allEightRegs_noTrap` --- LOAD_TIMING for reg=0..7 with
  *      arbitrary dividers, then HALT 0. Asserts the engine accepts every 3-bit
  *      reg index (regs 1,3,5,7 are spec §5.17 reserved for future-use
  *      data-hold/setup variants but legal at encode time and must not trap).
  *
  * The observable LOAD_TIMING-changes-the-waveform check lives in
  * `BitCycleEngineStretchSim` --- this sim only verifies the encoder/decoder
  * front door does not trap on the seven non-default reg indices.
  *
  * Run: `sbt "runMain mole.BitCycleEngineSim"`
  */
object BitCycleEngineSim extends App {

  private val cfg = MoleConfig(
    fabricFreqHz = 48 MHz,
    quarterPeriodCyclesReset = 6,
    programWordCount = 64,
    resultRingByteCount = 64,
    captureMaxBits = 65536,
    uartBaud = 2_000_000
  )

  private val resultBase: Int = cfg.programWordCount
  private val resultWordCount: Int = (cfg.resultRingByteCount + 3) / 4
  private val resultLimit: Int = resultBase + resultWordCount - 1

  private lazy val compiled =
    SimConfig.withWave.compile(BitCycleEngineTargetDut(cfg))

  // Same DUT compile for the target-role mismatch case (the DUT does not
  // bake the role into elaboration; SET_ROLE flips it at runtime).
  private val cfgTarget = cfg.copy(role = EngineRole.Target)
  private lazy val compiledTarget =
    SimConfig.withWave.compile(BitCycleEngineTargetDut(cfgTarget))

  // ------------------------------------------------------------------
  // Shared plumbing (mirrors BitCycleEngineJmpBoundarySim).
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

  private def runSimpleProgram(
      compiledDut: SimCompiled[BitCycleEngineTargetDut],
      label: String,
      program: Seq[Int],
      expectStatus: Int,
      expectMismatch: Option[Boolean] = None,
      safetyLimit: Int = 4000,
      stimulus: BitCycleEngineTargetDut => Unit = _ => ()
  ): Unit = {
    println(s"--- BitCycleEngineSim: $label ---")
    compiledDut.doSim(label) { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.clockDomain.waitSampling(5)
      load(dut, program)
      dut.io.programLength #= program.length
      dut.io.engineStart #= true
      stimulus(dut)
      val cycles = waitHalted(dut, safetyLimit)
      dut.io.engineStart #= false
      assert(
        dut.io.halted.toBoolean,
        s"$label: engine never halted (ran $cycles cycles)"
      )
      val h = readHalt(dut)
      assert(
        h.status == expectStatus,
        f"$label: HALT status 0x${h.status}%02x != expected 0x$expectStatus%02x"
      )
      expectMismatch.foreach { exp =>
        assert(
          h.mismatch == exp,
          s"$label: HALT mismatch=${h.mismatch} != expected $exp"
        )
      }
      println(s"$label OK ($cycles cycles, status=0x${h.status.toHexString})")
    }
  }

  import Instruction._

  // ------------------------------------------------------------------
  // Case 1: BRANCH_ON ALWAYS taken.
  // ------------------------------------------------------------------
  private def runBranchOnAlwaysTaken(): Unit = {
    // Same shape as BitCycleEngineJmpBoundarySim.runBranchAlwaysMaxLegalRuns
    // but smaller. ALWAYS branch at PC=0, offset=+2 → target=3.
    val program = Seq(
      encode(BranchOn(CondCode.always, pcRelOffset = +2)), // PC=0
      encode(Halt(0x0a)), // PC=1 sentinel
      encode(Halt(0x0a)), // PC=2 sentinel
      encode(Halt(0x00)) //  PC=3 target
    )
    runSimpleProgram(
      compiled,
      "branchOnAlways_taken",
      program,
      expectStatus = 0
    )
  }

  // ------------------------------------------------------------------
  // Case 2 (split): branchOnMismatch_notTakenWhenClear /
  //                 branchOnMismatch_takenWhenSet.
  // ------------------------------------------------------------------
  private def runBranchOnMismatchNotTakenWhenClear(): Unit = {
    // MISMATCH_FLAG starts clear at reset. BRANCH_ON MISMATCH must fall
    // through to the sentinel HALT 0x0A.
    val program = Seq(
      encode(BranchOn(CondCode.mismatch, pcRelOffset = +2)), // PC=0
      encode(Halt(0x0a)), //                                   PC=1 fall-through
      encode(Halt(0x0a)), //                                   PC=2 sentinel
      encode(Halt(0x00)) //                                    PC=3 target
    )
    runSimpleProgram(
      compiled,
      "branchOnMismatch_notTakenWhenClear",
      program,
      expectStatus = 0x0a
    )
  }

  private def runBranchOnMismatchTakenWhenSet(): Unit = {
    // Target role: SAMPLE with expect=1 against externally-driven SDA=0
    // sets MISMATCH_FLAG. Then BRANCH_ON MISMATCH skips the sentinel.
    val program = Seq(
      encode(SetBusMode(BusMode.i2c)), //                       PC=0
      encode(SampleBitOnScl(expect = true, mask = true, capture = false)),
      // PC=1: sets MISMATCH on sampled-low
      encode(BranchOn(CondCode.mismatch, pcRelOffset = +2)), // PC=2
      encode(Halt(0x0a)), //                                    PC=3 sentinel
      encode(Halt(0x0a)), //                                    PC=4 sentinel
      encode(Halt(0x00)) //                                     PC=5 target
    )
    runSimpleProgram(
      compiledTarget,
      "branchOnMismatch_takenWhenSet",
      program,
      expectStatus = 0,
      expectMismatch = Some(true),
      safetyLimit = 5000,
      stimulus = dut => {
        // Drive SDA low + pulse SCL to land one sample with mismatch.
        fork {
          dut.io.sda.read #= false
          dut.io.scl.read #= false
          dut.clockDomain.waitSampling(40)
          dut.io.scl.read #= true
          dut.clockDomain.waitSampling(20)
        }
      }
    )
  }

  // ------------------------------------------------------------------
  // Case 3: WAIT_ON with finite timeout that expires; BRANCH_ON TIMEOUT
  // takes; verify TIMEOUT_FLAG observable via that branch path.
  // ------------------------------------------------------------------
  private def runWaitOnTimeoutSetsTimeoutFlag(): Unit = {
    // WAIT_ON START_SEEN with a small timeout (4 quarter-bits) at idle:
    // no start arrives, timeout fires, TIMEOUT_FLAG is latched. Then
    // BRANCH_ON TIMEOUT skips the sentinel HALT 0x0A and lands on the
    // clean HALT 0.
    val program = Seq(
      encode(WaitOn(CondCode.startSeen, timeoutQuarters = 4)), //  PC=0
      encode(BranchOn(CondCode.timeout, pcRelOffset = +2)), //     PC=1
      encode(Halt(0x0a)), //                                       PC=2 sentinel
      encode(Halt(0x0a)), //                                       PC=3 sentinel
      encode(Halt(0x00)) //                                        PC=4 target
    )
    runSimpleProgram(
      compiled,
      "waitOnTimeout_setsTimeoutFlag",
      program,
      expectStatus = 0,
      safetyLimit = 4000
    )
  }

  // ------------------------------------------------------------------
  // Case 4: FLAG_CLEAR clears MISMATCH_FLAG.
  // ------------------------------------------------------------------
  private def runFlagClearClearsMismatch(): Unit = {
    // Set MISMATCH via target-role SAMPLE expect=1 vs SDA=0, then
    // FLAG_CLEAR bit 0 (= MISMATCH per EnginePipeline §FLAG_CLEAR), then
    // HALT 0. The HALT word's mismatch bit must be clear.
    val program = Seq(
      encode(SetBusMode(BusMode.i2c)), //                        PC=0
      encode(SampleBitOnScl(expect = true, mask = true, capture = false)),
      // PC=1
      encode(FlagClear(clearMask = 0x01)), //                    PC=2
      encode(Halt(0x00)) //                                      PC=3
    )
    runSimpleProgram(
      compiledTarget,
      "flagClear_clearsMismatch",
      program,
      expectStatus = 0,
      expectMismatch = Some(false),
      safetyLimit = 5000,
      stimulus = dut => {
        fork {
          dut.io.sda.read #= false
          dut.io.scl.read #= false
          dut.clockDomain.waitSampling(40)
          dut.io.scl.read #= true
          dut.clockDomain.waitSampling(20)
        }
      }
    )
  }

  // ------------------------------------------------------------------
  // Case 5: SET_BUS_MODE on each of the four live modes, no trap.
  // ------------------------------------------------------------------
  private def runSetBusModeAllFourLiveModesNoTrap(): Unit = {
    val program = Seq(
      encode(SetBusMode(BusMode.i2c)),
      encode(SetBusMode(BusMode.i3cOd)),
      encode(SetBusMode(BusMode.i3cPp)),
      encode(SetBusMode(BusMode.hdrDdr)),
      encode(Halt(0))
    )
    runSimpleProgram(
      compiled,
      "setBusMode_allFourLiveModes_noTrap",
      program,
      expectStatus = 0
    )
  }

  // ------------------------------------------------------------------
  // Case 6: SET_BUS_MODE reserved value traps STATUS_TRAP.
  // ------------------------------------------------------------------
  private def runSetBusModeReservedModeTraps(): Unit = {
    // Hand-craft a SET_BUS_MODE with mode=4 (first reserved value).
    // Encoder rejects via SpinalEnum but the engine's runtime trap path
    // sees the raw wire bits, so we build the word manually:
    //   group = 01 (CTRL), sub = 0011 (setBusMode); mode field at [6:3]
    //   = 0b0100 (= 4 reserved).
    val setBusModeOpcodePrefix = (1 << 30) | (3 << 26) // group 01, sub 3
    val reservedModeWord = setBusModeOpcodePrefix | (4 << 3)
    val program = Seq(
      reservedModeWord,
      encode(Halt(0x00)) //  sentinel; should never execute
    )
    runSimpleProgram(
      compiled,
      "setBusMode_reservedMode_traps",
      program,
      expectStatus = 0x1f
    )
  }

  // ------------------------------------------------------------------
  // Case 7: SET_ROLE controller → target → controller, no trap.
  // ------------------------------------------------------------------
  private def runSetRoleRoundTrip(): Unit = {
    val program = Seq(
      encode(SetRole(role = true)), //  controller→target
      encode(SetRole(role = false)), // target→controller
      encode(Halt(0))
    )
    runSimpleProgram(
      compiled,
      "setRole_roundTrip",
      program,
      expectStatus = 0
    )
  }

  // ------------------------------------------------------------------
  // Case 8: LOAD_TIMING for all 8 reg indices, no trap.
  // ------------------------------------------------------------------
  private def runLoadTimingAllEightRegsNoTrap(): Unit = {
    val program = (0 until 8).map { reg =>
      // Distinct, non-zero divider values per reg so the program is not
      // accidentally a no-op. 4..11 fits comfortably in the 14-bit field.
      encode(LoadTiming(reg = reg, divider = 4 + reg))
    } :+ encode(Halt(0))
    runSimpleProgram(
      compiled,
      "loadTiming_allEightRegs_noTrap",
      program,
      expectStatus = 0
    )
  }

  // ------------------------------------------------------------------
  // Entry point.
  // ------------------------------------------------------------------

  runBranchOnAlwaysTaken()
  runBranchOnMismatchNotTakenWhenClear()
  runBranchOnMismatchTakenWhenSet()
  runWaitOnTimeoutSetsTimeoutFlag()
  runFlagClearClearsMismatch()
  runSetBusModeAllFourLiveModesNoTrap()
  runSetBusModeReservedModeTraps()
  runSetRoleRoundTrip()
  runLoadTimingAllEightRegsNoTrap()
  println("BitCycleEngineSim: all 9 cases passed")
}
