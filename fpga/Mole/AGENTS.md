# Mole/fpga/Mole --- AGENTS.md

Project-specific conventions for the Mole v0 SpinalHDL bit-cycle
engine. The repo-wide rules in `../../AGENTS.md` still apply; this
file adds Mole-engine-only ones.

## Status pointer

The current bring-up status lives in `TODO.md`:
- Check the `## ✅ Done` checklist near the top.
- Skim the highest-numbered `### 🔲 Step N` block before starting
  new work --- the hints there are the design contract for the
  next step (Goal, Suggested IO, Design notes, Sim hints,
  Makefile).

Step numbering is **sequential across phases** starting at Step 1,
not reset per phase.

## Top-level naming contract

These names are externally visible (the host compiler / runner /
ROADMAP all reference them). Do not rename without a roadmap
update:

- Scala package: `mole`
- Generated Verilog module: `MoleTop`
- Verilog-generation entrypoint: `mole.MoleTopVerilog`
- Sim entrypoints follow `mole.<Block>Sim` (e.g.
  `mole.BitCycleEngineSim`)

`Makefile`'s `gen`, `make all`, and the eventual host-side
synthesis scripts all key off `MoleTop` / `MoleTopVerilog`.

## No cross-project sbt deps

Unlike the sibling icebreaker-spinalhdl-examples projects, Mole
owns its UART, its SPRAM controller, and its bit engine in-tree.
There is **no** `ProjectRef` to a sibling sbt project, and there
should not be one --- Mole is the engine repo, not a consumer of
the examples repo.

If a sibling repo (e.g. `icebreaker-spinalhdl-examples/Uart`) has
a useful IP, **copy the minimum needed code locally** with a
header comment crediting the source. This matches the repo-wide
rule in `../../AGENTS.md` §3.4.

## ISA is a stable contract

The 11-opcode ISA (`EMIT_BIT`, `EMIT_QUARTER`, `STRETCH_SCL`,
`WAIT_SCL_RELEASE`, `WAIT_SDA_LOW`, `SET_BUS_MODE`, `JMP`,
`BRANCH_ON_MISMATCH`, `HALT`, `MARK`, `LOAD_TIMING`) and its
16-bit encoding are externally visible: the host compiler emits
exactly this byte format and every deployed Mole decodes it.
Reordering opcodes, shrinking fields, or repurposing reserved
bits is a wire-format break.

If the ISA truly needs to change:
1. Bump a bytecode-format version word at the top of every
   program.
2. Update `ROADMAP.md` §"Layer 0 --- the bit-cycle engine".
3. Update the host encoder (the Rust crate under `../../crates/`)
   in the same PR.

The `LOAD_REG` / `BRANCH_ON_CAPTURED_MASK` / `CAPTURE_RUN` /
`CALL` / `RET` opcodes are **reserved for v0.5** (see ROADMAP).
Do not implement them in v0 even if a step seems to want them ---
add the requirement to the v0.5 plan instead.

**`EMIT_BYTE` (and any byte-level / word-level emit) is
explicitly rejected**, not deferred. See ROADMAP §"Why no
byte-level emit". A "byte" on the wire is 9 bits, not 8, and the
9th is structurally different from the first 8 --- the SDK
encodes `write-byte` as a macro that expands to 9 `EMIT_BIT`s
with correct per-bit operands. Treat any review that re-proposes
`EMIT_BYTE` as a sign the SDK needs a new macro, not the engine
a new opcode.

**Putting SDA OD/PP into `BUS_MODE` is also explicitly rejected.**
SDA drive style flips inside a wire byte (the 9th-bit asymmetry:
target drives ACK after controller drives 8 data bits); a mode
register tracking this would have to become a per-byte FSM ---
the exact "protocol-aware shortcut" the ROADMAP rejects. SCL drive
style is per-frame-phase (slow state, lives in `BUS_MODE`); SDA
drive style is per-bit (fast data, lives in the bitstream). See
ROADMAP §"Why SDA does *not* live in `BUS_MODE`".

