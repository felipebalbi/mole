# Mole/fpga/Mole --- TODO

Bottom-up bring-up plan for the Mole v0 bit-cycle engine in
SpinalHDL, targeting the iCEbreaker (iCE40 UP5K-SG48). Same
workflow as the sibling `icebreaker-spinalhdl-examples` projects:
each block built in isolation, sim'd, then composed into a wrapper,
then a top, then real silicon. Order isn't load-bearing --- adjust
as the design teaches us something.

This TODO covers the **FPGA side** of `ROADMAP.md`'s v0 Phase 0 +
Phase 1 (engine in HDL). The host-side Scheme compiler, encoder,
and result decoder live in their own root-level crates (e.g.
`../../mole-asm/`, `../../mole-asm-cli/`) and have their own
bring-up plan (TBD).

Each completed step gets a "What landed" entry so the design
rationale survives independently of the source.

The 14-opcode ISA (`EMIT_BIT`, `EMIT_QUARTER`, `STRETCH_SCL`,
`WAIT_ON`, `SET_BUS_MODE`, `SAMPLE_BIT_ON_SCL`,
`DRIVE_BIT_ON_SCL`, `JMP`, `BRANCH_ON`, `HALT`, `MARK`,
`LOAD_TIMING`, `LOAD_LOOP`, `DEC_BRANCH`; two reserved opcode
slots) and its 16-bit fixed-width encoding are the externally
visible contract. See `../../ROADMAP.md` §"Layer 0" and
`AGENTS.md` §"ISA is a stable contract" before changing either.

---

## ✅ Done

- [x] Project scaffold (Makefile, build.sbt, .scalafmt.conf,
      icebreaker.pcf, README.md, AGENTS.md, TODO.md, project/,
      src/{hw,sim}/).
- [x] **Step 1 --- `MoleConfig`.** Compile-time config record + spec-floor sim.
- [x] **Step 2 --- `MoleBus`.** 3-signal open-drain / push-pull bundle + wired-AND audit.
- [x] **Step 3 --- UART.** Imported `UartConfig`, `BaudGenerator`, `RxSync`, `Tx/RxShiftReg`, `Tx/RxFsm`, `UartTx`, `UartRx` from sibling Uart project.
- [x] **Step 4 --- UART sims.** Imported 8 sub-block sims; added top-level `UartSim` loopback.
- [x] **Step 5 --- `SpramController`.** One-tile SPRAM wrapper with read-priority arbitration; `SB_SPRAM256KA` BlackBox + `Mem` sim path.
- [x] **Step 6 --- `SpramControllerSim`.** 7 black-box cases against the `Mem` substitute: write/read coverage, read-priority arbitration (both writers), wrap-around, latency, same-address r/w.
- [x] **Step 7 --- `Instruction` ISA scaffolding.** 12-opcode + 4-reserved-slot encoder/decoder with 16-bit fixed-width wire format; full round-trip + flag-triple invariant + range-reject sim under `sim-isa`. *(Step 20 later bumped the opcode count to 14 and the reserved-slot count to 2; the round-trip suite was extended accordingly.)*
- [x] **Step 8 --- `BitCycleEngineCore` (minimal).** `EMIT_BIT` / `SET_BUS_MODE` / `HALT` decoded; quarter-bit pacing via shared `QuarterBitTimer`; `SymbolDecoder` + `SclWaveformGen` + `BusModeOps.isPpClass` as the engine's only protocol context; `Revision` word emitted as two 16-bit halves on `HALT`. Sim lands in Step 9.
- [x] **Step 9 --- `BitCycleEngineSmokeSim`.** Per-cycle bus-driver trace under `i3c-OD` vs `i3c-PP`, asserting "released" vs "actively driven high" on the SCL high half + the SDA decode for `dominant` / `recessive` / `hiz`. Distinguishes pulled-high (pull-up) from driven-high (PP) by reading the engine's `driveHigh` directly. `sim-engine-smoke` uncommented; aggregate `sim` target picks it up.
- [x] **Step 10 --- Async waits + stretch.** `EMIT_QUARTER` / `STRETCH_SCL` / `WAIT_ON cond, timeout` added to `BitCycleEngineCore`. Sticky flag set wired up (`MISMATCH_FLAG`, `TIMEOUT_FLAG`, `START_FLAG`, `STOP_FLAG` per AGENTS §3.15). Bus observer (2-FF sync on SDA/SCL + edge detectors). Reserved cond codes trap to halt.
- [x] **Step 11 --- Control flow + bookkeeping + timing override.** `JMP` / `BRANCH_ON cond, offset` / `LOAD_TIMING reg word` / `MARK label` decoded; controller-side ISA complete for v0 (target-role opcodes still deferred to Step 19). CAPTURE bit on EMIT_BIT / EMIT_QUARTER wired through to a new ring-write path. Result-ring format pinned (Revision + record stream + reserved HALT slot at `resultLimit`). Single `enterHalt(status)` helper across all trap paths (HALT opcode, reserved opcode, reserved cond code, reserved tx_symbol, invalid `SET_BUS_MODE` wire value). 32-bit MARK timestamp counter reset on `io.start`.
- [x] **Step 12 --- `BitCycleEngineSim`.** Full-ISA Verilator sim with 17 named tests under one `BitCycleEngineFullDut` compile (debug read mux over `SpramController` while the engine is idle). Covers JMP / BRANCH_ON ALWAYS + SDA_LOW + MISMATCH + TIMEOUT / WAIT_ON cond-hit + timeout-hit / MARK records + monotonic timestamps / CAPTURE record value / MISMATCH_FLAG tracking / LOAD_TIMING swap / STRETCH_SCL dwell / Revision word format / HALT status passthrough / reserved-opcode + invalid-BUS_MODE + reserved-tx_symbol traps / result-ring overflow. `sim-engine-full` uncommented; aggregate `sim` picks it up. `SAMPLE_BIT_ON_SCL` / `DRIVE_BIT_ON_SCL` deferred to Step 19.
- [x] **Step 13 --- `MoleTop`.** Synthesisable top-level. PLL bypass-selectable (`MolePllUp5k`) and SB_IO bypass-selectable (`MoleIoBufUp5k`). 3-state phase FSM (`acceptLoad` -> `running` -> `draining`) gates `engine.start`, SPRAM read-port ownership, and `acceptRx`. Reset bridge (`!pllLocked || !io_reset` async-asserts, 2-FF chain sync-deasserts). Closed-phase RX drain: during `running` / `draining` the UART RX stream is consumed silently so a stale-byte race can't seed the next frame on phase re-open. LEDs: R = pulse-stretched loader fault, G = `!engine.done`, B = 26-bit heartbeat gated by `engine.done`. Supporting blocks landed alongside: `MolePllUp5k`, `MoleIoBufUp5k`, `Crc16Xmodem`, `MoleLoaderFsm`, `MoleDrainerFsm`.
- [x] **Step 14 --- `MoleTopSim`.** End-to-end Verilator sim under one `MoleTopSimDut` compile (sim-side `UartTx` / `UartRx` so the host pushes / pops bytes via Spinal Streams instead of bit-banging the wire). 4 cases: short-halt round-trip (HALT word + Revision asserts), bus-toggle (SDA driveLow + release observed during execution), bad-CRC recovery (loaderLoaded never pulses, engineDone never goes low, fault LED lights; then a good frame loads cleanly after the resync gap), back-to-back frames (phase FSM cycles cleanly twice). Small test config (`programWordCount=16`, `resultRingByteCount=32`) keeps each case under ~50 K cycles.
- [x] **Step 15 --- `MoleTopVerilog`.** Canonical Verilog generation entrypoint. `useBlackBox = true` so yosys infers SB_PLL40_PAD + SB_IO as hard cells; `defaultClockDomainFrequency = 24 MHz` so derived dividers infer the right size. Output at `gen/MoleTop.v`; gitignored via `.gitignore`'s per-project rule.
- [x] **Step 16 --- Flashable bitstream + synth chain.** `make gen` / `make all` / `make flash` enabled end-to-end. `nextpnr --freq 12` bumped to `--freq 24` (the actual fabric clock after the PLL; originally `--freq 48`, retargeted to 24 MHz after the first real synth on UP5K SG48I came in at Fmax ~28 MHz --- see `MoleConfig.scala` and ROADMAP §"Clocks" for the rationale); timing now reports against the real budget. Bring-up procedure (build, flash, talk, three smoke programs) documented in `BRINGUP.md`.
- [x] **Step 16 --- Flashable bitstream + synth chain.** `make gen` / `make all` / `make flash` enabled end-to-end. `nextpnr --freq 12` bumped to `--freq 24` (the actual fabric clock after the PLL; originally `--freq 48`, retargeted to 24 MHz after the first real synth on UP5K SG48I came in at Fmax ~28 MHz --- see `MoleConfig.scala` and ROADMAP §"Clocks" for the rationale); timing now reports against the real budget. Bring-up procedure (build, flash, talk, three smoke programs) documented in `BRINGUP.md`.
- [x] **Step 19 --- `SAMPLE_BIT_ON_SCL` + `DRIVE_BIT_ON_SCL` (target role).** New `EngineRole` sealed trait + `MoleConfig.role` compile-time toggle (Controller default; Target opt-in). Engine FSM grows two Scala-gated states plus an external-SCL edge-detector factor-out (`BusObserver`). `EMIT_BIT` in target role releases SCL. New `BitCycleEngineTargetSim` covers six cases under two compiles; DAA arbitration uses two engines on a sim-side wired-AND bus.
- [x] **Step 20 --- `LOAD_LOOP` + `DEC_BRANCH` (ISA ergonomics).** Two new v0 opcodes claim reserved slots 0xC / 0xD, displacing `WAIT_ADDRESSED` (lowest-priority of the v0.5 reservations) and `MISMATCH_CLEAR` (subsumed by the still-reserved `FLAG_CLEAR` at 0xE). Two 8-bit loop-counter registers (`LCR0`, `LCR1`) enable bounded loops with one level of nesting, no scratch-slot spill. Wire encoding `[15:12]op [11]reg [10:8]reserved=0 [7:0]imm-or-offset`; the 3-bit pad reserves room for a 16-LCR widening with no wire-format break. Out of declared phase order (lands ahead of Steps 17--19 which are hardware-bring-up gated). See Phase 5 below for the closeout block.

