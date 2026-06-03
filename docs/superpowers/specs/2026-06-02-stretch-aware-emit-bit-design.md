---
date: 2026-06-02
topic: stretch-aware EMIT_BIT
status: draft
related-commits:
  - 22a670860853 fix(engine): hold SDA one fabric cycle past SCL fall
  - 177cc92e7176 fix(fixtures): invert ACK-slot branch condition
---

# Stretch-aware controller-role `EMIT_BIT`

## 1. Problem

Mole's controller-role bit-cycle FSM runs open-loop off
`QuarterBitTimer`. `EMIT_BIT`, `EMIT_QUARTER`, and `STRETCH_SCL` all
advance state on timer ticks alone --- they do not observe whether SCL
has actually released (gone high) on the wire before assuming Q2 has
started. If a slave holds SCL low past the engine's nominal Q1->Q2
boundary, the engine sails through Q2 / Q3 anyway with SCL still low on
the wire, samples SDA at a meaningless moment, advances PC, and the
slave silently drops the bit.

The only existing workaround is hand-splicing `WAIT_ON SCL_HIGH,
timeout` between every controller-role `EMIT_BIT`. The SDK does not
auto-emit it; a typical i2c-soak.moleasm is 93 hand-rolled
EMIT_BIT/EMIT_QUARTER instructions; adding `WAIT_ON` before every bit
would roughly double the program size and make the canonical I2C byte
macro unreadable. The user explicitly named this as the next pain point
to fix.

## 2. Proposal in one line

Add a `emitBitStretchWait` sub-state inside the controller-role
`emitBitState`. At the Q1->Q2 timer tick the engine commits the SCL
recessive symbol (so the OD pad releases / the PP pad drops PMOS) and
then immediately checks `observer.sclSampled`. If low, pause
`QuarterBitTimer.enable` and spin in the wait sub-state until
`observer.sclRising` fires; then reload the timer with a fresh full Q2
period and resume the normal Q2/Q3 path. Bounded by a 20-bit
fabric-cycle timeout counter (default 2^20 ~= 44 ms at 24 MHz) that
surfaces as `TIMEOUT_FLAG` + `MISMATCH_FLAG` + HALT status `0xD`. No
ISA change, no bytecode-format change.

## 3. Scope

In scope:
- Controller-role `EMIT_BIT` FSM in
  `fpga/Mole/src/hw/BitCycleEngineCore.scala` (lines ~1140-1195).
- A new `emitBitStretchWait` sub-state.
- A new 20-bit fabric-cycle countdown register
  (`stretchTimeoutCtr`).
- A `MoleConfig` constant for the timeout reload value.
- Two new sim files under `fpga/Mole/src/sim/`.
- Doc edits to `ROADMAP.md`, `fpga/Mole/AGENTS.md`,
  `fpga/Mole/WIRE_FORMAT.md`.

Out of scope:
- `EMIT_QUARTER` (stays the literal-wire-shape escape hatch).
- `STRETCH_SCL` (stays "exactly N quarters, ignoring slave").
- Target-role opcodes (`SAMPLE_BIT_ON_SCL` / `DRIVE_BIT_ON_SCL`
  already pace off external SCL edges).
- Result-ring drain pipeline (user deferred separately).
- Adversarial back-to-back stretching (v0 assumption: slave releases
  for >=1 `tHIGH` between stretches).
- Per-bit or whole-program opt-out flags (`EMIT_QUARTER` is the
  documented escape hatch).

## 4. Design decisions

| # | Topic | Decision | Rationale |
|---|---|---|---|
| 1 | Layer | Engine FSM, hardware only | Every existing `.mole.bin` benefits immediately; bytecode contract preserved. |
| 2 | Where the guard fires | Q1->Q2 only | Only the SCL release edge is constrained by the I2C/I3C spec; minimal FSM surface; composes cleanly with EMIT_QUARTER. |
| 3 | Timeout shape | Raw 20-bit fabric-cycle counter, default 2^20 ~= 44 ms | Decouples from per-mode quarter divider; one default covers SMBus (35 ms) through Fast+ uniformly; no `LOAD_TIMING` slot pressure. |
| 4 | PP-class policy | Immediate HALT 0xD (no wait) | Compliance-strict: stretching I3C-PP slave is spec-illegal; surface the violation, do not mask it. |
| 5 | Opt-out flag | None at `MoleConfig` level | EMIT_QUARTER is already the documented per-bit escape; one-bitstream contract preserved. |
| 6 | HALT status | `0xD` (engine-trap range) | `0xA` is in the caller-defined range and would collide; `0xD` is the lowest unallocated engine-trap slot per the `BitCycleEngineCore.scala` line 80 contract. |
| 7 | Adversarial stretching | Document v0 assumption; defer | "Back-to-back stretch" testing requires explicit EMIT_QUARTER programs anyway. |
| 8 | Sim split | Two files, six cases | Timing-vs-role separation; matches architect's recommendation. |

