package mole

import spinal.core._

/** Verilog generation entrypoint for [[MoleTop]].
  *
  * Emits `MoleTop.v` into the `gen/` directory (created on demand by
  * the Makefile, gitignored by the per-project rule). Always uses
  * `useBlackBox = true` --- the synth path needs SB_PLL40_PAD and
  * SB_IO primitive instantiations so yosys can recognise them as
  * iCE40 hard cells; the sim-bypass models exist only for
  * `MoleTopSim`.
  *
  * Run via `make gen` or directly:
  * {{{
  *   sbt -batch "runMain mole.MoleTopVerilog"
  * }}}
  *
  * Build full bitstream via `make all` (Verilog -> JSON -> ASC -> BIN).
  */
object MoleTopVerilog extends App {
  SpinalConfig(
    targetDirectory = "gen",
    defaultClockDomainFrequency = FixedFrequency(48 MHz)
  ).generateVerilog(MoleTop(MoleConfig(), useBlackBox = true))
}
