# AGENTS.md --- Mole

This file is for AI coding agents (Claude, Codex, Cursor, Cline,
Aider, Continue, GitHub Copilot, ...) and human contributors working
in this repository. It exists so an agent can come in cold and avoid
the dozen mistakes that would otherwise be re-discovered the hard
way.

If you only have time to read two sections, read **§3 Hard rules**
and **§4 File conventions**.

---

## 1. What Mole is

Mole is a **MIPI I3C / I2C compliance and conformance test rig** ---
a work-alike of the MCCI Model 2710 SuperMITT, scoped to SDR +
HDR-DDR (no HDR-Ternary, so no analog I3C PHY). It is the foundation
of a product line:

- **Mole Verde** --- pocket / per-developer dongle, iCE40 UP5K class.
- **Mole Rojo** --- bench / compliance, ECP5-45K class.
- **Mole Negro** --- future certification tier, CertusPro-NX class.

The full design and rationale lives in
[`ROADMAP.md`](./ROADMAP.md). **Read it before touching architecture
decisions** --- specifically the Layer-0 ISA, the quarter-bit timing
model, and the compile-time error-injection contract.

### One-paragraph architecture

A small **bit-cycle engine** in the FPGA ("Layer 0") executes a
**15-opcode ISA** (16-bit fixed-width instructions, 17 reserved
opcode slots) over quarter-bit-resolution SDA/SCL patterns. The
engine is **literally bus-agnostic**: per-bit / per-quarter drive
fields are 2-bit `tx_symbol = {dominant, recessive, hiz,
reserved}`, and the `BUS_MODE` register owns the
symbol-to-electrical mapping. All of I2C / I3C lives on the host
as a **Scheme SDK** ("Layer 1") that compiles down to that
bytecode. Spec-compliant primitives live in `i2c/`, `i3c/`,
`ccc/`, `hdr-ddr/` namespaces; raw bit-level escape hatches live
in `raw/` and require explicit opt-in. The engine plays
controller *or* target, selected at runtime by `SET_ROLE`
(power-on default from `MoleConfig.role`); target-role bytes
use `SAMPLE_BIT_ON_SCL` / `DRIVE_BIT_ON_SCL` to slave to the
external controller's SCL. Bounded loops use a two-register loop
counter (`LCR0`/`LCR1`) primed by `LOAD_LOOP` and counted down by
`DEC_BRANCH`. Error injection is decided at compile time (PRNG
in the host) so `ratio = 0` is *exactly* zero, not "approximately
zero".

---

## 2. Repository layout

```text
.
├── AGENTS.md          ← you are here
├── ROADMAP.md         ← source of truth for the design; read first
├── README.md          ← contributor-facing overview
├── Cargo.toml         ← host-tools Cargo workspace (root)
├── Cargo.lock         ← committed
├── .gitignore         ← Rust + SpinalHDL + FPGA toolchain
├── mole-asm/          ← bytecode compiler library + mdBook tutorial
├── mole-asm-cli/      ← clap + color-eyre CLI front-end (`mole-asm` binary)
├── (future) firmware/ ← no_std Pico firmware (separate Cargo workspace)
└── fpga/              ← SpinalHDL projects (bit engine, UART,
                         SPRAM controller, top-levels per board)
```

Layout is *intentionally* minimal right now. Sub-trees grow as
phases of the roadmap land; do not pre-create scaffolding for work
that isn't underway. New host-side crates land at the repo root
alongside `mole-asm/` / `mole-asm-cli/` --- there is no `crates/`
sub-directory.

There will eventually be **two Rust workspaces** (host tools at the
repo root, no_std Pico firmware under `firmware/`) for the same
reason pico-de-gallo split them: target triples and `no_std` deps
don't co-exist cleanly in one workspace. Do not merge them.

---

## 3. Hard rules (don't break these)

