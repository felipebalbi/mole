package mole

import spinal.core._

/** 16-bit fixed-width instruction encoding for the Mole bit-cycle engine.
  *
  * This file is the Scala-side **reference implementation** of the bytecode
  * wire format. It is consumed by simulation (round-trip audits, engine tests)
  * and by the engine's RTL fetch path, which slices the 16-bit instruction word
  * directly from the `instrReg` register. It is **not** the host's runtime
  * encoder: the host compiler is the `mole-asm` Rust crate under
  * `../../mole-asm/` (with its CLI front-end in `../../mole-asm-cli/`), which
  * emits pre-assembled bytes that travel raw over UART into the engine's SPRAM.
  * With both the Scala and Rust encoders in tree, the Scala `encode`/`decode`
  * pair below doubles as a cross-validation oracle --- "every legal instruction
  * encodes to the same 16-bit word in both implementations" is the cheapest
  * strong-signal correctness check for a wire-format contract.
  *
  * Wire-format scope. Per `../../AGENTS.md` §3.9 the instruction width is fixed
  * at 16 bits; §3.10 locks the `expect`/`mask`/`capture` flag triple at bit
  * positions `[2:0]` on every bearer opcode. The **opcode field is 5 bits wide
  * at `[15:11]`** --- thirty-two opcode slots, fourteen v0 opcodes in use plus
  * two v0.5 reserved (`FLAG_CLEAR` at 0x0E, `CAPTURE_RUN` at 0x0F) and sixteen
  * additional reserved slots at 0x10..0x1F for v0.5+ growth (the runtime
  * role-select opcode `SET_ROLE` is the first occupant at 0x10; the rest stay
  * trap slots until a future phase claims them). ROADMAP §"Encoding width"
  * lists the full per-opcode field budget; §"ISA" enumerates the v0 opcodes.
  *
  * Wire-format stability. Pre-Phase-0 the binary encoding is still mutable
  * (AGENTS §3.17); once the Rust host encoder ships its first tagged release
  * the encoding becomes a contract between the host compiler and every deployed
  * Mole. Reordering opcodes, moving the flag triple, repurposing a reserved
  * `tx_symbol` / `cond_code` slot, or shifting the opcode field would all be
  * wire-format breaks that require a bytecode-version bump and a `BREAKING
  * CHANGE:` footer.
  *
  * File layout:
  *   1. SpinalEnums for the four operand domains shared across opcodes
  *      ([[Opcode]], [[TxSymbol]], [[BusMode]], [[CondCode]]) --- shared
  *      between the Scala reference impl and the RTL fetch path.
  *   2. The Scala-side [[Instruction]] sealed-trait ADT --- one case class per
  *      v0 opcode plus a generic `ReservedV05` carrier. Pure Scala; never
  *      elaborated into RTL.
  *   3. [[Instruction.encode]] / [[Instruction.decode]] --- the host-side
  *      round-trip pair operating on the 16-bit wire word as a plain `Int`.
  *      Pure Scala. The future Rust encoder is the runtime authority; this pair
  *      is its sim-time twin.
  *
  * Ships all four SpinalEnums, all fifteen v0 case classes (including
  * [[Instruction.SetRole]] for runtime role select at slot `0x10`), encode +
  * decode bodies for the entire ISA, plus a `ReservedV05` carrier for the
  * remaining reserved slots (the two v0.5 slots at `0x0E` / `0x0F` and the
  * fifteen upper-half reserved slots at `0x11..0x1F`). The round-trip audit
  * lives in [[InstructionSim]]; the engine's RTL-side decode reads bit slices
  * defined here directly, sharing the SpinalEnum widths but none of this file's
  * pure-Scala encode/decode body.
  */

/** Opcode field --- bits `[15:11]` of every instruction word.
  *
  * Thirty-two total slots (5-bit field). Fourteen are in v0 use, two are
  * reserved for v0.5 (`FLAG_CLEAR` at 0x0E, `CAPTURE_RUN` at 0x0F), and sixteen
  * more (`0x10..0x1F`) are reserved for v0.5+. The runtime role-select opcode
  * `SET_ROLE` is the first occupant of the upper-half range at `0x10`; the
  * remaining fifteen upper-half slots stay trap-on- decode until a future phase
  * claims them. The numeric assignment locked here is the wire-format contract:
  * declaration order is the binary code under SpinalHDL's default
  * `binarySequential` encoding.
  *
  * `HALT` deliberately occupies code `0x0` so a zero-initialised SPRAM word (or
  * a fetch off the end of a loaded program) traps cleanly rather than decoding
  * as a free-running `EMIT_BIT`.
  *
  * Slots `0xC` and `0xD` carry the v1 loop-counter pair `LOAD_LOOP` and
  * `DEC_BRANCH` --- they consume what was previously reserved for
  * `WAIT_ADDRESSED` and `MISMATCH_CLEAR`. Both deferred-v0.5 candidates were
  * lower-priority than ergonomic bounded loops: the SDK-level expansion for
  * `WAIT_ADDRESSED` (Start + 8 SAMPLE_BIT_ON_SCL + host compare + conditional
  * DRIVE_BIT_ON_SCL) has not yet shown the I3C-rate timing pressure that would
  * justify reserving a slot, and `MISMATCH_CLEAR`'s use case is fully served by
  * the broader `FLAG_CLEAR` still reserved at `0xE`. See ROADMAP §"Reserved for
  * v0.5" for the full deferral note.
  *
  * The remaining v0.5 reserved slots at `0xE` and `0xF` carry `FLAG_CLEAR` and
  * `CAPTURE_RUN` per ROADMAP §"Reserved for v0.5". `CALL` / `RET` were
  * considered and deliberately dropped --- the SDK inlines call sites at
  * compile time, so dedicated control-flow opcodes never become necessary. The
  * sixteen upper-half slots (0x11..0x1F) are reservations only; v0 has no
  * decoder behaviour for them and the engine traps them at fetch.
  */
