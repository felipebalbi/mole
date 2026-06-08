package mole

import spinal.core._

/** 32-bit fixed-width instruction encoding for the Mole v0.2 bit-cycle engine.
  *
  * This file is the Scala-side **reference implementation** of the v0.2
  * bytecode wire format. It is consumed by simulation (round-trip audits,
  * golden cross-checks) and will be consumed by the v0.2 RTL pipeline (Phase
  * C). It is **not** the host's runtime encoder: the host compiler is the
  * `mole-asm` Rust crate under `../../mole-asm/`.
  *
  * With both the Scala and Rust encoders in tree, the Scala `encode`/`decode`
  * pair doubles as a cross-validation oracle. The
  * `InstructionGoldenCrossCheckSim` reads every `*.molecode` fixture produced
  * by the Rust encoder and decodes each word with this Scala implementation to
  * confirm bit-exact agreement.
  *
  * Wire-format scope. Per `../../docs/MOLE-0.2-SPEC.md` §3 the instruction
  * width is fixed at **32 bits**. The **opcode field is 6 bits wide** at
  * `{group[31:30], sub[29:26]}` --- 64 slots total across 4 groups of 16
  * sub-opcodes each. The flag triple `{expect[2], mask[1], capture[0]}` is
  * locked at `[2:0]` on every bearer opcode. All 25 live opcodes: 9 WIRE, 8
  * CTRL, 8 DATA; LOOP group fully reserved.
  *
  * Wire-format stability. Pre-Phase-0 the binary encoding is still mutable
  * (AGENTS §3.17); once the Rust host encoder ships its first tagged release
  * the encoding becomes a contract. Reordering opcodes, moving the flag triple,
  * repurposing a reserved `tx_symbol` / `cond_code` slot, or shifting the
  * opcode field would all be wire-format breaks requiring a `BREAKING CHANGE:`
  * footer.
  *
  * File layout:
  *   1. Wire-format constants.
  *   2. SpinalEnums for the four operand domains ([[Opcode]], [[TxSymbol]],
  *      [[BusMode]], [[CondCode]]).
  *   3. The Scala-side [[Instruction]] sealed-trait ADT --- one case class per
  *      v0.2 live opcode plus a [[ReservedV05]] carrier. Pure Scala.
  *   4. [[Instruction.encode]] / [[Instruction.decode]] --- the host-side
  *      round-trip pair operating on the 32-bit wire word as a plain `Int`. The
  *      return value of `encode` is the **bit pattern**, not a meaningful
  *      numeric value; it may be negative when viewed as a signed `Int` (e.g.
  *      any DATA or LOOP-group word has bit 31 set).
  */

/** Opcode field --- the 6-bit value `{group[31:30], sub[29:26]}`.
  *
  * 64 total slots across 4 groups. 25 are live (9 WIRE + 8 CTRL + 8 DATA); 39
  * are reserved. The LOOP group (`group=0b11`) is fully reserved.
  *
  * Each element's `.position` is the 6-bit opcode word value:
  * `group * 16 + sub`. The `defaultEncoding = binarySequential` is thus the
  * natural declaration-order encoding, which matches the group/sub partition
  * only if we declare them in group order. We use an explicit
  * `SpinalEnumEncoding` so the correspondence is audit-able.
  */
object Opcode extends SpinalEnum {
  // ---- WIRE group (group = 0b00 = 0) --------------------------------
  val emitBitImm = newElement() // 0x00  WIRE sub=0x0
  val emitBitReg = newElement() // 0x01  WIRE sub=0x1
  val emitQuarterImm = newElement() // 0x02  WIRE sub=0x2
  val emitQuarterReg = newElement() // 0x03  WIRE sub=0x3
  val emitByte = newElement() // 0x04  WIRE sub=0x4
  val sampleBitOnScl = newElement() // 0x05  WIRE sub=0x5
  val driveBitOnScl = newElement() // 0x06  WIRE sub=0x6
  val stretchSclImm = newElement() // 0x07  WIRE sub=0x7
  val stretchSclReg = newElement() // 0x08  WIRE sub=0x8
  val wireRes9 = newElement() // 0x09  WIRE reserved
  val wireResA = newElement() // 0x0A  WIRE reserved
  val wireResB = newElement() // 0x0B  WIRE reserved
  val wireResC = newElement() // 0x0C  WIRE reserved
  val wireResD = newElement() // 0x0D  WIRE reserved
  val wireResE = newElement() // 0x0E  WIRE reserved
  val wireResF = newElement() // 0x0F  WIRE reserved

  // ---- CTRL group (group = 0b01 = 1) --------------------------------
  val halt = newElement() // 0x10  CTRL sub=0x0
  val branchOn = newElement() // 0x11  CTRL sub=0x1
  val waitOn = newElement() // 0x12  CTRL sub=0x2
  val setBusMode = newElement() // 0x13  CTRL sub=0x3
  val setRole = newElement() // 0x14  CTRL sub=0x4
  val flagClear = newElement() // 0x15  CTRL sub=0x5
  val mark = newElement() // 0x16  CTRL sub=0x6
  val loadTiming = newElement() // 0x17  CTRL sub=0x7
  val ctrlRes8 = newElement() // 0x18  CTRL reserved
  val ctrlRes9 = newElement() // 0x19  CTRL reserved
  val ctrlResA = newElement() // 0x1A  CTRL reserved
  val ctrlResB = newElement() // 0x1B  CTRL reserved
  val ctrlResC = newElement() // 0x1C  CTRL reserved
  val ctrlResD = newElement() // 0x1D  CTRL reserved
  val ctrlResE = newElement() // 0x1E  CTRL reserved
  val ctrlResF = newElement() // 0x1F  CTRL reserved

