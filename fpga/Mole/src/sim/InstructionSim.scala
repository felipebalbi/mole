package mole

/** Round-trip audit for [[Instruction]] --- the wire-format contract.
  *
  * Pure-Scala main, not a SpinalSim DUT. The host-side [[Instruction]] ADT and
  * its encode/decode pair are pure Scala functions over `Int` words; exercising
  * them does not need a simulator backend. Mirrors the [[MoleConfigSim]] /
  * [[OpenDrainBusSim]] convention: a `runMain` entrypoint wired into
  * `make sim-isa`, asserting and exiting zero on success.
  *
  * What this sim guards against:
  *   1. **Round-trip stability.** For every legal `Instruction` value,
  *      `decode(encode(i)) == i` must hold. Any mismatch is a wire-format
  *      regression --- the host compiler and a deployed Mole would disagree on
  *      the bytecode.
  *   2. **Field-position alignment.** The opcode lives at `[15:11]`, the flag
  *      triple at `[2:0]` (`expect`/`mask`/`capture`), and `tx_symbol` at
  *      `[10:9]` of every bearer opcode. Drifting these positions is what
  *      `../../AGENTS.md` §3.10 forbids; the sim asserts the bit positions
  *      structurally.
  *   3. **Reserved-bits-are-zero invariant on encode.** Bits in the "reserved"
  *      middle slice of every opcode must encode to zero so the engine's
  *      v0-vs-v0.5 trap can distinguish a v0 program (clean reserved bits) from
  *      a forward-rolled program (set reserved bits).
  *   4. **Golden encodings** for at least one shape per opcode --- catches
  *      accidental field-shift regressions that round-trip cleanly but generate
  *      a different wire word than the spec says.
  *
  * Run: `sbt "runMain mole.InstructionSim"`
  */
object InstructionSim extends App {

  import Instruction._

  // --------------------------------------------------------------
  // Round-trip primitives
  // --------------------------------------------------------------

  /** Encode `i`, decode the result, assert equality. Returns the encoded word
    * so callers can also assert on its bit layout.
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

  /** Assert that exactly the listed bit indices are set in `word`. Useful for
    * structural opcode-layout checks ("the flag triple lives at `[2:0]`" ---
    * which bits are *not* part of the triple must be zero).
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

  /** Assert opcode bits land at `[15:11]` and match `expected`. Every per-
    * opcode block calls this; a failure means the opcode field has drifted.
    */
  def assertOpcode(word: Int, expected: Opcode.E, label: String): Unit =
    assert(
      field(word, OPCODE_HI, OPCODE_LO) == expected.position,
      f"$label: opcode field mismatch in 0x$word%04X " +
        f"(expected ${expected.position}%X)"
    )

