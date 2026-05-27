package mole

/** Round-trip audit for [[Instruction]] --- the wire-format contract.
  *
  * Pure-Scala main, not a SpinalSim DUT. The host-side [[Instruction]] ADT
  * and its encode/decode pair are pure Scala functions over `Int` words;
  * exercising them does not need a simulator backend. Mirrors the
  * [[MoleConfigSim]] / [[OpenDrainBusSim]] convention: a `runMain`
  * entrypoint wired into `make sim-isa`, asserting and exiting zero on
  * success.
  *
  * What this sim guards against:
  *   1. **Round-trip stability.** For every legal `Instruction` value,
  *      `decode(encode(i)) == i` must hold. Any mismatch is a wire-format
  *      regression --- the host compiler and a deployed Mole would disagree
  *      on the bytecode.
  *   2. **Field-position alignment.** The opcode lives at `[15:12]`, the
  *      flag triple at `[2:0]` (`expect`/`mask`/`capture`), and `tx_symbol`
  *      at `[11:10]` of every bearer opcode. Drifting these positions is
  *      what `../../AGENTS.md` §3.10 forbids; the sim asserts the bit
  *      positions structurally.
  *   3. **Reserved-bits-are-zero invariant on encode.** Bits in the
  *      "reserved" middle slice of every opcode must encode to zero so the
  *      engine's v0-vs-v0.5 trap can distinguish a v0 program (clean
  *      reserved bits) from a forward-rolled program (set reserved bits).
  *   4. **Golden encodings** for at least one shape per opcode --- catches
  *      accidental field-shift regressions that round-trip cleanly but
  *      generate a different wire word than the spec says.
  *
  * Run: `sbt "runMain mole.InstructionSim"`
  */
object InstructionSim extends App {

  import Instruction._

  // --------------------------------------------------------------
  // Round-trip primitives
  // --------------------------------------------------------------

  /** Encode `i`, decode the result, assert equality. Returns the encoded
    * word so callers can also assert on its bit layout.
    */
  def roundTrip(i: Instruction): Int = {
    val word = encode(i)
    assert(
      (word & ~0xffff) == 0,
      f"encode($i) -> 0x$word%08X has bits set above [15:0]"
    )
    val back = decode(word)
    assert(
      back == i,
      f"round-trip failed: encode($i) = 0x$word%04X; decode = $back"
    )
    word
  }

  /** Assert that exactly the listed bit indices are set in `word`. Useful
    * for structural opcode-layout checks ("the flag triple lives at
    * `[2:0]`" --- which bits are *not* part of the triple must be zero).
    */
  def assertBitsSet(word: Int, expected: Set[Int], label: String): Unit = {
    val actual: Set[Int] =
      (0 until 16).filter(b => ((word >> b) & 1) == 1).toSet
    assert(
      actual == expected,
      f"$label: word 0x$word%04X has bits $actual set; expected $expected"
    )
  }

  /** Extract a contiguous bit field from `word`. `hi` is inclusive, `lo` is
    * inclusive (matches the `[hi:lo]` notation used in the ROADMAP).
    */
  def field(word: Int, hi: Int, lo: Int): Int =
    (word >> lo) & ((1 << (hi - lo + 1)) - 1)

  /** Assert opcode bits land at `[15:12]` and match `expected`. Every per-
    * opcode block calls this; a failure means the opcode field has drifted.
    */
  def assertOpcode(word: Int, expected: Opcode.E, label: String): Unit =
    assert(
      field(word, OPCODE_HI, OPCODE_LO) == expected.position,
      f"$label: opcode field mismatch in 0x$word%04X " +
        f"(expected ${expected.position}%X)"
    )

  /** Assert the (expect, mask, capture) flag triple lands at exactly
    * `[2:0]` of `word`. The single most load-bearing structural assertion
    * in the file (AGENTS §3.10) --- every bearer opcode runs through it.
    */
  def assertFlagTriple(
      word: Int,
      expect: Boolean,
      mask: Boolean,
      capture: Boolean,
      label: String
  ): Unit = {
    assert(
      ((word >> EXPECT_BIT) & 1) == (if (expect) 1 else 0),
      f"$label: expect bit wrong (word=0x$word%04X)"
    )
    assert(
      ((word >> MASK_BIT) & 1) == (if (mask) 1 else 0),
      f"$label: mask bit wrong (word=0x$word%04X)"
    )
    assert(
      ((word >> CAPTURE_BIT) & 1) == (if (capture) 1 else 0),
      f"$label: capture bit wrong (word=0x$word%04X)"
    )
  }

