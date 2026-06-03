# Stretch-aware EMIT_BIT --- Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the controller-role `EMIT_BIT` opcode auto-synchronise
with a slave that stretches SCL, so user programs no longer need to
hand-splice `WAIT_ON SCL_HIGH` between every bit.

**Spec:**
[`docs/superpowers/specs/2026-06-02-stretch-aware-emit-bit-design.md`](../specs/2026-06-02-stretch-aware-emit-bit-design.md)

**Architecture:** Add an `emitBitStretchWait` sub-state inside
`BitCycleEngineCore`'s existing `emitBitState`. At the Q1->Q2 timer
tick the engine commits the SCL recessive symbol and checks
`observer.sclSampled`. If low under OD-class `BUS_MODE`, pause the
`QuarterBitTimer` via `enable=False` and spin in the wait state until
`observer.sclRising`, then reload the timer with a full Q2 period.
Under PP-class `BUS_MODE` the same check produces an immediate
HALT 0xD. Bounded by a 20-bit fabric-cycle timeout (default 2^20 ~=
44 ms at 24 MHz). No ISA / bytecode / wire-format change.

**Tech Stack:** SpinalHDL (Scala 2.13), SpinalSim (Verilator
back-end), sbt, nextpnr-ice40 for synthesis, yosys for elaboration.

**Hard constraint:** `make all` MUST close timing at >= 24.00 MHz at
the pinned seed. If the build falls below that, run a seed sweep and
re-pin the Makefile `--seed` value, not lower the design clock.

---

## File structure

| File | Role | Status |
|---|---|---|
| `fpga/Mole/src/hw/MoleConfig.scala` | Add `stretchTimeoutCycles: Int = 1 << 20` field | Modify |
| `fpga/Mole/src/hw/BitCycleEngineCore.scala` | Add `stretchTimeoutCtr` reg + Q1->Q2 guard + `emitBitStretchWait` sub-state + HALT 0xD path | Modify |
| `fpga/Mole/src/hw/Instruction.scala` | Comment-only: STRETCH_SCL "exactly N, ignores slave"; EMIT_BIT "stretch-aware at Q1->Q2" | Modify |
| `fpga/Mole/src/sim/BitCycleEngineStretchSim.scala` | 4 timing scenarios | Create |
| `fpga/Mole/src/sim/BitCycleEngineStretchRoleSim.scala` | 2 role scenarios | Create |
| `fpga/Mole/Makefile` | Two new `sim-engine-stretch{,-role}` targets + .PHONY + aggregate | Modify |
| `ROADMAP.md` | "Async clock-stretching" risk marked resolved; new subsection under "Canonical EMIT_BIT shape" | Modify |
| `fpga/Mole/AGENTS.md` | Bullet under "Quarter-bit is the timing unit" | Modify |
| `fpga/Mole/WIRE_FORMAT.md` | Add HALT status table; document 0xD = stretch fault | Modify |

The sim DUTs reuse the existing `BitCycleEngineTargetDut` (which
takes `cfg: MoleConfig` and supports both roles via `cfg.role`); no
new sim DUT scaffolding needed.

---

## Task 1: Tester drafts failing timing-scenario sims (RED)

**Files:**
- Create: `fpga/Mole/src/sim/BitCycleEngineStretchSim.scala`
- Modify: `fpga/Mole/Makefile`

This task lands the four timing-scenario sims **before** any engine
change. They must FAIL on the current head (proves they exercise
real behaviour), and PASS after Task 3.

**Sub-agent:** `tester` (premium model --- adversarial sim design).

- [ ] **Step 1.1: Write the sim file**

Create `fpga/Mole/src/sim/BitCycleEngineStretchSim.scala` with
four `runTest` cases. Use `BitCycleEngineTargetDut` as the DUT
(takes `cfg: MoleConfig`; works in either role). Pattern after
existing `fpga/Mole/src/sim/BitCycleEngineEmitBitDataHoldSim.scala`
for the helper shape (loaderWrite, sampleBus, runMode).

Required cases:

1. `stretchQ1ToQ2_releasesBeforeTimeout`
   - Config: `cfg.role = Controller`, default
     `quarterPeriodCyclesReset = 6`, `BUS_MODE = i2c` via
     `SET_BUS_MODE i2c` in the program.
   - Program: `SET_BUS_MODE i2c; EMIT_BIT tx=dominant; HALT 0`.
   - Stim: hold `dut.io.bus.scl.read := false` from the moment
     the engine drives SCL recessive (detected by watching
     `dut.io.bus.scl.driveHigh`/`driveLow` falling to released
     state) for ~30 fabric cycles, then release to true.
   - Assert: engine eventually reports `done = true`; HALT word
     `status = 0x0` (clean caller halt), `mismatch = false`,
     `overflow = false`.

2. `stretchQ3ToQ0_crossBitBoundary`
   - Program: `SET_BUS_MODE i2c; EMIT_BIT tx=dominant;
     EMIT_BIT tx=recessive; HALT 0`.
   - Stim: hold SCL low from the *first* bit's would-be Q2 release
     all the way through the second bit's would-be Q1->Q2; then
     release before the timeout fires.
   - Assert: clean HALT 0x0, `mismatch = false`.

3. `stretchNeverReleased_timesOutAndHalts`
   - Program: `SET_BUS_MODE i2c; EMIT_BIT tx=dominant; HALT 0`.
   - Config: shrink `stretchTimeoutCycles` to something small
     (e.g. 64) via a custom `MoleConfig` so the sim doesn't
     spin 2^20 cycles. The sim's `cfg` value will be one of
     the inputs the implementer (Task 3) must accept.
   - Stim: hold SCL low forever.
   - Assert: HALT word `status = 0xD`, `mismatch = true`,
     `overflow = false`.

