package mole

import spinal.core._
import spinal.core.sim._
import spinal.lib._

/** Companion sim for [[EnginePipeline]].
  *
  * Exercises the 5-stage (F1/F2/D/R/X/W) pipeline scaffold with a sim-side
  * SPRAM model. Six cases covering:
  *   1. HALT status=0 → canonical HALT word 0xC000_0000.
  *   2. HALT status=5 → HALT word with status=5 at [27:23].
  *   3. Non-HALT opcode (EMIT_BIT_IMM) → trap, haltStatus=0x1F.
  *   4. Reserved opcode (LOOP group) → trap, haltStatus=0x1F.
  *   5. Fetch sequence: four HALT words; engine halts on the first.
  *   6. programLength=0 → engine stays idle (no fetch attempted).
  *
  * The sim drives `io.spramRead` / `io.spramResp` and captures `io.ringWrite`
  * directly, bypassing the hardware `SpramController`.
  *
  * Run: `sbt "runMain mole.EnginePipelineSim"`
  */
object EnginePipelineSim {

  // Small config: keeps elaboration fast and addresses narrow.
  private def simCfg = MoleConfig(
    fabricFreqHz = 48 MHz,
    quarterPeriodCyclesReset = 6,
    programWordCount = 64,
    resultRingByteCount = 64,
    captureMaxBits = 65536,
    uartBaud = 1_000_000
  )

  // ---- HALT instruction encodings (spec §5.10) ----------------------------
  // [31:26] = group=0b01 sub=0b0000 → 0x40000000 base
  // [7:3]   = status field
  private def haltWord(status: Int): Long =
    (0x40000000L | (status.toLong << 3)) & 0xffffffffL

  // EMIT_BIT_IMM: group=0b00 sub=0b0000 tx=recessive (01) → [4:3]=01 → 0x08
  // C.7: EMIT_BIT_IMM now EXECUTES (not traps). Used in the emit-bit test below.
  private val emitBitImmWord: Long = 0x00000008L

  // CTRL.BRANCH_ON: group=0b01 sub=0b0001 → opcode pos 0x11 = 17
  // 17 << 26 = 0x44000000. Still traps in C.7 (CTRL opcodes not yet implemented).
  private val branchOnWord: Long = 0x44000000L

  // LOOP group reserved: group=0b11 sub=0b0000 → 0xFC000000
  private val loopGroupWord: Long = 0xfc000000L

  // STATUS_TRAP expected value (spec §11: 0x1F)
  private val statusTrap: Int = 0x1f

  // Expected HALT word tag pattern: tag=0b11 at [31:30], rest zero except
  // overflow/mismatch/status fields.
  // For status s with overflow=0 mismatch=0:
  //   [31:30]=11 [29]=0 [28]=0 [27:23]=s [22:0]=0
  private def expectedHaltRingWord(status: Int): Long =
    0xc0000000L | (status.toLong << 23)

  // ---- Compile the DUT once and reuse ------------------------------------
  private def compileDut() =
    SimConfig.withWave.compile(EnginePipeline(simCfg))

  // ---- Sim helpers -------------------------------------------------------

  /** Initialise all inputs to safe idle values. */
  private def initInputs(dut: EnginePipeline): Unit = {
    dut.io.engineStart #= false
    dut.io.programLength #= 0
    dut.io.sdaSampled #= true // SDA released (recessive)
    dut.io.sclSampled #= true // SCL released
    dut.io.sda.read #= true // SDA pad read-back
    dut.io.scl.read #= true // SCL pad read-back
    dut.io.spramRead.ready #= true
    dut.io.spramResp.valid #= false
    dut.io.spramResp.payload #= 0
    dut.io.ringWrite.ready #= true
  }

