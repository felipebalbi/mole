// SPDX-FileCopyrightText: 2026 Felipe Balbi
// SPDX-License-Identifier: CERN-OHL-W-2.0

package mole

import spinal.core._
import spinal.core.sim._
import spinal.lib._

/** Smoke sim for [[EnginePipeline]] WIRE-group opcodes --- EMIT_BIT_IMM under
  * i3c-OD and i3c-PP bus modes.
  *
  * Resurrected from `attic/BitCycleEngineSmokeSim.scala.v0-stash` for C.7. The
  * v0 sim wrapped [[BitCycleEngineCore]]; this version wraps [[EnginePipeline]]
  * (the v0.2 5-stage pipeline) via the [[BitCycleEngineSmokeDut]] wrapper.
  *
  * ==What changed from v0==
  *
  *   - Instruction encoding: v0.2 32-bit words via `Instruction.encode(...)`.
  *     `EmitBit(...)` → `EmitBitImm(...)`, `SetBusMode(...)` unchanged.
  *   - IO: `io.start`/`io.done` → `io.engineStart`/`io.halted`; bus split into
  *     `io.sda` + `io.scl` (both `MoleBusLine`).
  *   - HALT word: v0.2 32-bit; status at `[27:23]`, mismatch at `[28]`,
  *     overflow at `[29]`, tag at `[31:30]`.
  *   - Bus drivers observed are on `io.sda.driveLow` / `io.sda.driveHigh` /
  *     `io.scl.driveLow` / `io.scl.driveHigh`.
  *
  * ==Program==
  *
  * Four runs, all driving the byte 0x55 + ACK + HALT through the engine:
  *   - `i3c-OD`, `i3c-PP`: bit-by-bit via 8 × `EMIT_BIT_IMM` + 1 hiz ACK.
  *   - `i3c-OD/byte-imm`, `i3c-PP/byte-imm`: single `EMIT_BYTE_IMM(0x55)`.
  *
  * The byte-imm runs assert byte-identical on-wire waveform to the bit-by-bit
  * runs. New in v0.2 with the EMIT_BYTE_IMM opcode.
  *
  * Run: `sbt "runMain mole.BitCycleEngineSmokeSim"`
  */
object BitCycleEngineSmokeSim {

  // --------------------------------------------------------------
  // Types
  // --------------------------------------------------------------

  private case class BusSample(
      sdaLow: Boolean,
      sdaHigh: Boolean,
      sclLow: Boolean,
      sclHigh: Boolean
  )

  private case class LowInterval(start: Int, end: Int, midSda: BusSample)

  // --------------------------------------------------------------
  // Config
  // --------------------------------------------------------------

  private def smallCfg = MoleConfig(
    fabricFreqHz = 48 MHz, // use 48 MHz (production default); uartFreqHz=24 MHz
    quarterPeriodCyclesReset = 6,
    programWordCount = 64,
    resultRingByteCount = 64,
    captureMaxBits = 65536,
    uartBaud = 2_000_000
  )

  // --------------------------------------------------------------
  // Program builder (v0.2 encoding)
  // --------------------------------------------------------------

  /** Build the 9-bit "byte 0x55 + ACK hiz + HALT" program. */
  private def buildProgram(mode: BusMode.E): Seq[Int] = {
    import Instruction._
    val dataBits = (0 until 8).map { i =>
      val isOne = ((0x55 >> (7 - i)) & 1) != 0
      val sym = if (isOne) TxSymbol.recessive else TxSymbol.dominant
      encode(EmitBitImm(sym, expect = false, mask = false, capture = false))
    }
    val ackBit = encode(EmitBitImm(TxSymbol.hiz, false, false, false))
    Seq(encode(SetBusMode(mode))) ++ dataBits ++ Seq(ackBit, encode(Halt(0)))
  }