## Quarter-bit is the timing unit on the wire

The fabric clock *is* the quarter-bit clock. One fabric cycle =
one quarter-bit time. `LOAD_TIMING` controls how many fabric
cycles per quarter-bit at runtime (for pp / od / i2c frequency
selection); `SET_BUS_MODE` picks which of those divider words is
active. There is no sub-quarter-bit timing knob.

This means:
- Every state in the bit-cycle FSM advances on a quarter-bit
  boundary --- no half-bit, no "between quarters" state.
- `EMIT_BIT` carries the **canonical bit shape** for all 4
  quarters: SCL low / low / high / high (engine-generated, not
  in the bitstream), SDA held at `bit_value` throughout (per the
  `drive_sda` field). The SDK emits one `EMIT_BIT` per wire bit
  and never has to reason about the SCL waveform. See ROADMAP
  §"Canonical EMIT_BIT shape" for the wire-level contract.
- **SCL is engine-generated during `EMIT_BIT`, bitstream-
  controlled during `EMIT_QUARTER`.** This is the only path to
  per-quarter SCL control; `EMIT_BIT`'s bitstream does not carry
  an SCL drive field. The engine's `SclWaveformGen` reads
  `BUS_MODE.mode[2]` to choose OD-release vs PP-high for the
  high half of every `EMIT_BIT`.
- Glitch injection (and any other per-quarter deviation from the
  canonical shape, including SCL glitches) happens by emitting 4
  explicit `EMIT_QUARTER`s in place of one `EMIT_BIT`. The engine
  itself stays glitch-free --- the bitstream encodes the shape.
- `EMIT_BIT` and `EMIT_QUARTER` coexist deliberately: see the
  ISA-contract section above and ROADMAP §"Why not
  `EMIT_QUARTER`-only?" for the asymmetry that justifies keeping
  both.

## Open-drain primitive: custom `MoleBus`, not `ReadableOpenDrain`

Every bus wire (`io.bus.scl`, `io.bus.sda`) uses Mole's custom
`MoleBus` bundle (lands with Step 2): three signals per line ---
`driveLow`, `driveHigh`, `read`. **Do not** replace with stock
`spinal.lib.io.ReadableOpenDrain[Bool]` or `TriState`.

Rationale:
- `ReadableOpenDrain` exposes only `(write, read)` --- it cannot
  express I3C push-pull mode where the pad actively drives high
  instead of releasing. The 3-signal bundle splits the two so the
  per-bit `drive_high` flag from the `EMIT_*` instruction routes
  directly to the right pin without an inferred mode register.
- Sister project `icebreaker-spinalhdl-examples/I2c` uses
  `ReadableOpenDrain` because it only ever speaks I²C. Mole has to
  speak both, so the primitive necessarily diverges. This is a
  deliberate divergence, documented in Step 2's "What landed"
  block when it ships.
- Maps cleanly to iCE40 `SB_IO` in push-pull mode with
  output-enable driven by `driveLow | driveHigh`. Pull-ups stay
  external so OD "1" still works.

Polarity rules:
- `driveLow := True` → NMOS pull-down on → pin at GND → bus low.
- `driveHigh := True` → PMOS pull-up on → pin at VIO → bus high
  (push-pull). Pull-up resistor is irrelevant in this state.
- Both `False` → output-enable off → pin floats → external
  pull-up wins → bus high (open-drain release).
- Both `True` → **bus contention.** Illegal. Asserted out in
  sim, and the engine's `drive` decoder should never produce it
  (the 3-bit field has no encoding for both).

Three rules:
- The engine drives low or drives high based on the **per-bit
  `drive_high` flag**; it does not have a "mode" register.
- "Release the bus" is `driveLow := False; driveHigh := False`
  (both NMOS and PMOS off). This is the default at reset and
  between programs.
