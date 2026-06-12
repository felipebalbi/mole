# Mole --- FPGA bit-cycle engine (v0.2, iCEbreaker)

This is the SpinalHDL project for **Mole**: the protocol-agnostic
bit-cycle engine that drives I3C / I2C compliance test programs at
quarter-bit resolution.

For the product-wide design and rationale, see
[`../../ROADMAP.md`](../../ROADMAP.md). The repo-wide conventions
live in [`../../AGENTS.md`](../../AGENTS.md); this project's
specifics are in [`AGENTS.md`](AGENTS.md). The normative ISA and
wire-format spec is
[`../../docs/MOLE-0.2-SPEC.md`](../../docs/MOLE-0.2-SPEC.md).

Status: **Phase C complete, awaiting v0.2 hardware acceptance gate
(C.11).** The v0 engine closed Step 17 (TMP108 over hand-encoded
I²C) against silicon and was retired; Phase C reworks the engine
in-place on branch `v0.2` to the 26-opcode ISA with a 5-stage
pipeline. C.1–C.10 landed under sim coverage (29/29 sim targets
green via `make sim`); C.11 (iCEbreaker smoke + TMP108 regression
+ MCXA268 I3C target soak) requires hands-on bring-up on real
hardware and is the v0.2 acceptance gate. See
[`TODO.md`](TODO.md) §"Step 18 / Phase C.11" for the hand-off
contract.

## What this is

A SpinalHDL implementation of Layer 0 of the Mole architecture: a
small, register-mapped engine that executes the **26-opcode v0.2
ISA** (10 WIRE + 8 CTRL + 8 DATA opcodes; LOOP group fully
reserved; 38 reserved sub-slots) and drives SDA / SCL with
quarter-bit-resolution timing. Instructions are **32-bit
fixed-width**; opcode field is `{group[31:30], sub[29:26]}`; flag
triple is at `[2:0]` on the eight flag-bearing opcodes. The full
encoding is in [`../../docs/MOLE-0.2-SPEC.md`](../../docs/MOLE-0.2-SPEC.md).

Per-bit / per-quarter drive is a bus-agnostic 2-bit `tx_symbol`
(`dominant` / `recessive` / `hiz` / reserved) decoded against the
active `BUS_MODE` register --- the engine has zero protocol
knowledge. The same engine plays controller (engine drives SCL)
or target (engine slaves to external SCL via `SAMPLE_BIT_ON_SCL`
/ `DRIVE_BIT_ON_SCL`), runtime-selectable via `SET_ROLE` so a
single bitstream serves both roles. Protocol semantics (I2C,
I3C, CCC, HDR-DDR, peripheral emulation) live in the host-side
SDK (Layer 1).

The engine is a 5-stage `spinal.lib.misc.pipeline` pipeline
(F1 / F2 / D / R / X / W with the F1+F2 split absorbing the
SPRAM 1-cycle read latency) with an 8 × 32-bit register file
(`R0`–`R7`; W→R bypass mux per port; load-use stall exported).
The X stage hosts an inline mini-FSM that executes each
WIRE / CTRL / DATA opcode; the W stage owns ring writes and
sticky-flag updates.

## What's in scope (v0.2)

- iCE40 UP5K-SG48 target on the iCEbreaker board (Verde dev
  surface).
- Host link via FT2232H channel A (`/dev/ttyUSB0` on Linux) for
  program upload and result-ring readback. Programs live in
  on-die SPRAM (4096 × 32-bit words, two `SB_SPRAM256KA` tiles
  in parallel for 32-bit grain).
- I2C (all modes) and I3C SDR up to the fabric ceiling. Verde
  fabric clock is **24 MHz** `engineClk` = 24 MHz `uartClk`
  (1:1 ratio off `PLLOUTGLOBAL`); see "Verde retarget" in
  [`TODO.md`](TODO.md) for the X.1–X.5 plateau history that
  drove the call to drop back to 24 MHz from the 48 MHz
  aspiration.