## 5. FSM mechanic (pseudo-Scala)

Inside the existing `emitBitState`, on the timer tick that would
advance `qIdx 1 -> 2`:

```scala
when(qIdx === 1 && !roleReg) {
  // Commit the Q2 SCL symbol (recessive) NOW --- regardless of
  // whether the slave is stretching, the engine must STOP requesting
  // dominant before observing the wire. Under OD this is Hi-Z; under
  // PP this drops the PMOS to recessive-drive.
  val scl = SymbolDecoder(SclWaveformGen(U(2, 2 bits)), busModeReg)
  sclDriveLow  := scl.driveLow
  sclDriveHigh := scl.driveHigh

  when(!observer.sclSampled) {
    // Slave is stretching SCL low.
    when(BusModeOps.isPpClass(busModeReg)) {
      // PP-class slaves must not stretch --- spec violation; HALT.
      mismatchFlag := True
      enterHalt(B"1101")    // 0xD
    } otherwise {
      // OD-class: wait for release with timeout.
      goto(emitBitStretchWait)
    }
  } otherwise {
    qIdx := 2               // normal path
  }
}
```

```scala
val emitBitStretchWait: State = new State {
  onEntry {
    stretchTimeoutCtr := U(cfg.stretchTimeoutCycles, 20 bits)
  }
  whenIsActive {
    timerEnable := False    // quarter timer PAUSED

    when(observer.sclRising) {
      timer.io.load := True // reload fresh Q2 period
      qIdx := 2
      goto(emitBitState)    // resume canonical path
    } elsewhen (stretchTimeoutCtr === 0) {
      timeoutFlag  := True
      mismatchFlag := True
      enterHalt(B"1101")    // 0xD
    } otherwise {
      stretchTimeoutCtr := stretchTimeoutCtr - 1
    }
  }
}
```

State diagram (controller-role `EMIT_BIT` only):

```
fetch -> decode --(EMIT_BIT)--> emitBit{Q0}
                                  | tick
                                  v
                                emitBit{Q1}
                                  | tick
                                  v
                       sclSampled? --YES--> emitBit{Q2} -> {Q3} -> next
                                  |
                                  NO
                                  v
                       BUS_MODE PP-class?
                          |               |
                         YES              NO (OD-class)
                          |               |
                          v               v
                  HALT(0xD)       emitBitStretchWait
                                    | sclRising         | timeout
                                    v                   v
                                  emitBit{Q2}        HALT(0xD)
```

## 6. Files and rough line-count delta

| File | Delta lines | What |
|---|---|---|
| `fpga/Mole/src/hw/BitCycleEngineCore.scala` | +60 / -2 | New state + Q1->Q2 guard + `stretchTimeoutCtr` + HALT 0xD + comment block |
| `fpga/Mole/src/hw/MoleConfig.scala` | +6 | `stretchTimeoutCycles: Int = 1 << 20` field with docstring |
| `fpga/Mole/src/hw/Instruction.scala` | +6 | Comment-only: STRETCH_SCL "exactly N, ignores slave"; EMIT_BIT "stretch-aware at Q1->Q2" |
| `fpga/Mole/src/sim/BitCycleEngineStretchSim.scala` | +200 (new) | 4 timing scenarios |
| `fpga/Mole/src/sim/BitCycleEngineStretchRoleSim.scala` | +120 (new) | 2 role scenarios |
| `fpga/Mole/Makefile` | +6 | Two new `sim-engine-stretch{,-role}` targets + .PHONY + aggregate |
| `ROADMAP.md` | +20 / -3 | "Known risks & mitigations" entry 4 marked resolved; new subsection under "Canonical EMIT_BIT shape" |
| `fpga/Mole/AGENTS.md` | +12 | One paragraph under "Quarter-bit is the timing unit" noting the auto-guard |
| `fpga/Mole/WIRE_FORMAT.md` | +6 | HALT status table (currently no explicit table) gains `0xD = stretch fault` row; if no table exists, add one |
| `docs/superpowers/specs/2026-06-02-stretch-aware-emit-bit-design.md` | +250 (this doc) | Spec |

