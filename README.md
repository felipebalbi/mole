# Mole

**Mole** is an open MIPI I3C / I2C compliance and conformance test
rig --- a work-alike of the MCCI Model 2710 SuperMITT, scoped to
SDR + HDR-DDR (no HDR-Ternary, so no analog I3C PHY). It drives any
legal *or* illegal SDR / HDR-DDR / I2C sequence with deterministic
quarter-bit timing, observes the bus, injects errors exactly when
(and only when) the test author asked for them, plays controller
*or* target, and emulates well-known I2C / I3C peripherals at the
wire level.

The architecture is two layers:

- **Layer 0** --- a tiny, protocol-agnostic bit-cycle engine in the
  FPGA (**12 opcodes, 4 reserved slots, 16-bit fixed-width
  instructions**) that drives quarter-bit patterns on SDA / SCL
  and compares them against expectations. Knows nothing about I2C
  or I3C; per-bit drive is a bus-agnostic `tx_symbol` decoded
  against the active `BUS_MODE`. Plays controller *or* target.
- **Layer 1** --- a Scheme SDK on the host. All of I2C, I3C, CCC,
  HDR-DDR, peripheral emulation, and error injection lives here as
  Scheme source that compiles down to Layer-0 bytecode. The SDK
  *is* the living, diff-able encoding of the spec.

Error injection is **compile-time only**: the engine has zero
runtime randomness, so `ratio = 0` is exactly zero, not
"approximately zero", and `(seed, ratio)` reproduces a run byte
for byte.

## Product line

| SKU            | Tier                 | FPGA               | Scope                                       |
|----------------|----------------------|--------------------|---------------------------------------------|
| **Mole Verde** | pocket / per-dev     | iCE40 UP5K class   | I2C all modes + I3C SDR up to ~6 MHz SCL    |
| **Mole Rojo**  | bench / compliance   | ECP5-45K class     | full-rate I3C SDR + HDR-DDR + deep capture  |
| **Mole Negro** | future certification | CertusPro-NX class | full MIPI compliance catalog                |

All three share one Scheme SDK, one bytecode format, one ISA, and
one error-injection model.

## Repository layout

```text
.
├── AGENTS.md          ← contributor + AI-agent rules (read this)
├── ROADMAP.md         ← full design and rationale (source of truth)
├── README.md          ← you are here
├── crates/            ← Rust workspaces (host compiler, encoder,
│                        decoder, CLI, FFI, future Pico firmware)
└── fpga/              ← SpinalHDL projects (bit engine, UART,
                         SPRAM controller, per-board top-levels)
```

The layout is intentionally minimal --- sub-trees grow as roadmap
phases land. There will eventually be two Rust workspaces (host
tools vs. `no_std` Pico firmware); they will stay separate.

## Status

Phase 2 complete --- the `MoleTop` integration under
[`fpga/Mole/`](./fpga/Mole/) wires the UART loader, the
`BitCycleEngineCore`, the `SpramController`, and the result-ring
drainer into a single synthesisable top-level. A 3-state phase
FSM (`acceptLoad` -> `running` -> `draining`) gates `engine.start`,
SPRAM read-port ownership, and the UART RX drain so the host can
load a new program, the engine runs it to `HALT`, the drainer
sweeps the result ring back over UART TX, and the cycle restarts.
PLL (12 MHz -> 24 MHz) and SB_IO bypass-selectable for sim;
`MoleTopVerilog` ships `gen/MoleTop.v` synthesised under
`useBlackBox = true`. End-to-end coverage via `sim-top` (loader
+ engine + drainer in a single Verilator compile). Bring-up
procedure (build, flash, three smoke programs) in
[`fpga/Mole/BRINGUP.md`](./fpga/Mole/BRINGUP.md); wire format in
[`fpga/Mole/WIRE_FORMAT.md`](./fpga/Mole/WIRE_FORMAT.md).

Phase 1 (the `BitCycleEngineCore` ISA) shipped the full
controller-side ISA (`EMIT_BIT` / `EMIT_QUARTER` / `STRETCH_SCL`
/ `WAIT_ON` / `JMP` / `BRANCH_ON` / `SET_BUS_MODE` /
`LOAD_TIMING` / `MARK` / `HALT`) with quarter-bit pacing, four
`LOAD_TIMING`-addressable divider slots, sticky flag set
(MISMATCH / TIMEOUT / START / STOP), a sync'd bus observer, and
a result-ring writer (`Revision` + record stream + reserved
`HALT` slot at `resultLimit`). The target-role opcodes
(`SAMPLE_BIT_ON_SCL` / `DRIVE_BIT_ON_SCL`) and the four
v0.5-reserved opcode slots all trap to `HALT` status `0xF`.
Coverage: `sim-config`, `sim-opendrain`, eight UART block sims +
`sim-uart` loopback, `sim-spram`, `sim-isa`, `sim-engine-smoke`
(per-cycle bus-driver trace under `i3c-OD` vs `i3c-PP`),
`sim-engine-full` (17 named tests covering every opcode + the
trap paths + ring overflow), `sim-pll`, `sim-crc`, `sim-iobuf`,
`sim-loader-fsm`, `sim-drainer-fsm`, `sim-top`. Next up: Phase 3
(validation against a real DUT --- TMP108 over I2C). See
**ROADMAP.md §Phased plan** and
[`fpga/Mole/TODO.md`](./fpga/Mole/TODO.md) for current
bring-up state.

## Getting started (for contributors)

1. Read [`ROADMAP.md`](./ROADMAP.md) end-to-end. Especially the
   Layer-0 ISA section, the quarter-bit timing model, and the
   compile-time error-injection contract.
2. Read [`AGENTS.md`](./AGENTS.md) §3 (hard rules) and §4 (file
   conventions). LF endings, ~80-col wrap, and the "no new lint
   / build / test infrastructure without being asked" rule are
   real.
3. Pick an open phase and propose a change. The bytecode wire
   format is a stability contract; changes to it require a
   roadmap update in the same PR.

## Commit conventions

Mole follows
[Conventional Commits 1.0.0](https://www.conventionalcommits.org/en/v1.0.0/).
Commits take the form `<type>(<scope>): <subject>` (scope
optional). See [`AGENTS.md`](./AGENTS.md) §6 for the allowed
types, current scopes, breaking-change rules, and worked
examples.

Two Mole-specific addenda: AI-assisted commits must carry the
Copilot `Co-authored-by:` trailer, and `Signed-off-by:` trailers
are reserved for humans (the DCO is a human certification).

## License

TBD. No license file is present yet; treat the contents as
"all rights reserved" until one is added.

## Acknowledgements

The MCCI Model 2710 SuperMITT defined the shape of the problem
and remains the reference point for what "compliance test rig"
means in this space.
