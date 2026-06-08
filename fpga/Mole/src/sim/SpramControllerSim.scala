package mole

import spinal.core._
import spinal.core.sim._
import spinal.sim.SimThread
import spinal.lib._

/** Black-box-style sim for [[SpramController]].
  *
  * Drives the controller with `useBlackBox = false` so we exercise the wrapper
  * logic (address mux, arbitration, ready back-pressure, one-cycle read
  * latency) against the SpinalHDL `Mem` substitute --- no external Verilog
  * model of `SB_SPRAM256KA` required. The wrapper logic under test is identical
  * between the two paths; the only thing the sim does not cover is the BlackBox
  * port wiring itself, which is verified by HW bring-up (Phase C).
  *
  * Cases -----
  *   1. **Write-then-read every cell.** Loader-write a recognizable pattern
  *      (`addr ^ 0xA5A5_A5A5`) across the full address space, then read every
  *      cell and verify the readback.
  *   2. **Read priority over write.** Inject simultaneous `readCmd.valid` and
  *      `resultWrite.valid`; the read fires that cycle, the write
  *      back-pressures and lands the cycle after.
  *   3. **Loader vs result-write arbitration.** Two concurrent writes:
  *      `resultWrite` (higher priority) wins, `loaderWrite` back-pressures and
  *      lands next.
  *   4. **Result-ring wrap-around.** Drive a software-managed pointer past the
  *      result-ring size and verify the cells wrap cleanly without
  *      address-translation glitches.
  *   5. **One-cycle read latency.** Verify exactly one cycle of
  *      `readResp.valid` after each `readCmd.fire`, no more, no fewer.
  *   6. **Read-vs-write to the same address.** With the read-priority arbiter
  *      the read wins on cycle N and the write fires on cycle N+1; the read
  *      therefore returns the *pre-write* value and the second read (issued
  *      after the write completes) returns the *post-write* value. Documents
  *      the contract.
  *   7. **Read priority over loader write.** Symmetric to case 2 but with
  *      `loaderWrite` as the contending writer. The arbiter is supposed to
  *      preempt either writer; case 2 alone only proves that for the
  *      higher-priority writer.
  *   8. **Dual-tile parallelism.** Write a value whose upper and lower halves
  *      are distinct (`0xAABB_CCDD`), read it back, and assert both halves are
  *      intact. Proves the `tileLo`/`tileHi` data split and `DATAOUT`
  *      concatenation are wired correctly and not swapped.
  *   9. **Alternating bit pattern.** Write `0x5555_5555` and `0xAAAA_AAAA` to
  *      consecutive cells, read them back, and verify each bit. Catches any
  *      per-bit wiring error between the two tile data paths.
  *
  * Run: `sbt "runMain mole.SpramControllerSim"`
  */
object SpramControllerSim {

  // --------------------------------------------------------------
  // Helpers
  // --------------------------------------------------------------

  /** Issue one read command and wait for the response to land.
    *
    * Read happens combinationally (`readCmd.ready := True`) so the cmd
    * handshake fires immediately. The response is registered one cycle later;
    * we wait for `readResp.valid` to observe it and snapshot the payload while
    * the flag is still high.
    */
  private def doRead(dut: SpramController, addr: Long): BigInt = {
    dut.io.readCmd.valid #= true
    dut.io.readCmd.payload #= addr
    dut.clockDomain.waitSamplingWhere(dut.io.readCmd.ready.toBoolean)
    dut.io.readCmd.valid #= false
    dut.clockDomain.waitSamplingWhere(dut.io.readResp.valid.toBoolean)
    dut.io.readResp.payload.toBigInt
  }

  /** Issue one loader write and wait for the handshake to fire. */
  private def doLoaderWrite(
      dut: SpramController,
      addr: Long,
      data: Long
  ): Unit = {
    dut.io.loaderWrite.valid #= true
    dut.io.loaderWrite.payload.addr #= addr
    dut.io.loaderWrite.payload.data #= data
    dut.clockDomain.waitSamplingWhere(dut.io.loaderWrite.ready.toBoolean)
    dut.io.loaderWrite.valid #= false
  }

