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

- **Layer 0** --- a tiny, protocol-agnostic bit-cycle engine in
  the FPGA (**26 opcodes across three live groups: 10 WIRE + 8
  CTRL + 8 DATA, plus the LOOP group fully reserved; 32-bit
  fixed-width instructions**) that drives quarter-bit patterns on
  SDA / SCL and compares them against expectations. Knows nothing
  about I2C or I3C; per-bit drive is a bus-agnostic `tx_symbol`
  decoded against the active `BUS_MODE`. Plays controller *or*
  target (runtime-selectable via `SET_ROLE`; single bitstream for
  both). Eight 32-bit general-purpose registers (`R0`..`R7`);
  bounded loops via `LOAD_IMM R6, n` (or `LOAD_LOOP n` sugar) +
  `DEC` + `BRANCH_ON NOT_REG_ZERO`. Any GP register pair gives
  arbitrary nesting depth.
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
├── Cargo.toml         ← host-tools Cargo workspace at the root
├── mole-asm/          ← bytecode compiler library + mdBook tutorial
├── mole-asm-cli/      ← `mole-asm` CLI (clap + color-eyre)
├── (future) firmware/ ← no_std Pico firmware (its own workspace)
└── fpga/              ← SpinalHDL projects (bit engine, UART,
                         SPRAM controller, per-board top-levels)
```

The layout is intentionally minimal --- sub-trees grow as roadmap
phases land. New host-side crates land at the repo root alongside
`mole-asm/`. There will eventually be two Rust workspaces (host
tools at the root, `no_std` Pico firmware under `firmware/`); they
will stay separate.

## Status

**v0 (15 opcodes, 16-bit instructions, `BitCycleEngineCore`) is
retired.** Phase 1 / Phase 3 closed against silicon: Step 17
(TMP108 over hand-encoded I²C) ran on the iCEbreaker UP5K at 24
MHz fabric. That bench session drove a set of host-side fixes
(result-ring trailing-garbage tag-walk, ring-bytes default
mismatch, strict ring decode with length check, transport
hardware flow control on the builder, mandatory `crtscts` in
`BRINGUP.md`) that the sim coverage did not catch.

**v0.2 (26 opcodes, 32-bit instructions, `EnginePipeline`) is
the live engine, REVISION 0.1.1 on silicon.** Phase C in
progress --- a 5-stage F1 / F2 / D / R / X / W pipeline, 32-bit-
grain result ring (REVISION + CAPTURE + MARK + HALT records),
explicit `_IMM` / `_REG` opcode variants (`EMIT_BIT_IMM`,
`EMIT_BYTE_IMM`, etc.), hazard-free `DEC` + `BRANCH_ON
NOT_REG_ZERO` loop idiom, and per-program reset on
`io.engineStart` rising edge (back-to-back program runs without
an FPGA power cycle). REVISION 0.1.1 silicon is bench-validated
under the sustained-load `tmp108-loop.moleasm` soak (1000
iterations, 2046-record ring fill + designed overflow). Phase
C.11 (MCXA268 I3C target soak under v0.2) is the remaining v0.2
acceptance gate; once it closes, the Scheme SDK (ROADMAP
§"Phase 2") can begin on a stable engine + host substrate.

The integration substrate underneath: the `MoleTop` top-level
under [`fpga/Mole/`](./fpga/Mole/) wires the UART loader, the
`EnginePipeline` (v0.2; the v0 `BitCycleEngineCore` is stashed
under `fpga/Mole/src/attic/`), the `SpramController`, and the
result-ring drainer into a single synthesisable design. A
3-state phase FSM (`acceptLoad` -> `running` -> `draining`)
gates `engine.start`, SPRAM read-port ownership, and the UART
RX drain so the host can load a new program, the engine runs
it to `HALT`, the drainer sweeps the result ring back over UART
TX, and the cycle restarts. PLL (12 MHz -> 24 MHz) and SB_IO
bypass-selectable for sim; `MoleTopVerilog` ships `gen/MoleTop.v`
synthesised under `useBlackBox = true`. End-to-end coverage via
`sim-top` (loader + engine + drainer in a single Verilator
compile). Bring-up procedure (build, flash, smoke programs) in
[`fpga/Mole/BRINGUP.md`](./fpga/Mole/BRINGUP.md); wire format in
[`fpga/Mole/WIRE_FORMAT.md`](./fpga/Mole/WIRE_FORMAT.md).

The v0.2 `EnginePipeline` ships the full controller-side ISA
(`EMIT_BIT_IMM` / `EMIT_BIT_REG` / `EMIT_QUARTER_IMM` /
`EMIT_QUARTER_REG` / `EMIT_BYTE_IMM` / `EMIT_BYTE_REG` /
`STRETCH_SCL_IMM` / `STRETCH_SCL_REG` / `WAIT_ON` / `BRANCH_ON`
/ `SET_BUS_MODE` / `SET_ROLE` / `LOAD_TIMING` / `FLAG_CLEAR` /
`MARK` / `HALT` plus the eight DATA-group opcodes for the 32-bit
register file) with quarter-bit pacing, four
`LOAD_TIMING`-addressable divider slots, sticky flag set
(MISMATCH / TIMEOUT / START / STOP / REG_ZERO), a sync'd bus
observer, and a 32-bit-grain result-ring writer (`REVISION` +
CAPTURE + MARK record stream + reserved HALT slot at
`resultLimit`). The target-role opcodes (`SAMPLE_BIT_ON_SCL` /
`DRIVE_BIT_ON_SCL`) execute under `SET_ROLE target`. Reserved
slots and the LOOP group all trap to HALT with `STATUS_TRAP`
(`0x1F`). Per-program reset on the `io.engineStart` rising edge
clears `haltedReg`, `revisionPending`, `ringWrPtr`,
`ringOverflow`, `pcReg`, and the sticky flag regs so back-to-
back program loads run without an FPGA power cycle. Coverage:
32 `make sim` targets including the 5-stage-pipeline scaffold
(`sim-engine-pipeline`, 15 named cases through the v0.2 ISA),
end-to-end loader+engine+drainer integration (`sim-top`,
`sim-top-flow-control`, `sim-top-cts-violation`), and the
per-block UART / SPRAM / CRC / IO-buffer audits inherited from
the v0 floor. See **ROADMAP.md §Phased plan** and
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
   format becomes a stability contract once Phase 0 ships its
   first tagged encoder release (per AGENTS.md §3.17); until
   then, format changes are free but must update
   `docs/MOLE-0.2-SPEC.md` in the same PR.

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

Licensed under either of

- Apache License, Version 2.0
  ([LICENSE-APACHE](./LICENSE-APACHE) or
  https://www.apache.org/licenses/LICENSE-2.0)
- MIT license
  ([LICENSE-MIT](./LICENSE-MIT) or
  https://opensource.org/licenses/MIT)

at your option.

This is the standard Rust-ecosystem dual license. The workspace
declares `license = "MIT OR Apache-2.0"` in
[`Cargo.toml`](./Cargo.toml) and every member crate inherits it.

### Contribution

Unless you explicitly state otherwise, any contribution
intentionally submitted for inclusion in Mole by you, as defined
in the Apache-2.0 license, shall be dual licensed as above,
without any additional terms or conditions.

## Acknowledgements

The MCCI Model 2710 SuperMITT defined the shape of the problem
and remains the reference point for what "compliance test rig"
means in this space.