  /** Assert the slice `[hi:lo]` of `word` is zero. Used to lock in the
    * reserved-bits-are-zero invariant on each bearer opcode's middle
    * slice.
    */
  def assertReservedZero(
      word: Int,
      hi: Int,
      lo: Int,
      label: String
  ): Unit =
    assert(
      field(word, hi, lo) == 0,
      f"$label: reserved [$hi:$lo] non-zero in 0x$word%04X"
    )

  /** Iterate `(expect, mask, capture)` over all 8 combinations. */
  val allFlagTriples: Seq[(Boolean, Boolean, Boolean)] =
    for {
      expect <- Seq(false, true)
      mask <- Seq(false, true)
      capture <- Seq(false, true)
    } yield (expect, mask, capture)

  /** All four `TxSymbol` values, in declaration / wire-position order. */
  val allTxSymbols: Seq[TxSymbol.E] = Seq(
    TxSymbol.dominant,
    TxSymbol.recessive,
    TxSymbol.hiz,
    TxSymbol.reserved
  )

  /** All four `BusMode` values --- declaration order, wire codes
    * deliberately non-sequential (see [[BusMode]] docstring).
    */
  val allBusModes: Seq[BusMode.E] = Seq(
    BusMode.i2c,
    BusMode.i3cOd,
    BusMode.i3cPp,
    BusMode.hdrDdr
  )

  /** All 16 [[CondCode]] values, in wire-code order. Index `i` has
    * `.position == i`.
    */
  val allCondCodes: Seq[CondCode.E] =
    (0 until 16).map(i => CondCode.elements(i))

  /** The four reserved-v0.5 opcode slots. */
  val reservedOpcodes: Seq[Opcode.E] = Seq(
    Opcode.waitAddressed,
    Opcode.mismatchClear,
    Opcode.flagClear,
    Opcode.captureRun
  )

  // --------------------------------------------------------------
  // EMIT_BIT
  // --------------------------------------------------------------

  println("--- InstructionSim: EMIT_BIT round-trip ---")

  // 4 symbols x 8 flag combos = 32 distinct instructions; cheap to exhaust.
  for {
    tx <- allTxSymbols
    (expect, mask, capture) <- allFlagTriples
  } {
    val insn = EmitBit(tx, expect, mask, capture)
    val word = roundTrip(insn)
    assertOpcode(word, Opcode.emitBit, "EMIT_BIT")
    assert(
      field(word, 11, 10) == tx.position,
      f"EMIT_BIT tx_symbol field mismatch for $tx in 0x$word%04X"
    )
    assertReservedZero(word, 9, 3, "EMIT_BIT")
    assertFlagTriple(word, expect, mask, capture, "EMIT_BIT")
  }
  // Golden: EMIT_BIT(dominant, expect=0, mask=0, capture=0) = 0x1000.
  assert(
    encode(EmitBit(TxSymbol.dominant, false, false, false)) == 0x1000,
    "EMIT_BIT minimal golden mismatch"
  )
  // Golden: EMIT_BIT(recessive, expect=1, mask=1, capture=1) =
  //   opcode(0x1)<<12 | tx(0b01)<<10 | 0b111 = 0x1407.
  assert(
    encode(EmitBit(TxSymbol.recessive, true, true, true)) == 0x1407,
    "EMIT_BIT full golden mismatch"
  )
  println(s"  EMIT_BIT: ${allTxSymbols.size * allFlagTriples.size} round-trips OK")

  // --------------------------------------------------------------
  // EMIT_QUARTER
  // --------------------------------------------------------------

  println("--- InstructionSim: EMIT_QUARTER round-trip ---")