object Opcode extends SpinalEnum {
  val halt = newElement() // 0x00 --- safer trap on zero-memory fetch
  val emitBit = newElement() // 0x01 --- workhorse, one full wire bit
  val emitQuarter = newElement() // 0x02 --- per-quarter override (glitches)
  val stretchScl = newElement() // 0x03 --- pull SCL low for N quarters
  val waitOn = newElement() // 0x04 --- block until cond / timeout
  val branchOn = newElement() // 0x05 --- conditional PC-relative branch
  val jmp = newElement() // 0x06 --- unconditional absolute jump
  val setBusMode = newElement() // 0x07 --- swap symbol-to-electrical map
  val loadTiming = newElement() // 0x08 --- load quarter-bit divider word
  val mark = newElement() // 0x09 --- record labelled marker in ring
  val sampleBitOnScl = newElement() // 0x0A --- target-role sample
  val driveBitOnScl = newElement() // 0x0B --- target-role drive + sample
  val loadLoop = newElement() // 0x0C --- load 8-bit loop counter LCR[reg]
  val decBranch = newElement() // 0x0D --- decrement LCR[reg], branch if != 0
  val flagClear = newElement() // 0x0E reserved (v0.5)
  val captureRun = newElement() // 0x0F reserved (v0.5)
  val setRole = newElement() // 0x10 --- runtime role select (controller/target)
  val reserved11 = newElement() // 0x11 reserved (v0.5+)
  val reserved12 = newElement() // 0x12 reserved (v0.5+)
  val reserved13 = newElement() // 0x13 reserved (v0.5+)
  val reserved14 = newElement() // 0x14 reserved (v0.5+)
  val reserved15 = newElement() // 0x15 reserved (v0.5+)
  val reserved16 = newElement() // 0x16 reserved (v0.5+)
  val reserved17 = newElement() // 0x17 reserved (v0.5+)
  val reserved18 = newElement() // 0x18 reserved (v0.5+)
  val reserved19 = newElement() // 0x19 reserved (v0.5+)
  val reserved1a = newElement() // 0x1A reserved (v0.5+)
  val reserved1b = newElement() // 0x1B reserved (v0.5+)
  val reserved1c = newElement() // 0x1C reserved (v0.5+)
  val reserved1d = newElement() // 0x1D reserved (v0.5+)
  val reserved1e = newElement() // 0x1E reserved (v0.5+)
  val reserved1f = newElement() // 0x1F reserved (v0.5+)

  /** `true` for opcode codes 0x00..0x0D (the fourteen lower-half v0 opcodes)
    * and the upper-half v0 opcode `SET_ROLE` at 0x10. `false` for every
    * reserved slot (0x0E, 0x0F, 0x11..0x1F). Host encoder uses this to refuse
    * generating reserved-slot instructions in v0 programs.
    */
  def isV0(op: Opcode.E): Boolean =
    op.position < 14 || op == setRole
}

/** Per-line drive operand --- 2 bits, named symbolically per ROADMAP §"TX
  * symbol". Decoded against the active [[BusMode]] to obtain the actual
  * pad-driver state (NMOS on / PMOS on / both off). The engine itself knows
  * nothing about which protocol applies.
  *
  * `reserved` (binary `11`) is held for the v0.5 `raw_override` escape (see
  * ROADMAP §"Reserved for v0.5"). The encoder/decoder round-trip the value but
  * the engine refuses to execute it in v0 --- per AGENTS §3.11 the encoding
  * must not be repurposed.
  *
  * Appears in three opcodes:
  *   - [[Instruction.EmitBit]] (SDA only; SCL is engine-generated).
  *   - [[Instruction.EmitQuarter]] (both lines: two 2-bit fields).
  *   - [[Instruction.DriveBitOnScl]] (SDA only; target role).
  */
object TxSymbol extends SpinalEnum {
  val dominant = newElement() // 0b00 --- pull bus toward dominant state
  val recessive = newElement() // 0b01 --- release toward recessive state
  val hiz = newElement() // 0b10 --- driver disabled (true Hi-Z)
  val reserved = newElement() // 0b11 --- v0.5 raw_override (do not repurpose)
}

