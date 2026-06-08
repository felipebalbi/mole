package mole

import spinal.core._
import spinal.lib._
import spinal.lib.pipeline._

/** STATUS_TRAP constant (spec §11): engine trap status value 0x1F. */
object StatusCode {
  val TRAP: Int = 0x1f
}

/** Stageables flowing through the F → D → R → X → W pipeline.
  *
  * C.6 catalog: enough to support HALT (executes) and all other opcodes (trap
  * to STATUS_TRAP). C.7/C.8/C.9 will extend this catalog with WIRE / CTRL /
  * DATA execution stageables.
  */
object PipeStageables {

  // ---- F → all subsequent stages ----------------------------------------

  /** Program counter of the fetched instruction (word address). */
  val PC = Stageable(UInt(13 bits))

  /** Raw 32-bit instruction word from SPRAM. */
  val INSTRUCTION = Stageable(Bits(32 bits))

  // ---- D → all subsequent stages ----------------------------------------

  /** 2-bit opcode group from bits [31:30]. */
  val OPCODE_GROUP = Stageable(UInt(2 bits))

  /** 4-bit sub-opcode from bits [29:26]. */
  val OPCODE_SUB = Stageable(UInt(4 bits))

  /** True when the decoded opcode is CTRL.HALT (group=01, sub=0000). */
  val IS_HALT = Stageable(Bool())

  /** True when the decoded opcode must trap (any non-HALT in C.6). */
  val IS_TRAP = Stageable(Bool())

  /** 5-bit halt status: extracted from instruction [7:3] for HALT, or
    * STATUS_TRAP (0x1F) for trap cases.
    */
  val HALT_STATUS = Stageable(UInt(5 bits))

  /** Register A read address (for future WIRE/DATA opcodes). */
  val READ_REG_A_ADDR = Stageable(UInt(3 bits))

  /** Register B read address (for future WIRE/DATA opcodes). */
  val READ_REG_B_ADDR = Stageable(UInt(3 bits))

  /** True when the current instruction reads register A. */
  val READS_REG_A = Stageable(Bool())

  /** True when the current instruction reads register B. */
  val READS_REG_B = Stageable(Bool())

  /** True when the E-stage instruction is a stall-inducing load-use producer.
    * Always False in C.6.
    */
  val IS_LOAD_USE = Stageable(Bool())

  // ---- R → X/W stages ---------------------------------------------------

  /** Forwarded register-A value (combinational from RegFile port 0). */
  val REG_A_VALUE = Stageable(Bits(32 bits))

  /** Forwarded register-B value (combinational from RegFile port 1). */
  val REG_B_VALUE = Stageable(Bits(32 bits))

  // ---- X → W stage ------------------------------------------------------

  /** True when this instruction writes a register in W. False in C.6. */
  val WRITES_REG = Stageable(Bool())

  /** Destination register address for the W-stage register write. */
  val WRITE_REG_ADDR = Stageable(UInt(3 bits))

  /** Data to write to the destination register. */
  val WRITE_REG_DATA = Stageable(Bits(32 bits))

  /** True when X requests a halt (HALT or trap). */
  val HALT_REQUEST = Stageable(Bool())

  /** True when X wants to push a word to the result ring. */
  val RING_WRITE_VALID = Stageable(Bool())

  /** The 32-bit result-ring word to push (HALT word layout per spec §11). */
  val RING_WRITE_DATA = Stageable(Bits(32 bits))

  /** True when X requests a PC redirect. */
  val PC_REDIRECT_VALID = Stageable(Bool())

  /** PC redirect target. */
  val PC_REDIRECT_TARGET = Stageable(UInt(13 bits))
}

/** 5-stage (6-stage with F split into F1/F2) pipeline for the Mole v0.2
  * bit-cycle engine.
  *
  * Stage topology: F1 (fetch request) → F2 (fetch response/latch) → D (decode)
  * → R (register read) → X (execute) → W (writeback). F1+F2 model the one-cycle
  * SPRAM read latency without hiding it in haltIt().
  *
  * C.6 execution: HALT executes and pushes a HALT word to the result ring;
  * every other opcode traps to STATUS_TRAP (0x1F) with the same ring push.
  *
  * Bus-shaped FSM idiom (AGENTS §"Bus-shaped FSM idiom"):
  *   - Bus driver registers are `Reg(Bool())`s at component scope.
  *   - No `releaseAll()` helper.
  *   - Stretch-aware Q1→Q2 guard is inline on X stage (not a separate stage).
  *     In C.6 no WIRE opcodes execute so the guard is absent; C.7 adds it.
  *
  * @param cfg
  *   Mole compile-time configuration.
  */