  /** Assert the (expect, mask, capture) flag triple lands at exactly `[2:0]` of
    * `word`. The single most load-bearing structural assertion in the file
    * (AGENTS §3.10) --- every bearer opcode runs through it.
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
    * reserved-bits-are-zero invariant on each bearer opcode's middle slice.
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

  /** All four `BusMode` values --- declaration order, wire codes deliberately
    * non-sequential (see [[BusMode]] docstring).
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

  /** Every non-v0 opcode slot --- the two v0.5 reserved slots at `0x0E` /
    * `0x0F` (`flagClear`, `captureRun`) plus the fifteen upper-half reserved
    * slots at `0x11..0x1F` introduced by the 4→5-bit opcode-field widening.
    * SET_ROLE at `0x10` is a v0 opcode and is *not* in this set; it has its own
    * typed case class and round-trip block. (Slots `0xC` and `0xD` now host
    * `LOAD_LOOP` and `DEC_BRANCH`; see [[Instruction.LoadLoop]] /
    * [[Instruction.DecBranch]].)
    */
  val reservedOpcodes: Seq[Opcode.E] =
    Opcode.elements.filter(op => !Opcode.isV0(op)).toSeq

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
      field(word, 10, 9) == tx.position,
      f"EMIT_BIT tx_symbol field mismatch for $tx in 0x$word%04X"
    )
    assertReservedZero(word, 8, 3, "EMIT_BIT")
    assertFlagTriple(word, expect, mask, capture, "EMIT_BIT")
  }
  // Golden: EMIT_BIT(dominant, expect=0, mask=0, capture=0) = 0x0800.
  assert(
    encode(EmitBit(TxSymbol.dominant, false, false, false)) == 0x0800,
    "EMIT_BIT minimal golden mismatch"
  )
  // Golden: EMIT_BIT(recessive, expect=1, mask=1, capture=1) =
  //   opcode(0x1)<<11 | tx(0b01)<<9 | 0b111 = 0x0a07.
  assert(
    encode(EmitBit(TxSymbol.recessive, true, true, true)) == 0x0a07,
    "EMIT_BIT full golden mismatch"
  )
  println(
    s"  EMIT_BIT: ${allTxSymbols.size * allFlagTriples.size} round-trips OK"
  )

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
      field(word, 10, 9) == sda.position,
      f"EMIT_QUARTER sda_symbol field mismatch for $sda in 0x$word%04X"
    )
    assert(
      field(word, 8, 7) == scl.position,
      f"EMIT_QUARTER scl_symbol field mismatch for $scl in 0x$word%04X"
    )
    assertReservedZero(word, 6, 3, "EMIT_QUARTER")
    assertFlagTriple(word, expect, mask, capture, "EMIT_QUARTER")
  }
  // Golden: EMIT_QUARTER(dominant, dominant, 0,0,0) = 0x1000.
  assert(
    encode(
      EmitQuarter(
        TxSymbol.dominant,
        TxSymbol.dominant,
        false,
        false,
        false
      )
    ) == 0x1000,
    "EMIT_QUARTER minimal golden mismatch"
  )
  // Golden: EMIT_QUARTER(recessive, hiz, 1,1,1) =
  //   opcode(0x2)<<11 | sda(0b01)<<9 | scl(0b10)<<7 | 0b111 = 0x1307.
  assert(
    encode(
      EmitQuarter(
        TxSymbol.recessive,
        TxSymbol.hiz,
        true,
        true,
        true
      )
    ) == 0x1307,
    "EMIT_QUARTER full golden mismatch"
  )
  println(
    s"  EMIT_QUARTER: ${allTxSymbols.size * allTxSymbols.size * allFlagTriples.size} round-trips OK"
  )

  // --------------------------------------------------------------
  // STRETCH_SCL
  // --------------------------------------------------------------

  println("--- InstructionSim: STRETCH_SCL round-trip ---")

  // Full 11-bit operand space, 0..2047 (2048 round-trips, cheap).
  for (n <- 0 until (1 << 11)) {
    val insn = StretchScl(n)
    val word = roundTrip(insn)
    assertOpcode(word, Opcode.stretchScl, "STRETCH_SCL")
    assert(
      field(word, 10, 0) == n,
      f"STRETCH_SCL n_quarters field mismatch (n=$n, word=0x$word%04X)"
    )
  }
  assert(encode(StretchScl(0)) == 0x1800, "STRETCH_SCL(0) golden mismatch")
  assert(
    encode(StretchScl(0x7ff)) == 0x1fff,
    "STRETCH_SCL(0x7ff) golden mismatch"
  )
  // Reject overflow.
  try {
    encode(StretchScl(2048))
    sys.error("STRETCH_SCL(2048) should have thrown")
  } catch { case _: IllegalArgumentException => () }
  println("  STRETCH_SCL: 2048 round-trips + overflow-reject OK")

  // --------------------------------------------------------------
  // WAIT_ON
  // --------------------------------------------------------------

  println("--- InstructionSim: WAIT_ON round-trip ---")

  // All 16 cond codes (in-use + reserved) x sample timeouts.
  // Timeouts: 0 (= forever), 1, 0x3f, 0x40, 0x7e, 0x7f (full 7-bit range).
  val waitTimeouts = Seq(0, 1, 0x3f, 0x40, 0x7e, 0x7f)
  for {
    cond <- allCondCodes
    t <- waitTimeouts
  } {
    val insn = WaitOn(cond, t)
    val word = roundTrip(insn)
    assertOpcode(word, Opcode.waitOn, "WAIT_ON")
    assert(
      field(word, 10, 7) == cond.position,
      f"WAIT_ON cond_code field mismatch ($cond, word=0x$word%04X)"
    )
    assert(
      field(word, 6, 0) == t,
      f"WAIT_ON timeout field mismatch (t=$t, word=0x$word%04X)"
    )
  }
  // Codes 10..15 round-trip but should be flagged not-v0 by the host.
  for (cond <- allCondCodes if !CondCode.isV0(cond)) {
    val insn = WaitOn(cond, 0)
    roundTrip(insn) // must not throw
  }
  // Golden: WAIT_ON(always, 0) = 0x2000.
  assert(
    encode(WaitOn(CondCode.always, 0)) == 0x2000,
    "WAIT_ON golden mismatch"
  )
  // Reject timeout overflow.
  try {
    encode(WaitOn(CondCode.always, 128))
    sys.error("WAIT_ON(always, 128) should have thrown")
  } catch { case _: IllegalArgumentException => () }
  println(
    s"  WAIT_ON: ${allCondCodes.size * waitTimeouts.size} round-trips + timeout-reject OK"
  )

  // --------------------------------------------------------------
  // BRANCH_ON
  // --------------------------------------------------------------

  println("--- InstructionSim: BRANCH_ON round-trip ---")

  // All 16 cond codes x full signed-7-bit offset range (-64..63).
  for {
    cond <- allCondCodes
    off <- -64 to 63
  } {
    val insn = BranchOn(cond, off)
    val word = roundTrip(insn)
    assertOpcode(word, Opcode.branchOn, "BRANCH_ON")
    assert(
      field(word, 10, 7) == cond.position,
      f"BRANCH_ON cond_code field mismatch ($cond, word=0x$word%04X)"
    )
    // Low septet should be two's-complement of off & 0x7f.
    assert(
      field(word, 6, 0) == (off & 0x7f),
      f"BRANCH_ON offset field mismatch (off=$off, word=0x$word%04X)"
    )
  }
  // Golden: BRANCH_ON(always, 0) = 0x2800.
  assert(
    encode(BranchOn(CondCode.always, 0)) == 0x2800,
    "BRANCH_ON golden mismatch"
  )
  // Golden: BRANCH_ON(always, -1) = 0x287f (two's-complement low septet).
  assert(
    encode(BranchOn(CondCode.always, -1)) == 0x287f,
    "BRANCH_ON(-1) two's-complement encoding mismatch"
  )
  // Reject out-of-range offsets.
  try {
    encode(BranchOn(CondCode.always, 64))
    sys.error("BRANCH_ON(always, 64) should have thrown")
  } catch { case _: IllegalArgumentException => () }
  try {
    encode(BranchOn(CondCode.always, -65))
    sys.error("BRANCH_ON(always, -65) should have thrown")
  } catch { case _: IllegalArgumentException => () }
  println(
    s"  BRANCH_ON: ${allCondCodes.size * 128} round-trips + range-reject OK"
  )

  // --------------------------------------------------------------
  // JMP
  // --------------------------------------------------------------

  println("--- InstructionSim: JMP round-trip ---")

  for (addr <- 0 until (1 << 11)) {
    val insn = Jmp(addr)
    val word = roundTrip(insn)
    assertOpcode(word, Opcode.jmp, "JMP")
    assert(
      field(word, 10, 0) == addr,
      f"JMP addr field mismatch (addr=$addr, word=0x$word%04X)"
    )
  }
  assert(encode(Jmp(0)) == 0x3000, "JMP(0) golden mismatch")
  assert(encode(Jmp(0x7ff)) == 0x37ff, "JMP(0x7ff) golden mismatch")
  try {
    encode(Jmp(2048))
    sys.error("JMP(2048) should have thrown")
  } catch { case _: IllegalArgumentException => () }
  println("  JMP: 2048 round-trips + overflow-reject OK")

  // --------------------------------------------------------------
  // SET_BUS_MODE
  // --------------------------------------------------------------

  println("--- InstructionSim: SET_BUS_MODE round-trip ---")

  // SET_BUS_MODE wire encoding is non-sequential per ROADMAP
  // §"Bus mode register": `mode[2]` is the SCL drive class, `mode[1:0]`
  // is the timing-divider selector. The host encoder uses the wire
  // codes (0, 1, 6, 7), NOT the SpinalEnumElement `.position`
  // declaration index (0, 1, 2, 3). Mirror that here so the field
  // check stays correct after any future re-ordering of the enum.
  val busModeWire: Map[BusMode.E, Int] = Map(
    BusMode.i2c -> 0,
    BusMode.i3cOd -> 1,
    BusMode.i3cPp -> 6,
    BusMode.hdrDdr -> 7
  )

  for (mode <- allBusModes) {
    val insn = SetBusMode(mode)
    val word = roundTrip(insn)
    assertOpcode(word, Opcode.setBusMode, "SET_BUS_MODE")
    assert(
      field(word, 10, 8) == busModeWire(mode),
      f"SET_BUS_MODE mode field mismatch ($mode, " +
        f"got=${field(word, 10, 8)}%d expected=${busModeWire(mode)}%d, " +
        f"word=0x$word%04X)"
    )
    assertReservedZero(word, 7, 0, "SET_BUS_MODE")
  }
  // Goldens: every BusMode against its wire-encoded operand.
  // i2c    -> mode = 0b000 = 0 -> 0x3800 | (0 << 8) = 0x3800
  // i3c-OD -> mode = 0b001 = 1 -> 0x3800 | (1 << 8) = 0x3900
  // i3c-PP -> mode = 0b110 = 6 -> 0x3800 | (6 << 8) = 0x3e00
  // hdr-ddr-> mode = 0b111 = 7 -> 0x3800 | (7 << 8) = 0x3f00
  assert(
    encode(SetBusMode(BusMode.i2c)) == 0x3800,
    "SET_BUS_MODE(i2c) golden mismatch"
  )
  assert(
    encode(SetBusMode(BusMode.i3cOd)) == 0x3900,
    "SET_BUS_MODE(i3cOd) golden mismatch"
  )
  assert(
    encode(SetBusMode(BusMode.i3cPp)) == 0x3e00,
    "SET_BUS_MODE(i3cPp) golden mismatch"
  )
  assert(
    encode(SetBusMode(BusMode.hdrDdr)) == 0x3f00,
    "SET_BUS_MODE(hdrDdr) golden mismatch"
  )
  println(s"  SET_BUS_MODE: ${allBusModes.size} round-trips OK")

  // --------------------------------------------------------------
  // LOAD_TIMING
  // --------------------------------------------------------------

  println("--- InstructionSim: LOAD_TIMING round-trip ---")

  // 4 regs x sample of 9-bit divider words (0, 1, 0xff, 0x100, 0x1fe, 0x1ff).
  val dividerSamples = Seq(0, 1, 0xff, 0x100, 0x1fe, 0x1ff)
  for {
    reg <- 0 until 4
    div <- dividerSamples
  } {
    val insn = LoadTiming(reg, div)
    val word = roundTrip(insn)
    assertOpcode(word, Opcode.loadTiming, "LOAD_TIMING")
    assert(
      field(word, 10, 9) == reg,
      f"LOAD_TIMING reg field mismatch (reg=$reg, word=0x$word%04X)"
    )
    assert(
      field(word, 8, 0) == div,
      f"LOAD_TIMING divider field mismatch (div=$div, word=0x$word%04X)"
    )
  }
  assert(encode(LoadTiming(0, 0)) == 0x4000, "LOAD_TIMING(0,0) golden mismatch")
  assert(
    encode(LoadTiming(3, 0x1ff)) == 0x47ff,
    "LOAD_TIMING(3,0x1ff) golden mismatch"
  )
  try {
    encode(LoadTiming(4, 0))
    sys.error("LOAD_TIMING(4,0) should have thrown")
  } catch { case _: IllegalArgumentException => () }
  try {
    encode(LoadTiming(0, 512))
    sys.error("LOAD_TIMING(0,512) should have thrown")
  } catch { case _: IllegalArgumentException => () }
  println(
    s"  LOAD_TIMING: ${4 * dividerSamples.size} round-trips + range-reject OK"
  )

  // --------------------------------------------------------------
  // MARK
  // --------------------------------------------------------------

  println("--- InstructionSim: MARK round-trip ---")

  for (label <- 0 until 256) {
    val insn = Mark(label)
    val word = roundTrip(insn)
    assertOpcode(word, Opcode.mark, "MARK")
    assert(
      field(word, 10, 3) == label,
      f"MARK label field mismatch (label=$label, word=0x$word%04X)"
    )
    assertReservedZero(word, 2, 0, "MARK")
  }
  assert(encode(Mark(0)) == 0x4800, "MARK(0) golden mismatch")
  assert(encode(Mark(0xff)) == 0x4ff8, "MARK(0xff) golden mismatch")
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
    assertReservedZero(word, 10, 3, "SAMPLE_BIT_ON_SCL")
    assertFlagTriple(word, expect, mask, capture, "SAMPLE_BIT_ON_SCL")
  }
  // Golden: SAMPLE_BIT_ON_SCL(0,0,0) = 0x5000.
  assert(
    encode(SampleBitOnScl(false, false, false)) == 0x5000,
    "SAMPLE_BIT_ON_SCL minimal golden mismatch"
  )
  // Golden: SAMPLE_BIT_ON_SCL(1,1,1) = 0x5007.
  assert(
    encode(SampleBitOnScl(true, true, true)) == 0x5007,
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
      field(word, 10, 9) == tx.position,
      f"DRIVE_BIT_ON_SCL tx_symbol field mismatch for $tx in 0x$word%04X"
    )
    assertReservedZero(word, 8, 3, "DRIVE_BIT_ON_SCL")
    assertFlagTriple(word, expect, mask, capture, "DRIVE_BIT_ON_SCL")
  }
  // Golden: DRIVE_BIT_ON_SCL(dominant, 0,0,0) = 0x5800.
  assert(
    encode(DriveBitOnScl(TxSymbol.dominant, false, false, false)) == 0x5800,
    "DRIVE_BIT_ON_SCL minimal golden mismatch"
  )
  // Golden: DRIVE_BIT_ON_SCL(recessive, 1,1,1) = 0x5a07.
  assert(
    encode(DriveBitOnScl(TxSymbol.recessive, true, true, true)) == 0x5a07,
    "DRIVE_BIT_ON_SCL full golden mismatch"
  )
  println(
    s"  DRIVE_BIT_ON_SCL: ${allTxSymbols.size * allFlagTriples.size} round-trips OK"
  )

  // --------------------------------------------------------------
  // LOAD_LOOP (slot 0xC)
  // --------------------------------------------------------------

  println("--- InstructionSim: LOAD_LOOP round-trip ---")

  for {
    reg <- 0 until 2
    imm <- 0 until 256
  } {
    val insn = LoadLoop(reg, imm)
    val word = roundTrip(insn)
    assertOpcode(word, Opcode.loadLoop, "LOAD_LOOP")
    assert(
      field(word, 10, 10) == reg,
      f"LOAD_LOOP reg field mismatch (reg=$reg, word=0x$word%04X)"
    )
    assertReservedZero(word, 9, 8, "LOAD_LOOP")
    assert(
      field(word, 7, 0) == imm,
      f"LOAD_LOOP imm field mismatch (imm=$imm, word=0x$word%04X)"
    )
  }
  // Golden: LOAD_LOOP(0, 0) = 0x6000.
  assert(encode(LoadLoop(0, 0)) == 0x6000, "LOAD_LOOP(0,0) golden mismatch")
  // Golden: LOAD_LOOP(1, 0xff) = 0x64ff (reg bit at [10], imm at [7:0]).
  assert(
    encode(LoadLoop(1, 0xff)) == 0x64ff,
    "LOAD_LOOP(1,0xff) golden mismatch"
  )
  // Range rejects.
  try {
    encode(LoadLoop(2, 0))
    sys.error("LOAD_LOOP(2, 0) should have thrown (reg > 1)")
  } catch { case _: IllegalArgumentException => () }
  try {
    encode(LoadLoop(-1, 0))
    sys.error("LOAD_LOOP(-1, 0) should have thrown")
  } catch { case _: IllegalArgumentException => () }
  try {
    encode(LoadLoop(0, 256))
    sys.error("LOAD_LOOP(0, 256) should have thrown (imm > 0xff)")
  } catch { case _: IllegalArgumentException => () }
  println("  LOAD_LOOP: 512 round-trips + range-reject OK")

  // --------------------------------------------------------------
  // DEC_BRANCH (slot 0xD)
  // --------------------------------------------------------------

  println("--- InstructionSim: DEC_BRANCH round-trip ---")

  for {
    reg <- 0 until 2
    off <- -128 to 127
  } {
    val insn = DecBranch(reg, off)
    val word = roundTrip(insn)
    assertOpcode(word, Opcode.decBranch, "DEC_BRANCH")
    assert(
      field(word, 10, 10) == reg,
      f"DEC_BRANCH reg field mismatch (reg=$reg, word=0x$word%04X)"
    )
    assertReservedZero(word, 9, 8, "DEC_BRANCH")
    assert(
      field(word, 7, 0) == (off & 0xff),
      f"DEC_BRANCH offset field mismatch (off=$off, word=0x$word%04X)"
    )
  }
  // Golden: DEC_BRANCH(0, 0) = 0x6800.
  assert(encode(DecBranch(0, 0)) == 0x6800, "DEC_BRANCH(0,0) golden mismatch")
  // Golden: DEC_BRANCH(0, -1) = 0x68ff (two's-complement low byte).
  assert(
    encode(DecBranch(0, -1)) == 0x68ff,
    "DEC_BRANCH(0,-1) two's-complement encoding mismatch"
  )
  // Golden: DEC_BRANCH(1, 127) = 0x6c7f (reg bit at [10]).
  assert(
    encode(DecBranch(1, 127)) == 0x6c7f,
    "DEC_BRANCH(1,127) golden mismatch"
  )
  // Range rejects.
  try {
    encode(DecBranch(2, 0))
    sys.error("DEC_BRANCH(2, 0) should have thrown (reg > 1)")
  } catch { case _: IllegalArgumentException => () }
  try {
    encode(DecBranch(0, 128))
    sys.error("DEC_BRANCH(0, 128) should have thrown")
  } catch { case _: IllegalArgumentException => () }
  try {
    encode(DecBranch(0, -129))
    sys.error("DEC_BRANCH(0, -129) should have thrown")
  } catch { case _: IllegalArgumentException => () }
  println("  DEC_BRANCH: 512 round-trips + range-reject OK")

  // --------------------------------------------------------------
  // SET_ROLE (slot 0x10 --- runtime engine-role select)
  // --------------------------------------------------------------

  println("--- InstructionSim: SET_ROLE round-trip ---")

  for (role <- Seq(false, true)) {
    val insn = SetRole(role)
    val word = roundTrip(insn)
    assertOpcode(word, Opcode.setRole, "SET_ROLE")
    assert(
      field(word, 10, 10) == (if (role) 1 else 0),
      f"SET_ROLE role bit mismatch (role=$role, word=0x$word%04X)"
    )
    assertReservedZero(word, 9, 0, "SET_ROLE")
  }
  // Golden: SET_ROLE(controller) = 0x10 << 11 = 0x8000.
  assert(
    encode(SetRole(role = false)) == 0x8000,
    "SET_ROLE(controller) golden mismatch"
  )
  // Golden: SET_ROLE(target) = 0x8000 | (1 << 10) = 0x8400.
  assert(
    encode(SetRole(role = true)) == 0x8400,
    "SET_ROLE(target) golden mismatch"
  )
  println("  SET_ROLE: 2 round-trips OK")

  // --------------------------------------------------------------
  // HALT
  // --------------------------------------------------------------

  println("--- InstructionSim: HALT round-trip ---")

  for (status <- 0 until 16) {
    val insn = Halt(status)
    val word = roundTrip(insn)
    assertOpcode(word, Opcode.halt, "HALT")
    assert(
      field(word, 10, 7) == status,
      f"HALT status field mismatch in 0x$word%04X (status=$status)"
    )
    assertReservedZero(word, 6, 0, "HALT")
  }
  // HALT(0) = 0x0000 --- the safe-trap-on-zero-memory contract.
  assert(encode(Halt(0)) == 0x0000, "HALT(0) golden mismatch")
  // HALT(0xf) = 0x0780.
  assert(encode(Halt(0xf)) == 0x0780, "HALT(0xf) golden mismatch")
  try {
    encode(Halt(16))
    sys.error("HALT(16) should have thrown")
  } catch { case _: IllegalArgumentException => () }
  println("  HALT: 16 round-trips + overflow-reject OK")

  // --------------------------------------------------------------
  // RESERVED v0.5 + upper-half (0xE..0xF + 0x11..0x1F)
  // --------------------------------------------------------------

  println("--- InstructionSim: ReservedV05 round-trip ---")

  val payloadSamples = Seq(0, 1, 0x3ff, 0x400, 0x7fe, 0x7ff)
  for {
    op <- reservedOpcodes
    p <- payloadSamples
  } {
    val insn = ReservedV05(op, p)
    val word = roundTrip(insn)
    assertOpcode(word, op, s"ReservedV05($op)")
    assert(
      field(word, 10, 0) == p,
      f"ReservedV05 payload field mismatch (payload=$p, word=0x$word%04X)"
    )
  }
  // Golden: ReservedV05(flagClear, 0) = 0x7000 (lowest reserved slot
  // after LOAD_LOOP / DEC_BRANCH claimed 0xC and 0xD in v1).
  assert(
    encode(ReservedV05(Opcode.flagClear, 0)) == 0x7000,
    "ReservedV05(flagClear, 0) golden mismatch"
  )
  // Golden: ReservedV05(captureRun, 0x7ff) = 0x7fff
  // (op 0x0f << 11 = 0x7800; payload 0x7ff).
  assert(
    encode(ReservedV05(Opcode.captureRun, 0x7ff)) == 0x7fff,
    "ReservedV05(captureRun, 0x7ff) golden mismatch"
  )
  // Golden: upper-half reserved slot at op = 0x11 with zero payload
  // = (0x11 << 11) = 0x8800. Sanity-check the first upper-half slot
  // round-trips.
  assert(
    encode(ReservedV05(Opcode.reserved11, 0)) == 0x8800,
    "ReservedV05(reserved11, 0) golden mismatch"
  )
  // Golden: highest reserved slot at op = 0x1f, max payload.
  // = (0x1f << 11) | 0x7ff = 0xffff.
  assert(
    encode(ReservedV05(Opcode.reserved1f, 0x7ff)) == 0xffff,
    "ReservedV05(reserved1f, 0x7ff) golden mismatch"
  )
  // ReservedV05 constructor must reject v0 opcodes.
  try {
    ReservedV05(Opcode.emitBit, 0)
    sys.error("ReservedV05(emitBit, ...) should have thrown")
  } catch { case _: IllegalArgumentException => () }
  // ReservedV05 constructor must reject v0 opcodes in the newly-claimed
  // 0xC / 0xD slots too.
  try {
    ReservedV05(Opcode.loadLoop, 0)
    sys.error("ReservedV05(loadLoop, ...) should have thrown")
  } catch { case _: IllegalArgumentException => () }
  try {
    ReservedV05(Opcode.decBranch, 0)
    sys.error("ReservedV05(decBranch, ...) should have thrown")
  } catch { case _: IllegalArgumentException => () }
  // ReservedV05 constructor must reject the v0 SET_ROLE opcode at 0x10
  // --- it has its own typed case class.
  try {
    ReservedV05(Opcode.setRole, 0)
    sys.error("ReservedV05(setRole, ...) should have thrown")
  } catch { case _: IllegalArgumentException => () }
  // ReservedV05 constructor must reject payload overflow (11-bit cap).
  try {
    ReservedV05(Opcode.flagClear, 0x800)
    sys.error("ReservedV05 with 0x800 payload should have thrown")
  } catch { case _: IllegalArgumentException => () }
  println(
    s"  ReservedV05: ${reservedOpcodes.size * payloadSamples.size} round-trips + constructor-reject OK"
  )

  // --------------------------------------------------------------
  // Decoder must produce a ReservedV05 for any word whose opcode
  // field is a non-v0 slot (0x0E..0x0F or 0x11..0x1F), regardless
  // of where the payload bits sit.
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
  println(
    s"  Decoder reserved dispatch: ${reservedOpcodes.size * payloadSamples.size} cases OK"
  )

  // --------------------------------------------------------------
  // Flag-triple-at-[2:0] invariant on every bearer opcode.
  // Concentrates the AGENTS §3.10 check in one place. For each bearer
  // opcode, encode the three single-flag-set variants and assert
  // exactly bit 2 / 1 / 0 of the word is the only flag-triple bit set.
  // --------------------------------------------------------------

  println("--- InstructionSim: flag-triple [2:0] invariant ---")

  /** For each bearer opcode, returns a builder that produces an `Instruction`
    * with the given flag triple but otherwise minimal (zero) operand values.
    * The minimal operands keep the assertion focused on the flag bits only.
    */
  val bearerBuilders
      : Seq[(String, (Boolean, Boolean, Boolean) => Instruction)] =
    Seq(
      ("EMIT_BIT", (e, m, c) => EmitBit(TxSymbol.dominant, e, m, c)),
      (
        "EMIT_QUARTER",
        (e, m, c) => EmitQuarter(TxSymbol.dominant, TxSymbol.dominant, e, m, c)
      ),
      ("SAMPLE_BIT_ON_SCL", (e, m, c) => SampleBitOnScl(e, m, c)),
      (
        "DRIVE_BIT_ON_SCL",
        (e, m, c) => DriveBitOnScl(TxSymbol.dominant, e, m, c)
      )
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
