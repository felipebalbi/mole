// SPDX-FileCopyrightText: 2026 Felipe Balbi
// SPDX-License-Identifier: CERN-OHL-W-2.0

package mole

/** Round-trip audit and golden-encoding verification for the v0.2
  * [[Instruction]] ISA.
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
  *      regression.
  *   2. **Golden encodings (spec §12.4).** For each live opcode, one canonical
  *      example is asserted against the hex value stated in the spec. This
  *      catches accidental field-shift regressions that round-trip correctly
  *      but produce the wrong wire word.
  *   3. **Flag-triple-at-[2:0] invariant.** Asserted structurally on every WIRE
  *      bearer opcode (AGENTS §3.10).
  *   4. **Range / reject coverage.** Out-of-range constructor values must throw
  *      [[IllegalArgumentException]].
  *   5. **Reserved slots** round-trip via [[Instruction.ReservedV05]].
  *
  * Run: `sbt "runMain mole.InstructionSim"`
  */
object InstructionSim extends App {

  import Instruction._

  // ------------------------------------------------------------------
  // Round-trip primitives
  // ------------------------------------------------------------------

  /** Encode `i`, decode the result, assert equality. Returns the encoded word
    * (as a bit pattern; may be negative for DATA-group words) so callers can
    * also assert on its bit layout.
    */
  def roundTrip(i: Instruction): Int = {
    val word = encode(i)
    val back = decode(word)
    assert(
      back == i,
      f"round-trip failed: encode($i) = 0x${word.toLong & 0xffffffffL}%08X; " +
        f"decode = $back"
    )
    word
  }

  /** Extract a contiguous bit field from `word`. `hi` is inclusive, `lo` is
    * inclusive (matches the `[hi:lo]` notation used in the spec). Uses unsigned
    * right-shift to handle DATA-group words (bit 31 set).
    */
  def field(word: Int, hi: Int, lo: Int): Int =
    (word >>> lo) & ((1 << (hi - lo + 1)) - 1)

  /** Assert the 6-bit opcode field `[31:26]` matches `expected.position`. */
  def assertOpcode(word: Int, expected: Opcode.E, label: String): Unit =
    assert(
      field(word, 31, 26) == expected.position,
      f"$label: opcode field mismatch in 0x${word.toLong & 0xffffffffL}%08X " +
        f"(expected ${expected.position}%X)"
    )

  /** Assert the `(expect, mask, capture)` flag triple is at exactly `[2:0]`.
    * This is the AGENTS §3.10 load-bearing invariant.
    */
  def assertFlagTriple(
      word: Int,
      expect: Boolean,
      mask: Boolean,
      capture: Boolean,
      label: String
  ): Unit = {
    assert(
      ((word >>> EXPECT_BIT) & 1) == (if (expect) 1 else 0),
      f"$label: expect bit wrong (word=0x${word.toLong & 0xffffffffL}%08X)"
    )
    assert(
      ((word >>> MASK_BIT) & 1) == (if (mask) 1 else 0),
      f"$label: mask bit wrong (word=0x${word.toLong & 0xffffffffL}%08X)"
    )
    assert(
      ((word >>> CAPTURE_BIT) & 1) == (if (capture) 1 else 0),
      f"$label: capture bit wrong (word=0x${word.toLong & 0xffffffffL}%08X)"
    )
  }

  /** Assert the slice `[hi:lo]` of `word` is zero. */
  def assertReservedZero(word: Int, hi: Int, lo: Int, label: String): Unit =
    assert(
      field(word, hi, lo) == 0,
      f"$label: reserved [$hi:$lo] non-zero in " +
        f"0x${word.toLong & 0xffffffffL}%08X"
    )

  /** Assert a word equals a specific 32-bit hex constant (compared as bit
    * patterns). The expected value is passed as a `Long` to avoid sign issues.
    */
  def assertGolden(word: Int, expected: Long, label: String): Unit = {
    val wordU = word.toLong & 0xffffffffL
    assert(
      wordU == expected,
      f"$label: golden mismatch: got 0x$wordU%08X, expected 0x$expected%08X"
    )
  }

  /** Iterate (expect, mask, capture) over all 8 combinations. */
  val allFlagTriples: Seq[(Boolean, Boolean, Boolean)] =
    for {
      e <- Seq(false, true)
      m <- Seq(false, true)
      c <- Seq(false, true)
    } yield (e, m, c)

  /** All four TxSymbol values. */
  val allTxSymbols: Seq[TxSymbol.E] =
    Seq(TxSymbol.dominant, TxSymbol.recessive, TxSymbol.hiz, TxSymbol.reserved)

  /** All four live BusMode values (v0.2 sequential encoding). */
  val allBusModes: Seq[BusMode.E] =
    Seq(BusMode.i2c, BusMode.i3cOd, BusMode.i3cPp, BusMode.hdrDdr)

  /** All 16 CondCode values (wire codes 0..15). */
  val allCondCodes: Seq[CondCode.E] =
    (0 until 16).map(i => CondCode.elements(i))

  /** All reserved opcode elements. */
  val reservedOpcodes: Seq[Opcode.E] =
    Opcode.elements.filter(op => !Opcode.isLive(op)).toSeq

  // ------------------------------------------------------------------
  // §5.1  EMIT_BIT_IMM
  // ------------------------------------------------------------------

  println("--- InstructionSim: EMIT_BIT_IMM round-trip ---")

  for {
    tx <- allTxSymbols
    (expect, mask, capture) <- allFlagTriples
  } {
    val insn = EmitBitImm(tx, expect, mask, capture)
    val word = roundTrip(insn)
    assertOpcode(word, Opcode.emitBitImm, "EMIT_BIT_IMM")
    // tx_symbol at [4:3]
    assert(
      field(word, 4, 3) == tx.position,
      f"EMIT_BIT_IMM tx_symbol field mismatch for $tx in " +
        f"0x${word.toLong & 0xffffffffL}%08X"
    )
    assertReservedZero(word, 25, 5, "EMIT_BIT_IMM")
    assertFlagTriple(word, expect, mask, capture, "EMIT_BIT_IMM")
  }
  // Golden §12.4: EMIT_BIT_IMM tx=recessive → 0x0000_0008
  // group=00 sub=0000, tx=0b01 at [4:3] → 0b01 << 3 = 0x08
  assertGolden(
    encode(EmitBitImm(TxSymbol.recessive, false, false, false)),
    0x0000_0008L,
    "EMIT_BIT_IMM tx=recessive golden"
  )
  // Additional golden: tx=dominant (0) → 0x0000_0000
  assertGolden(
    encode(EmitBitImm(TxSymbol.dominant, false, false, false)),
    0x0000_0000L,
    "EMIT_BIT_IMM tx=dominant golden"
  )
  println(
    s"  EMIT_BIT_IMM: ${allTxSymbols.size * allFlagTriples.size} round-trips OK"
  )

