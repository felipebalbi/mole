package mole

import spinal.core._

/** Compile-time engine revision word emitted on `HALT` (and, in later
  * phases, on `MARK` with a reserved label id).
  *
  * The major/minor/patch triple is the single source of truth for the
  * engine's binary identity. Values are sourced from JVM system
  * properties (`-Drevision.major=...`, etc.) so the `Makefile` is the
  * one place a version bump is recorded; the defaults baked in here
  * mirror the `Makefile`'s `REVISION_*` macros so a bare
  * `sbt runMain mole.MoleTopVerilog` outside `make` still produces the
  * canonical word.
  *
  * Wire layout per `fpga/Mole/AGENTS.md` §"REVISION word convention":
  *
  * {{{
  *   [31:24] = major   (8 bits)
  *   [23:16] = minor   (8 bits)
  *   [15: 0] = patch  (16 bits)
  * }}}
  *
  * The result ring is 16-bit-word grained; the engine emits the word in
  * two writes (low word first --- [[wordLo]] then [[wordHi]]) so the
  * host reading the byte stream sees the bytes in little-endian order
  * matching the rest of the Mole protocol.
  */
object Revision {

  /** Read a system property as an Int, falling back to `default` if the
    * property is absent. Used at elaboration time to pick up
    * `-Drevision.major=...` etc. from the `Makefile`.
    */
  private def intProp(key: String, default: Int): Int =
    sys.props.getOrElse(key, default.toString).toInt

  /** Major revision (top 8 bits of the word). Default `0` mirrors the
    * `Makefile`'s `REVISION_MAJOR`.
    */
  val major: Int = intProp("revision.major", 0)

  /** Minor revision (middle 8 bits). Default `1` mirrors the `Makefile`'s
    * `REVISION_MINOR`.
    */
  val minor: Int = intProp("revision.minor", 1)

  /** Patch revision (low 16 bits). Default `0` mirrors the `Makefile`'s
    * `REVISION_PATCH`.
    */
  val patch: Int = intProp("revision.patch", 0)

  require(
    major >= 0 && major <= 0xff,
    s"REVISION major=$major must fit in 8 bits unsigned (0..255)"
  )
  require(
    minor >= 0 && minor <= 0xff,
    s"REVISION minor=$minor must fit in 8 bits unsigned (0..255)"
  )
  require(
    patch >= 0 && patch <= 0xffff,
    s"REVISION patch=$patch must fit in 16 bits unsigned (0..65535)"
  )

  /** Bit width of the full revision word. */
  val WORD_WIDTH: Int = 32

  /** Bit width of one result-ring write (the engine emits the 32-bit
    * word as two of these, low half first).
    */
  val HALF_WIDTH: Int = 16

  /** Full 32-bit revision word, as a plain `Int`. */
  val word: Int = (major << 24) | (minor << 16) | patch

  /** Low 16 bits of [[word]] --- the first half-word written into the
    * result ring on `HALT`.
    */
  val wordLo: Int = word & 0xffff

  /** High 16 bits of [[word]] --- the second half-word written into the
    * result ring on `HALT`.
    */
  val wordHi: Int = (word >>> 16) & 0xffff

  /** Hardware view of [[wordLo]]: a fixed [[Bits]] literal suitable for
    * driving a result-ring write port.
    */
  def hwLo: Bits = B(wordLo, HALF_WIDTH bits)

  /** Hardware view of [[wordHi]]. */
  def hwHi: Bits = B(wordHi, HALF_WIDTH bits)
}
