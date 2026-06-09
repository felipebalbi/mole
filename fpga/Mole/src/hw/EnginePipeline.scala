package mole

import spinal.core._
import spinal.lib._
import spinal.lib.misc.pipeline._

/** STATUS_TRAP constant (spec §11): engine trap status value 0x1F. */
object StatusCode {
  val TRAP: Int = 0x1f
  // STATUS_STRETCH_TIMEOUT: status 0x0D, matching v0 engine behaviour
  // (stretch-timeout HALT, PP-class slave stretch violation).
  val STRETCH_TIMEOUT: Int = 13
}

/** Payloads flowing through the F → D → R → X → W pipeline.
  *
  * C.6 catalog: enough to support HALT (executes) and all other opcodes (trap
  * to STATUS_TRAP). C.7 extends this catalog with WIRE execution payloads.
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

  /** True when the decoded opcode must trap (any unimplemented opcode in C.7).
    */
  val IS_TRAP = Payload(Bool())

  /** True when the decoded opcode is CTRL.BRANCH_ON (group=01, sub=0001). */
  val IS_BRANCH_ON = Payload(Bool())

  /** True when the decoded opcode is CTRL.WAIT_ON (group=01, sub=0010). */
  val IS_WAIT_ON = Payload(Bool())

  /** True when the decoded opcode is CTRL.FLAG_CLEAR (group=01, sub=0101). */
  val IS_FLAG_CLEAR = Payload(Bool())

  /** True when the decoded opcode is CTRL.MARK (group=01, sub=0110). */
  val IS_MARK = Payload(Bool())

  /** True when the decoded opcode is CTRL.LOAD_TIMING (group=01, sub=0111). */
  val IS_LOAD_TIMING = Payload(Bool())

  /** BRANCH_ON / WAIT_ON 4-bit cond code, decoded at D from `[16:13]`. */
  val COND_CODE = Payload(UInt(4 bits))

  /** BRANCH_ON signed 10-bit PC-relative offset, decoded at D from `[12:3]`. */
  val BRANCH_OFFSET = Payload(SInt(10 bits))

  /** WAIT_ON unsigned 10-bit timeout, decoded at D from `[12:3]`. */
  val WAIT_TIMEOUT = Payload(UInt(10 bits))

  /** FLAG_CLEAR 5-bit mask, decoded at D from `[7:3]`. Bit N clears flag N. */
  val FLAG_CLEAR_MASK = Payload(UInt(5 bits))

  /** MARK 14-bit label, decoded at D from `[16:3]`. */
  val MARK_LABEL = Payload(UInt(14 bits))

  /** LOAD_TIMING 3-bit register selector, decoded at D from `[19:17]`. */
  val LOAD_TIMING_REG = Payload(UInt(3 bits))

  /** LOAD_TIMING 14-bit divider, decoded at D from `[16:3]`. */
  val LOAD_TIMING_DIVIDER = Payload(UInt(14 bits))

  /** 5-bit halt status: extracted from instruction [7:3] for HALT, or
    * STATUS_TRAP (0x1F) for trap cases.
    */
  val HALT_STATUS = Payload(UInt(5 bits))

  /** Register A read address (for WIRE/DATA opcodes). */
  val READ_REG_A_ADDR = Payload(UInt(3 bits))

  /** Register B read address (for EMIT_BYTE_REG: R7). */
  val READ_REG_B_ADDR = Payload(UInt(3 bits))

  /** True when the current instruction reads register A. */
  val READS_REG_A = Payload(Bool())

  /** True when the current instruction reads register B. */
  val READS_REG_B = Payload(Bool())

  /** True for EMIT_BYTE_IMM (WIRE sub=0x9). Lets the X-stage WIRE entry
    * distinguish IMM (data from instruction word) from REG (data from R7).
    */
  val IS_EMIT_BYTE_IMM = Payload(Bool())

  /** EMIT_BYTE_IMM data byte: `dInsn[10:3]`. Valid only when `IS_EMIT_BYTE_IMM`
    * is set; otherwise undefined.
    */
  val EMIT_BYTE_IMM_DATA = Payload(Bits(8 bits))

  /** True when the E-stage instruction is a stall-inducing load-use producer.
    * Always False in C.7.
    */
  val IS_LOAD_USE = Payload(Bool())

  // ---- R → X/W stages ---------------------------------------------------

  /** Forwarded register-A value (combinational from RegFile port 0). */
  val REG_A_VALUE = Payload(Bits(32 bits))

  /** Forwarded register-B value (combinational from RegFile port 1). */
  val REG_B_VALUE = Payload(Bits(32 bits))

  // ---- X → W stage ------------------------------------------------------

  /** True when this instruction writes a register in W. */
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
  * C.7 execution:
  *   - HALT executes and pushes a HALT word to the result ring.
  *   - All 9 WIRE-group opcodes execute via per-quarter mini-FSMs in X.
  *   - CTRL/DATA opcodes still trap to STATUS_TRAP pending C.8/C.9.
  *
  * WIRE opcode execution in X stage: The X stage uses a mini-FSM (xWireState
  * register) with haltWhen() to implement multi-cycle WIRE operations. The
  * QuarterBitTimer paces every quarter-bit boundary. All X-stage output
  * Payloads are assigned unconditionally as defaults (False/0), then overridden
  * by the mini-FSM execution or the HALT/TRAP path.
  *
  * tHD;DAT SDA-pad pipeline: The engine's SDA output registers
  * (sdaDriveLow/sdaDriveHigh) are connected to io.sda via RegNext(). SCL is
  * unpipelined. This gives every SDA edge a one-fabric-cycle lag relative to
  * SCL, satisfying tHD;DAT for MCXA266/RT685 LPI2C (see
  * BitCycleEngineEmitBitDataHoldSim regression).
  *
  * Stretch-aware Q1→Q2 guard (AGENTS §"Quarter-bit is the timing unit"): Inline
  * on X stage (NOT a separate pipeline stage) per the v0 Fmax finding.
  * waitingForStretch register + haltWhen(). Only applies to EMIT_BIT_IMM and
  * EMIT_BIT_REG in controller role under OD-class BUS_MODE. PP-class stretch →
  * STATUS_TRAP immediately (compliance violation).
  *
  * Timing divider (C.7): Fixed at cfg.quarterPeriodCyclesReset - 1 for sim.
  * LOAD_TIMING (C.8) will make the divider runtime-mutable.
  *
  * Sticky flags (spec §7): Written at W stage commit. One-cycle forwarding
  * model: written at W, read from the architectural registers in D. BRANCH_ON /
  * WAIT_ON (C.8) will consume these from the registered path.
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

    // ---- Bus observation -------------------------------------------------
    val sdaSampled = in Bool ()
    val sclSampled = in Bool ()

    // ---- Bus drive -------------------------------------------------------
    val sda = master(MoleBusLine())
    val scl = master(MoleBusLine())

    // ---- Engine role -----------------------------------------------------
    val role = out Bool ()

    // ---- Engine status outputs ------------------------------------------
    val halted = out Bool ()
    val haltStatus = out UInt (5 bits)
    val mismatchFlag = out Bool ()
    val timeoutFlag = out Bool ()
    val startFlag = out Bool ()
    val stopFlag = out Bool ()

    // ---- Loader interface ------------------------------------------------
    val programLength = in UInt (programLenWidth bits)
    val engineStart = in Bool ()
  }

  // --------------------------------------------------------------------------
  // Architectural registers (live outside pipeline stages)
  // --------------------------------------------------------------------------

  val haltedReg = Reg(Bool()) init False
  val haltStatusReg = Reg(UInt(5 bits)) init 0

  // Sticky engine flags (spec §7).
  // One-cycle forwarding model: written at W, read from these regs in D.
  // mismatchFlagReg / timeoutFlagReg are written by the WIRE mini-FSM.
  // startFlagReg / stopFlagReg are written by the always-on bus-observer
  // edge detectors below (C.8).
  val mismatchFlagReg = Reg(Bool()) init False
  val timeoutFlagReg = Reg(Bool()) init False
  val startFlagReg = Reg(Bool()) init False
  startFlagReg.allowUnsetRegToAvoidLatch()
  val stopFlagReg = Reg(Bool()) init False
  stopFlagReg.allowUnsetRegToAvoidLatch()

  // REG_ZERO_FLAG (spec §7 bit 4): set by the most recent flag-writing DATA
  // opcode when its result is zero. Writers (DEC, ADD_IMM, MOV, LOAD_IMM,
  // AND_IMM, OR_IMM, XOR_IMM, SHIFT) land in C.9. Reader (BRANCH_ON
  // REG_ZERO / NOT_REG_ZERO) is wired in C.8 via the cond evaluator below.
  // Declared now so the cond evaluator has a stable read path.
  val regZeroFlagReg = Reg(Bool()) init False
  regZeroFlagReg.allowUnsetRegToAvoidLatch()

  // roleReg: mutable via SET_ROLE in C.8.
  // Encoding (matching v0 BitCycleEngineCore): False=Controller, True=Target.
  // Boot default from cfg.role. Both arms elaborate unconditionally
  // (AGENTS §"Exception: MoleConfig.role") to support runtime role switch.
  val roleReg = Reg(Bool()) init (cfg.role == EngineRole.Target)
  roleReg.allowUnsetRegToAvoidLatch()

  // BUS_MODE register: set by SET_BUS_MODE (C.8). Default i2c on reset.
  val busModeReg = Reg(BusMode()) init (BusMode.i2c)
  busModeReg.allowUnsetRegToAvoidLatch()

  val ringPtrWidth: Int = log2Up(resultWordCount + 1)
  val ringWrPtr = Reg(UInt(ringPtrWidth bits)) init 0
  val ringOverflow = Reg(Bool()) init False

  // Bus driver registers (AGENTS §"Registered drivers"; no releaseAll()).
  // Written by WIRE opcode execution. The bus-shaped FSM idiom: only the
  // WIRE mini-FSM writes these; they hold their last value between opcodes.
  val sdaDriveLow = Reg(Bool()) init False
  val sdaDriveHigh = Reg(Bool()) init False
  val sclDriveLow = Reg(Bool()) init False
  val sclDriveHigh = Reg(Bool()) init False

  // tHD;DAT SDA-pad pipeline: one-cycle delay on SDA outputs only.
  // SCL is NOT pipelined (AGENTS §"Quarter-bit is the timing unit on the wire"
  // and the v0 BitCycleEngineEmitBitDataHoldSim regression).
  // Placing the pipeline at the pad boundary (not inside the FSM) keeps no
  // extra mux levels on the critical path.
  io.sda.driveLow := RegNext(sdaDriveLow) init False
  io.sda.driveHigh := RegNext(sdaDriveHigh) init False
  io.scl.driveLow := sclDriveLow
  io.scl.driveHigh := sclDriveHigh
  // io.sda.read and io.scl.read are inputs: wired from pad by MoleTop.

  // Defense-in-depth: bus contention asserts (sim-only per SpinalHDL).
  assert(
    !(sdaDriveLow && sdaDriveHigh),
    "EnginePipeline: SDA bus contention (driveLow && driveHigh both set)"
  )
  assert(
    !(sclDriveLow && sclDriveHigh),
    "EnginePipeline: SCL bus contention (driveLow && driveHigh both set)"
  )

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
  // Bus observer (2-FF synchronizer on SDA/SCL inputs)
  // --------------------------------------------------------------------------
  val observer = BusObserver(io.sda.read, io.scl.read)

  // START/STOP sticky-flag writers (C.8, spec §7).
  // The observer emits one-cycle pulses on `startEdge` / `stopEdge`. These
  // are sticky flags set asynchronously by the engine; clearing requires
  // FLAG_CLEAR (handled in W below). The writers here are conditional set-
  // only; X-stage FLAG_CLEAR commits at W and may clear them on the same
  // cycle — clear wins by virtue of FLAG_CLEAR's W-stage assignment running
  // after this set (Scala last-assignment-wins on a single Reg).
  when(observer.startEdge) { startFlagReg := True }
  when(observer.stopEdge) { stopFlagReg := True }

  // --------------------------------------------------------------------------
  // Register file
  // --------------------------------------------------------------------------
  val regFile = RegFile(width = 32, depth = 8)

  // --------------------------------------------------------------------------
  // QuarterBitTimer
  //
  // Sized at maxReloadValue = (1 << 9) - 1 = 511 (matching v0 design).
  // C.7: fixed reload = cfg.quarterPeriodCyclesReset - 1 for all bus modes.
  // C.8's LOAD_TIMING will introduce per-mode timing registers that override
  // this default. Until then, all EMIT_*/STRETCH_SCL operate at the reset rate.
  val timer = QuarterBitTimer(maxReloadValue = (1 << 9) - 1)
  // timerEnable is combinatorial (driven by the mini-FSM body; no loop risk
  // since it doesn't feed timer.io.tick back to itself).
  // timerLoad uses a registered path to break any combinatorial loop through
  // timer.io.tick → timerLoad → timer.io.load. The registered flag fires one
  // cycle after the mini-FSM requests a reload; this is correct because:
  //   - Entry-to-state loads (from WS_IDLE dispatch) fire on the cycle the
  //     mini-FSM starts; the next cycle the timer begins counting.
  //   - Intra-EMIT_BYTE bit-advance loads fire the cycle after the Q3 tick
  //     (the new Q0 starts with the timer freshly loaded).
  //   - Stretch-resume loads fire the cycle after SCL-rising is observed.
  // The one-cycle delay is consistent with the v0 design (state transitions
  // in the monolithic FSM always had the timerLoad in onEntry, which became
  // effective on the same clock edge as the state register change).
  val timerEnable = Bool()
  timerEnable := False
  // timerLoadReg: set by the mini-FSM; consumed by timer.io.load directly.
  // The mini-FSM writes timerLoadReg := True to request a load; it's a
  // registered Reg so there's no combinatorial path from tick back to load.
  val timerLoadReg = Reg(Bool()) init False
  // Default: auto-clear each cycle (one-cycle pulse).
  timerLoadReg := False

  // --------------------------------------------------------------------------
  // LOAD_TIMING register file (C.8, spec §5.17)
  //
  // 8 timing registers, each 14 bits. Indexed by `reg=0..7`. Odd regs
  // (1, 3, 5, 7) are setup/hold time slots — reserved in C.8 (writable but
  // not consumed by the WIRE FSM yet). Even regs (0, 2, 4, 6) are the
  // quarter-bit-clock dividers selected by the active BUS_MODE per spec §5.17.
  //
  // All 8 reset to `cfg.quarterPeriodCyclesReset - 1` so a program that
  // never issues LOAD_TIMING behaves exactly as the C.7 fixed-divider engine.
  // --------------------------------------------------------------------------
  val loadTimingRegs = Vec(
    Reg(UInt(14 bits)) init (cfg.quarterPeriodCyclesReset - 1),
    8
  )

  // Active quarter-bit divider, selected by busModeReg per spec §5.17 table.
  // i2c       → reg 0
  // i3c-OD    → reg 2
  // i3c-PP    → reg 4
  // hdr-ddr   → reg 6
  val activeDivider = UInt(14 bits)
  activeDivider := loadTimingRegs(0) // default; switch below picks final.
  switch(busModeReg) {
    is(BusMode.i2c) { activeDivider := loadTimingRegs(0) }
    is(BusMode.i3cOd) { activeDivider := loadTimingRegs(2) }
    is(BusMode.i3cPp) { activeDivider := loadTimingRegs(4) }
    is(BusMode.hdrDdr) { activeDivider := loadTimingRegs(6) }
  }
  timer.io.reload := activeDivider.resize(timer.counterWidth bits)
  timer.io.load := timerLoadReg
  timer.io.enable := timerEnable

  // --------------------------------------------------------------------------
  // Free-running quarter-bit timestamp (C.8, spec §5.16)
  //
  // 32-bit timestamp consumed by MARK records. Increments on `timer.io.tick`
  // — i.e. once per quarter-bit boundary while the timer is enabled.
  //
  // SPEC GAP (v0.2 §5.16): "free-running" implies the timestamp should tick
  // even between emits, but the `QuarterBitTimer` only ticks during WIRE
  // mini-FSM execution (when `timerEnable` is set). In practice MARK records
  // measure quarter-bit time *spanned by emits*, which is the only meaningful
  // wall-clock measure for the test (idle dead-time has no quarter-bit
  // unit). Add as `docs(spec)` follow-up to either tighten the wording or
  // add a separate always-on counter at a defined rate.
  val quarterTimestampReg = Reg(UInt(32 bits)) init 0
  when(timer.io.tick) {
    quarterTimestampReg := quarterTimestampReg + 1
  }

  // --------------------------------------------------------------------------
  // PC register (owned by F1)
  // --------------------------------------------------------------------------
  val pcReg = Reg(UInt(progAddrWidth bits)) init 0

  // fetchActive: engine is running, not halted, in-range, AND no
  // HALT/trap is in flight downstream.
  //
  // The last clause prevents an SPRAM-read/result-write deadlock: the
  // SPRAM controller's `resultWrite.ready := !readCmd.valid` arbitration
  // means F1's continuous reads back-pressure W's ring write. If F1 keeps
  // fetching while X is committing a HALT/trap to W, W stalls waiting
  // for ringWrite.ready, X stalls behind W, and the engine never halts.
  // Suppressing fetches when any downstream stage has HALT_REQUEST in
  // its payload drains the SPRAM read port for the cycle W needs to
  // commit the HALT word, then haltedReg goes True and fetching stays
  // off permanently. Without this, programs that BRANCH_ON-trap (or
  // that simply HALT while pcReg is still well inside programLength)
  // never observe the halt.
  //
  // The in-flight HALT check itself is wired below (after the CtrlLinks
  // are declared); we declare a Bool now and assign it later.
  val haltInFlight = Bool()
  val fetchActive = io.engineStart && !haltedReg && !haltInFlight &&
    (pcReg < io.programLength.resize(progAddrWidth bits))

  // --------------------------------------------------------------------------
  // Ring write port — default idle; W drives it.
  // --------------------------------------------------------------------------
  io.ringWrite.valid := False
  io.ringWrite.payload.addr := U(resultBase, spramAddrWidth bits)
  io.ringWrite.payload.data := B(0, 32 bits)

  // --------------------------------------------------------------------------
  // X-stage WIRE mini-FSM state registers
  //
  // xWireState: 0=IDLE, 1=EMIT_BIT, 2=EMIT_QUARTER, 3=EMIT_BYTE (both REG
  //             at sub=0x4 and IMM at sub=0x9 reuse the same running state),
  //             4=SAMPLE_BIT, 5=DRIVE_BIT, 6=STRETCH_SCL.
  // These are component-scope Regs (not pipeline Payloads) because the WIRE
  // mini-FSM persists across the multiple cycles that X is stalled.
  // --------------------------------------------------------------------------
  val WS_IDLE: Int = 0
  val WS_EMIT_BIT: Int = 1
  val WS_EMIT_QUARTER: Int = 2
  val WS_EMIT_BYTE: Int = 3
  val WS_SAMPLE_BIT: Int = 4
  val WS_DRIVE_BIT: Int = 5
  val WS_STRETCH_SCL: Int = 6

  val xWireState = Reg(UInt(3 bits)) init WS_IDLE
  val xQIdx = Reg(UInt(2 bits)) init 0 // quarter index: 0..3
  val xByteIdx = Reg(UInt(4 bits)) init 0 // byte bit-index: 0..8
  val xStretchCount = Reg(UInt(14 bits)) init 0 // STRETCH_SCL countdown
  val xDriveBitPhase = Reg(UInt(2 bits)) init 0 // DRIVE_BIT sub-phase 0..2
  val xByteDataReg =
    Reg(Bits(8 bits)) init 0 // EMIT_BYTE data: R7 (REG) or imm (IMM)
  val xCapturedBit = Reg(Bool()) init False // captured bit from WIRE op

  // --------------------------------------------------------------------------
  // X-stage CTRL state registers (C.8)
  //
  // xMarkPhase: 0=IDLE/COMMIT_HEADER, 1=COMMIT_TS_LO, 2=COMMIT_TS_HI.
  //   After phase-2 commit (or skip-on-overflow), phase resets directly
  //   to 0 and xMarkDone pulses for one cycle to release X. There is no
  //   separate "DONE" phase value — xMarkDone is the done marker.
  //   (Earlier C.8 drafts used phase=3 as DONE but the reset-to-0 was
  //   gated on xMarkActive, which goes False the same cycle xMarkDone
  //   goes True, so phase wedged at 3 and the next MARK in X silently
  //   dropped all 3 ring writes.)
  //
  // The MARK 3-word commit is an inline X-stage mini-FSM. X stalls
  // (haltWhen) for the 3 cycles needed to emit header / ts_lo / ts_hi to W.
  // We latch the timestamp at COMMIT_HEADER entry so all three words share
  // the same atomic timestamp, even if the timer ticks mid-commit.
  // --------------------------------------------------------------------------
  val xMarkPhase = Reg(UInt(2 bits)) init 0
  // MARK label is read directly from the X-stage payload (instruction is
  // held in X for the full 3-cycle commit while xMarkStalling stalls X),
  // so no separate latch reg is needed. Timestamp IS latched because it
  // would otherwise advance during the commit.
  val xMarkTimestampReg = Reg(UInt(32 bits)) init 0

  // WAIT_ON state:
  //   xWaitOnActive    — True while a WAIT_ON is spinning in X.
  //   xWaitOnCounter   — remaining quarter-bit ticks until timeout.
  //   xWaitOnInfinite  — True if timeout=0 (wait forever; no countdown).
  val xWaitOnActive = Reg(Bool()) init False
  val xWaitOnCounter = Reg(UInt(10 bits)) init 0
  val xWaitOnInfinite = Reg(Bool()) init False

  // Inline stretch-wait guard registers (AGENTS §"Stretch-aware Q2 entry").
  // Not a separate pipeline stage — inline on the X stage (preserves Fmax).
  val waitingForStretch = Reg(Bool()) init False

  // 21-bit stretch-timeout counter. Width 21 so cfg.stretchTimeoutCycles =
  // 1<<20 fits as a literal. Init 0 = "already expired" safe default.
  val stretchTimeoutCtr = Reg(UInt(21 bits)) init 0

  // Pipelined zero-comparator for stretch timeout (preserves Fmax per v0
  // finding: 21-input OR-reduction on FSM critical path cost ~5 MHz on
  // UP5K-SG48 at all seeds). Init True = "already expired" safe default.
  val stretchTimeoutCtrIsZero = Reg(Bool()) init True
  stretchTimeoutCtrIsZero := (stretchTimeoutCtr === 0)

  // X-stage pending halt/trap/capture outputs. These are combinatorial signals
  // set by the mini-FSM body and consumed by the pipeline Payload assignments
  // below. Using Scala Bools that drive the x.up() payloads unconditionally.
  val xWireHaltReq = Bool()
  val xWireHaltWord = Bits(32 bits)
  val xWireWritesReg = Bool()
  val xWireWriteAddr = UInt(3 bits)
  val xWireWriteData = Bits(32 bits)
  xWireHaltReq := False
  xWireHaltWord := B(0, 32 bits)
  xWireWritesReg := False
  xWireWriteAddr := U(0, 3 bits)
  xWireWriteData := B(0, 32 bits)

  // Helper to build a HALT word (spec §11):
  //   [31:30]=11 [29]=overflow [28]=mismatch [27:23]=status [22:0]=0
  def makeHaltWord(status: UInt, mismatch: Bool): Bits =
    B"11" ## ringOverflow.asBits ## mismatch.asBits ##
      status.asBits ## B(0, 23 bits)

  def makeTrapWord(): Bits =
    makeHaltWord(U(StatusCode.TRAP, 5 bits), mismatchFlagReg)

  def makeStretchTimeoutWord(forceMismatch: Bool): Bits =
    makeHaltWord(U(StatusCode.STRETCH_TIMEOUT, 5 bits), forceMismatch)

  // --------------------------------------------------------------------------
  // Pipeline construction — spinal.lib.misc.pipeline
  // --------------------------------------------------------------------------
  val f1, f2, d, r, x, w = CtrlLink()

  val f1f2 = StageLink(f1.down, f2.up)
  val f2d = StageLink(f2.down, d.up)
  val dr = StageLink(d.down, r.up)
  val rx = StageLink(r.down, x.up)
  val xw = StageLink(x.down, w.up)

  // --------------------------------------------------------------------------
  // F1: Fetch Request
  // --------------------------------------------------------------------------

  f1.up.valid := True

  io.spramRead.valid := fetchActive
  io.spramRead.payload := pcReg.resize(spramAddrWidth bits)

  f1.haltWhen(!fetchActive || !io.spramRead.ready)

  f1.up(PipeStageables.PC) := pcReg.resize(13 bits)

  when(f1.down.isFiring) {
    pcReg := pcReg + 1
  }

  // --------------------------------------------------------------------------
  // F2: Fetch Response
  //
  // F2 must latch the SPRAM response into a register on the cycle the
  // response arrives. The SPRAM response port (`io.spramResp.payload`) is
  // the live SPRAM bus and changes whenever a newer F1 read issues; if F2
  // exposes `io.spramResp.payload` combinationally as its INSTRUCTION
  // payload, then the next F1 fetch can silently mutate F2's "held"
  // instruction while X is stalling downstream. The result is that the
  // instruction in D (and beyond) shifts forward in the program by however
  // many F1 fetches happened during the stall — the program drops
  // instructions silently.
  //
  // The fix: track whether THIS f2 transaction has captured its response
  // yet. On the cycle f1 fires (the f1→f2 StageLink fills f2), schedule a
  // pending-response. One cycle later the SPRAM response arrives and we
  // latch it into f2InstrReg, marking f2InstrValid. F2 halts until the
  // latch is valid; once valid, F2 holds the instruction stable for as
  // long as downstream is stalled.
  // --------------------------------------------------------------------------

  val f2InstrReg = Reg(Bits(32 bits)) init 0
  val f2InstrValid = Reg(Bool()) init False
  val f2RespPending = Reg(Bool()) init False

  // Default: hold state.
  // f2RespPending: set when f1 fires (a SPRAM read is in flight for this
  //                f2 transaction). Cleared when the response is latched.
  // f2InstrValid:  True once the latch holds the response for the current
  //                f2 transaction. Cleared on f2.down.isFiring.
  when(f1.down.isFiring) {
    f2RespPending := True
  }
  when(f2RespPending && io.spramResp.valid) {
    f2InstrReg := io.spramResp.payload
    f2InstrValid := True
    f2RespPending := False
  }
  when(f2.down.isFiring) {
    f2InstrValid := False
  }

  f2.haltWhen(f2.isValid && !f2InstrValid)

  f2.up(PipeStageables.INSTRUCTION) := f2InstrReg

  // --------------------------------------------------------------------------
  // D: Decode
  // --------------------------------------------------------------------------

  val dInsn = d(PipeStageables.INSTRUCTION)
  val dGroup = dInsn(31 downto 30).asUInt
  val dSub = dInsn(29 downto 26).asUInt

  d.up(PipeStageables.OPCODE_GROUP) := dGroup
  d.up(PipeStageables.OPCODE_SUB) := dSub

  // CTRL.HALT: group=0b01 (1), sub=0b0000 (0).
  val dIsHalt = (dGroup === 1) && (dSub === 0)
  // WIRE group: group=0b00 (0). Sub-opcodes 0x0..0x9 are live (10 total:
  // the 9 from spec §4 plus EMIT_BYTE_IMM at sub=0x9); 0xA..0xF reserved.
  val dIsWire = (dGroup === 0)
  val dIsWireLive = dIsWire && (dSub <= 9)
  // SET_BUS_MODE (CTRL sub=0x3) and SET_ROLE (CTRL sub=0x4): implemented in C.7
  // because they are required by nearly every WIRE test program.
  val dIsSetBusMode = (dGroup === 1) && (dSub === 3)
  val dIsSetRole = (dGroup === 1) && (dSub === 4)
  // C.8 CTRL opcodes: BRANCH_ON, WAIT_ON, FLAG_CLEAR, MARK, LOAD_TIMING.
  val dIsBranchOn = (dGroup === 1) && (dSub === 1)
  val dIsWaitOn = (dGroup === 1) && (dSub === 2)
  val dIsFlagClear = (dGroup === 1) && (dSub === 5)
  val dIsMark = (dGroup === 1) && (dSub === 6)
  val dIsLoadTiming = (dGroup === 1) && (dSub === 7)
  // CTRL/DATA non-HALT non-WIRE → trap, EXCEPT the live CTRL opcodes above.
  // DATA group implementation lands in C.9; for now traps to STATUS_TRAP.
  val dIsTrap =
    !dIsHalt && !dIsWireLive && !dIsSetBusMode && !dIsSetRole &&
      !dIsBranchOn && !dIsWaitOn && !dIsFlagClear && !dIsMark &&
      !dIsLoadTiming

  d.up(PipeStageables.IS_HALT) := dIsHalt
  d.up(PipeStageables.IS_TRAP) := dIsTrap
  d.up(PipeStageables.IS_BRANCH_ON) := dIsBranchOn
  d.up(PipeStageables.IS_WAIT_ON) := dIsWaitOn
  d.up(PipeStageables.IS_FLAG_CLEAR) := dIsFlagClear
  d.up(PipeStageables.IS_MARK) := dIsMark
  d.up(PipeStageables.IS_LOAD_TIMING) := dIsLoadTiming

  // CTRL.BRANCH_ON / WAIT_ON shared cond code at [16:13] (4 bits, spec §6).
  d.up(PipeStageables.COND_CODE) := dInsn(16 downto 13).asUInt
  // BRANCH_ON signed 10-bit offset at [12:3].
  d.up(PipeStageables.BRANCH_OFFSET) := dInsn(12 downto 3).asSInt
  // WAIT_ON unsigned 10-bit timeout at [12:3].
  d.up(PipeStageables.WAIT_TIMEOUT) := dInsn(12 downto 3).asUInt
  // FLAG_CLEAR 5-bit mask at [7:3].
  d.up(PipeStageables.FLAG_CLEAR_MASK) := dInsn(7 downto 3).asUInt
  // MARK 14-bit label at [16:3].
  d.up(PipeStageables.MARK_LABEL) := dInsn(16 downto 3).asUInt
  // LOAD_TIMING 3-bit reg selector at [19:17], 14-bit divider at [16:3].
  d.up(PipeStageables.LOAD_TIMING_REG) := dInsn(19 downto 17).asUInt
  d.up(PipeStageables.LOAD_TIMING_DIVIDER) := dInsn(16 downto 3).asUInt

  // HALT status from instruction [7:3]; STATUS_TRAP for traps; 0 for WIRE.
  val dHaltStatus = dInsn(7 downto 3).asUInt
  d.up(PipeStageables.HALT_STATUS) := Mux(
    dIsHalt,
    dHaltStatus,
    Mux(dIsTrap, U(StatusCode.TRAP, 5 bits), U(0, 5 bits))
  )

  // Register operand decoding.
  // REG_A: src at [22:20] for EMIT_BIT_REG, EMIT_QUARTER_REG, STRETCH_SCL_REG,
  //        DRIVE_BIT_ON_SCL.
  // REG_B: always R7 (for EMIT_BYTE_REG implicit R7 data read).
  val dRegAAddr = dInsn(22 downto 20).asUInt
  d.up(PipeStageables.READ_REG_A_ADDR) := dRegAAddr
  d.up(PipeStageables.READ_REG_B_ADDR) := U(7, 3 bits)

  val dEmitBitReg = dIsWire && (dSub === 1)
  val dEmitQuarterReg = dIsWire && (dSub === 3)
  val dEmitByteReg = dIsWire && (dSub === 4)
  val dEmitByteImm = dIsWire && (dSub === 9)
  val dStretchSclReg = dIsWire && (dSub === 8)
  val dDriveBitOnScl = dIsWire && (dSub === 6)

  d.up(PipeStageables.READS_REG_A) :=
    dEmitBitReg || dEmitQuarterReg || dStretchSclReg || dDriveBitOnScl
  // EMIT_BYTE_REG (sub=0x4) implicitly reads R7 for the data byte.
  // EMIT_BYTE_IMM (sub=0x9) does NOT read R7 — data is in dInsn[10:3] —
  // so it bypasses the load-use hazard path.
  d.up(PipeStageables.READS_REG_B) := dEmitByteReg
  d.up(PipeStageables.IS_LOAD_USE) := False

  // EMIT_BYTE_IMM cross-stage info: detect + data byte from [10:3].
  d.up(PipeStageables.IS_EMIT_BYTE_IMM) := dEmitByteImm
  d.up(PipeStageables.EMIT_BYTE_IMM_DATA) := dInsn(10 downto 3)

  // --------------------------------------------------------------------------
  // R: Register Read
  // --------------------------------------------------------------------------

  regFile.io.readAddr0 := r(PipeStageables.READ_REG_A_ADDR)
  regFile.io.readAddr1 := r(PipeStageables.READ_REG_B_ADDR)

  r.up(PipeStageables.REG_A_VALUE) := regFile.io.readData0
  r.up(PipeStageables.REG_B_VALUE) := regFile.io.readData1

  r.haltWhen(regFile.io.loadUseStall)

  regFile.io.eValid := x.isValid && x(PipeStageables.WRITES_REG)
  regFile.io.eWriteAddr := x(PipeStageables.WRITE_REG_ADDR)
  regFile.io.eIsLoadUse := x(PipeStageables.IS_LOAD_USE)

  // --------------------------------------------------------------------------
  // X: Execute
  //
  // C.7: HALT executes; all 9 WIRE opcodes execute via inline mini-FSM;
  // CTRL/DATA (non-HALT) still trap to STATUS_TRAP.
  //
  // WIRE mini-FSM overview:
  //   The X stage stalls (haltWhen) while xWireState != WS_IDLE.
  //   On the first cycle in X with a WIRE instruction (xWireState == WS_IDLE):
  //     - The mini-FSM initialises xWireState and related state regs.
  //     - If the mini-FSM completes in one cycle (e.g. EMIT_QUARTER), it resets
  //       xWireState to WS_IDLE in the same cycle, so the pipeline advances
  //       on the next cycle.
  //     - Multi-cycle operations set xWireState to a running state; X remains
  //       stalled until the mini-FSM resets xWireState to WS_IDLE.
  //
  // All X-stage output Payloads are assigned unconditionally to safe defaults
  // first (outside any when() block). The HALT/TRAP path and the WIRE inline
  // trap paths override them.
  // --------------------------------------------------------------------------

  // ---- Unconditional defaults for all X-stage output Payloads ---------------
  // These must be assigned unconditionally to avoid SpinalHDL latch detection.
  x.up(PipeStageables.WRITES_REG) := False
  x.up(PipeStageables.WRITE_REG_ADDR) := U(0, 3 bits)
  x.up(PipeStageables.WRITE_REG_DATA) := B(0, 32 bits)
  x.up(PipeStageables.HALT_REQUEST) := False
  x.up(PipeStageables.RING_WRITE_VALID) := False
  x.up(PipeStageables.RING_WRITE_DATA) := B(0, 32 bits)
  x.up(PipeStageables.PC_REDIRECT_VALID) := False
  x.up(PipeStageables.PC_REDIRECT_TARGET) := U(0, 13 bits)

  // Decode helpers for X stage.
  // xIsWire: True only for WIRE-group instructions (group=0b00).
  // SET_BUS_MODE and SET_ROLE are CTRL group; treated separately below.
  val xIsWire = (x(PipeStageables.OPCODE_GROUP) === 0) &&
    !x(PipeStageables.IS_TRAP)
  val xIsSetBusMode = (x(PipeStageables.OPCODE_GROUP) === 1) &&
    (x(PipeStageables.OPCODE_SUB) === 3) && !x(PipeStageables.IS_TRAP)
  val xIsSetRole = (x(PipeStageables.OPCODE_GROUP) === 1) &&
    (x(PipeStageables.OPCODE_SUB) === 4) && !x(PipeStageables.IS_TRAP)
  // C.8 CTRL opcode handles (live only when !IS_TRAP).
  val xIsBranchOn = x(PipeStageables.IS_BRANCH_ON) && !x(PipeStageables.IS_TRAP)
  val xIsWaitOn = x(PipeStageables.IS_WAIT_ON) && !x(PipeStageables.IS_TRAP)
  val xIsFlagClear =
    x(PipeStageables.IS_FLAG_CLEAR) && !x(PipeStageables.IS_TRAP)
  val xIsMark = x(PipeStageables.IS_MARK) && !x(PipeStageables.IS_TRAP)
  val xIsLoadTiming =
    x(PipeStageables.IS_LOAD_TIMING) && !x(PipeStageables.IS_TRAP)
  val xSub = x(PipeStageables.OPCODE_SUB)
  val xInsn = x(PipeStageables.INSTRUCTION)

  // Field decoders (combinational).
  val xEmitBitImmTxRaw = xInsn(4 downto 3) // EMIT_BIT_IMM tx_symbol
  val xEmitQImmSdaRaw = xInsn(4 downto 3) // EMIT_QUARTER_IMM sda
  val xEmitQImmSclRaw = xInsn(6 downto 5) // EMIT_QUARTER_IMM scl
  val xDriveTxRaw = xInsn(4 downto 3) // DRIVE_BIT_ON_SCL tx_symbol
  val xStretchImmN = xInsn(16 downto 3).asUInt // STRETCH_SCL_IMM count
  val xExpect = xInsn(Instruction.EXPECT_BIT) // flag: expect
  val xMask = xInsn(Instruction.MASK_BIT) // flag: mask
  val xCapture = xInsn(Instruction.CAPTURE_BIT) // flag: capture
  val xRegAVal = x(PipeStageables.REG_A_VALUE)
  val xRegBVal = x(PipeStageables.REG_B_VALUE) // R7 data for EMIT_BYTE_REG

  // ---- Cond-code evaluator (C.8, spec §6) --------------------------------
  // Shared between BRANCH_ON and WAIT_ON. Combinational function on the
  // sticky flags + the synchronised bus samples. Reserved codes 12..15 are
  // checked explicitly in the CTRL dispatch below and trap STATUS_TRAP;
  // here they evaluate to False as defence-in-depth.
  //
  // Reads from the *current* architectural Reg values. WIRE flag writers
  // (mismatchFlagReg / timeoutFlagReg) and START/STOP set/clear at the same
  // cycle, so BRANCH_ON/WAIT_ON observe values "one cycle behind" the
  // setting opcode — which is correct since BRANCH_ON/WAIT_ON are by
  // construction a later opcode in program order.
  val xCondCode = x(PipeStageables.COND_CODE)
  val xCondTrue = Bool()
  xCondTrue := False
  switch(xCondCode) {
    is(0) { xCondTrue := True } // ALWAYS
    is(1) { xCondTrue := mismatchFlagReg } // MISMATCH
    is(2) { xCondTrue := !mismatchFlagReg } // NOT_MISMATCH
    is(3) { xCondTrue := startFlagReg } // START_SEEN
    is(4) { xCondTrue := stopFlagReg } // STOP_SEEN
    is(5) { xCondTrue := !observer.sdaSampled } // SDA_LOW (dominant)
    is(6) { xCondTrue := observer.sdaSampled } // SDA_HIGH (recessive)
    is(7) { xCondTrue := observer.sclSampled } // SCL_HIGH
    is(8) { xCondTrue := timeoutFlagReg } // TIMEOUT
    is(9) { xCondTrue := !timeoutFlagReg } // NOT_TIMEOUT
    is(10) { xCondTrue := regZeroFlagReg } // REG_ZERO
    is(11) { xCondTrue := !regZeroFlagReg } // NOT_REG_ZERO
    default { xCondTrue := False } // 12..15 reserved; trap path takes over.
  }
  val xCondReserved = xCondCode >= 12

  // ---- HALT / TRAP path ---------------------------------------------------
  val xHaltOrTrap = x(PipeStageables.IS_HALT) || x(PipeStageables.IS_TRAP)
  val xHaltStatus = x(PipeStageables.HALT_STATUS)
  val xHaltWord = makeHaltWord(xHaltStatus, mismatchFlagReg)

  // xWireDone: one-cycle flag set when the mini-FSM completes. Prevents
  // re-entry into the WS_IDLE entry dispatch on the cycle after completion.
  // Auto-clears each cycle (Reg, default := False driven unconditionally).
  val xWireDone = Reg(Bool()) init False
  xWireDone := False // auto-clear

  // xWireActive: True when the WIRE mini-FSM body should execute.
  // False when xWireDone prevents re-entry.
  val xWireActive = xIsWire && x.isValid && !xWireDone

  // WIRE stall condition: the mini-FSM is running (xWireState != IDLE) OR
  // a WIRE instruction just arrived (xWireState == IDLE but we need to start).
  // The `xWireDone` flag prevents xWireStarting from triggering after
  // the FSM completes (xWireDone=True suppresses xWireActive).
  val xWireStarting = xWireActive && (xWireState === WS_IDLE)
  val xWireRunning = xWireActive && (xWireState =/= WS_IDLE)
  // Stall X while the WIRE mini-FSM is running or starting.
  x.haltWhen(xWireRunning || xWireStarting || waitingForStretch)

  // C.8 CTRL stalls.
  //
  // MARK: 3-cycle inline mini-FSM in X (header/ts_lo/ts_hi). Stall X for
  //   phases 0..2 inclusive. Phase 3 is the one-cycle "done" marker that
  //   lets the pipeline advance and gates re-entry. xMarkDone is the
  //   one-cycle completion marker analogous to xWireDone.
  //
  // WAIT_ON: spin in X every cycle, evaluate cond/timer. xWaitOnDone is
  //   set on the cycle the wait completes (cond true OR timeout fired);
  //   the stall releases that same cycle so X fires once. Subsequent cycles
  //   are gated by xWaitOnDone preventing re-entry.
  val xMarkDone = Reg(Bool()) init False
  xMarkDone := False // auto-clear
  // xMarkActive: this MARK is mid-commit. Stall while phase != 3 and not done.
  val xMarkActive = xIsMark && x.isValid && !xMarkDone
  val xMarkStalling = xMarkActive && (xMarkPhase < 3)
  x.haltWhen(xMarkStalling)

  val xWaitOnDone = Reg(Bool()) init False
  xWaitOnDone := False // auto-clear
  // Set combinationally by the WAIT_ON body below: True the cycle the
  // wait completes. Drives both xWaitOnDone latching and the stall release.
  val xWaitOnCompletes = Bool()
  xWaitOnCompletes := False
  val xWaitOnActiveThisCycle = xIsWaitOn && x.isValid && !xWaitOnDone
  // Stall X for WAIT_ON unless it is completing this cycle.
  x.haltWhen(xWaitOnActiveThisCycle && !xWaitOnCompletes)

  // Stall X when ring write is back-pressured (for halt/trap path).
  x.haltWhen(xHaltOrTrap && !io.ringWrite.ready)

  // MARK back-pressure / W-stage mutex are handled inline in the C.8 MARK
  // direct-ring-drive block (see component-scope block below). xMarkStalling
  // above already keeps X stalled through phases 0..2; phase advance is
  // gated on io.ringWrite.ready inside the direct-drive block, so a
  // back-pressured ring port naturally extends the stall by holding phase.

  // ---- HALT / TRAP outputs ------------------------------------------------
  // Set when IS_HALT or IS_TRAP is true (non-WIRE path).
  // The WIRE inline-trap path overrides x.up() outputs directly in the
  // mini-FSM body below via the `xWireHaltReq` override mechanism.
  when(xHaltOrTrap) {
    x.up(PipeStageables.HALT_REQUEST) := True
    x.up(PipeStageables.RING_WRITE_VALID) := True
    x.up(PipeStageables.RING_WRITE_DATA) := xHaltWord
    x.up(PipeStageables.PC_REDIRECT_VALID) := True
    x.up(PipeStageables.PC_REDIRECT_TARGET) := x(PipeStageables.PC)
  }

  // ---- WIRE capture writeback (driven from component-scope xWireWritesReg) --
  // These are combinatorial wires set by the mini-FSM body (see below).
  // They feed the x.up() Payloads so the WIRE-completed cycle passes the
  // capture data to W.
  // Override the unconditional default assignments when xWireWritesReg is True.
  when(xWireWritesReg) {
    x.up(PipeStageables.WRITES_REG) := True
    x.up(PipeStageables.WRITE_REG_ADDR) := xWireWriteAddr
    x.up(PipeStageables.WRITE_REG_DATA) := xWireWriteData
  }

  // Override HALT/RING outputs for WIRE-inline traps.
  when(xWireHaltReq) {
    x.up(PipeStageables.HALT_REQUEST) := True
    x.up(PipeStageables.RING_WRITE_VALID) := True
    x.up(PipeStageables.RING_WRITE_DATA) := xWireHaltWord
    x.up(PipeStageables.PC_REDIRECT_VALID) := True
    x.up(PipeStageables.PC_REDIRECT_TARGET) := x(PipeStageables.PC)
  }

  // ---- C.8 MARK ring-write data words -------------------------------------
  // MARK records are committed by a direct X→ring drive at component scope
  // (see "C.8 MARK direct ring drive" block below), bypassing the W stage's
  // ring-write payload path. The W path is reserved for one-write-per-
  // instruction flow (HALT, capture-1 WIRE); MARK is multi-write per
  // instruction and would otherwise need a FIFO between X and W. Inline
  // direct drive matches the inline-stretch-guard and per-quarter WIRE
  // FSM precedents (see AGENTS.md §"Pipeline framework note").
  //
  // Header word (phase 0): [31:30] = 0b10 (MARK tag, spec §11), [29:14] = 0,
  // [13:0] = label.
  // ts_lo (phase 1): low 16 bits of timestamp, padded with 0 in [31:16].
  // ts_hi (phase 2): high 16 bits of timestamp, padded with 0 in [31:16].
  // (Two halves rather than one 32-bit slice keeps the host-side decode
  // simple and matches the v0-era 16-bit-grain ring shape the host
  // already parses; the SPRAM word is 32 bits so the upper 16 are zero.)
  // MARK header reads MARK_LABEL straight from the X-stage payload. The
  // MARK instruction is held in X for the entire 3-cycle commit (X stalls
  // via xMarkStalling), so the payload is stable across all phases — no
  // need to latch the label into a separate reg.
  val xMarkHeader =
    B"10" ## B(0, 16 bits) ## x(PipeStageables.MARK_LABEL).asBits
  val xMarkTsLoWord =
    B(0, 16 bits) ## xMarkTimestampReg(15 downto 0).asBits
  val xMarkTsHiWord =
    B(0, 16 bits) ## xMarkTimestampReg(31 downto 16).asBits
  val xMarkRingData = Bits(32 bits)
  switch(xMarkPhase) {
    is(0) { xMarkRingData := xMarkHeader }
    is(1) { xMarkRingData := xMarkTsLoWord }
    is(2) { xMarkRingData := xMarkTsHiWord }
    default { xMarkRingData := B(0, 32 bits) }
  }

  // ---- C.8 BRANCH_ON PC redirect output -----------------------------------
  // Taken branches drive PC_REDIRECT_VALID/TARGET. Untaken branches fall
  // through (no redirect). The W stage extends the existing flush mechanism
  // to consume these and overwrite pcReg.
  //
  // SPEC GAP (v0.2 §5.11): out-of-range branch target behaviour is not
  // specified. v0 trapped (4-bit status 0xF); v0.2 traps with STATUS_TRAP
  // (5-bit 0x1F) per the same principle. Track as docs(spec) follow-up.
  val xBranchOffset = x(PipeStageables.BRANCH_OFFSET)
  // Target PC = branch_pc + 1 + offset. Compute in signed arithmetic so
  // negative offsets work. progAddrWidth bits result for the redirect.
  // branch_pc + 1 + offset can be negative (offset < -branch_pc-1) or
  // overflow programLength; treat both as out-of-range and trap.
  val xBranchPcPlus1 = (x(PipeStageables.PC) + 1).asSInt
  val xBranchTarget =
    (xBranchPcPlus1.resize((progAddrWidth + 2) bits) +
      xBranchOffset.resize((progAddrWidth + 2) bits))
  val xBranchTargetUnsigned = xBranchTarget.asUInt
  val xBranchOutOfRange = xBranchTarget < 0 ||
    xBranchTargetUnsigned >= io.programLength.resize(xBranchTarget.getWidth)
  val xBranchTaken = xIsBranchOn && !xCondReserved && xCondTrue &&
    !xBranchOutOfRange
  val xBranchTrap = xIsBranchOn &&
    (xCondReserved || (xCondTrue && xBranchOutOfRange))

  when(xBranchTaken) {
    x.up(PipeStageables.PC_REDIRECT_VALID) := True
    x.up(PipeStageables.PC_REDIRECT_TARGET) :=
      xBranchTargetUnsigned.resize(13 bits)
  }
  when(xBranchTrap) {
    x.up(PipeStageables.HALT_REQUEST) := True
    x.up(PipeStageables.RING_WRITE_VALID) := True
    x.up(PipeStageables.RING_WRITE_DATA) := makeTrapWord()
    x.up(PipeStageables.PC_REDIRECT_VALID) := True
    x.up(PipeStageables.PC_REDIRECT_TARGET) := x(PipeStageables.PC)
  }

  // ---- C.8 WAIT_ON trap on reserved cond ---------------------------------
  when(xIsWaitOn && xCondReserved) {
    x.up(PipeStageables.HALT_REQUEST) := True
    x.up(PipeStageables.RING_WRITE_VALID) := True
    x.up(PipeStageables.RING_WRITE_DATA) := makeTrapWord()
    x.up(PipeStageables.PC_REDIRECT_VALID) := True
    x.up(PipeStageables.PC_REDIRECT_TARGET) := x(PipeStageables.PC)
  }

  // ---- SET_BUS_MODE and SET_ROLE single-cycle execution -------------------
  // These two CTRL opcodes are implemented here (C.7) because they are
  // required by nearly every WIRE test program. All other CTRL opcodes
  // remain as STATUS_TRAP pending C.8.
  //
  // SET_BUS_MODE: mode field at [6:3] (4 bits). Valid values 0..3.
  // Reserved values (4..15) → trap.
  // SET_ROLE: role bit at [3]. 0=controller, 1=target.
  //
  // These are single-cycle operations: write the architectural register and
  // let the pipeline advance. No haltWhen needed.
  when(x.isValid && xIsSetBusMode && x.isReady) {
    val modeRaw = xInsn(6 downto 3).asUInt
    when(modeRaw <= 3) {
      // BusMode wire encoding: i2c=0, i3cOd=1, i3cPp=2, hdrDdr=3.
      // The SpinalEnum uses the exact same sequential encoding (set in
      // Instruction.scala BusMode.defaultEncoding).
      val newMode = BusMode()
      newMode.assignFromBits(modeRaw(1 downto 0).asBits)
      busModeReg := newMode
    } otherwise {
      // Invalid BUS_MODE value → STATUS_TRAP.
      x.up(PipeStageables.HALT_REQUEST) := True
      x.up(PipeStageables.RING_WRITE_VALID) := True
      x.up(PipeStageables.RING_WRITE_DATA) := makeTrapWord()
      x.up(PipeStageables.PC_REDIRECT_VALID) := True
      x.up(PipeStageables.PC_REDIRECT_TARGET) := x(PipeStageables.PC)
    }
  }

  when(x.isValid && xIsSetRole && x.isReady) {
    roleReg := xInsn(3)
    // Release bus drivers on role switch (per v0 SET_ROLE semantics).
    sdaDriveLow := False
    sdaDriveHigh := False
    sclDriveLow := False
    sclDriveHigh := False
  }

  // ---- C.8 LOAD_TIMING single-cycle execution ----------------------------
  // Writes `divider` into timing register `reg`. Fires the cycle X commits.
  // No range checks (assembler-enforced; both fields are wire-width-bounded).
  when(x.isValid && xIsLoadTiming && x.isReady) {
    loadTimingRegs(x(PipeStageables.LOAD_TIMING_REG)) :=
      x(PipeStageables.LOAD_TIMING_DIVIDER)
  }

  // ---- C.8 FLAG_CLEAR single-cycle execution -----------------------------
  // Applies the 5-bit mask: flags[N] := flags[N] & ~mask[N]. Per spec §7:
  //   bit 0 = MISMATCH_FLAG
  //   bit 1 = TIMEOUT_FLAG
  //   bit 2 = START_FLAG
  //   bit 3 = STOP_FLAG
  //   bit 4 = REG_ZERO_FLAG
  //
  // Last-assignment-wins on each flag: FLAG_CLEAR runs after the always-on
  // START/STOP set-writers above (textual order), so a FLAG_CLEAR that
  // arrives on the same cycle as a start/stop edge wins (correct: program
  // explicitly asked for clear). WIRE-FSM writers run later in the file
  // but are gated on xWireActive — a FLAG_CLEAR and a WIRE-FSM flag write
  // on the same cycle is structurally impossible because the FSMs are
  // mutually exclusive in X (only one opcode in X at a time).
  when(x.isValid && xIsFlagClear && x.isReady) {
    val mask = x(PipeStageables.FLAG_CLEAR_MASK)
    when(mask(0)) { mismatchFlagReg := False }
    when(mask(1)) { timeoutFlagReg := False }
    when(mask(2)) { startFlagReg := False }
    when(mask(3)) { stopFlagReg := False }
    when(mask(4)) { regZeroFlagReg := False }
  }

  // ---- C.8 MARK 3-cycle inline mini-FSM ---------------------------------
  //
  // Phase 0: entry. Latch label + current timestamp. Direct-ring block (see
  //   below) drives header word to io.ringWrite. On accepted handshake,
  //   ringWrPtr++ and xMarkPhase → 1.
  // Phase 1: direct-ring drives ts_lo. On accept, ringWrPtr++, phase → 2.
  // Phase 2: direct-ring drives ts_hi. On accept, ringWrPtr++, phase → 3
  //   and xMarkDone := True so X fires next cycle and stall releases.
  // Phase 3: one-cycle "done" marker. Stall releases; pipeline fires; the
  //   xMarkDone reg prevents re-entry next cycle. Phase resets to 0 here.
  //
  // SPEC GAP (v0.2 §5.16): mid-window MARK overflow behaviour is not
  // specified. Implementation choice: check at window start; either MARK
  // lands whole or not at all. Avoids partial records the host cannot
  // parse. Track as docs(spec) follow-up.
  //
  // Whole-or-nothing overflow check: if at phase-0 entry the ring would
  // overflow during the 3-word commit (ringWrPtr + 3 > resultWordCount-1
  // → no room for header+ts_lo+ts_hi before the HALT slot), set
  // ringOverflow and skip the entire commit. The xMarkSkip branch drives
  // the FSM straight to phase 3 / done.
  val xMarkRoomAvailable =
    (ringWrPtr +^ U(3, ringPtrWidth + 1 bits)) <=
      U(resultWordCount - 1, ringPtrWidth + 1 bits)
  // Phase-0 timestamp latch. Captures quarterTimestampReg so subsequent
  // phases (1, 2) commit consistent ts_lo / ts_hi halves of the same
  // value. The label needs no latch — the MARK instruction sits in X
  // for the full commit (xMarkStalling holds X), so MARK_LABEL is
  // stable across all phases.
  when(xMarkActive && xMarkPhase === 0) {
    xMarkTimestampReg := quarterTimestampReg
  }
  // Whole-or-nothing skip path: at phase-0 entry, if no room, latch
  // overflow and fast-forward to done. xMarkSkip is consumed by the
  // direct-ring block (below) which gates valid := False on this path.
  //
  // Note: phase resets directly to 0 (not to 3 then back) because the
  // phase-3 "DONE" marker has no consumer — xMarkDone (the one-cycle
  // pulse) is what gates X-stage release, and a phase-3 → 0 reset
  // predicated on xMarkActive would never fire (xMarkActive goes False
  // the same cycle xMarkDone goes True). Leaving phase at 3 would then
  // wedge the NEXT MARK in X with xMarkStalling=False and
  // xMarkDriveRing=False, silently dropping all 3 ring writes.
  val xMarkSkip = xMarkActive && xMarkPhase === 0 && !xMarkRoomAvailable
  when(xMarkSkip) {
    ringOverflow := True
    xMarkPhase := 0
    xMarkDone := True
  }

  // ---- C.8 WAIT_ON inline single-state spin ------------------------------
  //
  // Per spec §5.12: timeout=0 ⇒ infinite wait (no decrement, no TIMEOUT
  // set). Otherwise decrement xWaitOnCounter each cycle; on counter==0
  // before cond met, set TIMEOUT_FLAG and complete. On cond met first,
  // clear TIMEOUT_FLAG and complete.
  //
  // Two state-bits:
  //   xWaitOnActive  — True from entry through completion.
  //   xWaitOnCounter — decrementing remaining timeout.
  //
  // Entry vs running distinguished by xWaitOnActive register.
  when(xWaitOnActiveThisCycle && !xCondReserved) {
    val condMet = xCondTrue
    when(!xWaitOnActive) {
      // ---- Entry cycle: latch timeout / infinite flag. -------------------
      val timeoutImm = x(PipeStageables.WAIT_TIMEOUT)
      xWaitOnInfinite := (timeoutImm === 0)
      xWaitOnCounter := timeoutImm
      xWaitOnActive := True
      when(condMet) {
        // Cond already true on entry: clear TIMEOUT, complete same cycle.
        timeoutFlagReg := False
        xWaitOnCompletes := True
        xWaitOnDone := True
        xWaitOnActive := False
      }
    } otherwise {
      // ---- Running cycle: check cond / decrement / timeout. -------------
      when(condMet) {
        timeoutFlagReg := False
        xWaitOnCompletes := True
        xWaitOnDone := True
        xWaitOnActive := False
      } elsewhen (xWaitOnInfinite) {
        // Infinite wait: no decrement, no timeout. Keep spinning.
      } elsewhen (xWaitOnCounter === 0) {
        // Timeout expired. Set TIMEOUT_FLAG, complete.
        timeoutFlagReg := True
        xWaitOnCompletes := True
        xWaitOnDone := True
        xWaitOnActive := False
      } otherwise {
        xWaitOnCounter := xWaitOnCounter - 1
      }
    }
  }

  // ---- WIRE mini-FSM body -------------------------------------------------
  //
  // This `when` block computes updates to the component-scope state regs and
  // the combinatorial xWireHaltReq / xWireWritesReg / etc. signals that feed
  // the x.up() overrides above. It runs both when a WIRE instruction is first
  // seen (xWireState==WS_IDLE, x.isValid, xIsWire) and while running
  // (xWireRunning).
  //
  // Bus-shaped FSM idiom: sdaDriveLow/sdaDriveHigh/sclDriveLow/sclDriveHigh
  // are component-scope Reg(Bool())s. The mini-FSM only writes the lines it
  // changes per cycle.

  when(xWireActive) {

    when(xWireState === WS_IDLE) {
      // ---- Entry dispatch: first cycle with a WIRE instruction in X --------

      switch(xSub) {

        // ------ EMIT_BIT_IMM (sub=0x0) ------------------------------------
        is(0) {
          val txRaw = xEmitBitImmTxRaw
          when(txRaw === B"11") {
            // Reserved tx_symbol → immediate trap.
            xWireHaltReq := True
            xWireHaltWord := makeTrapWord()
            xWireDone := True
          } otherwise {
            val sdaSym = TxSymbol()
            sdaSym.assignFromBits(txRaw)
            val sdaDrive = SymbolDecoder(sdaSym, busModeReg)
            sdaDriveLow := sdaDrive.driveLow
            sdaDriveHigh := sdaDrive.driveHigh
            // SCL Q0: dominant per SclWaveformGen.
            when(!roleReg) {
              val sclQ0 =
                SymbolDecoder(SclWaveformGen(U(0, 2 bits)), busModeReg)
              sclDriveLow := sclQ0.driveLow
              sclDriveHigh := sclQ0.driveHigh
            } otherwise {
              // Target role: release SCL entirely.
              sclDriveLow := False
              sclDriveHigh := False
            }
            xQIdx := 0
            timerLoadReg := True
            xWireState := WS_EMIT_BIT
          }
        }

        // ------ EMIT_BIT_REG (sub=0x1) ------------------------------------
        is(1) {
          val txRaw = xRegAVal(1 downto 0)
          when(txRaw === B"11") {
            xWireHaltReq := True
            xWireHaltWord := makeTrapWord()
            xWireDone := True
          } otherwise {
            val sdaSym = TxSymbol()
            sdaSym.assignFromBits(txRaw)
            val sdaDrive = SymbolDecoder(sdaSym, busModeReg)
            sdaDriveLow := sdaDrive.driveLow
            sdaDriveHigh := sdaDrive.driveHigh
            when(!roleReg) {
              val sclQ0 =
                SymbolDecoder(SclWaveformGen(U(0, 2 bits)), busModeReg)
              sclDriveLow := sclQ0.driveLow
              sclDriveHigh := sclQ0.driveHigh
            } otherwise {
              sclDriveLow := False
              sclDriveHigh := False
            }
            xQIdx := 0
            timerLoadReg := True
            xWireState := WS_EMIT_BIT
          }
        }

        // ------ EMIT_QUARTER_IMM (sub=0x2) --------------------------------
        is(2) {
          val sdaRaw = xEmitQImmSdaRaw
          val sclRaw = xEmitQImmSclRaw
          when(sdaRaw === B"11" || sclRaw === B"11") {
            xWireHaltReq := True
            xWireHaltWord := makeTrapWord()
            xWireDone := True
          } otherwise {
            val sdaSym = TxSymbol()
            sdaSym.assignFromBits(sdaRaw)
            val sclSym = TxSymbol()
            sclSym.assignFromBits(sclRaw)
            val sdaDrive = SymbolDecoder(sdaSym, busModeReg)
            val sclDrive = SymbolDecoder(sclSym, busModeReg)
            sdaDriveLow := sdaDrive.driveLow
            sdaDriveHigh := sdaDrive.driveHigh
            sclDriveLow := sclDrive.driveLow
            sclDriveHigh := sclDrive.driveHigh
            timerLoadReg := True
            xWireState := WS_EMIT_QUARTER
          }
        }

        // ------ EMIT_QUARTER_REG (sub=0x3) --------------------------------
        is(3) {
          val sdaRaw = xRegAVal(1 downto 0)
          val sclRaw = xRegAVal(3 downto 2)
          when(sdaRaw === B"11" || sclRaw === B"11") {
            xWireHaltReq := True
            xWireHaltWord := makeTrapWord()
            xWireDone := True
          } otherwise {
            val sdaSym = TxSymbol()
            sdaSym.assignFromBits(sdaRaw)
            val sclSym = TxSymbol()
            sclSym.assignFromBits(sclRaw)
            val sdaDrive = SymbolDecoder(sdaSym, busModeReg)
            val sclDrive = SymbolDecoder(sclSym, busModeReg)
            sdaDriveLow := sdaDrive.driveLow
            sdaDriveHigh := sdaDrive.driveHigh
            sclDriveLow := sclDrive.driveLow
            sclDriveHigh := sclDrive.driveHigh
            timerLoadReg := True
            xWireState := WS_EMIT_QUARTER
          }
        }

        // ------ EMIT_BYTE (REG sub=0x4, IMM sub=0x9) -----------------------
        // Both variants share the entire WS_EMIT_BYTE running state: 8 data
        // bits + ACK slot. They differ only in the source of the latched
        // data byte at entry.
        //   REG: byte comes from R7[7:0] (forwarded REG_B_VALUE).
        //   IMM: byte comes from the instruction word at [10:3]; R7 is not
        //        read, so the load-use hazard does not apply.
        //
        // Helper closure captures the entry sequence so both arms stay in
        // lockstep — any future change to one MUST also change the other.
        def emitByteEntry(byteData: Bits): Unit = {
          xByteDataReg := byteData
          xByteIdx := 0
          // Drive SDA for MSB.
          val msb = byteData(7)
          val sdaSym = TxSymbol()
          sdaSym := TxSymbol.recessive
          when(!msb) { sdaSym := TxSymbol.dominant }
          val sdaDrive = SymbolDecoder(sdaSym, busModeReg)
          sdaDriveLow := sdaDrive.driveLow
          sdaDriveHigh := sdaDrive.driveHigh
          when(!roleReg) {
            val sclQ0 =
              SymbolDecoder(SclWaveformGen(U(0, 2 bits)), busModeReg)
            sclDriveLow := sclQ0.driveLow
            sclDriveHigh := sclQ0.driveHigh
          } otherwise {
            sclDriveLow := False
            sclDriveHigh := False
          }
          xQIdx := 0
          timerLoadReg := True
          xWireState := WS_EMIT_BYTE
        }

        is(4) {
          // EMIT_BYTE_REG: data from R7[7:0].
          emitByteEntry(xRegBVal(7 downto 0))
        }

        is(9) {
          // EMIT_BYTE_IMM: data from dInsn[10:3] (forwarded as Stageable).
          emitByteEntry(x(PipeStageables.EMIT_BYTE_IMM_DATA))
        }

        // ------ SAMPLE_BIT_ON_SCL (sub=0x5) --------------------------------
        is(5) {
          when(!roleReg) {
            // Controller role → trap.
            xWireHaltReq := True
            xWireHaltWord := makeTrapWord()
            xWireDone := True
          } otherwise {
            xWireState := WS_SAMPLE_BIT
          }
        }

        // ------ DRIVE_BIT_ON_SCL (sub=0x6) ---------------------------------
        is(6) {
          val txRaw = xDriveTxRaw
          when(txRaw === B"11" || !roleReg) {
            // Reserved tx_symbol or controller role → trap.
            xWireHaltReq := True
            xWireHaltWord := makeTrapWord()
            xWireDone := True
          } otherwise {
            // Release SDA on entry; drive after first falling SCL edge.
            sdaDriveLow := False
            sdaDriveHigh := False
            xDriveBitPhase := 0
            xWireState := WS_DRIVE_BIT
          }
        }

        // ------ STRETCH_SCL_IMM (sub=0x7) ----------------------------------
        is(7) {
          val n = xStretchImmN
          when(n === 0) {
            // n=0 → no-op; pipeline advances this cycle.
            xWireState := WS_IDLE
            xWireDone := True
          } otherwise {
            xStretchCount := n
            sclDriveLow := True
            sclDriveHigh := False
            timerLoadReg := True
            xWireState := WS_STRETCH_SCL
          }
        }

        // ------ STRETCH_SCL_REG (sub=0x8) ----------------------------------
        is(8) {
          val n = xRegAVal(13 downto 0).asUInt
          when(n === 0) {
            xWireState := WS_IDLE
            xWireDone := True
          } otherwise {
            xStretchCount := n
            sclDriveLow := True
            sclDriveHigh := False
            timerLoadReg := True
            xWireState := WS_STRETCH_SCL
          }
        }

        default {
          // Unreachable for live sub-opcodes; safe fallthrough.
          xWireState := WS_IDLE
          xWireDone := True
        }
      }

    } // end xWireState === WS_IDLE
      .elsewhen(xWireState === WS_EMIT_BIT) {
        // ---- EMIT_BIT running state -----------------------------------------
        //
        // Stretch-wait inline guard (AGENTS §"Stretch-aware Q2 entry on
        // controller-role EMIT_BIT_*"):
        //   Applied when waitingForStretch. Timer disabled (timerEnable = False
        //   by default). Released on SCL rising or stretch-timeout.
        //
        // Normal operation: timer paces the 4 quarter-states.
        // Sample at Q2→Q3 transition (xQIdx==2 on tick).

        when(waitingForStretch) {
          // Timer disabled; SDA untouched (preserves tHD;DAT hold from entry).
          when(observer.sclRising) {
            // SCL released: resume at Q2.
            waitingForStretch := False
            timerLoadReg := True
            xQIdx := 2
          } elsewhen (stretchTimeoutCtrIsZero) {
            // Stretch timeout: set TIMEOUT_FLAG, MISMATCH_FLAG, HALT 0x0D.
            timeoutFlagReg := True
            mismatchFlagReg := True
            waitingForStretch := False
            xWireState := WS_IDLE
            xWireDone := True
            xWireHaltReq := True
            xWireHaltWord := makeStretchTimeoutWord(forceMismatch = True)
          } otherwise {
            stretchTimeoutCtr := stretchTimeoutCtr - 1
          }
        } otherwise {
          timerEnable := True

          when(timer.io.tick) {
            // Sample SDA at Q2 → Q3 transition (xQIdx==2 on this tick).
            when(xQIdx === 2) {
              when(xMask) {
                mismatchFlagReg := (observer.sdaSampled =/= xExpect)
              }
              when(xCapture) {
                xCapturedBit := observer.sdaSampled
              }
            }

            when(xQIdx === 3) {
              // Bit complete.
              when(xCapture) {
                xWireWritesReg := True
                xWireWriteAddr := U(7, 3 bits)
                xWireWriteData :=
                  B(0, 31 bits) ## xCapturedBit.asBits
              }
              xWireState := WS_IDLE
              xWireDone := True
            } otherwise {
              val nextQ = xQIdx + 1
              // Update SCL for the new quarter.
              when(!roleReg) {
                val sclSym = SclWaveformGen(nextQ)
                val sclDrive = SymbolDecoder(sclSym, busModeReg)
                sclDriveLow := sclDrive.driveLow
                sclDriveHigh := sclDrive.driveHigh
              }

              // Stretch-aware Q1→Q2 guard.
              // At the Q1→Q2 tick (xQIdx==1), the SCL recessive symbol was just
              // committed above. If SCL is still low, slave is stretching.
              when(xQIdx === 1 && !roleReg && !observer.sclSampled) {
                when(BusModeOps.isPpClass(busModeReg)) {
                  // PP-class slave stretch: compliance violation → STATUS_TRAP.
                  mismatchFlagReg := True
                  xWireState := WS_IDLE
                  xWireDone := True
                  xWireHaltReq := True
                  xWireHaltWord :=
                    makeStretchTimeoutWord(forceMismatch = True)
                } otherwise {
                  // OD-class: pause inline; wait for SCL to release.
                  waitingForStretch := True
                  stretchTimeoutCtr :=
                    U(cfg.stretchTimeoutCycles, 21 bits)
                  // Force pipelined zero-flag False so the wait's first
                  // cycle doesn't see the pre-load (0 → flag True) and HALT.
                  stretchTimeoutCtrIsZero := False
                  // Do NOT advance xQIdx here.
                }
              } otherwise {
                xQIdx := nextQ
              }
            }
          }
        }
      }
      .elsewhen(xWireState === WS_EMIT_QUARTER) {
        // ---- EMIT_QUARTER running state: single-quarter dwell ---------------
        timerEnable := True
        when(timer.io.tick) {
          when(xMask) {
            mismatchFlagReg := (observer.sdaSampled =/= xExpect)
          }
          when(xCapture) {
            xCapturedBit := observer.sdaSampled
            xWireWritesReg := True
            xWireWriteAddr := U(7, 3 bits)
            xWireWriteData :=
              B(0, 31 bits) ## observer.sdaSampled.asBits
          }
          xWireState := WS_IDLE
          xWireDone := True
        }
      }
      .elsewhen(xWireState === WS_EMIT_BYTE) {
        // ---- EMIT_BYTE running state: 8 data bits + ACK slot ----------------
        // Each bit uses the same 4-quarter pattern as EMIT_BIT (no stretch guard).
        // xByteIdx = 0..7 data bits (MSB first), 8 = ACK/NAK hiz slot.
        timerEnable := True

        when(timer.io.tick) {
          // Sample at Q2 tick for the ACK slot (xByteIdx==8 and xQIdx==2).
          when(xByteIdx === 8 && xQIdx === 2) {
            when(xMask) {
              mismatchFlagReg := (observer.sdaSampled =/= xExpect)
            }
            when(xCapture) {
              xCapturedBit := observer.sdaSampled
            }
          }

          when(xQIdx === 3) {
            when(xByteIdx === 8) {
              // All 9 bit-cells complete (8 data + 1 ACK).
              when(xCapture) {
                xWireWritesReg := True
                xWireWriteAddr := U(7, 3 bits)
                xWireWriteData :=
                  B(0, 31 bits) ## xCapturedBit.asBits
              }
              xWireState := WS_IDLE
              xWireDone := True
            } otherwise {
              // Advance to the next bit cell.
              val nextIdx = xByteIdx + 1
              xByteIdx := nextIdx
              xQIdx := 0
              timerLoadReg := True
              // Drive SDA for the next bit (or hiz for ACK slot).
              val nextSdaSym = TxSymbol()
              when(nextIdx === 8) {
                // ACK slot: SDA hiz (target drives this on real bus).
                nextSdaSym := TxSymbol.hiz
              } otherwise {
                // Data bit: MSB = bit 7, then bit 6..0.
                // Compute bit value from the latched byte.
                // nextIdx ∈ {1..7}: bit position = 7 - nextIdx(2:0).
                val bitVal = xByteDataReg(
                  7 - nextIdx(2 downto 0).resize(3)
                )
                nextSdaSym := TxSymbol.recessive
                when(!bitVal) { nextSdaSym := TxSymbol.dominant }
              }
              val nextSdaDrive = SymbolDecoder(nextSdaSym, busModeReg)
              sdaDriveLow := nextSdaDrive.driveLow
              sdaDriveHigh := nextSdaDrive.driveHigh
              // SCL Q0 for the next bit cell.
              when(!roleReg) {
                val sclQ0 =
                  SymbolDecoder(SclWaveformGen(U(0, 2 bits)), busModeReg)
                sclDriveLow := sclQ0.driveLow
                sclDriveHigh := sclQ0.driveHigh
              }
            }
          } otherwise {
            val nextQ = xQIdx + 1
            when(!roleReg) {
              val sclSym = SclWaveformGen(nextQ)
              val sclDrive = SymbolDecoder(sclSym, busModeReg)
              sclDriveLow := sclDrive.driveLow
              sclDriveHigh := sclDrive.driveHigh
            }
            xQIdx := nextQ
          }
        }
      }
      .elsewhen(xWireState === WS_SAMPLE_BIT) {
        // ---- SAMPLE_BIT_ON_SCL running state (target role) ------------------
        // Wait for external SCL rising edge; sample SDA; flag-triple; advance.
        when(observer.sclRising) {
          when(xMask) {
            mismatchFlagReg := (observer.sdaSampled =/= xExpect)
          }
          when(xCapture) {
            xCapturedBit := observer.sdaSampled
            xWireWritesReg := True
            xWireWriteAddr := U(7, 3 bits)
            xWireWriteData :=
              B(0, 31 bits) ## observer.sdaSampled.asBits
          }
          xWireState := WS_IDLE
          xWireDone := True
        }
      }
      .elsewhen(xWireState === WS_DRIVE_BIT) {
        // ---- DRIVE_BIT_ON_SCL running state (target role) -------------------
        // Three sub-phases (per v0 BitCycleEngineCore):
        //   0: wait SCL falling; drive SDA per tx_symbol.
        //   1: wait SCL rising; sample/compare/capture.
        //   2: wait closing SCL falling; release SDA.
        //
        // Three separate phase checks (not a switch) to avoid the SpinalHDL
        // stale-read bug with per-phase state on entry (the v0 comment documents
        // this: a register-based phase counter suffered same-cycle stale reads).
        when(xDriveBitPhase === 0) {
          when(observer.sclFalling) {
            val txRaw = xDriveTxRaw
            val sdaSym = TxSymbol()
            sdaSym.assignFromBits(txRaw)
            val sdaDrive = SymbolDecoder(sdaSym, busModeReg)
            sdaDriveLow := sdaDrive.driveLow
            sdaDriveHigh := sdaDrive.driveHigh
            xDriveBitPhase := 1
          }
        } elsewhen (xDriveBitPhase === 1) {
          when(observer.sclRising) {
            when(xMask) {
              mismatchFlagReg := (observer.sdaSampled =/= xExpect)
            }
            when(xCapture) {
              xCapturedBit := observer.sdaSampled
            }
            xDriveBitPhase := 2
          }
        } elsewhen (xDriveBitPhase === 2) {
          when(observer.sclFalling) {
            // Release SDA.
            sdaDriveLow := False
            sdaDriveHigh := False
            when(xCapture) {
              xWireWritesReg := True
              xWireWriteAddr := U(7, 3 bits)
              xWireWriteData :=
                B(0, 31 bits) ## xCapturedBit.asBits
            }
            xWireState := WS_IDLE
            xWireDone := True
          }
        }
      }
      .elsewhen(xWireState === WS_STRETCH_SCL) {
        // ---- STRETCH_SCL running state: count down quarter-ticks ------------
        // SCL forced low on entry; release to recessive on the last tick.
        timerEnable := True
        when(timer.io.tick) {
          when(xStretchCount === 1) {
            // Final tick: release SCL.
            val relSym = TxSymbol.recessive
            val sclDrive = SymbolDecoder(relSym, busModeReg)
            sclDriveLow := sclDrive.driveLow
            sclDriveHigh := sclDrive.driveHigh
            xWireState := WS_IDLE
            xWireDone := True
          } otherwise {
            xStretchCount := xStretchCount - 1
          }
        }
      }

  } // end when(xWireActive)

  // --------------------------------------------------------------------------
  // W: Writeback
  // --------------------------------------------------------------------------

  // Commit RegFile write.
  regFile.io.writeEnable := w.down.isFiring && w(PipeStageables.WRITES_REG)
  regFile.io.writeAddr := w(PipeStageables.WRITE_REG_ADDR)
  regFile.io.writeData := w(PipeStageables.WRITE_REG_DATA)

  // Commit ring push.
  //
  // Ring layout (spec §11 / mole-abi):
  //   resultBase                       — first record-stream slot
  //   resultBase + ringWrPtr           — next free record slot
  //   recordLimit = resultLimit - 1    — last writable record slot
  //   resultLimit = resultBase + resultWordCount - 1  — HALT reserved slot
  //
  // HALT writes always land at resultLimit and do NOT advance ringWrPtr.
  // Non-HALT writes (CAPTURE today; MARK in C.8) write at
  // resultBase + ringWrPtr and advance ringWrPtr, but only while the
  // new pointer would still leave HALT's reserved slot untouched
  // (ringWrPtr <= resultWordCount - 2). Beyond that, set ringOverflow
  // and drop the write so the HALT slot can never be overwritten.
  when(w.down.isFiring && w(PipeStageables.RING_WRITE_VALID)) {
    when(w(PipeStageables.HALT_REQUEST)) {
      // HALT: address fixed at resultLimit, no pointer advance.
      io.ringWrite.valid := True
      io.ringWrite.payload.addr := U(
        resultBase + resultWordCount - 1,
        spramAddrWidth bits
      )
      io.ringWrite.payload.data := w(PipeStageables.RING_WRITE_DATA)
    } otherwise {
      // Non-HALT record: only commit while there is still room before
      // the reserved HALT slot. recordLimit ringWrPtr range is
      // 0 .. resultWordCount - 2 inclusive.
      when(ringWrPtr < U(resultWordCount - 1, ringPtrWidth bits)) {
        io.ringWrite.valid := True
        io.ringWrite.payload.addr :=
          (U(resultBase, spramAddrWidth bits) +
            ringWrPtr.resize(spramAddrWidth bits)).resized
        io.ringWrite.payload.data := w(PipeStageables.RING_WRITE_DATA)
        ringWrPtr := ringWrPtr + 1
      } otherwise {
        // No room before HALT slot: drop the record, latch overflow.
        ringOverflow := True
      }
    }
  }

  // Commit halt.
  when(w.down.isFiring && w(PipeStageables.HALT_REQUEST)) {
    haltedReg := True
    // Status at [27:23] of the ring word (spec §11).
    haltStatusReg := w(PipeStageables.RING_WRITE_DATA)(27 downto 23).asUInt
  }

  // Stall W when a ring write must actually issue but is back-pressured.
  // We must stall when the W payload requests a ring write AND we are
  // actually driving io.ringWrite.valid this cycle. Specifically, HALT
  // always issues (we always set valid for HALT_REQUEST), and non-HALT
  // issues only when ringWrPtr is below the reserved-HALT-slot
  // threshold. If the non-HALT write is being dropped (overflow), no
  // back-pressure stall is needed — we silently discard.
  val wWillIssueRingWrite =
    w(PipeStageables.RING_WRITE_VALID) && (
      w(PipeStageables.HALT_REQUEST) ||
        (ringWrPtr < U(resultWordCount - 1, ringPtrWidth bits))
    )
  w.haltWhen(wWillIssueRingWrite && !io.ringWrite.ready)

  // ---- C.8 MARK direct ring drive (Option 1) ----------------------------
  // MARK is the only opcode that needs >1 ring write per instruction
  // (header, ts_lo, ts_hi). The W-stage ring path is one-write-per-firing
  // by construction; folding 3 writes through W would require a 3-deep
  // FIFO between X and W. Instead, X drives io.ringWrite directly for
  // MARK, with a mutex against W's writer so the two writers are never
  // simultaneously active.
  //
  // Contention case: at most one cycle, at MARK phase-0 entry, where W
  // may be draining the instruction that was in X the cycle before MARK
  // entered. The mutex is `!wDrainingRingWrite` — if W is firing a ring
  // write this cycle, MARK holds phase and tries again next cycle.
  //
  // Phase advance is gated on io.ringWrite.ready AND mutex AND active.
  // Back-pressure (ready=False) naturally extends the X stall (because
  // xMarkStalling holds while phase < 3) without a separate haltWhen.
  //
  // Whole-or-nothing overflow: xMarkSkip (set at phase 0 when no room)
  // suppresses io.ringWrite.valid for the whole MARK and the FSM body
  // above fast-forwards to phase 3.
  val wDrainingRingWrite = w.down.isFiring && w(PipeStageables.RING_WRITE_VALID)
  val xMarkDriveRing = xMarkActive && (xMarkPhase < 3) && !xMarkSkip
  when(xMarkDriveRing && !wDrainingRingWrite) {
    // Override the component-scope idle defaults (and any W-stage
    // assignment, which is gated False by the mutex above).
    io.ringWrite.valid := True
    io.ringWrite.payload.addr :=
      (U(resultBase, spramAddrWidth bits) +
        ringWrPtr.resize(spramAddrWidth bits)).resized
    io.ringWrite.payload.data := xMarkRingData
    when(io.ringWrite.ready) {
      // Handshake accepted: commit pointer + phase advance.
      ringWrPtr := ringWrPtr + 1
      xMarkPhase := xMarkPhase + 1
      // Final commit (phase 2 → done): reset phase to 0 (skipping the
      // phase-3 marker — see xMarkSkip comment above) and pulse
      // xMarkDone so X fires next cycle.
      when(xMarkPhase === 2) {
        xMarkPhase := 0
        xMarkDone := True
      }
    }
  }

  // Flush pipeline on halt commit OR taken branch (C.8).
  //
  // The W-stage flush mechanism is extended to consume PC_REDIRECT_VALID
  // from any opcode (HALT, trap, taken BRANCH_ON). For HALT/trap the
  // engine halts and the flushed stragglers never resume. For taken
  // BRANCH_ON the pcReg is overwritten with the redirect target and the
  // pipeline re-fetches from the new PC.
  val flush = False
  when(w.down.isFiring && w(PipeStageables.HALT_REQUEST)) {
    flush := True
  }
  when(
    w.down.isFiring && w(PipeStageables.PC_REDIRECT_VALID) &&
      !w(PipeStageables.HALT_REQUEST)
  ) {
    flush := True
    pcReg := w(PipeStageables.PC_REDIRECT_TARGET).resize(progAddrWidth bits)
  }
  for (upstream <- List(f1, f2, d, r, x)) {
    upstream.throwWhen(flush, usingReady = true)
  }

  // Reset WIRE mini-FSM state on halt so the next run starts clean.
  when(flush) {
    xWireState := WS_IDLE
    waitingForStretch := False
    xMarkPhase := 0
    xMarkDone := False
    xWaitOnActive := False
    xWaitOnDone := False
    // Clear the F2 instruction-latch state so the post-flush refetch
    // forces a fresh SPRAM-response capture. Without this clear, the
    // stale f2InstrReg (from the pre-flush in-flight fetch that the
    // throwWhen invalidated at the StageLink level) would be exposed as
    // f2.up(INSTRUCTION) to the next post-flush transaction — the
    // post-flush f2 transaction sees f2InstrValid=True (stale) and
    // skips the haltWhen-on-pending wait, propagating the wrong
    // instruction into D. This was the C.8 BRANCH redirect bug:
    // BRANCH at PC=0 redirected to PC=7, but the post-flush
    // transaction at F2 saw the stale HALT(0x0a) latched from the
    // pre-flush PC=1 fetch, and that HALT(0x0a) committed at W.
    f2InstrValid := False
    f2RespPending := False
  }

  // Late-bind the haltInFlight Bool declared at the top of this Component.
  //
  // The check observes ONLY W (not X) to avoid a combinational loop:
  // x.up(HALT_REQUEST) is set conditionally in a when() body that
  // depends on x_down_isReady → w_up_ready → w_haltRequest →
  // io_ringWrite_ready → io_resultWrite_ready (in SpramController) →
  // io_readCmd_valid (combinationally fed by fetchActive) →
  // haltInFlight → fetchActive. Observing the registered xw boundary
  // (i.e., w.up payload, which arrives one cycle after X drives it)
  // breaks the loop. The 1-cycle delay in suppressing fetches is
  // acceptable: W needs at least one cycle of ringWrite.ready to
  // commit anyway, so the SPRAM read port frees up in time.
  haltInFlight := w.isValid && w(PipeStageables.HALT_REQUEST)

  // --------------------------------------------------------------------------
  // Build — assemble all CtrlLinks and StageLinks.
  // --------------------------------------------------------------------------
  Builder(f1, f2, d, r, x, w, f1f2, f2d, dr, rx, xw)
}
