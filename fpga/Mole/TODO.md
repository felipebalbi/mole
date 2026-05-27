# Mole/fpga/Mole --- TODO

Bottom-up bring-up plan for the Mole v0 bit-cycle engine in
SpinalHDL, targeting the iCEbreaker (iCE40 UP5K-SG48). Same
workflow as the sibling `icebreaker-spinalhdl-examples` projects:
each block built in isolation, sim'd, then composed into a wrapper,
then a top, then real silicon. Order isn't load-bearing --- adjust
as the design teaches us something.

This TODO covers the **FPGA side** of `ROADMAP.md`'s v0 Phase 0 +
Phase 1 (engine in HDL). The host-side Scheme compiler, encoder,
and result decoder live under `crates/` and have their own bring-up
plan (TBD).

Each completed step gets a "What landed" entry so the design
rationale survives independently of the source.

The 12-opcode ISA (`EMIT_BIT`, `EMIT_QUARTER`, `STRETCH_SCL`,
`WAIT_ON`, `SET_BUS_MODE`, `SAMPLE_BIT_ON_SCL`,
`DRIVE_BIT_ON_SCL`, `JMP`, `BRANCH_ON`, `HALT`, `MARK`,
`LOAD_TIMING`; four reserved opcode slots) and its
16-bit fixed-width encoding are the externally visible contract.
See `../../ROADMAP.md` §"Layer 0" and `AGENTS.md` §"ISA is a
stable contract" before changing either.

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
- [x] **Step 7 --- `Instruction` ISA scaffolding.** 12-opcode + 4-reserved-slot encoder/decoder with 16-bit fixed-width wire format; full round-trip + flag-triple invariant + range-reject sim under `sim-isa`.

---

## ✅ Phase 0 --- Foundations

### ✅ Step 1 --- `MoleConfig`

**Goal:** a single, by-value compile-time record that every
sub-block keys off, so widths and counter constants are derived
once at elaboration. Mirrors `I2cConfig` from the I2c example
project.

**What landed:**

- **Files:** `src/hw/MoleConfig.scala`, `src/sim/MoleConfigSim.scala`.
- **`MoleConfig` defaults:** `fabricFreqHz = 48 MHz`,
  `quarterPeriodCyclesReset = 12` (1 MHz bit rate at default
  divider), `programWordCount = 4096` (12-bit JMP addr cap),
  `resultRingByteCount = 8192`, `captureMaxBits = 65536`,
  `uartBaud = 2_000_000`. Each field has a `require(...)` guard;
  `programWordCount` is capped at 4096 per the ISA's 12-bit JMP
  operand (ROADMAP §"Encoding width"). One helper:
  `quarterPeriodCyclesFor(quarterHz: HertzNumber): Int`.
- **`uartBaud` choice:** iCEBreaker's FT2232H supports up to
  12 Mbaud, so the host side has plenty of headroom. 2 Mbaud is
  the highest baud that still fits the textbook 16× RX oversample
  on a 48 MHz fabric: `baudRate × oversample = 32 MHz < 48 MHz`,
  and `phaseInc = round(2_000_000 × 16 × 2^24 / 48_000_000) ≈
  11_184_811 (0xAAA_AAB)`, comfortably inside the 24-bit DDS
  accumulator with ppm-level baud accuracy. Pushing higher (e.g.
  3 Mbaud) would either overflow the DDS at 16× or force dropping
  oversample to 8× — neither is justified for v0.
