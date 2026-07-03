<!--
SPDX-FileCopyrightText: 2026 Felipe Balbi
SPDX-License-Identifier: CERN-OHL-W-2.0
-->

# Mole/fpga/Mole --- AGENTS.md

Project-specific conventions for the Mole v0.2 SpinalHDL bit-cycle
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

The **in-development format is v0.2**: a **26-opcode ISA** (32-bit
fixed-width instructions, opcode `{group[31:30], sub[29:26]}`; 10
WIRE + 8 CTRL + 8 DATA opcodes; LOOP group fully reserved) with
magic `0x0002_4D4C` and format version `0x0002`. The normative
encoding is `docs/MOLE-0.2-SPEC.md`; that file wins whenever
this AGENTS.md and the spec disagree.

### Pre-Phase-0 mutability caveat

Until the first tagged Phase 0 encoder release the v0.2 wire
format is **NOT** a stable contract --- any field may change
without a version bump. See root `AGENTS.md` §3.17 and
`docs/MOLE-0.2-SPEC.md` §1 and §10 for the full caveat.

The v0 engine (15 opcodes, 16-bit instructions) ran on real
hardware through Step 17 (TMP108 I2C closed). Phase C reworks
the engine to v0.2 in-place; do not treat v0 encoding as the
current target. **v0 bytecode is not cross-compatible with the
v0.2 engine or encoder.**

### Post-tagging discipline (applies once Phase 0 tags)

Once Phase 0 ships its first tagged encoder release, bytecode is
a stable contract between the host compiler and every deployed
Mole. From that point:

1. Bump a bytecode-format version word at the top of every
   program when the encoding changes.
2. Update `ROADMAP.md` §"Layer 0 --- the bit-cycle engine" and
   `docs/MOLE-0.2-SPEC.md`.
3. Update the host encoder (the Rust `mole-asm` crate at
   `../../mole-asm/`, with its CLI front-end in
   `../../mole-asm-cli/`) in the same PR.
4. Mark the commit `feat(engine)!:` with a `BREAKING CHANGE:`
   footer. See root `AGENTS.md` §6.

### Opcode group table (v0.2)

| Group | Bits `[31:30]` | Live opcodes | Notes                   |
|-------|----------------|--------------|-------------------------|
| WIRE  | `0b00`         | 10           | Emit/sample/stretch     |
| CTRL  | `0b01`         | 8            | Branch, wait, config    |
| DATA  | `0b10`         | 8            | ALU, load, move         |
| LOOP  | `0b11`         | 0            | Fully reserved          |

Full sub-opcode lists are in `docs/MOLE-0.2-SPEC.md` §4.

### Retired v0 vocabulary (do not reintroduce)

The v0 ISA used unsuffixed `EMIT_BIT`, `EMIT_QUARTER`, and
`STRETCH_SCL`; a fused `DEC_BRANCH`; dedicated `LOAD_LOOP` and
`MARK` opcodes at specific slot numbers; and a 16-bit instruction
width with opcode at `[15:11]`. All of these are retired in v0.2:

- `EMIT_BIT` / `EMIT_QUARTER` / `STRETCH_SCL` → replaced by
  explicit `_IMM` / `_REG` variants. The unsuffixed names are
  not live opcodes in v0.2; the assembler rejects them.
- `DEC_BRANCH` → retired. Use separate `DEC Rx` followed by
  `BRANCH_ON REG_ZERO, <label>`. There are no `LCR0`/`LCR1`
  dedicated loop-counter registers; R6 is just a GP register
  conventionally used for loops.
- `LOAD_LOOP n` is **assembler sugar** for `LOAD_IMM R6, n`
  (spec §12.3). It is a legal mnemonic in source. It does NOT
  correspond to a hardware opcode; do not add one.
- 16-bit instruction width and opcode at `[15:11]` are retired.
  v0.2 is 32-bit fixed; opcode is `{group[31:30], sub[29:26]}`.

### EMIT_BYTE and byte-level emits

Byte emission is **two live opcodes in v0.2**, both in the WIRE
group, sharing a 9-cell running state in the X stage:

- **`EMIT_BYTE_REG`** (sub `0b0100`) shifts out `R7[7:0]` MSB
  first with full SCL pulses, then clocks an ACK/NAK slot with
  `hiz` SDA. See spec §5.5.
- **`EMIT_BYTE_IMM`** (sub `0b1001`) carries its 8-bit payload
  in the instruction word (`[10:3]`) and otherwise behaves
  identically: same shift order, same ACK slot, same flag
  triple semantics. No `R7` load is required. See spec §5.5b.