/** Bus mode register --- 3-bit operand of [[Instruction.SetBusMode]] and the
  * engine's only piece of protocol context.
  *
  * `mode[2]` selects the SCL high-half drive class (0 = OD-release, 1 = PP
  * active high). `mode[1:0]` selects which of the four
  * [[Instruction.LoadTiming]] divider registers feeds the quarter-bit timer.
  * The encoding is deliberately non-sequential to keep the `mode[2:0]`
  * semantics auditable directly from the wire word --- per ROADMAP §"Bus mode
  * register":
  *
  * {{{
  *   i2c    : mode[2]=0 mode[1:0]=00 -> 0b000 = 0
  *   i3cOd  : mode[2]=0 mode[1:0]=01 -> 0b001 = 1
  *   i3cPp  : mode[2]=1 mode[1:0]=10 -> 0b110 = 6
  *   hdrDdr : mode[2]=1 mode[1:0]=11 -> 0b111 = 7
  * }}}
  *
  * The SpinalEnum carries the wire encoding via [[SpinalEnumEncoding]] so RTL
  * (Step 8) and host (Step 7) see the same 3-bit value.
  */
object BusMode extends SpinalEnum {
  val i2c = newElement()
  val i3cOd = newElement()
  val i3cPp = newElement()
  val hdrDdr = newElement()

  defaultEncoding = SpinalEnumEncoding("busModeWire")(
    i2c -> 0,
    i3cOd -> 1,
    i3cPp -> 6,
    hdrDdr -> 7
  )
}

/** Unified condition code --- 4-bit field at `[10:7]` of both
  * [[Instruction.BranchOn]] (PC-relative branch) and [[Instruction.WaitOn]]
  * (timed block). Single shared namespace per AGENTS §3.14: adding a code in a
  * reserved slot is not a wire-format break; repurposing one in use is.
  *
  * Codes 0..9 are in use per `../../fpga/Mole/TODO.md` §"Engine flags +
  * cond-code namespace" and ROADMAP §"Engine flags --- unified condition
  * codes". The declaration order below is the wire-format binding. Codes 10..15
  * round-trip cleanly through the encoder but decode to a "trap" the engine
  * refuses to execute (Step 8 enforces).
  */
object CondCode extends SpinalEnum {
  val always = newElement() // 0x0  unconditional
  val mismatch = newElement() // 0x1  MISMATCH_FLAG == 1
  val notMismatch = newElement() // 0x2  MISMATCH_FLAG == 0
  val startSeen = newElement() // 0x3  edge: Start observed (START_FLAG)
  val stopSeen = newElement() // 0x4  edge: Stop observed  (STOP_FLAG)
  val sdaLow = newElement() // 0x5  level: SDA sampled low
  val sdaHigh = newElement() // 0x6  level: SDA sampled high
  val sclHigh = newElement() // 0x7  level: SCL sampled high
  val timeout = newElement() // 0x8  TIMEOUT_FLAG == 1
  val notTimeout = newElement() // 0x9  TIMEOUT_FLAG == 0
  val reserved10 = newElement() // 0xA  reserved (v0.5)
  val reserved11 = newElement() // 0xB  reserved (v0.5)
  val reserved12 = newElement() // 0xC  reserved (v0.5)
  val reserved13 = newElement() // 0xD  reserved (v0.5)
  val reserved14 = newElement() // 0xE  reserved (v0.5)
  val reserved15 = newElement() // 0xF  reserved (v0.5)

  /** `true` for cond codes 0x0..0x9 (the ten v0 codes). `false` for 0xA..0xF
    * (reserved). Host encoder uses this to warn when generating a reserved-slot
    * condition.
    */
  def isV0(c: CondCode.E): Boolean = c.position < 10
}

/** Host-side instruction value --- one case class per opcode. The sealed trait
  * gives the round-trip suite a typed `==`-based equality check, and the
  * case-class form is what the future Rust host encoder mirrors.
  *
  * Reserved v0.5 opcode slots collapse onto a single
  * [[Instruction.ReservedV05]] carrier --- they have no operand structure in v0
  * and the engine refuses to execute them at fetch.
  */
sealed trait Instruction

object Instruction {

  // --------------------------------------------------------------
  // Wire-format constants (locked; changing any of these is a
  // bytecode wire-format break per AGENTS §3.9 / §3.10).
  // --------------------------------------------------------------

  /** Instruction word width, in bits. */
  val WORD_WIDTH: Int = 16

  /** Opcode field width, in bits. */
  val OPCODE_WIDTH: Int = 5

  /** Opcode field high bit. */
  val OPCODE_HI: Int = 15

  /** Opcode field low bit. */
  val OPCODE_LO: Int = 11

  /** Operand region width, in bits (everything below the opcode field). */
  val OPERAND_WIDTH: Int = OPCODE_LO

  /** `tx_symbol` field width, in bits. */
  val TX_SYMBOL_WIDTH: Int = 2

  /** `cond_code` field width, in bits (shared by `WAIT_ON` / `BRANCH_ON`). */
  val COND_CODE_WIDTH: Int = 4

  /** `mode` field width on `SET_BUS_MODE`, in bits. */
  val BUS_MODE_WIDTH: Int = 3