  // ------------------------------------------------------------------
  // §5.2  EMIT_BIT_REG
  // ------------------------------------------------------------------

  println("--- InstructionSim: EMIT_BIT_REG round-trip ---")

  for {
    src <- 0 until 8
    (expect, mask, capture) <- allFlagTriples
  } {
    val insn = EmitBitReg(src, expect, mask, capture)
    val word = roundTrip(insn)
    assertOpcode(word, Opcode.emitBitReg, "EMIT_BIT_REG")
    // src at [22:20]
    assert(
      field(word, 22, 20) == src,
      f"EMIT_BIT_REG src field mismatch (src=$src)"
    )
    assertReservedZero(word, 25, 23, "EMIT_BIT_REG [25:23]")
    assertReservedZero(word, 19, 3, "EMIT_BIT_REG [19:3]")
    assertFlagTriple(word, expect, mask, capture, "EMIT_BIT_REG")
  }
  // Golden §12.4: EMIT_BIT_REG src=R2 capture=1 → 0x0420_0001
  assertGolden(
    encode(EmitBitReg(2, false, false, true)),
    0x0420_0001L,
    "EMIT_BIT_REG src=R2 capture=1 golden"
  )
  // Range rejects
  try {
    encode(EmitBitReg(8, false, false, false))
    sys.error("EMIT_BIT_REG(src=8) should have thrown")
  } catch { case _: IllegalArgumentException => () }
  println(
    s"  EMIT_BIT_REG: ${8 * allFlagTriples.size} round-trips + range-reject OK"
  )

  // ------------------------------------------------------------------
  // §5.3  EMIT_QUARTER_IMM
  // ------------------------------------------------------------------

  println("--- InstructionSim: EMIT_QUARTER_IMM round-trip ---")

  for {
    sda <- allTxSymbols
    scl <- allTxSymbols
    (expect, mask, capture) <- allFlagTriples
  } {
    val insn = EmitQuarterImm(sda, scl, expect, mask, capture)
    val word = roundTrip(insn)
    assertOpcode(word, Opcode.emitQuarterImm, "EMIT_QUARTER_IMM")
    // scl at [6:5], sda at [4:3]
    assert(
      field(word, 6, 5) == scl.position,
      f"EMIT_QUARTER_IMM scl field mismatch for $scl"
    )
    assert(
      field(word, 4, 3) == sda.position,
      f"EMIT_QUARTER_IMM sda field mismatch for $sda"
    )
    assertReservedZero(word, 25, 7, "EMIT_QUARTER_IMM")
    assertFlagTriple(word, expect, mask, capture, "EMIT_QUARTER_IMM")
  }
  // Golden §12.4: EMIT_QUARTER_IMM sda=dominant scl=hiz → 0x0800_0040
  // group=00 sub=0010, scl=0b10(hiz) at [6:5]=0x40, sda=0b00(dom) at [4:3]=0
  assertGolden(
    encode(
      EmitQuarterImm(TxSymbol.dominant, TxSymbol.hiz, false, false, false)
    ),
    0x0800_0040L,
    "EMIT_QUARTER_IMM sda=dominant scl=hiz golden"
  )
  println(
    s"  EMIT_QUARTER_IMM: " +
      s"${allTxSymbols.size * allTxSymbols.size * allFlagTriples.size} " +
      s"round-trips OK"
  )

  // ------------------------------------------------------------------
  // §5.4  EMIT_QUARTER_REG
  // ------------------------------------------------------------------

  println("--- InstructionSim: EMIT_QUARTER_REG round-trip ---")

  for {
    src <- 0 until 8
    (expect, mask, capture) <- allFlagTriples
  } {
    val insn = EmitQuarterReg(src, expect, mask, capture)
    val word = roundTrip(insn)
    assertOpcode(word, Opcode.emitQuarterReg, "EMIT_QUARTER_REG")
    assert(field(word, 22, 20) == src, s"EMIT_QUARTER_REG src field mismatch")
    assertReservedZero(word, 25, 23, "EMIT_QUARTER_REG [25:23]")
    assertReservedZero(word, 19, 3, "EMIT_QUARTER_REG [19:3]")
    assertFlagTriple(word, expect, mask, capture, "EMIT_QUARTER_REG")
  }
  // Golden §12.4: EMIT_QUARTER_REG src=R1 → 0x0C10_0000
  assertGolden(
    encode(EmitQuarterReg(1, false, false, false)),
    0x0c10_0000L,
    "EMIT_QUARTER_REG src=R1 golden"
  )
  println(
    s"  EMIT_QUARTER_REG: ${8 * allFlagTriples.size} round-trips OK"
  )

  // ------------------------------------------------------------------
  // §5.5  EMIT_BYTE_REG
  // ------------------------------------------------------------------

  println("--- InstructionSim: EMIT_BYTE_REG round-trip ---")

  for ((expect, mask, capture) <- allFlagTriples) {
    val insn = EmitByteReg(expect, mask, capture)
    val word = roundTrip(insn)
    assertOpcode(word, Opcode.emitByteReg, "EMIT_BYTE_REG")
    assertReservedZero(word, 25, 3, "EMIT_BYTE_REG")
    assertFlagTriple(word, expect, mask, capture, "EMIT_BYTE_REG")
  }
  // Golden §12.4: EMIT_BYTE_REG expect=0 mask=1 capture=1 → 0x1000_0003
  assertGolden(
    encode(EmitByteReg(false, true, true)),
    0x1000_0003L,
    "EMIT_BYTE_REG expect=0 mask=1 capture=1 golden"
  )
  // Additional: EMIT_BYTE_REG (no flags) → 0x1000_0000
  assertGolden(
    encode(EmitByteReg(false, false, false)),
    0x1000_0000L,
    "EMIT_BYTE_REG no-flags golden"
  )
  println(s"  EMIT_BYTE_REG: ${allFlagTriples.size} round-trips OK")