  // 4 sda x 4 scl x 8 flag-triples = 128 distinct instructions.
  for {
    sda <- allTxSymbols
    scl <- allTxSymbols
    (expect, mask, capture) <- allFlagTriples
  } {
    val insn = EmitQuarter(sda, scl, expect, mask, capture)
    val word = roundTrip(insn)
    assertOpcode(word, Opcode.emitQuarter, "EMIT_QUARTER")
    assert(
      field(word, 11, 10) == sda.position,
      f"EMIT_QUARTER sda_symbol field mismatch for $sda in 0x$word%04X"
    )
    assert(
      field(word, 9, 8) == scl.position,
      f"EMIT_QUARTER scl_symbol field mismatch for $scl in 0x$word%04X"
    )
    assertReservedZero(word, 7, 3, "EMIT_QUARTER")
    assertFlagTriple(word, expect, mask, capture, "EMIT_QUARTER")
  }
  // Golden: EMIT_QUARTER(dominant, dominant, 0,0,0) = 0x2000.
  assert(
    encode(EmitQuarter(
      TxSymbol.dominant, TxSymbol.dominant, false, false, false
    )) == 0x2000,
    "EMIT_QUARTER minimal golden mismatch"
  )
  // Golden: EMIT_QUARTER(recessive, hiz, 1,1,1) =
  //   opcode(0x2)<<12 | sda(0b01)<<10 | scl(0b10)<<8 | 0b111 = 0x2607.
  assert(
    encode(EmitQuarter(
      TxSymbol.recessive, TxSymbol.hiz, true, true, true
    )) == 0x2607,
    "EMIT_QUARTER full golden mismatch"
  )
  println(s"  EMIT_QUARTER: ${allTxSymbols.size * allTxSymbols.size * allFlagTriples.size} round-trips OK")

  // --------------------------------------------------------------
  // STRETCH_SCL
  // --------------------------------------------------------------

  println("--- InstructionSim: STRETCH_SCL round-trip ---")

  // Full 12-bit operand space, 0..4095 (4096 round-trips, cheap).
  for (n <- 0 until (1 << 12)) {
    val insn = StretchScl(n)
    val word = roundTrip(insn)
    assertOpcode(word, Opcode.stretchScl, "STRETCH_SCL")
    assert(
      field(word, 11, 0) == n,
      f"STRETCH_SCL n_quarters field mismatch (n=$n, word=0x$word%04X)"
    )
  }
  assert(encode(StretchScl(0)) == 0x3000, "STRETCH_SCL(0) golden mismatch")
  assert(encode(StretchScl(0xfff)) == 0x3fff, "STRETCH_SCL(0xfff) golden mismatch")
  // Reject overflow.
  try {
    encode(StretchScl(4096))
    sys.error("STRETCH_SCL(4096) should have thrown")
  } catch { case _: IllegalArgumentException => () }
  println("  STRETCH_SCL: 4096 round-trips + overflow-reject OK")

  // --------------------------------------------------------------
  // WAIT_ON
  // --------------------------------------------------------------

  println("--- InstructionSim: WAIT_ON round-trip ---")

  // All 16 cond codes (in-use + reserved) x sample timeouts.
  // Timeouts: 0 (= forever), 1, 0x7f, 0x80, 0xfe, 0xff.
  val waitTimeouts = Seq(0, 1, 0x7f, 0x80, 0xfe, 0xff)
  for {
    cond <- allCondCodes
    t <- waitTimeouts
  } {
    val insn = WaitOn(cond, t)
    val word = roundTrip(insn)
    assertOpcode(word, Opcode.waitOn, "WAIT_ON")
    assert(
      field(word, 11, 8) == cond.position,
      f"WAIT_ON cond_code field mismatch ($cond, word=0x$word%04X)"
    )
    assert(
      field(word, 7, 0) == t,
      f"WAIT_ON timeout field mismatch (t=$t, word=0x$word%04X)"
    )
  }
  // Codes 10..15 round-trip but should be flagged not-v0 by the host.
  for (cond <- allCondCodes if !CondCode.isV0(cond)) {
    val insn = WaitOn(cond, 0)
    roundTrip(insn) // must not throw
  }
  // Golden: WAIT_ON(always, 0) = 0x4000.
  assert(encode(WaitOn(CondCode.always, 0)) == 0x4000, "WAIT_ON golden mismatch")
  // Reject timeout overflow.
  try {
    encode(WaitOn(CondCode.always, 256))
    sys.error("WAIT_ON(always, 256) should have thrown")
  } catch { case _: IllegalArgumentException => () }
  println(s"  WAIT_ON: ${allCondCodes.size * waitTimeouts.size} round-trips + timeout-reject OK")

