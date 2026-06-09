package mole

import java.io.File
import java.nio.file.Files

/** Cross-check the Scala [[Instruction]] encoder against the Rust mole-asm
  * golden molecode fixtures.
  *
  * Each fixture file in mole-asm/tests/fixtures (extension .molecode) is a
  * sequence of little-endian 32-bit words produced by the Rust encoder: Word 0
  * (LE): MAGIC = 0x0002_4D4C, Word 1 (LE): N = body length, Words 2..N+1: N
  * instruction words.
  *
  * This sim:
  *   1. Locates all .molecode files under ../../mole-asm/tests/fixtures/
  *      (relative to the sbt project root, i.e. fpga/Mole/).
  *   2. For each file, verifies the preamble (MAGIC + length).
  *   3. Calls [[Instruction.decode]] on every body word and counts by opcode.
  *   4. Reports a per-file opcode histogram.
  *   5. Aggregates PASS / FAIL across all files.
  *
  * A failure means the Scala decoder cannot parse a word the Rust encoder
  * produced --- indicating a bit-layout disagreement between the two encoders.
  *
  * Run: `sbt "runMain mole.InstructionGoldenCrossCheckSim"`
  */
object InstructionGoldenCrossCheckSim extends App {

  import Instruction._

  // ------------------------------------------------------------------
  // Locate fixtures directory
  // ------------------------------------------------------------------

  // sbt runs with working directory = the sub-project directory
  // (fpga/Mole/). The Rust fixtures are two levels up from there.
  val fixturesPath =
    sys.props.getOrElse(
      "mole.fixtures",
      "../../mole-asm/tests/fixtures"
    )
  val fixturesDir = new File(fixturesPath)
  assert(
    fixturesDir.isDirectory,
    s"Fixtures directory not found: ${fixturesDir.getAbsolutePath}\n" +
      "Set JVM property -Dmole.fixtures=<path> to override."
  )

  val fixtureFiles = fixturesDir
    .listFiles()
    .filter(f => f.isFile && f.getName.endsWith(".molecode"))
    .sortBy(_.getName)

  assert(
    fixtureFiles.nonEmpty,
    s"No *.molecode files found in ${fixturesDir.getAbsolutePath}"
  )

  // ------------------------------------------------------------------
  // Per-file decode helpers
  // ------------------------------------------------------------------

  /** Read a little-endian 32-bit unsigned value from 4 bytes at offset `i` in
    * `bytes`. Returns an `Int` (the bit pattern; may be negative when bit 31 is
    * set --- treat as unsigned 32-bit).
    */
  def readLE32(bytes: Array[Byte], i: Int): Int =
    (bytes(i) & 0xff) |
      ((bytes(i + 1) & 0xff) << 8) |
      ((bytes(i + 2) & 0xff) << 16) |
      ((bytes(i + 3) & 0xff) << 24)

  /** Opcode name from a decoded instruction (for histogram). */
  def opcodeName(insn: Instruction): String = insn match {
    case _: EmitBitImm     => "EMIT_BIT_IMM"
    case _: EmitBitReg     => "EMIT_BIT_REG"
    case _: EmitQuarterImm => "EMIT_QUARTER_IMM"
    case _: EmitQuarterReg => "EMIT_QUARTER_REG"
    case _: EmitByteReg    => "EMIT_BYTE_REG"
    case _: EmitByteImm    => "EMIT_BYTE_IMM"
    case _: SampleBitOnScl => "SAMPLE_BIT_ON_SCL"
    case _: DriveBitOnScl  => "DRIVE_BIT_ON_SCL"
    case _: StretchSclImm  => "STRETCH_SCL_IMM"
    case _: StretchSclReg  => "STRETCH_SCL_REG"
    case _: Halt           => "HALT"
    case _: BranchOn       => "BRANCH_ON"
    case _: WaitOn         => "WAIT_ON"
    case _: SetBusMode     => "SET_BUS_MODE"
    case _: SetRole        => "SET_ROLE"
    case _: FlagClear      => "FLAG_CLEAR"
    case _: Mark           => "MARK"
    case _: LoadTiming     => "LOAD_TIMING"
    case _: LoadImm        => "LOAD_IMM"
    case _: Mov            => "MOV"
    case _: AddImm         => "ADD_IMM"
    case _: Dec            => "DEC"
    case _: AndImm         => "AND_IMM"
    case _: OrImm          => "OR_IMM"
    case _: XorImm         => "XOR_IMM"
    case _: Shift          => "SHIFT"
    case r: ReservedV05    => s"RESERVED(0x${r.opcode.position.toHexString})"
  }