- **Divergence from hint:** the hint said *Sim: none (pure data
  record). Makefile: no new target.* Reconsidered ---
  `MoleConfig` is the source of truth for every sub-block's timing,
  so a regression in its derived helpers would silently warp every
  bus speed. Added `MoleConfigSim` as a plain Scala `App` (no
  `SimConfig.compile`) that asserts the default config can produce
  a valid integer divider for every Phase-0 bus rate
  (I²C 100 k / 400 k / 1 M, I³C OD 2 M / 4 M) and `println`-warns
  about I³C PP-high (12.5 MHz SCL → 50 MHz quarter rate) being
  out of reach at 48 MHz fabric. Runs in milliseconds; gated by
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
  2. `48 MHz / 115 200 baud` — Mole "early dev".
  3. `48 MHz / 2 Mbaud` — Mole production default per
     `MoleConfig.uartBaud`. iCEBreaker's FT2232H supports up to
     12 Mbaud; 2 Mbaud × 16× oversample = 32 MHz tick rate, well
     under the 24-bit DDS overflow threshold at 48 MHz fabric.
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
  removed — at the v0 default of 48 MHz fabric × 16× oversample
  they would push DDS phaseInc to / past the 24-bit field limit
  and would refuse to elaborate under the new `UartConfig`
  guard. They land as a follow-up once an 8× oversample option
  is added to `UartConfig`.
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
  0xC WAIT_ADDRESSED   0xD MISMATCH_CLEAR     0xE FLAG_CLEAR     0xF CAPTURE_RUN
                       (the four 0xC..0xF slots are reserved for v0.5;
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

### 🔲 Step 8 --- `BitCycleEngineCore` + `QuarterBitTimer` + `BusMode` + `Revision`

**Goal:** the smallest engine that decodes `EMIT_BIT` +
`SET_BUS_MODE` + `HALT` and nothing else. Drives the bus with
quarter-bit timing derived from `MoleConfig.quarterPeriodCyclesReset`.
Generates canonical SCL waveform internally per current `BUS_MODE`.
Reports a fixed `REVISION` word on `HALT`.

**Files:** `src/hw/QuarterBitTimer.scala`,
`src/hw/BitCycleEngineCore.scala`, `src/hw/BusMode.scala`,
`src/hw/SclWaveformGen.scala`, `src/hw/Revision.scala`.

**Design notes:**
- Bus drives are **registered**, not per-state combinational
  (see `AGENTS.md` §"Bus-shaped FSM idiom").
- **`BusMode` register (3 bits)** holds the current active mode
  per ROADMAP §"Bus mode register": `mode[1:0]` = active timing
  divider (`i2c` / `i3c-OD` / `i3c-PP` / `hdr-ddr`), `mode[2]`
  = SCL high-half drive class (OD-release vs PP-high; `mode[2]`
  follows from `mode[1:0]` per the named-symbol table but is
  carried explicitly to keep the SCL-gen path independent of
  the mode decode). One writer (`SET_BUS_MODE`), two readers
  (timing-divider mux, `SclWaveformGen`). Reset value: `i2c`
  (safe default --- OD release on idle bus).
- **`SymbolDecoder`** is a pure combinational function
  `(tx_symbol, BUS_MODE) → (driveLow, driveHigh)` per ROADMAP
  §"TX symbol" decode table. Lives once in the engine; every
  pad consumer (SDA-from-EMIT_BIT, SDA-from-EMIT_QUARTER,
  SCL-from-EMIT_QUARTER, SDA-from-DRIVE_BIT_ON_SCL) routes
  through it. No per-pad mode state.
- **`SclWaveformGen`** emits the engine-generated SCL
  `tx_symbol` stream during `EMIT_BIT`: `dominant` for Q0/Q1,
  `recessive` for Q2/Q3. That stream then goes through the
  shared `SymbolDecoder` with the current `BUS_MODE`, so the
  Q2/Q3 high half automatically becomes OD-release under
  `i2c`/`i3c-OD` and PP-high under `i3c-PP`/`hdr-ddr` without
  duplicate decode logic.
- **Canonical `EMIT_BIT` shape (Model A).** One `EMIT_BIT` = one
  full wire bit = 4 quarters. SDA is held at the instruction's
  `tx_symbol` across all 4 quarters; the symbol decodes against
  the active `BUS_MODE`. SCL is driven by `SclWaveformGen` ---
  *not* by the bitstream. The SDK emits one `EMIT_BIT` per
  wire bit and never has to think about the SCL waveform. See
  ROADMAP §"Canonical EMIT_BIT shape" for the contract.
- **`EMIT_QUARTER` overrides `SclWaveformGen`.** When the
  current instruction is `EMIT_QUARTER`, the SCL pad takes its
  `tx_symbol` from the bitstream's `scl_symbol` field instead
  of `SclWaveformGen`, for that single quarter. SDA likewise
  takes `sda_symbol` from the bitstream. Both still flow
  through `SymbolDecoder`. This is the only path to per-quarter
  SCL control. **Target-role lint:** the SDK rejects
  `EMIT_QUARTER` with `scl_symbol = recessive` when `BUS_MODE`
  is PP class (would request PP-drive-1 of SCL --- target must
  never do that). The engine ignores that combination as
  defense in depth.
- Pads are push-pull-capable (SB_IO push-pull mode) so the
  PP-class `tx_symbol = recessive` decode (`driveHigh := True`)
  actively drives high (for I3C PP). External pull-ups still
  present so OD recessive works for I2C / I3C OD; push-pull
  always wins against the pull-up.
- Quarter-bit timer is a down-counter loaded with the current
  active divider's `quarterPeriodCycles` value (selected by
  `BUS_MODE.mode[1:0]`); the bit FSM advances one quarter-state
  per timer underflow.