  // ---- DATA group (group = 0b10 = 2) --------------------------------
  val loadImm = newElement() // 0x20  DATA sub=0x0
  val mov = newElement() // 0x21  DATA sub=0x1
  val addImm = newElement() // 0x22  DATA sub=0x2
  val dec = newElement() // 0x23  DATA sub=0x3
  val andImm = newElement() // 0x24  DATA sub=0x4
  val orImm = newElement() // 0x25  DATA sub=0x5
  val xorImm = newElement() // 0x26  DATA sub=0x6
  val shift = newElement() // 0x27  DATA sub=0x7
  val dataRes8 = newElement() // 0x28  DATA reserved
  val dataRes9 = newElement() // 0x29  DATA reserved
  val dataResA = newElement() // 0x2A  DATA reserved
  val dataResB = newElement() // 0x2B  DATA reserved
  val dataResC = newElement() // 0x2C  DATA reserved
  val dataResD = newElement() // 0x2D  DATA reserved
  val dataResE = newElement() // 0x2E  DATA reserved
  val dataResF = newElement() // 0x2F  DATA reserved

  // ---- LOOP group (group = 0b11 = 3) --- fully reserved -------------
  val loopRes0 = newElement() // 0x30  LOOP reserved
  val loopRes1 = newElement() // 0x31  LOOP reserved
  val loopRes2 = newElement() // 0x32  LOOP reserved
  val loopRes3 = newElement() // 0x33  LOOP reserved
  val loopRes4 = newElement() // 0x34  LOOP reserved
  val loopRes5 = newElement() // 0x35  LOOP reserved
  val loopRes6 = newElement() // 0x36  LOOP reserved
  val loopRes7 = newElement() // 0x37  LOOP reserved
  val loopRes8 = newElement() // 0x38  LOOP reserved
  val loopRes9 = newElement() // 0x39  LOOP reserved
  val loopResA = newElement() // 0x3A  LOOP reserved
  val loopResB = newElement() // 0x3B  LOOP reserved
  val loopResC = newElement() // 0x3C  LOOP reserved
  val loopResD = newElement() // 0x3D  LOOP reserved
  val loopResE = newElement() // 0x3E  LOOP reserved
  val loopResF = newElement() // 0x3F  LOOP reserved

  /** `true` iff the opcode slot is a live v0.2 opcode (one of the 25 defined in
    * spec §4). `false` for every reserved slot.
    */
  def isLive(op: Opcode.E): Boolean = op match {
    case `emitBitImm` | `emitBitReg` | `emitQuarterImm` | `emitQuarterReg` |
        `emitByte` | `sampleBitOnScl` | `driveBitOnScl` | `stretchSclImm` |
        `stretchSclReg` =>
      true
    case `halt` | `branchOn` | `waitOn` | `setBusMode` | `setRole` |
        `flagClear` | `mark` | `loadTiming` =>
      true
    case `loadImm` | `mov` | `addImm` | `dec` | `andImm` | `orImm` | `xorImm` |
        `shift` =>
      true
    case _ => false
  }

  /** The 2-bit group of an opcode (bits `[31:30]` of the instruction). */
  def group(op: Opcode.E): Int = (op.position >> 4) & 0x3

  /** The 4-bit sub-opcode of an opcode (bits `[29:26]` of the instruction). */
  def sub(op: Opcode.E): Int = op.position & 0xf
}

/** Per-line drive operand --- 2 bits, named symbolically per spec §2.
  *
  * `reserved` (binary `11`) is held for the v0.5 `raw_override` escape. The
  * encoder/decoder round-trip the value but the engine refuses to execute it in
  * v0.2 unless `(use-raw-primitives)` is declared.
  *
  * Appears in five opcodes: EMIT_BIT_IMM, EMIT_BIT_REG, EMIT_QUARTER_IMM (two
  * fields: sda and scl), EMIT_QUARTER_REG (via register low bits), and
  * DRIVE_BIT_ON_SCL.
  */
object TxSymbol extends SpinalEnum {
  val dominant = newElement() // 0b00 --- active-drive toward dominant
  val recessive = newElement() // 0b01 --- release / Hi-Z + pull
  val hiz = newElement() // 0b10 --- tri-state driver-off
  val reserved = newElement() // 0b11 --- v0.5 raw_override (do not repurpose)
}

/** Bus mode register --- 4-bit wire encoding per spec §9.
  *
  * v0.2 renumbers from v0 (which used non-sequential values 0, 1, 6, 7). v0.2
  * wire values are sequential: i2c=0, i3c-OD=1, i3c-PP=2, hdr-ddr=3.
  *
  * The explicit `SpinalEnumEncoding` carries the wire values so RTL and host
  * always agree at the bit level.
  */
object BusMode extends SpinalEnum {
  val i2c = newElement() // 0b0000 = 0   OD class
  val i3cOd = newElement() // 0b0001 = 1   OD class
  val i3cPp = newElement() // 0b0010 = 2   PP class
  val hdrDdr = newElement() // 0b0011 = 3   PP class
  // 0b0100..0b1111 reserved; engine traps STATUS_TRAP if SET

  defaultEncoding = SpinalEnumEncoding("busModeWireV02")(
    i2c -> 0,
    i3cOd -> 1,
    i3cPp -> 2,
    hdrDdr -> 3
  )

  /** Wire-level integer value for a BusMode element. Matches the
    * `defaultEncoding` table above and the Rust encoder's constants.
    */
  def wireValue(mode: BusMode.E): Int = mode match {
    case `i2c`    => 0
    case `i3cOd`  => 1
    case `i3cPp`  => 2
    case `hdrDdr` => 3
  }
}

