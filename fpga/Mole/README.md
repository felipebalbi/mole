# Mole --- FPGA bit-cycle engine (v0, iCEbreaker)

This is the v0 SpinalHDL project for **Mole**: the protocol-agnostic
bit-cycle engine that drives I3C / I2C compliance test programs at
quarter-bit resolution.

For the product-wide design and rationale, see
[`../../ROADMAP.md`](../../ROADMAP.md). The repo-wide conventions
live in [`../../AGENTS.md`](../../AGENTS.md); this project's
specifics are in [`AGENTS.md`](AGENTS.md).

Status: **scaffold only.** No HDL yet --- the bring-up plan lives
in [`TODO.md`](TODO.md).

## What this is

A SpinalHDL implementation of Layer 0 of the Mole architecture: a
small, register-mapped engine that executes a ~10-opcode ISA
(`EMIT_BIT`, `EMIT_QUARTER`, `STRETCH_SCL`, `WAIT_SCL_RELEASE`,
`WAIT_SDA_LOW`, `JMP`, `BRANCH_ON_MISMATCH`, `HALT`, `MARK`,
`LOAD_TIMING`) and drives SDA / SCL with quarter-bit-resolution
timing. No I2C or I3C knowledge lives here --- protocol semantics
live in the host-side Scheme SDK (Layer 1).

## What's in scope (v0)

- iCE40 UP5K-SG48 target on the iCEbreaker board (SG48 package).
- Host link via FT2232H channel A (`/dev/ttyUSB0`) for program
  upload and result-ring readback. No Pico, no external flash ---
  programs live in on-die SPRAM.
- I2C (all modes) and I3C SDR up to the fabric ceiling (~12 MHz
  I3C with the UP5K's ~48 MHz fabric --- see ROADMAP §"Clocks").
- Same engine plays controller *or* target (selected by host).
- Open-drain SDA / SCL using Spinal's `ReadableOpenDrain[Bool]`
  primitive. The engine **never** drives these lines actively
  high.

## What's out of scope (v0)

- HDR-DDR (lands on the ECP5-45K bench tier --- v2).
- HDR-Ternary (would need an analog PHY).
- USB / postcard-rpc / RP2350 transport (v1 polish layer).
- Programmable VIO / pull-ups (v2 PHY daughter card).

## Layout

```
Mole/
  README.md         this file
  AGENTS.md         project-specific conventions
  TODO.md           phased bring-up plan + design notes per block
  Makefile
  build.sbt
  icebreaker.pcf    SDA/SCL on PMOD1A; UART on FT2232H ch.A
  src/
    hw/             synthesizable code
    sim/            SpinalSim testbenches
```

## Quickstart

Once there's something to build (see `TODO.md` for the first
milestone that produces a flashable bitstream):

```sh
cd fpga/Mole
make           # bitstream
make sim       # run all sims
make flash     # program the iCEbreaker
```

`make help` lists every target.

The Verilog top-level module is `MoleTop`; the Scala entrypoint
that generates it is `mole.MoleTopVerilog`.

## Hardware notes

- 12 MHz on-board oscillator; PLL pulls the fabric clock up to
  the v0 target frequency (see `MoleConfig`, Step 1).
- SDA / SCL exposed on PMOD1A (PMOD1A.1 → SCL, PMOD1A.2 → SDA).
  External 4.7 kΩ pull-ups to 3.3 V required --- not on die.
- USB-UART on FT2232H channel A (`/dev/ttyUSB0`); channel B is
  used by `iceprog` to program the bitstream.
- Status LEDs (R / G / B) reused for engine state / heartbeat /
  HALT-status indication.

See `icebreaker.pcf` for the canonical pin assignment.