  // ------------------------------------------------------------------
  // Process each file
  // ------------------------------------------------------------------

  var totalFiles = 0
  var totalWords = 0
  var failCount = 0

  for (file <- fixtureFiles) {
    totalFiles += 1
    val name = file.getName
    println(s"\n--- $name ---")

    val bytes = Files.readAllBytes(file.toPath)

    // Must be a multiple of 4 bytes and at least 8 (two preamble words)
    assert(
      bytes.length >= 8 && (bytes.length % 4) == 0,
      s"$name: invalid length ${bytes.length}; must be >= 8 and multiple of 4"
    )

    val totalWordCount = bytes.length / 4
    println(s"  Total words: $totalWordCount")

    // Check MAGIC (word 0, little-endian)
    val magic = readLE32(bytes, 0)
    val magicU = magic.toLong & 0xffffffffL
    assert(
      magicU == MAGIC,
      f"$name: MAGIC mismatch: got 0x$magicU%08X, expected 0x$MAGIC%08X"
    )

    // Check length word (word 1, little-endian)
    val bodyLen = readLE32(bytes, 4)
    val bodyLenU = bodyLen.toLong & 0xffffffffL
    assert(
      PREAMBLE_WORDS + bodyLenU == totalWordCount,
      s"$name: length field $bodyLenU does not match file word count " +
        s"$totalWordCount (preamble=$PREAMBLE_WORDS)"
    )
    assert(
      bodyLenU >= 1,
      s"$name: empty program body (length=0)"
    )
    assert(
      bodyLenU <= MAX_PROGRAM_WORDS,
      s"$name: body length $bodyLenU exceeds MAX_PROGRAM_WORDS=$MAX_PROGRAM_WORDS"
    )
    println(s"  Body words: $bodyLenU")

    // Decode each body word and accumulate histogram
    val histogram = scala.collection.mutable.Map[String, Int]()
    var fileFail = false

    for (wi <- 0 until bodyLenU.toInt) {
      val byteOff = (PREAMBLE_WORDS + wi) * 4
      val word = readLE32(bytes, byteOff)
      val wordU = word.toLong & 0xffffffffL

      val insn =
        try {
          decode(word)
        } catch {
          case ex: Exception =>
            println(
              f"  FAIL at word $wi (0x$wordU%08X): ${ex.getMessage}"
            )
            failCount += 1
            fileFail = true
            null
        }

      if (insn != null) {
        val oname = opcodeName(insn)
        histogram(oname) = histogram.getOrElse(oname, 0) + 1
        totalWords += 1
      }
    }

    // Print histogram sorted by count descending
    if (histogram.nonEmpty) {
      println("  Opcode histogram:")
      for ((op, count) <- histogram.toSeq.sortBy(-_._2)) {
        println(f"    $op%-24s $count%d")
      }
    }

    if (!fileFail) {
      println(s"  PASS")
    } else {
      println(s"  FAIL (see decode errors above)")
    }
  }

  // ------------------------------------------------------------------
  // Final summary
  // ------------------------------------------------------------------

  println(
    s"\nInstructionGoldenCrossCheckSim summary:" +
      s" $totalFiles fixture files, $totalWords words decoded"
  )

  if (failCount == 0) {
    println("ALL FIXTURES PASS: Scala and Rust encoders agree on every word.")
  } else {
    sys.error(
      s"CROSS-CHECK FAILED: $failCount word(s) could not be decoded. " +
        "Scala and Rust encoders disagree."
    )
  }
}
