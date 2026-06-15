# Stretch-aware Q1->Q2 guard for `EMIT_BYTE_*` (Phase B)

Status: design spec. Approved by the reviewer seat
(`approve-with-conditions`, R3); dissenting positions from the
architect and reliability seats are preserved in
"Considered alternatives" (§9). No code lands with this commit;
this document is the architectural contract the implementing
agent consults.

Supersedes-relationship: this is the Phase B sibling to
`docs/superpowers/specs/2026-06-02-stretch-aware-emit-bit-design.md`
(Phase A). Phase A shipped the stretch-aware Q1->Q2 guard for
`EMIT_BIT_IMM` / `EMIT_BIT_REG`; Phase B extends the same guard
to `EMIT_BYTE_IMM` / `EMIT_BYTE_REG`. The Phase A spec gains a
"superseded by Phase B" header bullet as part of the Phase B
`feat(engine)` commit; the Phase A design remains the
authoritative description of the EMIT_BIT_* arm.

---

## 1. Context

`EMIT_BIT_IMM` and `EMIT_BIT_REG` carry a stretch-aware Q1->Q2
guard (Phase A) that handles slave clock-stretching on the
rising edge of SCL within a single bit cell. `EMIT_BYTE_IMM`
and `EMIT_BYTE_REG` loop nine bit cells (8 data MSB-first plus
the ACK/NAK cell) but do **not** currently arm the guard. This
is the only behavioral difference between the bit and byte
WIRE-bearer opcodes, and it makes `EMIT_BYTE_*` unsafe against
any real slave that legitimately stretches the clock --- the
exact case the Phase A guard was designed to handle for the
single-bit bearers.

The originating field signal is the i.MX RT685 `embassy-imxrt`
boundary-soak repro driving an iMXRT-class controller against a
target that stretches on the byte boundary; the engine never
HALTs but the bus quietly hangs because `EMIT_BYTE_*` does not
notice the stretched SCL. The repro artifact and the patched
soak example are intentionally **not** committed to this repo
(they live as untracked working-copy files; see the orchestrator
notes for paths).

Phase B closes the gap with one inline guard call at the
`WS_EMIT_BYTE` per-cell boundary plus a small refactor that
extracts the existing Phase A guard into a shared helper, in
service of the same FSM-shaped invariants Phase A established.

---

## 2. Proposal in one line

Extract the existing `WS_EMIT_BIT` stretch-aware Q1->Q2 guard
into a private `stretchGuard(triggerCondition, onPpTrap)` helper
on the engine pipeline, then call it from `WS_EMIT_BYTE` at the
Q1->Q2 boundary of each of the nine bit cells, in a two-commit
PR that ships behind a one-line precursor `fix(engine)` commit
correcting the PP-class HALT status code.

---

## 3. Scope

In-scope:

- A precursor `fix(engine)` commit on the same branch that
  routes the PP-class stretch trap through the existing
  `STATUS_TRAP (0x1F)` constant instead of the
  `STRETCH_TIMEOUT (0x0D)` code it currently uses --- see §4.1.
- A `refactor(engine)` commit that lifts the Phase A guard out
  of `WS_EMIT_BIT` into a private `stretchGuard` helper with
  Phase A sims passing byte-identical --- see §4.2.
- A `feat(engine)` commit that wires the helper into
  `WS_EMIT_BYTE` at the per-cell Q1->Q2 boundary, including
  the F11 stale-zero mitigation inside the helper body, plus
  the spec edits enumerated in §6 --- see §4.3.

Out-of-scope (hard fences, non-negotiable):

- No new opcode.
- No new HALT status code (both `STRETCH_TIMEOUT` and
  `STATUS_TRAP` pre-exist).
- No new `BUS_MODE`.
- No new `MoleConfig` knob (`stretchTimeoutCycles` is the
  existing knob).
- No ISA bit-layout change. No wire-format bump.
- No Makefile / CI changes.
- No SDK (`mole-sdk`) changes. Layer 1 already emits
  `EMIT_BYTE_*`; the behavior change is invisible at the SDK
  surface beyond the fact that previously-quiet bus hangs now
  surface as HALTs.
- No combinational re-architecture of the
  `stretchTimeoutCtrIsZero` register (see §9.3 for why the
  reviewer rejected this alternative).
- No change to `EMIT_QUARTER_*`, `SAMPLE_BIT_ON_SCL`,
  `DRIVE_BIT_ON_SCL`, `STRETCH_SCL_*`, or any non-WIRE opcode.

