package mole

import spinal.core._

/** 16-bit fixed-width instruction encoding for the Mole bit-cycle engine.
  *
  * This file is the Scala-side **reference implementation** of the bytecode
  * wire format. It is consumed by simulation (round-trip audits, engine tests)
  * and by the engine's RTL fetch path, which slices the 16-bit instruction word
  * directly from the `instrReg` register. It is **not** the host's runtime
  * encoder: the host compiler is the (future) Rust crate under `../../crates/`,
  * which emits pre-assembled bytes that travel raw over UART into the engine's
  * SPRAM. Once the Rust crate exists, the Scala `encode`/`decode` pair below
  * doubles as a cross-validation oracle --- "every legal instruction encodes to
  * the same 16-bit word in both implementations" is the cheapest strong-signal
  * correctness check for a wire-format contract.
  *
  * Wire-format scope. Per `../../AGENTS.md` §3.9 the instruction width is fixed
  * at 16 bits; §3.10 locks the `expect`/`mask`/`capture` flag triple at bit
  * positions `[2:0]` on every bearer opcode. ROADMAP §"Encoding width" lists
  * the full per-opcode field budget and §"ISA" lists the 12 v0 opcodes (plus 4
  * reserved slots).
  *
  * Wire-format stability. Pre-Phase-0 the binary encoding is still mutable
  * (AGENTS §3.17); once the Rust host encoder ships its first tagged release
  * the encoding becomes a contract between the host compiler and every deployed
  * Mole. Reordering opcodes, moving the flag triple, or repurposing a reserved
  * `tx_symbol` / `cond_code` slot would all be wire-format breaks that require
  * a bytecode-version bump and a `BREAKING CHANGE:` footer.
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
  * Step 7 ships all four SpinalEnums, all twelve case classes, encode + decode
  * bodies for the entire ISA (12 v0 opcodes plus a `ReservedV05` carrier for
  * the four v0.5 slots), plus the round-trip audit in [[InstructionSim]]. The
  * engine's RTL-side decode and per-opcode semantics land in Step 8 onward.
  */

/** Opcode field --- bits `[15:12]` of every instruction word.
  *
  * Sixteen total slots (4-bit field), twelve in v0 use plus four reserved for
  * v0.5 (`WAIT_ADDRESSED`, `MISMATCH_CLEAR`, `FLAG_CLEAR`, `CAPTURE_RUN`).
  * Numeric assignment locked here is the wire-format contract: declaration
  * order is the binary code under SpinalHDL's default `binarySequential`
  * encoding.
  *
  * `HALT` deliberately occupies code `0x0` so a zero-initialised SPRAM word (or
  * a fetch off the end of a loaded program) traps cleanly rather than decoding
  * as a free-running `EMIT_BIT`.
  *
  * The four reserved slots are claimed by v0.5 candidates per ROADMAP
  * §"Reserved for v0.5". `CALL` / `RET` were considered and deliberately
  * dropped --- the SDK inlines call sites at compile time, so dedicated
  * control-flow opcodes never become necessary. The `MISMATCH_CLEAR` +
  * `FLAG_CLEAR` overlap is intentional: v0.5 picks one or both depending on the
  * use case that surfaces; reserving slots for each preserves that choice. The
  * slots are reservations only; v0 has no decoder behaviour for them and the
  * engine rejects them at fetch (Step 8).
  */
object Opcode extends SpinalEnum {
  val halt = newElement() // 0x0  --- safer trap on zero-memory fetch
  val emitBit = newElement() // 0x1  --- workhorse, one full wire bit
  val emitQuarter = newElement() // 0x2  --- per-quarter override (glitches)
  val stretchScl = newElement() // 0x3  --- pull SCL low for N quarters
  val waitOn = newElement() // 0x4  --- block until cond / timeout
  val branchOn = newElement() // 0x5  --- conditional PC-relative branch
  val jmp = newElement() // 0x6  --- unconditional absolute jump
  val setBusMode = newElement() // 0x7  --- swap symbol-to-electrical map
  val loadTiming = newElement() // 0x8  --- load quarter-bit divider word
  val mark = newElement() // 0x9  --- record labelled marker in ring
  val sampleBitOnScl = newElement() // 0xA  --- target-role sample
  val driveBitOnScl = newElement() // 0xB  --- target-role drive + sample
  val waitAddressed = newElement() // 0xC  reserved (v0.5)
  val mismatchClear = newElement() // 0xD  reserved (v0.5)
  val flagClear = newElement() // 0xE  reserved (v0.5)
  val captureRun = newElement() // 0xF  reserved (v0.5)