4. `stretchUnderPpMode_haltsImmediately`
   - Program: `SET_BUS_MODE i3c-PP; EMIT_BIT tx=dominant;
     HALT 0`.
   - Note: I3C-PP needs `LOAD_TIMING i3c_pp_freq, N` *before*
     `SET_BUS_MODE` so the timing slot is populated. Use the
     reset divider value (program: `LOAD_TIMING i3c_pp_freq, 6;
     SET_BUS_MODE i3c-pp; EMIT_BIT tx=dominant; HALT 0`).
   - Stim: hold SCL low from the moment the engine enters
     `EMIT_BIT`.
   - Assert: HALT word `status = 0xD`, `mismatch = true`. No
     wait-state spin (verify by capping observation cycles
     at, say, 200 fabric cycles --- engine must HALT in well
     under that, no waiting for timeout).

Use the `Instruction.encode` helpers via the package-shared
`Instruction._` import the existing sims use. Use the same
`compiled` cache pattern (lazy `SimConfig.withWave.compile(...)`)
to share Verilator builds across runs.

The sim file MUST end with `runTest` calls inside `def main(args:
Array[String]): Unit` (extends `App` works too), and print a final
`println("BitCycleEngineStretchSim OK")` on success, matching the
style of `BitCycleEngineEmitBitDataHoldSim.scala`.

- [ ] **Step 1.2: Wire up the Makefile target**

Edit `fpga/Mole/Makefile`:

In the `sim:` aggregate target line continuation (~line 92):

```
     sim-engine-jmp-boundary sim-top-cts-violation \
     sim-engine-emit-bit-data-hold \
     sim-engine-stretch ## Run all simulations
```

Add the target rule below `sim-engine-emit-bit-data-hold`:

```
sim-engine-stretch: ## Stretch-aware EMIT_BIT timing scenarios (Q1->Q2 guard)
	$(SBT) -batch "runMain $(PACKAGE).BitCycleEngineStretchSim"
```

In the `.PHONY` line at the bottom (~line 241):

```
        sim-engine-emit-bit-data-hold \
        sim-engine-stretch \
        gen waves flash clean distclean help
```

- [ ] **Step 1.3: Run the sim and verify it FAILS on current head**

```bash
cd fpga/Mole && make sim-engine-stretch 2>&1 | tail -30
```

Expected: AT LEAST ONE of the four cases fails with an assertion
about the HALT status being unexpected (likely the
`stretchNeverReleased` case will succeed with status=0 because
the current engine doesn't notice SCL is low, but
`stretchUnderPpMode_haltsImmediately` should also fail because
no HALT 0xD is set). At least one assertion failure is sufficient
proof of TDD-red.

If ALL FOUR cases pass on the unmodified engine, the sim doesn't
actually exercise the new behaviour --- go back to Step 1.1 and
tighten the assertions until at least the `stretchUnderPpMode`
or `stretchNeverReleased` case fails.

- [ ] **Step 1.4: Verify hygiene**

```bash
python3 -c "b = open('fpga/Mole/src/sim/BitCycleEngineStretchSim.scala','rb').read(); print(f'len={len(b)} hasCR={13 in b} bom0={b[0]} tail={b[-1]}')"
```

Expected: `hasCR=False`, `tail=10`.

- [ ] **Step 1.5: DO NOT COMMIT YET**

The Makefile change is staged with the engine-test commit at
Task 6. Leave changes unstaged.

---

## Task 2: Tester drafts failing role-scenario sims (RED)

**Files:**
- Create: `fpga/Mole/src/sim/BitCycleEngineStretchRoleSim.scala`
- Modify: `fpga/Mole/Makefile` (already touched in Task 1)

**Sub-agent:** `tester` (premium model).

- [ ] **Step 2.1: Write the sim file**

Create `fpga/Mole/src/sim/BitCycleEngineStretchRoleSim.scala` with
two `runTest` cases:

1. `targetRole_emitBitNoStretchGuard`
   - Config: `cfg.role = EngineRole.Target` (boot default Target).
   - Program: `SET_BUS_MODE i2c; EMIT_BIT tx=dominant; HALT 0`.
   - Stim: hold `bus.scl.read := false` throughout.
   - Assert: engine reaches done quickly (within
     ~4 * quarter-period + fetch overhead, say 50 fabric cycles
     for `quarterPeriodCyclesReset = 6`), HALT status=0, mismatch=false.
     This proves the `!roleReg` gate excludes the stretch guard in
     target role. A target-role engine does not generate SCL anyway
     --- it must NOT spin in `emitBitStretchWait` waiting for an
     external SCL release.

2. `midProgramSetRole_switchesPathCleanly`
   - Config: `cfg.role = EngineRole.Controller`.
   - Program (in order): `SET_BUS_MODE i2c; EMIT_BIT tx=dominant;
     SET_ROLE target; SAMPLE_BIT_ON_SCL; HALT 0`.
   - Stim: phase 1 (Controller EMIT_BIT) --- hold SCL low for
     ~20 fabric cycles starting at the would-be Q2 release, then
     release; phase 2 (Target SAMPLE_BIT_ON_SCL) --- drive a
     single SCL rising edge from the testbench within
     ~50 fabric cycles after the first phase completes.
   - Assert: clean HALT status=0, mismatch=false. Both arms
     exercised in one bitstream; no deadlocks.

Use the same `BitCycleEngineTargetDut` DUT and the same helper
patterns as Task 1.

- [ ] **Step 2.2: Wire up the Makefile target**

Edit `fpga/Mole/Makefile`:

In the `sim:` aggregate (continue from Task 1):

```
     sim-engine-emit-bit-data-hold \
     sim-engine-stretch sim-engine-stretch-role ## Run all simulations
```

Add target rule below `sim-engine-stretch`:

```
sim-engine-stretch-role: ## Stretch-aware EMIT_BIT role-gating (Controller vs Target)
	$(SBT) -batch "runMain $(PACKAGE).BitCycleEngineStretchRoleSim"
```

Update `.PHONY` to include `sim-engine-stretch-role`.

- [ ] **Step 2.3: Run the sim and verify it FAILS on current head**

```bash
cd fpga/Mole && make sim-engine-stretch-role 2>&1 | tail -30
```

Expected: BOTH cases pass on the current head (because there is no
stretch guard yet, target role behaves identically to controller
role for EMIT_BIT, and the no-stretch controller case is unchanged).
This is **intentional** --- this sim is a regression guard, not a
TDD-red test. Its job is to catch the case where Task 3 accidentally
makes the guard fire in target role.