/** Unified condition code --- 4-bit field at `[16:13]` of both BRANCH_ON and
  * WAIT_ON. Single shared namespace per spec §6 and AGENTS §3.14.
  *
  * Codes 0..11 are live; 12..15 are reserved. Adding a code in a reserved slot
  * is NOT a wire-format break; repurposing one in use IS.
  *
  * Declaration order matches wire codes 0..15 so `.position == wire_code`.
  */
object CondCode extends SpinalEnum {
  val always = newElement() // 0x0  unconditional (always true)
  val mismatch = newElement() // 0x1  MISMATCH_FLAG is set
  val notMismatch = newElement() // 0x2  MISMATCH_FLAG is clear
  val startSeen = newElement() // 0x3  START_FLAG is set
  val stopSeen = newElement() // 0x4  STOP_FLAG is set
  val sdaLow = newElement() // 0x5  SDA currently dominant (low)
  val sdaHigh = newElement() // 0x6  SDA currently recessive (high/hi-z)
  val sclHigh = newElement() // 0x7  SCL currently recessive (high/hi-z)
  val timeout = newElement() // 0x8  TIMEOUT_FLAG is set
  val notTimeout = newElement() // 0x9  TIMEOUT_FLAG is clear
  val regZero = newElement() // 0xA  REG_ZERO_FLAG is set (NEW in v0.2)
  val notRegZero = newElement() // 0xB  REG_ZERO_FLAG is clear (NEW in v0.2)
  val reserved12 = newElement() // 0xC  reserved (engine trap STATUS_TRAP)
  val reserved13 = newElement() // 0xD  reserved
  val reserved14 = newElement() // 0xE  reserved
  val reserved15 = newElement() // 0xF  reserved

  /** `true` for live cond codes 0x0..0xB. `false` for 0xC..0xF (reserved). */
  def isLive(c: CondCode.E): Boolean = c.position < 12
}

/** Host-side instruction value --- one case class per live v0.2 opcode plus a
  * [[ReservedV05]] carrier for reserved slots. The sealed trait gives the
  * round-trip suite typed `==`-based equality, and the case-class form mirrors
  * the Rust host encoder's structure.
  */
sealed trait Instruction

object Instruction {

  // ------------------------------------------------------------
  // Wire-format constants (spec §3, §10)
  // ------------------------------------------------------------

  /** Instruction word width, in bits (v0.2 = 32). */
  val WORD_WIDTH: Int = 32

  /** Group field high bit. */
  val GROUP_HI: Int = 31

  /** Group field low bit (2-bit field `[31:30]`). */
  val GROUP_LO: Int = 30

  /** Sub-opcode field high bit. */
  val SUB_HI: Int = 29

  /** Sub-opcode field low bit (4-bit field `[29:26]`). */
  val SUB_LO: Int = 26

  /** Flag triple --- `expect` bit position. Locked at `[2]`. */
  val EXPECT_BIT: Int = 2

  /** Flag triple --- `mask` bit position. Locked at `[1]`. */
  val MASK_BIT: Int = 1

  /** Flag triple --- `capture` bit position. Locked at `[0]`. */
  val CAPTURE_BIT: Int = 0

  /** High bit of flag triple (same as EXPECT_BIT; named for range checks). */
  val FLAG_TRIPLE_HI: Int = 2

  /** Low bit of flag triple. */
  val FLAG_TRIPLE_LO: Int = 0

  /** Mirrors `mole-abi::MAGIC`. Full 32-bit preamble word 0 value. */
  val MAGIC: Long = 0x0002_4d4cL

  /** Mirrors `mole-abi::FORMAT_VERSION`. */
  val FORMAT_VERSION: Int = 0x0002

  /** Mirrors `mole-abi::PREAMBLE_WORDS`. */
  val PREAMBLE_WORDS: Int = 2

  /** Mirrors `mole-abi::MAX_PROGRAM_WORDS`. */
  val MAX_PROGRAM_WORDS: Int = 8192

  // ------------------------------------------------------------
  // Per-opcode host-side case classes
  // Bit layouts per spec §5; field positions are quoted literally.
  // ------------------------------------------------------------

  /** WIRE.EMIT_BIT_IMM (spec §5.1)
    *
    * {{{
    *   [31:26] opcode = group=0b00 sub=0b0000 (= 0x00 << 26)
    *   [25: 5] reserved = 0
    *   [ 4: 3] tx_symbol (2 bits)
    *   [ 2: 0] flags (expect[2], mask[1], capture[0])
    * }}}
    */
  case class EmitBitImm(
      txSymbol: TxSymbol.E,
      expect: Boolean,
      mask: Boolean,
      capture: Boolean
  ) extends Instruction

  /** WIRE.EMIT_BIT_REG (spec §5.2)
    *
    * {{{
    *   [31:26] opcode = group=0b00 sub=0b0001
    *   [25:23] reserved = 0
    *   [22:20] src (3 bits: register R0–R7; low 2 bits used as tx_symbol at E)
    *   [19: 3] reserved = 0
    *   [ 2: 0] flags
    * }}}
    */
  case class EmitBitReg(
      src: Int,
      expect: Boolean,
      mask: Boolean,
      capture: Boolean
  ) extends Instruction

  /** WIRE.EMIT_QUARTER_IMM (spec §5.3)
    *
    * {{{
    *   [31:26] opcode = group=0b00 sub=0b0010
    *   [25: 7] reserved = 0
    *   [ 6: 5] scl_symbol (2 bits)
    *   [ 4: 3] sda_symbol (2 bits)
    *   [ 2: 0] flags
    * }}}
    */
  case class EmitQuarterImm(
      sdaSymbol: TxSymbol.E,
      sclSymbol: TxSymbol.E,
      expect: Boolean,
      mask: Boolean,
      capture: Boolean
  ) extends Instruction

