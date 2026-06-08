package mole

import spinal.core._

/** Verilog generation entrypoint for [[MoleTop]] (v0.2).
  *
  * Emits `MoleTop.v` into the `gen/` directory (created on demand by the
  * Makefile, gitignored). Always uses `useBlackBox = true` so yosys infers
  * `SB_PLL40_PAD`, `SB_IO`, and `SB_SPRAM256KA` as iCE40 hard cells.
  *
  * ==v0.2 changes vs v0 stash==
  *
  *   - `defaultClockDomainFrequency` set to 48 MHz (the engineCd domain).
  *   - The generated top-level module is still `MoleTop` (naming contract per
  *     `fpga/Mole/AGENTS.md` § "Top-level naming contract").
  *
  * Run via `make gen` or directly:
  * {{{
  *   sbt -batch "runMain mole.MoleTopVerilog"
  * }}}
  *
  * Build full bitstream via `make all` (Verilog → JSON → ASC → BIN).
  */
object MoleTopVerilog extends App {
  SpinalConfig(
    targetDirectory = "gen",
    defaultClockDomainFrequency = FixedFrequency(48 MHz)
  ).generateVerilog(MoleTop(MoleConfig(), useBlackBox = true))
}