If a case fails on the current head, the test is over-constrained;
loosen it. Document the "regression-guard, not TDD-red" rationale
in a comment at the top of the file.

- [ ] **Step 2.4: Verify hygiene** (same pattern as Task 1.4)

- [ ] **Step 2.5: DO NOT COMMIT YET**

---

## Task 3: Reliability adversarial review of FSM mechanic

**Files:** read-only review of the spec FSM mechanic + the failing
sims from Tasks 1-2.

**Sub-agent:** `reliability` (premium model).

This task produces a written review, not code. Output goes to the
coder subagent (Task 4) as additional input.

- [ ] **Step 3.1: Dispatch reliability subagent**

The reliability subagent reviews:
- The spec FSM mechanic (Section 5 of the spec).
- The 8 failure modes (Section 10).
- The 8 spec subtleties (Section 11).
- The two failing sim files from Tasks 1-2.

It must identify, BEFORE the coder writes any Scala:
- Any race conditions in the proposed pseudo-Scala
  (timer-load-vs-tick, role-flip-mid-stretch, simultaneous
  flag writes).
- Any failure modes the spec didn't list (e.g. what if `io.start`
  re-fires while `emitBitStretchWait` is active? What if a
  bus-mode-switching opcode lands inside the wait?).
- Whether the `MISMATCH_FLAG` write in the timeout path can race
  with the EMIT_BIT Q2-tick `MISMATCH_FLAG` write (spec subtlety
  #7 claims no; verify).
- Whether `stretchTimeoutCtr` underflow (the decrement going
  below 0) needs explicit guarding.
- Whether `onEntry` SpinalHDL semantics actually fire on the cycle
  the FSM transitions into `emitBitStretchWait`, or one cycle
  later --- this affects the timeout count.

The deliverable is a markdown-formatted findings document with
each finding tagged as either **must-fix-before-coding** or
**nice-to-fix** or **noted-as-OK-after-analysis**.

- [ ] **Step 3.2: Apply any must-fix findings**

If the reliability review surfaces must-fix findings, **the
controller (you)** mediates: either (a) the spec is wrong and
needs an inline update before Task 4 begins, or (b) the spec is
right and a sim case must be added to cover the surfaced corner.

Add any new sim cases to the relevant Task 1 or Task 2 sim file
and re-verify they fail (red) on the current head.

- [ ] **Step 3.3: DO NOT COMMIT** (review only; no code change)

---

## Task 4: Coder implements engine FSM + MoleConfig (GREEN)

**Files:**
- Modify: `fpga/Mole/src/hw/MoleConfig.scala`
- Modify: `fpga/Mole/src/hw/BitCycleEngineCore.scala`
- Modify: `fpga/Mole/src/hw/Instruction.scala` (comments only)

**Sub-agent:** `coder` (standard model --- mechanical implementation
of a well-specified change).

- [ ] **Step 4.1: Add `stretchTimeoutCycles` to MoleConfig**

Edit `fpga/Mole/src/hw/MoleConfig.scala`. Add a new field between
`uartBaud` and `role` (preserve the `role` field's position --- it's
load-bearing for `cfg.role` in tests):

```scala
  uartBaud: Int = 1_000_000,
  stretchTimeoutCycles: Int = 1 << 20,
  role: EngineRole = EngineRole.Controller
```

Add a docstring entry above the field (insert near line 78, before
the `@param role` block):

```scala
  /** Stretch-wait timeout in fabric cycles, applied at the Q1->Q2
    * boundary of every controller-role `EMIT_BIT` when the slave
    * is observed to be stretching SCL low. Default 2^20 ~= 44 ms
    * at 24 MHz fabric, comfortably above SMBus tTIMEOUT (35 ms) and
    * any plausible I2C/I3C wakeup; small enough that a genuinely
    * wedged slave produces a deterministic HALT rather than an
    * infinite spin. See `ROADMAP.md` §"Stretch-aware Q2 entry" for
    * the engine-side contract.
    *
    * Set to 1 in a custom MoleConfig to make the engine HALT
    * immediately on any observed stretch (useful for compliance
    * tests that need to surface stretch as a violation rather
    * than tolerate it).
    */
  stretchTimeoutCycles: Int = 1 << 20,
```

Add the `require`:

```scala
  require(
    stretchTimeoutCycles >= 1,
    s"stretchTimeoutCycles=$stretchTimeoutCycles must be >= 1"
  )
```

- [ ] **Step 4.2: Add stretchTimeoutCtr register to BitCycleEngineCore**

Edit `fpga/Mole/src/hw/BitCycleEngineCore.scala`. After the
`stretchQs` declaration (~line 432) and before the LOAD_TIMING
section, add:

```scala
  // ------------------------------------------------------------------
  // Stretch-wait countdown counter (see ROADMAP §"Stretch-aware Q2
  // entry" and docs/superpowers/specs/2026-06-02-stretch-aware-
  // emit-bit-design.md §11.6).
  //
  // 20-bit raw fabric-cycle counter loaded on entry to
  // `emitBitStretchWait` and decremented every active cycle of that
  // state. When it reaches zero the wait state HALTs with status 0xD
  // and sets TIMEOUT_FLAG + MISMATCH_FLAG. Init value is 0 because
  // the FSM never reads the reg before `onEntry` writes it.
  // ------------------------------------------------------------------
  val stretchTimeoutCtr = Reg(UInt(20 bits)) init U(0, 20 bits)
```

If the actual line numbers in the current file differ, search for
`val stretchQs` and place the new block immediately after it.

- [ ] **Step 4.3: Patch the EMIT_BIT decode arm in `emitBitState`**

Find the `emitBitState` block (`when(timer.io.tick)`). Inside the
`otherwise` arm of the `when(qIdx === 3)` (the path that advances
`qIdx` to the next quarter), wrap the `nextQ === 2` case with the
stretch guard. Replace the existing block:

```scala
          } otherwise {
            val nextQ = qIdx + 1
            qIdx := nextQ
            // Update SCL for the new quarter. SDA stays as latched on
            // entry to this state (held for the full bit per spec).
            // Target-role: leave SCL released (set in decodeState's
            // emitBit arm); the runtime `when(!roleReg)` here gates
            // the per-quarter SclWaveformGen drive the same way the
            // entry arm gates the Q0 latch. SclWaveformGen and the
            // symbol decoder elaborate unconditionally.
            val sclSym = SclWaveformGen(nextQ)
            val scl = SymbolDecoder(sclSym, busModeReg)
            when(!roleReg) {
              sclDriveLow := scl.driveLow
              sclDriveHigh := scl.driveHigh
            }
          }
```

With:

```scala
          } otherwise {
            val nextQ = qIdx + 1
            // Update SCL for the new quarter. SDA stays as latched on
            // entry to this state (held for the full bit per spec).
            // Target-role: leave SCL released (set in decodeState's
            // emitBit arm); the runtime `when(!roleReg)` here gates
            // the per-quarter SclWaveformGen drive the same way the
            // entry arm gates the Q0 latch. SclWaveformGen and the
            // symbol decoder elaborate unconditionally.
            val sclSym = SclWaveformGen(nextQ)
            val scl = SymbolDecoder(sclSym, busModeReg)
            when(!roleReg) {
              sclDriveLow := scl.driveLow
              sclDriveHigh := scl.driveHigh
            }

            // Stretch-aware Q2 entry (controller role only). On the
            // Q1->Q2 tick the engine has just committed the SCL
            // recessive symbol above; if the slave is holding SCL
            // low we must NOT advance qIdx into Q2 (the sample point
            // would land in slave-stretched dead time). Under OD-class
            // BUS_MODE we pause in the stretch-wait state until SCL
            // releases or the timeout fires; under PP-class BUS_MODE
            // the slave is in violation (PP slaves must not stretch),
            // and we HALT 0xD immediately. The qIdx update is
            // conditional on NOT entering the stretch path; if we do
            // enter the wait state, qIdx stays at 1 and we resume
            // with the same nextQ value on exit.
            when(qIdx === 1 && !roleReg && !observer.sclSampled) {
              when(BusModeOps.isPpClass(busModeReg)) {
                mismatchFlag := True
                enterHalt(B"1101") // 0xD: PP-class stretch (spec violation)
              } otherwise {
                goto(emitBitStretchWait)
              }
            } otherwise {
              qIdx := nextQ
            }
          }
```

- [ ] **Step 4.4: Add the `emitBitStretchWait` state**

Add a new state declaration in the FSM. Place it immediately after
`emitBitState`'s closing brace (search for the `val emitBitState:
State = new State` and find its matching `}` --- the new state
goes after it, before `emitQuarterState`).

```scala
    // ---------------------------------------- EmitBitStretchWait ----
    //
    // OD-class slave-stretching wait state. Reached only from
    // `emitBitState`'s Q1->Q2 tick when `!observer.sclSampled` is
    // observed in controller role under an OD-class BUS_MODE. The
    // SCL recessive symbol was already committed in the caller; SDA
    // stays at the bit's tx_symbol (per the canonical hold, do not
    // touch). The QuarterBitTimer is paused via `enable = False`
    // for the duration; on exit it is reloaded with a full fresh Q2
    // period so the sample point in Q2 sees a settled wire even
    // when the synchroniser latency rounds.
    //
    // Exit paths:
    //   - observer.sclRising fires    -> reload timer, qIdx := 2,
    //                                     return to emitBitState.
    //   - stretchTimeoutCtr === 0      -> set TIMEOUT_FLAG +
    //                                     MISMATCH_FLAG, HALT 0xD.
    //
    // Race notes:
    //   - sclRising is detected one cycle before stretchTimeoutCtr's
    //     decrement could underflow; the `elsewhen` order makes
    //     release-wins-tie deterministic.
    //   - stretchTimeoutCtr underflow is impossible: the decrement
    //     only happens when `stretchTimeoutCtr =/= 0`, and the
    //     comparison fires first via `elsewhen`.
    //   - SDA drivers are intentionally NOT touched: this preserves
    //     the canonical tHD;DAT hold contract from
    //     `BitCycleEngineEmitBitDataHoldSim`.
    val emitBitStretchWait: State = new State {
      onEntry {
        stretchTimeoutCtr := U(cfg.stretchTimeoutCycles, 20 bits)
      }
      whenIsActive {
        timerEnable := False // pause the quarter-bit timer

        when(observer.sclRising) {
          timerLoad := True // reload fresh Q2 period for the sample point
          qIdx := 2
          goto(emitBitState)
        } elsewhen (stretchTimeoutCtr === 0) {
          timeoutFlag := True
          mismatchFlag := True
          enterHalt(B"1101") // 0xD: OD-class stretch timeout
        } otherwise {
          stretchTimeoutCtr := stretchTimeoutCtr - 1
        }
      }
    }
