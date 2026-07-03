// SPDX-FileCopyrightText: 2026 Felipe Balbi
// SPDX-License-Identifier: CERN-OHL-W-2.0

package mole

import spinal.core._
import spinal.core.sim._
import spinal.lib._

/** Out-of-range BRANCH_ON regression coverage (F-FPGA-004 reframed under v0.2).
  *
  * Original v0 issue: JMP decoded as `pc := instrReg(10 downto 0).asUInt
  * .resize(pcWidth)`, silently truncating 11-bit operands on small-memory
  * boards. The v0.2 ISA replaces JMP with `BRANCH_ON cond, signed-10-bit
  * offset`; `JMP <label>` is assembler sugar for `BRANCH_ON ALWAYS, offset`
  * (spec §12.3). The target is `branch_pc + 1 + offset`, computed in signed
  * arithmetic — both negative wrap and overflow-past-`programLength` are
  * out-of-range cases that must trap STATUS_TRAP per the spec §5.11 SPEC GAP
  * resolution landed in C.8.
  *
  * ==Cases==
  *
  *   1. `branchAlwaysZeroLoopRuns` — `BRANCH_ON ALWAYS, -1` at PC=0 is a tight
  *      self-loop (target = 0+1-1 = 0). Engine must NOT halt within a generous
  *      observation window — guard mis-firing on a legal in-range target would
  *      surface as a STATUS_TRAP halt.
  *   2. `branchAlwaysMaxLegalRuns` — `BRANCH_ON ALWAYS, +N` lands on the last
  *      legal program slot, which holds HALT(0). Engine must halt cleanly
  *      (status 0). Exercises the boundary just-inside-`programLength`.
  *   3. `branchAlwaysOutOfRangeForwardTraps` — `BRANCH_ON ALWAYS, +N` lands one
  *      past the last legal slot. Engine must trap STATUS_TRAP (0x1F).
  *   4. `branchAlwaysOutOfRangeNegativeTraps` — `BRANCH_ON ALWAYS, -5` at PC=0
  *      (target = -4 — wraps negative). Engine must trap STATUS_TRAP.
  *
  * The v0.2 wire format encodes the 10-bit offset shifted by 3, with the
  * cond-code in [16:13]. Tests use `Instruction.encode(BranchOn(...))` so the
  * encoder's range checks gate the input first (the offset must fit a signed
  * 10-bit field); the out-of-range *runtime* cases are the ones where the
  * offset is legal at encode time but the resulting *target* PC falls outside
  * `[0, programLength)`.
  *
  * Run: `sbt "runMain mole.BitCycleEngineJmpBoundarySim"`
  */
object BitCycleEngineJmpBoundarySim {

  // smallCfg: programWordCount = 16 so a small branch offset can step past
  // programLength without overflowing the signed-10-bit operand field.
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

  // ------------------------------------------------------------------
  // Shared sim plumbing (matches the C.7 sim template).
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