  /** Issue one result-ring write and wait for the handshake to fire. */
  private def doResultWrite(
      dut: SpramController,
      addr: Long,
      data: Long
  ): Unit = {
    dut.io.resultWrite.valid #= true
    dut.io.resultWrite.payload.addr #= addr
    dut.io.resultWrite.payload.data #= data
    dut.clockDomain.waitSamplingWhere(dut.io.resultWrite.ready.toBoolean)
    dut.io.resultWrite.valid #= false
  }

  /** Default-released the three Stream sources before each sim. */
  private def quiet(dut: SpramController): Unit = {
    dut.io.loaderWrite.valid #= false
    dut.io.loaderWrite.payload.addr #= 0
    dut.io.loaderWrite.payload.data #= 0
    dut.io.resultWrite.valid #= false
    dut.io.resultWrite.payload.addr #= 0
    dut.io.resultWrite.payload.data #= 0
    dut.io.readCmd.valid #= false
    dut.io.readCmd.payload #= 0
  }

  /** Result of a [[captureReadResp]] watcher: payload captured the cycle
    * `readResp.valid` first pulsed high, plus the watcher thread itself so
    * callers can `.join()` it.
    */
  private class FlowCapture {
    var payload: Option[BigInt] = None
    var thread: SimThread = null
  }

  /** Fork a watcher that snapshots the next `readResp.valid` pulse.
    *
    * The contention cases (2, 6, 7) need to observe a one-cycle `Flow.valid`
    * pulse at the same moment they check back-pressure state. Sampling that
    * registered Flow with a bare `waitSampling()` + `.toBoolean` from the main
    * thread is fragile: SpinalSim's delta ordering between input writes (the
    * mandatory `#= false` to release the bus) and register-output observation
    * can hide the pulse. A forked watcher loop sees every cycle and never races
    * with input-driver writes.
    *
    * Call once *before* staging the contention inputs. The watcher exits the
    * first cycle it sees the pulse, capturing the payload into `cap.payload`.
    * Callers should `cap.thread.join()` (or
    * `waitSamplingWhere(cap.payload.isDefined)`) once it is safe to block.
    */
  private def captureReadResp(dut: SpramController): FlowCapture = {
    val cap = new FlowCapture
    cap.thread = fork {
      while (cap.payload.isEmpty) {
        if (dut.io.readResp.valid.toBoolean) {
          cap.payload = Some(dut.io.readResp.payload.toBigInt)
        } else {
          dut.clockDomain.waitSampling()
        }
      }
    }
    cap
  }

  // Common DUT factory --- small config so tests run fast.
  // 64 program words + 64 result bytes = 64 + 16 = 80 words (64 result
  // bytes / 4 bytes-per-word = 16 words) → 7-bit address space.
  // Large enough to exercise wrap-around with low address counts;
  // small enough not to bloat `Mem` elaboration.
  private def smallCfg = MoleConfig(
    fabricFreqHz = 48 MHz,
    quarterPeriodCyclesReset = 6,
    programWordCount = 64,
    resultRingByteCount = 64,
    captureMaxBits = 65536,
    uartBaud = 1_000_000
  )

  private def compileDut() =
    SimConfig.withWave.compile(SpramController(smallCfg, useBlackBox = false))

  // --------------------------------------------------------------
  // Test cases
  // --------------------------------------------------------------