  // ------------------------------------------------------------------
  // §5.5b  EMIT_BYTE_IMM
  // ------------------------------------------------------------------

  println("--- InstructionSim: EMIT_BYTE_IMM round-trip ---")

  // Exhaustive round-trip across all 256 imm values × all flag triples.
  for (imm <- 0 to 0xff; (expect, mask, capture) <- allFlagTriples) {
    val insn = EmitByteImm(imm, expect, mask, capture)
    val word = roundTrip(insn)
    assertOpcode(word, Opcode.emitByteImm, "EMIT_BYTE_IMM")
    assertReservedZero(word, 25, 11, "EMIT_BYTE_IMM")
    // imm field lives at [10:3]
    val immField = (word >>> 3) & 0xff
    assert(
      immField == imm,
      f"EMIT_BYTE_IMM: imm field 0x$immField%02X != expected 0x$imm%02X" +
        f" (word=0x${word.toLong & 0xffffffffL}%08X)"
    )
    assertFlagTriple(word, expect, mask, capture, "EMIT_BYTE_IMM")
  }
  // Golden §12.4: EMIT_BYTE_IMM imm=0x48 (no flags) → 0x2400_0240
  assertGolden(
    encode(EmitByteImm(0x48, false, false, false)),
    0x2400_0240L,
    "EMIT_BYTE_IMM imm=0x48 no-flags golden"
  )
  // Additional: matches Rust encoder unit tests (mole-asm/src/encoder.rs).
  //   EMIT_BYTE_IMM imm=0xAB expect=1 mask=1 capture=0 → 0x2400_055E
  assertGolden(
    encode(EmitByteImm(0xab, true, true, false)),
    0x2400_055eL,
    "EMIT_BYTE_IMM imm=0xAB expect=1 mask=1 golden"
  )
  //   EMIT_BYTE_IMM imm=0xFF all flags → 0x2400_07FF
  assertGolden(
    encode(EmitByteImm(0xff, true, true, true)),
    0x2400_07ffL,
    "EMIT_BYTE_IMM imm=0xFF all-flags golden"
  )
  //   EMIT_BYTE_IMM imm=0x00 no flags → 0x2400_0000
  assertGolden(
    encode(EmitByteImm(0x00, false, false, false)),
    0x2400_0000L,
    "EMIT_BYTE_IMM imm=0x00 no-flags golden"
  )
  println(
    s"  EMIT_BYTE_IMM: ${256 * allFlagTriples.size} round-trips OK"
  )

  // ------------------------------------------------------------------
  // §5.6  SAMPLE_BIT_ON_SCL
  // ------------------------------------------------------------------

  println("--- InstructionSim: SAMPLE_BIT_ON_SCL round-trip ---")

  for ((expect, mask, capture) <- allFlagTriples) {
    val insn = SampleBitOnScl(expect, mask, capture)
    val word = roundTrip(insn)
    assertOpcode(word, Opcode.sampleBitOnScl, "SAMPLE_BIT_ON_SCL")
    // dst=R7=7 at [25:23]; canonical encoder always sets this
    assert(
      field(word, 25, 23) == 7,
      f"SAMPLE_BIT_ON_SCL dst field must be R7 (7) in " +
        f"0x${word.toLong & 0xffffffffL}%08X"
    )
    assertReservedZero(word, 22, 3, "SAMPLE_BIT_ON_SCL")
    assertFlagTriple(word, expect, mask, capture, "SAMPLE_BIT_ON_SCL")
  }
  // Golden §12.4: SAMPLE_BIT_ON_SCL capture=1 → 0x1780_0001
  // dst=R7 at [25:23] → 7<<23=0x0380_0000; opcode prefix=0x1400_0000;
  // flags=0b001=1 → total 0x1780_0001
  assertGolden(
    encode(SampleBitOnScl(false, false, true)),
    0x1780_0001L,
    "SAMPLE_BIT_ON_SCL capture=1 golden"
  )
  println(s"  SAMPLE_BIT_ON_SCL: ${allFlagTriples.size} round-trips OK")

  // ------------------------------------------------------------------
  // §5.7  DRIVE_BIT_ON_SCL
  // ------------------------------------------------------------------

  println("--- InstructionSim: DRIVE_BIT_ON_SCL round-trip ---")

  for {
    tx <- allTxSymbols
    (expect, mask, capture) <- allFlagTriples
  } {
    val insn = DriveBitOnScl(tx, expect, mask, capture)
    val word = roundTrip(insn)
    assertOpcode(word, Opcode.driveBitOnScl, "DRIVE_BIT_ON_SCL")
    // tx at [4:3]
    assert(
      field(word, 4, 3) == tx.position,
      s"DRIVE_BIT_ON_SCL tx field mismatch"
    )
    // Canonical dst rule: dst=R7 when capture=1, dst=0 when capture=0
    val expectedDst = if (capture) 7 else 0
    assert(
      field(word, 25, 23) == expectedDst,
      s"DRIVE_BIT_ON_SCL dst field mismatch (capture=$capture)"
    )
    assertReservedZero(word, 22, 5, "DRIVE_BIT_ON_SCL [22:5]")
    assertFlagTriple(word, expect, mask, capture, "DRIVE_BIT_ON_SCL")
  }
  // Golden §12.4: DRIVE_BIT_ON_SCL tx=dominant → 0x1800_0000
  // group=00 sub=0110, tx=0b00(dom) at [4:3]=0; dst=0 (no capture)
  assertGolden(
    encode(DriveBitOnScl(TxSymbol.dominant, false, false, false)),
    0x1800_0000L,
    "DRIVE_BIT_ON_SCL tx=dominant golden"
  )
  println(
    s"  DRIVE_BIT_ON_SCL: " +
      s"${allTxSymbols.size * allFlagTriples.size} round-trips OK"
  )

  // ------------------------------------------------------------------
  // §5.8  STRETCH_SCL_IMM
  // ------------------------------------------------------------------

  println("--- InstructionSim: STRETCH_SCL_IMM round-trip ---")