- For I2C / I3C OD operation the SDK sets `drive_high = 0` on
  every bit, so the PMOS never fires --- electrically identical
  to a classic open-drain bus.

## Compile-time deterministic engine

No randomness lives in the FPGA. All PRNG belongs in the host
compiler. Reviewers should treat any `Random`, LFSR-as-PRNG, or
"jitter source" in the engine as a bug.

The engine reports back to the host:
- The bit values it sampled on read-enabled `EMIT_*` instructions.
- The mismatch flag set by the most recent `EMIT_*` against its
  expect mask.
- `MARK` records (label + implicit timestamp).
- `HALT` status code.

That is the whole result-ring contract.

## Bus-shaped FSM idiom

Same pattern the I2c example project codified (and named in its
`AGENTS.md`):

### Registered drivers, not per-state combinational drives

Bus lines come from `Reg(Bool())` regs at Component scope, one
pair per line (`driveLow` + `driveHigh`):

```scala
val sclDriveLow  = Reg(Bool()) init(False)  // NMOS off  = released
val sclDriveHigh = Reg(Bool()) init(False)  // PMOS off  = released
val sdaDriveLow  = Reg(Bool()) init(False)
val sdaDriveHigh = Reg(Bool()) init(False)
io.bus.scl.driveLow  := sclDriveLow
io.bus.scl.driveHigh := sclDriveHigh
io.bus.sda.driveLow  := sdaDriveLow
io.bus.sda.driveHigh := sdaDriveHigh
```

States touch only the lines they change. Do **not** add a
`releaseAll()` helper that drives the regs at Component scope
alongside per-state writes --- last-assignment-wins makes the
release clobber the regs and the bus will never go low.

### Edge on entry, dwell in active

Each state owns one bus edge and one dwell:

```scala
someState.onEntry {
  someDrive    := <new value>          // the edge
  phaseCounter := timing.<spec value>  // dwell until next edge
}
someState.whenIsActive {
  when(phaseCounter === 0) { goto(nextState) }
  .otherwise              { phaseCounter := phaseCounter - 1 }
}
```

Reading the states top-to-bottom gives the bus waveform.

### Compile-time toggles via Scala `if`, not Spinal `when`

Optional features keyed off `MoleConfig` (e.g. capture-ring depth,
WAIT-SCL-RELEASE wired-in vs gated out) use a Scala-time `if` so
the optional logic disappears entirely from the synthesised
design when the toggle is `false`. A Spinal `when(...)` would
still emit the gating and the sense wire.

## REVISION word convention

The engine reports a `REVISION` word in its result ring on demand
(`MARK` with a reserved label id, or as the first word emitted on
reset). Layout is "version-shaped":

```
[31:24] = major
[23:16] = minor
[15: 0] = patch
```

Values come from the Makefile via JVM system properties
(`-Drevision.major=...`), read on the Scala side in
`src/hw/Revision.scala` (lands with Step 8). The defaults baked
into `Revision.scala` **must stay in lockstep** with the Makefile
so a bare `sbt runMain ...` outside `make` still produces the
canonical version.

## Step closeout convention

When closing a step:
1. Tick its checkbox in `## ✅ Done` (or add the line if absent).
2. Convert its `### 🔲 Step N` hint block into `### ✅ Step N`
   with a "What landed" body. Include:
   - **Files** changed / created.
   - **Divergence from the hint**, with rationale.
   - **Sim** notes --- companion file path and what it covers.
   - **Makefile** --- the new `sim-<name>` target name (and
     uncomment its declaration up top).
3. Bump `README.md`'s status line if visible state changed.

## Hardware bring-up gating

Sim-only completion does not equal step done for any step whose
hint says it ships hardware. The MCXA-as-DUT bring-up (Phase 3)
is the gate for declaring v0 done --- not the final smoke sim.