  /** Flag triple --- `expect` bit position. Locked at `[2]`. */
  val EXPECT_BIT: Int = 2

  /** Flag triple --- `mask` bit position. Locked at `[1]`. */
  val MASK_BIT: Int = 1

  /** Flag triple --- `capture` bit position. Locked at `[0]`. */
  val CAPTURE_BIT: Int = 0

  // --------------------------------------------------------------
  // Per-opcode host-side case classes
  // --------------------------------------------------------------

  /** `EMIT_BIT tx_symbol expect mask capture` --- emit one full I2C/I3C wire
    * bit (4 quarter-bit cycles at canonical positions: SCL low/low/high/high,
    * SDA held at `txSymbol` throughout). Layout:
    *
    * {{{
    *   [15:11] opcode   [10:9] tx_symbol   [8:3] reserved (=0)
    *   [2] expect       [1] mask           [0] capture
    * }}}
    *
    * Per ROADMAP §"Canonical EMIT_BIT shape" SCL is engine-generated and not
    * part of the bitstream. The compare against the sampled SDA value, gated by
    * `mask`, updates the sticky `MISMATCH_FLAG`. `capture=1` also writes the
    * sampled bit to the result ring.
    */
  case class EmitBit(
      txSymbol: TxSymbol.E,
      expect: Boolean,
      mask: Boolean,
      capture: Boolean
  ) extends Instruction

  /** `HALT status` --- end of program, status returned to the host via the
    * result ring. Layout:
    *
    * {{{
    *   [15:11] opcode   [10:7] status   [6:0] reserved (=0)
    * }}}
    *
    * `status` is 4 bits --- 16 distinct termination codes. The first few are
    * reserved by convention for `ok` / `mismatch` / `timeout` / `bus-error`
    * outcomes; the SDK pins them in a later step.
    */
  case class Halt(status: Int) extends Instruction

  /** `EMIT_QUARTER sda_symbol scl_symbol expect mask capture` --- single
    * quarter-bit override of both SDA and SCL. The only opcode that encodes SCL
    * in the bitstream (per ROADMAP §"When to use EMIT_QUARTER"). Used for Start
    * / Stop / Repeated Start, HDR-DDR bit shapes, compliance violations
    * (early/late releases, glitches), and bus-idle waits. Layout:
    *
    * {{{
    *   [15:11] opcode      [10:9] sda_symbol   [8:7] scl_symbol
    *   [6:3] reserved (=0) [2] expect [1] mask [0] capture
    * }}}
    */
  case class EmitQuarter(
      sdaSymbol: TxSymbol.E,
      sclSymbol: TxSymbol.E,
      expect: Boolean,
      mask: Boolean,
      capture: Boolean
  ) extends Instruction

  /** `STRETCH_SCL n` --- hold SCL low for `n` quarters. 11-bit operand at
    * `[10:0]` (max 2047 quarters, ~512 µs at the 24 MHz default fabric clock
    * with the reset divider). Used in both roles: controller-role forced
    * stretching (fuzz) and target-role canonical clock stretching. The SDK
    * chains multiple `STRETCH_SCL` calls when a single 11-bit field is not
    * enough.
    */
  case class StretchScl(nQuarters: Int) extends Instruction

  /** `WAIT_ON cond, timeout` --- block until `cond` becomes true, or until
    * `timeout` quarter-bit ticks elapse. Layout:
    *
    * {{{
    *   [15:11] opcode   [10:7] cond_code   [6:0] timeout (unsigned)
    * }}}
    *
    * `timeout = 0` means "wait forever" (no timeout). 7-bit unsigned operand
    * caps the per-instruction timeout at 127 quarter-bit ticks; longer waits
    * compose with `BRANCH_ON TIMEOUT` + `JMP`. Shares the
    * `[10:7]cond_code [6:0]operand` shape with [[BranchOn]] --- only operand
    * semantics differ (signed-PC-offset vs unsigned-quarter-timeout) per AGENTS
    * §3.14.
    */
  case class WaitOn(cond: CondCode.E, timeoutQuarters: Int) extends Instruction

  /** `BRANCH_ON cond, offset` --- conditional jump to a PC-relative signed
    * 7-bit offset (-64..63 instructions). Layout:
    *
    * {{{
    *   [15:11] opcode   [10:7] cond_code   [6:0] pc_rel_offset (signed)
    * }}}
    *
    * The unified branch opcode replaces all per-condition branch instructions:
    * a new condition is a new `cond_code` value, not a new opcode. Shares field
    * shape with [[WaitOn]]. Long-range branches compose with [[Jmp]] (an
    * unconditional 11-bit absolute jump).
    */
  case class BranchOn(cond: CondCode.E, pcRelOffset: Int) extends Instruction

  /** `JMP addr` --- unconditional jump to absolute 11-bit instruction address
    * (0..2047). Layout: `[15:11] opcode [10:0] addr`. Programs larger than 2048
    * instructions need an SDK-level sectioning convention; v0 sims fit in the
    * 2K range with margin. Long-distance conditional branches in the SDK expand
    * to `BRANCH_ON cond, near` + `JMP far` to stay within [[BranchOn]]'s
    * -64..63 reach while still spanning the JMP range.
    */
  case class Jmp(addr: Int) extends Instruction

