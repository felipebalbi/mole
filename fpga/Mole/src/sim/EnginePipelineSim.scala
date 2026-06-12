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

  // CTRL still-reserved sub: group=0b01 sub=0b1000 → opcode pos 0x18 = 24.
  // 24 << 26 = 0x60000000. Per spec §4 the CTRL group reserves sub-codes
  // 0b1000..0b1111 — this picks the lowest reserved sub. After C.8 the
  // live CTRL opcodes (BRANCH_ON, WAIT_ON, FLAG_CLEAR, MARK, LOAD_TIMING)
  // all execute; this still-reserved sub is the only CTRL opcode left
  // that traps to STATUS_TRAP, so it is the canonical regression target
  // for the "non-HALT CTRL traps" case.
  private val ctrlReservedWord: Long = 0x60000000L

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
  // Case 3: Trap on non-HALT CTRL opcode (still-reserved CTRL sub 0b1000).
  // After C.8, BRANCH_ON / WAIT_ON / FLAG_CLEAR / MARK / LOAD_TIMING all
  // execute; CTRL sub-codes 0b1000..0b1111 remain reserved and must trap
  // to STATUS_TRAP. This case nails the "still-reserved CTRL sub"
  // regression with the lowest such sub.
  // --------------------------------------------------------------------------
  def caseTrapOnNonHalt(): Unit = {
    compileDut().doSim("trap-on-ctrl-opcode") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      initInputs(dut)
      dut.clockDomain.waitSampling(4)

      // CTRL sub=0b1000 (still reserved post-C.8) → must trap STATUS_TRAP.
      val mem = Array.fill(
        simCfg.programWordCount + (simCfg.resultRingByteCount + 3) / 4
      )(0L)
      mem(0) = ctrlReservedWord
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
      var firstRingAddr = 0L
      var secondRingWord = 0L
      fork {
        while (true) {
          if (
            dut.io.ringWrite.valid.toBoolean && dut.io.ringWrite.ready.toBoolean
          ) {
            ringWriteCount += 1
            if (ringWriteCount == 1) {
              firstRingWord = dut.io.ringWrite.payload.data.toLong & 0xffffffffL
              firstRingAddr = dut.io.ringWrite.payload.addr.toLong & 0xffffffffL
            } else if (ringWriteCount == 2) {
              secondRingWord =
                dut.io.ringWrite.payload.data.toLong & 0xffffffffL
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

      // Expect exactly 2 ring writes: the one-shot REVISION header at
      // resultBase, followed by the HALT word at resultLimit.
      // (Spec §11.1 ring layout.)
      assert(
        ringWriteCount == 2,
        s"[caseFetchSequence] ringWriteCount: expected 2 (REVISION + HALT) got $ringWriteCount"
      )

      val resultBase = simCfg.programWordCount.toLong
      assert(
        firstRingAddr == resultBase,
        s"[caseFetchSequence] first ring write addr: expected $resultBase (REVISION) got $firstRingAddr"
      )
      val expectedRevision =
        ((mole.Revision.major.toLong & 0xff) << 24) |
          ((mole.Revision.minor.toLong & 0xff) << 16) |
          (mole.Revision.patch.toLong & 0xffff)
      assert(
        firstRingWord == expectedRevision,
        f"[caseFetchSequence] REVISION word: expected 0x$expectedRevision%08x got 0x$firstRingWord%08x"
      )

      val ringStatus = ((secondRingWord >> 23) & 0x1fL).toInt
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
  // Case 7b: EMIT_BIT_IMM with capture=1 emits a CAPTURE record (spec §11.3).
  //
  // The test drives SDA externally to a known value, asserts EMIT_BIT_IMM
  // with capture=1 fires a CAPTURE word (tag=0b00 at [31:30], sampled SDA
  // at [0]) into the ring at ring slot 1 (just past REVISION). The R7
  // writeback also lands per §8 but is not the subject of this test.
  // --------------------------------------------------------------------------
  def caseCaptureRecordToRing(): Unit = {
    compileDut().doSim("capture-record-to-ring") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      initInputs(dut)
      dut.clockDomain.waitSampling(4)

      // EMIT_BIT_IMM tx=hiz capture=1: tx=10 (hiz) at [4:3], capture=1
      // at [0]. group=00 sub=0000 → opcode [31:26] = 0. Result:
      // (10 << 3) | 1 = 0x11.
      val emitBitHizCapture: Long = 0x00000011L
      val haltZero: Long = 0x40000000L

      val mem = Array.fill(
        simCfg.programWordCount + (simCfg.resultRingByteCount + 3) / 4
      )(0L)
      mem(0) = emitBitHizCapture
      mem(1) = haltZero
      forkSpramModel(dut, mem)

      // Hold SDA high (recessive in OD); engine should sample 1.
      // Hold SCL high so the engine's stretch-aware Q1→Q2 guard does
      // not wait for an external SCL release (the testbench is not
      // driving SCL).
      dut.io.sda.read #= true
      dut.io.scl.read #= true

      val ringRecords =
        scala.collection.mutable.ArrayBuffer.empty[(Long, Long)]
      fork {
        while (true) {
          if (
            dut.io.ringWrite.valid.toBoolean && dut.io.ringWrite.ready.toBoolean
          ) {
            ringRecords += ((
              dut.io.ringWrite.payload.addr.toLong & 0xffffL,
              dut.io.ringWrite.payload.data.toLong & 0xffffffffL
            ))
          }
          dut.clockDomain.waitSampling()
        }
      }

      dut.io.programLength #= 2
      dut.io.engineStart #= true

      waitFor(
        dut,
        2000,
        dut.io.halted.toBoolean,
        "[caseCaptureRecordToRing] engine did not halt"
      )
      dut.clockDomain.waitSampling(4)

      // Expect 3 ring writes: REVISION, CAPTURE, HALT.
      assert(
        ringRecords.size == 3,
        s"[caseCaptureRecordToRing] expected 3 ring writes (REVISION + CAPTURE + HALT), got " +
          s"${ringRecords.size}: " +
          ringRecords
            .map { case (a, d) => f"(0x$a%x→0x$d%08x)" }
            .mkString(", ")
      )

      val resultBase = simCfg.programWordCount.toLong
      val resultLimit = resultBase + ((simCfg.resultRingByteCount + 3) / 4) - 1

      // CAPTURE record at resultBase + 1 (REVISION sat at resultBase).
      val (capAddr, capWord) = ringRecords(1)
      assert(
        capAddr == resultBase + 1,
        s"[caseCaptureRecordToRing] CAPTURE addr: expected ${resultBase + 1} got $capAddr"
      )
      val capTag = ((capWord >> 30) & 0x3L).toInt
      val capSda = (capWord & 0x1L).toInt
      val capReserved = capWord & 0x3fff_fffeL // [29:1]
      assert(
        capTag == 0,
        s"[caseCaptureRecordToRing] CAPTURE tag: expected 0b00 got $capTag (word=0x${capWord.toHexString})"
      )
      assert(
        capSda == 1,
        s"[caseCaptureRecordToRing] CAPTURE sda: expected 1 (line held high) got $capSda"
      )
      assert(
        capReserved == 0,
        f"[caseCaptureRecordToRing] CAPTURE [29:1] reserved bits non-zero: 0x$capReserved%x"
      )

      // HALT record at resultLimit.
      val (haltAddr, haltW) = ringRecords(2)
      assert(
        haltAddr == resultLimit,
        s"[caseCaptureRecordToRing] HALT addr: expected $resultLimit got $haltAddr"
      )
      assert(
        ((haltW >> 30) & 0x3L) == 0x3,
        f"[caseCaptureRecordToRing] HALT tag: expected 0b11 got 0x$haltW%08x"
      )

      println("[caseCaptureRecordToRing] PASS")
    }
  }

  // --------------------------------------------------------------------------
  // Case 7c: WIRE capture writes R7 observably via REG_ZERO_FLAG +
  // BRANCH_ON REG_ZERO (regression: pre-Phase-F engines silently dropped
  // R7 writes from WIRE captures).
  //
  // Background: prior to Phase F the WIRE mini-FSM's combinational
  // `xWireWritesReg := True` pulse fired on the same cycle X was
  // halted by the FSM's own `xWireRunning` stall. The X→W flop
  // captured WRITES_REG=False on the cycle X eventually fired, so
  // R7 never received the sampled bit. The pre-existing target sim
  // documented this gap explicitly in its file header:
  //
  //   "v0.2 SAMPLE does not emit a ring CAPTURE record either"
  //   (BitCycleEngineTargetSim.scala:30)
  //
  // Phase F latched (writes_req, addr, data) into Regs that hold
  // across the X stall-release boundary, fixing R7 capture writes
  // AND adding CAPTURE-to-ring as a side effect (same data path).
  //
  // This test exercises the R7 path specifically: it drives SDA
  // high, runs EMIT_BIT_IMM capture=1 (samples 1 into R7), then
  // DEC R7 (R7 := 0), then BRANCH_ON REG_ZERO +1, then HALT 5
  // (fail path), HALT 0 (pass path). A pass HALT means R7 actually
  // received 1 and DEC wrapped it to 0; a fail HALT means R7 still
  // held its reset value and DEC produced 0xFFFFFFFF (non-zero).
  // --------------------------------------------------------------------------
  def caseCaptureWritesR7Observable(): Unit = {
    compileDut().doSim("capture-writes-r7-observable") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      initInputs(dut)
      dut.clockDomain.waitSampling(4)

      // Word 0: EMIT_BIT_IMM tx=hiz capture=1
      //   opcode group=00 sub=0000 → [31:26]=0
      //   tx=10 (hiz) at [4:3] → 0x10
      //   capture=1 at [0] → 0x01
      //   Final = 0x11.
      val emitBitHizCapture: Long = 0x00000011L

      // Word 1: DEC R7
      //   opcode group=10 sub=0011 → [31:26] = (2<<4)|3 = 0x23
      //   word top = 0x23 << 26 = 0x8C00_0000
      //   dst=R7 at [25:23] = 7<<23 = 0x0380_0000
      //   src=R7 at [22:20] = 7<<20 = 0x0070_0000
      //   Final = 0x8FF0_0000.
      val decR7: Long = 0x8ff00000L

      // Word 2: BRANCH_ON REG_ZERO, pcRelOffset=+1 (skip the fail HALT).
      //   opcode group=01 sub=0001 → [31:26] = (1<<4)|1 = 0x11
      //   word top = 0x11 << 26 = 0x4400_0000
      //   cond=10 (REG_ZERO) at [16:13] = 10<<13 = 0x0001_4000
      //     (cond-code list in EnginePipeline.scala xCondCode switch:
      //      0=ALWAYS, 1=MISMATCH, 2=NOT_MISMATCH, 3=START_SEEN,
      //      4=STOP_SEEN, 5=SDA_LOW, 6=SDA_HIGH, 7=SCL_HIGH,
      //      8=TIMEOUT, 9=NOT_TIMEOUT, 10=REG_ZERO, 11=NOT_REG_ZERO).
      //   pcRelOffset=+1 at [12:3] = 1<<3 = 0x8
      //   Final = 0x4401_4008.
      val branchOnRegZeroPlus1: Long = 0x44014008L

      // Word 3: HALT 5 (fail path: R7 was not 1)
      val haltFail: Long = 0x40000028L // status=5 at [7:3] = 5<<3 = 0x28
      // Word 4: HALT 0 (pass path: R7 was 1, DEC brought it to 0)
      val haltPass: Long = 0x40000000L

      val mem = Array.fill(
        simCfg.programWordCount + (simCfg.resultRingByteCount + 3) / 4
      )(0L)
      mem(0) = emitBitHizCapture
      mem(1) = decR7
      mem(2) = branchOnRegZeroPlus1
      mem(3) = haltFail
      mem(4) = haltPass
      forkSpramModel(dut, mem)

      // SDA high so EMIT_BIT_IMM samples 1 into R7. SCL high so the
      // engine's stretch-aware Q1→Q2 guard does not wait.
      dut.io.sda.read #= true
      dut.io.scl.read #= true

      dut.io.programLength #= 5
      dut.io.engineStart #= true

      waitFor(
        dut,
        2000,
        dut.io.halted.toBoolean,
        "[caseCaptureWritesR7Observable] engine did not halt"
      )

      assert(
        dut.io.haltStatus.toInt == 0,
        s"[caseCaptureWritesR7Observable] expected HALT status=0 (R7=1 after capture, DEC→0, " +
          s"REG_ZERO_FLAG set, branched over fail) got 0x${dut.io.haltStatus.toInt.toHexString} " +
          "— this means WIRE capture did NOT write R7 (regression of Phase F)"
      )

      println("[caseCaptureWritesR7Observable] PASS")
    }
  }

  // --------------------------------------------------------------------------
  // C.8 smoke cases — one per new CTRL opcode (BRANCH_ON, MARK, LOAD_TIMING).
  //
  // The full v0.2 ISA coverage lives in `BitCycleEngineSim` (C.8 sim suite,
  // landed in Commit 3). These cases prove the dispatch wiring in
  // EnginePipeline itself, so a regression in the X-stage CTRL block fails
  // here first.
  // --------------------------------------------------------------------------

  // BRANCH_ON ALWAYS, +1: PC=0 branches over a trap at PC=1, lands on HALT(0)
  // at PC=2. Verifies PC redirect + flush + re-fetch path.
  def caseBranchOnAlwaysSkip(): Unit = {
    compileDut().doSim("branch-on-always-skip") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      initInputs(dut)
      dut.clockDomain.waitSampling(4)

      val mem = Array.fill(
        simCfg.programWordCount + (simCfg.resultRingByteCount + 3) / 4
      )(0L)
      // BRANCH_ON ALWAYS, offset=+1: cond=0 at [16:13], offset=1 at [12:3].
      // group=01 sub=0001 [29:26], offset_field = 1 << 3 = 0x8.
      // 0x44000000 | 0x00000008 = 0x44000008.
      mem(0) = 0x44000008L
      // PC=1: still-reserved CTRL trap — must be skipped.
      mem(1) = ctrlReservedWord
      // PC=2: HALT(0).
      mem(2) = haltWord(0)
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

      dut.io.programLength #= 3
      dut.io.engineStart #= true
      dut.clockDomain.waitSampling()

      waitFor(
        dut,
        50,
        dut.io.halted.toBoolean,
        "[caseBranchOnAlwaysSkip] engine did not halt"
      )

      assert(
        dut.io.haltStatus.toInt == 0,
        s"[caseBranchOnAlwaysSkip] expected status 0 (branch landed on HALT(0))" +
          s", got 0x${dut.io.haltStatus.toInt.toHexString} — branch was not " +
          s"taken (would have hit STATUS_TRAP at PC=1)"
      )
      val ringTag = ((ringWordCapture >> 30) & 0x3L).toInt
      assert(
        ringTag == 3,
        s"[caseBranchOnAlwaysSkip] ring tag: expected 3 (HALT) got $ringTag"
      )

      println("[caseBranchOnAlwaysSkip] PASS")
    }
  }

  // MARK label=0x1234, HALT(0): verifies the 3-word MARK commit lands.
  // Checks the first ring word is the MARK header (tag=0b10, label=0x1234).
  def caseMarkBasic(): Unit = {
    compileDut().doSim("mark-basic") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      initInputs(dut)
      dut.clockDomain.waitSampling(4)

      val mem = Array.fill(
        simCfg.programWordCount + (simCfg.resultRingByteCount + 3) / 4
      )(0L)
      // MARK label=0x1234: group=01 sub=0110 [29:26], label at [16:3].
      // 0x4 << 26 = 0x18000000; sub=6 → (1<<30)|(6<<26) = 0x58000000.
      // label 0x1234 << 3 = 0x91A0. Final = 0x580091A0.
      mem(0) = 0x580091a0L
      mem(1) = haltWord(0)
      forkSpramModel(dut, mem)

      // Capture (addr, data) tuples on accepted ring handshakes so the
      // test can verify MARK records land at [resultBase, recordLimit] and
      // HALT lands at the reserved resultLimit slot (per C.8.1 fix).
      val ringRecords =
        scala.collection.mutable.ArrayBuffer.empty[(Long, Long)]
      fork {
        while (true) {
          if (
            dut.io.ringWrite.valid.toBoolean && dut.io.ringWrite.ready.toBoolean
          ) {
            ringRecords += ((
              dut.io.ringWrite.payload.addr.toLong & 0xffffL,
              dut.io.ringWrite.payload.data.toLong & 0xffffffffL
            ))
          }
          dut.clockDomain.waitSampling()
        }
      }

      dut.io.programLength #= 2
      dut.io.engineStart #= true
      dut.clockDomain.waitSampling()

      waitFor(
        dut,
        80,
        dut.io.halted.toBoolean,
        "[caseMarkBasic] engine did not halt"
      )

      // Expect 5 ring writes: REVISION, MARK header, ts_lo, ts_hi, HALT.
      // (Spec §11.1: REVISION at resultBase, record stream after.)
      assert(
        ringRecords.size == 5,
        s"[caseMarkBasic] expected 5 ring writes (revision + mark x3 + halt), got " +
          s"${ringRecords.size}: " +
          ringRecords
            .map { case (a, d) => f"(0x$a%x→0x$d%08x)" }
            .mkString(", ")
      )
      // ---- REVISION at resultBase --------------------------------------
      val resultBase = simCfg.programWordCount.toLong
      val resultLimit = resultBase + ((simCfg.resultRingByteCount + 3) / 4) - 1
      val (revAddr, revWord) = ringRecords(0)
      assert(
        revAddr == resultBase,
        s"[caseMarkBasic] revision addr: expected 0x${resultBase.toHexString}" +
          f" (resultBase), got 0x$revAddr%x"
      )
      val expectedRevision =
        ((mole.Revision.major.toLong & 0xff) << 24) |
          ((mole.Revision.minor.toLong & 0xff) << 16) |
          (mole.Revision.patch.toLong & 0xffff)
      assert(
        revWord == expectedRevision,
        f"[caseMarkBasic] revision word: expected 0x$expectedRevision%08x got 0x$revWord%08x"
      )
      // ---- MARK records: header, ts_lo, ts_hi at resultBase + {1,2,3}. ----
      val (mhdrAddr, markHeader) = ringRecords(1)
      assert(
        mhdrAddr == resultBase + 1,
        s"[caseMarkBasic] mark header addr: expected 0x${(resultBase + 1).toHexString}" +
          f" (resultBase+1, post-REVISION), got 0x$mhdrAddr%x"
      )
      val markTag = ((markHeader >> 30) & 0x3L).toInt
      val markLabel = (markHeader & 0x3fffL).toInt
      assert(
        markTag == 0x2,
        s"[caseMarkBasic] mark header tag: expected 0b10 (2), got $markTag " +
          s"(word=0x${markHeader.toHexString})"
      )
      assert(
        markLabel == 0x1234,
        s"[caseMarkBasic] mark label: expected 0x1234 got " +
          f"0x$markLabel%x (word=0x${markHeader.toHexString})"
      )
      val (mtloAddr, _) = ringRecords(2)
      assert(
        mtloAddr == resultBase + 2,
        s"[caseMarkBasic] ts_lo addr: expected 0x${(resultBase + 2).toHexString}" +
          f", got 0x$mtloAddr%x"
      )
      val (mthiAddr, _) = ringRecords(3)
      assert(
        mthiAddr == resultBase + 3,
        s"[caseMarkBasic] ts_hi addr: expected 0x${(resultBase + 3).toHexString}" +
          f", got 0x$mthiAddr%x"
      )
      // ---- HALT record at the reserved resultLimit slot. ------------------
      val (haltAddr, haltW) = ringRecords(4)
      assert(
        haltAddr == resultLimit,
        s"[caseMarkBasic] halt addr: expected 0x${resultLimit.toHexString}" +
          f" (resultLimit), got 0x$haltAddr%x"
      )
      assert(
        ((haltW >> 30) & 0x3L) == 0x3,
        s"[caseMarkBasic] halt word tag: expected 0b11, got " +
          f"0x$haltW%08x"
      )

      println("[caseMarkBasic] PASS")
    }
  }

  // LOAD_TIMING reg=0, divider=12 then HALT. Verifies the opcode commits
  // without trapping and the engine reaches HALT(0).
  def caseLoadTimingBasic(): Unit = {
    compileDut().doSim("load-timing-basic") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      initInputs(dut)
      dut.clockDomain.waitSampling(4)

      val mem = Array.fill(
        simCfg.programWordCount + (simCfg.resultRingByteCount + 3) / 4
      )(0L)
      // LOAD_TIMING reg=0, divider=12: group=01 sub=0111 [29:26], reg at
      // [19:17], divider at [16:3]. sub=7 → (1<<30)|(7<<26) = 0x5C000000.
      // reg=0 contributes nothing; divider=12 << 3 = 0x60. Final = 0x5C000060.
      mem(0) = 0x5c000060L
      mem(1) = haltWord(0)
      forkSpramModel(dut, mem)

      dut.io.programLength #= 2
      dut.io.engineStart #= true
      dut.clockDomain.waitSampling()

      waitFor(
        dut,
        50,
        dut.io.halted.toBoolean,
        "[caseLoadTimingBasic] engine did not halt"
      )
      assert(
        dut.io.haltStatus.toInt == 0,
        s"[caseLoadTimingBasic] expected status 0, got " +
          s"0x${dut.io.haltStatus.toInt.toHexString} — LOAD_TIMING trapped " +
          s"or never reached HALT(0)"
      )

      println("[caseLoadTimingBasic] PASS")
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
    caseCaptureRecordToRing()
    caseCaptureWritesR7Observable()
    caseBranchOnAlwaysSkip()
    caseMarkBasic()
    caseLoadTimingBasic()
    println("EnginePipelineSim: all 12 cases passed")
  }
}