```

- [ ] **Step 4.5: Comment-only updates to Instruction.scala**

Edit `fpga/Mole/src/hw/Instruction.scala`. Find the `EmitBit` case
class docstring (around line 290) and add a final paragraph:

```scala
  /** ... existing docstring ...
    *
    * Stretch-aware Q2 entry: in controller role under OD-class
    * BUS_MODE, the engine auto-pauses the Q1->Q2 advance until the
    * slave releases SCL (or `MoleConfig.stretchTimeoutCycles` fabric
    * cycles elapse, whichever comes first). Under PP-class BUS_MODE
    * the slave is in violation; the engine HALTs with status 0xD.
    * See ROADMAP §"Stretch-aware Q2 entry".
    */
```

Find the `StretchScl` case class docstring (around line 336) and
add:

```scala
  /** ... existing docstring ...
    *
    * `STRETCH_SCL` is the controller-role *forced-stretch* primitive
    * (also the canonical target-role stretching mechanic). It holds
    * SCL low for exactly N quarters regardless of any slave
    * stretching --- the auto-stretch-sync that `EMIT_BIT` does at
    * Q1->Q2 is NOT applied here. If a slave is also stretching
    * during a `STRETCH_SCL`, the engine still releases SCL after
    * N quarters; the next `EMIT_BIT`'s Q1->Q2 guard catches any
    * residual slave stretch.
    */