  /** `SET_BUS_MODE mode` --- swap the symbol-to-electrical mapping plus the
    * active timing divider. Layout:
    *
    * {{{
    *   [15:11] opcode   [10:8] mode   [7:0] reserved (=0)
    * }}}
    *
    * The 3-bit `mode` field's encoding is non-sequential to keep `mode[2]` =
    * SCL drive class and `mode[1:0]` = active divider register directly
    * readable from the wire (see [[BusMode]]).
    */
  case class SetBusMode(mode: BusMode.E) extends Instruction

  /** `LOAD_TIMING reg word` --- load one of four quarter-bit divider words.
    * Layout:
    *
    * {{{
    *   [15:11] opcode   [10:9] reg   [8:0] divider_word
    * }}}
    *
    * `reg` selects `pp-freq` / `od-freq` / `i2c-freq` / `hdr-ddr-freq`. The
    * 9-bit divider field caps the slowest quarter at 511 fabric cycles (~21.3
    * µs / 11.7 kHz bit rate at the 24 MHz default fabric clock) --- comfortably
    * below the slowest spec'd bus mode (I²C SM at 100 kHz needs roughly 60
    * cycles per quarter at 24 MHz fabric). Pure --- does not change the active
    * mode (use [[SetBusMode]] for that).
    */
  case class LoadTiming(reg: Int, dividerWord: Int) extends Instruction

  /** `MARK label` --- insert a labelled marker (with implicit timestamp) in the
    * result ring. Layout:
    *
    * {{{
    *   [15:11] opcode   [10:3] label   [2:0] reserved (=0)
    * }}}
    *
    * 256 distinct labels --- ample for the per-test sectioning that
    * disassemblers and result decoders rely on.
    */
  case class Mark(label: Int) extends Instruction

  /** `SAMPLE_BIT_ON_SCL expect mask capture` --- target-role opcode: wait for
    * the next SCL rising edge (driven externally), sample SDA at the canonical
    * sample point, compare, optionally capture. Layout:
    *
    * {{{
    *   [15:11] opcode      [10:3] reserved (=0)
    *   [2] expect [1] mask [0] capture
    * }}}
    *
    * No `tx_symbol` field --- the target only reads SDA in this opcode. The
    * companion [[DriveBitOnScl]] does both.
    */
  case class SampleBitOnScl(
      expect: Boolean,
      mask: Boolean,
      capture: Boolean
  ) extends Instruction

  /** `DRIVE_BIT_ON_SCL tx_symbol expect mask capture` --- target-role opcode:
    * on the next SCL falling edge, drive SDA per `tx_symbol` for one
    * external-SCL-clocked bit cell; concurrently, on the SCL rising edge inside
    * that cell, sample SDA, compare, optionally capture. The simultaneous drive
    * + sample is what enables I3C DAA arbitration: a target driving `recessive`
    * reads `dominant` iff another target pulled the line low, setting
    * `MISMATCH_FLAG`. Layout:
    *
    * {{{
    *   [15:11] opcode      [10:9] tx_symbol    [8:3] reserved (=0)
    *   [2] expect [1] mask [0] capture
    * }}}
    *
    * Identical shape to [[EmitBit]] --- the only difference is who clocks SCL.
    */
  case class DriveBitOnScl(
      txSymbol: TxSymbol.E,
      expect: Boolean,
      mask: Boolean,
      capture: Boolean
  ) extends Instruction

  /** `LOAD_LOOP reg, imm8` --- load an 8-bit immediate into one of the two loop
    * counter registers (`LCR0` at `reg=0`, `LCR1` at `reg=1`). Layout:
    *
    * {{{
    *   [15:11] opcode   [10] reg   [9:8] reserved (=0)   [7:0] imm8
    * }}}
    *
    * Bits `[9:8]` are reserved (= 0) in the v1 two-LCR build; widening to a
    * future four-LCR file uses those two bits as additional reg-id bits with no
    * wire-format break. The encoder pins `reg` to `{0, 1}`.
    *
    * Paired with [[DecBranch]] for bounded loops: `LOAD_LOOP reg=0, imm=N`
    * primes LCR0 to `N`, then `DEC_BRANCH reg=0, back_offset` decrements LCR0
    * each pass and back-edges while LCR0 != 0. `imm = 0` is legal but unusual
    * --- the SDK warns since the matching `DEC_BRANCH` wraps to `0xFF` and runs
    * a full 256 iterations. Documented in the moleasm book.
    */
  case class LoadLoop(reg: Int, imm: Int) extends Instruction