  // --------------------------------------------------------------
  // BRANCH_ON
  // --------------------------------------------------------------

  println("--- InstructionSim: BRANCH_ON round-trip ---")

  // All 16 cond codes x full signed-8-bit offset range (-128..127).
  for {
    cond <- allCondCodes
    off <- -128 to 127
  } {
    val insn = BranchOn(cond, off)
    val word = roundTrip(insn)
    assertOpcode(word, Opcode.branchOn, "BRANCH_ON")
    assert(
      field(word, 11, 8) == cond.position,
      f"BRANCH_ON cond_code field mismatch ($cond, word=0x$word%04X)"
    )
    // Low byte should be two's-complement of off & 0xff.
    assert(
      field(word, 7, 0) == (off & 0xff),
      f"BRANCH_ON offset field mismatch (off=$off, word=0x$word%04X)"
    )
  }
  // Golden: BRANCH_ON(always, 0) = 0x5000.
  assert(encode(BranchOn(CondCode.always, 0)) == 0x5000, "BRANCH_ON golden mismatch")
  // Golden: BRANCH_ON(always, -1) = 0x50ff (two's-complement low byte).
  assert(
    encode(BranchOn(CondCode.always, -1)) == 0x50ff,
    "BRANCH_ON(-1) two's-complement encoding mismatch"
  )
  // Reject out-of-range offsets.
  try {
    encode(BranchOn(CondCode.always, 128))
    sys.error("BRANCH_ON(always, 128) should have thrown")
  } catch { case _: IllegalArgumentException => () }
  try {
    encode(BranchOn(CondCode.always, -129))
    sys.error("BRANCH_ON(always, -129) should have thrown")
  } catch { case _: IllegalArgumentException => () }
  println(s"  BRANCH_ON: ${allCondCodes.size * 256} round-trips + range-reject OK")

  // --------------------------------------------------------------
  // JMP
  // --------------------------------------------------------------

  println("--- InstructionSim: JMP round-trip ---")

  for (addr <- 0 until (1 << 12)) {
    val insn = Jmp(addr)
    val word = roundTrip(insn)
    assertOpcode(word, Opcode.jmp, "JMP")
    assert(
      field(word, 11, 0) == addr,
      f"JMP addr field mismatch (addr=$addr, word=0x$word%04X)"
    )
  }
  assert(encode(Jmp(0)) == 0x6000, "JMP(0) golden mismatch")
  assert(encode(Jmp(0xfff)) == 0x6fff, "JMP(0xfff) golden mismatch")
  try {
    encode(Jmp(4096))
    sys.error("JMP(4096) should have thrown")
  } catch { case _: IllegalArgumentException => () }
  println("  JMP: 4096 round-trips + overflow-reject OK")

  // --------------------------------------------------------------
  // SET_BUS_MODE
  // --------------------------------------------------------------

  println("--- InstructionSim: SET_BUS_MODE round-trip ---")

  for (mode <- allBusModes) {
    val insn = SetBusMode(mode)
    val word = roundTrip(insn)
    assertOpcode(word, Opcode.setBusMode, "SET_BUS_MODE")
    assert(
      field(word, 11, 9) == mode.position,
      f"SET_BUS_MODE mode field mismatch ($mode, word=0x$word%04X)"
    )
    assertReservedZero(word, 8, 0, "SET_BUS_MODE")
  }
  // Golden: SET_BUS_MODE(i2c) = 0x7000 (mode wire code 0b000).
  assert(encode(SetBusMode(BusMode.i2c)) == 0x7000, "SET_BUS_MODE(i2c) golden mismatch")
  // Golden: SET_BUS_MODE(hdrDdr) = 0x7000 | (0b111 << 9) = 0x7e00.
  assert(
    encode(SetBusMode(BusMode.hdrDdr)) == 0x7e00,
    "SET_BUS_MODE(hdrDdr) golden mismatch"
  )
  println(s"  SET_BUS_MODE: ${allBusModes.size} round-trips OK")

