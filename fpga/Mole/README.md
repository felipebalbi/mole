# Mole --- FPGA bit-cycle engine (v0, iCEbreaker)

This is the v0 SpinalHDL project for **Mole**: the protocol-agnostic
bit-cycle engine that drives I3C / I2C compliance test programs at
quarter-bit resolution.

For the product-wide design and rationale, see
[`../../ROADMAP.md`](../../ROADMAP.md). The repo-wide conventions
live in [`../../AGENTS.md`](../../AGENTS.md); this project's
specifics are in [`AGENTS.md`](AGENTS.md).

Status: **Phase 3 in progress** --- Step 17 (TMP108 over hand-encoded
I²C) closed against a real DUT on PMOD1A; Step 18 (MCXA dev board
as I3C target soak) is the remaining v0 acceptance gate. The
Phase 2 `MoleTop` integration (UART loader -> bit engine ->
drainer) ships and is the bring-up substrate; smoke procedure in
[`BRINGUP.md`](BRINGUP.md). Outstanding work tracked in
[`TODO.md`](TODO.md).

## What this is

A SpinalHDL implementation of Layer 0 of the Mole architecture: a
small, register-mapped engine that executes a **15-opcode ISA**
(`EMIT_BIT`, `EMIT_QUARTER`, `STRETCH_SCL`, `WAIT_ON`,
`SET_BUS_MODE`, `SAMPLE_BIT_ON_SCL`, `DRIVE_BIT_ON_SCL`, `JMP`,
`BRANCH_ON`, `HALT`, `MARK`, `LOAD_TIMING`, `LOAD_LOOP`,
`DEC_BRANCH`, `SET_ROLE`; 17 reserved opcode slots) and drives
SDA / SCL with quarter-bit-resolution timing. Per-bit /
per-quarter drive is a bus-agnostic 2-bit `tx_symbol`
(`dominant` / `recessive` / `hiz` / reserved) decoded against
the active `BUS_MODE` register --- the engine has zero protocol
knowledge. The same engine plays controller (engine drives SCL)
or target (engine slaves to external SCL via `SAMPLE_BIT_ON_SCL`
/ `DRIVE_BIT_ON_SCL`), runtime-selectable via `SET_ROLE` so a
single bitstream serves both roles. Protocol semantics (I2C,
I3C, CCC, HDR-DDR, peripheral emulation) live in the host-side
Scheme SDK (Layer 1).

## What's in scope (v0)

- iCE40 UP5K-SG48 target on the iCEbreaker board (SG48 package).
- Host link via FT2232H channel A (`/dev/ttyUSB0`) for program
  upload and result-ring readback. No Pico, no external flash ---
  programs live in on-die SPRAM.
- I2C (all modes) and I3C SDR up to the fabric ceiling (~6 MHz SCL
  on the UP5K's 24 MHz fabric --- see ROADMAP §"Clocks").
- Same engine plays controller *or* target (runtime-selectable
  via `SET_ROLE` opcode; `MoleConfig.role` is the power-on
  default).
- Custom `MoleBus` bundle on SDA / SCL (`driveLow` + `driveHigh`
  + `read`), backing push-pull-capable iCE40 `SB_IO` pads with
  external pull-ups. OD vs PP is decoded at runtime from the
  active `BUS_MODE` register; PP-high is legal only under
  `i3c-PP` / `hdr-ddr` and is never used for SCL in target role.

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
  BRINGUP.md        end-to-end smoke procedure (build, flash, talk)
  TODO.md           phased bring-up plan + design notes per block
  WIRE_FORMAT.md    host link wire format (UART program upload + drain)
  Makefile
  build.sbt
  icebreaker.pcf    SDA/SCL on PMOD1A; UART on FT2232H ch.A
  src/
    hw/             synthesizable code
    sim/            SpinalSim testbenches
```

## Quickstart

```sh
cd fpga/Mole
make           # bitstream (Spinal -> yosys -> nextpnr -> icepack)
make sim       # run all sims
make flash     # program the iCEbreaker (FT2232H channel B)
```

`make help` lists every target. The Verilog top-level module is
`MoleTop`; the Scala entrypoint that generates it is
`mole.MoleTopVerilog`.

For the first end-to-end smoke of a freshly built bitstream
(connect a UART terminal, send a frame, watch the result drain),
follow [`BRINGUP.md`](BRINGUP.md).

## Hardware notes

- 12 MHz on-board oscillator; PLL pulls the fabric clock up to
  the v0 target frequency (see `MoleConfig`, Step 1).
- SDA / SCL exposed on PMOD1A (PMOD1A.1 → SCL, PMOD1A.2 → SDA).
  External 4.7 kΩ pull-ups to 3.3 V required --- not on die.
- USB-UART on FT2232H channel A (`/dev/ttyUSB0`); channel B is
  used by `iceprog` to program the bitstream. The host-link wire
  format (UART program upload, CRC, result drain) is fully
  specified in [`WIRE_FORMAT.md`](WIRE_FORMAT.md).
- Status LEDs (R / G / B) reused for engine state / heartbeat /
  HALT-status indication.

See `icebreaker.pcf` for the canonical pin assignment.
