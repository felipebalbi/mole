package mole

import spinal.core._
import spinal.lib._
import spinal.lib.misc.pipeline._

/** STATUS_TRAP constant (spec §11): engine trap status value 0x1F. */
object StatusCode {
  val TRAP: Int = 0x1f
}

/** Payloads flowing through the F → D → R → X → W pipeline.
  *
  * C.6 catalog: enough to support HALT (executes) and all other opcodes (trap
  * to STATUS_TRAP). C.7/C.8/C.9 will extend this catalog with WIRE / CTRL /
  * DATA execution payloads.
  */
object PipeStageables {

  // ---- F → all subsequent stages ----------------------------------------

  /** Program counter of the fetched instruction (word address). */
  val PC = Payload(UInt(13 bits))

  /** Raw 32-bit instruction word from SPRAM. */
  val INSTRUCTION = Payload(Bits(32 bits))

  // ---- D → all subsequent stages ----------------------------------------

  /** 2-bit opcode group from bits [31:30]. */
  val OPCODE_GROUP = Payload(UInt(2 bits))

  /** 4-bit sub-opcode from bits [29:26]. */
  val OPCODE_SUB = Payload(UInt(4 bits))

  /** True when the decoded opcode is CTRL.HALT (group=01, sub=0000). */
  val IS_HALT = Payload(Bool())

  /** True when the decoded opcode must trap (any non-HALT in C.6). */
  val IS_TRAP = Payload(Bool())

  /** 5-bit halt status: extracted from instruction [7:3] for HALT, or
    * STATUS_TRAP (0x1F) for trap cases.
    */
  val HALT_STATUS = Payload(UInt(5 bits))

  /** Register A read address (for future WIRE/DATA opcodes). */
  val READ_REG_A_ADDR = Payload(UInt(3 bits))

  /** Register B read address (for future WIRE/DATA opcodes). */
  val READ_REG_B_ADDR = Payload(UInt(3 bits))

  /** True when the current instruction reads register A. */
  val READS_REG_A = Payload(Bool())

  /** True when the current instruction reads register B. */
  val READS_REG_B = Payload(Bool())

  /** True when the E-stage instruction is a stall-inducing load-use producer.
    * Always False in C.6.
    */
  val IS_LOAD_USE = Payload(Bool())

  // ---- R → X/W stages ---------------------------------------------------

  /** Forwarded register-A value (combinational from RegFile port 0). */
  val REG_A_VALUE = Payload(Bits(32 bits))

  /** Forwarded register-B value (combinational from RegFile port 1). */
  val REG_B_VALUE = Payload(Bits(32 bits))

  // ---- X → W stage ------------------------------------------------------

  /** True when this instruction writes a register in W. False in C.6. */
  val WRITES_REG = Payload(Bool())

  /** Destination register address for the W-stage register write. */
  val WRITE_REG_ADDR = Payload(UInt(3 bits))

  /** Data to write to the destination register. */
  val WRITE_REG_DATA = Payload(Bits(32 bits))

  /** True when X requests a halt (HALT or trap). */
  val HALT_REQUEST = Payload(Bool())

  /** True when X wants to push a word to the result ring. */
  val RING_WRITE_VALID = Payload(Bool())

  /** The 32-bit result-ring word to push (HALT word layout per spec §11). */
  val RING_WRITE_DATA = Payload(Bits(32 bits))

  /** True when X requests a PC redirect. */
  val PC_REDIRECT_VALID = Payload(Bool())

  /** PC redirect target. */
  val PC_REDIRECT_TARGET = Payload(UInt(13 bits))
}