  // --------------------------------------------------------------
  // LOAD_TIMING
  // --------------------------------------------------------------

  println("--- InstructionSim: LOAD_TIMING round-trip ---")

  // 4 regs x sample of 10-bit divider words (0, 1, 0x1ff, 0x200, 0x3fe, 0x3ff).
  val dividerSamples = Seq(0, 1, 0x1ff, 0x200, 0x3fe, 0x3ff)
  for {
    reg <- 0 until 4
    div <- dividerSamples
  } {
    val insn = LoadTiming(reg, div)
    val word = roundTrip(insn)
    assertOpcode(word, Opcode.loadTiming, "LOAD_TIMING")
    assert(
      field(word, 11, 10) == reg,
      f"LOAD_TIMING reg field mismatch (reg=$reg, word=0x$word%04X)"
    )
    assert(
      field(word, 9, 0) == div,
      f"LOAD_TIMING divider field mismatch (div=$div, word=0x$word%04X)"
    )
  }
  assert(encode(LoadTiming(0, 0)) == 0x8000, "LOAD_TIMING(0,0) golden mismatch")
  assert(
    encode(LoadTiming(3, 0x3ff)) == 0x8fff,
    "LOAD_TIMING(3,0x3ff) golden mismatch"
  )
  try {
    encode(LoadTiming(4, 0))
    sys.error("LOAD_TIMING(4,0) should have thrown")
  } catch { case _: IllegalArgumentException => () }
  try {
    encode(LoadTiming(0, 1024))
    sys.error("LOAD_TIMING(0,1024) should have thrown")
  } catch { case _: IllegalArgumentException => () }
  println(s"  LOAD_TIMING: ${4 * dividerSamples.size} round-trips + range-reject OK")

  // --------------------------------------------------------------
  // MARK
  // --------------------------------------------------------------

  println("--- InstructionSim: MARK round-trip ---")

  for (label <- 0 until 256) {
    val insn = Mark(label)
    val word = roundTrip(insn)
    assertOpcode(word, Opcode.mark, "MARK")
    assert(
      field(word, 11, 4) == label,
      f"MARK label field mismatch (label=$label, word=0x$word%04X)"
    )
    assertReservedZero(word, 3, 0, "MARK")
  }
  assert(encode(Mark(0)) == 0x9000, "MARK(0) golden mismatch")
  assert(encode(Mark(0xff)) == 0x9ff0, "MARK(0xff) golden mismatch")
  try {
    encode(Mark(256))
    sys.error("MARK(256) should have thrown")
  } catch { case _: IllegalArgumentException => () }
  println("  MARK: 256 round-trips + overflow-reject OK")

  // --------------------------------------------------------------
  // SAMPLE_BIT_ON_SCL  (target-role; full opcode shape, no tx_symbol)
  // --------------------------------------------------------------

  println("--- InstructionSim: SAMPLE_BIT_ON_SCL round-trip ---")

  for ((expect, mask, capture) <- allFlagTriples) {
    val insn = SampleBitOnScl(expect, mask, capture)
    val word = roundTrip(insn)
    assertOpcode(word, Opcode.sampleBitOnScl, "SAMPLE_BIT_ON_SCL")
    assertReservedZero(word, 11, 3, "SAMPLE_BIT_ON_SCL")
    assertFlagTriple(word, expect, mask, capture, "SAMPLE_BIT_ON_SCL")
  }
  // Golden: SAMPLE_BIT_ON_SCL(0,0,0) = 0xA000.
  assert(
    encode(SampleBitOnScl(false, false, false)) == 0xa000,
    "SAMPLE_BIT_ON_SCL minimal golden mismatch"
  )
  // Golden: SAMPLE_BIT_ON_SCL(1,1,1) = 0xA007.
  assert(
    encode(SampleBitOnScl(true, true, true)) == 0xa007,
    "SAMPLE_BIT_ON_SCL full golden mismatch"
  )
  println(s"  SAMPLE_BIT_ON_SCL: ${allFlagTriples.size} round-trips OK")