The motivation for the IMM variant is the common SDK case of
emitting a known byte (address, CCC, register pointer): without
IMM, every byte costs `LOAD_IMM R7, <byte>; EMIT_BYTE_REG`
(two instructions and a load-use hazard window); a typical I2C
write of 3–6 bytes thus pays 6–12 instructions of glue. With
IMM the same write is 3–6 instructions flat with no R7 traffic.

In moleasm, the bare mnemonic `EMIT_BYTE` (no suffix) was sugar
for `EMIT_BYTE_REG` in early v0.2 drafts. It is **retired**: the
assembler now raises E-LEX-006 and points at both canonical forms.
The bare form was the only EMIT_* opcode that allowed an
unsuffixed mnemonic; removing it makes the WIRE-group naming
convention uniform with `EMIT_BIT_*`, `EMIT_QUARTER_*`, and
`STRETCH_SCL_*`. Use `EMIT_BYTE_IMM` when the byte is known at
compile time (the common case for I2C addresses, CCC, register
pointers) and `EMIT_BYTE_REG` when the byte comes from R7
(typical of write-then-read flows where the byte is fetched
mid-program).

Any review proposing a higher-level byte emit beyond what §5.5
and §5.5b already define should be treated as a sign the SDK
needs a new macro, not the engine a new opcode.

### tx_symbol and BUS_MODE are separate

**`tx_symbol` into `BUS_MODE` is explicitly rejected.**
`BUS_MODE` owns the symbol-to-electrical *mapping* (slow state:
changes a handful of times per transaction). The bitstream
carries the *value* (fast data: per-bit). Folding the value into
the mapping turns every bit-level driver flip into a
`SET_BUS_MODE` churn. See ROADMAP §"Why SDA does *not* live in
`BUS_MODE`" for the full asymmetry argument.

### Reserved for v0.5

`FLAG_CLEAR`, `CAPTURE_RUN`, `CALL`, and `RET` are reserved for
v0.5 (see ROADMAP §"Reserved for v0.5"). `FLAG_CLEAR` is now
**live in v0.2** (CTRL sub `0b0101`); the remaining three are
still v0.5 reserved. The `tx_symbol = 0b11` encoding is reserved
for the v0.5 `raw_override` escape and must not be repurposed.

## Quarter-bit is the timing unit on the wire

The fabric clock *is* the quarter-bit clock. One fabric cycle =
one quarter-bit time. `LOAD_TIMING` controls how many fabric
cycles per quarter-bit at runtime (for pp / od / i2c frequency
selection); `SET_BUS_MODE` picks which of those divider words is
active. There is no sub-quarter-bit timing knob.

**Fabric frequency targets.** On iCE40 UP5K the Verde target
`engineClk` is **24 MHz**, the same fabric clock the v0 engine
ran on against silicon (Step 17 closed at 24 MHz). The v0.2 rework
attempted 48 MHz via the 5-stage pipeline; the X.1..X.5 perf chain
on UP5K-SG48 plateaued at ~32 MHz best across 10 seeds (paths
shifted as each long route was attacked but the ceiling didn't
move structurally), at which point the call was made to keep 24
MHz so the design sits safely off the compliance edge rather than
chase a moving Fmax target. The `uartClk` domain also runs at 24
MHz (1:1 with `engineClk` on Verde — both wired off the same PLL
output). See `docs/MOLE-0.2-SPEC.md` §2 for the clock-domain
table.

This means:
- Every state in the bit-cycle pipeline advances on a
  quarter-bit boundary --- no half-bit, no "between quarters"
  state.
- `EMIT_BIT_IMM` / `EMIT_BIT_REG` carry the **canonical bit
  shape** for all 4 quarters: SCL low / low / high / high
  (engine-generated, not in the bitstream), SDA held at the
  bit's `tx_symbol`. A single-cycle pad-boundary output
  pipeline on SDA only (SCL is unpipelined) gives every SDA
  transition --- bit-to-bit, between EMIT_QUARTERs, idle
  release --- a one-fabric-cycle lag behind the corresponding
  SCL change. That is the `tHD;DAT` data-hold; see ROADMAP
  §"Canonical EMIT_BIT shape" for the spec rationale and the
  LPI2C / FlexComm motivation. The `tx_symbol` is decoded
  against the active `BUS_MODE` (dominant → OD-low or
  PP-drive-0; recessive → OD-release or PP-drive-1; hiz →
  driver-off). The SDK emits one `EMIT_BIT_IMM` per wire bit
  and never has to reason about the SCL waveform.