  /** WIRE.EMIT_QUARTER_REG (spec §5.4)
    *
    * {{{
    *   [31:26] opcode = group=0b00 sub=0b0011
    *   [25:23] reserved = 0
    *   [22:20] src (3 bits: register; R[src][3:2]=scl, R[src][1:0]=sda at E)
    *   [19: 3] reserved = 0
    *   [ 2: 0] flags
    * }}}
    */
  case class EmitQuarterReg(
      src: Int,
      expect: Boolean,
      mask: Boolean,
      capture: Boolean
  ) extends Instruction

  /** WIRE.EMIT_BYTE (spec §5.5)
    *
    * {{{
    *   [31:26] opcode = group=0b00 sub=0b0100
    *   [25: 3] reserved = 0
    *   [ 2: 0] flags (applied to the ACK/NAK ninth-bit slot)
    * }}}
    */
  case class EmitByte(
      expect: Boolean,
      mask: Boolean,
      capture: Boolean
  ) extends Instruction

  /** WIRE.SAMPLE_BIT_ON_SCL (spec §5.6)
    *
    * {{{
    *   [31:26] opcode = group=0b00 sub=0b0101
    *   [25:23] dst (3 bits: must be R7=0b111 unless raw/ pragma)
    *   [22: 3] reserved = 0
    *   [ 2: 0] flags
    * }}}
    *
    * This case class always encodes `dst=7` (R7), matching the canonical
    * non-raw-mode encoder. Under raw/ mode the encoder would need a separate
    * path; the Scala encoder here is the canonical path.
    */
  case class SampleBitOnScl(
      expect: Boolean,
      mask: Boolean,
      capture: Boolean
  ) extends Instruction

  /** WIRE.DRIVE_BIT_ON_SCL (spec §5.7)
    *
    * {{{
    *   [31:26] opcode = group=0b00 sub=0b0110
    *   [25:23] dst (3 bits: R7 when capture=1, else 0b000)
    *   [22: 5] reserved = 0
    *   [ 4: 3] tx_symbol (2 bits)
    *   [ 2: 0] flags
    * }}}
    *
    * Canonical encoder rule (spec §5.7): when `capture=0`, `dst=0b000` is
    * emitted; when `capture=1`, `dst=7` (R7). This matches the Rust encoder's
    * canonical non-raw path.
    */
  case class DriveBitOnScl(
      txSymbol: TxSymbol.E,
      expect: Boolean,
      mask: Boolean,
      capture: Boolean
  ) extends Instruction

  /** WIRE.STRETCH_SCL_IMM (spec §5.8)
    *
    * {{{
    *   [31:26] opcode = group=0b00 sub=0b0111
    *   [25:17] reserved = 0
    *   [16: 3] n_quarters (14 bits, unsigned, 0..16383)
    *   [ 2: 0] reserved = 0  (NOT a flag bearer)
    * }}}
    */
  case class StretchSclImm(nQuarters: Int) extends Instruction

  /** WIRE.STRETCH_SCL_REG (spec §5.9)
    *
    * {{{
    *   [31:26] opcode = group=0b00 sub=0b1000
    *   [25:23] reserved = 0
    *   [22:20] src (3 bits: R[src][13:0] used as count at E)
    *   [19: 3] reserved = 0
    *   [ 2: 0] reserved = 0  (NOT a flag bearer)
    * }}}
    */
  case class StretchSclReg(src: Int) extends Instruction

  /** CTRL.HALT (spec §5.10)
    *
    * {{{
    *   [31:26] opcode = group=0b01 sub=0b0000
    *   [25: 8] reserved = 0
    *   [ 7: 3] status (5 bits, 0..31; user range 0..0x1C)
    *   [ 2: 0] reserved = 0
    * }}}
    */
  case class Halt(status: Int) extends Instruction

  /** CTRL.BRANCH_ON (spec §5.11)
    *
    * {{{
    *   [31:26] opcode = group=0b01 sub=0b0001
    *   [25:17] reserved = 0
    *   [16:13] cond (4 bits: condition code 0..15)
    *   [12: 3] offset (10 bits, signed two's-complement, -512..511)
    *   [ 2: 0] reserved = 0
    * }}}
    */
  case class BranchOn(cond: CondCode.E, pcRelOffset: Int) extends Instruction

  /** CTRL.WAIT_ON (spec §5.12)
    *
    * {{{
    *   [31:26] opcode = group=0b01 sub=0b0010
    *   [25:17] reserved = 0
    *   [16:13] cond (4 bits: condition code 0..15)
    *   [12: 3] timeout (10 bits, unsigned, 0..1023; 0 = infinite)
    *   [ 2: 0] reserved = 0
    * }}}
    */
  case class WaitOn(cond: CondCode.E, timeoutQuarters: Int) extends Instruction

  /** CTRL.SET_BUS_MODE (spec §5.13)
    *
    * {{{
    *   [31:26] opcode = group=0b01 sub=0b0011
    *   [25: 7] reserved = 0
    *   [ 6: 3] mode (4 bits: 0b0000=i2c, 0b0001=i3c-OD, 0b0010=i3c-PP,
    *                          0b0011=hdr-ddr; 0b0100..0b1111 reserved)
    *   [ 2: 0] reserved = 0
    * }}}
    */
  case class SetBusMode(mode: BusMode.E) extends Instruction

  /** CTRL.SET_ROLE (spec §5.14)
    *
    * {{{
    *   [31:26] opcode = group=0b01 sub=0b0100
    *   [25: 4] reserved = 0
    *   [ 3]    role (1 bit: 0=controller, 1=target)
    *   [ 2: 0] reserved = 0
    * }}}
    */
  case class SetRole(role: Boolean) extends Instruction