  // --------------------------------------------------------------
  // DRIVE_BIT_ON_SCL  (target-role; mirrors EMIT_BIT shape)
  // --------------------------------------------------------------

  println("--- InstructionSim: DRIVE_BIT_ON_SCL round-trip ---")

  for {
    tx <- allTxSymbols
    (expect, mask, capture) <- allFlagTriples
  } {
    val insn = DriveBitOnScl(tx, expect, mask, capture)
    val word = roundTrip(insn)
    assertOpcode(word, Opcode.driveBitOnScl, "DRIVE_BIT_ON_SCL")
    assert(
      field(word, 11, 10) == tx.position,
      f"DRIVE_BIT_ON_SCL tx_symbol field mismatch for $tx in 0x$word%04X"
    )
    assertReservedZero(word, 9, 3, "DRIVE_BIT_ON_SCL")
    assertFlagTriple(word, expect, mask, capture, "DRIVE_BIT_ON_SCL")
  }
  // Golden: DRIVE_BIT_ON_SCL(dominant, 0,0,0) = 0xB000.
  assert(
    encode(DriveBitOnScl(TxSymbol.dominant, false, false, false)) == 0xb000,
    "DRIVE_BIT_ON_SCL minimal golden mismatch"
  )
  // Golden: DRIVE_BIT_ON_SCL(recessive, 1,1,1) = 0xB407.
  assert(
    encode(DriveBitOnScl(TxSymbol.recessive, true, true, true)) == 0xb407,
    "DRIVE_BIT_ON_SCL full golden mismatch"
  )
  println(s"  DRIVE_BIT_ON_SCL: ${allTxSymbols.size * allFlagTriples.size} round-trips OK")

  // --------------------------------------------------------------
  // HALT
  // --------------------------------------------------------------

  println("--- InstructionSim: HALT round-trip ---")

  for (status <- 0 until 16) {
    val insn = Halt(status)
    val word = roundTrip(insn)
    assertOpcode(word, Opcode.halt, "HALT")
    assert(
      field(word, 11, 8) == status,
      f"HALT status field mismatch in 0x$word%04X (status=$status)"
    )
    assertReservedZero(word, 7, 0, "HALT")
  }
  // HALT(0) = 0x0000 --- the safe-trap-on-zero-memory contract.
  assert(encode(Halt(0)) == 0x0000, "HALT(0) golden mismatch")
  // HALT(0xf) = 0x0f00.
  assert(encode(Halt(0xf)) == 0x0f00, "HALT(0xf) golden mismatch")
  try {
    encode(Halt(16))
    sys.error("HALT(16) should have thrown")
  } catch { case _: IllegalArgumentException => () }
  println("  HALT: 16 round-trips + overflow-reject OK")

  // --------------------------------------------------------------
  // RESERVED v0.5 opcodes (0xC..0xF)
  // --------------------------------------------------------------

  println("--- InstructionSim: ReservedV05 round-trip ---")

  val payloadSamples = Seq(0, 1, 0x7ff, 0x800, 0xffe, 0xfff)
  for {
    op <- reservedOpcodes
    p <- payloadSamples
  } {
    val insn = ReservedV05(op, p)
    val word = roundTrip(insn)
    assertOpcode(word, op, s"ReservedV05($op)")
    assert(
      field(word, 11, 0) == p,
      f"ReservedV05 payload field mismatch (payload=$p, word=0x$word%04X)"
    )
  }
  // Golden: ReservedV05(waitAddressed, 0) = 0xC000.
  assert(
    encode(ReservedV05(Opcode.waitAddressed, 0)) == 0xc000,
    "ReservedV05(waitAddressed, 0) golden mismatch"
  )
  // Golden: ReservedV05(captureRun, 0xfff) = 0xFFFF.
  assert(
    encode(ReservedV05(Opcode.captureRun, 0xfff)) == 0xffff,
    "ReservedV05(captureRun, 0xfff) golden mismatch"
  )
  // ReservedV05 constructor must reject v0 opcodes.
  try {
    ReservedV05(Opcode.emitBit, 0)
    sys.error("ReservedV05(emitBit, ...) should have thrown")
  } catch { case _: IllegalArgumentException => () }
  // ReservedV05 constructor must reject payload overflow.
  try {
    ReservedV05(Opcode.waitAddressed, 0x1000)
    sys.error("ReservedV05 with 0x1000 payload should have thrown")
  } catch { case _: IllegalArgumentException => () }
  println(s"  ReservedV05: ${reservedOpcodes.size * payloadSamples.size} round-trips + constructor-reject OK")