```

- [ ] **Step 4.6: Compile to catch syntax / elaboration errors**

```bash
cd fpga/Mole && sbt -batch "compile" 2>&1 | tail -10
```

Expected: `[success]`. If errors, fix per the SpinalHDL diagnostic
output. Common pitfalls: forgetting to import `spinal.lib.fsm._`
(already present in the file), referencing an out-of-scope `cfg`
inside the FSM (`cfg` is at component-scope; should be reachable).

- [ ] **Step 4.7: Run the timing sim from Task 1**

```bash
cd fpga/Mole && make sim-engine-stretch 2>&1 | tail -30
```

Expected: ALL FOUR cases pass. Concretely:
- `stretchQ1ToQ2_releasesBeforeTimeout: OK`
- `stretchQ3ToQ0_crossBitBoundary: OK`
- `stretchNeverReleased_timesOutAndHalts: OK`
- `stretchUnderPpMode_haltsImmediately: OK`
- Final line: `BitCycleEngineStretchSim OK`

If any case fails, **read the assertion message carefully** and fix
the engine FSM (Step 4.3 / 4.4) before proceeding. Do not loosen
the sim assertions; the sims are the spec.

- [ ] **Step 4.8: Run the role sim from Task 2**

```bash
cd fpga/Mole && make sim-engine-stretch-role 2>&1 | tail -20
```

Expected: BOTH cases pass; `BitCycleEngineStretchRoleSim OK`.

- [ ] **Step 4.9: Run the full engine regression suite**

```bash
cd fpga/Mole && make sim-engine-smoke sim-engine-full sim-engine-target sim-engine-jmp-boundary sim-engine-emit-bit-data-hold 2>&1 | grep -E "OK$|FAIL|Error|assertion" | head -60
```

Expected: Every line ends in `OK`. No `FAIL` / `Error` /
`assertion failed`. Specifically count:
- BitCycleEngineSmokeSim: i3c-OD OK, i3c-PP OK
- BitCycleEngineSim: 21 cases all OK
- BitCycleEngineTargetSim: 7 cases all OK
- BitCycleEngineJmpBoundarySim: 5 cases all OK
- BitCycleEngineEmitBitDataHoldSim: i2c OK, i3c-PP OK

If any pre-existing sim fails, the implementation has a regression.
Fix it before continuing.

- [ ] **Step 4.10: DO NOT COMMIT YET**

The implementation files (MoleConfig, BitCycleEngineCore,
Instruction) commit together with the test files in Task 6.

---

## Task 5: Verify nextpnr timing closure (HARD CONSTRAINT)

**Files:** none modified in this task by default; potentially
`fpga/Mole/Makefile` if a seed sweep is needed.

**Operator:** controller (you), not a subagent. Runs commands and
interprets results.

- [ ] **Step 5.1: Run full build**

```bash
cd fpga/Mole && make all 2>&1 | tail -10
```

Look for the line:

```
Info: Max frequency for clock 'pll_clkOut': XX.YY MHz (PASS at 24.00 MHz)
```

- [ ] **Step 5.2: Decision tree on result**

**If `Max frequency >= 24.00 MHz` AND `(PASS at 24.00 MHz)`:**

Done. Proceed to Task 6.

**If `Max frequency < 24.00 MHz` OR `(FAIL at 24.00 MHz)`:**

The pinned seed no longer closes timing. Run a seed sweep:

```bash
cd fpga/Mole
for s in $(seq 0 15); do
  sed -i.bak "s|--seed [0-9]*|--seed $s|" Makefile
  rm Makefile.bak
  rm -f gen/MoleTop.bin gen/MoleTop.asc gen/MoleTop.json
  echo "=== seed $s ==="
  make all 2>&1 | grep -E "Max frequency|PASS at|FAIL at" | head -5
done
```

Pick the **lowest-numbered seed** that produces both
"Max frequency >= 24.00 MHz" and "(PASS at 24.00 MHz)". Re-pin
the Makefile to that seed (the `--seed N` line ~line 81 of the
Makefile). Note the pre-change seed (3 today) and post-change
seed in the eventual commit message footer:

> Seed re-pinned from 3 to N for post-stretch-aware timing
> closure.

**If NO seed in 0..15 produces a passing build:**

ESCALATE: do not proceed. Report back to the controller (the
human). Possible remediation paths the human will choose between:
- Sweep seeds 16..31.
- Tightening the engine FSM (the architect's `+30 LUTs / +21 FFs`
  estimate may have been low).
- Pushing the design onto Mole Rojo earlier than planned.

The human will decide. **Do not** lower the design clock,
shrink features, or disable the stretch-aware behaviour. The
spec is explicit: the design MUST close timing at >= 24.00 MHz.

- [ ] **Step 5.3: Final sanity check**

```bash
cd fpga/Mole && make all 2>&1 | grep -E "Max frequency|PASS at|FAIL at"
```

Expected: one line, `Max frequency for clock 'pll_clkOut': XX.YY
MHz (PASS at 24.00 MHz)` with `XX.YY >= 24.00`.

- [ ] **Step 5.4: DO NOT COMMIT YET**

If the Makefile was modified (seed re-pinned), the change goes
into the `feat(engine):` commit at Task 6 with the seed-rotation
footer.

---

## Task 6: Commit `feat(engine)` + `test(engine)`

**Files:** Everything from Tasks 1-5.

**Operator:** controller (you).

Per the spec's Section 14, the changes split into three commits.
This task creates the first two (the engine change and the sim
catalog).

- [ ] **Step 6.1: Stage and commit `feat(engine)`**

```bash
git add fpga/Mole/src/hw/MoleConfig.scala \
        fpga/Mole/src/hw/BitCycleEngineCore.scala \
        fpga/Mole/src/hw/Instruction.scala
