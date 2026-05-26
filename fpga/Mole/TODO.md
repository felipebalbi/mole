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

---

## 🔲 Phase 0 --- Foundations

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
  `uartBaud = 921 600`. Each field has a `require(...)` guard;
  `programWordCount` is capped at 4096 per the ISA's 12-bit JMP
  operand (ROADMAP §"Encoding width"). One helper:
  `quarterPeriodCyclesFor(quarterHz: HertzNumber): Int`.
- **`uartBaud` choice:** the TODO hint says "3 Mbaud comfortable on
  FT2232H" but at the v0 default of 48 MHz fabric × 16× RX
  oversample, a 3 MBaud DDS would need `phaseInc = 2^24` --- right
  at the 24-bit field's overflow. 921 600 keeps a 3.3× DDS margin
  (rubber-duck-caught blocking issue). Raising to 3 MBaud needs
  either an 8× oversample option in `UartConfig` or fabric > 60 MHz;
  both are out of Phase-0 scope.
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

### 🔲 Step 2 --- `OpenDrainBus`

**Goal:** the `IMasterSlave` bundle every block that touches the
bus exposes. Mirrors `I2cIo` in the I2c example project but with
push-pull-capable pads so the per-bit `tx_symbol` field can
decode (against the active `BUS_MODE`) into an actively driven
high (I3C PP) instead of just releasing it (I2C / I3C OD).

**Files:** `src/hw/OpenDrainBus.scala`.

**Suggested IO:**
```scala
case class MoleBusLine() extends Bundle {
  val driveLow  = Bool()  // pull NMOS low
  val driveHigh = Bool()  // active high (PP); ignored in OD mode
  val read      = Bool()  // sampled wire value
}

case class MoleBus() extends Bundle with IMasterSlave {
  val scl = MoleBusLine()
  val sda = MoleBusLine()
  override def asMaster(): Unit = {
    out(scl.driveLow, scl.driveHigh, sda.driveLow, sda.driveHigh)
    in(scl.read, sda.read)
  }
}
```

**Design notes:**
- This is *not* a stock `ReadableOpenDrain` --- that primitive has
  only `(write, read)` and can't express PP-drive-high. Mole
  needs the third state explicitly. Document the divergence from
  the I2c example project here when this lands.
- Decoder lives elsewhere (engine / pad wrapper); this bundle is
  just plumbing.
- Pad wrapper (`MolePad.scala` later, or inline in `MoleTop`)
  maps to SB_IO in push-pull mode with output-enable controlled
  by `(driveLow | driveHigh)`. Bus contention (`driveLow &
  driveHigh`) is illegal and should be asserted out in sim.
- The "release all" helper writes `False` to all four `drive*`
  fields --- both NMOS off, no active high → pull-up wins.
- One bundle is reused everywhere; do **not** copy the drive
  fields inline into other components.

**Sim:** Step 2 lands `OpenDrainBusSim`, the wired-AND helper for
sims with multiple participants on a bus. Wired-AND becomes
"low wins; if no one is low, highest active-high driver wins; if
no one drives, pull-up wins" --- so the helper has to model three
participants: NMOS pull-downs, PP pull-ups, and the external
pull-up resistor. A bus contention case (one peer drives low,
another drives high) should assert.

**Makefile:** uncomment `sim-opendrain`.

### 🔲 Step 3 --- UART (`UartIo`, `BaudGenerator`, `UartRx`, `UartTx`)

**Goal:** in-tree UART RX/TX, no cross-project dep. 8N1 only;
runtime-tunable baud via a divider counter.

**Files:** `src/hw/UartIo.scala`, `src/hw/BaudGenerator.scala`,
`src/hw/UartRx.scala`, `src/hw/UartTx.scala`.

**Design notes:**
- Single-rate (start, 8 data, stop). No parity. No flow control;
  back-pressure handled at the engine layer via the result ring.