  /** CTRL.FLAG_CLEAR (spec §5.15)
    *
    * {{{
    *   [31:26] opcode = group=0b01 sub=0b0101
    *   [25: 8] reserved = 0
    *   [ 7: 3] mask (5 bits; bit N=1 clears flag N)
    *   [ 2: 0] reserved = 0
    * }}}
    */
  case class FlagClear(clearMask: Int) extends Instruction

  /** CTRL.MARK (spec §5.16)
    *
    * {{{
    *   [31:26] opcode = group=0b01 sub=0b0110
    *   [25:17] reserved = 0
    *   [16: 3] label (14 bits, unsigned, 0..16383)
    *   [ 2: 0] reserved = 0
    * }}}
    */
  case class Mark(label: Int) extends Instruction

  /** CTRL.LOAD_TIMING (spec §5.17)
    *
    * {{{
    *   [31:26] opcode = group=0b01 sub=0b0111
    *   [25:20] reserved = 0
    *   [19:17] reg (3 bits: timing-register selector 0..7)
    *   [16: 3] divider (14 bits, unsigned, 0..16383)
    *   [ 2: 0] reserved = 0
    * }}}
    */
  case class LoadTiming(reg: Int, divider: Int) extends Instruction

  /** DATA.LOAD_IMM (spec §5.18)
    *
    * {{{
    *   [31:26] opcode = group=0b10 sub=0b0000
    *   [25:23] dst (3 bits: destination register R0–R7)
    *   [22:17] reserved = 0
    *   [16: 3] imm14 (14 bits, unsigned, 0..16383)
    *   [ 2: 0] reserved = 0
    * }}}
    *
    * Sugar: `LOAD_LOOP n` assembles as `LOAD_IMM R6, n`.
    */
  case class LoadImm(dst: Int, imm14: Int) extends Instruction

  /** DATA.MOV (spec §5.19)
    *
    * {{{
    *   [31:26] opcode = group=0b10 sub=0b0001
    *   [25:23] dst (3 bits)
    *   [22:20] src (3 bits)
    *   [19: 3] reserved = 0
    *   [ 2: 0] reserved = 0
    * }}}
    */
  case class Mov(dst: Int, src: Int) extends Instruction

  /** DATA.ADD_IMM (spec §5.20)
    *
    * {{{
    *   [31:26] opcode = group=0b10 sub=0b0010
    *   [25:23] dst (3 bits)
    *   [22:20] src (3 bits)
    *   [19:17] reserved = 0
    *   [16: 3] imm14 (14 bits, signed two's-complement, -8192..8191)
    *   [ 2: 0] reserved = 0
    * }}}
    */
  case class AddImm(dst: Int, src: Int, imm14: Int) extends Instruction

  /** DATA.DEC (spec §5.21)
    *
    * {{{
    *   [31:26] opcode = group=0b10 sub=0b0011
    *   [25:23] dst (3 bits; same register as src)
    *   [22:20] src (3 bits; same as dst; assembler ensures dst==src)
    *   [19: 3] reserved = 0
    *   [ 2: 0] reserved = 0
    * }}}
    */
  case class Dec(reg: Int) extends Instruction

  /** DATA.AND_IMM (spec §5.22)
    *
    * {{{
    *   [31:26] opcode = group=0b10 sub=0b0100
    *   [25:23] dst (3 bits)
    *   [22:20] src (3 bits)
    *   [19:17] reserved = 0
    *   [16: 3] imm14 (14 bits, zero-extended mask)
    *   [ 2: 0] reserved = 0
    * }}}
    */
  case class AndImm(dst: Int, src: Int, imm14: Int) extends Instruction

  /** DATA.OR_IMM (spec §5.23) --- identical layout to AND_IMM, sub=0b0101. */
  case class OrImm(dst: Int, src: Int, imm14: Int) extends Instruction

  /** DATA.XOR_IMM (spec §5.24) --- identical layout to AND_IMM, sub=0b0110. */
  case class XorImm(dst: Int, src: Int, imm14: Int) extends Instruction

  /** DATA.SHIFT (spec §5.25)
    *
    * {{{
    *   [31:26] opcode = group=0b10 sub=0b0111
    *   [25:23] dst (3 bits)
    *   [22:20] src (3 bits)
    *   [19]    arith (1 bit: 0=logical, 1=arithmetic)
    *   [18]    dir   (1 bit: 0=left, 1=right)
    *   [17]    reserved = 0
    *   [16: 8] reserved = 0
    *   [ 7: 3] shamt (5 bits: shift amount 0..31)
    *   [ 2: 0] reserved = 0
    * }}}
    */
  case class Shift(dst: Int, src: Int, arith: Boolean, dir: Boolean, shamt: Int)
      extends Instruction

  /** Reserved opcode carrier --- round-trips a 26-bit operand payload verbatim
    * for any reserved slot. v0.2 has no semantics for reserved slots; the
    * engine traps them at fetch.
    *
    * Constructor enforces `Opcode.isLive(opcode) == false`; live opcodes use
    * their own typed case classes.
    */
  case class ReservedV05(opcode: Opcode.E, payload: Int) extends Instruction {
    require(
      !Opcode.isLive(opcode),
      s"ReservedV05 carries reserved opcodes only; got live opcode $opcode"
    )
    require(
      payload >= 0 && payload < (1 << 26),
      s"ReservedV05 payload must fit in 26 bits, got $payload"
    )
  }

  // ------------------------------------------------------------
  // Encode / decode --- host-side round-trip pair
  // ------------------------------------------------------------