  /** `true` for opcode codes 0x0..0xB (the twelve v0 opcodes). `false` for
    * 0xC..0xF (reserved v0.5 slots). Host encoder uses this to refuse
    * generating reserved-slot instructions in v0 programs.
    */
  def isV0(op: Opcode.E): Boolean = op.position < 12
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

/** Unified condition code --- 4-bit field at `[11:8]` of both
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
  val OPCODE_WIDTH: Int = 4

  /** Opcode field high bit. */
  val OPCODE_HI: Int = 15

  /** Opcode field low bit. */
  val OPCODE_LO: Int = 12

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
    *   [15:12] opcode   [11:10] tx_symbol   [9:3] reserved (=0)
    *   [2] expect       [1] mask            [0] capture
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
    *   [15:12] opcode   [11:8] status   [7:0] reserved (=0)
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
    *   [15:12] opcode      [11:10] sda_symbol   [9:8] scl_symbol
    *   [7:3] reserved (=0) [2] expect [1] mask  [0] capture
    * }}}
    */
  case class EmitQuarter(
      sdaSymbol: TxSymbol.E,
      sclSymbol: TxSymbol.E,
      expect: Boolean,
      mask: Boolean,
      capture: Boolean
  ) extends Instruction

  /** `STRETCH_SCL n` --- hold SCL low for `n` quarters. 12-bit operand at
    * `[11:0]`. Used in both roles: controller-role forced stretching (fuzz) and
    * target-role canonical clock stretching.
    */
  case class StretchScl(nQuarters: Int) extends Instruction

  /** `WAIT_ON cond, timeout` --- block until `cond` becomes true, or until
    * `timeout` quarter-bit ticks elapse. Layout:
    *
    * {{{
    *   [15:12] opcode   [11:8] cond_code   [7:0] timeout (unsigned)
    * }}}
    *
    * `timeout = 0` means "wait forever" (no timeout). Shares the
    * `[11:8]cond_code [7:0]operand` shape with [[BranchOn]] --- only operand
    * semantics differ (signed-PC-offset vs unsigned-quarter-timeout) per AGENTS
    * §3.14.
    */
  case class WaitOn(cond: CondCode.E, timeoutQuarters: Int) extends Instruction

  /** `BRANCH_ON cond, offset` --- conditional jump to a PC-relative signed
    * 8-bit offset (±128 instructions). Layout:
    *
    * {{{
    *   [15:12] opcode   [11:8] cond_code   [7:0] pc_rel_offset (signed)
    * }}}
    *
    * The unified branch opcode replaces all per-condition branch instructions:
    * a new condition is a new `cond_code` value, not a new opcode. Shares field
    * shape with [[WaitOn]].
    */
  case class BranchOn(cond: CondCode.E, pcRelOffset: Int) extends Instruction

  /** `JMP addr` --- unconditional jump to absolute 12-bit instruction address.
    * Layout: `[15:12] opcode [11:0] addr`. 4096-instruction range (the whole
    * program). Long-distance conditional branches in the SDK expand to
    * `BRANCH_ON cond, near` + `JMP far` to stay within [[BranchOn]]'s ±128
    * reach.
    */
  case class Jmp(addr: Int) extends Instruction

  /** `SET_BUS_MODE mode` --- swap the symbol-to-electrical mapping plus the
    * active timing divider. Layout:
    *
    * {{{
    *   [15:12] opcode   [11:9] mode   [8:0] reserved (=0)
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
    *   [15:12] opcode   [11:10] reg   [9:0] divider_word
    * }}}
    *
    * `reg` selects `pp-freq` / `od-freq` / `i2c-freq` / `hdr-ddr-freq`. Pure
    * --- does not change the active mode (use [[SetBusMode]] for that).
    */
  case class LoadTiming(reg: Int, dividerWord: Int) extends Instruction

  /** `MARK label` --- insert a labelled marker (with implicit timestamp) in the
    * result ring. Layout:
    *
    * {{{
    *   [15:12] opcode   [11:4] label   [3:0] reserved (=0)
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
    *   [15:12] opcode      [11:3] reserved (=0)
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
    *   [15:12] opcode      [11:10] tx_symbol    [9:3] reserved (=0)
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

  /** Reserved-v0.5 opcode carrier. Round-trips a 12-bit operand payload
    * verbatim --- v0 has no semantics for any of the four reserved slots, and
    * the per-opcode payload layout is a v0.5 design decision that has not
    * happened yet.
    *
    * Constructor enforces `Opcode.isV0(opcode) == false`; v0 opcodes use their
    * own typed case classes, not this carrier.
    */
  case class ReservedV05(opcode: Opcode.E, payload: Int) extends Instruction {
    require(
      !Opcode.isV0(opcode),
      s"ReservedV05 carries v0.5 reserved opcodes only; got v0 opcode $opcode"
    )
    require(
      payload >= 0 && payload < (1 << 12),
      s"ReservedV05 payload must fit in 12 bits, got $payload"
    )
  }

  // --------------------------------------------------------------
  // Encode / decode --- host-side round-trip pair
  // --------------------------------------------------------------

  /** Encode a host-side [[Instruction]] into its 16-bit wire word.
    *
    * **Pure Scala, not RTL.** This function is the sim-time reference encoder,
    * not the host's runtime encoder. The host compiler (the future Rust crate
    * under `../../crates/`) emits pre-assembled bytes over UART; this Scala
    * function exists to (1) satisfy Step 7's "round-trip encode/decode" sim
    * requirement, (2) let Step 9+ engine sims construct test programs in Scala
    * instead of hand-coded hex literals, and (3) cross-validate the Rust
    * encoder once it lands --- if the two implementations agree on every legal
    * instruction, the wire format is correct.
    *
    * `encode` operates on Scala case classes and returns a plain `scala.Int`.
    * It cannot elaborate into hardware: SpinalHDL does not "see" Scala case
    * classes at elaboration time, and the call site would emerge as a baked-in
    * constant even if invoked from a Component body.
    *
    * Throws [[IllegalArgumentException]] on out-of-range operand values (e.g. a
    * `Jmp` whose address overflows 12 bits) --- the host SDK is responsible for
    * catching those before they reach the engine.
    */
  def encode(insn: Instruction): Int = insn match {
    case EmitBit(tx, expect, mask, capture) =>
      (Opcode.emitBit.position << OPCODE_LO) |
        (tx.position << 10) |
        flagTripleBits(expect, mask, capture)

    case Halt(status) =>
      require(
        status >= 0 && status < (1 << 4),
        s"HALT status must be 0..15, got $status"
      )
      (Opcode.halt.position << OPCODE_LO) |
        (status << 8)

    case EmitQuarter(sdaSymbol, sclSymbol, expect, mask, capture) =>
      (Opcode.emitQuarter.position << OPCODE_LO) |
        (sdaSymbol.position << 10) |
        (sclSymbol.position << 8) |
        flagTripleBits(expect, mask, capture)

    case StretchScl(nQuarters) =>
      require(
        nQuarters >= 0 && nQuarters < (1 << 12),
        s"STRETCH_SCL n_quarters must fit in 12 bits, got $nQuarters"
      )
      (Opcode.stretchScl.position << OPCODE_LO) | nQuarters

    case WaitOn(cond, timeoutQuarters) =>
      require(
        timeoutQuarters >= 0 && timeoutQuarters < (1 << 8),
        s"WAIT_ON timeout must fit in 8 bits unsigned, got $timeoutQuarters"
      )
      (Opcode.waitOn.position << OPCODE_LO) |
        (cond.position << 8) |
        (timeoutQuarters & 0xff)

    case BranchOn(cond, pcRelOffset) =>
      require(
        pcRelOffset >= -128 && pcRelOffset <= 127,
        s"BRANCH_ON pc_rel_offset must be signed 8-bit (-128..127), got $pcRelOffset"
      )
      (Opcode.branchOn.position << OPCODE_LO) |
        (cond.position << 8) |
        (pcRelOffset & 0xff)

    case Jmp(addr) =>
      require(
        addr >= 0 && addr < (1 << 12),
        s"JMP addr must fit in 12 bits, got $addr"
      )
      (Opcode.jmp.position << OPCODE_LO) | addr

    case SetBusMode(mode) =>
      (Opcode.setBusMode.position << OPCODE_LO) |
        (busModeWireValue(mode) << 9)

    case LoadTiming(reg, dividerWord) =>
      require(
        reg >= 0 && reg < (1 << 2),
        s"LOAD_TIMING reg must fit in 2 bits (0..3), got $reg"
      )
      require(
        dividerWord >= 0 && dividerWord < (1 << 10),
        s"LOAD_TIMING divider_word must fit in 10 bits, got $dividerWord"
      )
      (Opcode.loadTiming.position << OPCODE_LO) |
        (reg << 10) |
        dividerWord

    case Mark(label) =>
      require(
        label >= 0 && label < (1 << 8),
        s"MARK label must fit in 8 bits, got $label"
      )
      (Opcode.mark.position << OPCODE_LO) |
        (label << 4)

    case SampleBitOnScl(expect, mask, capture) =>
      (Opcode.sampleBitOnScl.position << OPCODE_LO) |
        flagTripleBits(expect, mask, capture)

    case DriveBitOnScl(tx, expect, mask, capture) =>
      (Opcode.driveBitOnScl.position << OPCODE_LO) |
        (tx.position << 10) |
        flagTripleBits(expect, mask, capture)

    case ReservedV05(opcode, payload) =>
      // Constructor already enforces non-v0 opcode + 12-bit payload range.
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
    val op = Opcode.elements((word >> OPCODE_LO) & 0xf)
    op match {
      case Opcode.emitBit =>
        EmitBit(
          txSymbol = TxSymbol.elements((word >> 10) & 0x3),
          expect = bitSet(word, EXPECT_BIT),
          mask = bitSet(word, MASK_BIT),
          capture = bitSet(word, CAPTURE_BIT)
        )

      case Opcode.halt =>
        Halt(status = (word >> 8) & 0xf)

      case Opcode.emitQuarter =>
        EmitQuarter(
          sdaSymbol = TxSymbol.elements((word >> 10) & 0x3),
          sclSymbol = TxSymbol.elements((word >> 8) & 0x3),
          expect = bitSet(word, EXPECT_BIT),
          mask = bitSet(word, MASK_BIT),
          capture = bitSet(word, CAPTURE_BIT)
        )

      case Opcode.stretchScl =>
        StretchScl(nQuarters = word & 0xfff)

      case Opcode.waitOn =>
        WaitOn(
          cond = CondCode.elements((word >> 8) & 0xf),
          timeoutQuarters = word & 0xff
        )

      case Opcode.branchOn =>
        BranchOn(
          cond = CondCode.elements((word >> 8) & 0xf),
          pcRelOffset = signExtend8(word & 0xff)
        )

      case Opcode.jmp =>
        Jmp(addr = word & 0xfff)

      case Opcode.setBusMode =>
        val wireBits = (word >> 9) & 0x7
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
          reg = (word >> 10) & 0x3,
          dividerWord = word & 0x3ff
        )

      case Opcode.mark =>
        Mark(label = (word >> 4) & 0xff)

      case Opcode.sampleBitOnScl =>
        SampleBitOnScl(
          expect = bitSet(word, EXPECT_BIT),
          mask = bitSet(word, MASK_BIT),
          capture = bitSet(word, CAPTURE_BIT)
        )

      case Opcode.driveBitOnScl =>
        DriveBitOnScl(
          txSymbol = TxSymbol.elements((word >> 10) & 0x3),
          expect = bitSet(word, EXPECT_BIT),
          mask = bitSet(word, MASK_BIT),
          capture = bitSet(word, CAPTURE_BIT)
        )

      case reserved =>
        // Codes 0xC..0xF: the four v0.5 reserved slots. Round-trip the
        // payload verbatim; the engine traps these at fetch (Step 8 / 11).
        ReservedV05(opcode = reserved, payload = word & 0xfff)
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
    * (-128..127). Used by [[BranchOn]] to recover the signed PC-relative offset
    * from the wire word's low byte. Uses Java's arithmetic-right- shift on
    * `Int` (`>>`) to sign-extend the top bit.
    */
  private def signExtend8(byte: Int): Int = (byte << 24) >> 24
}