---

## ✅ Phase 0 --- Foundations

### ✅ Step 1 --- `MoleConfig`

**Goal:** a single, by-value compile-time record that every
sub-block keys off, so widths and counter constants are derived
once at elaboration. Mirrors `I2cConfig` from the I2c example
project.

**What landed:**

- **Files:** `src/hw/MoleConfig.scala`, `src/sim/MoleConfigSim.scala`.
- **`MoleConfig` defaults:** `fabricFreqHz = 24 MHz`,
  `quarterPeriodCyclesReset = 6` (1 MHz bit rate at default
  divider), `programWordCount = 4096` (12-bit JMP addr cap),
  `resultRingByteCount = 8192`, `captureMaxBits = 65536`,
  `uartBaud = 1_000_000`. Each field has a `require(...)` guard;
  `programWordCount` is capped at 4096 per the ISA's 12-bit JMP
  operand (ROADMAP §"Encoding width"). One helper:
  `quarterPeriodCyclesFor(quarterHz: HertzNumber): Int`.
- **`uartBaud` choice:** iCEBreaker's FT2232H supports up to
  12 Mbaud, so the host side has plenty of headroom. 1 Mbaud is
  the highest baud that still fits the textbook 16× RX oversample
  on a 24 MHz fabric: `baudRate × oversample = 16 MHz < 24 MHz`,
  and `phaseInc = round(1_000_000 × 16 × 2^24 / 24_000_000) ≈
  11_184_811 (0xAAA_AAB)`, comfortably inside the 24-bit DDS
  accumulator with ppm-level baud accuracy. Pushing higher (e.g.
  1.5 Mbaud) at 16× steps onto the DDS overflow threshold with no
  margin (`1.5e6 × 16 = 24e6 = fabric`), and 2 Mbaud at 16×
  overflows outright --- both rejected by the `baudRate *
  oversample < clkFreqHz` guard in [[UartConfig]]. (Original
  Mole "Phase 2" defaults were 48 MHz / 2 Mbaud --- see ROADMAP
  §"Clocks" for the retarget rationale.)
- **Divergence from hint:** the hint said *Sim: none (pure data
  record). Makefile: no new target.* Reconsidered ---
  `MoleConfig` is the source of truth for every sub-block's timing,
  so a regression in its derived helpers would silently warp every
  bus speed. Added `MoleConfigSim` as a plain Scala `App` (no
  `SimConfig.compile`) that asserts the default config can produce
  a valid integer divider for every Phase-0 bus rate
  (I²C 100 k / 400 k / 1 M, I³C OD 2 M / 4 M) and `println`-warns
  about I³C PP-high (12.5 MHz SCL → 50 MHz quarter rate) being
  out of reach at 24 MHz fabric. Runs in milliseconds; gated by
  `make sim-config`.
- **Sim:** `src/sim/MoleConfigSim.scala`. Plain Scala main, not a
  SpinalSim DUT. Asserts spec-floor coverage at default config.
- **Makefile:** `sim-config` target added; `sim` aggregate now
  depends on it; `.PHONY` updated.

### ✅ Step 2 --- `MoleBus`

**Goal:** the `IMasterSlave` bundle every block that touches the
bus exposes. Mirrors `I2cIo` in the I2c example project but with
push-pull-capable pads so the per-bit `tx_symbol` field can
decode (against the active `BUS_MODE`) into an actively driven
high (I3C PP) instead of just releasing it (I2C / I3C OD).

**What landed:**

- **Files:** `src/hw/MoleBus.scala`, `src/sim/OpenDrainBusSim.scala`.
- **Bundle shape:** `MoleBusLine` is itself an `IMasterSlave`
  exposing `driveLow`, `driveHigh` (outputs in master view) and
  `read` (input in master view). `MoleBus` aggregates two lines
  (`scl`, `sda`) and `master(scl); master(sda)`s them — same
  pattern as `I2cIo` in the sibling project. The recursion through
  nested `IMasterSlave` lets `controller.io.bus <> target.io.bus`
  connect all six leaf signals correctly in one line.
- **No `releaseAll()` helper.** The repo-level AGENTS explicitly
  rejects helpers from the sibling `I2cIo` like `releaseAll`
  because Mole's bus-shaped FSMs always *set* each driver on
  every transition (last-assignment-wins clobber risk
  otherwise). Wide-fanout "release" comes through the symbol
  decoder selecting `tx_symbol = hiz`, which decodes to
  `(driveLow=0, driveHigh=0)`.
- **Divergence from hint:** the TODO hint listed the file as
  `src/hw/OpenDrainBus.scala`, but the bundle is `MoleBus` and
  `AGENTS.md` §"Open-drain primitive" calls it `MoleBus` as well.
  Picked `MoleBus.scala` (matches bundle name); the sim file
  stays `OpenDrainBusSim.scala` because that's what the Makefile
  target is named and what the AGENTS describes the resolution
  function as testing.
- **Sim:** plain Scala `App`, not a SpinalSim DUT. The bundle
  has no state to exercise; what we want to verify is the *bus
  resolution function* future engine sims will use to wired-AND
  N participants on the same line. Exposes
  `OpenDrainBusSim.wiredAnd(parts: Seq[Drive]): Option[Boolean]`
  and `resolveBus(...)` — pure functions sampled at sim time
  from each participant's `driveLow` / `driveHigh` values.
  Asserts every legal and illegal combination: released,
  one-low, one-high, many-released, partial-low, low-vs-high
  split (low wins — NMOS dominates), and self-contention
  (`driveLow=True && driveHigh=True` on a single participant,
  which the symbol decoder is never allowed to produce —
  reported as `None`).
- **Makefile:** `sim-opendrain` target uncommented; aggregate
  `sim:` depends on it; `.PHONY` updated.

### ✅ Step 3 --- UART (`UartConfig`, `BaudGenerator`, `RxSync`, shift regs, FSMs, `UartTx`, `UartRx`)

**Goal:** in-tree UART RX/TX, no cross-project dep. 8N1 by
default; runtime-tunable baud via a divider counter.

**What landed:**

- **Files (all in `src/hw/`):** `UartConfig.scala`,
  `BaudGenerator.scala`, `RxSync.scala`, `TxShiftReg.scala`,
  `RxShiftReg.scala`, `TxFsm.scala`, `RxFsm.scala`,
  `UartTx.scala`, `UartRx.scala`.
- **Source:** copied verbatim from
  `felipebalbi/icebreaker-spinalhdl-examples@98c06a8c` `Uart/src/hw/`
  with `package uart` → `package mole` on every file and a one-line
  credit header comment naming the upstream sha. The credit header
  is parsed by no tool — it just tells the next reader where to look
  for the upstream when re-syncing.