- **Stretch-aware Q2 entry on controller-role `EMIT_BIT_*`.**
  The engine auto-syncs to slave-stretched SCL at the Q1→Q2
  boundary: if `observer.sclSampled` is low when the engine
  would have advanced to Q2, it pauses the `QuarterBitTimer`
  and spins in a wait branch *inside* the X stage's WIRE-bearer
  execute path (gated by an inline `waitingForStretch` register,
  not a dedicated pipeline stage --- the inline shape was needed
  to close Fmax, matching the v0 finding) until SCL releases or
  `MoleConfig.stretchTimeoutCycles` fabric cycles elapse
  (default 2^20 ≈ 44 ms at 24 MHz, counted in a 21-bit
  countdown with a pipelined zero-comparator). PP-class
  `BUS_MODE` slaves that stretch are treated as compliance
  violations: immediate HALT with status `STATUS_TRAP`, no
  wait. The guard is bypassed in target role (`!roleReg`), and
  `EMIT_QUARTER_IMM` / `EMIT_QUARTER_REG` / `STRETCH_SCL_*`
  are untouched (user retains literal-wire-shape control /
  forced-stretch semantics). See ROADMAP §"Stretch-aware Q2
  entry" for the wire contract.
- **SCL is engine-generated during `EMIT_BIT_*`, bitstream-
  controlled during `EMIT_QUARTER_*`.** This is the only path
  to per-quarter SCL control; `EMIT_BIT_*`'s bitstream does not
  carry an SCL drive field. The engine's `SclWaveformGen` reads
  `BUS_MODE.mode[1]` to choose OD-release vs PP-high for the
  high half of every `EMIT_BIT_*`. In target role the engine
  releases SCL entirely and slaves to the external clock; the
  controller-side opcodes (`EMIT_BIT_*`, `EMIT_QUARTER_*`) are
  replaced by `SAMPLE_BIT_ON_SCL` / `DRIVE_BIT_ON_SCL` which
  pace off external SCL edges.
- Glitch injection (and any other per-quarter deviation from the
  canonical shape, including SCL glitches) happens by emitting 4
  explicit `EMIT_QUARTER_IMM`s in place of one `EMIT_BIT_IMM`.
  The engine itself stays glitch-free --- the bitstream encodes
  the shape. Target-role lint: `EMIT_QUARTER_IMM` with
  `scl=recessive` is rejected by the SDK under PP-class
  `BUS_MODE` (would request PP-drive-1 of SCL); `dominant`
  (pull low: stretch, fuzz) and `hiz` (release) are always
  legal.
- `EMIT_BIT_*` and `EMIT_QUARTER_*` coexist deliberately: see
  the ISA-contract section above and ROADMAP §"Why not
  `EMIT_QUARTER`-only?" for the asymmetry that justifies keeping
  both.
- **`EMIT_QUARTER_*` is the escape hatch, not the workhorse.**
  It exists for the bounded set of non-canonical wire shapes ---
  Start / Stop / Repeated Start, HDR data bits, compliance
  violations (setup/hold violations, SCL/SDA glitches,
  early/late release), and optional bus-idle waits. A typical
  I3C SDR write is ~4 % `EMIT_QUARTER_*`, ~96 % `EMIT_BIT_*`.
  If a code review surfaces a program *dominated* by
  `EMIT_QUARTER_*`, that is a signal the SDK macro layer is
  missing an abstraction, not that the ISA grain is wrong. See
  ROADMAP §"When to use EMIT_QUARTER" for the enumerated use
  cases and the not-used-for list.

## Open-drain primitive: custom `MoleBus`, not `ReadableOpenDrain`

Every bus wire (`io.bus.scl`, `io.bus.sda`) uses Mole's custom
`MoleBus` bundle (lands with Step 2): three signals per line ---
`driveLow`, `driveHigh`, `read`. **Do not** replace with stock
`spinal.lib.io.ReadableOpenDrain[Bool]` or `TriState`.

Rationale:
- `ReadableOpenDrain` exposes only `(write, read)` --- it cannot
  express I3C push-pull mode where the pad actively drives high
  instead of releasing. The 3-signal bundle splits the two so
  the symbol decoder (one combinational function of `tx_symbol`
  + active `BUS_MODE`) can drive the right pin without an
  inferred mode register *on the pad*. The only mode register
  in the design is `BUS_MODE` itself --- see ROADMAP §"Bus mode
  register".
- Sister project `icebreaker-spinalhdl-examples/I2c` uses
  `ReadableOpenDrain` because it only ever speaks I²C. Mole has
  to speak both I2C and I3C (OD + PP), so the primitive
  necessarily diverges. This is a deliberate divergence,
  documented in Step 2's "What landed" block when it ships.