  // --------------------------------------------------------------
  // Decoder must produce a ReservedV05 for any word whose opcode
  // field is 0xC..0xF, regardless of where the payload bits sit.
  // --------------------------------------------------------------

  println("--- InstructionSim: decoder reserved-opcode dispatch ---")

  for {
    op <- reservedOpcodes
    p <- payloadSamples
  } {
    val word = (op.position << OPCODE_LO) | p
    val back = decode(word)
    assert(
      back == ReservedV05(op, p),
      f"decoder reserved-opcode dispatch failed for word 0x$word%04X: got $back"
    )
  }
  println(s"  Decoder reserved dispatch: ${reservedOpcodes.size * payloadSamples.size} cases OK")

  // --------------------------------------------------------------
  // Flag-triple-at-[2:0] invariant on every bearer opcode.
  // Concentrates the AGENTS §3.10 check in one place. For each bearer
  // opcode, encode the three single-flag-set variants and assert
  // exactly bit 2 / 1 / 0 of the word is the only flag-triple bit set.
  // --------------------------------------------------------------

  println("--- InstructionSim: flag-triple [2:0] invariant ---")

  /** For each bearer opcode, returns a builder that produces an
    * `Instruction` with the given flag triple but otherwise minimal
    * (zero) operand values. The minimal operands keep the assertion
    * focused on the flag bits only.
    */
  val bearerBuilders: Seq[(String, (Boolean, Boolean, Boolean) => Instruction)] =
    Seq(
      ("EMIT_BIT", (e, m, c) => EmitBit(TxSymbol.dominant, e, m, c)),
      ("EMIT_QUARTER", (e, m, c) =>
        EmitQuarter(TxSymbol.dominant, TxSymbol.dominant, e, m, c)),
      ("SAMPLE_BIT_ON_SCL", (e, m, c) => SampleBitOnScl(e, m, c)),
      ("DRIVE_BIT_ON_SCL", (e, m, c) =>
        DriveBitOnScl(TxSymbol.dominant, e, m, c))
    )

  for ((label, build) <- bearerBuilders) {
    // expect-only: bit 2 set, bits 1 and 0 clear.
    val wE = encode(build(true, false, false))
    assert(
      ((wE >> EXPECT_BIT) & 1) == 1 && ((wE >> MASK_BIT) & 1) == 0 &&
        ((wE >> CAPTURE_BIT) & 1) == 0,
      f"$label expect-only encoding has wrong flag bits: 0x$wE%04X"
    )
    // mask-only: bit 1 set, bits 2 and 0 clear.
    val wM = encode(build(false, true, false))
    assert(
      ((wM >> MASK_BIT) & 1) == 1 && ((wM >> EXPECT_BIT) & 1) == 0 &&
        ((wM >> CAPTURE_BIT) & 1) == 0,
      f"$label mask-only encoding has wrong flag bits: 0x$wM%04X"
    )
    // capture-only: bit 0 set, bits 2 and 1 clear.
    val wC = encode(build(false, false, true))
    assert(
      ((wC >> CAPTURE_BIT) & 1) == 1 && ((wC >> EXPECT_BIT) & 1) == 0 &&
        ((wC >> MASK_BIT) & 1) == 0,
      f"$label capture-only encoding has wrong flag bits: 0x$wC%04X"
    )
  }
  println(s"  Flag triple at [2:0] on ${bearerBuilders.size} bearer opcodes OK")

  // --------------------------------------------------------------
  // Opcode coverage sanity --- the round-trip suite touches every
  // case in the `Instruction` ADT. If a new opcode lands without a
  // sim block, the encode/decode match in this file would still be
  // exhaustive at compile time but coverage would silently drop.
  // This counter is a coarse smoke check.
  // --------------------------------------------------------------

  println("InstructionSim: full-ISA round-trip + golden encodings OK")
}