  val stretchSamples = Seq(0, 1, 100, 400, 8191, 16383)
  for (n <- stretchSamples) {
    val insn = StretchSclImm(n)
    val word = roundTrip(insn)
    assertOpcode(word, Opcode.stretchSclImm, "STRETCH_SCL_IMM")
    // n_quarters at [16:3]
    assert(
      field(word, 16, 3) == n,
      s"STRETCH_SCL_IMM n_quarters field mismatch (n=$n)"
    )
    assertReservedZero(word, 25, 17, "STRETCH_SCL_IMM [25:17]")
    assertReservedZero(word, 2, 0, "STRETCH_SCL_IMM [2:0]")
  }
  // Golden §12.4: STRETCH_SCL_IMM 400 → 0x1C00_0C80
  // group=00 sub=0111, n=400 at [16:3] → 400<<3=3200=0x0C80
  assertGolden(
    encode(StretchSclImm(400)),
    0x1c00_0c80L,
    "STRETCH_SCL_IMM 400 golden"
  )
  // Range rejects
  try {
    encode(StretchSclImm(16384))
    sys.error("STRETCH_SCL_IMM(16384) should have thrown")
  } catch { case _: IllegalArgumentException => () }
  try {
    encode(StretchSclImm(-1))
    sys.error("STRETCH_SCL_IMM(-1) should have thrown")
  } catch { case _: IllegalArgumentException => () }
  println(
    s"  STRETCH_SCL_IMM: ${stretchSamples.size} round-trips + range-reject OK"
  )

  // ------------------------------------------------------------------
  // §5.9  STRETCH_SCL_REG
  // ------------------------------------------------------------------

  println("--- InstructionSim: STRETCH_SCL_REG round-trip ---")

  for (src <- 0 until 8) {
    val insn = StretchSclReg(src)
    val word = roundTrip(insn)
    assertOpcode(word, Opcode.stretchSclReg, "STRETCH_SCL_REG")
    assert(
      field(word, 22, 20) == src,
      s"STRETCH_SCL_REG src field mismatch (src=$src)"
    )
    assertReservedZero(word, 25, 23, "STRETCH_SCL_REG [25:23]")
    assertReservedZero(word, 19, 0, "STRETCH_SCL_REG [19:0]")
  }
  // Golden §12.4: STRETCH_SCL_REG src=R0 → 0x2000_0000
  assertGolden(
    encode(StretchSclReg(0)),
    0x2000_0000L,
    "STRETCH_SCL_REG src=R0 golden"
  )
  try {
    encode(StretchSclReg(8))
    sys.error("STRETCH_SCL_REG(src=8) should have thrown")
  } catch { case _: IllegalArgumentException => () }
  println("  STRETCH_SCL_REG: 8 round-trips + range-reject OK")

  // ------------------------------------------------------------------
  // §5.10 HALT
  // ------------------------------------------------------------------

  println("--- InstructionSim: HALT round-trip ---")

  for (status <- 0 until 32) {
    val insn = Halt(status)
    val word = roundTrip(insn)
    assertOpcode(word, Opcode.halt, "HALT")
    assert(
      field(word, 7, 3) == status,
      s"HALT status field mismatch (status=$status)"
    )
    assertReservedZero(word, 25, 8, "HALT [25:8]")
    assertReservedZero(word, 2, 0, "HALT [2:0]")
  }
  // Golden §12.4: HALT status=2 → 0x4000_0010
  // group=01 sub=0000, status=2 at [7:3] → 2<<3=0x10
  assertGolden(
    encode(Halt(2)),
    0x4000_0010L,
    "HALT status=2 golden"
  )
  // Additional: HALT status=0 → 0x4000_0000
  assertGolden(
    encode(Halt(0)),
    0x4000_0000L,
    "HALT status=0 golden"
  )
  // Range rejects
  try {
    encode(Halt(32))
    sys.error("HALT(32) should have thrown")
  } catch { case _: IllegalArgumentException => () }
  try {
    encode(Halt(-1))
    sys.error("HALT(-1) should have thrown")
  } catch { case _: IllegalArgumentException => () }
  println("  HALT: 32 round-trips + range-reject OK")

  // ------------------------------------------------------------------
  // §5.11 BRANCH_ON
  // ------------------------------------------------------------------

  println("--- InstructionSim: BRANCH_ON round-trip ---")

  val branchOffsets = Seq(-512, -256, -4, -1, 0, 1, 4, 255, 511)
  for {
    cond <- allCondCodes
    off <- branchOffsets
  } {
    val insn = BranchOn(cond, off)
    val word = roundTrip(insn)
    assertOpcode(word, Opcode.branchOn, "BRANCH_ON")
    // cond at [16:13]
    assert(
      field(word, 16, 13) == cond.position,
      s"BRANCH_ON cond field mismatch ($cond)"
    )
    // offset at [12:3] as two's-complement 10-bit
    assert(
      field(word, 12, 3) == (off & 0x3ff),
      s"BRANCH_ON offset field mismatch (off=$off)"
    )
    assertReservedZero(word, 25, 17, "BRANCH_ON [25:17]")
    assertReservedZero(word, 2, 0, "BRANCH_ON [2:0]")
  }
  // Golden §12.4: BRANCH_ON MISMATCH, loop_top (offset=-4) → 0x4400_3FE0
  // cond=MISMATCH=1 at [16:13] → 1<<13=0x2000; offset=-4: 0x3FC<<3=0x1FE0
  assertGolden(
    encode(BranchOn(CondCode.mismatch, -4)),
    0x4400_3fe0L,
    "BRANCH_ON MISMATCH offset=-4 golden"
  )
  // Sugar: JMP <label> is BRANCH_ON ALWAYS, offset
  assertGolden(
    encode(BranchOn(CondCode.always, 0)),
    0x4400_0000L,
    "BRANCH_ON ALWAYS offset=0 golden"
  )
  // Range rejects
  try {
    encode(BranchOn(CondCode.always, 512))
    sys.error("BRANCH_ON(offset=512) should have thrown")
  } catch { case _: IllegalArgumentException => () }
  try {
    encode(BranchOn(CondCode.always, -513))
    sys.error("BRANCH_ON(offset=-513) should have thrown")
  } catch { case _: IllegalArgumentException => () }
  println(
    s"  BRANCH_ON: ${allCondCodes.size * branchOffsets.size} round-trips + range-reject OK"
  )

  // ------------------------------------------------------------------
  // §5.12 WAIT_ON
  // ------------------------------------------------------------------

  println("--- InstructionSim: WAIT_ON round-trip ---")