  /** `DEC_BRANCH reg, offset` --- decrement `LCR[reg]` and conditionally branch
    * by a signed 8-bit PC-relative offset (±128 instructions) if the
    * post-decrement value is non-zero. Layout:
    *
    * {{{
    *   [15:11] opcode   [10] reg   [9:8] reserved (=0)
    *   [7:0] pc_rel_offset (signed)
    * }}}
    *
    * Semantics, per fetch:
    *   1. `LCR[reg] <- LCR[reg] - 1` (8-bit wrap; `0 -> 0xFF`).
    *   2. `if (LCR[reg] != 0) PC <- PC + offset`.
    *
    * Same reserved-bit shape as [[LoadLoop]] --- `[9:8]` reserved for the
    * eventual wider reg field. Carries its own 8-bit signed offset (-128..127)
    * --- intentionally wider than [[BranchOn]]'s 7-bit offset because tight
    * inner loops benefit from longer back-edges. Sticky engine flags
    * (`MISMATCH_FLAG`, `TIMEOUT_FLAG`, `START_FLAG`, `STOP_FLAG`) are NOT
    * touched by `DEC_BRANCH` per AGENTS §3.15.
    */
  case class DecBranch(reg: Int, pcRelOffset: Int) extends Instruction

  /** `SET_ROLE role` --- runtime engine-role select. Layout:
    *
    * {{{
    *   [15:11] opcode   [10] role   [9:0] reserved (=0)
    * }}}
    *
    * `role = 0` selects controller role; `role = 1` selects target role. The
    * power-on default is taken from `MoleConfig.role` so a program that never
    * issues `SET_ROLE` keeps the historical compile-time-style behaviour. The
    * decode arm also releases all bus drivers (`sdaDriveLow/High`,
    * `sclDriveLow/High := False`) before writing the role register so a
    * mid-program role switch leaves the bus in a clean Hi-Z state regardless of
    * which arm was driving last. There is no "must be first" check --- the SDK
    * convention is to issue `SET_ROLE` near the top of every program, but the
    * engine accepts the opcode at any PC.
    */
  case class SetRole(role: Boolean) extends Instruction

  /** Reserved opcode carrier --- round-trips an 11-bit operand payload verbatim
    * for any reserved-v0.5 / reserved-v0.5+ opcode slot. v0 has no semantics
    * for any reserved slot, and the per-opcode payload layout is a v0.5+ design
    * decision that has not happened yet.
    *
    * Constructor enforces `Opcode.isV0(opcode) == false`; v0 opcodes use their
    * own typed case classes, not this carrier.
    */
  case class ReservedV05(opcode: Opcode.E, payload: Int) extends Instruction {
    require(
      !Opcode.isV0(opcode),
      s"ReservedV05 carries reserved opcodes only; got v0 opcode $opcode"
    )
    require(
      payload >= 0 && payload < (1 << OPERAND_WIDTH),
      s"ReservedV05 payload must fit in $OPERAND_WIDTH bits, got $payload"
    )
  }

  // --------------------------------------------------------------
  // Encode / decode --- host-side round-trip pair
  // --------------------------------------------------------------