- `BaudGenerator` divides `fabricFreqHz` to the baud-x16 clock
  used by the RX state machine for mid-bit sampling.
- TX is a straightforward shifter; RX is a 16x oversampling FSM.

**Sim:** Step 4.

**Makefile:** no new target yet (sim lands in Step 4).

### 🔲 Step 4 --- `UartSim`

**Goal:** loopback `UartTx` → `UartRx` at several baud rates;
test back-to-back frames; test stop-bit-missing recovery.

**Files:** `src/sim/UartSim.scala`.

**Makefile:** uncomment `sim-uart`.

### 🔲 Step 5 --- `SpramController`

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

**Sim:** Step 6.

### 🔲 Step 6 --- `SpramControllerSim`

**Goal:** smoke-test address mapping, single-port arbitration,
and result-ring wrap-around.

**Files:** `src/sim/SpramControllerSim.scala`.

**Makefile:** uncomment `sim-spram`.

---

## 🔲 Phase 1 --- Engine core

### 🔲 Step 7 --- `Opcode` enum + `Instruction` bundle

**Goal:** lock the externally visible 16-bit instruction encoding.
This is the bytecode wire-format contract --- changing it later is
a breaking change for every deployed Mole.

**Files:** `src/hw/Instruction.scala`, `src/sim/InstructionSim.scala`.

**Suggested IO:**
- `Opcode` SpinalEnum with exactly the 12 opcodes listed in
  ROADMAP §"ISA" (`EMIT_BIT`, `EMIT_QUARTER`, `STRETCH_SCL`,
  `WAIT_ON`, `SET_BUS_MODE`, `SAMPLE_BIT_ON_SCL`,
  `DRIVE_BIT_ON_SCL`, `JMP`, `BRANCH_ON`, `HALT`, `MARK`,
  `LOAD_TIMING`). Reserved v0.5 opcodes (`WAIT_ADDRESSED`,
  `MISMATCH_CLEAR`, `FLAG_CLEAR`, `CAPTURE_RUN`, `CALL`, `RET`)
  occupy fixed encoding slots **but no implementation** in v0
  (only four reserved opcode slots are available, so at most
  four of the six v0.5 candidates can land before an ISA-width
  bump).
- `Instruction` bundle: opcode field + operand fields, packed to
  16 bits.

**TX symbol --- locked at 2 bits per line:**
The per-line drive operand is **2 bits** carrying a symbolic
intent (`tx_symbol`), not a raw electrical state. It appears in
three opcodes:
- `EMIT_BIT.tx_symbol` --- SDA only.
- `EMIT_QUARTER.sda_symbol` + `EMIT_QUARTER.scl_symbol` --- both
  lines (two 2-bit fields, 4 bits total).
- `DRIVE_BIT_ON_SCL.tx_symbol` --- SDA only (target-side,
  external SCL).

`EMIT_BIT` does **not** carry an SCL drive field. SCL is
engine-generated from the `BUS_MODE` register per ROADMAP §"Bus
mode register" and §"Canonical EMIT_BIT shape". The only path to
per-quarter SCL control is dropping to `EMIT_QUARTER`. In target
role the engine releases SCL entirely; `DRIVE_BIT_ON_SCL` /
`SAMPLE_BIT_ON_SCL` pace off external SCL edges.

The 2-bit encoding:

```
tx_symbol[1:0]:
  00 = dominant     pull bus toward dominant state
  01 = recessive    release / drive bus toward recessive state
  10 = hiz          driver disabled (true Hi-Z, no active drive)
  11 = reserved     held for v0.5 raw_override escape hatch
```

Symbol → electrical mapping is owned by `BUS_MODE` (see Step 8
and ROADMAP §"TX symbol"); the engine knows nothing about which
protocol applies. In OD modes `dominant`=OD-low,
`recessive`=OD-release (Hi-Z + pull-up wins). In PP modes
`dominant`=PP-drive-0, `recessive`=PP-drive-1. `hiz` is always
driver-off. Do **not** repurpose the reserved `11` encoding ---
it is the `raw_override` slot reserved for v0.5.