  val waitTimeouts = Seq(0, 1, 64, 512, 1023)
  for {
    cond <- allCondCodes
    t <- waitTimeouts
  } {
    val insn = WaitOn(cond, t)
    val word = roundTrip(insn)
    assertOpcode(word, Opcode.waitOn, "WAIT_ON")
    assert(
      field(word, 16, 13) == cond.position,
      s"WAIT_ON cond field mismatch ($cond)"
    )
    assert(
      field(word, 12, 3) == t,
      s"WAIT_ON timeout field mismatch (t=$t)"
    )
    assertReservedZero(word, 25, 17, "WAIT_ON [25:17]")
    assertReservedZero(word, 2, 0, "WAIT_ON [2:0]")
  }
  // Golden §12.4: WAIT_ON SDA_LOW, 64 → 0x4800_A200
  // cond=SDA_LOW=5 at [16:13] → 5<<13=0xA000; timeout=64 at [12:3] → 64<<3=0x200
  assertGolden(
    encode(WaitOn(CondCode.sdaLow, 64)),
    0x4800_a200L,
    "WAIT_ON SDA_LOW 64 golden"
  )
  // Range rejects
  try {
    encode(WaitOn(CondCode.always, 1024))
    sys.error("WAIT_ON(timeout=1024) should have thrown")
  } catch { case _: IllegalArgumentException => () }
  try {
    encode(WaitOn(CondCode.always, -1))
    sys.error("WAIT_ON(timeout=-1) should have thrown")
  } catch { case _: IllegalArgumentException => () }
  println(
    s"  WAIT_ON: ${allCondCodes.size * waitTimeouts.size} round-trips + range-reject OK"
  )

  // ------------------------------------------------------------------
  // §5.13 SET_BUS_MODE
  // ------------------------------------------------------------------

  println("--- InstructionSim: SET_BUS_MODE round-trip ---")

  for (mode <- allBusModes) {
    val insn = SetBusMode(mode)
    val word = roundTrip(insn)
    assertOpcode(word, Opcode.setBusMode, "SET_BUS_MODE")
    // mode at [6:3]; v0.2 sequential: i2c=0, i3c-OD=1, i3c-PP=2, hdr-ddr=3
    val expectedWire = BusMode.wireValue(mode)
    assert(
      field(word, 6, 3) == expectedWire,
      s"SET_BUS_MODE mode field mismatch ($mode, wire=$expectedWire)"
    )
    assertReservedZero(word, 25, 7, "SET_BUS_MODE [25:7]")
    assertReservedZero(word, 2, 0, "SET_BUS_MODE [2:0]")
  }
  // Golden §12.4: SET_BUS_MODE i3c-PP → 0x4C00_0010
  // group=01 sub=0011, mode=0b0010 at [6:3] → 2<<3=0x10
  assertGolden(
    encode(SetBusMode(BusMode.i3cPp)),
    0x4c00_0010L,
    "SET_BUS_MODE i3c-PP golden"
  )
  // v0.2 sequential encoding goldens (verify renumbering from v0)
  assertGolden(
    encode(SetBusMode(BusMode.i2c)),
    0x4c00_0000L,
    "SET_BUS_MODE i2c golden"
  )
  assertGolden(
    encode(SetBusMode(BusMode.i3cOd)),
    0x4c00_0008L,
    "SET_BUS_MODE i3c-OD golden"
  )
  assertGolden(
    encode(SetBusMode(BusMode.hdrDdr)),
    0x4c00_0018L,
    "SET_BUS_MODE hdr-ddr golden"
  )
  println(s"  SET_BUS_MODE: ${allBusModes.size} round-trips OK")

  // ------------------------------------------------------------------
  // §5.14 SET_ROLE
  // ------------------------------------------------------------------

  println("--- InstructionSim: SET_ROLE round-trip ---")

  for (role <- Seq(false, true)) {
    val insn = SetRole(role)
    val word = roundTrip(insn)
    assertOpcode(word, Opcode.setRole, "SET_ROLE")
    // role at [3]
    assert(
      field(word, 3, 3) == (if (role) 1 else 0),
      s"SET_ROLE role bit mismatch (role=$role)"
    )
    assertReservedZero(word, 25, 4, "SET_ROLE [25:4]")
    assertReservedZero(word, 2, 0, "SET_ROLE [2:0]")
  }
  // Golden §12.4: SET_ROLE target → 0x5000_0008
  assertGolden(encode(SetRole(true)), 0x5000_0008L, "SET_ROLE target golden")
  // Controller → 0x5000_0000
  assertGolden(
    encode(SetRole(false)),
    0x5000_0000L,
    "SET_ROLE controller golden"
  )
  println("  SET_ROLE: 2 round-trips OK")

  // ------------------------------------------------------------------
  // §5.15 FLAG_CLEAR
  // ------------------------------------------------------------------

  println("--- InstructionSim: FLAG_CLEAR round-trip ---")

  val flagMasks = Seq(0, 1, 0x1f, 0b00001, 0b00110, 0b11111)
  for (mask <- flagMasks) {
    val insn = FlagClear(mask)
    val word = roundTrip(insn)
    assertOpcode(word, Opcode.flagClear, "FLAG_CLEAR")
    // mask at [7:3]
    assert(
      field(word, 7, 3) == mask,
      s"FLAG_CLEAR mask field mismatch (mask=$mask)"
    )
    assertReservedZero(word, 25, 8, "FLAG_CLEAR [25:8]")
    assertReservedZero(word, 2, 0, "FLAG_CLEAR [2:0]")
  }
  // Golden §12.4: FLAG_CLEAR 0b00001 → 0x5400_0008
  assertGolden(
    encode(FlagClear(0b00001)),
    0x5400_0008L,
    "FLAG_CLEAR 0b00001 golden"
  )
  // All-bits: 31<<3=0xF8
  assertGolden(
    encode(FlagClear(31)),
    0x5400_00f8L,
    "FLAG_CLEAR 0b11111 golden"
  )
  // Range rejects
  try {
    encode(FlagClear(32))
    sys.error("FLAG_CLEAR(32) should have thrown")
  } catch { case _: IllegalArgumentException => () }
  try {
    encode(FlagClear(-1))
    sys.error("FLAG_CLEAR(-1) should have thrown")
  } catch { case _: IllegalArgumentException => () }
  println(s"  FLAG_CLEAR: ${flagMasks.size} round-trips + range-reject OK")