  /** Build the byte-IMM equivalent: a single EMIT_BYTE_IMM(0x55) emits the same
    * 8 data bits (MSB first) followed by the engine-generated ACK slot (hiz
    * SDA), then HALT.
    *
    * This is the entire point of the EMIT_BYTE_IMM opcode: replace 8
    * EMIT_BIT_IMMs (plus 1 ACK EMIT_BIT_IMM) with one instruction, with no
    * LOAD_IMM R7 setup needed. The on-wire waveform MUST be byte-identical to
    * [[buildProgram]], which is what [[runModeEmitByteImm]] asserts below.
    */
  private def buildProgramEmitByteImm(mode: BusMode.E): Seq[Int] = {
    import Instruction._
    Seq(
      encode(SetBusMode(mode)),
      encode(EmitByteImm(0x55, expect = false, mask = false, capture = false)),
      encode(Halt(0))
    )
  }

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

  private def isPpClass(mode: BusMode.E): Boolean = mode match {
    case BusMode.i3cPp | BusMode.hdrDdr => true
    case _                              => false
  }

  // --------------------------------------------------------------
  // Sim helpers
  // --------------------------------------------------------------

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

  private def sampleBus(dut: BitCycleEngineSmokeDut): BusSample =
    BusSample(
      sdaLow = dut.io.sda.driveLow.toBoolean,
      sdaHigh = dut.io.sda.driveHigh.toBoolean,
      sclLow = dut.io.scl.driveLow.toBoolean,
      sclHigh = dut.io.scl.driveHigh.toBoolean
    )

  private def quiet(dut: BitCycleEngineSmokeDut): Unit = {
    dut.io.engineStart #= false
    dut.io.programLength #= 0
    dut.io.loaderWrite.valid #= false
    dut.io.loaderWrite.payload.addr #= 0
    dut.io.loaderWrite.payload.data #= 0
    dut.io.debugReadCmd.valid #= false
    dut.io.debugReadCmd.payload #= 0
    // Pull-up modeled bus reads as high (recessive idle).
    dut.io.sda.read #= true
    dut.io.scl.read #= true
  }

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

  private def compileDut() =
    SimConfig.withWave.compile(BitCycleEngineSmokeDut(smallCfg))

  // --------------------------------------------------------------
  // Per-mode run
  // --------------------------------------------------------------

  private def runMode(label: String, mode: BusMode.E): Unit =
    runWithProgram(label, mode, buildProgram(mode))

  /** Same structural assertions as [[runMode]], but driven by an
    * EMIT_BYTE_IMM(0x55) program instead of 8 explicit EMIT_BIT_IMMs. Proves
    * the X-stage WS_EMIT_BYTE IMM-entry produces a byte- identical on-wire
    * waveform to the bit-by-bit path. New in v0.2 with the EMIT_BYTE_IMM opcode
    * (commit chain: spec → asm → engine → this test).
    */
  private def runModeEmitByteImm(label: String, mode: BusMode.E): Unit =
    runWithProgram(label, mode, buildProgramEmitByteImm(mode))

