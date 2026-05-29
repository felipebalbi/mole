package mole

import spinal.core._
import spinal.core.sim._
import spinal.lib._

/** F-FPGA-004 regression coverage: JMP target out-of-range trap.
  *
  * `BitCycleEngineCore` previously decoded JMP as
  * `pc := instrReg(10 downto 0).asUInt.resize(pcWidth)`, which silently
  * truncates the 11-bit operand to `pcWidth` bits whenever
  * `programWordCount < 2048`. A JMP with bits set above `pcWidth - 1` would
  * land on a small in-range address instead of the intended (out-of-range) one
  * --- a silent miscompilation visible only to board variants with smaller
  * program memory.
  *
  * The fix guards the decode arm with a runtime range check against
  * `programWordCount`; an out-of-range JMP traps via `enterHalt(0xF)`.
  *
  * Coverage:
  *
  *   - `jmp-zero-runs`: at `programWordCount = 1024`, JMP 0 lands on PC 0 (a
  *     HALT) cleanly.
  *   - `jmp-at-max-legal-runs`: JMP `programWordCount - 1` lands on the last
  *     legal slot and halts cleanly.
  *   - `jmp-at-programWordCount-traps`: JMP `programWordCount` is legal at the
  *     wire-format level (11-bit operand) but out of range for this board;
  *     engine must trap with status 0xF.
  *   - `jmp-at-wire-max-traps`: JMP 2047 (wire-format maximum) on a
  *     `programWordCount = 1024` board must trap with status 0xF.
  *   - `jmp-default-config-no-regression`: with `programWordCount = 2048`
  *     (Verde default), JMP 2047 still runs --- the guard does not fire at the
  *     wire-format boundary.
  *
  * The JMP opcode is `0x06` at `[15:11]`; `JMP target` encodes as
  * `(0x06 << 11) | (target & 0x7FF)`. Test cases construct these raw 16-bit
  * words directly so the host encoder's stricter range checks do not interfere.
  *
  * Run: `sbt "runMain mole.BitCycleEngineJmpBoundarySim"`
  */
object BitCycleEngineJmpBoundarySim extends App {

  // --------------------------------------------------------------
  // Raw JMP encoder. Bypass `Instruction.encode` so the wire-format
  // edge cases (target == programWordCount, target == 2047) can be
  // constructed without the host-side range check rejecting them.
  // --------------------------------------------------------------
  private def jmpWord(target: Int): Int = {
    require(target >= 0 && target < 2048, s"target $target out of 11-bit range")
    (0x06 << 11) | (target & 0x7ff)
  }

  // HALT with status 0 --- the clean halt the user-visible cases land on.
  private val haltClean: Int = Instruction.encode(Instruction.Halt(0))

  // --------------------------------------------------------------
  // Shared sim helpers, modelled after BitCycleEngineSim's runner.
  // --------------------------------------------------------------

  private def quiet(dut: BitCycleEngineFullDut): Unit = {
    dut.io.start #= false
    dut.io.loaderWrite.valid #= false
    dut.io.loaderWrite.payload.addr #= 0
    dut.io.loaderWrite.payload.data #= 0
    dut.io.debugReadCmd.valid #= false
    dut.io.debugReadCmd.payload #= 0
    dut.io.bus.sda.read #= true
    dut.io.bus.scl.read #= true
  }

  private def loaderWrite(
      dut: BitCycleEngineFullDut,
      addr: Int,
      word: Int
  ): Unit = {
    dut.io.loaderWrite.valid #= true
    dut.io.loaderWrite.payload.addr #= addr
    dut.io.loaderWrite.payload.data #= word
    dut.clockDomain.waitSamplingWhere(dut.io.loaderWrite.ready.toBoolean)
    dut.io.loaderWrite.valid #= false
  }

  private def loadAt(
      dut: BitCycleEngineFullDut,
      words: Seq[(Int, Int)]
  ): Unit = {
    for ((addr, word) <- words) loaderWrite(dut, addr, word)
    dut.clockDomain.waitSampling(2)
  }

  private def debugRead(dut: BitCycleEngineFullDut, addr: Int): Int = {
    dut.io.debugReadCmd.valid #= true
    dut.io.debugReadCmd.payload #= addr
    dut.clockDomain.waitSamplingWhere(dut.io.debugReadCmd.ready.toBoolean)
    dut.io.debugReadCmd.valid #= false
    dut.clockDomain.waitSamplingWhere(dut.io.debugReadResp.valid.toBoolean)
    dut.io.debugReadResp.payload.toInt
  }

  private def runProgram(
      dut: BitCycleEngineFullDut,
      words: Seq[(Int, Int)],
      safetyLimit: Int = 50_000
  ): Unit = {
    dut.clockDomain.forkStimulus(period = 10)
    quiet(dut)
    dut.clockDomain.waitSampling(5)
    loadAt(dut, words)
    dut.io.start #= true
    dut.clockDomain.waitSamplingWhere(!dut.io.done.toBoolean)
    dut.io.start #= false
    var c = 0
    while (!dut.io.done.toBoolean && c < safetyLimit) {
      dut.clockDomain.waitSampling()
      c += 1
    }
    assert(
      dut.io.done.toBoolean,
      s"engine never halted (ran $safetyLimit cycles)"
    )
  }

  private def haltStatus(dut: BitCycleEngineFullDut, cfg: MoleConfig): Int = {
    val resultBase = cfg.programWordCount
    val resultWordCount = (cfg.resultRingByteCount + 1) / 2
    val resultLimit = resultBase + resultWordCount - 1
    val w = debugRead(dut, resultLimit)
    require(
      (w >>> 14) == 0x3,
      s"word at $resultLimit is not a HALT tag (=${w >>> 14})"
    )
    (w >> 8) & 0xf
  }

