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

The 10-opcode ISA (`EMIT_BIT`, `EMIT_QUARTER`, `STRETCH_SCL`,
`WAIT_SCL_RELEASE`, `WAIT_SDA_LOW`, `JMP`, `BRANCH_ON_MISMATCH`,
`HALT`, `MARK`, `LOAD_TIMING`) and its 16-bit encoding are
externally visible: the host compiler emits exactly this byte
format and every deployed Mole decodes it. Reordering opcodes,
shrinking fields, or repurposing reserved bits is a wire-format
break.

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

## Quarter-bit is the timing unit on the wire

The fabric clock *is* the quarter-bit clock. One fabric cycle =
one quarter-bit time. `LOAD_TIMING` controls how many fabric
cycles per quarter-bit at runtime (for pp / od / i2c frequency
selection); there is no sub-quarter-bit timing knob.

This means:
- Every state in the bit-cycle FSM advances on a quarter-bit
  boundary --- no half-bit, no "between quarters" state.
- Glitch injection happens by emitting `EMIT_QUARTER` with a
  drive value different from the surrounding quarters; the engine
  itself stays glitch-free.

## Open-drain primitive: `ReadableOpenDrain`, not `TriState`

Every bus wire (`io.bus.scl`, `io.bus.sda`) uses
`spinal.lib.io.ReadableOpenDrain[Bool]`. **Do not** replace with
`TriState`.

Rationale (same as in the I2c example project):
- `ReadableOpenDrain` has only `(write, read)` --- no
  `writeEnable` to forget, and you cannot accidentally drive a
  hard `1` because there is no second transistor to enable.
- Maps cleanly to an actual open-drain pad at synthesis (iCE40
  `SB_IO` open-drain mode).
- Two wires per line instead of three.

Polarity is **electrical, not logical**:
- `write := False` turns the open-drain NMOS on → pin tied to
  GND → bus low.
- `write := True` turns the NMOS off → pin floats → external
  pull-up wins → bus high.

The engine drives low and *releases* high. It must never drive
either line actively high. This is a hardware-safety rule; a
review that lands code violating it should be reverted on sight.

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

Bus lines come from `Reg(Bool())` regs at Component scope:

```scala
val sclDrive = Reg(Bool()) init(True)   // True = released
val sdaDrive = Reg(Bool()) init(True)
io.bus.scl.write := sclDrive
io.bus.sda.write := sdaDrive
```

States touch only the lines they change. Do **not** call
`io.bus.releaseAll()` at Component scope alongside the registered
drives --- last-assignment-wins makes `releaseAll()` clobber the
regs and the bus will never go low.

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
