package mole

import spinal.core._
import spinal.core.sim._
import spinal.lib._

/** DATA-group ISA coverage for the v0.2 bit-cycle engine (C.9).
  *
  * Companion to `BitCycleEngineSim` (CTRL coverage). The CTRL sim's docstring
  * notes that the v0 stash carried DATA-touching cases that were deferred to
  * C.9 — this file is that deferred sim, plus new coverage for v0.2-only shapes
  * (REG_ZERO_FLAG, SHIFT, load-use hazards, aleft trap).
  *
  * ==Verification strategy==
  *
  * The DUT exposes no direct register-read path. We verify architectural state
  * by chaining: do the DATA op under test; then `XOR_IMM scratch, target,
  * expected_imm14` to drive REG_ZERO_FLAG; then `BRANCH_ON REG_ZERO` selects
  * between a `HALT 0` pass and a `HALT 0x0A` sentinel fail. Distinct sentinels
  * per check let the failure pinpoint which assertion broke. For values that
  * don't fit in a 14-bit zero-extended XOR_IMM immediate, we verify via the
  * REG_ZERO_FLAG sticky bit directly + BRANCH_ON REG_ZERO / NOT_REG_ZERO.
  *
  * ==Cases==
  *
  *   1. `loadImm_allEightRegs_noTrap` --- LOAD_IMM into R0..R7 with distinct
  *      immediates, then HALT 0. Smoke test that all 8 dst encodings work.
  *   2. `loadImm_zero_setsRegZeroFlag` --- LOAD_IMM R3, 0; BRANCH_ON REG_ZERO
  *      to pass HALT 0.
  *   3. `loadImm_nonzero_clearsRegZeroFlag` --- after a LOAD_IMM Rx, 0 sets the
  *      flag, a subsequent LOAD_IMM Ry, 1 must clear it (write-once-
  *      overwritten per AGENTS §3.15).
  *   4. `mov_propagatesValue` --- LOAD_IMM R0, 0x1234; MOV R3, R0; verify R3 ==
  *      0x1234 via XOR_IMM + BRANCH_ON REG_ZERO.
  *   5. `addImm_positive_negative_zero_wrap` --- LOAD_IMM R0, 10; ADD_IMM R1,
  *      R0, 5; verify R1 == 15. Then ADD_IMM R2, R1, -15; verify R2 == 0 via
  *      REG_ZERO_FLAG.
  *   6. `dec_wrapsAtZero` --- LOAD_IMM R0, 0; DEC R0; the result is 0xFFFFFFFF,
  *      which is non-zero, so REG_ZERO_FLAG must be clear and BRANCH_ON
  *      NOT_REG_ZERO must take.
  *   7. `dec_countedLoop` --- the canonical loop idiom from spec §5.21:
  *      LOAD_IMM R6, 3; loop: DEC R6; BRANCH_ON NOT_REG_ZERO, loop; HALT 0.
  *      Just verify it terminates cleanly (does not hang, does not trap).
  *   8. `andImm_orImm_xorImm` --- combined bitwise smoke. LOAD_IMM R0, 0xFF;
  *      AND_IMM R1, R0, 0x0F → R1 = 0x0F; OR_IMM R2, R1, 0xF0 → R2 = 0xFF;
  *      XOR_IMM R3, R2, 0xFF → R3 = 0 → REG_ZERO_FLAG set → BRANCH_ON REG_ZERO
  *      passes.
  *   9. `shift_left_logical` --- LOAD_IMM R0, 1; SHIFT R1, R0, left, 4; verify
  *      R1 == 16 via XOR_IMM + BRANCH_ON REG_ZERO.
  *   10. `shift_right_logical` --- LOAD_IMM R0, 256; SHIFT R1, R0, right, 4;
  *       verify R1 == 16.
  *   11. `shift_aright_signExtends` --- can't easily load a negative 32-bit
  *       value with LOAD_IMM (zero-extends 14-bit), so use DEC of 0 to land
  *       0xFFFFFFFF, then SHIFT R1, R0, aright, 4; result is 0xFFFFFFFF
  *       (arithmetic right shift of all-ones is all-ones). Verify via ADD_IMM
  *       R2, R1, 1 → wraps to 0 → REG_ZERO_FLAG set.
  *   12. `shift_aleft_traps` --- hand-crafted raw .dw with SHIFT arith=1+dir=0.
  *       Engine must trap with STATUS_TRAP (0x1F) per the C.9 D-stage
  *       SHIFT-aleft trap detection.
  *   13. `loadUse_hazard_correctValue` --- LOAD_IMM R0, 0x1234 followed
  *       immediately by MOV R3, R0. The W-to-R bypass + load-use stall must
  *       ensure R3 sees 0x1234 (not stale R0). Verify by XOR_IMM check.
  *
  * Run: `sbt "runMain mole.BitCycleEngineDataSim"`
  */