# Include the Makefile only if Task 5 re-pinned the seed.
# Otherwise the Makefile stays unstaged for Task 6.2.
git status
```

If the Makefile was re-pinned in Task 5, also stage it now:
```bash
git add fpga/Mole/Makefile
```

Commit:

```bash
git commit -m 'feat(engine): add stretch-aware Q2 entry to EMIT_BIT

In controller role, the bit-cycle FSM previously sailed through
the Q1->Q2 boundary on the QuarterBitTimer tick alone --- it
never observed whether SCL had actually released on the wire. A
spec-strict I2C slave (NXP LPI2C, FlexComm) that stretches SCL
past the nominal Q2 entry would have its bit silently dropped:
the engine sampled SDA at a meaningless moment, then advanced PC.

The fix:
- At every Q1->Q2 tick in controller role, the engine commits the
  SCL recessive symbol and then checks observer.sclSampled.
- If low under OD-class BUS_MODE (i2c, i3c-OD): pause the quarter
  timer via enable=False and spin in a new emitBitStretchWait
  sub-state until observer.sclRising fires, then reload the timer
  with a full fresh Q2 period and resume the normal Q2/Q3 path.
- If low under PP-class BUS_MODE (i3c-PP, hdr-ddr): the slave is
  in spec violation (PP slaves must not stretch); set MISMATCH_FLAG
  and HALT with status 0xD.
- Bounded by a new 20-bit stretchTimeoutCtr loaded from
  MoleConfig.stretchTimeoutCycles (default 2^20 ~= 44 ms at
  24 MHz fabric, comfortably above SMBus tTIMEOUT 35 ms).
- On timeout: set TIMEOUT_FLAG + MISMATCH_FLAG, HALT 0xD.

Target role is untouched (the !roleReg gate excludes the guard);
EMIT_QUARTER is untouched (literal-wire-shape escape hatch);
STRETCH_SCL is untouched (controller-side forced stretch primitive
that ignores slave stretching by design).

HALT status 0xD allocated from the engine-trap range (0xD..0xF
per the BitCycleEngineCore.scala line 80 contract). 0xE remains
reserved for future engine traps.

No ISA change, no bytecode-format change, no wire-format change.
Old bytecode runs unchanged and runs better against stretching
slaves.

Design spec:
docs/superpowers/specs/2026-06-02-stretch-aware-emit-bit-design.md

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>'
```

If the Makefile was re-pinned in Task 5, ADD this footer to the
commit body BEFORE the Co-authored-by:

```
Seed re-pinned from 3 to N for post-stretch-aware timing closure
on UP5K-SG48; Max frequency XX.YY MHz at PASS=24.00 MHz.
```

- [ ] **Step 6.2: Stage and commit `test(engine)`**

```bash
git add fpga/Mole/src/sim/BitCycleEngineStretchSim.scala \
        fpga/Mole/src/sim/BitCycleEngineStretchRoleSim.scala \
        fpga/Mole/Makefile
git status
```

If Makefile was already staged in Step 6.1 (seed re-pin), git status
will report it clean here; that's fine.

Commit:

```bash
git commit -m 'test(engine): add stretch-aware EMIT_BIT regression sims

Two new sim files cover the stretch-aware Q2 entry contract added
in the previous commit:

BitCycleEngineStretchSim --- 4 timing scenarios:
1. stretchQ1ToQ2_releasesBeforeTimeout: slave releases SCL late;
   engine pauses, resumes on observed rising edge, completes bit
   cleanly.
2. stretchQ3ToQ0_crossBitBoundary: slave holds SCL across the
   nominal bit boundary; next bit Q1->Q2 catches the residual stall.
3. stretchNeverReleased_timesOutAndHalts: slave wedges SCL low;
   engine times out (small cfg.stretchTimeoutCycles for the sim),
   HALT 0xD with MISMATCH + TIMEOUT both set.
4. stretchUnderPpMode_haltsImmediately: I3C-PP slave stretches;
   engine HALTs 0xD immediately, no wait-state spin.

BitCycleEngineStretchRoleSim --- 2 role scenarios:
1. targetRole_emitBitNoStretchGuard: !roleReg gates the guard out;
   target-role EMIT_BIT proceeds open-loop even with SCL held low.
2. midProgramSetRole_switchesPathCleanly: Controller EMIT_BIT with
   stretch + SET_ROLE target + SAMPLE_BIT_ON_SCL; both arms work
   in one bitstream.

Makefile targets sim-engine-stretch{,-role} added; both wired into
the sim: aggregate and .PHONY list.

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>'
```

- [ ] **Step 6.3: Verify both commits landed locally**

```bash
git log --oneline -5
git status
```

Expected: top two commits are the new ones; working tree clean.

---

## Task 7: Docs subagent updates ROADMAP / AGENTS / WIRE_FORMAT

**Files:**
- Modify: `ROADMAP.md`
- Modify: `fpga/Mole/AGENTS.md`
- Modify: `fpga/Mole/WIRE_FORMAT.md`

**Sub-agent:** `docs` (standard model --- documentation polish).

Per the spec's Section 9.

- [ ] **Step 7.1: Update ROADMAP.md**

Two edits:

(a) The existing "Known risks & mitigations" entry 4 (around
line 1594) currently reads:

```
4. **Async clock-stretching semantics**: `WAIT_ON SCL_HIGH, t`
   is the one "real-time, not pre-computed" operation in the
   engine. Need to specify cleanly how it composes with the
   quarter-bit clock (recommend: pauses the divider, resumes
   from the same quarter).
```

Replace with:

```
4. **Async clock-stretching semantics**: ~~`WAIT_ON SCL_HIGH, t`
   is the one "real-time, not pre-computed" operation in the
   engine. Need to specify cleanly how it composes with the
   quarter-bit clock~~ **RESOLVED.** EMIT_BIT auto-syncs at the
   Q1->Q2 boundary in controller role: engine commits SCL
   recessive, pauses QuarterBitTimer.enable while observing
   `sclSampled`, resumes on `sclRising` with a fresh full Q2
   period. Bounded by `MoleConfig.stretchTimeoutCycles` (default
   2^20 fabric cycles ~= 44 ms). Surfaces as HALT status 0xD on
   timeout; PP-class slave stretching is treated as a compliance
   violation (immediate HALT 0xD). See §"Stretch-aware Q2 entry"
   below for the wire-level contract and docs/superpowers/specs/
   2026-06-02-stretch-aware-emit-bit-design.md for the full
   design.