  /** Run the SPRAM response model:
    *   - Each cycle, check if `spramRead.fire` happened last cycle and generate
    *     a valid response one cycle later.
    *   - The sim provides a `mem` array indexed by SPRAM word address.
    *
    * Runs in a forked thread for the duration of the sim.
    */
  private def forkSpramModel(
      dut: EnginePipeline,
      mem: Array[Long]
  ): Unit = fork {
    var pendingResp = false
    var pendingData = 0L
    while (true) {
      // Apply response from last cycle's read, if any.
      if (pendingResp) {
        dut.io.spramResp.valid #= true
        dut.io.spramResp.payload #= pendingData
        pendingResp = false
      } else {
        dut.io.spramResp.valid #= false
      }
      // Check if a read command fires this cycle.
      if (
        dut.io.spramRead.valid.toBoolean && dut.io.spramRead.ready.toBoolean
      ) {
        val addr = dut.io.spramRead.payload.toInt
        pendingData = if (addr < mem.length) mem(addr) else 0L
        pendingResp = true
      }
      dut.clockDomain.waitSampling()
    }
  }

  /** Wait up to `maxCycles` clock cycles for `cond` to become true. Fails with
    * an assertion error if the timeout expires.
    */
  private def waitFor(
      dut: EnginePipeline,
      maxCycles: Int,
      cond: => Boolean,
      msg: String
  ): Unit = {
    var count = 0
    while (!cond && count < maxCycles) {
      dut.clockDomain.waitSampling()
      count += 1
    }
    assert(cond, s"$msg (waited $maxCycles cycles)")
  }