- **Skipped from upstream:** `UartController.scala` (Apb3 register-
  file wrapper Mole does not need), `UartEchoDemo.scala` /
  `UartTxDemo.scala` (top-level demos with iCEbreaker pin maps),
  `Revision.scala` (Mole's own `Revision.scala` lands with Step 8
  using the engine's REVISION word, different field layout).
- **`UartConfig` modifications:**
  - Stripped `txFifoDepth` / `rxFifoDepth` fields and their
    `require`s. Confirmed by grep that they were referenced only by
    `UartController.scala` and `UartControllerSim.scala`, neither
    of which is imported.
  - Flipped `useCts` / `useRts` defaults from `true` to `false`.
    Mole's host-link runs over an FT2232H with no flow-control pins
    wired through; bare `UartTx(UartConfig())` should therefore
    expose neither port.
  - Added `require(baudRate.toLong * oversample < clkFreqHz, …)`.
    Without this guard, the 24-bit DDS phase increment computed by
    the RX-side `BaudGenerator` overflows silently when `baudRate
    * oversample >= clkFreqHz` (rubber-duck-caught Phase-0
    blocker). The `.toLong` widening prevents 32-bit `Int *`
    wrap-around at evaluation time.
- **`UartTx` / `UartRx` modifications:** dropped the
  `UartTxVerilog` / `UartRxVerilog` companion objects. They
  generated bare-core Verilog for sibling-repo iCEbreaker bring-up;
  Mole's top-level Verilog entry point lands with Step 15.
- **Divergence from hint:**
  - The TODO listed `src/hw/UartIo.scala` as one of the files.
    Upstream has no such file — `UartTx` and `UartRx` declare
    `io = new Bundle { … }` directly. The copy follows upstream;
    no `UartIo.scala` lands.
  - The TODO says "no parity" but the upstream `UartConfig`
    already gates parity at elaboration via `cfg.parity` (default
    `ParityType.None` elides all parity hardware). Mole keeps
    parity available as a future-work knob without paying any
    hardware cost when it's off.
- **Sim:** none in Step 3 itself — the 8 per-block sims and the
  top-level loopback land with Step 4.
- **Makefile:** no new target.

### ✅ Step 4 --- UART sims

**Goal:** loopback `UartTx` → `UartRx` at several baud rates;
test back-to-back frames; test stop-bit-missing recovery.

**What landed:**

- **Files (all in `src/sim/`):** `BaudGeneratorSim.scala`,
  `RxSyncSim.scala`, `TxShiftRegSim.scala`, `RxShiftRegSim.scala`,
  `TxFsmSim.scala`, `RxFsmSim.scala`, `UartTxSim.scala`,
  `UartRxSim.scala` — all imported verbatim from
  `felipebalbi/icebreaker-spinalhdl-examples@98c06a8c`
  `Uart/src/sim/` with `package uart → package mole` and a credit
  header naming the upstream sha. The 8 imported sims already
  cover DDS phase accuracy, RxSync metastability, shift-register
  direction, FSM frame-format edge cases, parity / framing /
  overrun, and CTS / RTS flow control end-to-end.
  - `UartSim.scala` is new — the top-level TX → RX loopback test
    Mole owns directly. Wires `UartTx` to `UartRx` inside a
    `UartLoopbackDut` so the harness only deals with Stream
    handshakes (no mid-bit wire decoding required, unlike
    `UartTxSim` and `UartRxSim`).
- **`UartLoopbackDut`:** lives in `src/sim/` because it only ever
  builds under `SimConfig.compile(...)` — keeping it out of
  `src/hw/` is what stops `make` picking it up when generating
  `MoleTop.v`. Three sim-side ports beyond the obvious
  `data` / `rx` Streams: `wireOverride` + `wireOverrideEnable`
  inject a glitch on the wire mid-idle (the real `tx.io.tx` is
  multiplexed against the override on `enable`), and `wireRead`
  surfaces the live wire value for waveform inspection.
- **Three configs exercised:**
  1. `12 MHz / 115 200 baud` — sibling project default; sanity.
  2. `24 MHz / 115 200 baud` — Mole Verde "early dev".
  3. `24 MHz / 1 Mbaud` — Mole Verde production default per
     `MoleConfig.uartBaud`. iCEBreaker's FT2232H supports up to
     12 Mbaud; 1 Mbaud × 16× oversample = 16 MHz tick rate, well
     under the 24-bit DDS overflow threshold at 24 MHz fabric.
  All three satisfy the rubber-duck-added `baudRate * oversample
  < clkFreqHz` `require` on `UartConfig`.
- **Coverage per config:** single-byte round-trip across a
  representative pattern set (`0x00`, `0xFF`, `0xAA`, `0x55`,
  `0xAD`, `0x80`, `0x01`); back-to-back burst with `valid` held
  high across the whole sequence (catches FSMs that require
  `valid` to deassert between frames); single-cycle wire glitch
  injection mid-idle followed by a clean frame (verifies RX's
  oversample windowing debounces sub-bit pulses).
- **Divergence from earlier plan:** the original loopback target
  list included 3 MBaud and 12 MBaud stress cases. They're
  removed — at Mole Verde's 24 MHz fabric × 16× oversample they
  push DDS phaseInc past the 24-bit field limit (and even at the
  original 48 MHz fabric Phase-2 default they were marginal),
  refusing to elaborate under the `UartConfig` guard. They land
  as a follow-up once an 8× oversample option is added to
  `UartConfig`.
- **Sim runner docstrings fixed:** the upstream copies all had
  `Run: sbt "runMain uart.<name>"`. Swept all 8 to
  `runMain mole.<name>` to match Mole's package.
- **Makefile:** added per-sim targets `sim-baud-gen`,
  `sim-rx-sync`, `sim-tx-shiftreg`, `sim-rx-shiftreg`,
  `sim-tx-fsm`, `sim-rx-fsm`, `sim-uart-tx`, `sim-uart-rx`,
  `sim-uart`. The aggregate `sim` now depends on all 12 Phase-0
  sims wired so far. `.PHONY` updated.

### ✅ Step 5 --- `SpramController`

**Goal:** wrap the UP5K's 4× 16k×16 SPRAM tiles into a single
program-memory + result-ring backing store with a Stream-shaped
read port (for the engine fetch path) and a write port (for the
UART loader and the result-ring producer).

**Files:** `src/hw/SpramController.scala`.

**Design notes:**
- The UP5K has 1 Mbit of SPRAM (4 tiles × 16k × 16 bits =
  128 KB). Plenty for v0 program + result ring; no external
  flash needed.
- Tile selection / address translation lives here so consumers
  see a flat address space.
- Single-port semantics --- arbitrate writes from UART loader vs
  result-ring producer, reads from engine fetch path.

**What landed:**
- `src/hw/SpramController.scala` --- `case class
  SpramController(cfg: MoleConfig, useBlackBox: Boolean = true)`.
  Three Stream-shaped IOs (`loaderWrite`, `resultWrite`,
  `readCmd`) plus a one-cycle-latency `Flow`-shaped `readResp`.
- **Two write ports (not one), named after producer identity.**
  `loaderWrite` is the boot-time UART program loader,
  `resultWrite` is the engine result-ring producer. Keeping them
  distinct preserves the producer in code review and sim
  waveforms; arbitration logic combines them under the hood.
- **`readResp` is a `Flow`, not a `Stream`.** The `SB_SPRAM256KA`
  primitive returns data one cycle after the address is presented
  and offers no way to back-pressure once the read is in flight.
  Modelling the response as a `Flow` matches that semantics
  precisely; consumers buffer downstream if they cannot accept a
  read result every cycle.
- **Read-priority arbitration.** `readCmd.ready := True` (engine
  fetch is critical path); `resultWrite.ready := !readCmd.valid`;
  `loaderWrite.ready := !readCmd.valid && !resultWrite.valid`.
  Read-vs-write to the same address in the same cycle is
  *structurally* impossible: the write loses arbitration and is
  offered the bus next cycle. The "what happens on simultaneous
  same-cycle r/w to one address" question therefore never reaches
  the primitive.
- **One tile, not four (Phase 0 simplification).** The plan
  sketched a 1-to-4-tile address translator; Phase 0 ships
  one-tile-only with a `require(totalWords <= 16384)`. With the
  v0 defaults (4096 program + 4096 result words = 8192) we use
  half of one tile; users can push the result ring to ~24 KiB
  before tripping the require. Multi-tile arbitration lands as a
  Step 8+ follow-up when the engine actually needs more memory.
- **`SB_SPRAM256KA` BlackBox** declared in the same file. Port
  list cross-checked against icestorm's `cells_sim.v` reference
  model. `mapClockDomain(clock = io.CLOCK)` threads the implicit
  clock onto the primitive's `CLOCK` pin. `noIoPrefix()` strips
  the `io_` prefix from generated Verilog ports so the
  instantiation matches the primitive's real port names.
  **POWEROFF is active LOW** and is tied HIGH for the tile to be
  operational --- the single most common iCE40 SPRAM bring-up
  gotcha is documented inline.
- **Sim path** (`useBlackBox = false`) backs the wrapper with a
  plain `Mem(Bits(16 bits), 1 << addrWidth)` using
  `mem.readSync(addr, enable = doRead)` and `mem.write(addr,
  wrData)`. The address-mux + ready-back-pressure logic lives
  outside the `if (useBlackBox)` branch and is therefore
  exercised end-to-end by the sim despite the primitive being
  substituted.
- **`useBlackBox` is a constructor parameter, not auto-detected.**
  The plan's risk register flagged
  `GenerationFlags.simulation.isEnabled` as unverified in
  Spinal 1.14.1. Falling back to an explicit boolean keeps the
  build deterministic --- sims pass `false`, the (future) Verilog
  generator at Step 15 will pass `true`.
- **`readResp.valid := RegNext(doRead) init (False)`** lags
  `readCmd.fire` by exactly one cycle, matching both the BlackBox
  and `Mem` read latencies.

**Sim:** Step 6.

### ✅ Step 6 --- `SpramControllerSim`

**Goal:** smoke-test address mapping, single-port arbitration,
and result-ring wrap-around.

**Files:** `src/sim/SpramControllerSim.scala`.

**Makefile:** uncomment `sim-spram`.

**What landed:**
- `src/sim/SpramControllerSim.scala` --- SpinalSim object exposing
  7 black-box cases against `SpramController(smallCfg,
  useBlackBox = false)` (the sim path uses the `Mem` substitute;
  the wrapper logic --- arbitration, address mux, ready
  back-pressure --- is identical between the two paths).
- `smallCfg`: `programWordCount = 64`, `resultRingByteCount = 64`
  → `totalWords = 96`, `addrWidth = 7`. Small enough that
  "write every cell, read every cell" runs in a few hundred
  cycles; large enough to exercise wrap-around with a low pass
  count.
- **Cases:**
  1. **`caseWriteReadAllCells`** --- loader-write the pattern
     `addr ^ 0xA5A5` to every cell, read every cell, compare.
     Catches address-mux bugs and any bit-bound issue in the
     `Mem` substitute.
  2. **`caseReadPriorityOverWrite`** --- contend `readCmd` +
     `resultWrite`; assert read fires, write back-pressures,
     read returns the pre-write seed; let the write fire on the
     next cycle and verify it lands.
  3. **`caseResultBeatsLoader`** --- contend `loaderWrite` +
     `resultWrite`; assert `resultWrite` wins, loader
     back-pressures, both writes eventually land.
  4. **`caseResultRingWrap`** --- write to the ring address range
     `[programWordCount .. programWordCount + resultWordCount)`
     three full passes; verify each cell holds the value from
     the last pass.
  5. **`caseReadLatency`** --- fire one read; assert
     `readResp.valid` is high on the next cycle (and low on the
     cycle after that), and the payload matches the seeded
     value. Documents the one-cycle synchronous-read contract.
  6. **`caseSameAddrReadWrite`** --- contend `readCmd` +
     `resultWrite` on the same address; assert the read returns
     the *pre-write* value, the write lands the next cycle, and
     a second read returns the post-write value. The arbiter
     prevents the dangerous same-cycle-r/w-to-same-cell case
     from ever reaching the SPRAM primitive.
  7. **`caseReadPriorityOverLoader`** --- symmetric to case 2 but
     with `loaderWrite` as the contender (rubber-duck-added: case 2
     alone only proved read priority against the *higher*
     priority writer).
- **Helpers:** `doRead`, `doLoaderWrite`, `doResultWrite`, `quiet`.
  Each driver helper drops `valid` immediately after the
  handshake fires so it cannot accidentally fire a second time.
  `quiet` zeroes all sources at the top of every case for
  guaranteed reset hygiene.
- **Timing discipline (rubber-duck-caught):** the contention
  cases (2, 6, 7) snapshot `readResp.valid` and payload **on the
  cycle immediately after the read fires** (i.e. while the
  arbitration assertions are running) and BEFORE waiting for
  the back-pressured write to fire. `readResp` is a `Flow`
  whose `valid` only pulses for one cycle; waiting for the
  write before checking the response would always race past
  the pulse.
- **Makefile:** uncommented `sim-spram`; updated aggregate `sim`
  target and `.PHONY` to include it.

---

## 🔲 Phase 1 --- Engine core

### ✅ Step 7 --- `Opcode` enum + `Instruction` bundle

**What landed:**

- **Files:** `src/hw/Instruction.scala` (new),
  `src/sim/InstructionSim.scala` (new),
  `Makefile` (`sim-isa` uncommented + added to `sim` aggregate +
  `.PHONY`).
- **Wire-format contract.** 16-bit fixed-width instructions,
  opcode at `[15:12]`, flag triple `expect[2]/mask[1]/capture[0]`
  on every bearer opcode (AGENTS §3.9 / §3.10). Pre-Phase-0 the
  binary encoding is still mutable (AGENTS §3.17); once the Rust
  host encoder ships its first tagged release, this becomes a
  stable contract.
- **Opcode assignment** (locked here as the wire-format binding):

  ```
  0x0 HALT             0x1 EMIT_BIT           0x2 EMIT_QUARTER
  0x3 STRETCH_SCL      0x4 WAIT_ON            0x5 BRANCH_ON
  0x6 JMP              0x7 SET_BUS_MODE       0x8 LOAD_TIMING
  0x9 MARK             0xA SAMPLE_BIT_ON_SCL  0xB DRIVE_BIT_ON_SCL
  0xC LOAD_LOOP        0xD DEC_BRANCH         0xE FLAG_CLEAR     0xF CAPTURE_RUN
                       (the two 0xE..0xF slots are reserved for v0.5;
                       round-trip via `ReservedV05` carrier; engine traps
                       at fetch when Step 8 / 11 land.)
  ```

  `HALT = 0x0` so a zero-initialized SPRAM word (or fetch off
  end-of-program) traps cleanly instead of free-running as
  `EMIT_BIT`.
- **`TxSymbol` (2 bits)** `dominant=00`, `recessive=01`, `hiz=10`,
  `reserved=11` per AGENTS §3.11. Reserved encoding round-trips
  but the engine refuses to execute it in v0 --- it is the
  `raw_override` slot held for v0.5.
- **`BusMode` (3 bits)** deliberately non-sequential
  (`i2c=0b000`, `i3c-OD=0b001`, `i3c-PP=0b110`, `hdr-ddr=0b111`)
  so `mode[2]` directly carries the SCL high-half drive class
  (0 = OD-release, 1 = PP-high) and `mode[1:0]` directly
  indexes the active timing divider. Encoded as a SpinalEnum with
  a named encoding (`busModeWire`).
- **`CondCode` (4 bits, shared `BRANCH_ON` / `WAIT_ON` namespace
  per AGENTS §3.14):** `ALWAYS`, `MISMATCH`, `NOT_MISMATCH`,
  `START_SEEN`, `STOP_SEEN`, `SDA_LOW`, `SDA_HIGH`, `SCL_HIGH`,
  `TIMEOUT`, `NOT_TIMEOUT` for codes 0..9; 0xA..0xF reserved
  (round-trip cleanly, engine traps at fetch in Step 8 / 11).
- **`Instruction` ADT.** Pure-Scala `sealed trait` + one case
  class per opcode + a `ReservedV05(opcode, payload)` carrier for
  the four 0xC..0xF slots. The carrier's constructor enforces
  `!Opcode.isV0(opcode)` and `payload < 4096`. Operand fields use
  `Int` for now (e.g. `LoadTiming.reg: Int`); promotion to a
  typed enum (`TimingReg`) waits for the corresponding register
  file in Step 11.
- **`Instruction.encode` / `Instruction.decode`.** Pure-Scala
  reference implementation; the future Rust host encoder is the
  runtime authority, this pair is its sim-time twin and a
  cross-validation oracle. Does **not** elaborate into RTL ---
  input is Scala case classes, return is `scala.Int`, never
  called from inside any `Component` body. `BranchOn` uses
  two's-complement encoding on the wire and `signExtend8` on
  decode to recover signed `-128..127` offsets.
- **`InstructionWord` Bundle.** Thin opcode-plus-12-bit-payload
  bundle, intentionally not per-opcode field-decomposed --- the
  Step-8 fetch FSM slices `payload` per-opcode against the
  layouts in the case-class doc comments.
- **Divergence from the hint:**
  - The hint says "`Instruction` bundle: opcode field + operand
    fields, packed to 16 bits". The case-class ADT + a thin
    `InstructionWord` Bundle is what shipped; per-opcode operand
    Bundles would force every consumer to import a per-opcode
    type and would bake the layout decision twice (Scala case
    class + Bundle). Keeping `InstructionWord` as opcode +
    raw payload lets each engine consumer slice it against its
    own operand layout. If Step 8 finds the slice ergonomics
    awkward, per-opcode Bundles can be added without touching
    the wire format.
  - The hint's cond-code list reads
    "`ALWAYS, MISMATCH, NOT_MISMATCH, START_SEEN, STOP_SEEN,
    SDA_LOW, SDA_HIGH, SCL_HIGH, TIMEOUT, NOT_TIMEOUT`" ---
    exactly what shipped. A scratch draft had `NEVER` at slot 1
    (a typed no-op) instead of `SDA_HIGH`; replaced before the
    sim coverage was wired up so all 10 in-use slots match the
    TODO spec.
- **Sim:** `InstructionSim` runs as a plain Scala `App` (no
  `SimConfig.compile` --- mirrors `MoleConfigSim` /
  `OpenDrainBusSim`). Covers:
  - exhaustive round-trip for `EMIT_BIT` (4 tx × 8 flag combos),
    `EMIT_QUARTER` (4 sda × 4 scl × 8 flag combos),
    `SAMPLE_BIT_ON_SCL` (8 flag combos), `DRIVE_BIT_ON_SCL`
    (4 tx × 8 flag combos), `HALT` (16 statuses), all four
    `SET_BUS_MODE` symbols, all 256 `MARK` labels, all 4096
    `STRETCH_SCL` / `JMP` operand values;
  - full signed-byte sweep `-128..127` on `BRANCH_ON` for every
    cond code (16 × 256 round-trips); 6 timeout samples × 16
    cond codes on `WAIT_ON`; 4 regs × 6 divider samples on
    `LOAD_TIMING`; 4 reserved opcode slots × 6 payload samples
    on `ReservedV05`;
  - structural assertions: opcode at `[15:12]`, per-opcode
    field positions per the docstring tables, reserved-bit
    slices encode to zero, and a dedicated flag-triple-at-`[2:0]`
    invariant block covering every bearer opcode (AGENTS §3.10);
  - golden encodings (e.g. `EMIT_BIT(dom,0,0,0) = 0x1000`,
    `BRANCH_ON(always,-1) = 0x50FF`, `ReservedV05(captureRun,
    0xFFF) = 0xFFFF`) to catch silent field-shift regressions
    that round-trip cleanly but produce wrong wire words;
  - range-rejection: every encode that takes a numeric operand
    has at least one `IllegalArgumentException` assertion for
    its boundary +1 value.
- **Makefile:** `sim-isa` uncommented; runs as part of `make sim`.
- **Not landed here (deferred to later steps):**
  - The engine's RTL fetch decoder + per-opcode semantics
    (Step 8 onward).
  - Engine-side trap behaviour for reserved opcodes and reserved
    cond codes (Step 8 / 11).
  - The `Instruction.scala` `Bits(16 bits)` Bundle decomposition
    per opcode --- the current `InstructionWord` keeps the
    payload raw and per-opcode slicing lives in the consumer.

### ✅ Step 8 --- `BitCycleEngineCore` + `QuarterBitTimer` + `BusMode` + `Revision`

**What landed:**

- **Files:** `src/hw/Revision.scala`, `src/hw/QuarterBitTimer.scala`,
  `src/hw/SclWaveformGen.scala`, `src/hw/BusMode.scala`,
  `src/hw/BitCycleEngineCore.scala`. All new. No `Makefile`
  change --- the smoke sim lands with Step 9.
- **`Revision`** is a Scala `object` that reads
  `-Drevision.{major,minor,patch}` from `sys.props` with defaults
  matching `Makefile`'s `REVISION_*`. Exposes `wordLo` (low 16
  bits) and `wordHi` (high 16 bits) as `Int`, plus `hwLo` / `hwHi`
  as fixed `Bits(16 bits)` literals. The 32-bit word lands in the
  result ring as two consecutive halves, low first (matches the
  little-endian byte stream the host expects).
- **`QuarterBitTimer`** is a `Component` with
  `reload` / `load` / `enable` / `tick` IO. Counter width sized
  from a constructor `maxReloadValue` (default `1023` = the
  10-bit `LOAD_TIMING` divider field, so the same instance
  survives Step 11 without re-elaboration). `load` (level)
  forces the counter to `reload` next edge with no tick;
  `enable` runs the counter down by one per cycle and pulses
  `tick` on underflow (re-loading from `reload` the same edge).
  Documented period: `reload + 1` enable cycles per tick.
- **`SclWaveformGen`** is a pure-Scala `object` with one
  combinational helper `apply(quarterIndex: UInt): TxSymbol.E`
  that returns `dominant` for Q0/Q1 and `recessive` for Q2/Q3.
  Returning a `TxSymbol.E` (and not a raw drive pair) is what
  lets the engine route the SCL waveform through the same
  `SymbolDecoder` as SDA, so the Q2/Q3 high half automatically
  becomes OD-release under `i2c` / `i3c-OD` and PP-high under
  `i3c-PP` / `hdr-ddr` with no duplicate logic.
- **`BusMode.scala`** adds two engine-side helpers next to the
  enum (which still lives in `Instruction.scala` as the host-side
  wire-contract source of truth): `BusModeOps.isPpClass(busMode)`
  for the `mode[2]` read, and `SymbolDecoder(txSymbol, busMode)`
  as the *one* `(tx_symbol, BUS_MODE) → (driveLow, driveHigh)`
  decoder per the ROADMAP §"TX symbol" table. Decoder is
  structurally incapable of asserting both `driveLow` and
  `driveHigh` together (bus contention) by inspection of the
  table.
- **`BitCycleEngineCore`** is a `Component(MoleConfig)` with one
  `StateMachine` (`Idle → Fetch → FetchWait → Decode →
  (EmitBit) → Fetch`, plus a parallel `Halt` exit that emits
  the two `Revision` halves and returns to `Idle`). Bus pads
  are `Reg(Bool())`s at component scope per the
  `AGENTS.md` §"Bus-shaped FSM idiom"; SDA latches on entry to
  `EmitBit` (held for the full bit by spec), SCL updates on
  each timer tick. Between bits the regs hold their `Q3`
  values --- which is the canonical "SCL stays high between
  bits" behaviour.
- **Trap policy (Step 8 only):** the decode `switch` covers
  `HALT` / `SET_BUS_MODE` / `EMIT_BIT` explicitly and routes
  *every other opcode* (including the four 0xC..0xF reserved
  v0.5 slots) to the same `Halt` exit. Step 10 / 11 peel off
  the real implementations; Step 11 also grows the `HALT`
  word's status field that distinguishes "clean halt" from
  "trap on unknown opcode".
- **Result-ring writes** go to fixed addresses `programWordCount`
  and `programWordCount + 1` in Step 8 --- there is no write
  pointer yet. The proper ring pointer (with `MARK` records,
  per-write increment, and overflow handling) lands in
  Step 11.
- **Quarter-bit pacing.** Per ROADMAP §"Quarter-bit timing", the
  fabric clock is the quarter-bit clock --- the timer divides
  the fabric clock by `quarterPeriodCyclesReset` (default 6 at
  24 MHz → 4 MHz quarter rate → 1 MHz bit rate). Each `EMIT_BIT`
  is therefore 4 × 6 = 24 fabric cycles plus the 3-cycle
  fetch/decode overhead between bits. Step 11's `LOAD_TIMING`
  swaps the divider word at runtime by driving `timer.io.reload`
  from a per-`BusMode` register file.

**Divergence from the Step 8 hint block:**

- **`BusMode.scala` does not redeclare the enum.** The
  SpinalEnum lives once in `Instruction.scala` (so the host
  encoder and engine RTL share the same wire encoding). This
  file just adds engine-side helpers (`BusModeOps`,
  `SymbolDecoder`).
- **Trap-to-Halt for unrecognised opcodes** was not spelled out
  in the hint; the alternative ("hold in Decode forever") would
  produce a deadlock indistinguishable from a stuck FSM. The
  trap path is the safer Step 8 placeholder.
- **`HALT` writes two 16-bit halves**, not one. The Revision
  word is 32 bits per `AGENTS.md` §"REVISION word convention";
  the result ring is 16-bit-word-grained per `SpramController`.
  Two writes (low first) is the natural split.

**Sim:** none in this step. The `BitCycleEngineSmokeSim` and the
`sim-engine-smoke` Makefile target land with Step 9.

**Open design decisions held for later:**

- Sticky-flag clearing semantics for `WAIT_ON` (Step 10).
- Result-ring write pointer + overflow handling + `MARK`
  timestamp width (Step 11).
- `HALT` status field encoding (clean / trap / overflow ---
  Step 11 or Step 12).

### ✅ Step 9 --- `BitCycleEngineSmokeSim`

**What landed:**

- **Files:** `src/sim/BitCycleEngineSmokeSim.scala` (new).
- **DUT wrapper:** `BitCycleEngineSmokeDut` --- in-file
  `case class` that wires a `BitCycleEngineCore` to a
  `SpramController(useBlackBox = false)` and exposes
  `loaderWrite` / `start` / `done` / `bus` to the test bench.
  Lives in `src/sim/` so the `Makefile`'s `HW_SRCS` glob does not
  pick it up at synthesis time. The wrapper is *not* a
  `MoleTop` --- the UART loader / drainer integration is Phase 2.
- **Program:** for each run, the sim hand-encodes 11 instructions
  via `Instruction.encode` (the Scala sim oracle from Step 7):
  one `SET_BUS_MODE`, then 9 `EMIT_BIT`s (the byte `0x55` MSB
  first followed by a `hiz` ACK slot), then `HALT 0`. The bytes
  drive the loader-write Stream into program memory one word
  per handshake; `start` rises once the load completes.
- **Two runs:** `i3c-OD` and `i3c-PP`. The byte is identical;
  only the active `BUS_MODE` differs. That is the entire point ---
  any engine that bakes "OD" into a hard-coded SCL waveform mux
  fails the second run.
- **Per-cycle trace.** From the moment `done` falls until it
  re-rises, the sim records a `BusSample(sdaLow, sdaHigh, sclLow,
  sclHigh)` tuple per fabric cycle. This is the level of
  visibility you only get because we own the DUT --- a real-world
  scope cannot tell "released" from "driven high" through a pull
  resistor.
- **Trace assertions:**
  - Exactly 9 contiguous `sclLow = True` intervals (one per
    `EMIT_BIT`). The engine drives SCL low only during `Q0 + Q1`;
    `Q2 + Q3` and the fetch / decode gap between bits are always
    `sclLow = False`.
  - Each interval is `2 * quarterPeriodCyclesReset ± 2` fabric
    cycles wide (default 24 ± 2 = 22..26). The ± 2 covers the
    one-cycle aliasing of the sampler vs the SCL reg's update
    edge.
  - SDA in the middle of each low interval matches the symbol
    decoder's expected `(driveLow, driveHigh)` for that bit's
    `tx_symbol` under the active `BusMode` --- the sim mirrors the
    decode table in a pure-Scala `expectedSda` oracle.
  - Between every pair of consecutive low intervals,
    `sclDriveHigh` matches the expected drive class: `False` for
    every OD-class `BusMode`, `True` for every PP-class. This is
    the "released vs driven high" check the Step 9 hint called
    out.
  - Bus contention (`driveLow && driveHigh` on the same line in
    the same cycle) is asserted out on every cycle for both
    lines. Structural property of `SymbolDecoder` (no decode row
    asserts both); the assertion catches a future regression that
    wires the decoder wrong.
- **Makefile:** `sim-engine-smoke` uncommented as a top-level
  target. Added to the aggregate `sim` target and to
  `.PHONY`.
- **What we deliberately do *not* assert.** Cycle-exact
  duration of the trailing tail after the 9th SCL-low interval
  (the engine sits in `HALT` result-ring writes for two cycles
  plus arbitration with the loader port that is now idle; SDA /
  SCL regs hold their `Q3` values throughout, which is correct
  but verbose to assert on). A future Step 12 full-ISA sim
  validates the result-ring writes properly.

**Divergence from the Step 9 hint block:**

- **No `OpenDrainBusSim`-style wired-AND model in the trace.**
  The hint suggested "a sim helper that distinguishes 'pulled
  high by pull-up' from 'driven high by the DUT' on the wired
  bus". We have something better: direct visibility into the
  engine's `driveLow` / `driveHigh` registers. A pull-up model
  on top of those would only obscure the distinction the engine
  is responsible for making.
- **Single program, two runs** instead of two distinct programs.
  Same `0x55 + hiz` byte for both modes; only `SET_BUS_MODE`
  changes. Makes the contrast between the two traces direct ---
  any difference is attributable to the mode switch.

**Sim run:** `make sim-engine-smoke`.

### ✅ Step 10 --- Async waits + stretch

**Goal:** implement `EMIT_QUARTER`, `STRETCH_SCL`, and the
unified `WAIT_ON cond, timeout` opcode. These are the
"non-fixed-cadence" opcodes --- they pause the quarter-bit timer
on external bus state.

**Files:** extend `BitCycleEngineCore.scala`.

**Design notes:**
- `STRETCH_SCL n` reuses the registered SCL drive: forces it low
  for `n` quarters of the *programmed* (not measured) period.
- `WAIT_ON cond, timeout` is a single opcode that subsumes the
  v0-draft `WAIT_SCL_RELEASE` / `WAIT_SDA_LOW` / `WAIT_START` /
  `WAIT_STOP` quartet. The `cond` field is 4 bits from the
  shared `BRANCH_ON`/`WAIT_ON` cond-code namespace (codes 0..9
  in use; see Step 7). Timeout is **8-bit unsigned in quarter-
  bit units**; `0 = forever`. Long waits are expressed in the
  SDK as a loop: `WAIT_ON cond, 0xFF` + `BRANCH_ON TIMEOUT,
  back` (cheap because `BRANCH_ON` is signed PC-rel ±128).
- On timeout the engine sets `TIMEOUT_FLAG` (sticky, observable
  via `BRANCH_ON TIMEOUT`/`NOT_TIMEOUT`) and continues to the
  next instruction. `START_FLAG` / `STOP_FLAG` are likewise
  set by `WAIT_ON START_SEEN` / `STOP_SEEN`. See ROADMAP
  §"Engine flags --- unified condition codes" for the full
  flag and code list.

**What landed:**
- Three new `decodeState` arms (`emitQuarter`, `stretchScl`,
  `waitOn`) and three matching execute states in
  `BitCycleEngineCore.scala`.
- Four sticky flag regs (`mismatchFlag`, `timeoutFlag`,
  `startFlag`, `stopFlag`) cleared on `io.start` per ROADMAP
  §"Engine flags". MISMATCH compare runs at the Q2 → Q3 sample
  point in `emitBitState` (and on the lone tick of
  `emitQuarterState`) gated by the `[2:0]` flag triple's
  `mask` bit; `mask=0` leaves the flag sticky.
- Two-FF synchronizer on `io.bus.{sda,scl}.read` plus 1-cycle
  history regs (`sdaSampledPrev`, `sclSampledPrev` init `True`
  to match the recessive idle bus) drive the combinational
  `startEdge` / `stopEdge` pulses. Edges only ever update
  `start/stopFlag` while `waitOnState` is armed for the
  matching cond --- the re-arm-on-entry write in the WAIT_ON
  decode arm clears stale state per ROADMAP §"START_FLAG and
  STOP_FLAG".
- `STRETCH_SCL`: `n = 0` is a no-op (skip the state, PC++);
  otherwise drive SCL dominant for `n` quarter-bit ticks and
  release SCL to recessive (decoded against `BUS_MODE`) on the
  final tick so a following `WAIT_ON SCL_HIGH` does not
  deadlock waiting for the engine itself to release.
- `WAIT_ON`: 8-bit timeout in quarters with `timeout = 0` as
  the "wait forever" sentinel (captured in
  `waitTimeoutInfinite`); cond-first / timeout-second priority
  on the cycle they both fire. Reserved cond codes `0xA..0xF`
  (v0.5 slots) trap to `HALT` at decode time --- a forward-
  deployed v0.5 program against a v0 engine surfaces as a
  clean halt rather than an infinite wait.

**Deferred to Step 11:** the `capture` bit (writes the sampled
SDA value to the result ring --- needs the ring write pointer)
and `BRANCH_ON cond` reading these flags.

**Deferred to Step 19:** target-role helpers
`SAMPLE_BIT_ON_SCL` / `DRIVE_BIT_ON_SCL` and the AGENTS §3.13
"target never PP-drives SCL" lint (no role register yet).

**Sim runs:** the Step 9 smoke sim still passes unchanged
(EMIT_BIT path runs with `mask=0` in every test, so the new
MISMATCH wiring is dormant); systematic per-opcode coverage
including WAIT_ON paths lands in Step 12.

### ✅ Step 11 --- Control flow + bookkeeping + timing override

**Goal:** implement `JMP`, `BRANCH_ON cond, offset`, `MARK`,
`LOAD_TIMING`. After this step the controller-side engine is
ISA-complete for v0 (target-side opcodes land in Step 19).

**Files:** extended `src/hw/BitCycleEngineCore.scala` (no new
files --- `timingRegs` lives as a 4-entry `Vec` inside the engine).

**What landed:**

- **`JMP addr`** --- 12-bit absolute, wraps modulo `2^pcWidth`.
  The host SDK is responsible for valid targets; the engine does
  not range-check (out-of-range fetches manifest as reads from
  the result-ring SPRAM region and almost always trap to the
  malformed-instruction HALT).
- **`BRANCH_ON cond, offset`** --- 4-bit cond + 8-bit signed
  PC-relative offset. On taken: `pc := pc + 1 + sign_ext(offset)`
  modulo `2^pcWidth`. On not-taken: `pc := pc + 1`. Reserved
  cond codes (`0xA..0xF`) trap to the malformed-instruction
  HALT. Cond evaluation uses a *flag-only* mux
  (`evalCondFlagOnly`); the live-edge OR is specific to
  `waitOnState` (BRANCH_ON does not race in-flight edges).
- **`LOAD_TIMING reg word`** --- writes one of four 10-bit
  divider words held in `timingRegs(0..3)`. The timer's
  `io.reload` is combinationally muxed from
  `timingRegs(busModeReg[1:0])`, so swapping `BUS_MODE` switches
  the next quarter-bit period without an extra opcode.
- **`MARK label`** --- 3-word ring record (see "Result-ring
  format" below). Timestamp is a 32-bit fabric-cycle counter
  reset on `io.start` (wraps at ~179 s @ 24 MHz). Timestamp is
  latched at decode, written across three states so multi-cycle
  writes do not race the running counter.
- **`HALT status`** --- the existing two-word Revision write is
  now followed by a third **HALT status word** at the reserved
  `resultLimit` slot. Format documented in the engine doc
  comment block. `enterHalt(status: Bits)` is the single helper
  used by every trap path so latches stay consistent across
  `is(Opcode.halt)`, the `default` arm, and the reserved-cond /
  reserved-`tx_symbol` / invalid-`BUS_MODE` traps.
- **CAPTURE bit** --- previously deferred from Step 10. Both
  `EMIT_BIT` and `EMIT_QUARTER` latch `sdaSampled` into
  `captureValueReg` at the same Q2 sample point as MISMATCH and
  route through a new `captureWriteState` that pushes a 1-word
  CAPTURE record before refetching.
- **Malformed-instruction traps** --- in addition to the
  reserved cond codes from Step 10, the engine now traps:
  - Reserved `tx_symbol` (`0b11`) on `EMIT_BIT` / `EMIT_QUARTER`
    (per AGENTS §3.11 the encoding is held for a v0.5
    `raw_override` escape and must not be silently accepted).
  - Invalid `BUS_MODE` wire values --- only `{0, 1, 6, 7}` are
    legal per ROADMAP §"Bus mode register"; anything else
    traps.
  - The pre-existing reserved-cond trap in `WAIT_ON` now uses
    the same `enterHalt` helper as everyone else.
- **`done` semantics unchanged** --- still rises one cycle after
  the final result-ring write fires, but the final write is now
  `haltWriteStatusState` instead of `haltWriteHiState`.

**Result-ring format (v0, pre-Phase-0):**

The ring lives at `[resultBase, resultLimit]` where `resultLimit
= resultBase + resultWordCount - 1`. Layout:

- `resultBase` / `resultBase + 1` --- 32-bit Revision (always
  first; written as part of the HALT tail).
- `resultBase + 2 ..= recordLimit` --- record stream
  (`recordLimit = resultLimit - 1`). Each record is 1 or 3
  words tagged by the high 2 bits of word 0.
- `resultLimit` --- reserved exclusively for the HALT status
  word. An overflowing record stream cannot overwrite the HALT.

Record encodings (`tag = word[15:14]`):

- `00` CAPTURE (1 word): `[13:1]=0`, `[0]=captured SDA`.
- `01` reserved.
- `10` MARK (3 words): w0 `[13:8]=0 [7:0]=label`, w1
  `timestamp[15:0]`, w2 `timestamp[31:16]`.
- `11` HALT (1 word at `resultLimit`): `[13]=overflow`,
  `[12]=mismatchAtHalt`, `[11:8]=status`, `[7:0]=0`.

`mismatchAtHalt` is the *final* value of `MISMATCH_FLAG` --- it
does not track "any mismatch ever seen" (a passing compare
clears the flag per spec). `overflow` latches `True` the first
time the engine tried to write a record that would not fit
below `recordLimit`; subsequent CAPTURE / MARK opcodes silently
drop their writes.

Status codes `0x0..0xC` are caller-defined via the [[Halt]]
opcode. Codes `0xD..0xF` are reserved for engine traps; today
only `0xF` (malformed-instruction) is used. Per AGENTS §3.17
the wire format above is mutable pre-Phase-0; the v0
host-readback decoder lands in Phase 2 and will pin these
encodings down.

**Divergence from the original plan:**

- Result-ring overflow is "set the flag, drop the record"
  rather than "drop newest". Functionally equivalent here, but
  worth noting in case Step 12's tests pin the exact behaviour.
- No separate `TimingRegisters.scala` --- the file would have
  held a 4-entry `Vec` of regs and nothing else, so it lives
  inline in the engine. If a future step grows the timing-reg
  block (e.g. read-back over UART) it can move out cheaply.
- The 32-bit MARK timestamp is wider than the original "24-bit
  or whatever fits" sketch. Rubber-duck flagged that 16-bit
  wraps every 2.7 ms @ 24 MHz, which is shorter than a typical
  compliance trace; 32-bit gives ~179 s and round MARK records
  to 3 words, which is cleaner than a 2-word format with a
  partial timestamp.

**Sim:** none added in this step --- the full per-opcode
coverage lands in Step 12. Existing `sim-config` /
`sim-engine-smoke` continue to pass.

**Makefile:** unchanged (Step 12 uncomments `sim-engine-full`).

### ✅ Step 12 --- `BitCycleEngineSim` (full ISA)

**What landed:**

- **Files:** `src/sim/BitCycleEngineSim.scala` (new). 17 named
  tests under one lazy-compiled `BitCycleEngineFullDut`
  elaboration; entry point `mole.BitCycleEngineSim` runs the
  full suite. Compile-once, doSim-many keeps Verilator cost
  amortised across cases.
- **DUT shape:** `BitCycleEngineFullDut` is the smoke-sim DUT
  plus a debug read port that mux-takes the SPRAM read interface
  while the engine is idle. Engine wins arbitration while
  running; the test wins while halted. Avoids touching
  `SpramController` (whose `mem` lives inside an `else` block and
  is not externally visible). Lives in `src/sim/`; never
  elaborates to RTL.
- **DSL helpers:** `emitBit`, `emitQuarter`, `setMode`, `halt`,
  `jmp`, `branchOn`, `waitOn`, `stretch`, `loadTiming`, `mark`
  each return the wire word. `haltAt(ring, addr)` decodes the
  HALT word into a typed `HaltWord(overflow, mismatch, status)`
  case class. `revisionAt(ring)` returns the `(lo, hi)` pair at
  `resultBase` / `resultBase+1`.
- **Coverage matrix:**
  - `revision-written` --- HALT writes Revision lo/hi verbatim at
    `resultBase`/`resultBase+1`.
  - `halt-status-passthrough` --- caller's 4-bit status code
    survives into HALT word `[11:8]`.
  - `jmp-forward` --- `JMP addr` jumps over a poison HALT.
  - `branch-always-taken` --- `BRANCH_ON ALWAYS, +2` lands on
    the right HALT.
  - `branch-sda-low-taken` --- pre-drive SDA low; an
    `EMIT_QUARTER(hiz, hiz)` registers the level;
    `BRANCH_ON SDA_LOW, +2` takes.
  - `wait-cond-hit` --- bus high at start; test drops SDA
    mid-wait; engine clears TIMEOUT and falls through.
  - `wait-timeout-hit` --- bus stuck high; `WAIT_ON(SDA_LOW, 4)`
    times out; `BRANCH_ON TIMEOUT, +1` picks the right HALT.
  - `mark-records` --- two MARKs land at `resultBase+2` and
    `resultBase+5` as 3-word records; labels survive verbatim;
    timestamps are monotonic.
  - `capture-record` --- one `EMIT_BIT(hiz, capture=true)`
    against SDA pre-driven high produces a CAPTURE record
    `[15:14]=00, [0]=1`.
  - `mismatch-tracking` --- one `EMIT_BIT(hiz, expect=true,
    mask=true)` against SDA=low sets MISMATCH; HALT word's
    `mismatch` bit is true.
  - `branch-on-mismatch` --- as above, with `BRANCH_ON MISMATCH`
    in between; takes.
  - `load-timing` --- writes divider=2 to the i2c slot; measured
    SCL-low width drops into the `[4,8]`-cycle window (vs the
    default `~24`-cycle width).
  - `stretch-scl` --- `STRETCH_SCL 5` holds SCL low for
    `5 * (divider+1) ± 4` cycles.
  - `reserved-opcode-trap` --- opcode 0xC (sampleBitOnScl,
    target-role, unimplemented) traps to HALT status 0xF.
  - `invalid-busmode-trap` --- `SET_BUS_MODE` with wire value 2
    (not in {0,1,6,7}) traps to 0xF.
  - `reserved-tx-symbol-trap` --- `EMIT_BIT` with tx_symbol=0b11
    traps to 0xF.
  - `ring-overflow` --- 12 MARKs into a 29-word record area;
    the 10th overflows; HALT word's `overflow` bit is true.
- **`SAMPLE_BIT_ON_SCL` / `DRIVE_BIT_ON_SCL`:** intentionally
  not covered. These are target-role and land in Step 19 with
  the actual controller-driven SCL stim setup; covering them
  here would duplicate that test rig before it exists.
- **Per-test ring size:** uses a small cfg
  (`resultRingByteCount = 64` → 32 ring words, `programWordCount
  = 64`) so overflow is exercisable in a handful of MARKs.
- **Makefile:** uncommented `sim-engine-full`; added to
  `.PHONY`; added to the aggregate `sim:` target after
  `sim-engine-smoke`.

---

## ✅ Phase 2 --- Top integration

### ✅ Step 13 --- `MoleTop`

**Goal:** wire UART RX → SPRAM loader → engine → result-ring →
UART TX. Add status LEDs and a heartbeat. This is the
synthesisable top-level.

**Files:** `src/hw/MoleTop.scala`, plus supporting blocks
`src/hw/MolePllUp5k.scala`, `src/hw/MoleIoBufUp5k.scala`,
`src/hw/Crc16Xmodem.scala`, `src/hw/MoleLoaderFsm.scala`,
`src/hw/MoleDrainerFsm.scala`.

**What landed:**
- PLL bypass selectable via `useBlackBox` so sim doesn't need to
  elaborate `SB_PLL40_PAD`; SB_IO same pattern.
- Loader frame: `[len_lo, len_hi, w0_lo, w0_hi, ..., w(N-1)_lo,
  w(N-1)_hi, crc_lo, crc_hi]` little-endian, CRC-16/XMODEM over
  payload; rejects `len=0` / `len > programWordCount`; idle-gap
  resync (≥ 20 UART bit times). Spec lives in `WIRE_FORMAT.md`.
- Phase FSM: `acceptLoad` -> `running` -> `draining`. Gates
  `engine.start`, the SPRAM read-port ownership mux, and the
  closed-phase UART RX drain so stale bytes can't seed the next
  frame on phase re-open.
- Reset bridge: `resetAsync = !pllLocked || !io_reset` async-
  asserts a 2-FF chain in `bootCd`; the chain's output drives the
  fabric domain's synchronous reset.
- LEDs: R = pulse-stretched loader fault (~175 ms at 24 MHz so
  faults are visible), G = `!engine.done`, B = 26-bit heartbeat
  gated by `engine.done` (~0.36 Hz blink while idle).

### ✅ Step 14 --- `MoleTopSim`

**Goal:** end-to-end sim: push a hand-encoded program over the
sim UART, observe wire activity on the sim bus, drain the sim
UART and decode the result ring.

**Files:** `src/sim/MoleTopSim.scala`.

**Makefile:** `sim-top` uncommented; aggregate `sim` picks it up.

**What landed:**
- `MoleTopSimDut` wraps `MoleTop` with sim-side `UartTx` /
  `UartRx` so the host pushes / pops bytes via Spinal Streams
  instead of bit-banging the wire.
- Internal taps (sda/sclDriveLow/High, engineDone, loaderLoaded,
  loaderFault) exposed on the wrapper IO so the cases can
  observe the engine's pad drive without modelling the SB_IO.
- Small test cfg: `programWordCount=16`, `resultRingByteCount=32`,
  keeps every case under ~50 K cycles.
- 4 cases: short-halt round-trip (HALT word + Revision asserts),
  bus-toggle (SDA driveLow + release observed during execution),
  bad-CRC recovery (loaderLoaded never pulses, engineDone never
  goes low, fault LED lights; then a good frame loads cleanly
  after the resync gap), back-to-back frames (phase FSM cycles
  cleanly through `acceptLoad -> running -> draining ->
  acceptLoad` twice).

### ✅ Step 15 --- `MoleTopVerilog`

**Goal:** the canonical Verilog-generation entrypoint. Generated
module name is `MoleTop`.

**Files:** `src/hw/MoleTopVerilog.scala`.

**Makefile:** `gen` uncommented. `make all` produces
`gen/MoleTop.bin` via the Spinal -> yosys -> nextpnr -> icepack
chain.

**What landed:**
- `useBlackBox = true` so yosys infers SB_PLL40_PAD and SB_IO as
  hard cells; the bypass models exist only for `MoleTopSim`.
- `defaultClockDomainFrequency = 24 MHz` so any
  `CounterFreeRun`-style helper infers the right divider without
  per-instance config.
- Output at `gen/MoleTop.v`; gitignored via the per-project rule
  added in `.gitignore`.

### ✅ Step 16 --- Flashable bitstream + smoke

**Goal:** prove the toolchain end-to-end: `make all`, `make
flash`, smoke programs loaded over `/dev/ttyUSB0`.

**Files:** none new --- this is a process step.

**What landed:**
- `nextpnr --freq` bumped from `12` (the package-pin clock) to
  `24` (the actual fabric clock after the PLL multiply, retargeted
  from 48 to 24 MHz after the first real synth on UP5K SG48I
  came in at Fmax ~28 MHz --- see `MoleConfig.scala` and
  ROADMAP §"Clocks (v0)" for the retarget rationale); timing
  reports against the real budget.
- Bring-up procedure (build, flash, talk, three smoke programs:
  short halt for UART round-trip, infinite loop for green-LED
  proof, bus toggle for the scope) documented in
  [`BRINGUP.md`](BRINGUP.md).
- Wire format the host has to speak is in
  [`WIRE_FORMAT.md`](WIRE_FORMAT.md) (added during Step 13's
  loader bring-up).

---

## 🔲 Phase 3 --- Validation against a real DUT

### 🔲 Step 17 --- TMP108 over hand-encoded I²C

**Goal:** hand-encode a TMP108-read program (no Scheme compiler
yet), drive it from the host over UART, decode the temperature
value out of the result ring on the host. Validates the entire
toolchain against a real I²C target on PMOD1A.

**Bring-up gate:** Phase-3 done means real silicon + real DUT,
matching room temperature. Sim-only success does not count.

### 🔲 Step 18 --- MCXA dev board as I3C target

**Goal:** drive `embassy-mcxa` I3C target (which we own; see the
sibling `embassy-mcxa/src/i3c/target.rs`) from a hand-encoded I3C
SDR write-then-read program. Validates the engine's I3C SDR
timing against a known-good controller-side reference.

**Bring-up gate:** soak test for ≥10⁵ iterations without
mismatch. This is the v0 acceptance criterion --- after this
step, Mole is ready for the Scheme SDK (Phase 2 in ROADMAP) to
take over from hand-encoded programs.

---

## ✅ Phase 4 --- Target role

### ✅ Step 19 --- `SAMPLE_BIT_ON_SCL` + `DRIVE_BIT_ON_SCL`

**What landed:**

- **Files:**
  - `src/hw/EngineRole.scala` (new): sealed trait + Controller /
    Target case objects.
  - `src/hw/MoleConfig.scala`: new `role` field defaulting to
    Controller.
  - `src/hw/BusObserver.scala` (new): the existing two-FF
    synchroniser + edge detectors factored out of the engine
    body into a reusable Area. Adds `sclFalling` / `sclRising`
    that the target-role states pace off.
  - `src/hw/BitCycleEngineCore.scala`: Scala-gated SCL release
    on `EMIT_BIT` in target role; two new FSM states
    (`sampleBitOnSclState`, `driveBitOnSclState`) wired in
    behind `if (cfg.role == EngineRole.Target)` so
    controller-role builds elaborate bit-identically.
  - `src/sim/BitCycleEngineTargetDut.scala` (new):
    role=Target sim DUT.
  - `src/sim/BitCycleEngineTwoTargetDut.scala` (new):
    two-engine DUT for the DAA arbitration test.
  - `src/sim/BitCycleEngineTargetSim.scala` (new): six tests.
  - `Makefile`: `sim-engine-target` target added.
  - `mole-asm/book/src/target-role.md` (new): tutorial chapter.

- **Divergence from the hint:**
  - Used a Scala sealed trait `EngineRole` instead of a runtime
    config bit. Per AGENTS' compile-time-toggle idiom, a runtime
    register would keep both halves of the FSM present and only
    gate them.
  - The hint suggested driving the sim bus from a second engine
    configured as controller. Used a sim-side external-
    controller stim (controllerBit / sclLow / sclHigh) for the
    first five tests instead; simpler, no extra DUT compile.
    Test six (DAA) still uses two engines on a wired-AND.
  - Did not add a clock-stretching cross-test (target stretches
    SCL while controller waits). Covered structurally by
    `target-stretch-drives-scl-low`. A full stretch-acknowledge
    handshake belongs in the eventual cross-engine sim and is
    deferred.

- **Sim notes:** `BitCycleEngineTargetSim` has six tests run
  under two compiles. First five share `BitCycleEngineTargetDut`
  (single engine, sim-side controller stim). Sixth uses
  `BitCycleEngineTwoTargetDut` and resolves wired-AND each
  cycle via `OpenDrainBusSim.wiredAnd`.

- **Makefile:** `sim-engine-target` target added (parallel to
  `sim-engine-full`); listed in `sim:` aggregate and `.PHONY`.

- **Bring-up gate (still open):** real silicon target-role
  bring-up against the MCXA controller from Step 18. Deferred
  to PR2 in the post-RTS/CTS plan, which is gated on a separate
  hardware-bench session.

**Original hint preserved below.**

**Files:** extend `BitCycleEngineCore.scala`, add
`src/sim/TargetRoleSim.scala`.

**Design notes:**
- In target role the engine **releases SCL entirely**
  (`SclWaveformGen` is bypassed; SCL pad is held in
  `tx_symbol = hiz`). The external controller drives SCL; the
  engine slaves to its edges. `STRETCH_SCL n` is still legal
  in target role (pulls SCL low for clock stretching) and is
  the only path by which the target actively drives SCL.
- `SAMPLE_BIT_ON_SCL` blocks until the next SCL rising edge,
  samples SDA at the canonical sample point, compares against
  `expect`/`mask`, optionally writes to the result ring. Sets
  `MISMATCH_FLAG` on miss. No drive of either line.
- `DRIVE_BIT_ON_SCL tx_symbol expect mask capture` blocks until
  the next SCL **falling** edge, then drives SDA per the
  decoded `tx_symbol` until the following falling edge ---
  i.e. for one full controller-clocked bit. **Concurrently**,
  on the SCL rising edge inside that bit cell, samples SDA at
  the canonical sample point, compares against `expect`/`mask`,
  optionally captures. The simultaneous drive + sample is what
  enables **I3C DAA arbitration**: target drives its PID bit
  (`tx_symbol = dominant` for `0`, `recessive` for `1`) with
  `expect = tx_symbol`, `mask = 1`; if the wire reads
  `dominant` while the target drove `recessive`, another
  target won this bit and `MISMATCH_FLAG` fires for the
  drop-out handler.
- Both opcodes use the same flag triple (`expect`/`mask`/
  `capture` at `[2:0]`) and same `MISMATCH_FLAG` /
  `SAMPLE_BIT_ON_SCL` flag semantics as the controller-side
  opcodes; the SDK and result-decoder treat them uniformly.
- Pacing: combined with `STRETCH_SCL`, a target can pace the
  controller by pulling SCL low *before* releasing SDA
  (controller sees SCL stretched and waits).
- v0.5 may add `WAIT_ADDRESSED my_addr, timeout` as a hardware
  accelerator (in one of the two remaining reserved opcode slots
  0xE / 0xF) if the SDK expansion proves too slow for I3C SDR
  target emulation at full rate. Out of scope for v0.

**Sim notes:** drive the sim bus from a second engine instance
configured as controller (or a hand-rolled SCL+SDA waveform
generator); the target-role DUT samples / drives in response.
Cover:
- Pure-read transaction: controller writes address, target
  ACKs with `DRIVE_BIT_ON_SCL tx=dominant`, then drives 8 data
  bits, controller NAKs.
- Pure-write transaction: controller writes address + data,
  target ACKs each byte, captures all data bits via
  `SAMPLE_BIT_ON_SCL ... capture=1`.
- Clock-stretching: target pulls SCL low via `STRETCH_SCL`
  before releasing the ACK SDA, controller waits.
- DAA-arbitration mock: two target instances on the sim bus,
  driving competing PID bits; lower-PID target wins, higher-
  PID target sees `MISMATCH_FLAG` and `BRANCH_ON MISMATCH`
  away.

**Bring-up gate:** real silicon, real external controller
(MCXA from Step 18 running in controller role) talking to Mole
in target role. After this step the v0 engine is fully ISA-
complete in both roles.

**Makefile:** uncomment `sim-target`.

---

## ✅ Phase 5 --- ISA ergonomics (bounded loops)

### ✅ Step 20 --- `LOAD_LOOP` + `DEC_BRANCH`

**Goal:** give moleasm authors a real bounded-loop construct
backed by hardware loop counters, instead of unrolling repeats
in source. Two new v0 opcodes plus two 8-bit architectural
registers, claimed from the four reserved v0.5 slots.

Lands out of declared phase order (ahead of Steps 17--19, which
are hardware-bring-up gated and therefore slower to close).

**Files:**

- `src/hw/Instruction.scala` --- Opcode enum renames
  (`waitAddressed` -> `loadLoop`, `mismatchClear` -> `decBranch`);
  `isV0` boundary 12 -> 14; new `LoadLoop(reg, imm)` and
  `DecBranch(reg, pcRelOffset)` case classes with encode/decode
  arms.
- `src/sim/InstructionSim.scala` --- 1024 new round-trip checks
  (512 each), reg-field + reserved-pad invariants, range
  rejects, and updated `ReservedV05` golden (now `flagClear` at
  `0xE000` instead of `waitAddressed` at `0xC000`).
- `../../mole-asm/src/{symbols,assembler,encoder}.rs` --- new
  `LOOP_REG_ALIASES`, `MNEMONICS` bump to 14, `enc_load_loop` /
  `enc_dec_branch` helpers, parser arms with two new resolvers
  (`resolve_loop_reg`, `resolve_dec_branch_target`).
- `../../mole-asm/tests/fixtures/mole-asm.py` --- golden python
  assembler mirrors the same surface, including a new
  `_resolve_dec_branch_target` and `_resolve_loop_reg`.
- `../../mole-asm/tests/fixtures/loop-counter-demo.{moleasm,
  molecode,mole.bin}` --- 15-word worked example: part 1 sends
  8 dominant bits via `lcr0`, part 2 prints a 3x4 grid of MARK
  records using both LCRs nested.
- `../../mole-asm/book/src/{opcodes,patterns,reference,
  bounded-loops,SUMMARY,errors,glossary,syntax}.md` --- new
  "Bounded loops" chapter; LOAD_LOOP / DEC_BRANCH sections in
  Opcodes; the bounded-retry pattern rewritten to use the new
  counter (the prior version apologised for the engine having
  no counter); reference table + glossary entries for the LCR
  pair; reserved-mnemonic mentions updated everywhere
  (`WAIT_ADDRESSED` / `MISMATCH_CLEAR` graduated, only
  `FLAG_CLEAR` / `CAPTURE_RUN` remain).
- `../../README.md`, `../../AGENTS.md`, `README.md`, `AGENTS.md`,
  `Makefile` --- opcode count bump (12 -> 14), reserved-slot
  count bump (4 -> 2), short prose calling out the loop
  counter pair.

**Wire format (locked):**

```text
LOAD_LOOP   [15:12]op=0xC [11]reg [10:8]reserved=0 [7:0]imm8
DEC_BRANCH  [15:12]op=0xD [11]reg [10:8]reserved=0 [7:0]offset_signed
```

`reg = 0` selects `LCR0`, `reg = 1` selects `LCR1`. The 3-bit
`[10:8]` pad is reserved (= 0) so a future 16-LCR expansion can
claim those bits with no wire-format break. `DEC_BRANCH`
back-edges if and only if the post-decrement value of `LCR[reg]`
is non-zero; the decrement wraps 8-bit (0 -> 0xFF, hence
`LOAD_LOOP r, 0` runs a full 256 iterations --- legal but
linted by the assembler).

`DEC_BRANCH` does **not** touch the sticky engine flags
(`MISMATCH_FLAG`, `TIMEOUT_FLAG`, `START_FLAG`, `STOP_FLAG`)
per AGENTS §3.15, so the standard `BRANCH_ON MISMATCH ...`
fail-fast idiom composes cleanly inside a loop body.

**Sim:** `sim-isa` covers the encode/decode round-trip (1024
new checks plus updated `ReservedV05` golden).
`sim-engine-full` (`BitCycleEngineSim`) adds four explicit
loop-counter cases:

- `loop-single-count-down` --- `LOAD_LOOP lcr0, 4` plus
  `DEC_BRANCH lcr0, -2` around a `MARK` body produces exactly
  four MARK records.
- `loop-nested` --- outer `lcr1=3`, inner `lcr0=2`, MARK in
  the inner body produces `3 * 2 = 6` records (catches
  reg-id miswire and inner-counter-fails-to-re-prime bugs).
- `loop-boundary` --- two consecutive segments verify
  `DEC_BRANCH` with `LCR=1` falls through (no back-edge) and
  `LCR=2` back-edges exactly once.
- `loop-flag-neutral` --- a deliberate `MISMATCH_FLAG` set by
  an `EMIT_BIT` survives an intervening 3-iter
  `DEC_BRANCH` loop, then a `BRANCH_ON MISMATCH` correctly
  takes the branch. Catches any future regression that
  accidentally writes a sticky flag from the `DEC_BRANCH`
  arm.

The pre-existing `reserved-opcode-trap` test was updated to
target slot `0xE` (still reserved) instead of the now-claimed
`0xC` so it still exercises the trap arm in the decode
switch's `default` clause.

**Divergence from the original plan (PR plan.md):** none. The
two-LCR / DEC-only scope was confirmed at planning time; this
step lands exactly that.

**Why these two reserved slots:**

- `WAIT_ADDRESSED` (formerly 0xC) was the lowest-priority slot
  reservation --- its SDK-level expansion (Start + 8x
  `SAMPLE_BIT_ON_SCL` + host compare + conditional
  `DRIVE_BIT_ON_SCL`) has not yet shown the I3C-rate timing
  pressure that would justify a dedicated hardware accelerator.
- `MISMATCH_CLEAR` (formerly 0xD) was earmarked as a narrow
  special case of the broader `FLAG_CLEAR`, which keeps its
  slot at 0xE. If a v0.5 use case ever wants fine-grained
  mismatch clearing, `FLAG_CLEAR` with the mismatch-only mask
  covers it.

Two reserved slots remain (`FLAG_CLEAR` at 0xE, `CAPTURE_RUN`
at 0xF) --- enough headroom for the canonical v0.5 additions.

**Makefile:** no new target --- the engine FSM ships under the
existing `sim-engine-full` aggregate.

---

## Out of scope for this TODO

- Scheme compiler / SDK (host-side; lives in its own root-level
  crate, e.g. `../../mole-sdk/` when it lands).
- Error-injection PRNG (host-side; see ROADMAP §"Error injection
  model").
- RP2350 transport (v1; postcard-rpc).
- HDR-DDR (v2; ECP5-45K).
- PHY daughter card (v2; programmable VIO).