---

## 4. Design

### 4.1 Precursor `fix(engine)`: PP-class HALT status routing

**Problem.** Three normative sources (the file-header docstring
at `fpga/Mole/src/hw/EnginePipeline.scala:228`, the in-source
comment at the PP-trap site near `:1892`, and
`fpga/Mole/AGENTS.md`'s "Stretch-aware Q2 entry on
controller-role `EMIT_BIT_*`" subsection) all describe the
PP-class stretch HALT as emitting `STATUS_TRAP (0x1F)`. The live
code path at `EnginePipeline.scala:1897-1898` packs
`STRETCH_TIMEOUT (0x0D)` via
`makeStretchTimeoutWord(forceMismatch = True)`. Three doc
sources agree with each other; one code site disagrees with all
three. The code is the bug.

**Why this matters for Phase B diagnosability.** PP-class
stretch (compliance violation: a PP-class slave drove SCL low at
all, which is a spec violation) and OD-class timeout (bus
condition: a slow but legal OD-class slave held SCL longer than
`MoleConfig.stretchTimeoutCycles`) are different incident
classes with different operator responses --- the first is
"file a slave-side compliance bug", the second is "retry
or relax the timeout". The result-ring HALT word is the only
diagnostic the host has post-mortem; the
`(status, mismatchFlag, timeoutFlag)` triple must distinguish
the two cases.

Phase A could have shipped this fix as part of the EMIT_BIT
guard but did not. Phase B doubles the diagnosability cost of
that miss --- the second arm (`WS_EMIT_BYTE`) would inherit the
same collapsed status code --- so Phase B fixes the precursor
before adding the second arm.

**Fix shape.** Replace `EnginePipeline.scala:1897-1898`
(PP-class branch inside the existing `WS_EMIT_BIT` guard) so
that the packed status word is `StatusCode.TRAP` instead of
`StatusCode.STRETCH_TIMEOUT`. Concretely, either of these is
acceptable:

```scala
// Option A: call the existing makeHaltWord with TRAP directly.
xWireHaltWord := makeHaltWord(U(StatusCode.TRAP, 5 bits), True)

// Option B: extend makeTrapWord() to honour mismatchFlag.
xWireHaltWord := makeTrapWord(forceMismatch = True)
```

The OD-class branch at `EnginePipeline.scala:1842-1849`
continues to call `makeStretchTimeoutWord(forceMismatch = True)`
unchanged --- the OD-class timeout *is* the legitimate
`STRETCH_TIMEOUT` case (slow slave, bus condition).

The three doc sources (`:228`, `:1892`,
`fpga/Mole/AGENTS.md`) were already correct under this fix and
require no further edit. The single doc surface that does need
updating in this same precursor commit is the HALT status table
at `docs/MOLE-0.2-SPEC.md` §11 --- specifically: the
`STATUS_TRAP (0x1F)` row gains a note "PP-class slave stretch
(compliance violation)"; the `STRETCH_TIMEOUT (0x0D)` row is
clarified to "OD-class slave stretch exceeding
`MoleConfig.stretchTimeoutCycles`".

**Phase A sim impact.** `BitCycleEngineStretchRoleSim` (or
whichever Phase A sim asserts the PP-class HALT status; the
implementing agent confirms by grep before staging the precursor)
must be updated in the same precursor commit to expect
`STATUS_TRAP` rather than `STRETCH_TIMEOUT` on the PP-class arm.
This is an expected-value update only; it does not relax any
existing assertion. The sim continues to assert MISMATCH-flag
set on this path.

**No wire-format bump.** `STATUS_TRAP (0x1F)` is an existing
5-bit code in the spec §11 HALT-status table; this is a
re-routing of an existing code, not a code addition or a
bit-layout change.

### 4.2 Refactor commit: extract `stretchGuard` helper

**Shape.** Lift the existing `WS_EMIT_BIT` Q1->Q2 stretch-guard
*entry* (the `when(xQIdx === 1 && !roleReg && !observer.sclSampled)`
predicate body at `EnginePipeline.scala:1887-1908`, NOT the
shared wait-state body at `:1834-1852`) into a private helper:

```scala
// Drive the Q1->Q2 stretch-guard entry for a WIRE-bearer cell.
// Inputs:
//   triggerCondition: Bool - the per-caller predicate
//     gating guard entry (e.g. `xQIdx === 1 && !roleReg &&
//     !observer.sclSampled` for WS_EMIT_BIT; the analogous
//     per-cell predicate for WS_EMIT_BYTE).
//   onPpTrap: => Unit - caller-supplied state-machine exit
//     thunk run inside the PP-class arm (different
//     xWireDone / xWireState writes for BIT vs BYTE).
// Side-effects: writes waitingForStretch, stretchTimeoutCtr,
//   stretchTimeoutCtrIsZero, xWireHaltWord, and the
//   PP-trap-arm's HALT-record machinery.
// Contract: I13 (the stretchTimeoutCtrIsZero := False mirror)
//   fires inside this helper, on EVERY guard-entry path. See
//   I13 in §5 below.
private def stretchGuard(
    triggerCondition: Bool,
    onPpTrap: => Unit
): Unit = {
  // body: lifted verbatim from EnginePipeline.scala:1887-1908,
  // with the existing :1906 line `stretchTimeoutCtrIsZero :=
  // False` now serving both WS_EMIT_BIT and WS_EMIT_BYTE
  // callers from inside the helper body.
}
```

**Two-parameter cap.** The helper signature is **hard-capped at
two parameters**. A future caller (e.g. a v0.5 `raw_override`
WIRE bearer) that requires a third parameter is the explicit
signal to **re-inline** the helper across all call sites and
accept the duplication, NOT to grow the helper to three
parameters. This discipline is borrowed from the architect's R2
T3 framing and adopted by the reviewer; it is enforced
socially, in PR review, not by tooling. Future reviewers of
PRs touching this helper should reject parameter additions and
route the change through a re-inline-vs-extend discussion.

**Sim-invariance gate.** The refactor commit **must** leave
every Phase A sim passing byte-identical. The Phase A sim file
(`BitCycleEngineStretchRoleSim` and any companion exercising
`EMIT_BIT_*` stretch) is the gate: if the refactored helper
produces any waveform or HALT-word delta against Phase A, the
helper is structurally wrong and must be re-rolled. The reviewer
and reliability seats both endorse this gate as the discipline
that keeps the refactor honest: a silent helper-shape drift
introduced here would survive into Phase B's feat commit and
become invisible.

**What stays at the call site.** Two pieces explicitly do NOT
move into the helper:

1. **The shared wait-state body** at
   `EnginePipeline.scala:1834-1852` (release on SCL rising at
   `:1836-1840`, timeout-HALT at `:1841-1849`, decrement at
   `:1850-1851`). This is a single state body that both callers
   fall through into; it is not duplicated, so it does not need
   to be helper-ified. Phase B inherits it unchanged.
2. **The `xQIdx := nextQ` advance** at the existing
   `EnginePipeline.scala:1909-1911` `otherwise { ... }` block.
   The non-stretch advance stays at each call site --- the
   helper covers only the guard-fires branches, the caller still
   owns its own per-bit-cell index plumbing.

### 4.3 `feat(engine)`: extend the guard to `WS_EMIT_BYTE`

**Call site.** At the Q1->Q2 boundary of each of the nine cells
emitted by `EMIT_BYTE_*` (eight data bits MSB-first plus the
ACK/NAK cell), `WS_EMIT_BYTE` calls `stretchGuard` with:

- `triggerCondition` = the analogous per-cell predicate
  (controller role, this cell's `xQIdx === 1`, and
  `!observer.sclSampled`). The implementing agent reads the
  exact `xByteIdx` / `xQIdx` plumbing live in
  `EnginePipeline.scala` near `:1934+` (the "no stretch guard"
  comment around `:1936` documents the current absence) and
  reconstructs the predicate from the Phase A pattern.
- `onPpTrap` = a thunk that resets `xByteIdx := 0`, sets
  `xWireState := WS_IDLE`, sets `xWireDone` appropriately for
  the byte-emit completion accounting, and emits the HALT
  record for the PP-class trap. The exact contents mirror the
  WS_EMIT_BIT thunk shape extended by the byte-state cleanups.

**ACK-slot guard.** Whether the ACK/NAK cell at `xByteIdx === 8`
also arms the guard is left to the implementing agent's
discretion, with the architect's recommendation being **yes**
(the slave can legitimately stretch before sampling the ACK
just as during the data bits, and the symmetry of "every cell
arms the guard" is easier to audit than "every cell except
cell 8"). The spec text in §6 below does not pre-decide ACK-slot
behavior; both choices are spec-compliant and the implementing
agent's chosen behavior is documented in the §5.5 / §5.5b
behavior subsection.

**Wait-state re-use.** When the guard fires on `WS_EMIT_BYTE`,
the FSM transitions into the same shared wait state used by
`WS_EMIT_BIT` --- there is one wait-state body, not two. The
`xQIdx := 2` fall-through at `:1840` is correct for both callers
because both arm the guard at the Q1->Q2 boundary
(`xQIdx === 1` enters, `xQIdx := 2` on release). A future bearer
that arms the guard at a different quarter boundary would need a
predicate-driven `xQIdx` fall-through, but that is out of scope
here; the implementing agent may add a one-line comment at
`:1840` documenting the assumption.

### 4.4 The F11 stale-zero hazard and I13

**Hazard restated.** `stretchTimeoutCtrIsZero` is a
`Reg(Bool()) init True` at `EnginePipeline.scala:635-636`. The
combinational assign `stretchTimeoutCtrIsZero :=
(stretchTimeoutCtr === 0)` has one fabric cycle of latency
behind the counter. In `WS_EMIT_BIT`, the guard exits the wait
state and the FSM returns to `WS_IDLE` between bit cells; the
next `EMIT_BIT_*` opcode re-enters the guard via the per-program
reset path and the stale-True is masked by the unconditional
counter reload at wait-entry (the `:1906` line forces
`stretchTimeoutCtrIsZero := False` to bridge the one-cycle
latency).

In `WS_EMIT_BYTE`, the FSM stays in `WS_EMIT_BYTE` across all
nine cells. Cell N+1's first wait-cycle reads the same
`stretchTimeoutCtrIsZero` register on the same FSM state that
cell N just exited. If cell N's counter happened to decrement to
exactly zero on its last wait cycle (or even close enough to
zero that the registered comparator latches True), cell N+1's
first wait-cycle reads `stretchTimeoutCtrIsZero = True`, the
`:1841-1849` timeout-HALT path fires, and the engine HALTs on a
phantom timeout that never actually occurred in cell N+1's
counter.

**Mitigation M8 (one-line FSM signal clear).** Mirror the
existing `EnginePipeline.scala:1906` clear into every guard
wait-entry. Under the §4.2 helper extraction, this is a
single line inside the helper body: every call site benefits
from the explicit re-clear, and the EMIT_BIT arm's prior
behavior is preserved (it was already clearing via `:1906`; the
line just moves into the helper). No new register, no new
signal, no new state, no width change, no init change. The
repo-root `AGENTS.md` §3.4 fence ("no new infrastructure") holds.

**Invariant I13** (added to `docs/MOLE-0.2-SPEC.md` §3.3 in the
feat commit): see §5 below for the verbatim spec text.

---

## 5. Invariants

This design preserves invariants I1-I12 from the v0.2 spec
unchanged. It introduces one new invariant:

**I13 (NEW) --- Stretch comparator stale-zero clear.** Any FSM
arm that exits the stretch-aware Q1->Q2 guard while remaining in
the same FSM state for a subsequent bit/byte cell MUST clear the
`stretchTimeoutCtrIsZero` signal in the same cycle as the
wait-exit transition. The pipelined comparator that drives this
signal has one cycle of latency from `stretchTimeoutCtr` (per
the Phase A Fmax mitigation at
`fpga/Mole/src/hw/EnginePipeline.scala:632-635`); relying on it
without the clear can cause a phantom timeout on the next cell.
`EMIT_BIT_*` satisfies this trivially by returning to `WS_IDLE`
between cells; `EMIT_BYTE_*` satisfies it explicitly via the
wait-entry clear lifted into the `stretchGuard` helper. Future
bearers (e.g. a v0.5 `raw_override`) that loop within a single
FSM state MUST include the clear --- the helper's contract
makes this automatic for any caller of `stretchGuard`.

The other I1-I12 invariants are unchanged. Briefly, the ones
this design touched and confirmed:

- **I1 (32-bit fixed instruction width):** unchanged. No new
  opcode, no field relocation. `EMIT_BYTE_*` opcodes already
  carry their flag triple at `[2:0]` per the spec.
- **I4 (`BUS_MODE` owns the symbol-to-electrical mapping):**
  unchanged. The guard reads the active BUS_MODE class
  (`BusModeOps.isPpClass(busModeReg)`) to route the HALT status
  per §4.1; this is the same query the EMIT_BIT arm already
  makes.
- **I7 (sticky engine flags write-once until overwritten):**
  unchanged. The guard does not introduce new sticky flags;
  the existing MISMATCH / TIMEOUT pair is set by the existing
  HALT-word packers.
- **I9 (SPRAM sentinel-fill on `programStart`):** unchanged.
  The HALT record emitted by the guard is a normal record-tag
  write into the result region; it does not bypass sentinel-fill.
- **I12 (instruction-fetch / writeback flush on HALT):**
  unchanged. The flush handler around
  `fpga/Mole/src/hw/EnginePipeline.scala:2402-2427` already
  clears `waitingForStretch`, `xPendingWrite`, `f2InstrValid`,
  `f2RespPending`, and the FSM state; it covers `WS_EMIT_BYTE`
  HALTs without modification.

---

## 6. Spec edits

The Phase B `feat(engine)` commit (§4.3) updates
`docs/MOLE-0.2-SPEC.md` in four places. Two further edits live
in the precursor `fix(engine)` commit (§4.1) and the Phase A
spec marker.

**E1** -- `docs/MOLE-0.2-SPEC.md` §3.3 invariants ledger.
Add I13 verbatim from §5 above.

**E2** -- `docs/MOLE-0.2-SPEC.md` §5.5 (`EMIT_BYTE_REG`) behavior
subsection.
Insert `EMIT_BYTE-guard-prose` and `T1-resolution-text` (see §6.1
and §6.2 below).

**E3** -- `docs/MOLE-0.2-SPEC.md` §5.5b (`EMIT_BYTE_IMM`) behavior
subsection.
Insert the same two blocks; the two bearers differ only in
immediate vs register source, not in guard behavior.

**E4** -- `docs/MOLE-0.2-SPEC.md` §11 HALT status table (precursor
commit).
`STATUS_TRAP (0x1F)` row gains "PP-class slave stretch (compliance
violation)"; `STRETCH_TIMEOUT (0x0D)` row is clarified to "OD-class
slave stretch exceeding `MoleConfig.stretchTimeoutCycles`".

**E5** -- `docs/superpowers/specs/2026-06-02-stretch-aware-emit-bit-design.md`
header (feat commit).
Add a "Superseded by Phase B" note pointing to this spec file; the
Phase A design remains the authoritative description of the
`EMIT_BIT_*` arm.

Architect R2's proposed E4-extension (adding a note to the
`STRETCH_TIMEOUT (0x0D)` row saying it covers PP-class stretch)
is **reversed** under T1=(b): §11 now splits the two failure
modes across the two existing status codes, not collapses them.

### 6.1 `EMIT_BYTE-guard-prose` (for E2 and E3)

> **Stretch-aware Q1->Q2 guard.** Between Q1 (drive SDA) and Q2
> (rising edge of SCL) of each of the nine cells emitted by
> `EMIT_BYTE_*` (eight data bits MSB-first plus the ACK/NAK
> cell), the engine inspects SCL. If SCL is held low by the
> slave (clock stretching), the engine arms the stretch-aware
> guard: it suspends the cell, starts the timeout counter
> `stretchTimeoutCtr`, and waits for SCL to release. Status
> routing on guard exit follows the rules below. The guard's
> entry condition, timeout counter, wait-state body, and
> result-ring HALT record format are identical to those defined
> for `EMIT_BIT_*` in §5.1 (`EMIT_BIT_IMM`) and §5.2
> (`EMIT_BIT_REG`). Whether the ACK/NAK cell also arms the
> guard is implementation-defined; the reference implementation
> arms it on every cell for audit symmetry.

### 6.2 `T1-resolution-text` (for E2 and E3)

> **Status routing on guard exit.** When the active BUS_MODE is
> OD class (`i2c`, `i3c-OD`) and the guard's timeout counter
> (`MoleConfig.stretchTimeoutCycles`, default `1 << 20`,
> approximately 43.7 ms at 24 MHz) expires, the engine halts
> with status `STRETCH_TIMEOUT (0x0D)`. When the active
> BUS_MODE is PP class (`i3c-PP`, `hdr-ddr`), entering the
> guard at all is a compliance violation by the slave
> (a PP-class slave must never drive SCL low), and the engine
> halts immediately with status `STATUS_TRAP (0x1F)`. The two
> paths share the entry condition and the FSM transition; only
> the status word packed into the result-ring HALT record
> differs. In both cases the MISMATCH sticky flag is set; the
> TIMEOUT sticky flag is set only on the OD-class timeout path.

---

## 7. Phasing and commits

This change lands as three commits on the same branch in this
order. The precursor `fix(engine)` commit may be split into its
own PR or land on the Phase B PR; the reviewer is indifferent
to that split provided the precursor lands strictly before the
refactor and feat commits.

```text
Branch: spec/stretch-aware-emit-byte (off master)

Commit 1 (precursor):
  fix(engine): route PP-class stretch trap through STATUS_TRAP

  Body explains the doc-vs-code mismatch and why three docs
  agree against the live code (§4.1).

  Includes:
    - EnginePipeline.scala :1897-1898 one-line code change.
    - docs/MOLE-0.2-SPEC.md §11 HALT status table cleanup (E4).
    - BitCycleEngineStretchRoleSim expected-value update for
      the PP-class arm.

  Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>

Commit 2 (refactor, gated by Phase A sim-invariance):
  refactor(engine): extract stretchGuard helper from WS_EMIT_BIT

  Body explains the two-parameter cap (§4.2) and the sim-
  invariance gate. Behavior unchanged. Phase A sims must pass
  byte-identical waveforms against the refactored helper before
  this commit is staged.

  Includes:
    - EnginePipeline.scala stretchGuard helper extraction.
    - The :1906 stretchTimeoutCtrIsZero := False line now lives
      inside the helper body (I13 contract; no behavior change
      for EMIT_BIT_*).

  Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>

Commit 3 (feat):
  feat(engine): extend stretch-aware Q1->Q2 guard to EMIT_BYTE_*

  Body explains the F11 hazard and the I13 invariant (§4.4).

  Includes:
    - EnginePipeline.scala WS_EMIT_BYTE call to stretchGuard.
    - docs/MOLE-0.2-SPEC.md §3.3 (E1: add I13).
    - docs/MOLE-0.2-SPEC.md §5.5 / §5.5b (E2, E3: guard prose
      and status routing).
    - docs/superpowers/specs/2026-06-02-stretch-aware-emit-bit-
      design.md header marker (E5: superseded-by note).
    - New sim file covering the six R1 timing/role scenarios
      plus the C5-amend stale-zero case (§8).
    - Optional: a one-line `fpga/Mole/AGENTS.md` clarification
      in the "Per-program reset on engineStart rising edge"
      subsection noting the stretch-guard regs are
      write-on-trigger.

  Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
```

No `Signed-off-by:` from an agent on any of these commits
(repo-root `AGENTS.md` §3.8). No `BREAKING CHANGE:` footer ---
none of the three commits change the wire format, the ISA bit
layout, or any documented public-API surface.

---

## 8. Sim coverage

The Phase B feat commit ships one new sim file covering the
following cases. The R1 timing/role matrix carries forward from
Phase A; case C5 is amended per the reviewer R3 §5.4
contribution to specifically exercise the F11 stale-zero hazard.

- **C1.** Mid-data OD resume. `BUS_MODE = i2c`. Slave stretches
  on cell 3 of an 8-bit data byte; counter decrements; slave
  releases SCL before timeout; byte completes; the next
  `EMIT_BYTE_*` runs clean. Asserts: no HALT, expected CAPTURE
  records, R7 reflects payload on byte completion.
- **C2.** ACK-slot OD resume. `BUS_MODE = i2c`. Slave stretches
  on the ACK cell (xByteIdx = 8). Same pass condition as C1.
- **C3.** ACK-slot OD timeout. `BUS_MODE = i2c`. Slave stretches
  on the ACK cell past `stretchTimeoutCycles`. Asserts: HALT
  with `STRETCH_TIMEOUT (0x0D)`, MISMATCH and TIMEOUT sticky
  flags both set.
- **C4.** Mid-data PP trap. `BUS_MODE = i3c-PP`. Slave drives
  SCL low at all on cell 3. Asserts: immediate HALT with
  `STATUS_TRAP (0x1F)`, MISMATCH sticky flag set, TIMEOUT
  sticky flag clear. (Tests the §4.1 precursor's fix at the new
  EMIT_BYTE call site.)
- **C5 (amended).** Back-to-back stretches across two
  consecutive cells, specifically exercising the F11 stale-zero
  hazard. `BUS_MODE = i2c`. Two consecutive `EMIT_BYTE_IMM`
  opcodes (or one `EMIT_BYTE_IMM` followed by an
  `EMIT_BIT_IMM`; either composition exercises the cross-cell
  hazard). Cell N stretches for a duration that decrements
  `stretchTimeoutCtr` to a small value (1..10 cycles remaining)
  but not to zero; slave releases SCL; engine advances to
  cell N+1; slave immediately stretches cell N+1. Pass
  condition: cell N+1 emits no HALT in its first wait cycle and
  the counter visibly decrements from `stretchTimeoutCycles`
  toward zero. **Without I13**: cell N+1's first cycle reads
  stale `stretchTimeoutCtrIsZero = True` from the Reg and HALTs
  with phantom `STRETCH_TIMEOUT`. **With I13**: the explicit
  clear in the helper body forces the Reg False, the wait runs
  the fresh counter to completion, and the byte completes
  cleanly. This case is the only one in the matrix that
  distinguishes "I13 adopted and correct" from "I13 adopted but
  quietly broken".
- **C6.** Target-role no-guard. `BUS_MODE = i2c`, `SET_ROLE 1`.
  `WS_EMIT_BYTE` runs in target role; the guard never arms
  because the `!roleReg` predicate gates it out. Asserts: no
  HALT, expected target-role behavior. (Confirms the predicate
  in §4.3's `triggerCondition` is wired correctly.)

The R1 case lists "mid-data OD timeout" as a sixth scenario;
under the reviewer R3 §3.4 reading this is C3's data-cell twin
and may either be added as C7 or merged into C3's per-cell
parameterization. Implementing agent's discretion; both
choices give the same coverage.

---

## 9. Considered alternatives

These positions were raised during the three-round R1-R3
collaborative design and rejected under the strongest-argument
rule. They are recorded here so future readers see the
trade-offs and so a future PR that wants to revisit one of them
has the prior reasoning in hand.

### 9.1 T1=(a): keep both HALTs as `STRETCH_TIMEOUT (0x0D)`

**Proposed by:** the architect seat through R2; the reliability
seat withdrew to (a) in R3 with two operational caveats.

**Rationale for (a):** byte-for-byte symmetry of the two
guard arms is itself a diagnosability win (one less place for
the two HALT-pack lines to drift); PP-class disambiguation is
recoverable via the `(MISMATCH set, TIMEOUT clear)` tuple
combined with host-side knowledge of the active `BUS_MODE` at
program-load time; blast radius of changing a live HALT status
code is non-trivial.

**Why (b) was chosen:** the reviewer R3 §1.1 engagement is
decisive: PP-class compliance violation and OD-class timeout
are different incident classes with different recovery paths,
and the `(MISMATCH, TIMEOUT)` tuple disambiguator depends on
host-side bookkeeping (the BUS_MODE at program-load time joined
post-hoc to the HALT word) that does not exist in the host
today and is not the simplest robust answer. The repo is
pre-Phase-0 (`AGENTS.md` §3.17), so the wire-format blast-radius
argument has no deployed-host cost; the precursor is a
one-line code change.

**Reliability's (a) caveats** that survive into the (b) world
as informational items:

- The PP-class HALT word under (b) carries
  `(status = 0x1F, mismatch = 1)`; this is unambiguous in the
  HALT record alone.
- A latent diagnosability follow-up (architect R2 "T1 caveat 2"
  / reliability R3 T1-text-1) tracks that any future field
  incident triaged backwards from a HALT word benefits from
  decoder-side context joining the BUS_MODE at program-load
  time. This is a host-side enhancement, not an engine change,
  and is out of scope here.

### 9.2 T2 inline duplication

**Proposed by:** the reliability seat through R2; the architect
seat held to inline through R3 (final dissent).

**Rationale for inline:** the two call sites have asymmetric
data flow (`xQIdx` threading for `WS_EMIT_BIT`; `xByteIdx`
threading for `WS_EMIT_BYTE`); a helper that hides this
asymmetry behind a uniform call site either leaks the asymmetry
through its parameter list or hides it behind an internal
switch on opcode class; SpinalHDL closures captured across
`when`/`elsewhen` blocks are a known footgun; "two call sites"
is the canonical bar at which extraction is not yet justified;
the right move is to inline now and revisit when the third
caller (likely v0.5 `raw_override`) arrives with visible
demand.

**Why helper was chosen:** the reviewer R3 §2.2 "future caller
delta" calculation puts the cost asymmetry at roughly 10:1
(helper: ~3 new lines per new caller; inline: ~30 new lines
per new caller plus a 3-way diff audit). The reliability seat's
R3 reversal acknowledged that the asymmetric data flow worry
did not survive close reading of `EnginePipeline.scala:1887-1911`
--- the BIT-vs-BYTE divergence lives entirely in the post-trap
exit, which the `onPpTrap` thunk covers, and the bit-cell
counter is `xQIdx` for both callers (`xByteIdx` is irrelevant
to the guard, which fires per bit-cell, not per opcode). The
two-parameter cap (§4.2) defangs the "what if the helper grows
unbounded" concern: if a future caller needs a third parameter,
the helper re-inlines and the discipline self-corrects.

**Architect's surviving dissent:** the asymmetric data flow and
closure-stability arguments are real and the two-parameter cap
is socially enforced rather than tool-enforced. A future PR
that reaches for a third parameter under time pressure could
plausibly skip the re-inline discussion. Mitigation: this spec
documents the cap loudly in §4.2 and §6 / §7 of the helper's
docstring, and future reviewers are explicitly invoked.

### 9.3 T3 alternative: make `stretchTimeoutCtrIsZero` combinational

**Proposed by:** the reviewer seat (raised in the round-3 brief
as an alternative for evaluation; the reviewer's own R3
verdict rejects it).

**Rationale:** removing the registered comparator and
re-architecting as `Wire(Bool()) := (stretchTimeoutCtr === 0)`
eliminates the F11 stale-zero race at the source rather than
mitigating it per-call-site. One change replaces N per-site
mitigations.

**Why I13 (M8 mirror) was chosen instead:** the existing
register form is explicitly documented as a v0 Fmax mitigation
at `fpga/Mole/src/hw/EnginePipeline.scala:632-635`:

> "Pipelined zero-comparator for stretch timeout (preserves
> Fmax per v0 finding: 21-input OR-reduction on FSM critical
> path cost ~5 MHz on UP5K-SG48 at all seeds). Init True =
> 'already expired' safe default."

A 5 MHz reduction is 16% of the ~32 MHz UP5K-SG48 plateau
(against a 24 MHz Verde target); this risks erosion into the
seed-variance band where some seeds fail timing. Reversing a
documented Fmax mitigation is a structural engine change with
its own sim/synth cycle that does not belong folded into a
feature PR. M8 (the one-line FSM signal clear) is the
minimum-delta mitigation, mirrors the EMIT_BIT arm's existing
behavior, and lives entirely within the `stretchGuard` helper
body --- no new register, no new signal, no width change.

If a future PR has independent justification to revisit the
comparator (e.g. an Fmax recovery during a Phase C performance
pass), the combinational alternative is a valid candidate at
that time. For Phase B, I13 is the correct answer.

---

## 10. Out-of-scope follow-ups (filed here for visibility)

The following items were surfaced during the three-round
design but are not part of this PR. They are recorded so a
future PR has a hook to pick them up.

- **Decoder-side BUS_MODE join for HALT diagnostics.** Track
  the active BUS_MODE at program-load time and join it to the
  HALT word in the host-side `mole-loader` decoder when it
  lands. Closes the residual diagnosability gap noted in
  reliability R3 §1 caveat 1.
- **`fpga/Mole/AGENTS.md` "Per-program reset" subsection
  clarification.** Add a one-line note that the stretch guard
  regs (`stretchTimeoutCtr`, `waitingForStretch`,
  `stretchTimeoutCtrIsZero`) are write-on-trigger and do not
  require explicit per-program reset; the rising-edge
  `programStart` reset of architectural regs covers them by
  inclusion. May ship inside the Phase B feat commit at the
  implementing agent's discretion.
- **`:1840` assumption comment.** One-line comment near
  `EnginePipeline.scala:1840` noting the `xQIdx := 2`
  fall-through assumes the Q1->Q2 entry boundary; a future
  bearer arming at a different quarter boundary would need a
  predicate-driven fall-through. Observability debt only.
- **Future bearer (v0.5 `raw_override`) helper-shape
  re-evaluation.** When a third WIRE-bearer caller of
  `stretchGuard` arrives, re-run the inline-vs-helper
  trade-off with the third caller's actual signature visible.
  If the third caller would require a third helper parameter,
  the §4.2 two-parameter cap triggers a re-inline across all
  three call sites.
- **Combinational `stretchTimeoutCtrIsZero` revisit.** If a
  Phase C performance pass independently finds Fmax headroom
  on UP5K-SG48 or moves to a higher-Fmax target (ECP5 / Mole
  Rojo), the §9.3 alternative may be revisited as a separate
  `refactor(engine)` PR with its own synth cycle.

---

## End of Phase B design spec.