- `Revision.scala` reads `sys.props.getOrElse("revision.major",
  "0").toInt` etc. with defaults matching the Makefile.

**Sim:** Step 9.

### 🔲 Step 9 --- `BitCycleEngineSmokeSim`

**Goal:** load a hand-encoded program that emits one byte (e.g.
`0x55`) onto the sim bus, then `HALT`. Assert the wire trace
matches the expected quarter-bit pattern.

The program must cover both engine-generated SCL drive classes:

**Run 1: `SET_BUS_MODE i3c-OD` + EMIT_BIT × 9.** Expected SCL
trace per bit: pulled low Q0/Q1, **released** (Hi-Z, pull-up
wins) Q2/Q3.

**Run 2: `SET_BUS_MODE i3c-PP` + EMIT_BIT × 9.** Expected SCL
trace per bit: pulled low Q0/Q1, **actively driven high (PP)**
Q2/Q3.

A sim helper that distinguishes "pulled high by pull-up" from
"driven high by the DUT" on the wired bus is what catches an
engine that ignores `BUS_MODE.mode[2]` or wires `SclWaveformGen`
to a stuck mode.

Also cover both `tx_symbol` encodings of "1" for SDA in
`EMIT_BIT`:
- **OD release** (`tx_symbol = recessive` under `i3c-OD`) ---
  symbol decoder produces `(driveLow=0, driveHigh=0)`; bus
  floats, sim pull-up wins.
- **PP high** (`tx_symbol = recessive` under `i3c-PP`) --- symbol
  decoder produces `(driveLow=0, driveHigh=1)`; DUT actively
  drives the wired bus high; pull-up irrelevant.

`tx_symbol = hiz` is also exercised on at least one bit (the ACK
slot is the natural place): decoder produces `(0, 0)`
regardless of `BUS_MODE`.

**Files:** `src/sim/BitCycleEngineSmokeSim.scala`.

**Makefile:** uncomment `sim-engine-smoke`.

### 🔲 Step 10 --- Async waits + stretch

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

### 🔲 Step 11 --- Control flow + bookkeeping + timing override

**Goal:** implement `JMP`, `BRANCH_ON cond, offset`, `MARK`,
`LOAD_TIMING`. After this step the controller-side engine is
ISA-complete for v0 (target-side opcodes land in Step 19).

**Files:** extend `BitCycleEngineCore.scala`.

**Design notes:**
- `BRANCH_ON cond, offset` shares its 4-bit cond-code namespace
  with `WAIT_ON` (same encoder enum, same engine decoder). The
  `MISMATCH` / `NOT_MISMATCH` codes key off the sticky
  `MISMATCH_FLAG` set by the most recent `EMIT_*` /
  `SAMPLE_BIT_ON_SCL` / `DRIVE_BIT_ON_SCL` against its
  `expect`/`mask`. The flag is sticky --- only the next
  sampling opcode (or v0.5 `FLAG_CLEAR`) clears it.
- Offset is 8-bit signed PC-relative (±128). Long-range
  conditional branches expand to `BRANCH_ON cond, near` +
  `JMP far` in the SDK.
- `JMP addr` is 12-bit absolute (4096-insn program limit).
- `MARK label` writes a (label, implicit-timestamp) tuple into
  the result ring.