```

(b) Add a new subsection under §"Canonical EMIT_BIT shape"
(around line 700, right after the "tHD;DAT" subsection that was
added in commit 22a670860853). Insert before the "This is the
*contract* between the SDK and the engine" paragraph:

```markdown
#### Stretch-aware Q2 entry

The Q1->Q2 boundary is the one place in the controller-role bit
cycle where the engine looks at the wire instead of the timer.
At every Q1->Q2 timer tick the engine commits the SCL recessive
symbol (so the pad releases under OD or drops PMOS-drive under
PP) and immediately reads `observer.sclSampled`. If the slave is
holding SCL low, the engine pauses the quarter timer and spins
in a dedicated `emitBitStretchWait` sub-state until either
`observer.sclRising` fires (slave released) or
`MoleConfig.stretchTimeoutCycles` fabric cycles elapse. On
release, the timer is reloaded with a full fresh Q2 period and
the bit completes normally; the MISMATCH / CAPTURE sample point
at the (new) Q2->Q3 transition still lands well inside the
slave's SCL-high window thanks to the conservative full-period
reload (do not subtract synchroniser latency: the 2-FF
`BusObserver` already delays `sclRising` by 2 fabric cycles, and
the reload absorbs that delay implicitly).

On timeout the engine sets `TIMEOUT_FLAG` + `MISMATCH_FLAG` and
HALTs with status `0xD`. The host loader decodes `0xD` as
"engine-detected stretch fault"; a `BRANCH_ON TIMEOUT` or
`BRANCH_ON MISMATCH` placed *before* the next EMIT_BIT cannot
read these flags because the HALT lands first --- the diagnostic
is meant for the host, not for in-program recovery.