  // --------------------------------------------------------------------------
  // Case 1: HALT status=0
  // --------------------------------------------------------------------------
  def caseHaltStatusZero(): Unit = {
    compileDut().doSim("halt-status-zero") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      initInputs(dut)
      dut.clockDomain.waitSampling(4)

      // SPRAM[0] = HALT status=0
      val mem = Array.fill(
        simCfg.programWordCount + (simCfg.resultRingByteCount + 3) / 4
      )(0L)
      mem(0) = haltWord(0)
      forkSpramModel(dut, mem)

      var ringWordCapture = 0L
      var ringAddrCapture = 0L
      val ringCapture = fork {
        while (true) {
          if (
            dut.io.ringWrite.valid.toBoolean && dut.io.ringWrite.ready.toBoolean
          ) {
            ringWordCapture = dut.io.ringWrite.payload.data.toLong & 0xffffffffL
            ringAddrCapture = dut.io.ringWrite.payload.addr.toLong
          }
          dut.clockDomain.waitSampling()
        }
      }

      dut.io.programLength #= 1
      dut.io.engineStart #= true
      dut.clockDomain.waitSampling()

      waitFor(
        dut,
        30,
        dut.io.halted.toBoolean,
        "[caseHaltStatusZero] engine did not halt"
      )

      assert(
        dut.io.haltStatus.toInt == 0,
        s"[caseHaltStatusZero] haltStatus: expected 0 got ${dut.io.haltStatus.toInt}"
      )

      val expectedWord = expectedHaltRingWord(0)
      assert(
        (ringWordCapture & 0xffffffffL) == expectedWord,
        f"[caseHaltStatusZero] ringWord: expected 0x${expectedWord}%08X got 0x${ringWordCapture & 0xffffffffL}%08X"
      )

      println("[caseHaltStatusZero] PASS")
    }
  }

  // --------------------------------------------------------------------------
  // Case 2: HALT status=5
  // --------------------------------------------------------------------------
  def caseHaltStatusNonZero(): Unit = {
    compileDut().doSim("halt-status-nonzero") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      initInputs(dut)
      dut.clockDomain.waitSampling(4)

      // HALT status=5: instruction [7:3] = 5 → 5 << 3 = 0x28
      val mem = Array.fill(
        simCfg.programWordCount + (simCfg.resultRingByteCount + 3) / 4
      )(0L)
      mem(0) = haltWord(5)
      forkSpramModel(dut, mem)

      var ringWordCapture = 0L
      fork {
        while (true) {
          if (
            dut.io.ringWrite.valid.toBoolean && dut.io.ringWrite.ready.toBoolean
          ) {
            ringWordCapture = dut.io.ringWrite.payload.data.toLong & 0xffffffffL
          }
          dut.clockDomain.waitSampling()
        }
      }

      dut.io.programLength #= 1
      dut.io.engineStart #= true
      dut.clockDomain.waitSampling()

      waitFor(
        dut,
        30,
        dut.io.halted.toBoolean,
        "[caseHaltStatusNonZero] engine did not halt"
      )

      assert(
        dut.io.haltStatus.toInt == 5,
        s"[caseHaltStatusNonZero] haltStatus: expected 5 got ${dut.io.haltStatus.toInt}"
      )

      // ring word [27:23] should be 5
      val ringStatus = ((ringWordCapture >> 23) & 0x1f).toInt
      assert(
        ringStatus == 5,
        s"[caseHaltStatusNonZero] ring word status field [27:23]: expected 5 got $ringStatus"
      )

      val expectedWord = expectedHaltRingWord(5)
      assert(
        (ringWordCapture & 0xffffffffL) == expectedWord,
        f"[caseHaltStatusNonZero] ringWord: expected 0x${expectedWord}%08X got 0x${ringWordCapture & 0xffffffffL}%08X"
      )

      println("[caseHaltStatusNonZero] PASS")
    }
  }

  // --------------------------------------------------------------------------
  // Case 3: Trap on non-HALT CTRL opcode (BRANCH_ON, not yet implemented C.7)
  // --------------------------------------------------------------------------
  def caseTrapOnNonHalt(): Unit = {
    compileDut().doSim("trap-on-ctrl-opcode") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      initInputs(dut)
      dut.clockDomain.waitSampling(4)

      // CTRL.BRANCH_ON → should trap in C.7 (CTRL not yet implemented)
      val mem = Array.fill(
        simCfg.programWordCount + (simCfg.resultRingByteCount + 3) / 4
      )(0L)
      mem(0) = branchOnWord
      forkSpramModel(dut, mem)

      var ringWordCapture = 0L
      fork {
        while (true) {
          if (
            dut.io.ringWrite.valid.toBoolean && dut.io.ringWrite.ready.toBoolean
          ) {
            ringWordCapture = dut.io.ringWrite.payload.data.toLong & 0xffffffffL
          }
          dut.clockDomain.waitSampling()
        }
      }

      dut.io.programLength #= 1
      dut.io.engineStart #= true
      dut.clockDomain.waitSampling()

      waitFor(
        dut,
        30,
        dut.io.halted.toBoolean,
        "[caseTrapOnNonHalt] engine did not halt"
      )

      assert(
        dut.io.haltStatus.toInt == statusTrap,
        s"[caseTrapOnNonHalt] haltStatus: expected 0x1F got ${dut.io.haltStatus.toInt}"
      )

      // ring word tag=11, status=0x1F
      val ringTag = ((ringWordCapture >> 30) & 0x3L).toInt
      val ringStatus = ((ringWordCapture >> 23) & 0x1fL).toInt
      assert(
        ringTag == 3,
        s"[caseTrapOnNonHalt] ring tag: expected 3 got $ringTag"
      )
      assert(
        ringStatus == statusTrap,
        s"[caseTrapOnNonHalt] ring status: expected 0x1F got 0x${ringStatus.toHexString}"
      )

      println("[caseTrapOnNonHalt] PASS")
    }
  }

  // --------------------------------------------------------------------------
  // Case 4: Trap on reserved opcode (LOOP group)
  // --------------------------------------------------------------------------
  def caseTrapOnReservedOpcode(): Unit = {
    compileDut().doSim("trap-on-reserved-opcode") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      initInputs(dut)
      dut.clockDomain.waitSampling(4)

      // LOOP group word (fully reserved → trap)
      val mem = Array.fill(
        simCfg.programWordCount + (simCfg.resultRingByteCount + 3) / 4
      )(0L)
      mem(0) = loopGroupWord
      forkSpramModel(dut, mem)

      var ringWordCapture = 0L
      fork {
        while (true) {
          if (
            dut.io.ringWrite.valid.toBoolean && dut.io.ringWrite.ready.toBoolean
          ) {
            ringWordCapture = dut.io.ringWrite.payload.data.toLong & 0xffffffffL
          }
          dut.clockDomain.waitSampling()
        }
      }

      dut.io.programLength #= 1
      dut.io.engineStart #= true
      dut.clockDomain.waitSampling()

      waitFor(
        dut,
        30,
        dut.io.halted.toBoolean,
        "[caseTrapOnReservedOpcode] engine did not halt"
      )

      assert(
        dut.io.haltStatus.toInt == statusTrap,
        s"[caseTrapOnReservedOpcode] haltStatus: expected 0x1F got ${dut.io.haltStatus.toInt}"
      )

      val ringTag = ((ringWordCapture >> 30) & 0x3L).toInt
      val ringStatus = ((ringWordCapture >> 23) & 0x1fL).toInt
      assert(
        ringTag == 3,
        s"[caseTrapOnReservedOpcode] ring tag: expected 3 got $ringTag"
      )
      assert(
        ringStatus == statusTrap,
        s"[caseTrapOnReservedOpcode] ring status expected 0x1F got 0x${ringStatus.toHexString}"
      )

      println("[caseTrapOnReservedOpcode] PASS")
    }
  }

  // --------------------------------------------------------------------------
  // Case 5: Fetch sequence — engine halts on first HALT, ignores subsequent
  // --------------------------------------------------------------------------
  def caseFetchSequence(): Unit = {
    compileDut().doSim("fetch-sequence") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      initInputs(dut)
      dut.clockDomain.waitSampling(4)

      // Four consecutive HALT instructions with different statuses.
      // Engine must halt on SPRAM[0] (status=5) and not proceed to SPRAM[1..3].
      val mem = Array.fill(
        simCfg.programWordCount + (simCfg.resultRingByteCount + 3) / 4
      )(0L)
      mem(0) = haltWord(5) // first HALT: status=5
      mem(1) = haltWord(6)
      mem(2) = haltWord(7)
      mem(3) = haltWord(8)
      forkSpramModel(dut, mem)

      var ringWriteCount = 0
      var firstRingWord = 0L
      fork {
        while (true) {
          if (
            dut.io.ringWrite.valid.toBoolean && dut.io.ringWrite.ready.toBoolean
          ) {
            ringWriteCount += 1
            if (ringWriteCount == 1) {
              firstRingWord = dut.io.ringWrite.payload.data.toLong & 0xffffffffL
            }
          }
          dut.clockDomain.waitSampling()
        }
      }

      dut.io.programLength #= 4
      dut.io.engineStart #= true
      dut.clockDomain.waitSampling()

      waitFor(
        dut,
        40,
        dut.io.halted.toBoolean,
        "[caseFetchSequence] engine did not halt"
      )

      // Wait a few more cycles to confirm no additional ring writes fire.
      dut.clockDomain.waitSampling(10)

      assert(
        dut.io.haltStatus.toInt == 5,
        s"[caseFetchSequence] haltStatus: expected 5 (first HALT) got ${dut.io.haltStatus.toInt}"
      )

      assert(
        ringWriteCount == 1,
        s"[caseFetchSequence] ringWriteCount: expected 1 got $ringWriteCount"
      )

      val ringStatus = ((firstRingWord >> 23) & 0x1fL).toInt
      assert(
        ringStatus == 5,
        s"[caseFetchSequence] ring status: expected 5 got $ringStatus"
      )

      println("[caseFetchSequence] PASS")
    }
  }

  // --------------------------------------------------------------------------
  // Case 6: programLength=0 → engine stays idle (no fetch)
  // --------------------------------------------------------------------------
  def caseProgramLengthZero(): Unit = {
    compileDut().doSim("program-length-zero") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      initInputs(dut)
      dut.clockDomain.waitSampling(4)

      // Populate SPRAM with a HALT word, but set programLength=0.
      // The engine should not start fetching (no valid program).
      val mem = Array.fill(
        simCfg.programWordCount + (simCfg.resultRingByteCount + 3) / 4
      )(0L)
      mem(0) = haltWord(0)
      forkSpramModel(dut, mem)

      var readFired = false
      fork {
        while (true) {
          if (
            dut.io.spramRead.valid.toBoolean && dut.io.spramRead.ready.toBoolean
          ) {
            readFired = true
          }
          dut.clockDomain.waitSampling()
        }
      }

      // programLength=0: the engine treats this as "no program"; F1 will be
      // gated by the condition (PC >= programLength → halted by guard) or
      // alternatively the engine simply won't fetch if programLength=0 since
      // no valid PC is in range. The design choice: programLength=0 means
      // the engine will fetch from PC=0 but immediately encounter out-of-bounds
      // (PC >= 0 for programLength=0) — we stop fetching when programLength=0.
      // engineStart is asserted but programLength=0 means fetch is gated.
      dut.io.programLength #= 0
      dut.io.engineStart #= true
      dut.clockDomain.waitSampling(30)

      // With programLength=0 and engineStart=true the engine should not
      // issue any SPRAM reads (F1 gated). Confirm engine stays non-halted
      // (no instruction executed, no trap) and no reads fired.
      assert(
        !readFired,
        "[caseProgramLengthZero] spramRead fired with programLength=0 (should not fetch)"
      )
      assert(
        !dut.io.halted.toBoolean,
        "[caseProgramLengthZero] engine halted unexpectedly with programLength=0"
      )

      println("[caseProgramLengthZero] PASS")
    }
  }

  // --------------------------------------------------------------------------
  // Case 7: EMIT_BIT_IMM drives SCL low (basic WIRE opcode execution test)
  // --------------------------------------------------------------------------
  def caseEmitBitDrivesSclLow(): Unit = {
    compileDut().doSim("emit-bit-drives-scl-low") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      initInputs(dut)
      dut.clockDomain.waitSampling(4)

      // Program: EMIT_BIT_IMM dominant (tx=00=dominant, no flags); HALT 0
      // EMIT_BIT_IMM dominant: all zeros = 0x00000000.
      // HALT status=0: 0x40000000.
      // Default BUS_MODE = i2c (OD). Dominant → sclDriveLow=True.
      val emitBitDom: Long = 0x00000000L // EMIT_BIT_IMM dominant, no flags
      val haltZero: Long = 0x40000000L // HALT status=0

      val mem = Array.fill(
        simCfg.programWordCount + (simCfg.resultRingByteCount + 3) / 4
      )(0L)
      mem(0) = emitBitDom
      mem(1) = haltZero
      forkSpramModel(dut, mem)

      var sclEverLow = false
      fork {
        while (true) {
          if (dut.io.scl.driveLow.toBoolean) sclEverLow = true
          dut.clockDomain.waitSampling()
        }
      }

      dut.io.programLength #= 2 // EMIT_BIT + HALT
      dut.io.engineStart #= true

      waitFor(
        dut,
        200,
        dut.io.halted.toBoolean,
        "[caseEmitBitDrivesSclLow] engine did not halt"
      )

      assert(
        sclEverLow,
        "[caseEmitBitDrivesSclLow] EMIT_BIT_IMM never drove SCL low"
      )
      assert(
        dut.io.haltStatus.toInt == 0,
        s"[caseEmitBitDrivesSclLow] wrong halt status: ${dut.io.haltStatus.toInt}"
      )

      println("[caseEmitBitDrivesSclLow] PASS")
    }
  }

  // --------------------------------------------------------------------------
  // Entry point
  // --------------------------------------------------------------------------
  def main(args: Array[String]): Unit = {
    caseHaltStatusZero()
    caseHaltStatusNonZero()
    caseTrapOnNonHalt()
    caseTrapOnReservedOpcode()
    caseFetchSequence()
    caseProgramLengthZero()
    caseEmitBitDrivesSclLow()
    println("EnginePipelineSim: all 7 cases passed")
  }
}