  /** Encode a host-side [[Instruction]] into its 32-bit wire word.
    *
    * **Pure Scala, not RTL.** This is the sim-time reference encoder. The host
    * runtime encoder is the `mole-asm` Rust crate; this Scala function exists
    * to (1) satisfy the round-trip sim requirement and (2) cross-validate the
    * Rust encoder via the golden fixture cross-check sim.
    *
    * Returns the **bit pattern** as a plain `scala.Int`. The return value may
    * be negative when viewed as a signed Int (bit 31 set for DATA and LOOP
    * group words); this is expected --- callers must treat the value as an
    * unsigned 32-bit quantity (e.g. use `Integer.toUnsignedString` for display,
    * or `0xFFFFFFFFL & word` when promoting to `Long`).
    *
    * Throws [[IllegalArgumentException]] on out-of-range operand values --- the
    * caller is responsible for range-checking before calling encode.
    */
  def encode(insn: Instruction): Int = insn match {

    // ---- WIRE group -----------------------------------------------

    case EmitBitImm(tx, expect, mask, capture) =>
      opcodeWord(Opcode.emitBitImm) |
        (tx.position << 3) |
        flagTripleBits(expect, mask, capture)

    case EmitBitReg(src, expect, mask, capture) =>
      requireReg("EMIT_BIT_REG src", src)
      opcodeWord(Opcode.emitBitReg) |
        (src << 20) |
        flagTripleBits(expect, mask, capture)

    case EmitQuarterImm(sda, scl, expect, mask, capture) =>
      opcodeWord(Opcode.emitQuarterImm) |
        (scl.position << 5) |
        (sda.position << 3) |
        flagTripleBits(expect, mask, capture)

    case EmitQuarterReg(src, expect, mask, capture) =>
      requireReg("EMIT_QUARTER_REG src", src)
      opcodeWord(Opcode.emitQuarterReg) |
        (src << 20) |
        flagTripleBits(expect, mask, capture)

    case EmitByte(expect, mask, capture) =>
      opcodeWord(Opcode.emitByte) |
        flagTripleBits(expect, mask, capture)

    case SampleBitOnScl(expect, mask, capture) =>
      // Canonical: dst=R7=7 always (non-raw mode)
      opcodeWord(Opcode.sampleBitOnScl) |
        (7 << 23) |
        flagTripleBits(expect, mask, capture)

    case DriveBitOnScl(tx, expect, mask, capture) =>
      // Canonical: dst=R7 when capture=1, dst=0 when capture=0 (spec §5.7)
      val dst = if (capture) 7 else 0
      opcodeWord(Opcode.driveBitOnScl) |
        (dst << 23) |
        (tx.position << 3) |
        flagTripleBits(expect, mask, capture)

    case StretchSclImm(nQuarters) =>
      require(
        nQuarters >= 0 && nQuarters < (1 << 14),
        s"STRETCH_SCL_IMM n_quarters must be 0..16383, got $nQuarters"
      )
      opcodeWord(Opcode.stretchSclImm) | (nQuarters << 3)

    case StretchSclReg(src) =>
      requireReg("STRETCH_SCL_REG src", src)
      opcodeWord(Opcode.stretchSclReg) | (src << 20)

    // ---- CTRL group -----------------------------------------------

    case Halt(status) =>
      require(
        status >= 0 && status < 32,
        s"HALT status must be 0..31, got $status"
      )
      opcodeWord(Opcode.halt) | (status << 3)

    case BranchOn(cond, pcRelOffset) =>
      require(
        pcRelOffset >= -512 && pcRelOffset <= 511,
        s"BRANCH_ON offset must be signed 10-bit (-512..511), got $pcRelOffset"
      )
      opcodeWord(Opcode.branchOn) |
        (cond.position << 13) |
        ((pcRelOffset & 0x3ff) << 3)

    case WaitOn(cond, timeoutQuarters) =>
      require(
        timeoutQuarters >= 0 && timeoutQuarters < 1024,
        s"WAIT_ON timeout must be 0..1023, got $timeoutQuarters"
      )
      opcodeWord(Opcode.waitOn) |
        (cond.position << 13) |
        (timeoutQuarters << 3)

    case SetBusMode(mode) =>
      opcodeWord(Opcode.setBusMode) | (BusMode.wireValue(mode) << 3)

    case SetRole(role) =>
      opcodeWord(Opcode.setRole) | ((if (role) 1 else 0) << 3)

    case FlagClear(clearMask) =>
      require(
        clearMask >= 0 && clearMask < 32,
        s"FLAG_CLEAR mask must be 0..31 (5-bit), got $clearMask"
      )
      opcodeWord(Opcode.flagClear) | (clearMask << 3)

    case Mark(label) =>
      require(
        label >= 0 && label < (1 << 14),
        s"MARK label must be 0..16383, got $label"
      )
      opcodeWord(Opcode.mark) | (label << 3)

    case LoadTiming(reg, divider) =>
      require(
        reg >= 0 && reg < 8,
        s"LOAD_TIMING reg must be 0..7, got $reg"
      )
      require(
        divider >= 0 && divider < (1 << 14),
        s"LOAD_TIMING divider must be 0..16383, got $divider"
      )
      opcodeWord(Opcode.loadTiming) | (reg << 17) | (divider << 3)

    // ---- DATA group -----------------------------------------------

    case LoadImm(dst, imm14) =>
      requireReg("LOAD_IMM dst", dst)
      require(
        imm14 >= 0 && imm14 < (1 << 14),
        s"LOAD_IMM imm14 must be 0..16383 (unsigned), got $imm14"
      )
      opcodeWord(Opcode.loadImm) | (dst << 23) | (imm14 << 3)

    case Mov(dst, src) =>
      requireReg("MOV dst", dst)
      requireReg("MOV src", src)
      opcodeWord(Opcode.mov) | (dst << 23) | (src << 20)

    case AddImm(dst, src, imm14) =>
      requireReg("ADD_IMM dst", dst)
      requireReg("ADD_IMM src", src)
      require(
        imm14 >= -8192 && imm14 <= 8191,
        s"ADD_IMM imm14 must be -8192..8191 (signed 14-bit), got $imm14"
      )
      opcodeWord(Opcode.addImm) |
        (dst << 23) |
        (src << 20) |
        ((imm14 & 0x3fff) << 3)

    case Dec(reg) =>
      requireReg("DEC", reg)
      // Spec §5.21: dst and src both set to the same register.
      opcodeWord(Opcode.dec) | (reg << 23) | (reg << 20)

    case AndImm(dst, src, imm14) =>
      requireReg("AND_IMM dst", dst)
      requireReg("AND_IMM src", src)
      require(
        imm14 >= 0 && imm14 < (1 << 14),
        s"AND_IMM imm14 must be 0..16383 (zero-extended), got $imm14"
      )
      opcodeWord(Opcode.andImm) |
        (dst << 23) |
        (src << 20) |
        (imm14 << 3)

    case OrImm(dst, src, imm14) =>
      requireReg("OR_IMM dst", dst)
      requireReg("OR_IMM src", src)
      require(
        imm14 >= 0 && imm14 < (1 << 14),
        s"OR_IMM imm14 must be 0..16383, got $imm14"
      )
      opcodeWord(Opcode.orImm) |
        (dst << 23) |
        (src << 20) |
        (imm14 << 3)

    case XorImm(dst, src, imm14) =>
      requireReg("XOR_IMM dst", dst)
      requireReg("XOR_IMM src", src)
      require(
        imm14 >= 0 && imm14 < (1 << 14),
        s"XOR_IMM imm14 must be 0..16383, got $imm14"
      )
      opcodeWord(Opcode.xorImm) |
        (dst << 23) |
        (src << 20) |
        (imm14 << 3)

    case Shift(dst, src, arith, dir, shamt) =>
      requireReg("SHIFT dst", dst)
      requireReg("SHIFT src", src)
      require(
        shamt >= 0 && shamt < 32,
        s"SHIFT shamt must be 0..31, got $shamt"
      )
      opcodeWord(Opcode.shift) |
        (dst << 23) |
        (src << 20) |
        ((if (arith) 1 else 0) << 19) |
        ((if (dir) 1 else 0) << 18) |
        (shamt << 3)

    // ---- Reserved carrier -----------------------------------------

    case ReservedV05(opcode, payload) =>
      // Constructor already enforces non-live opcode + 26-bit payload.
      opcodeWord(opcode) | payload
  }