  // ------------------------------------------------------------------
  // §5.16 MARK
  // ------------------------------------------------------------------

  println("--- InstructionSim: MARK round-trip ---")

  val markLabels = Seq(0, 1, 42, 255, 1000, 16383)
  for (label <- markLabels) {
    val insn = Mark(label)
    val word = roundTrip(insn)
    assertOpcode(word, Opcode.mark, "MARK")
    // label at [16:3]
    assert(
      field(word, 16, 3) == label,
      s"MARK label field mismatch (label=$label)"
    )
    assertReservedZero(word, 25, 17, "MARK [25:17]")
    assertReservedZero(word, 2, 0, "MARK [2:0]")
  }
  // Golden §12.4: MARK label=42 → 0x5800_0150
  // label=42 at [16:3] → 42<<3=336=0x150
  assertGolden(
    encode(Mark(42)),
    0x5800_0150L,
    "MARK label=42 golden"
  )
  // Range rejects
  try {
    encode(Mark(16384))
    sys.error("MARK(16384) should have thrown")
  } catch { case _: IllegalArgumentException => () }
  println(s"  MARK: ${markLabels.size} round-trips + range-reject OK")

  // ------------------------------------------------------------------
  // §5.17 LOAD_TIMING
  // ------------------------------------------------------------------

  println("--- InstructionSim: LOAD_TIMING round-trip ---")

  val timingDividers = Seq(0, 1, 59, 480, 16383)
  for {
    reg <- 0 until 8
    div <- timingDividers
  } {
    val insn = LoadTiming(reg, div)
    val word = roundTrip(insn)
    assertOpcode(word, Opcode.loadTiming, "LOAD_TIMING")
    // reg at [19:17]
    assert(
      field(word, 19, 17) == reg,
      s"LOAD_TIMING reg field mismatch (reg=$reg)"
    )
    // divider at [16:3]
    assert(
      field(word, 16, 3) == div,
      s"LOAD_TIMING divider field mismatch (div=$div)"
    )
    assertReservedZero(word, 25, 20, "LOAD_TIMING [25:20]")
    assertReservedZero(word, 2, 0, "LOAD_TIMING [2:0]")
  }
  // Golden §12.4: LOAD_TIMING reg=0, divider=480 → 0x5C00_0F00
  // reg=0 at [19:17]=0; divider=480 at [16:3] → 480<<3=3840=0x0F00
  assertGolden(
    encode(LoadTiming(0, 480)),
    0x5c00_0f00L,
    "LOAD_TIMING reg=0 divider=480 golden"
  )
  // Range rejects
  try {
    encode(LoadTiming(8, 0))
    sys.error("LOAD_TIMING(reg=8) should have thrown")
  } catch { case _: IllegalArgumentException => () }
  try {
    encode(LoadTiming(0, 16384))
    sys.error("LOAD_TIMING(divider=16384) should have thrown")
  } catch { case _: IllegalArgumentException => () }
  println(
    s"  LOAD_TIMING: ${8 * timingDividers.size} round-trips + range-reject OK"
  )

  // ------------------------------------------------------------------
  // §5.18 LOAD_IMM
  // ------------------------------------------------------------------

  println("--- InstructionSim: LOAD_IMM round-trip ---")

  val immSamples = Seq(0, 1, 42, 100, 255, 8191, 16383)
  for {
    dst <- 0 until 8
    imm <- immSamples
  } {
    val insn = LoadImm(dst, imm)
    val word = roundTrip(insn)
    assertOpcode(word, Opcode.loadImm, "LOAD_IMM")
    assert(field(word, 25, 23) == dst, s"LOAD_IMM dst field mismatch")
    assert(field(word, 16, 3) == imm, s"LOAD_IMM imm14 field mismatch")
    assertReservedZero(word, 22, 17, "LOAD_IMM [22:17]")
    assertReservedZero(word, 2, 0, "LOAD_IMM [2:0]")
  }
  // Golden §12.4: LOAD_IMM R3, 100 → 0x8180_0320
  // dst=3 at [25:23] → 3<<23=0x0180_0000; imm=100 at [16:3] → 100<<3=0x320
  assertGolden(
    encode(LoadImm(3, 100)),
    0x8180_0320L,
    "LOAD_IMM R3 100 golden"
  )
  // Range rejects
  try {
    encode(LoadImm(8, 0))
    sys.error("LOAD_IMM(dst=8) should have thrown")
  } catch { case _: IllegalArgumentException => () }
  try {
    encode(LoadImm(0, 16384))
    sys.error("LOAD_IMM(imm=16384) should have thrown")
  } catch { case _: IllegalArgumentException => () }
  try {
    encode(LoadImm(0, -1))
    sys.error("LOAD_IMM(imm=-1) should have thrown")
  } catch { case _: IllegalArgumentException => () }
  println(
    s"  LOAD_IMM: ${8 * immSamples.size} round-trips + range-reject OK"
  )

  // ------------------------------------------------------------------
  // §5.19 MOV
  // ------------------------------------------------------------------

  println("--- InstructionSim: MOV round-trip ---")

  for {
    dst <- 0 until 8
    src <- 0 until 8
  } {
    val insn = Mov(dst, src)
    val word = roundTrip(insn)
    assertOpcode(word, Opcode.mov, "MOV")
    assert(field(word, 25, 23) == dst, s"MOV dst field mismatch")
    assert(field(word, 22, 20) == src, s"MOV src field mismatch")
    assertReservedZero(word, 19, 0, "MOV [19:0]")
  }
  // Golden §12.4: MOV R1, R0 → 0x8480_0000
  // dst=1 at [25:23] → 0x0080_0000; src=0 at [22:20] → 0
  assertGolden(
    encode(Mov(1, 0)),
    0x8480_0000L,
    "MOV R1 R0 golden"
  )
  println("  MOV: 64 round-trips OK")

  // ------------------------------------------------------------------
  // §5.20 ADD_IMM
  // ------------------------------------------------------------------

  println("--- InstructionSim: ADD_IMM round-trip ---")