  /** Encode a host-side [[Instruction]] into its 16-bit wire word.
    *
    * **Pure Scala, not RTL.** This function is the sim-time reference encoder,
    * not the host's runtime encoder. The host compiler (the `mole-asm` Rust
    * crate under `../../mole-asm/`) emits pre-assembled bytes over UART; this
    * Scala function exists to (1) satisfy Step 7's "round-trip encode/decode"
    * sim requirement, (2) let Step 9+ engine sims construct test programs in
    * Scala instead of hand-coded hex literals, and (3) cross-validate the Rust
    * encoder once it lands --- if the two implementations agree on every legal
    * instruction, the wire format is correct.
    *
    * `encode` operates on Scala case classes and returns a plain `scala.Int`.
    * It cannot elaborate into hardware: SpinalHDL does not "see" Scala case
    * classes at elaboration time, and the call site would emerge as a baked-in
    * constant even if invoked from a Component body.
    *
    * Throws [[IllegalArgumentException]] on out-of-range operand values (e.g. a
    * `Jmp` whose address overflows 11 bits) --- the host SDK is responsible for
    * catching those before they reach the engine.
    */
  def encode(insn: Instruction): Int = insn match {
    case EmitBit(tx, expect, mask, capture) =>
      (Opcode.emitBit.position << OPCODE_LO) |
        (tx.position << 9) |
        flagTripleBits(expect, mask, capture)

    case Halt(status) =>
      require(
        status >= 0 && status < (1 << 4),
        s"HALT status must be 0..15, got $status"
      )
      (Opcode.halt.position << OPCODE_LO) |
        (status << 7)

    case EmitQuarter(sdaSymbol, sclSymbol, expect, mask, capture) =>
      (Opcode.emitQuarter.position << OPCODE_LO) |
        (sdaSymbol.position << 9) |
        (sclSymbol.position << 7) |
        flagTripleBits(expect, mask, capture)

    case StretchScl(nQuarters) =>
      require(
        nQuarters >= 0 && nQuarters < (1 << 11),
        s"STRETCH_SCL n_quarters must fit in 11 bits, got $nQuarters"
      )
      (Opcode.stretchScl.position << OPCODE_LO) | nQuarters

    case WaitOn(cond, timeoutQuarters) =>
      require(
        timeoutQuarters >= 0 && timeoutQuarters < (1 << 7),
        s"WAIT_ON timeout must fit in 7 bits unsigned, got $timeoutQuarters"
      )
      (Opcode.waitOn.position << OPCODE_LO) |
        (cond.position << 7) |
        (timeoutQuarters & 0x7f)

    case BranchOn(cond, pcRelOffset) =>
      require(
        pcRelOffset >= -64 && pcRelOffset <= 63,
        s"BRANCH_ON pc_rel_offset must be signed 7-bit (-64..63), got $pcRelOffset"
      )
      (Opcode.branchOn.position << OPCODE_LO) |
        (cond.position << 7) |
        (pcRelOffset & 0x7f)

    case Jmp(addr) =>
      require(
        addr >= 0 && addr < (1 << 11),
        s"JMP addr must fit in 11 bits, got $addr"
      )
      (Opcode.jmp.position << OPCODE_LO) | addr

    case SetBusMode(mode) =>
      (Opcode.setBusMode.position << OPCODE_LO) |
        (busModeWireValue(mode) << 8)

    case LoadTiming(reg, dividerWord) =>
      require(
        reg >= 0 && reg < (1 << 2),
        s"LOAD_TIMING reg must fit in 2 bits (0..3), got $reg"
      )
      require(
        dividerWord >= 0 && dividerWord < (1 << 9),
        s"LOAD_TIMING divider_word must fit in 9 bits, got $dividerWord"
      )
      (Opcode.loadTiming.position << OPCODE_LO) |
        (reg << 9) |
        dividerWord

    case Mark(label) =>
      require(
        label >= 0 && label < (1 << 8),
        s"MARK label must fit in 8 bits, got $label"
      )
      (Opcode.mark.position << OPCODE_LO) |
        (label << 3)

    case SampleBitOnScl(expect, mask, capture) =>
      (Opcode.sampleBitOnScl.position << OPCODE_LO) |
        flagTripleBits(expect, mask, capture)

    case DriveBitOnScl(tx, expect, mask, capture) =>
      (Opcode.driveBitOnScl.position << OPCODE_LO) |
        (tx.position << 9) |
        flagTripleBits(expect, mask, capture)

    case LoadLoop(reg, imm) =>
      require(
        reg >= 0 && reg < 2,
        s"LOAD_LOOP reg must be 0 or 1, got $reg"
      )
      require(
        imm >= 0 && imm < (1 << 8),
        s"LOAD_LOOP imm must fit in 8 bits unsigned, got $imm"
      )
      (Opcode.loadLoop.position << OPCODE_LO) |
        (reg << 10) |
        (imm & 0xff)

    case DecBranch(reg, pcRelOffset) =>
      require(
        reg >= 0 && reg < 2,
        s"DEC_BRANCH reg must be 0 or 1, got $reg"
      )
      require(
        pcRelOffset >= -128 && pcRelOffset <= 127,
        s"DEC_BRANCH pc_rel_offset must be signed 8-bit (-128..127), got $pcRelOffset"
      )
      (Opcode.decBranch.position << OPCODE_LO) |
        (reg << 10) |
        (pcRelOffset & 0xff)

    case SetRole(role) =>
      (Opcode.setRole.position << OPCODE_LO) |
        ((if (role) 1 else 0) << 10)

    case ReservedV05(opcode, payload) =>
      // Constructor already enforces non-v0 opcode + 11-bit payload range.
      (opcode.position << OPCODE_LO) | payload
  }