1. **LF line endings on every text file.** Run `dos2unix` if you're
   not sure. Applies to `.rs`, `.scala`, `.sbt`, `.md`, `.toml`,
   `.yml`, `.yaml`, `.json`, `.sh`, `.py`, `.v`, `.sv`, `.pcf`,
   `Makefile`, anything checked in. CRLF in CI workflows silently
   breaks `actionlint` / `shellcheck`; CRLF in source produces
   whole-file diffs that drown out the actual change.
2. **Format before you commit.**
   - Rust: `cargo fmt` (workspace-wide) if `rustfmt` is available.
   - Scala: `sbt scalafmtAll` (or `sbt scalafmt` per project) if
     `scalafmt` is configured. If a project doesn't yet have a
     `.scalafmt.conf`, leave formatting alone rather than imposing
     a per-file style.
3. **Wrap prose and comments at ~80 columns.** Hard limit 100.
   Includes Markdown, code comments, commit message bodies. Code
   itself follows the formatter; don't fight `rustfmt` /
   `scalafmt` over line width.
4. **No new linting, build, or test infrastructure without being
   asked.** The roadmap will introduce CI in a deliberate phase
   (see ROADMAP §Phased plan). Until then, don't add `.github/
   workflows/`, pre-commit hooks, `deny.toml`, etc. This includes
   commit-message tooling --- Conventional Commits (§6) is a
   *convention*, not enforcement; don't add `commitlint`, husky
   hooks, or a "commit message check" GitHub Action unless asked.
5. **Don't bypass the namespace contract in the Scheme SDK.**
   Anything inside `i2c/`, `i3c/`, `ccc/`, `hdr-ddr/` *must* emit a
   spec-correct bitstream. Off-spec behavior belongs under `raw/`
   and a source using it must declare `(use-raw-primitives)` at the
   top. This is what makes "does this test inject errors?"
   answerable by `grep`.
6. **Error injection is compile-time only.** The bit engine has
   *zero* runtime randomness. Any PRNG sits in the host compiler,
   seeded by `(seed, ratio)`. `ratio = 0` must produce a bytecode
   byte-identical to the no-injection build.
7. **Quarter-bit-time is the timing unit on the wire.** The fabric
   clock *is* the quarter-bit clock. Don't introduce
   sub-quarter-bit timing knobs without a roadmap update.
8. **AI-assisted commits include the trailer:**
   ```
   Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
   ```
   Never add `Signed-off-by:` from an agent --- only humans certify
   the DCO.
9. **Instruction width is fixed 16 bits.** Opcode is always
   `[15:11]` (5 bits, 32 slots, 15 in use + 17 reserved). Don't
   widen instructions, don't relocate the opcode field, don't
   add a multi-word opcode form.
10. **The flag triple is at `[2:0]`.** On every opcode that
    carries `expect`/`mask`/`capture` (`EMIT_BIT`,
    `EMIT_QUARTER`, `SAMPLE_BIT_ON_SCL`, `DRIVE_BIT_ON_SCL`)
    the bits are at fixed positions: `[2]=expect`, `[1]=mask`,
    `[0]=capture`. Symbol fields stay at the high end;
    reserved bits fill the middle. Aligning the triple is what
    makes the hardware decoder cheap --- don't unalign it.
11. **`tx_symbol` is the only per-bit drive vocabulary.**
    `00=dominant`, `01=recessive`, `10=hiz`, `11=reserved`.
    The reserved encoding is held for a v0.5 `raw_override`
    escape (see ROADMAP §"Reserved for v0.5"). Do not
    repurpose it. Do **not** reintroduce `drive_sda`,
    `drive_scl`, `od_low`, `od_release`, `pp_high`, `pp_low`,
    `drive_high`, or `drive_enable` --- those were deliberately
    removed when the engine became bus-agnostic.
12. **`BUS_MODE` owns the symbol-to-electrical mapping.** The
    engine has *no* protocol knowledge --- it does not know
    what "I2C" or "I3C" means. New buses (SMBus, PMBus, LIN,
    1-Wire, CAN) are added as a `BUS_MODE` table entry plus an
    SDK macro layer, with zero ISA churn.
13. **Target role never PP-drives SCL.** The SDK rejects
    `EMIT_QUARTER` with `scl_symbol = recessive` when the
    active `BUS_MODE` is a PP class (`i3c-PP`, `hdr-ddr`); the
    engine ignores the combination as defense-in-depth.
    `scl_symbol = dominant` (pull low: stretch, fuzz) and
    `scl_symbol = hiz` (release) are always legal in target
    role. Legal target `BUS_MODE`s are `i2c` and `i3c-OD`.
14. **`BRANCH_ON` and `WAIT_ON` share encoding and
    cond-code namespace.** Shape is `[10:7]cond_code
    [6:0]operand`; only operand semantics differ
    (signed-PC-offset vs unsigned-quarter-timeout). The
    cond-code namespace is shared: codes 0..9 are in use, 10..15
    reserved for v0.5. Don't fork the two opcodes. Don't
    reorder in-use codes. Adding a code in a reserved slot is
    **not** a wire-format break; repurposing one in use **is**.
15. **Sticky engine flags are write-once until overwritten.**
    `MISMATCH_FLAG`, `TIMEOUT_FLAG`, `START_FLAG`, `STOP_FLAG`
    are set by the engine and cleared only by the next opcode
    that would write them (or by a future v0.5 `FLAG_CLEAR`).
    Don't add ad-hoc clear paths.
16. **moleasm syntax is locked.** Flag-bearing opcodes take
    `tx=dominant|recessive|hiz` (short forms `dom`/`rec`
    accepted); `EMIT_QUARTER` takes `sda=...` and `scl=...`
    with the same vocabulary. Defaults `expect=X` (don't-care),
    `mask=0`, `capture=0` are omitted when unset. Branch
    targets are labels, not raw offsets.
17. **Pre-Phase-0, wire format breaks are free.** Until the
    first tagged release of the bytecode encoder, the ISA's
    binary encoding is not a stable contract. Post-Phase-0,
    bytecode is a contract between the host compiler and every
    deployed Mole --- see §8.

---

## 4. File conventions

- **LF only, UTF-8, no BOM.** When writing from PowerShell prefer:
  ```powershell
  [System.IO.File]::WriteAllText(
      $path, $content,
      [System.Text.UTF8Encoding]::new($false))
  ```
  `Set-Content` / `Out-File` default to CRLF + BOM --- don't use
  them for source. From Python, open with `newline="\n"` or write
  bytes directly. After writing, verify the file contains no `\r`.
- **Trailing newline at end of file.** Every text file ends with a
  single `\n`. No trailing blank lines beyond that.
- **No tabs in Rust, Scala, Markdown, YAML, TOML.** Indentation
  follows the language formatter (Rust = 4 spaces, Scala = 2
  spaces, YAML/TOML = 2 spaces). Makefiles are the only exception
  --- they require hard tabs for recipe lines.
- **Line length ~80, hard cap 100** (see §3.3).
- **Binary artifacts are gitignored, not committed.** `.bin`,
  `.bit`, `.asc`, `.svf`, `.vcd`, `.fst`, `.mole.bin`, etc. are all
  in `.gitignore`. If you need to share one, attach it to a PR or
  issue, don't commit it.
- **Generated Verilog from SpinalHDL is currently *not* ignored.**
  The `.gitignore` has it commented out deliberately. Decide
  per-project whether to commit the generated `.v`; document the
  choice in the project's own README.
- **Verify hygiene after every edit.** The reliable check from
  PowerShell is:
  ```powershell
  $b = [IO.File]::ReadAllBytes($path)
  "len=$($b.Length) hasCR=$($b -contains 13) " +
  "bom0=$($b[0]) tail=$($b[-1])"
  ```
  Expect `hasCR=False`, `bom0` = the first character byte (not
  239/0xEF), and `tail=10` (LF). Editors that "helpfully"
  convert encodings have been a recurring source of churn ---
  trust the bytes, not the tool's report.

---

## 5. Language-specific notes

### Rust

- Edition 2024 once stabilized; until then 2021.
- Workspace `Cargo.lock` is **committed** for both the host
  workspace and the (future) firmware workspace.
- Validate dependency changes with `cargo build --locked` and
  `cargo test --locked`. A bare `cargo build` resolves new
  transitive versions silently.
- Host code: `std` is fine; prefer `thiserror` / `anyhow` over
  hand-rolled error enums in tool code.
- Firmware code (when it arrives): `#![no_std]`, `defmt` for
  logging, no `log` / `println!` / `eprintln!`.
