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
~10-opcode ISA over quarter-bit-resolution SDA/SCL patterns,
protocol-agnostic. All of I2C / I3C lives on the host as a
**Scheme SDK** ("Layer 1") that compiles down to that bytecode.
Spec-compliant primitives live in `i2c/`, `i3c/`, `ccc/`, `hdr-ddr/`
namespaces; raw bit-level escape hatches live in `raw/` and require
explicit opt-in. The engine plays controller *or* target via a
config bit. Error injection is decided at compile time (PRNG in the
host) so `ratio = 0` is *exactly* zero, not "approximately zero".

---

## 2. Repository layout

```text
.
├── AGENTS.md          ← you are here
├── ROADMAP.md         ← source of truth for the design; read first
├── .gitignore         ← Rust + SpinalHDL + FPGA toolchain
├── crates/            ← Rust workspaces (host compiler, encoder,
│                        result decoder, CLI, FFI, eventual Pico
│                        firmware)
└── fpga/              ← SpinalHDL projects (bit engine, UART,
                         SPRAM controller, top-levels per board)
```

Layout is *intentionally* minimal right now. Sub-trees grow as
phases of the roadmap land; do not pre-create scaffolding for work
that isn't underway.

There will eventually be **two Rust workspaces** (host tools vs.
no_std Pico firmware) for the same reason pico-de-gallo split them:
target triples and `no_std` deps don't co-exist cleanly in one
workspace. Do not merge them.

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
   workflows/`, pre-commit hooks, `deny.toml`, etc.
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

- One namespace per file under `crates/<sdk-crate>/sdk/<ns>/`.
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

### Markdown

- One sentence per line is fine if it helps diffs; otherwise wrap
  at ~80.
- ATX headings (`#`, `##`, ...), not Setext underlines.
- Fenced code blocks with a language tag (` ```rust `, ` ```scala
  `, ` ```text `).

---

## 6. Commit conventions

- Short imperative subject line, ~50 char target, 72 max.
- Body wrapped at ~72 columns, separated from the subject by a
  blank line. Explain the *why*.
- Scope prefix encouraged once we have multiple crates / FPGA
  projects: `engine: ...`, `sdk: ...`, `encoder: ...`,
  `roadmap: ...`.
- AI-assisted commits **must** carry:
  ```
  Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>
  ```
- No `Signed-off-by:` from an agent (see §3.8).
- One logical change per commit. If you find yourself writing "and
  also" in the body, split the commit.

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
  open-drain by design. The bit engine drives *low* and releases
  *high* (Hi-Z + external pull-up). Any change to the pad driver
  that lets it actively drive high is a hardware bug.

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
  skill (this repo is *not* personal --- it's a product). Trade
  secrets, partner names, and pricing math from the roadmap *do*
  stay in-repo; private code from other repos does not get
  copied in without attribution.