object BitCycleEngineDataSim extends App {

  private val cfg = MoleConfig(
    fabricFreqHz = 48 MHz,
    quarterPeriodCyclesReset = 6,
    programWordCount = 64,
    resultRingByteCount = 64,
    captureMaxBits = 65536,
    uartBaud = 1_000_000
  )

  private val resultBase: Int = cfg.programWordCount
  private val resultWordCount: Int = (cfg.resultRingByteCount + 3) / 4
  private val resultLimit: Int = resultBase + resultWordCount - 1

  private lazy val compiled =
    SimConfig.withWave.compile(BitCycleEngineTargetDut(cfg))

  // ------------------------------------------------------------------
  // Shared plumbing (mirrors BitCycleEngineSim).
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
    // DATA-group instructions set bit 31 → negative as a Scala Int. Convert
    // to unsigned Long so SpinalSim's range check accepts the full 32-bit
    // value.
    dut.io.loaderWrite.payload.data #= (word.toLong & 0xffffffffL)
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
      label: String,
      program: Seq[Int],
      expectStatus: Int,
      safetyLimit: Int = 4000
  ): Unit = {
    println(s"--- BitCycleEngineDataSim: $label ---")
    compiled.doSim(label) { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.clockDomain.waitSampling(5)
      load(dut, program)
      dut.io.programLength #= program.length
      dut.io.engineStart #= true
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
      println(s"$label OK ($cycles cycles, status=0x${h.status.toHexString})")
    }
  }

  import Instruction._

  // ------------------------------------------------------------------
  // Case 1: LOAD_IMM into all 8 registers, no trap.
  // ------------------------------------------------------------------
  private def runLoadImmAllEightRegsNoTrap(): Unit = {
    val program = (0 until 8).map { r =>
      // Distinct, non-zero immediates so REG_ZERO_FLAG stays clear at end.
      encode(LoadImm(dst = r, imm14 = 0x100 + r))
    } :+ encode(Halt(0))
    runSimpleProgram("loadImm_allEightRegs_noTrap", program, expectStatus = 0)
  }

  // ------------------------------------------------------------------
  // Case 2: LOAD_IMM 0 sets REG_ZERO_FLAG; BRANCH_ON REG_ZERO takes.
  // ------------------------------------------------------------------
  private def runLoadImmZeroSetsRegZeroFlag(): Unit = {
    val program = Seq(
      encode(LoadImm(dst = 3, imm14 = 0)), //                       PC=0
      encode(BranchOn(CondCode.regZero, pcRelOffset = +2)), //      PC=1
      encode(Halt(0x0a)), //                                        PC=2 fail
      encode(Halt(0x0a)), //                                        PC=3 fail
      encode(Halt(0x00)) //                                         PC=4 pass
    )
    runSimpleProgram(
      "loadImm_zero_setsRegZeroFlag",
      program,
      expectStatus = 0
    )
  }

  // ------------------------------------------------------------------
  // Case 3: LOAD_IMM nonzero CLEARS REG_ZERO_FLAG (write-once-overwritten).
  // ------------------------------------------------------------------
  private def runLoadImmNonzeroClearsRegZeroFlag(): Unit = {
    val program = Seq(
      encode(LoadImm(dst = 0, imm14 = 0)), //                       PC=0 sets
      encode(LoadImm(dst = 1, imm14 = 1)), //                       PC=1 clears
      encode(BranchOn(CondCode.notRegZero, pcRelOffset = +2)), //   PC=2
      encode(Halt(0x0a)), //                                        PC=3 fail
      encode(Halt(0x0a)), //                                        PC=4 fail
      encode(Halt(0x00)) //                                         PC=5 pass
    )
    runSimpleProgram(
      "loadImm_nonzero_clearsRegZeroFlag",
      program,
      expectStatus = 0
    )
  }

  // ------------------------------------------------------------------
  // Case 4: MOV propagates value. R3 := R0 = 0x1234; verify via XOR check.
  // ------------------------------------------------------------------
  private def runMovPropagatesValue(): Unit = {
    val program = Seq(
      encode(LoadImm(dst = 0, imm14 = 0x1234)), //                  PC=0
      encode(Mov(dst = 3, src = 0)), //                             PC=1
      encode(XorImm(dst = 4, src = 3, imm14 = 0x1234)), //          PC=2 → 0
      encode(BranchOn(CondCode.regZero, pcRelOffset = +2)), //      PC=3
      encode(Halt(0x0a)), //                                        PC=4 fail
      encode(Halt(0x0a)), //                                        PC=5 fail
      encode(Halt(0x00)) //                                         PC=6 pass
    )
    runSimpleProgram("mov_propagatesValue", program, expectStatus = 0)
  }

  // ------------------------------------------------------------------
  // Case 5: ADD_IMM positive + negative + zero. R1 := 10+5 = 15;
  // R2 := R1 + (-15) = 0; REG_ZERO_FLAG must be set after the second ADD.
  // ------------------------------------------------------------------
  private def runAddImmPosNegZero(): Unit = {
    val program = Seq(
      encode(LoadImm(dst = 0, imm14 = 10)), //                      PC=0
      encode(AddImm(dst = 1, src = 0, imm14 = 5)), //               PC=1 → 15
      encode(AddImm(dst = 2, src = 1, imm14 = -15)), //             PC=2 → 0
      encode(BranchOn(CondCode.regZero, pcRelOffset = +2)), //      PC=3
      encode(Halt(0x0a)), //                                        PC=4 fail
      encode(Halt(0x0a)), //                                        PC=5 fail
      encode(Halt(0x00)) //                                         PC=6 pass
    )
    runSimpleProgram(
      "addImm_positive_negative_zero",
      program,
      expectStatus = 0
    )
  }

  // ------------------------------------------------------------------
  // Case 6: DEC of zero wraps to 0xFFFFFFFF (non-zero); REG_ZERO_FLAG
  // must be CLEAR; BRANCH_ON NOT_REG_ZERO takes.
  // ------------------------------------------------------------------
  private def runDecWrapsAtZero(): Unit = {
    val program = Seq(
      encode(LoadImm(dst = 0, imm14 = 0)), //                       PC=0
      encode(Dec(reg = 0)), //                                      PC=1 → 0xFFFFFFFF
      encode(BranchOn(CondCode.notRegZero, pcRelOffset = +2)), //   PC=2
      encode(Halt(0x0a)), //                                        PC=3 fail
      encode(Halt(0x0a)), //                                        PC=4 fail
      encode(Halt(0x00)) //                                         PC=5 pass
    )
    runSimpleProgram("dec_wrapsAtZero", program, expectStatus = 0)
  }

  // ------------------------------------------------------------------
  // Case 7: DEC counted-loop idiom (spec §5.21).
  //   LOAD_IMM R6, 3
  //   loop: DEC R6
  //         BRANCH_ON NOT_REG_ZERO, loop
  //   HALT 0
  // ------------------------------------------------------------------
  private def runDecCountedLoop(): Unit = {
    val program = Seq(
      encode(LoadImm(dst = 6, imm14 = 3)), //                       PC=0
      encode(Dec(reg = 6)), //                                      PC=1 loop top
      encode(BranchOn(CondCode.notRegZero, pcRelOffset = -2)), //   PC=2 → PC=1
      encode(Halt(0x00)) //                                         PC=3
    )
    runSimpleProgram("dec_countedLoop", program, expectStatus = 0)
  }

  // ------------------------------------------------------------------
  // Case 8: AND/OR/XOR_IMM chain.
  //   LOAD_IMM R0, 0xFF
  //   AND_IMM  R1, R0, 0x0F  → 0x0F
  //   OR_IMM   R2, R1, 0xF0  → 0xFF
  //   XOR_IMM  R3, R2, 0xFF  → 0 (sets REG_ZERO_FLAG)
  //   BRANCH_ON REG_ZERO, pass
  // ------------------------------------------------------------------
  private def runAndOrXorChain(): Unit = {
    val program = Seq(
      encode(LoadImm(dst = 0, imm14 = 0xff)), //                    PC=0
      encode(AndImm(dst = 1, src = 0, imm14 = 0x0f)), //            PC=1 → 0x0F
      encode(OrImm(dst = 2, src = 1, imm14 = 0xf0)), //             PC=2 → 0xFF
      encode(XorImm(dst = 3, src = 2, imm14 = 0xff)), //            PC=3 → 0
      encode(BranchOn(CondCode.regZero, pcRelOffset = +2)), //      PC=4
      encode(Halt(0x0a)), //                                        PC=5 fail
      encode(Halt(0x0a)), //                                        PC=6 fail
      encode(Halt(0x00)) //                                         PC=7 pass
    )
    runSimpleProgram("andImm_orImm_xorImm_chain", program, expectStatus = 0)
  }

  // ------------------------------------------------------------------
  // Case 9: SHIFT left logical. R0 = 1; R1 := R0 << 4 = 16.
  // ------------------------------------------------------------------
  private def runShiftLeftLogical(): Unit = {
    val program = Seq(
      encode(LoadImm(dst = 0, imm14 = 1)), //                       PC=0
      encode(Shift(dst = 1, src = 0, arith = false, dir = false, shamt = 4)),
      // PC=1 → 16
      encode(XorImm(dst = 2, src = 1, imm14 = 16)), //              PC=2 → 0
      encode(BranchOn(CondCode.regZero, pcRelOffset = +2)), //      PC=3
      encode(Halt(0x0a)), //                                        PC=4 fail
      encode(Halt(0x0a)), //                                        PC=5 fail
      encode(Halt(0x00)) //                                         PC=6 pass
    )
    runSimpleProgram("shift_left_logical", program, expectStatus = 0)
  }

  // ------------------------------------------------------------------
  // Case 10: SHIFT right logical. R0 = 256; R1 := R0 >> 4 = 16.
  // ------------------------------------------------------------------
  private def runShiftRightLogical(): Unit = {
    val program = Seq(
      encode(LoadImm(dst = 0, imm14 = 256)), //                     PC=0
      encode(Shift(dst = 1, src = 0, arith = false, dir = true, shamt = 4)),
      // PC=1 → 16
      encode(XorImm(dst = 2, src = 1, imm14 = 16)), //              PC=2 → 0
      encode(BranchOn(CondCode.regZero, pcRelOffset = +2)), //      PC=3
      encode(Halt(0x0a)), //                                        PC=4 fail
      encode(Halt(0x0a)), //                                        PC=5 fail
      encode(Halt(0x00)) //                                         PC=6 pass
    )
    runSimpleProgram("shift_right_logical", program, expectStatus = 0)
  }

  // ------------------------------------------------------------------
  // Case 11: SHIFT arith right sign-extends. DEC of 0 → 0xFFFFFFFF;
  // aright by 4 → 0xFFFFFFFF; ADD_IMM 1 wraps to 0 → REG_ZERO_FLAG set.
  // ------------------------------------------------------------------
  private def runShiftArithRightSignExtends(): Unit = {
    val program = Seq(
      encode(LoadImm(dst = 0, imm14 = 0)), //                       PC=0
      encode(Dec(reg = 0)), //                                      PC=1 → 0xFFFFFFFF
      encode(Shift(dst = 1, src = 0, arith = true, dir = true, shamt = 4)),
      // PC=2 → 0xFFFFFFFF (sign-extended)
      encode(AddImm(dst = 2, src = 1, imm14 = 1)), //               PC=3 → 0
      encode(BranchOn(CondCode.regZero, pcRelOffset = +2)), //      PC=4
      encode(Halt(0x0a)), //                                        PC=5 fail
      encode(Halt(0x0a)), //                                        PC=6 fail
      encode(Halt(0x00)) //                                         PC=7 pass
    )
    runSimpleProgram("shift_aright_signExtends", program, expectStatus = 0)
  }

  // ------------------------------------------------------------------
  // Case 12: SHIFT aleft (arith=1, dir=0) is a D-stage trap.
  //
  // The assembler rejects this combination per spec §5.25, so we hand-
  // craft the raw word:
  //   [31:30] group  = 0b10  (DATA)
  //   [29:26] sub    = 0b0111 (SHIFT)
  //   [25:23] dst    = 0b001 (R1)
  //   [22:20] src    = 0b000 (R0)
  //   [19]    arith  = 1
  //   [18]    dir    = 0
  //   [17]    rsvd   = 0
  //   [16: 8] rsvd   = 0
  //   [ 7: 3] shamt  = 0b00001 (1)
  //   [ 2: 0] rsvd   = 0
  // ------------------------------------------------------------------
  private def runShiftAleftTraps(): Unit = {
    val word =
      (2 << 30) | // group=10
        (7 << 26) | // sub=0111
        (1 << 23) | // dst=R1
        (0 << 20) | // src=R0
        (1 << 19) | // arith=1
        (0 << 18) | // dir=0
        (1 << 3) //   shamt=1
    val program = Seq(
      word,
      encode(Halt(0x00)) // sentinel; should never execute
    )
    runSimpleProgram(
      "shift_aleft_traps",
      program,
      expectStatus = 0x1f
    )
  }

  // ------------------------------------------------------------------
  // Case 13: Load-use hazard. LOAD_IMM R0, V immediately followed by
  // MOV R3, R0 must produce R3 == V (regfile W-to-R bypass + load-use
  // stall path). Verify via XOR_IMM check.
  // ------------------------------------------------------------------
  private def runLoadUseHazardCorrectValue(): Unit = {
    val program = Seq(
      encode(LoadImm(dst = 0, imm14 = 0x2a5)), //                   PC=0
      encode(Mov(dst = 3, src = 0)), //                             PC=1 (hazard)
      encode(XorImm(dst = 4, src = 3, imm14 = 0x2a5)), //           PC=2 → 0
      encode(BranchOn(CondCode.regZero, pcRelOffset = +2)), //      PC=3
      encode(Halt(0x0a)), //                                        PC=4 fail
      encode(Halt(0x0a)), //                                        PC=5 fail
      encode(Halt(0x00)) //                                         PC=6 pass
    )
    runSimpleProgram(
      "loadUse_hazard_correctValue",
      program,
      expectStatus = 0
    )
  }

  // ------------------------------------------------------------------
  // Entry point.
  // ------------------------------------------------------------------

  runLoadImmAllEightRegsNoTrap()
  runLoadImmZeroSetsRegZeroFlag()
  runLoadImmNonzeroClearsRegZeroFlag()
  runMovPropagatesValue()
  runAddImmPosNegZero()
  runDecWrapsAtZero()
  runDecCountedLoop()
  runAndOrXorChain()
  runShiftLeftLogical()
  runShiftRightLogical()
  runShiftArithRightSignExtends()
  runShiftAleftTraps()
  runLoadUseHazardCorrectValue()
  println("BitCycleEngineDataSim: all 13 cases passed")
}