Total: ~+700 / -5. No host-side encoder change. No `mole-asm` change.
Zero new wire-format bits.

## 7. Wire-format and ROADMAP impact

| Commitment | Touched? | Breaking? |
|---|---|---|
| 15-opcode ISA at `[15:11]` | No | No |
| Flag triple `[2:0]` | No | No |
| `tx_symbol` 2-bit / reserved `0b11` | No | No |
| `BUS_MODE` symbol mapping | No | No |
| `LOAD_TIMING` divider format | No | No |
| Sticky flag write-once-until-overwritten contract | Yes - `TIMEOUT_FLAG` + `MISMATCH_FLAG` gain a new deterministic writer (the stretch guard) | **No** (still write-once-until-overwritten) |
| HALT status code namespace | Yes - defines previously-reserved `0xD` in the engine-trap range (`0xD..0xF`) | No (was reserved) |
| Bytecode binary encoding | No | **No** |

This is a **pure engine behavioural refinement**, observable on the
wire but invisible to the bytecode encoder. Old bytecode runs unchanged
and runs *better* against stretching slaves.

## 8. Regression sims

### `fpga/Mole/src/sim/BitCycleEngineStretchSim.scala` --- timing

Four scenarios:

1. **`stretchQ1ToQ2_releasesBeforeTimeout`** --- slave (simulated via
   `dut.io.bus.scl.read`) holds SCL low for K fabric cycles past
   nominal Q2 entry; engine pauses, resumes on observed rising edge,
   completes bit, captures correct SDA value, no MISMATCH set.
2. **`stretchQ3ToQ0_crossBitBoundary`** --- slave holds SCL low through
   the next bit's nominal Q0/Q1 entry; the *next* bit's Q1->Q2 guard
   catches the stall correctly. Proves the previous bit's clean Q3
   release didn't strand state.
3. **`stretchNeverReleased_timesOutAndHalts`** --- slave wedges SCL low;
   engine times out at `cfg.stretchTimeoutCycles`, sets `TIMEOUT_FLAG`
   + `MISMATCH_FLAG`, halts with status `0xD`.
4. **`stretchUnderPpMode_haltsImmediately`** --- `i3c-PP` `BUS_MODE`;
   any observed stretch at Q1->Q2 halts with status `0xD` immediately
   (no wait state entered).

### `fpga/Mole/src/sim/BitCycleEngineStretchRoleSim.scala` --- role

Two scenarios:

1. **`targetRole_emitBitNoStretchGuard`** --- boot with
   `MoleConfig(role = Target)`; program emits `EMIT_BIT` (with
   simulated SCL low on the wire); the stretch guard must NOT fire
   (target role: engine should not generate SCL; the `!roleReg`
   gate excludes the Q1->Q2 guard). Engine proceeds open-loop
   exactly as pre-fix.
2. **`midProgramSetRole_switchesPathCleanly`** --- boot Controller;
   `EMIT_BIT` with stretch (guard fires, waits, resumes); `SET_ROLE
   target`; `SAMPLE_BIT_ON_SCL` (external SCL paces correctly). Both
   arms work in one bitstream.

## 9. Doc edits