- Maps cleanly to iCE40 `SB_IO` in push-pull mode with
  output-enable driven by `driveLow | driveHigh`. Pull-ups stay
  external so OD recessive (Hi-Z + pull-up wins) still works.

Polarity rules:
- `driveLow := True` → NMOS pull-down on → pin at GND → bus low.
- `driveHigh := True` → PMOS pull-up on → pin at VIO → bus high
  (push-pull). Pull-up resistor is irrelevant in this state.
- Both `False` → output-enable off → pin floats → external
  pull-up wins → bus high (open-drain release).
- Both `True` → **bus contention.** Illegal. Asserted out in
  sim, and the symbol decoder must never produce it (no
  `tx_symbol`/`BUS_MODE` combination decodes to both).

`tx_symbol` → `(driveLow, driveHigh)` decode table (combinational
in `BUS_MODE`):

| `BUS_MODE`            | `dominant`           | `recessive`               | `hiz`    |
|-----------------------|----------------------|---------------------------|----------|
| `i2c`, `i3c-OD`       | NMOS on  → `(1, 0)`  | both off → `(0, 0)`       | `(0, 0)` |
| `i3c-PP`, `hdr-ddr`   | NMOS on  → `(1, 0)`  | PMOS on  → `(0, 1)`       | `(0, 0)` |

In OD modes `recessive` and `hiz` are electrically
indistinguishable (both produce `(0, 0)` and the external
pull-up wins). In PP modes `recessive` actively drives high;
`hiz` is genuine driver-off.

Three rules:
- The engine drives low or drives high based on the symbol
  decoder's output --- a pure combinational function of the
  current opcode's `tx_symbol` field and the active `BUS_MODE`.
  No per-pad mode register.