/** 5-stage (6-stage with F split into F1/F2) pipeline for the Mole v0.2
  * bit-cycle engine.
  *
  * Stage topology: F1 (fetch request) → F2 (fetch response/latch) → D (decode)
  * → R (register read) → X (execute) → W (writeback). F1+F2 model the one-cycle
  * SPRAM read latency without hiding it in haltWhen().
  *
  * C.6 execution: HALT executes and pushes a HALT word to the result ring;
  * every other opcode traps to STATUS_TRAP (0x1F) with the same ring push.
  *
  * Pipeline framework: spinal.lib.misc.pipeline — 6 CtrlLinks (f1/f2/d/r/x/w),
  * 5 StageLinks (registered M2S between each adjacent pair), 18 Payloads,
  * assembled with Builder(f1, f2, d, r, x, w, f1f2, f2d, dr, rx, xw).
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
  // Pipeline construction — spinal.lib.misc.pipeline
  //
  // Six CtrlLinks (one per pipeline stage) + five StageLinks (M2S registers
  // between adjacent stages). Builder() assembles them all.
  // --------------------------------------------------------------------------

  // Six stage control nodes.
  val f1, f2, d, r, x, w = CtrlLink()

  // Five inter-stage registers (M2S = data + valid registers).
  val f1f2 = StageLink(f1.down, f2.up)
  val f2d = StageLink(f2.down, d.up)
  val dr = StageLink(d.down, r.up)
  val rx = StageLink(r.down, x.up)
  val xw = StageLink(x.down, w.up)

  // --------------------------------------------------------------------------
  // F1: Fetch Request
  // F1 is always valid (it's the pipeline source). Halt it when not active.
  // --------------------------------------------------------------------------

  // Drive the first stage valid: always trying to produce a fetch.
  f1.up.valid := True

  // Drive spramRead from F1.
  io.spramRead.valid := fetchActive
  io.spramRead.payload := pcReg.resize(spramAddrWidth bits)

  // Stall F1 when:
  //   (a) not fetch-active (halted / not started / PC out of range), or
  //   (b) SPRAM not ready.
  f1.haltWhen(!fetchActive || !io.spramRead.ready)

  // Latch PC used for the current fetch into the PC payload.
  f1.up(PipeStageables.PC) := pcReg.resize(13 bits)

  // Advance PC when a fetch fires.
  when(f1.down.isFiring) {
    pcReg := pcReg + 1
  }

  // --------------------------------------------------------------------------
  // F2: Fetch Response — latch SPRAM response
  // The SPRAM delivers readResp.valid exactly 1 cycle after readCmd.fire.
  // We halt F2 until the response arrives.
  // --------------------------------------------------------------------------

  // Halt F2 while waiting for the SPRAM response.
  f2.haltWhen(f2.isValid && !io.spramResp.valid)

  f2.up(PipeStageables.INSTRUCTION) := io.spramResp.payload

  // --------------------------------------------------------------------------
  // D: Decode
  // --------------------------------------------------------------------------

  val dInsn = d(PipeStageables.INSTRUCTION)
  val dGroup = dInsn(31 downto 30).asUInt
  val dSub = dInsn(29 downto 26).asUInt

  d.up(PipeStageables.OPCODE_GROUP) := dGroup
  d.up(PipeStageables.OPCODE_SUB) := dSub

  // CTRL.HALT: group=0b01 (1), sub=0b0000 (0)
  val dIsHalt = (dGroup === 1) && (dSub === 0)
  d.up(PipeStageables.IS_HALT) := dIsHalt
  d.up(PipeStageables.IS_TRAP) := !dIsHalt

  // HALT status from instruction [7:3]; override to STATUS_TRAP for traps.
  val dHaltStatus = dInsn(7 downto 3).asUInt
  d.up(PipeStageables.HALT_STATUS) := Mux(
    dIsHalt,
    dHaltStatus,
    U(StatusCode.TRAP, 5 bits)
  )

  // Register operand decoding (unused in C.6; stable for C.7+).
  d.up(PipeStageables.READ_REG_A_ADDR) := dInsn(25 downto 23).asUInt
  d.up(PipeStageables.READ_REG_B_ADDR) := dInsn(22 downto 20).asUInt
  d.up(PipeStageables.READS_REG_A) := False
  d.up(PipeStageables.READS_REG_B) := False
  d.up(PipeStageables.IS_LOAD_USE) := False

  // --------------------------------------------------------------------------
  // R: Register Read
  // --------------------------------------------------------------------------

  regFile.io.readAddr0 := r(PipeStageables.READ_REG_A_ADDR)
  regFile.io.readAddr1 := r(PipeStageables.READ_REG_B_ADDR)

  r.up(PipeStageables.REG_A_VALUE) := regFile.io.readData0
  r.up(PipeStageables.REG_B_VALUE) := regFile.io.readData1

  // Stall R on load-use hazard (always 0 in C.6; wiring stays).
  r.haltWhen(regFile.io.loadUseStall)

  // RegFile E-stage signals: driven from the X stage's current content.
  // Use isValid for reads per API convention.
  regFile.io.eValid := x.isValid && x(PipeStageables.WRITES_REG)
  regFile.io.eWriteAddr := x(PipeStageables.WRITE_REG_ADDR)
  regFile.io.eIsLoadUse := x(PipeStageables.IS_LOAD_USE)

  // --------------------------------------------------------------------------
  // X: Execute
  // C.6: HALT executes; everything else traps to STATUS_TRAP.
  // --------------------------------------------------------------------------

  // Register write intent (no writers in C.6).
  x.up(PipeStageables.WRITES_REG) := False
  x.up(PipeStageables.WRITE_REG_ADDR) := U(0, 3 bits)
  x.up(PipeStageables.WRITE_REG_DATA) := B(0, 32 bits)

  // HALT or TRAP → issue halt request.
  val xHaltOrTrap = x(PipeStageables.IS_HALT) || x(PipeStageables.IS_TRAP)
  x.up(PipeStageables.HALT_REQUEST) := xHaltOrTrap

  // Build HALT word (spec §11):
  //   [31:30]=11 [29]=overflow [28]=mismatch [27:23]=status [22:0]=0
  val xStatus = x(PipeStageables.HALT_STATUS)
  val xHaltWord = B"11" ##
    ringOverflow.asBits ##
    mismatchFlagReg.asBits ##
    xStatus.asBits ##
    B(0, 23 bits)

  x.up(PipeStageables.RING_WRITE_VALID) := xHaltOrTrap
  x.up(PipeStageables.RING_WRITE_DATA) := xHaltWord
  x.up(PipeStageables.PC_REDIRECT_VALID) := xHaltOrTrap
  x.up(PipeStageables.PC_REDIRECT_TARGET) := x(PipeStageables.PC)

  // Stall X when ring write is back-pressured.
  x.haltWhen(x(PipeStageables.RING_WRITE_VALID) && !io.ringWrite.ready)

  // --------------------------------------------------------------------------
  // W: Writeback
  // --------------------------------------------------------------------------

  // Commit RegFile write (no writers in C.6; wiring stays for C.7+).
  regFile.io.writeEnable := w.down.isFiring && w(PipeStageables.WRITES_REG)
  regFile.io.writeAddr := w(PipeStageables.WRITE_REG_ADDR)
  regFile.io.writeData := w(PipeStageables.WRITE_REG_DATA)

  // Commit ring push and advance ring pointer.
  when(w.down.isFiring && w(PipeStageables.RING_WRITE_VALID)) {
    io.ringWrite.valid := True
    io.ringWrite.payload.addr :=
      (U(resultBase, spramAddrWidth bits) +
        ringWrPtr.resize(spramAddrWidth bits)).resized
    io.ringWrite.payload.data := w(PipeStageables.RING_WRITE_DATA)
    when(ringWrPtr < U(resultWordCount - 1, ringPtrWidth bits)) {
      ringWrPtr := ringWrPtr + 1
    } otherwise {
      ringOverflow := True
    }
  }

  // Commit halt.
  when(w.down.isFiring && w(PipeStageables.HALT_REQUEST)) {
    haltedReg := True
    // Status lives at [27:23] of the ring word (spec §11).
    haltStatusReg := w(PipeStageables.RING_WRITE_DATA)(27 downto 23).asUInt
  }

  // Stall W when ring write is back-pressured (commit point must not advance).
  w.haltWhen(w(PipeStageables.RING_WRITE_VALID) && !io.ringWrite.ready)

  // Flush pipeline on commit of a halt: squash F1/F2/D/R/X bubbles.
  // haltedReg gates F1 from issuing new reads after the flush resolves.
  //
  // CPU-style flush pattern (spinal.lib.misc.pipeline):
  //   Component-scope `flush` register raised from W's HALT commit path;
  //   all upstream CtrlLinks receive throwWhen(flush, usingReady=true).
  val flush = False
  when(w.down.isFiring && w(PipeStageables.HALT_REQUEST)) {
    flush := True
  }
  for (upstream <- List(f1, f2, d, r, x)) {
    upstream.throwWhen(flush, usingReady = true)
  }

  // --------------------------------------------------------------------------
  // Build — assemble all CtrlLinks and StageLinks.
  // --------------------------------------------------------------------------
  Builder(f1, f2, d, r, x, w, f1f2, f2d, dr, rx, xw)
}