  /** Load a sparse program: `(addr -> word)` pairs. Unwritten slots stay at
    * SPRAM-default zero (which decodes as WIRE EMIT_BIT_IMM dominant, not a
    * trap — so test programs must guard against fall-through with an explicit
    * HALT at every reachable PC).
    */
  private def loadAt(
      dut: BitCycleEngineTargetDut,
      words: Seq[(Int, Int)]
  ): Unit = {
    for ((addr, word) <- words) loaderWrite(dut, addr, word)
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

  /** Read and decode the HALT word at `resultLimit` (the reserved slot per the
    * C.7 / C.8 ABI). Returns the 5-bit status field.
    */
  private def haltStatus(dut: BitCycleEngineTargetDut): Int = {
    val w = debugRead(dut, resultLimit)
    require(
      ((w >>> 30) & 0x3) == 0x3,
      f"word at resultLimit=$resultLimit is not a HALT tag (=0x$w%08x)"
    )
    (w >>> 23) & 0x1f
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
  // Case 1: BRANCH_ON ALWAYS, -1 (self-loop). Must NOT halt.
  // ------------------------------------------------------------------
  private def runBranchAlwaysZeroLoopRuns(): Unit = {
    val label = "branchAlwaysZeroLoopRuns"
    println(s"--- BitCycleEngineJmpBoundarySim: $label ---")
    compiled.doSim(label) { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.clockDomain.waitSampling(5)

      // BRANCH_ON ALWAYS, -1 at PC=0 → target = 0 + 1 + (-1) = 0.
      // Tight self-loop; the guard must NOT mis-fire on this legal
      // in-range target.
      val program = Seq(
        0 -> encode(BranchOn(CondCode.always, pcRelOffset = -1))
      )
      loadAt(dut, program)

      dut.io.programLength #= 1
      dut.io.engineStart #= true
      dut.clockDomain.waitSampling()

      // Observe for 2000 cycles. A premature halt = guard mis-fire.
      var sawHalt = false
      for (_ <- 0 until 2_000) {
        if (dut.io.halted.toBoolean) sawHalt = true
        dut.clockDomain.waitSampling()
      }
      dut.io.engineStart #= false
      assert(
        !sawHalt,
        s"$label: engine halted during legal self-loop — guard mis-fired"
      )
      println(s"$label OK (no premature halt over 2000 cycles)")
    }
  }

  // ------------------------------------------------------------------
  // Case 2: BRANCH_ON ALWAYS, +N — lands on the last legal slot which
  // holds HALT(0). All "shouldn't reach" slots hold HALT(0x0A) sentinels
  // so we can distinguish "branch redirected correctly" (status=0) from
  // "branch didn't redirect, fell through to PC=1" (status=0x0A) from
  // "branch trapped" (status=0x1F). Sparse loading was avoided after the
  // first attempt revealed it produced a spurious STATUS_TRAP — diagnostic
  // notes in the Path A discussion (Coder-session, C.8 Commit 3).
  // ------------------------------------------------------------------
  private def runBranchAlwaysMaxLegalRuns(): Unit = {
    val label = "branchAlwaysMaxLegalRuns"
    println(s"--- BitCycleEngineJmpBoundarySim: $label ---")
    compiled.doSim(label) { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.clockDomain.waitSampling(5)

      // Contiguous 8-slot program. BRANCH at PC=0 with offset=+6 →
      // target = 0 + 1 + 6 = 7 (last legal slot = HALT(0)).
      // Fall-through path would hit PC=1 = HALT(0x0A) sentinel.
      val program = Seq(
        encode(BranchOn(CondCode.always, pcRelOffset = +6)), // PC=0
        encode(Halt(0x0a)), // PC=1 sentinel
        encode(Halt(0x0a)), // PC=2 sentinel
        encode(Halt(0x0a)), // PC=3 sentinel
        encode(Halt(0x0a)), // PC=4 sentinel
        encode(Halt(0x0a)), // PC=5 sentinel
        encode(Halt(0x0a)), // PC=6 sentinel
        encode(Halt(0x00)) //  PC=7 target
      )
      for ((word, idx) <- program.zipWithIndex) loaderWrite(dut, idx, word)
      dut.clockDomain.waitSampling(2)

      dut.io.programLength #= program.length
      dut.io.engineStart #= true

      val cycles = waitHalted(dut, safetyLimit = 200)
      assert(
        dut.io.halted.toBoolean,
        s"$label: engine did not halt within $cycles cycles"
      )
      val status = haltStatus(dut)
      assert(
        status == 0x0,
        f"$label: HALT status 0x$status%02x != 0x00. " +
          "0x0A = fall-through (branch did not redirect); " +
          "0x1F = STATUS_TRAP (out-of-range guard mis-fired); " +
          "anything else = engine in a weird state."
      )
      println(s"$label OK ($cycles cycles, status=0x00)")
    }
  }

  // ------------------------------------------------------------------
  // Case 3: BRANCH_ON ALWAYS, +N — target exactly at programLength
  // (first out-of-range slot). Must trap STATUS_TRAP.
  // ------------------------------------------------------------------
  private def runBranchAlwaysOutOfRangeForwardTraps(): Unit = {
    val label = "branchAlwaysOutOfRangeForwardTraps"
    println(s"--- BitCycleEngineJmpBoundarySim: $label ---")
    compiled.doSim(label) { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.clockDomain.waitSampling(5)

      // target = 0 + 1 + 7 = 8 == programLength (first out-of-range).
      val programLength = 8
      val offset = programLength - 1 // 7
      val program = Seq(
        0 -> encode(BranchOn(CondCode.always, pcRelOffset = offset))
      )
      loadAt(dut, program)

      dut.io.programLength #= programLength
      dut.io.engineStart #= true
      dut.clockDomain.waitSampling()

      val cycles = waitHalted(dut, safetyLimit = 200)
      dut.io.engineStart #= false
      assert(
        dut.io.halted.toBoolean,
        s"$label: engine did not halt within $cycles cycles — " +
          "out-of-range branch failed to trap"
      )
      val status = haltStatus(dut)
      assert(
        status == 0x1f,
        s"$label: HALT status 0x${status.toHexString} != STATUS_TRAP (0x1F)"
      )
      println(s"$label OK ($cycles cycles, status=0x1F)")
    }
  }

  // ------------------------------------------------------------------
  // Case 4: BRANCH_ON ALWAYS, -5 at PC=0 — target = -4 (negative wrap).
  // Must trap STATUS_TRAP.
  // ------------------------------------------------------------------
  private def runBranchAlwaysOutOfRangeNegativeTraps(): Unit = {
    val label = "branchAlwaysOutOfRangeNegativeTraps"
    println(s"--- BitCycleEngineJmpBoundarySim: $label ---")
    compiled.doSim(label) { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.clockDomain.waitSampling(5)

      // target = 0 + 1 + (-5) = -4 (negative wrap, out of range).
      val program = Seq(
        0 -> encode(BranchOn(CondCode.always, pcRelOffset = -5))
      )
      loadAt(dut, program)

      dut.io.programLength #= 1
      dut.io.engineStart #= true
      dut.clockDomain.waitSampling()

      val cycles = waitHalted(dut, safetyLimit = 200)
      dut.io.engineStart #= false
      assert(
        dut.io.halted.toBoolean,
        s"$label: engine did not halt within $cycles cycles — " +
          "negative-target branch failed to trap"
      )
      val status = haltStatus(dut)
      assert(
        status == 0x1f,
        s"$label: HALT status 0x${status.toHexString} != STATUS_TRAP (0x1F)"
      )
      println(s"$label OK ($cycles cycles, status=0x1F)")
    }
  }

  def main(args: Array[String]): Unit = {
    runBranchAlwaysZeroLoopRuns()
    runBranchAlwaysMaxLegalRuns()
    runBranchAlwaysOutOfRangeForwardTraps()
    runBranchAlwaysOutOfRangeNegativeTraps()
    println("BitCycleEngineJmpBoundarySim: all 4 cases passed")
  }
}