- Public APIs in the host compiler / encoder get rustdoc with at
  least one example. Internal helpers don't need it.

### Scheme / SDK sources

- One namespace per file under the SDK crate's `sdk/<ns>/` tree
  (the SDK crate itself lands at the repo root when it materialises,
  e.g. `mole-sdk/`).
- A source that uses `raw/` *must* declare
  `(use-raw-primitives)` as its first non-comment form.
- Test sources end in `.mole.scm`; library sources end in `.scm`.
- Compiled artifacts (`*.mole.bin`) are gitignored.

### SpinalHDL / Scala

- Follow [`icebreaker-spinalhdl-examples`'s
  AGENTS.md](../icebreaker-spinalhdl-examples/AGENTS.md) for the
  bottom-up bring-up rhythm (`src/hw/` synthesizable,
  `src/sim/` SpinalSim testbenches, never the reverse import
  direction).
- Each FPGA top-level owns its own `build.sbt`, `Makefile`,
  `<board>.pcf`, `src/{hw,sim}/`. Cross-project sbt deps are only
  permitted for stable shared IPs (today: none; the bit engine
  itself will eventually qualify).
- Toolchain pipeline is `sbt -> yosys -> nextpnr-{ice40,ecp5} ->
  ice{pack,prog} / ecppack / openFPGALoader`. Don't introduce
  proprietary vendor flows.