  // Common small config: programWordCount = 1024 so the JMP operand
  // (11-bit) can address slots beyond the program memory --- making
  // the guard reachable. Other fields mirror BitCycleEngineSim's cfg
  // closely enough to keep cycle budgets predictable.
  private val smallCfg = MoleConfig(
    fabricFreqHz = 24 MHz,
    quarterPeriodCyclesReset = 6,
    programWordCount = 1024,
    resultRingByteCount = 64,
    captureMaxBits = 65536,
    uartBaud = 1_000_000
  )

  private lazy val smallCompiled =
    SimConfig.withWave.compile(BitCycleEngineFullDut(smallCfg))

  private def runSmall(name: String)(
      body: BitCycleEngineFullDut => Unit
  ): Unit = {
    println(s"--- BitCycleEngineJmpBoundarySim: $name ---")
    smallCompiled.doSim(name) { dut => body(dut) }
    println(s"$name OK")
  }

  // --------------------------------------------------------------
  // Case 1: JMP 0 (smallest legal target).
  // --------------------------------------------------------------
  runSmall("jmp_to_address_zero_runs_normally_with_small_program_memory") {
    dut =>
      // JMP 0 is the lowest legal target. Placing JMP 0 at slot 0
      // creates a tight self-loop: the engine fetches JMP 0,
      // updates PC := 0, and refetches the same instruction. The
      // GUARD must NOT fire (0 < programWordCount) and the JMP
      // arm must NOT fall through to a trap path. We confirm the
      // legal-loop behaviour by asserting `engine.done` stays
      // False for a generous observation window. A premature
      // re-assertion of `done` would mean either the guard
      // mis-fired (status 0xF trap) or the JMP wrapped to a
      // garbage opcode --- both regressions worth catching.
      val program = Seq(0 -> jmpWord(0))
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.clockDomain.waitSampling(5)
      loadAt(dut, program)
      dut.io.start #= true
      dut.clockDomain.waitSamplingWhere(!dut.io.done.toBoolean)
      dut.io.start #= false
      var sawDone = false
      for (_ <- 0 until 2_000) {
        if (dut.io.done.toBoolean) sawDone = true
        dut.clockDomain.waitSampling()
      }
      assert(
        !sawDone,
        "jmp-zero: engine halted during the legal JMP-0 loop --- guard mis-fired or fetch went bad"
      )
  }

  // --------------------------------------------------------------
  // Case 2: JMP programWordCount - 1 (highest legal target).
  // --------------------------------------------------------------
  runSmall("jmp_to_address_at_max_legal_runs_normally") { dut =>
    // Slot 0: JMP 1023 (legal, equal to programWordCount - 1).
    // Slot 1023: HALT(0) (clean) --- the engine lands here and
    // returns to idle, with the HALT word written to the result
    // ring's reserved slot. Status decodes to 0.
    val maxLegal = smallCfg.programWordCount - 1
    val program = Seq(
      0 -> jmpWord(maxLegal),
      maxLegal -> haltClean
    )
    runProgram(dut, program)
    val status = haltStatus(dut, smallCfg)
    assert(
      status == 0x0,
      s"jmp-max-legal: HALT status 0x${status.toHexString} != 0x0 (guard mis-fired at the boundary?)"
    )
  }

  // --------------------------------------------------------------
  // Case 3: JMP programWordCount (first out-of-range target).
  // --------------------------------------------------------------
  runSmall("jmp_to_address_at_programWordCount_traps_with_status_F") { dut =>
    val program = Seq(0 -> jmpWord(smallCfg.programWordCount))
    runProgram(dut, program)
    val status = haltStatus(dut, smallCfg)
    assert(
      status == 0xf,
      s"jmp-at-programWordCount: HALT status 0x${status.toHexString} != 0xF --- guard failed to fire"
    )
  }

  // --------------------------------------------------------------
  // Case 4: JMP 2047 (wire-format maximum) on small program memory.
  // --------------------------------------------------------------
  runSmall("jmp_to_wire_max_traps_when_above_programWordCount") { dut =>
    val program = Seq(0 -> jmpWord(2047))
    runProgram(dut, program)
    val status = haltStatus(dut, smallCfg)
    assert(
      status == 0xf,
      s"jmp-wire-max: HALT status 0x${status.toHexString} != 0xF --- guard failed at the wire ceiling"
    )
  }

  // --------------------------------------------------------------
  // Case 5: default config, JMP 2047 still legal --- regression
  // guard for the "guard fires too eagerly" failure mode.
  // --------------------------------------------------------------
  private val defaultCfg = MoleConfig(
    fabricFreqHz = 24 MHz,
    quarterPeriodCyclesReset = 6,
    programWordCount = 2048,
    resultRingByteCount = 64,
    captureMaxBits = 65536,
    uartBaud = 1_000_000
  )

  private lazy val defaultCompiled =
    SimConfig.withWave.compile(BitCycleEngineFullDut(defaultCfg))

  println(
    "--- BitCycleEngineJmpBoundarySim: jmp_default_config_no_regression ---"
  )
  defaultCompiled.doSim("jmp_default_config_no_regression") { dut =>
    val maxLegal = defaultCfg.programWordCount - 1 // 2047
    val program = Seq(
      0 -> jmpWord(maxLegal),
      maxLegal -> haltClean
    )
    runProgram(dut, program)
    val status = haltStatus(dut, defaultCfg)
    assert(
      status == 0x0,
      s"jmp-default-no-regression: HALT status 0x${status.toHexString} != 0x0 --- guard regressed at default cfg"
    )
  }
  println("jmp_default_config_no_regression OK")

  println("--- BitCycleEngineJmpBoundarySim: all cases passed ---")
}