  /** Decode a 16-bit wire word into its host-side [[Instruction]].
    *
    * **Pure Scala, not RTL.** Same role as [[encode]] (which see): this is the
    * sim-time reference decoder, not the engine's RTL fetch decoder. The
    * engine's decode happens combinationally on the 16-bit `instrReg` register
    * sliced per-opcode against the layouts in this file's case-class doc
    * comments, sharing the SpinalEnum widths defined above but none of this
    * Scala function's body.
    *
    * `word` must fit in the lower 16 bits; the upper bits are required to be
    * zero so callers cannot accidentally pass a sign-extended `Int`.
    *
    * Reserved-bit slices in the wire word are not inspected --- a hand-crafted
    * word with stray bits in a reserved field decodes to the same case-class
    * value as a clean one. Round-trip stability (`decode(encode(i)) == i`) is
    * the invariant the sim asserts; strict "reject stray reserved bits" is a
    * defense-in-depth check the engine (not this function) can choose to add
    * later.
    */
  def decode(word: Int): Instruction = {
    require(
      (word & ~0xffff) == 0,
      f"instruction word 0x$word%08X has bits set above [15:0]"
    )
    val op = Opcode.elements((word >> OPCODE_LO) & 0x1f)
    op match {
      case Opcode.emitBit =>
        EmitBit(
          txSymbol = TxSymbol.elements((word >> 9) & 0x3),
          expect = bitSet(word, EXPECT_BIT),
          mask = bitSet(word, MASK_BIT),
          capture = bitSet(word, CAPTURE_BIT)
        )

      case Opcode.halt =>
        Halt(status = (word >> 7) & 0xf)

      case Opcode.emitQuarter =>
        EmitQuarter(
          sdaSymbol = TxSymbol.elements((word >> 9) & 0x3),
          sclSymbol = TxSymbol.elements((word >> 7) & 0x3),
          expect = bitSet(word, EXPECT_BIT),
          mask = bitSet(word, MASK_BIT),
          capture = bitSet(word, CAPTURE_BIT)
        )

      case Opcode.stretchScl =>
        StretchScl(nQuarters = word & 0x7ff)

      case Opcode.waitOn =>
        WaitOn(
          cond = CondCode.elements((word >> 7) & 0xf),
          timeoutQuarters = word & 0x7f
        )

      case Opcode.branchOn =>
        BranchOn(
          cond = CondCode.elements((word >> 7) & 0xf),
          pcRelOffset = signExtend7(word & 0x7f)
        )

      case Opcode.jmp =>
        Jmp(addr = word & 0x7ff)

      case Opcode.setBusMode =>
        val wireBits = (word >> 8) & 0x7
        SetBusMode(
          mode = busModeFromWire.getOrElse(
            wireBits,
            throw new IllegalArgumentException(
              f"SET_BUS_MODE word 0x$word%04X: undefined mode bits " +
                f"$wireBits%d (legal wire values: 0, 1, 6, 7)"
            )
          )
        )

      case Opcode.loadTiming =>
        LoadTiming(
          reg = (word >> 9) & 0x3,
          dividerWord = word & 0x1ff
        )

      case Opcode.mark =>
        Mark(label = (word >> 3) & 0xff)

      case Opcode.sampleBitOnScl =>
        SampleBitOnScl(
          expect = bitSet(word, EXPECT_BIT),
          mask = bitSet(word, MASK_BIT),
          capture = bitSet(word, CAPTURE_BIT)
        )

      case Opcode.driveBitOnScl =>
        DriveBitOnScl(
          txSymbol = TxSymbol.elements((word >> 9) & 0x3),
          expect = bitSet(word, EXPECT_BIT),
          mask = bitSet(word, MASK_BIT),
          capture = bitSet(word, CAPTURE_BIT)
        )

      case Opcode.loadLoop =>
        LoadLoop(
          reg = (word >> 10) & 0x1,
          imm = word & 0xff
        )

      case Opcode.decBranch =>
        DecBranch(
          reg = (word >> 10) & 0x1,
          pcRelOffset = signExtend8(word & 0xff)
        )

      case Opcode.setRole =>
        SetRole(role = bitSet(word, 10))

      case reserved =>
        // Every reserved opcode slot (0x0E, 0x0F, 0x11..0x1F) round-trips its
        // payload verbatim; the engine traps these at fetch.
        ReservedV05(opcode = reserved, payload = word & 0x7ff)
    }
  }

  // --------------------------------------------------------------
  // Internal helpers
  // --------------------------------------------------------------

  /** Wire encoding for [[BusMode]] --- mirrors the SpinalEnumEncoding
    * `busModeWire` declared on the enum itself. The wire codes are deliberately
    * non-sequential per ROADMAP §"Bus mode register": `mode[2]` (the SCL drive
    * class) sits high while `mode[1:0]` (the timing-divider selector) sits low,
    * so `mode` bits read directly as "drive-class then divider". The host
    * encoder MUST use these wire codes (not the SpinalEnumElement `.position`
    * declaration index) so the Scala reference and the engine RTL agree at the
    * bit level.
    */
  private val busModeWireValue: Map[BusMode.E, Int] = Map(
    BusMode.i2c -> 0,
    BusMode.i3cOd -> 1,
    BusMode.i3cPp -> 6,
    BusMode.hdrDdr -> 7
  )

  /** Inverse of [[busModeWireValue]], used by [[decode]]. */
  private val busModeFromWire: Map[Int, BusMode.E] =
    busModeWireValue.map(_.swap)

  /** Pack the (expect, mask, capture) triple into bits `[2:0]` of the word.
    * Used by every bearer opcode. Locked layout per AGENTS §3.10.
    */
  private def flagTripleBits(
      expect: Boolean,
      mask: Boolean,
      capture: Boolean
  ): Int =
    (if (expect) 1 << EXPECT_BIT else 0) |
      (if (mask) 1 << MASK_BIT else 0) |
      (if (capture) 1 << CAPTURE_BIT else 0)

  /** `true` iff bit `pos` is set in `word`. Shorthand used by every flag-triple
    * decoder.
    */
  private def bitSet(word: Int, pos: Int): Boolean =
    ((word >> pos) & 1) == 1

  /** Sign-extend an 8-bit unsigned value (0..255) into a signed `Int`
    * (-128..127). Used by [[DecBranch]] to recover its signed PC-relative
    * offset from the wire word's low byte. Uses Java's arithmetic-right-shift
    * on `Int` (`>>`) to sign-extend the top bit.
    */
  private def signExtend8(byte: Int): Int = (byte << 24) >> 24

  /** Sign-extend a 7-bit unsigned value (0..127) into a signed `Int` (-64..63).
    * Used by [[BranchOn]] to recover its signed PC-relative offset from the
    * wire word's low septet. Shifts the top bit into the sign position before
    * arithmetic-right-shifting it back.
    */
  private def signExtend7(septet: Int): Int = (septet << 25) >> 25
}