- "Release the bus" is `tx_symbol = hiz` (or any opcode that
  doesn't drive the line), which decodes to
  `driveLow := False; driveHigh := False`. Also the default at
  reset and between programs.
- For I2C / I3C OD operation the `BUS_MODE` is `i2c` or
  `i3c-OD`, so the symbol decoder never asserts `driveHigh` and
  the PMOS never fires --- electrically identical to a classic
  open-drain bus.

## Compile-time deterministic engine

No randomness lives in the FPGA. All PRNG belongs in the host
compiler. Reviewers should treat any `Random`, LFSR-as-PRNG, or
"jitter source" in the engine as a bug.

The engine reports back to the host:
- The bit values it sampled on read-enabled `EMIT_*` instructions.
- The mismatch flag set by the most recent `EMIT_*` against its
  expect mask.
- `MARK` records (label + implicit timestamp).
- `HALT` status word.

That is the whole result-ring contract.

Result-ring records (canonical reference:
`docs/MOLE-0.2-SPEC.md` §11): the result ring is **32-bit-grained**.
Every record is a whole number of 32-bit words; the drainer streams
each SPRAM word as 4 LE bytes. The `REVISION` word lives at word
offset 0 (a single 32-bit word, `[31:24]=major [23:16]=minor
[15:0]=patch`); the record stream starts at word 1 and consists of
CAPTURE records (`tag = 00`, 1 word, sda at `[0]`) and MARK records
(`tag = 10`, 3 words: header + ts_lo + ts_hi, where the two
timestamp halves each carry their useful bits in the low 16 of a
32-bit ring word, `[31:16]` reserved-zero as a v0-grain relic). The
HALT status word (`tag = 11`) is a 32-bit record at
`resultLimit = (resultRingByteCount / 4) - 1` as a reserved slot
that overflowing records can never overwrite. The HALT word layout
is:

```text
[31:30] tag      = 0b11
[29]    overflow (sticky overflow latch)
[28]    mismatch (snapshot of MISMATCH_FLAG at halt entry)
[27:23] status   (5 bits, 0x00..0x1F; 0x1F = engine trap)
[22: 0] reserved
```

See spec §11 for the full ABI constants. Overflow and recovery
semantics: result-ring overflow latches into the HALT word's
`[29]` bit; records written after overflow are dropped.

## Per-program reset on engineStart rising edge

The engine clears its architectural per-program state on every
`programStart` pulse (rising edge of `io.engineStart` detected
inside `EnginePipeline.scala`). The cleared regs are:

- `haltedReg`, `haltStatusReg` — so the engine actually runs the
  next program after a previous HALT.
- `revisionPending` — so REVISION re-emits at slot 0 for every
  program.
- `ringWrPtr`, `ringOverflow` — so record writes start at slot 1
  and the overflow latch starts cleared.
- `mismatchFlagReg`, `timeoutFlagReg`, `startFlagReg`, `stopFlagReg`,
  `regZeroFlagReg` — so the sticky flag set starts at all-False
  for every program (otherwise a flag from program N could survive
  into program N+1's `BRANCH_ON` / `WAIT_ON` decisions).
- `pcReg := 0` — so fetching resumes from PC=0.

The `MoleTop` phase FSM (`acceptLoad -> running -> draining`)
holds `engineStart = False` during `acceptLoad` and `draining`,
and drives it True only inside `runningState`. Every transition
into `runningState` for a NEW program is therefore a 0→1 edge on
`io.engineStart` that fires the reset. Back-to-back program loads
without an FPGA power cycle are supported.

The SPRAM result region is also cleared per program — see the
sentinel-fill section below.

## SPRAM result-region sentinel-fill (spec §11.7)

Per-program architectural reset (above) clears the engine's REGS
but not the contents of the SPRAM result region. Without an
additional mechanism, a short program following a long one would
leak the long program's CAPTURE / MARK records into the short
program's decoded view (the host decoder walks records by tag and
only stops on a non-record tag, so prior records propagate
forward).

The engine writes the SENTINEL word `0x4000_0000` (reserved
record tag `0b01` + zero payload) into every slot of the result
region `resultBase..resultLimit` on every `programStart`, BEFORE
emitting REVISION or fetching the first instruction. The host
decoder breaks on the reserved tag (per spec §11.6), so a
subsequent short program's decoded ring stops at the first
sentinel slot past its actual records.

The `sentinelFillActive` register initialises **False** on FPGA
hardware reset (NOT True), so the sentinel-fill does NOT run
before the first `programStart` after reset. Driving sentinel-fill
during the loader phase would back-pressure `loaderWrite` for
`resultWordCount` cycles through the shared `SpramController`
arbiter (read > resultWrite > loaderWrite) and risk the engine
fetching stale SPRAM. By initialising False, the first program
after FPGA reset sees the cold-boot SPRAM (undefined per the
iCE40 datasheet); every subsequent program is sentinel-filled
cleanly because `programStart` fires after the loader phase ends.

Don't bypass the sentinel-fill. Don't init `sentinelFillActive`
to True ("first program after reset deserves a clean SPRAM too")
without first addressing the loader / sentinel-fill arbitration
race documented above. The pre-program-reset case is acceptable
collateral; the cross-program leak case is not.

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

Optional features keyed off `MoleConfig` (e.g. capture-ring
depth, WAIT-SCL-RELEASE wired-in vs gated out) use a Scala-time
`if` so the optional logic disappears entirely from the
synthesised design when the toggle is `false`. A Spinal
`when(...)` would still emit the gating and the sense wire.

**Exception: `MoleConfig.role`.** Role is the one `MoleConfig`
field that intentionally departs from this convention. It is the
power-on default for the runtime `roleReg` register
(`BitCycleEngineCore.scala`, see the `roleReg` declaration
comment), not a Scala-time strip. Both controller-role and
target-role FSM arms elaborate unconditionally, and `SET_ROLE`
flips `roleReg` at any PC. The departure is deliberate: it lets
a single bitstream serve both roles, which matters more here
than the LUT savings a Scala-time strip would have bought. Other
`MoleConfig` fields keep the Scala-`if` discipline; do not
generalise the runtime-register pattern to them without an
equivalent justification.

**Pipeline framework note (Phase C onward).** The v0.2 engine
is built on `spinal.lib.pipeline.Pipeline` with 5 stages
(F/D/R/X/W). The three sub-rules above still bind: registered
drivers per stage, no per-stage combinational drives onto the bus
pins, no `releaseAll()`-shaped helpers. The pipeline framework
does NOT relax the bus-shaped FSM idiom --- it just changes how
state is held between stages (`Stageable[T]` slots instead of
state-machine register declarations). The stretch-aware Q1→Q2
guard remains an inline guard on the X stage's WIRE-bearer
execute path (NOT a dedicated pipeline stage), preserving the
v0 Fmax finding documented in the v0 engine source.

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
hint says it ships hardware.

**v0 silicon status.** Step 17 (TMP108 I2C) closed against v0
silicon. Step 18 (MCXA268 I3C target soak) is now planned to
close under v0.2 in Phase C.11; it is not a gate for calling v0
done --- v0 silicon is retired. Reopen Step 18 with v0.2 framing
when Phase C.11 arrives.

**v0.2 gate.** The v0.2 engine acceptance gate is Phase C.11
(MCXA268 I3C target soak under v0.2). Do not declare v0.2 done
before that step closes on real hardware.
