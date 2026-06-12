package mole

import spinal.core._

/** Compile-time engine revision word emitted at program start (spec §11.2).
  *
  * The major/minor/patch triple is the single source of truth for the engine's
  * binary identity. Values are sourced from JVM system properties
  * (`-Drevision.major=...`, etc.) so the `Makefile` is the one place a version
  * bump is recorded; the defaults baked in here mirror the `Makefile`'s
  * `REVISION_*` macros so a bare `sbt runMain mole.MoleTopVerilog` outside
  * `make` still produces the canonical word.
  *
  * Wire layout per `fpga/Mole/AGENTS.md` §"REVISION word convention" and spec
  * §11.2:
  *
  * {{{
  *   [31:24] = major   (8 bits)
  *   [23:16] = minor   (8 bits)
  *   [15: 0] = patch  (16 bits)
  * }}}
  *
  * The engine writes the 32-bit REVISION word at `word[0]` of the result ring
  * on every program start (see [[EnginePipeline]] revision-emission logic). The
  * drainer then streams it as 4 LE bytes to the host. There is no 16-bit-half
  * split; the result ring is 32-bit-grained from C.3 onward.
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

  /** Patch revision (low 16 bits). Default `1` mirrors the `Makefile`'s
    * `REVISION_PATCH`.
    */
  val patch: Int = intProp("revision.patch", 1)

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

  /** Bit width of the full revision word (32, matches the result-ring grain).
    */
  val WORD_WIDTH: Int = 32

  /** Full 32-bit revision word, as a plain `Int`. */
  val word: Int = (major << 24) | (minor << 16) | patch

  /** Hardware view of [[word]]: a fixed 32-bit [[Bits]] literal suitable for
    * driving a result-ring write port.
    */
  def hw: Bits = B(word, WORD_WIDTH bits)
}
