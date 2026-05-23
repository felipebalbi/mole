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

| SKU            | Tier                 | FPGA               | Scope                        |
|----------------|----------------------|--------------------|------------------------------|
| **Mole Verde** | pocket / per-dev     | iCE40 UP5K class   | I2C + I3C SDR                |
| **Mole Rojo**  | bench / compliance   | ECP5-45K class     | adds HDR-DDR + deep capture  |
| **Mole Negro** | future certification | CertusPro-NX class | full MIPI compliance catalog |

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

Pre-Phase-0. The repo currently holds the design (`ROADMAP.md`),
the contributor rules (`AGENTS.md`), and empty `crates/` and
`fpga/` trees. Nothing builds yet. See **ROADMAP.md §Phased plan**
for what lands when.

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

## License

TBD. No license file is present yet; treat the contents as
"all rights reserved" until one is added.

## Acknowledgements

The MCCI Model 2710 SuperMITT defined the shape of the problem
and remains the reference point for what "compliance test rig"
means in this space.