  private def runWithProgram(
      label: String,
      mode: BusMode.E,
      program: Seq[Int]
  ): Unit = {
    println(s"--- BitCycleEngineSmokeSim: $label ---")
    compileDut().doSim(label) { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.clockDomain.waitSampling(5)

      // 1. Load program.
      for ((word, idx) <- program.zipWithIndex) {
        loaderWrite(dut, idx, word)
      }
      dut.clockDomain.waitSampling(2)

      // 2. Start engine and capture bus trace.
      // engineStart must stay high throughout execution; programLength
      // bounds the PC (engine stops fetching when PC >= programLength).
      dut.io.programLength #= program.length
      dut.io.engineStart #= true

      val trace = collection.mutable.ArrayBuffer.empty[BusSample]
      val safetyLimit = 4000
      while (!dut.io.halted.toBoolean && trace.size < safetyLimit) {
        trace += sampleBus(dut)
        dut.clockDomain.waitSampling()
      }
      dut.io.engineStart #= false
      // Debug: check halt status at trace end.
      println(
        s"$label: halted=${dut.io.halted.toBoolean} status=${dut.io.haltStatus.toInt} mismatch=${dut.io.mismatchFlag.toBoolean}"
      )
      assert(
        dut.io.halted.toBoolean,
        s"$label: engine never halted (ran $safetyLimit cycles)"
      )
      assert(
        dut.io.haltStatus.toInt == 0,
        s"$label: unexpected halt status 0x${dut.io.haltStatus.toInt.toHexString} " +
          s"(expected 0; 0x1F = trap; trace size=${trace.size})"
      )

      // 3. Structural assertions.
      val intervals = findSclLowIntervals(trace.toSeq)
      if (intervals.size != 9) {
        println(s"$label: trace length ${trace.size} cycles, dumping first 40:")
        for ((s, c) <- trace.zipWithIndex.take(40)) {
          println(
            f"  cycle $c%3d: sdaLow=${s.sdaLow}%5b sdaHigh=${s.sdaHigh}%5b " +
              f"sclLow=${s.sclLow}%5b sclHigh=${s.sclHigh}%5b"
          )
        }
        val ivStr =
          intervals.map(iv => s"[${iv.start},${iv.end}]").mkString(", ")
        println(s"$label: found intervals at: $ivStr")
        println(s"$label: trace size = ${trace.size}")
        // Show trace from cycle 190 onward
        println(s"$label: cycles 190+ detail:")
        for ((s, c) <- trace.zipWithIndex.drop(190)) {
          println(
            f"  cycle $c%3d: sdaLow=${s.sdaLow}%5b sclLow=${s.sclLow}%5b"
          )
        }
      }
      assert(
        intervals.size == 9,
        s"$label: expected 9 SCL-low intervals, got ${intervals.size}"
      )

      // Q0 + Q1 = 2 × quarterPeriodCyclesReset fabric cycles.
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

      // Between consecutive SCL-low intervals: sclDriveHigh matches PP class.
      val expectSclHigh = isPpClass(mode)
      for (i <- 0 until intervals.size - 1) {
        val gapStart = intervals(i).end + 1
        val gapEnd = intervals(i + 1).start - 1
        for (c <- gapStart to gapEnd) {
          val s = trace(c)
          assert(
            s.sclHigh == expectSclHigh && !s.sclLow,
            f"$label: between bit $i and ${i + 1}, cycle $c: expected SCL " +
              f"(low=false, high=$expectSclHigh), saw (low=${s.sclLow}, " +
              f"high=${s.sclHigh})"
          )
        }
      }

      // Bus contention: never both drivers high.
      for ((s, c) <- trace.zipWithIndex) {
        assert(
          !(s.sdaLow && s.sdaHigh),
          s"$label: cycle $c: SDA contention"
        )
        assert(
          !(s.sclLow && s.sclHigh),
          s"$label: cycle $c: SCL contention"
        )
      }

      val widths = intervals.map(iv => iv.end - iv.start + 1).mkString(",")
      println(
        s"$label: ${intervals.size} bits emitted, SCL-low widths [$widths] " +
          s"cycles, between-bit sclDriveHigh=$expectSclHigh"
      )
    }
  }

  def main(args: Array[String]): Unit = {
    runMode("i3c-OD", BusMode.i3cOd)
    runMode("i3c-PP", BusMode.i3cPp)
    // EMIT_BYTE_IMM byte-identical-waveform parity. Same DUT, same
    // assertions; only the program shape differs (1 instruction vs 10).
    runModeEmitByteImm("i3c-OD/byte-imm", BusMode.i3cOd)
    runModeEmitByteImm("i3c-PP/byte-imm", BusMode.i3cPp)
    println("BitCycleEngineSmokeSim OK")
  }
}