  val addImmSamples = Seq(-8192, -1, 0, 1, 4, 8191)
  for {
    dst <- Seq(0, 7)
    src <- Seq(0, 7)
    imm <- addImmSamples
  } {
    val insn = AddImm(dst, src, imm)
    val word = roundTrip(insn)
    assertOpcode(word, Opcode.addImm, "ADD_IMM")
    assert(field(word, 25, 23) == dst, s"ADD_IMM dst field mismatch")
    assert(field(word, 22, 20) == src, s"ADD_IMM src field mismatch")
    assert(
      field(word, 16, 3) == (imm & 0x3fff),
      s"ADD_IMM imm14 field mismatch (imm=$imm)"
    )
    assertReservedZero(word, 19, 17, "ADD_IMM [19:17]")
    assertReservedZero(word, 2, 0, "ADD_IMM [2:0]")
  }
  // Golden §12.4: ADD_IMM R0, R0, 4 → 0x8800_0020
  assertGolden(
    encode(AddImm(0, 0, 4)),
    0x8800_0020L,
    "ADD_IMM R0 R0 4 golden"
  )
  // Range rejects
  try {
    encode(AddImm(0, 0, 8192))
    sys.error("ADD_IMM(imm=8192) should have thrown")
  } catch { case _: IllegalArgumentException => () }
  try {
    encode(AddImm(0, 0, -8193))
    sys.error("ADD_IMM(imm=-8193) should have thrown")
  } catch { case _: IllegalArgumentException => () }
  println(
    s"  ADD_IMM: ${2 * 2 * addImmSamples.size} round-trips + range-reject OK"
  )

  // ------------------------------------------------------------------
  // §5.21 DEC
  // ------------------------------------------------------------------

  println("--- InstructionSim: DEC round-trip ---")

  for (reg <- 0 until 8) {
    val insn = Dec(reg)
    val word = roundTrip(insn)
    assertOpcode(word, Opcode.dec, "DEC")
    // dst=src=reg
    assert(field(word, 25, 23) == reg, s"DEC dst field mismatch (reg=$reg)")
    assert(field(word, 22, 20) == reg, s"DEC src field mismatch (reg=$reg)")
    assertReservedZero(word, 19, 0, "DEC [19:0]")
  }
  // Golden §12.4: DEC R6 → 0x8F60_0000
  // dst=6 at [25:23] → 6<<23=0x0300_0000; src=6 at [22:20] → 6<<20=0x0060_0000
  assertGolden(
    encode(Dec(6)),
    0x8f60_0000L,
    "DEC R6 golden"
  )
  // Additional: DEC R0 → 0x8C00_0000
  assertGolden(
    encode(Dec(0)),
    0x8c00_0000L,
    "DEC R0 golden"
  )
  try {
    encode(Dec(8))
    sys.error("DEC(reg=8) should have thrown")
  } catch { case _: IllegalArgumentException => () }
  println("  DEC: 8 round-trips + range-reject OK")

  // ------------------------------------------------------------------
  // §5.22  AND_IMM  / §5.23  OR_IMM  / §5.24  XOR_IMM
  // (identical layout, different sub-opcodes)
  // ------------------------------------------------------------------

  println("--- InstructionSim: AND_IMM / OR_IMM / XOR_IMM round-trip ---")

  val logicImmSamples = Seq(0, 1, 0xff, 0x3fff)
  for (imm <- logicImmSamples) {
    // AND_IMM
    val wa = roundTrip(AndImm(0, 0, imm))
    assertOpcode(wa, Opcode.andImm, "AND_IMM")
    assert(field(wa, 16, 3) == imm, s"AND_IMM imm field mismatch")
    // OR_IMM
    val wo = roundTrip(OrImm(0, 0, imm))
    assertOpcode(wo, Opcode.orImm, "OR_IMM")
    assert(field(wo, 16, 3) == imm, s"OR_IMM imm field mismatch")
    // XOR_IMM
    val wx = roundTrip(XorImm(0, 0, imm))
    assertOpcode(wx, Opcode.xorImm, "XOR_IMM")
    assert(field(wx, 16, 3) == imm, s"XOR_IMM imm field mismatch")
  }
  // Golden §12.4: AND_IMM R0, R0, 0xFF → 0x9000_07F8
  assertGolden(encode(AndImm(0, 0, 0xff)), 0x9000_07f8L, "AND_IMM 0xFF golden")
  // Golden §12.4: OR_IMM R0, R0, 0x01 → 0x9400_0008
  assertGolden(encode(OrImm(0, 0, 0x01)), 0x9400_0008L, "OR_IMM 0x01 golden")
  // Golden §12.4: XOR_IMM R0, R0, 0xFF → 0x9800_07F8
  assertGolden(encode(XorImm(0, 0, 0xff)), 0x9800_07f8L, "XOR_IMM 0xFF golden")
  // Range rejects for AND_IMM (same logic applies to OR/XOR)
  try {
    encode(AndImm(0, 0, 16384))
    sys.error("AND_IMM(imm=16384) should have thrown")
  } catch { case _: IllegalArgumentException => () }
  println(
    s"  AND/OR/XOR_IMM: ${3 * logicImmSamples.size} round-trips + golden OK"
  )

  // ------------------------------------------------------------------
  // §5.25 SHIFT
  // ------------------------------------------------------------------

  println("--- InstructionSim: SHIFT round-trip ---")

  val shamtSamples = Seq(0, 1, 16, 31)
  for {
    arith <- Seq(false, true)
    dir <- Seq(false, true)
    shamt <- shamtSamples
  } {
    val insn = Shift(0, 0, arith, dir, shamt)
    val word = roundTrip(insn)
    assertOpcode(word, Opcode.shift, "SHIFT")
    assert(
      field(word, 19, 19) == (if (arith) 1 else 0),
      s"SHIFT arith mismatch"
    )
    assert(field(word, 18, 18) == (if (dir) 1 else 0), s"SHIFT dir mismatch")
    assert(field(word, 17, 17) == 0, s"SHIFT [17] not reserved-zero")
    assert(field(word, 7, 3) == shamt, s"SHIFT shamt mismatch")
    assertReservedZero(word, 16, 8, "SHIFT [16:8]")
    assertReservedZero(word, 2, 0, "SHIFT [2:0]")
  }
  // Golden §12.4: SHIFT R0, R0, left, 1 → 0x9C00_0008
  // arith=0 at [19]=0; dir=0(left) at [18]=0; shamt=1 at [7:3] → 1<<3=0x08
  assertGolden(
    encode(Shift(0, 0, false, false, 1)),
    0x9c00_0008L,
    "SHIFT left 1 golden"
  )
  // Range rejects
  try {
    encode(Shift(0, 0, false, false, 32))
    sys.error("SHIFT(shamt=32) should have thrown")
  } catch { case _: IllegalArgumentException => () }
  try {
    encode(Shift(8, 0, false, false, 0))
    sys.error("SHIFT(dst=8) should have thrown")
  } catch { case _: IllegalArgumentException => () }
  println(
    s"  SHIFT: ${2 * 2 * shamtSamples.size} round-trips + range-reject OK"
  )