**SET_BUS_MODE encoding:** 3 bits of `mode[2:0]` = 3 bits
operand. Encoder accepts symbolic `{i2c, i3c-OD, i3c-PP,
hdr-ddr}` and maps to the right `mode[2:0]` per ROADMAP §"Bus
mode register" table. All four combinations are used (was: 4th
slot reserved in earlier drafts; `hdr-ddr` now occupies it).

**Engine flags + cond-code namespace:** `BRANCH_ON` and `WAIT_ON`
share field shape (`[11:8]cond_code [7:0]operand`) and the same
4-bit cond-code namespace; only operand semantics differ
(signed-PC-offset vs unsigned-quarter-timeout). Codes 0..9 are
in use (`ALWAYS`, `MISMATCH`, `NOT_MISMATCH`, `START_SEEN`,
`STOP_SEEN`, `SDA_LOW`, `SDA_HIGH`, `SCL_HIGH`, `TIMEOUT`,
`NOT_TIMEOUT`); 10..15 reserved for v0.5. See ROADMAP §"Engine
flags --- unified condition codes". Sticky engine flags
(`MISMATCH_FLAG`, `TIMEOUT_FLAG`, `START_FLAG`, `STOP_FLAG`)
are set by the engine and cleared only by the next opcode that
would write them (or by the future v0.5 `FLAG_CLEAR`).

**Encoding width: 16-bit fixed.** Every opcode is exactly one
16-bit word. The flag triple (`expect`/`mask`/`capture`), where
present, is at fixed bit positions `[2]=expect`, `[1]=mask`,
`[0]=capture`. Symbol fields stay at the high end; reserved
bits fill the middle. Locked per-opcode field layout (matches
ROADMAP §"Encoding width"):

```
EMIT_BIT           [15:12]op [11:10]tx_symbol [9:3]reserved [2]expect [1]mask [0]capture
EMIT_QUARTER       [15:12]op [11:10]sda_symbol [9:8]scl_symbol [7:3]reserved [2]expect [1]mask [0]capture
STRETCH_SCL        [15:12]op [11:0]n_quarters
WAIT_ON            [15:12]op [11:8]cond_code [7:0]timeout_quarters_unsigned
SET_BUS_MODE       [15:12]op [11:9]mode [8:0]reserved
SAMPLE_BIT_ON_SCL  [15:12]op [11:3]reserved [2]expect [1]mask [0]capture
DRIVE_BIT_ON_SCL   [15:12]op [11:10]tx_symbol [9:3]reserved [2]expect [1]mask [0]capture
JMP                [15:12]op [11:0]addr
BRANCH_ON          [15:12]op [11:8]cond_code [7:0]pc_rel_offset_signed
HALT               [15:12]op [11:8]status [7:0]reserved
MARK               [15:12]op [11:4]label [3:0]reserved
LOAD_TIMING        [15:12]op [11:10]reg [9:0]divider_word
```

`JMP` operand is **12-bit absolute address** → 4096-instruction
program limit (= 8 KB). `BRANCH_ON` operand is **8-bit signed
PC-relative offset** → ±128 instructions. Long-range conditional
branches expand to `BRANCH_ON cond, near_label` + `JMP
far_label` in the SDK. Reject programs larger than 4096 insns
at encode time with a clear error; a v1 "jumbo-address" opcode
can lift the limit if a future workload demands it.

**Rejected: 8-bit and variable-length encoding.** See ROADMAP
§"Encoding width". Only `SET_BUS_MODE` (7 bits) would fit in
8 bits; `EMIT_QUARTER` alone needs the full operand width and
every wait / branch / timing opcode needs the full 16.
Variable-length saves ~1 byte per `SET_BUS_MODE` at the cost of
variable fetch, variable PC increment, branch-target alignment,
and a substantially more complex decoder / sim / disassembler.
The SPRAM headroom on UP5K (~16K instructions per bank vs
~2,500 for a worst-case I3C SDR frame) makes the savings
unspendable anyway.