  /** Decode a 32-bit wire word into its host-side [[Instruction]].
    *
    * **Pure Scala, not RTL.** Same role as [[encode]]. `word` is the raw 32-bit
    * bit pattern; it may be negative when treated as a signed `Int`.
    *
    * Reserved-bit slices in the wire word are not inspected --- a hand-crafted
    * word with stray bits in a reserved field decodes to the same case-class
    * value as a clean one. Round-trip stability (`decode(encode(i)) == i`) is
    * the invariant the sim asserts; strict "reject stray reserved bits" is a
    * defense-in-depth check the engine performs at fetch.
    */
  def decode(word: Int): Instruction = {
    val groupBits = (word >>> 30) & 0x3
    val subBits = (word >>> 26) & 0xf
    val opPos = (groupBits << 4) | subBits
    val op = Opcode.elements(opPos)

    op match {

      // ---- WIRE group ---------------------------------------------

      case Opcode.emitBitImm =>
        EmitBitImm(
          txSymbol = TxSymbol.elements((word >>> 3) & 0x3),
          expect = bitSet(word, EXPECT_BIT),
          mask = bitSet(word, MASK_BIT),
          capture = bitSet(word, CAPTURE_BIT)
        )

      case Opcode.emitBitReg =>
        EmitBitReg(
          src = (word >>> 20) & 0x7,
          expect = bitSet(word, EXPECT_BIT),
          mask = bitSet(word, MASK_BIT),
          capture = bitSet(word, CAPTURE_BIT)
        )

      case Opcode.emitQuarterImm =>
        EmitQuarterImm(
          sdaSymbol = TxSymbol.elements((word >>> 3) & 0x3),
          sclSymbol = TxSymbol.elements((word >>> 5) & 0x3),
          expect = bitSet(word, EXPECT_BIT),
          mask = bitSet(word, MASK_BIT),
          capture = bitSet(word, CAPTURE_BIT)
        )

      case Opcode.emitQuarterReg =>
        EmitQuarterReg(
          src = (word >>> 20) & 0x7,
          expect = bitSet(word, EXPECT_BIT),
          mask = bitSet(word, MASK_BIT),
          capture = bitSet(word, CAPTURE_BIT)
        )

      case Opcode.emitByte =>
        EmitByte(
          expect = bitSet(word, EXPECT_BIT),
          mask = bitSet(word, MASK_BIT),
          capture = bitSet(word, CAPTURE_BIT)
        )

      case Opcode.sampleBitOnScl =>
        // Decode: dst field at [25:23] is present but ignored in canonical mode.
        // The case class always represents the canonical R7 destination.
        SampleBitOnScl(
          expect = bitSet(word, EXPECT_BIT),
          mask = bitSet(word, MASK_BIT),
          capture = bitSet(word, CAPTURE_BIT)
        )

      case Opcode.driveBitOnScl =>
        DriveBitOnScl(
          txSymbol = TxSymbol.elements((word >>> 3) & 0x3),
          expect = bitSet(word, EXPECT_BIT),
          mask = bitSet(word, MASK_BIT),
          capture = bitSet(word, CAPTURE_BIT)
        )

      case Opcode.stretchSclImm =>
        StretchSclImm(nQuarters = (word >>> 3) & 0x3fff)

      case Opcode.stretchSclReg =>
        StretchSclReg(src = (word >>> 20) & 0x7)

      // ---- CTRL group ---------------------------------------------

      case Opcode.halt =>
        Halt(status = (word >>> 3) & 0x1f)

      case Opcode.branchOn =>
        BranchOn(
          cond = CondCode.elements((word >>> 13) & 0xf),
          pcRelOffset = signExtend10((word >>> 3) & 0x3ff)
        )

      case Opcode.waitOn =>
        WaitOn(
          cond = CondCode.elements((word >>> 13) & 0xf),
          timeoutQuarters = (word >>> 3) & 0x3ff
        )

      case Opcode.setBusMode =>
        val modeWire = (word >>> 3) & 0xf
        SetBusMode(
          mode = busModeFromWire.getOrElse(
            modeWire,
            throw new IllegalArgumentException(
              f"SET_BUS_MODE word 0x${word.toLong & 0xffffffffL}%08X: " +
                s"undefined mode wire value $modeWire " +
                s"(live values: 0=i2c, 1=i3c-OD, 2=i3c-PP, 3=hdr-ddr)"
            )
          )
        )

      case Opcode.setRole =>
        SetRole(role = bitSet(word, 3))

      case Opcode.flagClear =>
        FlagClear(clearMask = (word >>> 3) & 0x1f)

      case Opcode.mark =>
        Mark(label = (word >>> 3) & 0x3fff)

      case Opcode.loadTiming =>
        LoadTiming(
          reg = (word >>> 17) & 0x7,
          divider = (word >>> 3) & 0x3fff
        )

      // ---- DATA group ---------------------------------------------

      case Opcode.loadImm =>
        LoadImm(
          dst = (word >>> 23) & 0x7,
          imm14 = (word >>> 3) & 0x3fff
        )

      case Opcode.mov =>
        Mov(
          dst = (word >>> 23) & 0x7,
          src = (word >>> 20) & 0x7
        )

      case Opcode.addImm =>
        AddImm(
          dst = (word >>> 23) & 0x7,
          src = (word >>> 20) & 0x7,
          imm14 = signExtend14((word >>> 3) & 0x3fff)
        )

      case Opcode.dec =>
        Dec(reg = (word >>> 23) & 0x7)

      case Opcode.andImm =>
        AndImm(
          dst = (word >>> 23) & 0x7,
          src = (word >>> 20) & 0x7,
          imm14 = (word >>> 3) & 0x3fff
        )

      case Opcode.orImm =>
        OrImm(
          dst = (word >>> 23) & 0x7,
          src = (word >>> 20) & 0x7,
          imm14 = (word >>> 3) & 0x3fff
        )

      case Opcode.xorImm =>
        XorImm(
          dst = (word >>> 23) & 0x7,
          src = (word >>> 20) & 0x7,
          imm14 = (word >>> 3) & 0x3fff
        )

      case Opcode.shift =>
        Shift(
          dst = (word >>> 23) & 0x7,
          src = (word >>> 20) & 0x7,
          arith = bitSet(word, 19),
          dir = bitSet(word, 18),
          shamt = (word >>> 3) & 0x1f
        )

      // ---- Reserved carrier (all other slots) ---------------------

      case reserved =>
        ReservedV05(opcode = reserved, payload = word & 0x03ffffff)
    }
  }