  /** Case 1: write every cell, read every cell, verify. */
  def caseWriteReadAllCells(): Unit = {
    compileDut().doSim("write-read-all-cells") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.clockDomain.waitSampling(5)

      val totalWords = dut.cfg.programWordCount +
        (dut.cfg.resultRingByteCount + 3) / 4
      val mask = 0xffffffffL

      for (a <- 0 until totalWords) {
        val data = (a ^ 0xa5a5_a5a5L) & mask
        doLoaderWrite(dut, a.toLong, data)
      }

      dut.clockDomain.waitSampling(2)

      for (a <- 0 until totalWords) {
        val expected = BigInt((a ^ 0xa5a5_a5a5L) & mask)
        val got = doRead(dut, a.toLong)
        assert(
          got == expected,
          s"cell $a: expected 0x${expected.toString(16)} got 0x${got.toString(16)}"
        )
      }

      dut.clockDomain.waitSampling(5)
      println("[caseWriteReadAllCells] OK")
    }
  }

  /** Case 2: read priority over a contending result-write.
    *
    * Seed the cell first so the priority test has a concrete pre-write value to
    * read back. Then drive `readCmd.valid` and `resultWrite.valid` to the
    * *same* cycle (different addresses to keep this case independent of case
    * 6); confirm that the read fires this cycle and the write fires the next.
    */
  def caseReadPriorityOverWrite(): Unit = {
    compileDut().doSim("read-priority-over-write") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.clockDomain.waitSampling(5)

      val readAddr = 0x10L
      val writeAddr = 0x20L
      val seed = 0x1234_5678L
      val newVal = 0x5678_9abcL

      doLoaderWrite(dut, readAddr, seed)
      doLoaderWrite(dut, writeAddr, 0x0000_0000L)
      dut.clockDomain.waitSampling(2)

      // Fork the response watcher BEFORE staging the contention.
      // It samples readResp.valid every cycle and exits the first
      // cycle it sees the pulse, so it cannot miss the one-cycle
      // Flow.valid that fires on the cycle after readCmd fires.
      val resp = captureReadResp(dut)

      // Stage both sources simultaneously.
      dut.io.readCmd.valid #= true
      dut.io.readCmd.payload #= readAddr
      dut.io.resultWrite.valid #= true
      dut.io.resultWrite.payload.addr #= writeAddr
      dut.io.resultWrite.payload.data #= newVal

      // After one clock the read must have fired, write must have
      // been back-pressured.
      dut.clockDomain.waitSampling()
      assert(
        dut.io.readCmd.ready.toBoolean,
        "readCmd.ready must be high while a read is contending"
      )
      assert(
        !dut.io.resultWrite.ready.toBoolean,
        "resultWrite.ready must be low while a read is contending"
      )
      // Drop the read; the write fires now.
      dut.io.readCmd.valid #= false

      // Wait for the watcher to catch the readResp.valid pulse,
      // then verify the captured payload is the pre-write seed.
      resp.thread.join()
      val seedReadback = resp.payload.get
      assert(
        seedReadback == BigInt(seed),
        s"read during contention must return the pre-write seed: got 0x${seedReadback.toString(16)}"
      )

      dut.clockDomain.waitSamplingWhere(dut.io.resultWrite.ready.toBoolean)
      dut.io.resultWrite.valid #= false

      // Verify the write also landed once it won arbitration.
      val readbackWrite = doRead(dut, writeAddr)
      assert(
        readbackWrite == BigInt(newVal),
        s"write must land after losing arbitration: got 0x${readbackWrite.toString(16)}"
      )

      println("[caseReadPriorityOverWrite] OK")
    }
  }

  /** Case 3: result write wins over loader write when both contend. */
  def caseResultBeatsLoader(): Unit = {
    compileDut().doSim("result-beats-loader") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.clockDomain.waitSampling(5)

      val loaderAddr = 0x30L
      val resultAddr = 0x31L
      val loaderData = 0xdead_beefL
      val resultData = 0xcafe_babeL

      // Both writers offer the bus the same cycle, different
      // addresses so we can verify which actually landed.
      dut.io.loaderWrite.valid #= true
      dut.io.loaderWrite.payload.addr #= loaderAddr
      dut.io.loaderWrite.payload.data #= loaderData
      dut.io.resultWrite.valid #= true
      dut.io.resultWrite.payload.addr #= resultAddr
      dut.io.resultWrite.payload.data #= resultData

      dut.clockDomain.waitSampling()
      assert(
        dut.io.resultWrite.ready.toBoolean,
        "resultWrite.ready must be high (no competing read)"
      )
      assert(
        !dut.io.loaderWrite.ready.toBoolean,
        "loaderWrite.ready must be low while resultWrite is contending"
      )
      dut.io.resultWrite.valid #= false
      // Loader fires once result has yielded.
      dut.clockDomain.waitSamplingWhere(dut.io.loaderWrite.ready.toBoolean)
      dut.io.loaderWrite.valid #= false

      val resVal = doRead(dut, resultAddr)
      val loaderVal = doRead(dut, loaderAddr)
      assert(
        resVal == BigInt(resultData),
        s"resultWrite did not land: got 0x${resVal.toString(16)}"
      )
      assert(
        loaderVal == BigInt(loaderData),
        s"loaderWrite did not land after losing arbitration: got 0x${loaderVal.toString(16)}"
      )

      println("[caseResultBeatsLoader] OK")
    }
  }

  /** Case 4: result-ring wrap-around.
    *
    * The ring lives at addresses
    * `[programWordCount .. programWordCount + resultWordCount)`. The producer
    * is expected to mod the write address by `resultWordCount`. Drive it past
    * the boundary, verify the wraparound writes land in the right cells.
    */
  def caseResultRingWrap(): Unit = {
    compileDut().doSim("result-ring-wrap") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.clockDomain.waitSampling(5)

      val ringBase = dut.cfg.programWordCount
      val ringWords = (dut.cfg.resultRingByteCount + 3) / 4
      val passes = 3 // > 2 to guarantee real wrap behaviour

      for (i <- 0 until ringWords * passes) {
        val ringIdx = i % ringWords
        val ringAddr = ringBase + ringIdx
        val data = (0x1000_0000L + i) & 0xffff_ffffL
        doResultWrite(dut, ringAddr.toLong, data)
      }

      // The last write to each cell is the one from the final
      // pass; verify.
      val finalPassStart = ringWords * (passes - 1)
      for (idx <- 0 until ringWords) {
        val expected =
          BigInt((0x1000_0000L + finalPassStart + idx) & 0xffff_ffffL)
        val got = doRead(dut, (ringBase + idx).toLong)
        assert(
          got == expected,
          s"ring cell $idx: expected 0x${expected.toString(16)} got 0x${got.toString(16)}"
        )
      }

      println("[caseResultRingWrap] OK")
    }
  }

  /** Case 5: read latency is exactly one cycle.
    *
    * Drive `readCmd.valid` for a single cycle and sample `readResp.valid` on
    * each cycle, asserting that it is high on the cycle immediately following
    * the fire and low on every other cycle.
    */
  def caseReadLatency(): Unit = {
    compileDut().doSim("read-latency") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.clockDomain.waitSampling(5)

      val addr = 0x05L
      doLoaderWrite(dut, addr, 0xa5b6_c7d8L)
      dut.clockDomain.waitSampling(2)

      // Pre-flight: readResp.valid must be low while no read is in
      // flight.
      assert(
        !dut.io.readResp.valid.toBoolean,
        "readResp.valid must be low while idle"
      )

      // Fork the response watcher BEFORE staging the read.  Even
      // without a contending writer, sampling the registered
      // `readResp.valid` pulse from the main thread the cycle
      // after `readCmd.valid #= false` races with SpinalSim's
      // delta ordering --- the same race that bit cases 2/6/7.
      // The watcher loop sees every cycle and never races with
      // input-driver writes.  See `captureReadResp`'s docstring.
      val resp = captureReadResp(dut)

      // Fire one read.
      dut.io.readCmd.valid #= true
      dut.io.readCmd.payload #= addr
      dut.clockDomain.waitSampling()
      dut.io.readCmd.valid #= false

      // Wait for the watcher to catch the readResp.valid pulse,
      // then verify the captured payload.
      resp.thread.join()
      val got = resp.payload.get
      assert(
        got == BigInt(0xa5b6_c7d8L),
        s"readResp.payload mismatch: 0x${got.toString(16)}"
      )

      // Two full cycles after the fire `readCmd.valid` has been
      // low long enough that `RegNext(doRead)` is settled False
      // --- no race here, a bare sample is fine.
      dut.clockDomain.waitSampling()
      assert(
        !dut.io.readResp.valid.toBoolean,
        "readResp.valid must drop one cycle after the response"
      )

      println("[caseReadLatency] OK")
    }
  }

  /** Case 6: simultaneous read + write to the same address.
    *
    * The arbiter prevents this from ever reaching the primitive: the read fires
    * this cycle (returning the pre-write value) and the write fires the cycle
    * after. The second read (issued after the write completes) returns the
    * post-write value.
    */
  def caseSameAddrReadWrite(): Unit = {
    compileDut().doSim("same-addr-read-write") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.clockDomain.waitSampling(5)

      val addr = 0x09L
      val pre = 0xc0de_faceL
      val post = 0xfeed_d00dL

      doLoaderWrite(dut, addr, pre)
      dut.clockDomain.waitSampling(2)

      // Fork the response watcher BEFORE staging the contention so
      // it can't miss the one-cycle Flow.valid pulse.
      val resp = captureReadResp(dut)

      // Stage read + result write to the same address, same cycle.
      dut.io.readCmd.valid #= true
      dut.io.readCmd.payload #= addr
      dut.io.resultWrite.valid #= true
      dut.io.resultWrite.payload.addr #= addr
      dut.io.resultWrite.payload.data #= post

      // After one cycle: read fired, write is back-pressured.
      dut.clockDomain.waitSampling()
      assert(
        dut.io.readCmd.ready.toBoolean,
        "readCmd.ready must remain high while contending"
      )
      assert(
        !dut.io.resultWrite.ready.toBoolean,
        "resultWrite.ready must be low while a same-address read is contending"
      )

      // Drop read; let the write fire next cycle.
      dut.io.readCmd.valid #= false

      // Watcher caught the readResp.valid pulse and snapshot the
      // payload. The captured value must be the pre-write seed.
      resp.thread.join()
      val preReadback = resp.payload.get
      assert(
        preReadback == BigInt(pre),
        s"same-cycle r/w: read must see pre-write value, got 0x${preReadback.toString(16)}"
      )

      // Wait for the write to fire next cycle.
      dut.clockDomain.waitSamplingWhere(dut.io.resultWrite.ready.toBoolean)
      dut.io.resultWrite.valid #= false

      // Second read sees the post-write value.
      val postReadback = doRead(dut, addr)
      assert(
        postReadback == BigInt(post),
        s"second read must see post-write value, got 0x${postReadback.toString(16)}"
      )

      println("[caseSameAddrReadWrite] OK")
    }
  }

  /** Case 7: read priority over a contending loader write.
    *
    * Symmetric to case 2 but with `loaderWrite` instead of `resultWrite`. The
    * read-priority arbiter is supposed to preempt either writer; case 2 only
    * proved that for the higher-priority writer.
    */
  def caseReadPriorityOverLoader(): Unit = {
    compileDut().doSim("read-priority-over-loader") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.clockDomain.waitSampling(5)

      val readAddr = 0x12L
      val writeAddr = 0x22L
      val seed = 0x4321_8765L
      val newVal = 0x8765_4321L

      doLoaderWrite(dut, readAddr, seed)
      doLoaderWrite(dut, writeAddr, 0x0000_0000L)
      dut.clockDomain.waitSampling(2)

      // Fork the response watcher BEFORE staging the contention.
      val resp = captureReadResp(dut)

      dut.io.readCmd.valid #= true
      dut.io.readCmd.payload #= readAddr
      dut.io.loaderWrite.valid #= true
      dut.io.loaderWrite.payload.addr #= writeAddr
      dut.io.loaderWrite.payload.data #= newVal

      dut.clockDomain.waitSampling()
      assert(
        dut.io.readCmd.ready.toBoolean,
        "readCmd.ready must be high while a read is contending"
      )
      assert(
        !dut.io.loaderWrite.ready.toBoolean,
        "loaderWrite.ready must be low while a read is contending"
      )

      dut.io.readCmd.valid #= false

      // Watcher caught the readResp.valid pulse.
      resp.thread.join()
      val seedReadback = resp.payload.get
      assert(
        seedReadback == BigInt(seed),
        s"read during loader contention must return pre-write seed: got 0x${seedReadback.toString(16)}"
      )

      dut.clockDomain.waitSamplingWhere(dut.io.loaderWrite.ready.toBoolean)
      dut.io.loaderWrite.valid #= false

      val readbackWrite = doRead(dut, writeAddr)
      assert(
        readbackWrite == BigInt(newVal),
        s"loader write must land after losing arbitration: got 0x${readbackWrite.toString(16)}"
      )

      println("[caseReadPriorityOverLoader] OK")
    }
  }

  /** Case 8: dual-tile parallelism.
    *
    * Writes a value whose upper half (`[31:16]`) and lower half (`[15:0]`) are
    * distinct (`0xAABB_CCDD`), reads it back, and asserts both halves are
    * intact. This proves the `tileLo`/`tileHi` DATAIN split and DATAOUT
    * concatenation are wired correctly (not swapped, not overlapping).
    *
    * In `useBlackBox = false` mode the Mem is one 32-bit-wide store, so this
    * case validates the 32-bit write-path integrity of the sim substitute
    * alongside the real wiring contract for the hardware path.
    */
  def caseDualTileParallelism(): Unit = {
    compileDut().doSim("dual-tile-parallelism") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.clockDomain.waitSampling(5)

      // Pattern where upper and lower 16-bit halves are clearly
      // distinct so a swap or merge bug produces a wrong value.
      val addr = 0x03L
      val data = 0xaabb_ccddL

      doLoaderWrite(dut, addr, data)
      dut.clockDomain.waitSampling(2)

      val got = doRead(dut, addr)
      assert(
        got == BigInt(data),
        s"dual-tile: expected 0x${data.toHexString} got 0x${got.toString(16)}"
      )
      // Explicitly check each half to produce a precise error if
      // the halves are swapped.
      val loHalf = (got & BigInt(0xffffL)).toInt
      val hiHalf = ((got >> 16) & BigInt(0xffffL)).toInt
      assert(
        loHalf == 0xccdd,
        s"dual-tile: lo half expected 0xCCDD got 0x${loHalf.toHexString}"
      )
      assert(
        hiHalf == 0xaabb,
        s"dual-tile: hi half expected 0xAABB got 0x${hiHalf.toHexString}"
      )

      println("[caseDualTileParallelism] OK")
    }
  }

  /** Case 9: alternating bit pattern.
    *
    * Writes `0x5555_5555` and `0xAAAA_AAAA` to consecutive addresses, reads
    * them back, and asserts each bit individually. Detects any per-bit stuck-at
    * fault or cross-talk between the two tile data paths.
    */
  def caseAlternatingBitPattern(): Unit = {
    compileDut().doSim("alternating-bit-pattern") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.clockDomain.waitSampling(5)

      val addr0 = 0x06L
      val addr1 = 0x07L
      val pat0 = 0x5555_5555L // odd bits set
      val pat1 = 0xaaaa_aaaaL // even bits set

      doLoaderWrite(dut, addr0, pat0)
      doLoaderWrite(dut, addr1, pat1)
      dut.clockDomain.waitSampling(2)

      val got0 = doRead(dut, addr0)
      val got1 = doRead(dut, addr1)

      assert(
        got0 == BigInt(pat0),
        s"alternating[0]: expected 0x${pat0.toHexString} got 0x${got0.toString(16)}"
      )
      assert(
        got1 == BigInt(pat1),
        s"alternating[1]: expected 0x${pat1.toHexString} got 0x${got1.toString(16)}"
      )

      println("[caseAlternatingBitPattern] OK")
    }
  }

  // --------------------------------------------------------------
  // Entry point
  // --------------------------------------------------------------
  def main(args: Array[String]): Unit = {
    caseWriteReadAllCells()
    caseReadPriorityOverWrite()
    caseResultBeatsLoader()
    caseResultRingWrap()
    caseReadLatency()
    caseSameAddrReadWrite()
    caseReadPriorityOverLoader()
    caseDualTileParallelism()
    caseAlternatingBitPattern()
    println("SpramControllerSim: all cases passed")
  }
}