**Rejected: byte-level emits.** No `EMIT_BYTE`, `EMIT_WORD`, or
any "emit N bits in one fetch" opcode. See ROADMAP §"Why no
byte-level emit". Short version: a "byte" on the wire is **9
bits, not 8**, and the 9th (ACK on I2C/I3C address+data, T-bit
on I3C SDR data and CCC) is structurally different from the
first 8 (different driver, different OD/PP, different
`expect`). The SDK provides `write-byte` as a macro that
expands to 9 `EMIT_BIT`s with the correct per-bit operands;
`(map write-byte ...)` handles multi-byte bursts. Any future
proposal to re-add a byte-level emit is a sign the SDK macro
layer needs a new ergonomic instead.

**Rejected: per-bit SCL drive in `EMIT_BIT`'s bitstream.** SCL
drive style is a per-frame-phase choice (handful of times per
transaction), not a per-bit choice --- so it lives in
`BUS_MODE`, set by `SET_BUS_MODE`. The earlier design that put
SCL drive in `EMIT_BIT` had an unresolvable contradiction (a
single drive field cannot encode the canonical
low/low/high/high waveform); see ROADMAP §"Canonical EMIT_BIT
shape". `EMIT_QUARTER` retains per-quarter SCL drive via
`scl_symbol` --- that is the only escape hatch for SCL
glitching and is sufficient for compliance test purposes.

**Rejected: folding `tx_symbol` into `BUS_MODE`.** `BUS_MODE`
owns the *mapping* (slow state: which electrical class
`dominant`/`recessive` decode to). The bitstream carries the
*value* (fast data: which symbol this particular bit is).
Lumping the value into the mapping turns every bit-level
driver flip into a `SET_BUS_MODE` churn. See ROADMAP §"Why SDA
does *not* live in `BUS_MODE`" for the full argument.

**Kept (the dual question): `EMIT_BIT` survives the same
scrutiny.** Could the same argument force a drop to
`EMIT_QUARTER`-only? No. See ROADMAP §"Why not
`EMIT_QUARTER`-only?". The asymmetry: a wire byte's 9-bit
substructure is *structurally non-uniform* (9th bit always
different), so no byte-level instruction can compress it
losslessly. A wire bit's 4-quarter substructure is *structurally
uniform* in normal operation (canonical SCL pulse + steady
SDA), so `EMIT_BIT` compresses it losslessly --- and
`EMIT_QUARTER` exists for the rare non-uniform case (glitch
injection), exactly as per-bit `EMIT_BIT` chains exist for the
rare non-uniform case inside a byte. `EMIT_BIT` is the smallest
wire unit at which substructure becomes naturally uniform; that
is what makes the grain non-arbitrary.

**Sim notes:** round-trip encode/decode every legal opcode +
operand range; round-trip all four `tx_symbol` values
(`dominant`, `recessive`, `hiz`, reserved) for SDA in
`EMIT_BIT` / `DRIVE_BIT_ON_SCL` and for both axes in
`EMIT_QUARTER` --- assert the `reserved`/`raw_override`
encoding round-trips but the engine refuses to decode it in v0.
Round-trip all four `SET_BUS_MODE` symbols (`i2c`, `i3c-OD`,
`i3c-PP`, `hdr-ddr`). Round-trip every in-use cond-code (0..9)
on both `BRANCH_ON` and `WAIT_ON`; assert codes 10..15 round-
trip but decode to a "trap" the engine refuses to execute.
Assert the flag triple lives at `[2:0]` on every bearer opcode
(unaligned positions are a regression).

**Makefile:** uncomment `sim-isa`.

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