  // ------------------------------------------------------------------
  // Reserved slot round-trips
  // ------------------------------------------------------------------

  println("--- InstructionSim: ReservedV05 round-trip ---")

  val payloadSamples = Seq(0, 1, 0xff, 0x3ffff, 0x3ffffff)
  for {
    op <- reservedOpcodes.take(8) // sample to keep runtime short
    p <- payloadSamples
  } {
    val insn = ReservedV05(op, p)
    val word = roundTrip(insn)
    assertOpcode(word, op, s"ReservedV05($op)")
    assert(
      (word & 0x03ffffff) == p,
      s"ReservedV05 payload mismatch (op=$op, p=$p)"
    )
  }
  // Constructor must reject live opcodes
  try {
    ReservedV05(Opcode.emitBitImm, 0)
    sys.error("ReservedV05(emitBitImm) should have thrown")
  } catch { case _: IllegalArgumentException => () }
  try {
    ReservedV05(Opcode.halt, 0)
    sys.error("ReservedV05(halt) should have thrown")
  } catch { case _: IllegalArgumentException => () }
  // Payload overflow
  try {
    ReservedV05(Opcode.wireResA, 1 << 26)
    sys.error("ReservedV05 payload overflow should have thrown")
  } catch { case _: IllegalArgumentException => () }
  println(
    s"  ReservedV05: ${8 * payloadSamples.size} round-trips + constructor-reject OK"
  )

  // ------------------------------------------------------------------
  // Flag-triple [2:0] invariant across ALL bearer opcodes
  // (AGENTS §3.10 structural check)
  // ------------------------------------------------------------------

  println("--- InstructionSim: flag-triple [2:0] invariant ---")

  /** Minimal-operand bearers: (label, builder(expect, mask, capture)). */
  val bearerBuilders
      : Seq[(String, (Boolean, Boolean, Boolean) => Instruction)] =
    Seq(
      ("EMIT_BIT_IMM", (e, m, c) => EmitBitImm(TxSymbol.dominant, e, m, c)),
      (
        "EMIT_BIT_REG",
        (e, m, c) => EmitBitReg(0, e, m, c)
      ),
      (
        "EMIT_QUARTER_IMM",
        (e, m, c) =>
          EmitQuarterImm(TxSymbol.dominant, TxSymbol.dominant, e, m, c)
      ),
      (
        "EMIT_QUARTER_REG",
        (e, m, c) => EmitQuarterReg(0, e, m, c)
      ),
      ("EMIT_BYTE_REG", (e, m, c) => EmitByteReg(e, m, c)),
      ("EMIT_BYTE_IMM", (e, m, c) => EmitByteImm(0, e, m, c)),
      ("SAMPLE_BIT_ON_SCL", (e, m, c) => SampleBitOnScl(e, m, c)),
      (
        "DRIVE_BIT_ON_SCL",
        (e, m, c) => DriveBitOnScl(TxSymbol.dominant, e, m, c)
      )
    )

  for ((label, build) <- bearerBuilders) {
    // expect-only: bit 2 set, bits 1 and 0 clear
    val wE = encode(build(true, false, false))
    assert(
      ((wE >>> EXPECT_BIT) & 1) == 1 &&
        ((wE >>> MASK_BIT) & 1) == 0 &&
        ((wE >>> CAPTURE_BIT) & 1) == 0,
      s"$label expect-only: wrong flag bits in " +
        f"0x${wE.toLong & 0xffffffffL}%08X"
    )
    // mask-only: bit 1 set, bits 2 and 0 clear
    val wM = encode(build(false, true, false))
    assert(
      ((wM >>> MASK_BIT) & 1) == 1 &&
        ((wM >>> EXPECT_BIT) & 1) == 0 &&
        ((wM >>> CAPTURE_BIT) & 1) == 0,
      s"$label mask-only: wrong flag bits in " +
        f"0x${wM.toLong & 0xffffffffL}%08X"
    )
    // capture-only: bit 0 set, bits 2 and 1 clear
    val wC = encode(build(false, false, true))
    assert(
      ((wC >>> CAPTURE_BIT) & 1) == 1 &&
        ((wC >>> EXPECT_BIT) & 1) == 0 &&
        ((wC >>> MASK_BIT) & 1) == 0,
      s"$label capture-only: wrong flag bits in " +
        f"0x${wC.toLong & 0xffffffffL}%08X"
    )
  }
  println(
    s"  Flag triple at [2:0] on ${bearerBuilders.size} bearer opcodes OK"
  )

  // ------------------------------------------------------------------
  // Non-bearer opcodes must have [2:0] = 0 in canonical encoding
  // (STRETCH_SCL_IMM, STRETCH_SCL_REG, all CTRL, all DATA)
  // ------------------------------------------------------------------

  println("--- InstructionSim: non-bearer [2:0] == 0 invariant ---")

  val nonBearerSamples: Seq[Instruction] = Seq(
    StretchSclImm(0),
    StretchSclReg(0),
    Halt(0),
    BranchOn(CondCode.always, 0),
    WaitOn(CondCode.always, 0),
    SetBusMode(BusMode.i2c),
    SetRole(false),
    FlagClear(0),
    Mark(0),
    LoadTiming(0, 0),
    LoadImm(0, 0),
    Mov(0, 0),
    AddImm(0, 0, 0),
    Dec(0),
    AndImm(0, 0, 0),
    OrImm(0, 0, 0),
    XorImm(0, 0, 0),
    Shift(0, 0, false, false, 0)
  )
  for (insn <- nonBearerSamples) {
    val word = encode(insn)
    assert(
      (word & 0x7) == 0,
      s"Non-bearer $insn has [2:0] != 0 in " +
        f"0x${word.toLong & 0xffffffffL}%08X"
    )
  }
  println(s"  Non-bearer [2:0]==0: ${nonBearerSamples.size} opcodes OK")

  println("InstructionSim: all v0.2 round-trips + golden encodings PASS")
}