- Same engine plays controller *or* target (runtime-selectable
  via `SET_ROLE` opcode; `MoleConfig.role` is the power-on
  default).
- Custom `MoleBus` bundle on SDA / SCL (`driveLow` + `driveHigh`
  + `read`), backing push-pull-capable iCE40 `SB_IO` pads with
  external pull-ups. OD vs PP is decoded at runtime from the
  active `BUS_MODE` register; PP-high is legal only under
  `i3c-PP` / `hdr-ddr` and is never used for SCL in target role.

## What's out of scope (v0.2)

- HDR-DDR (lands on the ECP5-45K bench tier --- Rojo).
- HDR-Ternary (would need an analog PHY).
- USB / postcard-rpc / RP2350 transport (later polish layer).
- Programmable VIO / pull-ups (PHY daughter card; future SKU).
- The v0.5 reservations: `CAPTURE_RUN`, `CALL`, `RET` opcodes;
  `raw_override` (the `tx_symbol = 0b11` escape encoding).
  `FLAG_CLEAR` lived under the v0.5 banner earlier but landed
  in C.8.2 and is now live in v0.2.

## Layout

```
Mole/
  README.md         this file
  AGENTS.md         project-specific conventions (v0.2-aware)
  BRINGUP.md        end-to-end smoke procedure (build, flash, talk)
  TODO.md           phased bring-up plan + design notes per block
  WIRE_FORMAT.md    host link wire format (UART program upload + drain)
  Makefile
  build.sbt
  icebreaker.pcf    SDA/SCL on PMOD1A; UART on FT2232H ch.A
  src/
    hw/             synthesizable code (5-stage pipeline, 32-bit ISA)
    sim/            SpinalSim testbenches (29 targets)
    attic/          v0 stashed sources pending C.11.b resurrection
```

## Quickstart

```sh
cd fpga/Mole
make           # bitstream (Spinal -> yosys -> nextpnr -> icepack)
make sim       # run all 29 sims
make flash     # program the iCEbreaker (FT2232H channel B)
```

`make help` lists every target. The Verilog top-level module is
`MoleTop`; the Scala entrypoint that generates it is
`mole.MoleTopVerilog`. Other useful targets: `make gui` (nextpnr
Qt place-and-route viewer; Linux / oss-cad-suite only) and
`make report` (machine-readable timing + utilisation JSON).

For the first end-to-end smoke of a freshly built bitstream
(connect a UART terminal, send a frame via `mole-loader-cli`,
watch the result drain via `mole-loader`), follow
[`BRINGUP.md`](BRINGUP.md). The host-side wire format is fully
specified in [`WIRE_FORMAT.md`](WIRE_FORMAT.md).

## Hardware notes

- 12 MHz on-board oscillator; PLL multiplies to **24 MHz**
  fabric clock (`MolePllUp5k`: VCO 384 MHz, DIVQ=4, both
  `clkOutEngine` and `clkOutUart` tapped off `PLLOUTGLOBAL`).
  The 1:1 ratio means there is no CDC inside the Verde
  bitstream today; the framework stays in place for Rojo
  (ECP5-45K) which targets a 2:1 ratio for headroom.
- SDA / SCL exposed on PMOD1A (PMOD1A.1 → SCL, PMOD1A.2 → SDA).
  External 4.7 kΩ pull-ups to 3.3 V required for I2C; 1 kΩ to
  1.8 V for I3C-OD windows. Not on die.
- USB-UART on FT2232H channel A (`/dev/ttyUSB0` on Linux);
  channel B is used by `iceprog` to program the bitstream.
  Hardware flow control (`crtscts`) is mandatory --- see
  [`WIRE_FORMAT.md`](WIRE_FORMAT.md).
- Status LEDs named by FUNCTION (`io_ledFault` /
  `io_ledRunning` / `io_ledHeartbeat`) so a future board respin
  that swaps physical LED colors does not invalidate the
  software. See `icebreaker.pcf` for the function → pin →
  physical-LED mapping.

See `icebreaker.pcf` for the canonical pin assignment.