Under PP-class `BUS_MODE` (`i3c-PP`, `hdr-ddr`) the slave is in
spec violation if it stretches at all (the slave's NMOS fighting
the controller's PMOS is bus contention); the engine treats any
observed stretch as a hard error and HALTs `0xD` immediately, no
wait state entered.

The auto-guard applies **only** to controller-role `EMIT_BIT`:
- `EMIT_QUARTER` is the user's literal-wire-shape escape hatch
  and does not consult `sclSampled`.
- `STRETCH_SCL` is the controller-role forced-stretch primitive
  (also the canonical target-role stretching mechanic) and runs
  for exactly N quarters regardless of slave behaviour.
- Target role (`!roleReg`) bypasses the guard --- the engine
  doesn't generate SCL there, so there's nothing to wait for.

The implementation lives in `fpga/Mole/src/hw/BitCycleEngineCore
.scala` (the Q1->Q2 guard inside `emitBitState` plus the new
`emitBitStretchWait` state). See `BitCycleEngineStretchSim` and
`BitCycleEngineStretchRoleSim` under `fpga/Mole/src/sim/` for
the regression coverage.
```

- [ ] **Step 7.2: Update fpga/Mole/AGENTS.md**

Find the section "Quarter-bit is the timing unit on the wire"
(around line 105). After the existing bullet about `EMIT_BIT`
carrying the canonical bit shape (the one ending "...and never
has to reason about the SCL waveform"), add a new bullet:

```markdown
- **Stretch-aware Q2 entry on controller-role `EMIT_BIT`.** The
  engine auto-syncs to slave-stretched SCL at the Q1->Q2 boundary:
  if `observer.sclSampled` is low when the engine would have
  advanced to Q2, it pauses the QuarterBitTimer and spins in a
  dedicated wait state until SCL releases (or `MoleConfig
  .stretchTimeoutCycles` fabric cycles elapse, default 2^20 ~=
  44 ms at 24 MHz). PP-class BUS_MODE slaves that stretch are
  treated as compliance violations: immediate HALT 0xD. The guard
  is bypassed in target role (`!roleReg`), and EMIT_QUARTER /
  STRETCH_SCL are untouched (user retains literal-wire-shape
  control / forced-stretch semantics). See ROADMAP §"Stretch-
  aware Q2 entry" for the wire contract.
```

- [ ] **Step 7.3: Update fpga/Mole/WIRE_FORMAT.md**

The WIRE_FORMAT.md currently has no HALT status code table
(verified at spec time). Add one. Find the §"7.3 Overflow flag in
the HALT word" section (around line 426) and insert a new §7.4
after the existing 7.3, renumbering 7.4 (Sources) to 7.5:

```markdown
### 7.4 HALT status codes

The 4-bit `status` field at HALT word bits `[11:8]` carries one
of three code classes:

| Code range | Class | Meaning |
|---|---|---|
| `0x0..0xC` | Caller-defined | The program author chooses. By convention `0x0` = clean exit; higher codes used per-program (e.g. `i2c-soak.moleasm` uses `0x1`/`0x2`/`0x3` to discriminate wedge-handler sites). |
| `0xD` | **Engine-detected: stretch fault.** | The slave held SCL low past `MoleConfig.stretchTimeoutCycles` at a Q1->Q2 boundary of `EMIT_BIT` (OD-class BUS_MODE), OR the slave stretched at all under PP-class BUS_MODE (which is a spec violation). `MISMATCH_FLAG` is set; `TIMEOUT_FLAG` is set only on the OD timeout path. |
| `0xE` | Reserved | For future engine traps. |
| `0xF` | **Engine-detected: malformed instruction.** | One of: reserved opcode word, reserved condition code, reserved `tx_symbol` (`0b11`), invalid `SET_BUS_MODE` wire value, JMP target out of range. |

Callers SHOULD restrict their status codes to `0x0..0xC` so the
host loader can unambiguously distinguish caller halts from engine
traps. The loader's `HaltStatus` decoder (in `mole-loader/src/ring
.rs`) reports the raw 4-bit value; downstream logic (e.g. the
CLI's `halt_indicates_failure` check) decides which codes are
considered failures.
```

Renumber the existing §7.4 "Sources" to §7.5.

- [ ] **Step 7.4: Verify hygiene on all three doc files**

```bash
for f in ROADMAP.md fpga/Mole/AGENTS.md fpga/Mole/WIRE_FORMAT.md; do
  python3 -c "
b = open('$f','rb').read()
print(f'{\"$f\":50s} len={len(b)} hasCR={13 in b} tail={b[-1]}')
"
done
```

Expected: each line shows `hasCR=False` and `tail=10`.

- [ ] **Step 7.5: Commit `docs(engine)`**

```bash
git add ROADMAP.md fpga/Mole/AGENTS.md fpga/Mole/WIRE_FORMAT.md
git status
git commit -m 'docs(engine): document stretch-aware Q2 entry contract

Three doc edits covering the contract introduced by the previous
two commits:

ROADMAP.md:
- §"Known risks & mitigations" entry 4 ("Async clock-stretching
  semantics") marked RESOLVED with a forward reference to the
  new section.
- New subsection under §"Canonical EMIT_BIT shape" titled
  "Stretch-aware Q2 entry" describing the wire-level contract,
  the OD-class wait-and-resume vs PP-class immediate-HALT split,
  the timeout behaviour, and the deliberate exclusions
  (EMIT_QUARTER, STRETCH_SCL, target role).

fpga/Mole/AGENTS.md:
- New bullet under §"Quarter-bit is the timing unit on the wire"
  summarising the engine-side contract for sub-block authors.

fpga/Mole/WIRE_FORMAT.md:
- New §7.4 "HALT status codes" table formalising the
  caller-defined (0x0..0xC) / engine-trap (0xD, 0xF) /
  reserved (0xE) split. Documents 0xD = stretch fault and
  0xF = malformed instruction; previously the status code
  namespace was only described in BitCycleEngineCore.scala
  line 80 prose. Renumbers former §7.4 (Sources) to §7.5.

Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>'
```

- [ ] **Step 7.6: Verify the commit landed**

```bash
git log --oneline -5
git status
```

Expected: top three commits are the new ones; working tree clean.

---

## Task 8: Final reviewer pass against the spec

**Files:** read-only review of all three commits from Tasks 6-7.

**Sub-agent:** `reviewer` (premium model --- adversarial spec
compliance).

- [ ] **Step 8.1: Dispatch reviewer subagent**

The reviewer reviews:
- The three commits (`feat(engine)`, `test(engine)`,
  `docs(engine)`) in full.
- The spec at
  `docs/superpowers/specs/2026-06-02-stretch-aware-emit-bit-design.md`.

It checks:
- **Spec coverage**: every spec section / requirement has a
  corresponding implementation artefact.
- **Spec compliance**: the implementation does what the spec said
  and nothing extra. Specifically:
  - HALT status code is `0xD` everywhere (engine, doc table,
    commit messages). No `0xA` slips.
  - The Q1->Q2 guard fires ONLY in controller role.
  - EMIT_QUARTER, STRETCH_SCL paths are untouched.
  - The `MISMATCH_FLAG` timeout-path write does not race with
    Q2-tick write (spec subtlety #7).
  - 2-FF synchroniser latency handled per spec subtlety #1 (full
    fresh period reload on resume).
  - No `MoleConfig.stretchAware` opt-out flag was added (we
    explicitly rejected this; EMIT_QUARTER is the escape hatch).
- **Out-of-scope creep**: nothing in the patch addresses
  result-ring drain, Fast-mode tLOW, or other deferred items.

The reviewer reports either APPROVED or BLOCKED with specific
findings.

- [ ] **Step 8.2: Address review findings if any**

If BLOCKED: the controller (you) mediates. Either an
implementation file needs a small correction (re-dispatch coder
with a targeted prompt), or a doc needs a sharpening (re-dispatch
docs), or the reviewer's finding is misplaced and the controller
documents the rationale.

Any correction lands as either:
- An amend to the relevant Task 6 or Task 7 commit (`git commit
  --amend --no-edit` after re-staging), per the project's commit
  discipline; OR
- A separate `fix(engine):` / `fix(docs):` commit if the
  correction is large or has its own narrative.

Re-run the relevant sim if any code was touched.

- [ ] **Step 8.3: Confirm final state**

```bash
git log --oneline -5
git status
```

Expected: three new commits (feat / test / docs), working tree
clean, all sims still green, `make all` still closes timing at
>= 24.00 MHz.

```bash
cd fpga/Mole && make all 2>&1 | grep "Max frequency"
```

Confirms timing closure one final time.

---

## Self-review checklist

Before considering this plan complete:

- [x] Every section in the spec maps to a task.
  - Spec §5 (FSM mechanic) -> Task 4
  - Spec §6 (files & line counts) -> implicit across Tasks 4-7
  - Spec §7 (wire-format impact) -> Task 7 (docs)
  - Spec §8 (regression sims) -> Tasks 1, 2
  - Spec §9 (doc edits) -> Task 7
  - Spec §10 (failure modes) -> Task 3 (reliability)
  - Spec §11 (subtleties) -> Task 3 (reliability) + Task 4 (coder)
  - Spec §12 (v0 assumptions) -> Task 7 (docs)
  - Spec §13 (timing budget) -> Task 5 (verify)
  - Spec §14 (handoff: 3 commits) -> Tasks 6, 7
  - Spec §15 (out-of-scope follow-ups) -> deliberately not
    addressed; reviewer (Task 8) confirms
- [x] No placeholders --- every step has concrete code or commands.
- [x] Type / name consistency --- `stretchTimeoutCtr` /
  `stretchTimeoutCycles` / `emitBitStretchWait` / `0xD` used
  consistently across all tasks.
- [x] Hard constraint surfaced --- 24.00 MHz floor and
  seed-sweep escape valve are explicit in Task 5.
- [x] Three-commit split matches spec §14 sequencing.
- [x] TDD discipline --- tests written before engine code (Tasks
  1, 2 before Task 4); sims must FAIL on red, PASS on green.
- [x] Subagent role assignments match user instruction (coder,
  tester, reliability, reviewer, docs).