case class EnginePipeline(cfg: MoleConfig) extends Component {

  // Address widths derived from the config.
  val progAddrWidth: Int = log2Up(cfg.programWordCount) // 13 for 4096 words
  val resultWordCount: Int = (cfg.resultRingByteCount + 3) / 4
  val spramAddrWidth: Int = log2Up(cfg.programWordCount + resultWordCount)
  val resultBase: Int = cfg.programWordCount
  val programLenWidth: Int = log2Up(Instruction.MAX_PROGRAM_WORDS + 1)

  val io = new Bundle {

    // ---- SPRAM interface -------------------------------------------------
    val spramRead = master Stream UInt(spramAddrWidth bits)
    val spramResp = slave Flow Bits(32 bits)
    val ringWrite = master Stream SpramWriteCmd(spramAddrWidth)

    // ---- Bus observation (unused in C.6; stable port for MoleTop) --------
    val sdaSampled = in Bool ()
    val sclSampled = in Bool ()

    // ---- Bus drive (unused in C.6; idle at reset) -------------------------
    val sda = master(MoleBusLine())
    val scl = master(MoleBusLine())

    // ---- Engine role -------------------------------------------------------
    val role = out Bool ()

    // ---- Engine status outputs --------------------------------------------
    val halted = out Bool ()
    val haltStatus = out UInt (5 bits)
    val mismatchFlag = out Bool ()
    val timeoutFlag = out Bool ()
    val startFlag = out Bool ()
    val stopFlag = out Bool ()

    // ---- Loader interface --------------------------------------------------
    val programLength = in UInt (programLenWidth bits)
    val engineStart = in Bool ()
  }

  // --------------------------------------------------------------------------
  // Architectural registers (live outside pipeline stages)
  // --------------------------------------------------------------------------

  val haltedReg = Reg(Bool()) init False
  val haltStatusReg = Reg(UInt(5 bits)) init 0
  // These flags are written by WIRE/CTRL opcodes in C.7+. Tag them so the
  // SpinalHDL elaboration check does not treat the unassigned-but-init'd
  // registers as errors. The `allowUnsetRegToAvoidLatch` annotation says
  // "I know this register has no logic driver yet; treat the init as the
  // only assignment."
  val mismatchFlagReg = Reg(Bool()) init False
  mismatchFlagReg.allowUnsetRegToAvoidLatch()
  val timeoutFlagReg = Reg(Bool()) init False
  timeoutFlagReg.allowUnsetRegToAvoidLatch()
  val startFlagReg = Reg(Bool()) init False
  startFlagReg.allowUnsetRegToAvoidLatch()
  val stopFlagReg = Reg(Bool()) init False
  stopFlagReg.allowUnsetRegToAvoidLatch()
  // roleReg: mutable via SET_ROLE in C.8; tag for the same reason.
  val roleReg = Reg(Bool()) init (cfg.role == EngineRole.Controller)
  roleReg.allowUnsetRegToAvoidLatch()

  val ringPtrWidth: Int = log2Up(resultWordCount + 1)
  val ringWrPtr = Reg(UInt(ringPtrWidth bits)) init 0
  val ringOverflow = Reg(Bool()) init False

  // Bus driver registers (AGENTS §"Registered drivers"; no releaseAll()).
  // Written by WIRE opcode execution in C.7. Tag unassigned to silence
  // elaboration warnings — they are intentionally init'd to release state.
  val sdaDriveLow = Reg(Bool()) init False
  sdaDriveLow.allowUnsetRegToAvoidLatch()
  val sdaDriveHigh = Reg(Bool()) init False
  sdaDriveHigh.allowUnsetRegToAvoidLatch()
  val sclDriveLow = Reg(Bool()) init False
  sclDriveLow.allowUnsetRegToAvoidLatch()
  val sclDriveHigh = Reg(Bool()) init False
  sclDriveHigh.allowUnsetRegToAvoidLatch()

  io.sda.driveLow := sdaDriveLow
  io.sda.driveHigh := sdaDriveHigh
  io.scl.driveLow := sclDriveLow
  io.scl.driveHigh := sclDriveHigh
  // io.sda.read and io.scl.read are inputs: wired from pad by MoleTop.

  // --------------------------------------------------------------------------
  // Status output connections
  // --------------------------------------------------------------------------
  io.halted := haltedReg
  io.haltStatus := haltStatusReg
  io.mismatchFlag := mismatchFlagReg
  io.timeoutFlag := timeoutFlagReg
  io.startFlag := startFlagReg
  io.stopFlag := stopFlagReg
  io.role := roleReg

  // --------------------------------------------------------------------------
  // Register file
  // --------------------------------------------------------------------------
  val regFile = RegFile(width = 32, depth = 8)

  // --------------------------------------------------------------------------
  // PC register (owned by F1)
  // --------------------------------------------------------------------------
  val pcReg = Reg(UInt(progAddrWidth bits)) init 0

  // fetchActive: engine is running and not halted.
  val fetchActive = io.engineStart && !haltedReg &&
    (pcReg < io.programLength.resize(progAddrWidth bits))

  // --------------------------------------------------------------------------
  // Ring write port — default idle; W drives it.
  // --------------------------------------------------------------------------
  io.ringWrite.valid := False
  io.ringWrite.payload.addr := U(resultBase, spramAddrWidth bits)
  io.ringWrite.payload.data := B(0, 32 bits)

  // --------------------------------------------------------------------------
  // Pipeline construction
  // --------------------------------------------------------------------------
  val pip = new Pipeline

  // Six stages: F1 (fetch req), F2 (fetch resp/latch), D, R, X, W.
  val sF1 = new Stage()(pip)
  val sF2 = new Stage()(pip)
  val sD = new Stage()(pip)
  val sR = new Stage()(pip)
  val sX = new Stage()(pip)
  val sW = new Stage()(pip)

  sF1.setCompositeName(this, "F1")
  sF2.setCompositeName(this, "F2")
  sD.setCompositeName(this, "D")
  sR.setCompositeName(this, "R")
  sX.setCompositeName(this, "X")
  sW.setCompositeName(this, "W")

  // Registered (M2S) connections between stages.
  pip.connect(sF1, sF2)(Connection.M2S())
  pip.connect(sF2, sD)(Connection.M2S())
  pip.connect(sD, sR)(Connection.M2S())
  pip.connect(sR, sX)(Connection.M2S())
  pip.connect(sX, sW)(Connection.M2S())

  // --------------------------------------------------------------------------
  // F1: Fetch Request
  // F1 is always valid (it's the pipeline source). Halt it when not active.
  // --------------------------------------------------------------------------

  // Drive the first stage valid: always trying to produce a fetch.
  sF1.internals.input.valid := True

  // Drive spramRead from F1.
  io.spramRead.valid := fetchActive
  io.spramRead.payload := pcReg.resize(spramAddrWidth bits)

  // Stall F1 when:
  //   (a) not fetch-active (halted / not started / PC out of range), or
  //   (b) SPRAM not ready.
  sF1.haltIt(!fetchActive || !io.spramRead.ready)

  // latch PC used for the current fetch into the PC stageable.
  sF1(PipeStageables.PC) := pcReg.resize(13 bits)

  // Advance PC when a fetch fires.
  when(sF1.isFiring) {
    pcReg := pcReg + 1
  }

  // --------------------------------------------------------------------------
  // F2: Fetch Response — latch SPRAM response
  // The SPRAM delivers readResp.valid exactly 1 cycle after readCmd.fire.
  // We halt F2 until the response arrives.
  // --------------------------------------------------------------------------

  // Halt F2 while waiting for the SPRAM response.
  sF2.haltIt(sF2.valid && !io.spramResp.valid)

  sF2(PipeStageables.INSTRUCTION) := io.spramResp.payload

  // --------------------------------------------------------------------------
  // D: Decode
  // --------------------------------------------------------------------------

  val dInsn = sD(PipeStageables.INSTRUCTION)
  val dGroup = dInsn(31 downto 30).asUInt
  val dSub = dInsn(29 downto 26).asUInt

  sD(PipeStageables.OPCODE_GROUP) := dGroup
  sD(PipeStageables.OPCODE_SUB) := dSub

  // CTRL.HALT: group=0b01 (1), sub=0b0000 (0)
  val dIsHalt = (dGroup === 1) && (dSub === 0)
  sD(PipeStageables.IS_HALT) := dIsHalt
  sD(PipeStageables.IS_TRAP) := !dIsHalt

  // HALT status from instruction [7:3]; override to STATUS_TRAP for traps.
  val dHaltStatus = dInsn(7 downto 3).asUInt
  sD(PipeStageables.HALT_STATUS) := Mux(
    dIsHalt,
    dHaltStatus,
    U(StatusCode.TRAP, 5 bits)
  )

  // Register operand decoding (unused in C.6; stable for C.7+).
  sD(PipeStageables.READ_REG_A_ADDR) := dInsn(25 downto 23).asUInt
  sD(PipeStageables.READ_REG_B_ADDR) := dInsn(22 downto 20).asUInt
  sD(PipeStageables.READS_REG_A) := False
  sD(PipeStageables.READS_REG_B) := False
  sD(PipeStageables.IS_LOAD_USE) := False

  // --------------------------------------------------------------------------
  // R: Register Read
  // --------------------------------------------------------------------------

  regFile.io.readAddr0 := sR(PipeStageables.READ_REG_A_ADDR)
  regFile.io.readAddr1 := sR(PipeStageables.READ_REG_B_ADDR)

  sR(PipeStageables.REG_A_VALUE) := regFile.io.readData0
  sR(PipeStageables.REG_B_VALUE) := regFile.io.readData1

  // Stall R on load-use hazard (always 0 in C.6; wiring stays).
  sR.haltWhen(regFile.io.loadUseStall)

  // RegFile E-stage signals: driven from the X stage's stageables.
  // We need to compute these combinationally from sX's current content.
  regFile.io.eValid := sX.valid && sX(PipeStageables.WRITES_REG)
  regFile.io.eWriteAddr := sX(PipeStageables.WRITE_REG_ADDR)
  regFile.io.eIsLoadUse := sX(PipeStageables.IS_LOAD_USE)

  // --------------------------------------------------------------------------
  // X: Execute
  // C.6: HALT executes; everything else traps to STATUS_TRAP.
  // --------------------------------------------------------------------------

  // Register write intent (no writers in C.6).
  sX(PipeStageables.WRITES_REG) := False
  sX(PipeStageables.WRITE_REG_ADDR) := U(0, 3 bits)
  sX(PipeStageables.WRITE_REG_DATA) := B(0, 32 bits)

  // HALT or TRAP → issue halt request.
  val xHaltOrTrap = sX(PipeStageables.IS_HALT) || sX(PipeStageables.IS_TRAP)
  sX(PipeStageables.HALT_REQUEST) := xHaltOrTrap

  // Build HALT word (spec §11):
  //   [31:30]=11 [29]=overflow [28]=mismatch [27:23]=status [22:0]=0
  val xStatus = sX(PipeStageables.HALT_STATUS)
  val xHaltWord = B"11" ##
    ringOverflow.asBits ##
    mismatchFlagReg.asBits ##
    xStatus.asBits ##
    B(0, 23 bits)

  sX(PipeStageables.RING_WRITE_VALID) := xHaltOrTrap
  sX(PipeStageables.RING_WRITE_DATA) := xHaltWord
  sX(PipeStageables.PC_REDIRECT_VALID) := xHaltOrTrap
  sX(PipeStageables.PC_REDIRECT_TARGET) := sX(PipeStageables.PC)

  // Stall X when ring write is back-pressured.
  sX.haltWhen(sX(PipeStageables.RING_WRITE_VALID) && !io.ringWrite.ready)

  // --------------------------------------------------------------------------
  // W: Writeback
  // --------------------------------------------------------------------------

  // Commit RegFile write (no writers in C.6; wiring stays for C.7+).
  regFile.io.writeEnable := sW.isFiring && sW(PipeStageables.WRITES_REG)
  regFile.io.writeAddr := sW(PipeStageables.WRITE_REG_ADDR)
  regFile.io.writeData := sW(PipeStageables.WRITE_REG_DATA)

  // Commit ring push and advance ring pointer.
  when(sW.isFiring && sW(PipeStageables.RING_WRITE_VALID)) {
    io.ringWrite.valid := True
    io.ringWrite.payload.addr :=
      (U(resultBase, spramAddrWidth bits) +
        ringWrPtr.resize(spramAddrWidth bits)).resized
    io.ringWrite.payload.data := sW(PipeStageables.RING_WRITE_DATA)
    when(ringWrPtr < U(resultWordCount - 1, ringPtrWidth bits)) {
      ringWrPtr := ringWrPtr + 1
    } otherwise {
      ringOverflow := True
    }
  }

  // Commit halt.
  when(sW.isFiring && sW(PipeStageables.HALT_REQUEST)) {
    haltedReg := True
    // Status lives at [27:23] of the ring word (spec §11).
    haltStatusReg := sW(PipeStageables.RING_WRITE_DATA)(27 downto 23).asUInt
  }

  // Stall W when ring write is back-pressured (commit point must not advance).
  sW.haltWhen(sW(PipeStageables.RING_WRITE_VALID) && !io.ringWrite.ready)

  // Flush pipeline on commit of a halt: squash F1/F2/D/R/X bubbles.
  // haltedReg gates F1 from issuing new reads.
  sW.flushIt(sW.isFiring && sW(PipeStageables.HALT_REQUEST))

  // --------------------------------------------------------------------------
  // Build
  // --------------------------------------------------------------------------
  pip.build()
}