- `LOAD_TIMING reg word` updates the quarter-bit divider word
  for the selected timing register (`pp-freq`, `od-freq`,
  `i2c-freq`, `hdr-ddr-freq`). Takes effect on the **next**
  `EMIT_*` (or sample/drive) instruction whose `BUS_MODE`
  selects that divider.

### 🔲 Step 12 --- `BitCycleEngineSim` (full ISA)

**Goal:** systematic coverage of every opcode + every operand
field. Includes branch-taken / branch-not-taken pairs, stretch
under measured-vs-programmed timing, async waits with timeout,
result-ring overflow, mismatch tracking across multiple `EMIT_*`.

**Files:** `src/sim/BitCycleEngineSim.scala`.

**Makefile:** uncomment `sim-engine-full`.

---

## 🔲 Phase 2 --- Top integration

### 🔲 Step 13 --- `MoleTop`

**Goal:** wire UART RX → SPRAM loader → engine → result-ring →
UART TX. Add status LEDs and a heartbeat. This is the
synthesisable top-level.

**Files:** `src/hw/MoleTop.scala`.

**Suggested IO:** matches `icebreaker.pcf` (clock, reset, UART
RX/TX, SDA, SCL, three status LEDs).

**Design notes:**
- Loader: simple length-prefixed framing over UART (`[len_lo,
  len_hi, opcode_words..., crc16]`); rejects malformed frames.
- Result ring drains to UART TX continuously; back-pressure
  surfaces to the engine as a "ring full" fault on `MARK` /
  capture writes.
- LEDs: R = fault, G = running, B = idle/heartbeat.

### 🔲 Step 14 --- `MoleTopSim`

**Goal:** end-to-end sim: push a hand-encoded program over the
sim UART, observe wire activity on the sim bus, drain the sim
UART and decode the result ring.

**Files:** `src/sim/MoleTopSim.scala`.

**Makefile:** uncomment `sim-top`.

### 🔲 Step 15 --- `MoleTopVerilog`

**Goal:** the canonical Verilog-generation entrypoint. Generated
module name is `MoleTop`.

**Files:** `src/hw/MoleTopVerilog.scala`.

**Makefile:** uncomment `gen`. After this lands, `make all`
produces `gen/MoleTop.bin`.

### 🔲 Step 16 --- Flashable bitstream + smoke

**Goal:** prove the toolchain end-to-end: `make all`, `make
flash`, blinking LED via a hand-encoded program loaded over
`/dev/ttyUSB0`.

**Files:** none new --- this is a process step.

**Bring-up:**
- `make flash` programs the iCEbreaker via channel B.
- `cat blink.mole.bin > /dev/ttyUSB0` loads the program.
- LED toggles at the expected rate; UART echoes back the
  result-ring trailer.

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

## 🔲 Phase 4 --- Target role

### 🔲 Step 19 --- `SAMPLE_BIT_ON_SCL` + `DRIVE_BIT_ON_SCL`

**Goal:** complete the target-role half of the ISA. After this
step the engine can act as either I2C/I3C controller (using
`EMIT_BIT` / `EMIT_QUARTER`) or target (using `SAMPLE_BIT_ON_SCL`
/ `DRIVE_BIT_ON_SCL`), selected by a config bit. The two
opcodes are deliberately placed in Phase 4 because they need a
working external controller to test against --- the MCXA
bring-up from Step 18 doubles as that controller for sim and
silicon.

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
- v0.5 may add `WAIT_ADDRESSED my_addr, timeout` as a
  hardware accelerator if the SDK expansion (`WAIT_ON
  START_SEEN, t` + 8 × `SAMPLE_BIT_ON_SCL` + host-compiled
  compare + conditional `DRIVE_BIT_ON_SCL`) proves too slow
  for I3C SDR target emulation at full rate. Out of scope for
  v0.

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

## Out of scope for this TODO

- Scheme compiler / SDK (host-side; lives under `../../crates/`).
- Error-injection PRNG (host-side; see ROADMAP §"Error injection
  model").
- RP2350 transport (v1; postcard-rpc).
- HDR-DDR (v2; ECP5-45K).
- PHY daughter card (v2; programmable VIO).