  // ------------------------------------------------------------
  // Internal helpers
  // ------------------------------------------------------------

  /** Build the 32-bit opcode prefix for `op`: `{group[31:30], sub[29:26]}`. The
    * 26 low bits are zero; payload fields are OR-ed in by the encoder.
    *
    * Note: the result is a Java/Scala `Int` which is signed. For WIRE and CTRL
    * group words the MSBs are 0b00 and 0b01, so the Int value is positive. For
    * DATA group words (MSBs = 0b10) the Int value is negative --- this is
    * correct; callers should treat encode results as bit patterns.
    */
  private def opcodeWord(op: Opcode.E): Int =
    op.position << 26

  /** Wire value map for BusMode --- v0.2 sequential encoding. */
  private val busModeFromWire: Map[Int, BusMode.E] = Map(
    0 -> BusMode.i2c,
    1 -> BusMode.i3cOd,
    2 -> BusMode.i3cPp,
    3 -> BusMode.hdrDdr
  )

  /** Pack the (expect, mask, capture) triple into bits `[2:0]`. */
  private def flagTripleBits(
      expect: Boolean,
      mask: Boolean,
      capture: Boolean
  ): Int =
    (if (expect) 1 << EXPECT_BIT else 0) |
      (if (mask) 1 << MASK_BIT else 0) |
      (if (capture) 1 << CAPTURE_BIT else 0)

  /** `true` iff bit `pos` is set in `word` (treats word as unsigned). */
  private def bitSet(word: Int, pos: Int): Boolean =
    ((word >>> pos) & 1) == 1

  /** Sign-extend a 10-bit value (0..1023) to a signed `Int` (-512..511). */
  private def signExtend10(v: Int): Int = (v << 22) >> 22

  /** Sign-extend a 14-bit value (0..16383) to a signed `Int` (-8192..8191). */
  private def signExtend14(v: Int): Int = (v << 18) >> 18

  /** Assert `reg` is in range 0..7; throws [[IllegalArgumentException]]. */
  private def requireReg(name: String, reg: Int): Unit =
    require(
      reg >= 0 && reg < 8,
      s"$name register must be R0..R7 (0..7), got $reg"
    )
}