- **`ROADMAP.md` "Known risks & mitigations" entry 4** ("Async
  clock-stretching semantics"): mark resolved; cross-reference new
  section.
- **`ROADMAP.md` new subsection under "Canonical EMIT_BIT shape"**
  titled "Stretch-aware Q2 entry" --- 1 paragraph describing the auto-
  guard, the timeout, and the HALT 0xD contract.
- **`fpga/Mole/AGENTS.md` "Quarter-bit is the timing unit"**: one
  bullet noting EMIT_BIT auto-guards at Q1->Q2 and EMIT_QUARTER does
  not.
- **`fpga/Mole/WIRE_FORMAT.md`**: there is currently no HALT status
  code table in the wire-format doc (verified during spec review).
  This change must **add** one, covering at least: `0x0..0xC =
  caller-defined`, `0xD = stretch fault (engine-detected: slave held
  SCL low past timeout in OD class, or PP-class slave stretched at
  all)`, `0xE = reserved`, `0xF = malformed instruction
  (engine-detected: reserved opcode, reserved cond code, reserved
  tx_symbol, invalid SET_BUS_MODE wire value, JMP out of range)`.

## 10. Failure modes the design handles

| # | Scenario | Engine behaviour |
|---|---|---|
| 1 | Slave stretches Q1->Q2 (common) | Pause; resume on `sclRising`; sample SDA cleanly in fresh Q2 dwell. |
| 2 | Slave stretches Q3->Q0 (cross-bit) | Caught by the *next* bit's Q1->Q2 guard. Previous bit's Q3 already released SCL recessive. |
| 3 | Slave never releases (wedge) | Bounded by `cfg.stretchTimeoutCycles`; sets `TIMEOUT_FLAG` + `MISMATCH_FLAG`; HALT 0xD. No infinite spin. |
| 4 | PP-class BUS_MODE with stretching slave | Detected; immediate HALT 0xD; mismatch surfaced. (No wait state entered.) |
| 5 | Same-cycle release at the guard check | `sclSampled` is already-synchronised; if high at check, guard never engages; no spurious pause. |
| 6 | EMIT_QUARTER user-issued stretch shape | Untouched: `emitQuarterState` does not consult the guard. User retains literal-wire-shape control. |
| 7 | STRETCH_SCL controller-role fuzz stretch | Untouched: forces SCL low for exactly N quarters; auto-guard does not apply. |
| 8 | Engine running in target role | `!roleReg` gate excludes the guard; target-role engine never auto-stretches against itself. |

## 11. Spec subtleties the implementor must not miss

1. **2-FF synchroniser latency on `sclSampled`** (`BusObserver.scala`
   28-34). The `sclSampled` we observe is 2 fabric cycles behind the
   pad. When detecting stretch we are looking at the bus 2 cycles
   ago. The `onEntry` reload of the quarter timer compensates by
   giving Q2 a **full fresh period**, not a residual. Do not attempt
   to subtract 2 cycles or "catch up".
2. **Use level (`!sclSampled`) for detection**, not `sclRising`. A
   rising edge already happened (we just didn't see it yet) is fine ---
   we only care that the line is currently low. Use `sclRising` only
   for the exit condition.
3. **`MoleConfig.role` register exception applies.** Both arms
   elaborate; the `!roleReg` gate is runtime, not Scala-time. The
   stretch wait state itself elaborates unconditionally but is
   unreachable from target-role programs.
4. **Do NOT add a `releaseAll()` helper in the new state.** Per
   `fpga/Mole/AGENTS.md` "Registered drivers, not per-state
   combinational drives" --- touch only the registers that change. The
   recessive-commit IS the SCL change; leave SDA alone (it stays at
   the bit's `tx_symbol` per the canonical hold).
5. **The recessive-commit at the would-be-Q2 tick is
   unconditional** --- it happens even before the stretch check.
   Otherwise a slave that releases the same cycle we'd enter the wait
   would see the engine still requesting low.
6. **`stretchTimeoutCtr` reset on entry, not on construction.** Use
   `onEntry { ... := cfg.stretchTimeoutCycles }`. The reg's `init`
   value can be 0 (we never read it without going through the
   `onEntry` write first).
7. **`MISMATCH_FLAG` write in the wait-state timeout path** competes
   with the EMIT_BIT Q2-tick mismatch write. The wait-state path only
   fires on timeout; the EMIT_BIT Q2 path requires `mask=1`. They
   cannot fire on the same cycle (timeout fires inside wait state;
   Q2 mismatch fires after wait state exits). No race.
8. **20-bit counter width.** 2^20 at 24 MHz = ~44 ms. Specifically
   wide enough for SMBus (35 ms). Cost: 20 FFs + ~5 LUTs for the
   compare. Acceptable on UP5K-SG48.

## 12. v0 assumptions documented for future work

- **Slave releases for >=1 full `tHIGH` between stretches.** A slave
  that begins stretching the next bit's Q1->Q2 before the engine has
  finished sampling the current bit's Q2/Q3 will sample at the wrong
  moment. Programs that need to test back-to-back stretching use
  `EMIT_QUARTER` explicitly.
- **Single timeout value covers all BUS_MODE classes.** SMBus
  (35 ms ceiling) is the binding constraint; Fast+ (250 ns quarter)
  rarely needs anywhere near 44 ms. Per-mode timeouts deferred to
  v0.5 if compliance use cases demand them.
- **No bytecode-visible opt-out.** Compliance programs that need
  whole-program "ignore slave stretching" use a build-time `MoleConfig`
  override of `stretchTimeoutCycles = 1` (engine times out on cycle
  0, halts immediately, host sees `status=0xD`). Effectively the same
  as "ignore stretching" --- every stretched bit becomes a clean HALT
  the host can detect.

## 13. nextpnr timing impact (estimate)

Current Fmax = 24.16 MHz at the pinned seed (commit
22a670860853). The new state machine adds:
- 1 new `State` arm (1 FSM-encoding bit if the codegen needs to widen
  the state register; otherwise 0).
- 20-bit `stretchTimeoutCtr` register.
- Comparison `stretchTimeoutCtr === 0` (5-6 LUT).
- Decrement path on `stretchTimeoutCtr` (~20 LUTs in a ripple, or
  6 LUTs + cascade in the UP5K carry chain).
- Guard mux on `qIdx === 1 && !roleReg && !observer.sclSampled` (~3
  LUTs).

Conservatively: **+30 LUTs, +21 FFs**. Estimated Fmax impact: <0.2
MHz, likely landing at ~24.0 MHz. The pinned-seed Makefile note
already warns that the design runs at the timing edge; if the build
falls below 24.0 MHz after this change, a seed sweep (per the
Makefile comment on lines 60-70) is the right response, not a design
change.

**User constraint (overriding the architect's "<0.2 MHz" estimate):
the design MUST close timing at >= 24.00 MHz at the pinned seed.**
If the implementation drives Fmax below 24.00 MHz, the implementor
runs a seed sweep and re-pins the Makefile's `--seed` value to a
passing seed (commit-message footer: "Seed re-pinned to N for
post-stretch-aware timing closure; pre-change passing seed was 3").
Lowering the design clock or shrinking features to fit is **not** an
acceptable response.

## 14. Implementation handoff

When this spec is approved and we exit plan mode, the implementor
agent should:

1. Create `docs/superpowers/specs/` (currently absent) and write this
   file verbatim.
2. Invoke the **writing-plans** skill to produce an implementation
   plan against this spec.
3. The plan should sequence: sim files first (TDD), then engine FSM,
   then docs, then verify timing closure via `make all`.
4. Recommended subagent split per AGENTS.md §9: **tester** drafts the
   sim cases first, **reliability** does an adversarial review of the
   FSM mechanic against the failure-mode table, **coder** implements
   the Scala, **reviewer** validates against the spec before commit.
5. Each artefact is a separate commit per the project's commit
   discipline:
   - `feat(engine): add stretch-aware Q2 entry to EMIT_BIT` (the
     engine change + MoleConfig + Instruction.scala comments)
   - `test(engine): add stretch-aware EMIT_BIT regression sims` (the
     two new sim files + Makefile)
   - `docs(engine): document stretch-aware Q2 entry contract`
     (ROADMAP + AGENTS + WIRE_FORMAT)

Each commit must include the `Co-authored-by: Copilot
<223556219+Copilot@users.noreply.github.com>` trailer per AGENTS.md
§3.8.

## 15. Out-of-scope follow-ups (filed here for visibility)

These came up during brainstorming but are explicitly NOT addressed
by this spec:

- **Result-ring overflow / live drain.** The current "ring" is a
  one-shot buffer; long-running programs cannot be observed live.
  User has deferred this work explicitly.
- **I2C-Fast `tLOW` budget.** At 400 kHz with
  `quarterPeriodCyclesReset = 6`, SCL-low is 1250 ns vs UM10204 rev
  7 spec min 1300 ns (50 ns under). Pre-existing; not affected by
  this spec.
- **Adversarial back-to-back stretching.** v0 assumption documented
  above; characterization sim deferred.
- **Per-BUS_MODE timeout defaults.** One value (`2^20 fabric cycles`)
  covers all modes; per-mode tunability deferred to v0.5 if needed.
- **CAPTURE garbage past MARK in i2c-soak loader output.** Already
  flagged on user's separate TODO; result-ring trailing-garbage is a
  loader-side decode issue.