- Comments: **why, not what.** Datasheet / spec page references in
  source headers are encouraged --- they don't go stale.
- **Engine invariants when implementing Layer 0:** 16-bit fixed
  instruction width (opcode `[15:11]`, flag triple `[2:0]` on
  bearer opcodes); the fabric clock *is* the quarter-bit clock
  (no PLL-multiplied sub-quarter divisions); pads are
  push-pull-capable with external pull-ups so OD `recessive`
  works without a dedicated OD pad mode. See ROADMAP §"Bus mode
  register" and §"TX symbol" for the symbol-decode contract the
  pad layer must honor.

### Markdown

- One sentence per line is fine if it helps diffs; otherwise wrap
  at ~80.
- ATX headings (`#`, `##`, ...), not Setext underlines.
- Fenced code blocks with a language tag (` ```rust `, ` ```scala
  `, ` ```text `).

---

## 6. Commit conventions

Mole follows
[Conventional Commits 1.0.0](https://www.conventionalcommits.org/en/v1.0.0/).
Commit messages take the form `<type>(<scope>): <subject>`
(scope optional) with an optional body and footers.

### Allowed types

The eleven standard types from conventionalcommits.org. The
first five are expected to be common in Mole; the rest are
rarer but welcome when they fit.

- `feat` --- a new feature (new opcode, new SDK primitive,
  new sub-block).
- `fix` --- a bug fix.
- `test` --- adding or fixing a test / sim. Use this when
  only test code changes, not the DUT.
- `ci` --- changes to `.github/workflows/*` or other CI
  plumbing.
- `docs` --- documentation only (`README.md`, `AGENTS.md`,
  `ROADMAP.md`, rustdoc, in-source headers).
- `refactor` --- code change that neither fixes a bug nor
  adds a feature.
- `perf` --- code change that improves performance.
- `style` --- whitespace / formatting only, no semantic
  change. (`scalafmt` / `cargo fmt` runs land under
  `style`.)
- `build` --- changes to `build.sbt`, `Makefile`,
  `Cargo.toml` metadata, dependency versions.
- `chore` --- routine maintenance that doesn't fit elsewhere
  (gitignore updates, file moves with no content change).
- `revert` --- revert of a prior commit. Per the spec, the
  subject is `revert: <original subject>` and the body must
  reference the reverted commit hash.

### Current scopes

Scope is optional per the spec; use one when it applies.
Extend this list as new sub-trees land --- don't pre-declare
scopes for sub-trees that don't exist yet.

- `engine` --- SpinalHDL bit-cycle engine and its sims
  (`fpga/Mole/`).
- `sdk` --- Scheme SDK (Layer 1).
- `encoder` --- host-side Rust bytecode encoder.
- `cli` --- host CLI (when it lands).
- `loader` --- host-side bytecode loader and result-ring
  decoder (`mole-loader/`, `mole-loader-cli/`).
- `roadmap` --- changes to `ROADMAP.md`.
- `agents` --- changes to this file (`AGENTS.md`).
- `readme` --- changes to top-level `README.md`.

Future scopes: `firmware` (Pico no_std workspace), `ffi`,
per-board FPGA project scopes (e.g. `engine-rojo` once Mole
Rojo gets its own FPGA sub-tree).

### Subject, body, footers

- Subject line ≤ 72 chars including the `<type>(<scope>):`
  prefix. No separate soft target --- 72 is fine.
- Subject in imperative mood ("add", "fix", "split"), no
  trailing period.
- Body wrapped at ~72 cols, separated from the subject by a
  blank line. Explain the *why*, not the *what*. The diff
  already shows the what.
- One logical change per commit. If you find yourself
  writing "and also" in the body, split the commit.
- Footers go at the end after a blank line and follow the
  spec (`Token: value` lines, hyphens for spaces in the
  token; the one exception is `BREAKING CHANGE:` which uses
  a space).

### Breaking changes

A commit that breaks a wire-format, ABI, or public-API
contract MUST mark itself as breaking, in BOTH of the
following ways:

1. Append `!` after the type or `type(scope)`:
   `feat(engine)!: relocate flag triple to [2:0]`.
2. Include a `BREAKING CHANGE:` footer describing the break
   and (where relevant) the new format version:
   ```
   BREAKING CHANGE: bytecode format bumped from v0 to v1.
   The BRANCH_ON / WAIT_ON cond-code field moves from
   bits [10:7] to bits [11:8] to align with the encoding
   in §3.10. Hosts emitting v0 bytecode against a v1
   engine (or vice versa) will mis-decode every opcode.
   ```

Per §3.17, the bytecode wire format is a stable contract
once Phase 0 ships its first tagged encoder release. Wire-
format breaks after that point MUST be accompanied by a
bumped format version in `ROADMAP.md` and a `BREAKING
CHANGE:` footer; never sneak a break through as a bare
`feat:`.

### AI-assisted commits

- AI-assisted commits **must** carry the Copilot trailer:
  ```
  Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
  ```
- No `Signed-off-by:` from an agent (see §3.8) --- the DCO
  is a human certification.

### Examples

```text
feat(engine): add SET_BUS_MODE opcode encoding
fix(engine): satisfy DDS guard in UART sims
test(engine): forked-watcher for SpramControllerSim race
ci: install Verilator from apt; drop oss-cad-suite
docs(roadmap): clarify quarter-bit timing budget
feat(engine)!: relocate flag triple to [2:0]
```

The `!` form's body must also contain a `BREAKING CHANGE:`
footer (see "Breaking changes" above).

---

## 7. Workflow expectations

- **Read `ROADMAP.md` before architectural changes.** If a change
  contradicts the roadmap, either update the roadmap in the same
  PR or stop and ask.
- **Don't run sims or builds the user didn't ask for.** Running
  the existing per-block sim target after a change you made is
  fine; spinning the full nextpnr place-and-route uninvited is
  not.
- **Don't create planning `.md` files inside the repo.** Use the
  per-session workspace (`~/.copilot/session-state/<id>/`) for
  ephemeral plans. The only design doc that lives in-tree is
  `ROADMAP.md`.
- **Sub-trees may add their own `AGENTS.md`** that overrides this
  one for local specifics (e.g., `fpga/<project>/AGENTS.md` will
  document its pinout, target frequency, and any open-drain
  hazards).
- **Don't drive any open-drain bus high.** SDA and SCL are
  open-drain *when the active `BUS_MODE` is OD class* (`i2c`,
  `i3c-OD`). In OD class the bit engine drives *low* and
  releases *high* (Hi-Z + external pull-up). Active PP drive
  high is legal **only** under PP-class `BUS_MODE` (`i3c-PP`,
  `hdr-ddr`) and never for SCL in target role. Any pad-driver
  change that lets active-high leak into an OD `BUS_MODE` is a
  hardware bug.
- **After editing ROADMAP.md or AGENTS.md, grep for superseded
  vocabulary.** The recent ISA cleanups removed a long list of
  terms in favor of new ones; stragglers cause future agents to
  reintroduce the bad pattern by analogy. Pattern to audit:
  `drive_sda|drive_scl|od_low|od_release|pp_high|pp_low|drive_high|drive_enable|WAIT_START|WAIT_STOP|WAIT_SDA_LOW|WAIT_SCL_RELEASE|BRANCH_ON_MM|BRANCH_MISMATCH|BRANCH_ON_CAPTURED_MASK`.
  All of these have been retired; any new occurrence is almost
  certainly a regression.

---

## 8. What NOT to do

- Don't add lint / build / test infrastructure that doesn't
  already exist (see §3.4).
- Don't introduce runtime randomness in the bit engine (§3.6).
- Don't expose raw / off-spec primitives under a compliant
  namespace (§3.5).
- Don't merge the host and firmware Rust workspaces (§2).
- Don't commit binary build artifacts (§4).
- Don't reorder bytecode opcodes or change their wire encoding
  without bumping a bytecode format version and updating
  `ROADMAP.md`. Bytecode is a stable contract between the host
  compiler and every deployed Mole.
- Don't rename the product, SKUs, or Layer-0 / Layer-1 boundary
  without a roadmap update. These are externally communicated
  names.
- Don't ignore the personal-repo rule from the org-roam-daily
  skill (this repo is *not* personal --- it's an open product).
  Partner names that haven't been publicly disclosed *do* stay
  in-repo; private code from other repos does not get copied in
  without attribution.
- Don't reintroduce a third drive-style vocabulary. There are
  exactly two: `tx_symbol` (the per-bit / per-quarter bitstream
  field, values `dominant`/`recessive`/`hiz`) and the
  `BUS_MODE` named symbol (`i2c`, `i3c-OD`, `i3c-PP`,
  `hdr-ddr`). Anything else --- `drive_sda`, `od_low`,
  `pp_high`, `drive_high`, `drive_enable` --- was the *previous*
  encoding and has been removed.
- Don't introduce a `WAIT_START` / `WAIT_STOP` / `WAIT_SDA_LOW`
  / `WAIT_SCL_RELEASE` / etc. opcode. Those were unified into
  `WAIT_ON cond, timeout` using the shared cond-code namespace
  (§3.14). Same for `BRANCH_MISMATCH` / `BRANCH_ON_MM` /
  `BRANCH_ON_CAPTURED_MASK` --- unified into `BRANCH_ON cond,
  offset`.
- Don't bake protocol awareness into the engine. Anything the
  engine "knows" beyond `BUS_MODE` (active mode bits + divider
  selection) and the sticky flag set is a layering violation.
  Layer 1 (host SDK) is the spec; Layer 0 is dumb wire.
- Don't fold `tx_symbol` into `BUS_MODE`. The asymmetry is
  load-bearing: `BUS_MODE` is *slow state* (symbol mapping,
  changes a handful of times per transaction), `tx_symbol` is
  *fast data* (per-bit value). See ROADMAP §"Why SDA does *not*
  live in `BUS_MODE`".

## 9. Model selection & cost discipline

Premium models (Opus, GPT-5 family, "high"/"xhigh" reasoning variants)
cost an order of magnitude more than standard models (Sonnet, Haiku,
mini). Most steps in a typical task do not need premium reasoning,
and over-using premium models wastes credits without improving
outcomes. The rules below apply to *all* model selection: your own
session, sub-agents launched via the `task` tool, and parallel work
launched via `/fleet`.

### Default posture

- **Default to the cheapest model that can do the job.** Reach for a
  premium model only when one of the escalation triggers below is hit.
- **Plan with premium, execute with cheap.** Spend at most one or two
  premium turns on design / planning, then downshift to a cheaper
  model for mechanical execution of the plan.
- **Never bump the model "just in case."** If you cannot articulate
  *why* a cheaper model would fail, use the cheaper model.

### Escalation triggers (use a premium model)

Reach for a premium model when *any* of these are true:

- Cross-module refactor, architectural design, or API design from
  scratch.
- Subtle correctness reasoning: concurrency, lifetimes, `unsafe`,
  FFI ABI, cryptography, safety-critical control paths.
- Debugging a failure that survived one prior cheap-model attempt.
- Reviewing code on a safety-, security-, or money-critical path.
- The diff cannot be predicted in advance — i.e. there is genuine
  creative or design work to do, not just typing.

### De-escalation triggers (use a cheap model)

Use the cheapest available model when *any* of these are true:

- Searching, reading, summarising files or docs.
- Single-file mechanical edits: rename, format, lint fix, dependency
  bump, boilerplate, scaffolding from a known template.
- Generating tests for code that already works.
- Running builds, tests, linters, or other commands where the model
  only needs to report success/failure.
- Routine commits, PR descriptions, changelog entries.
- The diff is essentially predictable before generation.

### Sub-agent routing (the `task` tool)

When delegating with the `task` tool, set `model:` explicitly. Do not
let sub-agents inherit a premium default for cheap work.

| Sub-agent type    | Default model             | Override to                                     |
|-------------------|---------------------------|-------------------------------------------------|
| `explore`         | cheap                     | keep cheap (`claude-haiku-4.5` or `gpt-5-mini`) |
| `task` (run cmd)  | cheap                     | keep cheap                                      |
| `research`        | cheap for breadth         | premium only for the final synthesis            |
| `general-purpose` | match task                | cheap for mechanical work; premium for design   |
| `rubber-duck`     | premium                   | keep premium — this is where reasoning pays off |
| `code-review`     | premium on critical paths | cheap on cosmetic / mechanical diffs            |

### `/fleet` (parallel sub-agents) rules

- Fleet mode multiplies cost by the fleet width. Apply the rules
  above *per worker*, not in aggregate.
- Split a fleet job along complexity lines: route the cheap,
  parallelisable workers (file edits, test runs, doc updates) to a
  cheap model; reserve premium models for the small number of
  workers that need real reasoning.
- If every worker in a fleet would need a premium model, the work is
  probably not a good fit for fleet mode — reconsider the
  decomposition before paying N× premium.

### Session hygiene

- Keep sessions short and focused. Long premium sessions are the
  single largest source of waste because every turn re-processes the
  full history.
- Use `/compact` when the conversation grows long, and `/new` for
  unrelated work.
- Prefer `/ask` for one-off side questions so they don't extend the
  main session.

### When in doubt

Ask: *"If a cheaper model produced the wrong answer here, would I
catch it in seconds (compiler, tests, my own review) or in
weeks (production incident)?"* If the former, use the cheap model
and let the feedback loop do its job.
