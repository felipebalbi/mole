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

The 10-opcode ISA (`EMIT_BIT`, `EMIT_QUARTER`, `STRETCH_SCL`,
`WAIT_SCL_RELEASE`, `WAIT_SDA_LOW`, `JMP`, `BRANCH_ON_MISMATCH`,
`HALT`, `MARK`, `LOAD_TIMING`) and its 16-bit encoding are the
externally visible contract. See `../../ROADMAP.md` §"Layer 0" and
`AGENTS.md` §"ISA is a stable contract" before changing either.

---

## ✅ Done

- [x] Project scaffold (Makefile, build.sbt, .scalafmt.conf,
      icebreaker.pcf, README.md, AGENTS.md, TODO.md, project/,
      src/{hw,sim}/).

---

## 🔲 Phase 0 --- Foundations

### 🔲 Step 1 --- `MoleConfig`

**Goal:** a single, by-value compile-time record that every
sub-block keys off, so widths and counter constants are derived
once at elaboration. Mirrors `I2cConfig` from the I2c example
project.

**Files:** `src/hw/MoleConfig.scala`.

**Suggested fields:**
- `fabricFreqHz: HertzNumber` --- target post-PLL clock. v0 = 48
  MHz (UP5K-friendly), tunable up to ~60 MHz with timing margin.
- `quarterPeriodCyclesReset: Int` --- power-on default for the
  quarter-bit divider (overridable at runtime via `LOAD_TIMING`).
- `programWordCount: Int` --- SPRAM-backed program memory depth
  in 16-bit words.
- `resultRingByteCount: Int` --- result ring depth in bytes.
- `captureMaxBits: Int` --- per-program cap on capturable bits
  (back-pressure boundary).
- `uartBaud: Int` --- default UART baud (3 Mbaud comfortable on
  FT2232H; 115 200 for early dev).

**Design notes:**
- All `quarterPeriodCycles` derivations live here, exactly like
  `I2cConfig` did for I²C. Sub-blocks consume the derived field;
  they do not re-derive from `fabricFreqHz`.
- `fabricFreqHz` is plumbed as a Spinal `HertzNumber` so the
  type system catches MHz-vs-Hz mismatches at elaboration.

**Sim:** none (pure data record).

**Makefile:** no new target.

### 🔲 Step 2 --- `OpenDrainBus`

**Goal:** the `IMasterSlave` bundle every block that touches the
bus exposes. Mirrors `I2cIo` in the I2c example project but with
push-pull-capable pads so the per-bit `drive_high` flag can
actively drive the line high (I3C PP) instead of releasing it
(I2C / I3C OD).

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
- `Opcode` SpinalEnum with exactly the 10 opcodes listed in
  ROADMAP §"Layer 0". Reserved opcodes (`LOAD_REG`,
  `BRANCH_ON_CAPTURED_MASK`, `CAPTURE_RUN`, `CALL`, `RET`) get
  fixed encoding slots **but no implementation** in v0.
- `Instruction` bundle: opcode field + operand fields, packed to
  16 bits.

**Drive field --- locked at 3 bits per line:**
The `EMIT_BIT` / `EMIT_QUARTER` `drive` operand is **3 bits per
line × 2 lines (SDA, SCL) = 6 bits**. Per ROADMAP §"Drive field":

```
drive[2] = drive_high      0 = Hi-Z when bit_value=1 (open-drain)
                           1 = actively drive high   (push-pull)
drive[1] = bit_value       0 = low, 1 = high
drive[0] = drive_enable    0 = total Hi-Z, ignore bit_value
                           1 = drive per bit_value
```

This is what makes OD-vs-PP a per-bit *data* choice rather than an
engine mode. Do not collapse `drive_high` and `bit_value` into a
single "released" bit --- that would lose the PP-high case (I3C
SDR data drives `1` actively, not via pull-up).

**Sim notes:** round-trip encode/decode every legal opcode +
operand range; assert all four useful `drive` combinations
(`OD low`, `OD release`, `PP low`, `PP high`) round-trip exactly;
assert reserved opcodes decode to a "trap" instruction the engine
refuses to execute.

**Makefile:** uncomment `sim-isa`.

### 🔲 Step 8 --- `BitCycleEngineCore` + `QuarterBitTimer` + `Revision`

**Goal:** the smallest engine that decodes `EMIT_BIT` + `HALT` and
nothing else. Drives the open-drain bus with quarter-bit timing
derived from `MoleConfig.quarterPeriodCyclesReset`. Reports a
fixed `REVISION` word on `HALT`.

**Files:** `src/hw/QuarterBitTimer.scala`,
`src/hw/BitCycleEngineCore.scala`, `src/hw/Revision.scala`.

**Design notes:**
- Bus drives are **registered**, not per-state combinational
  (see `AGENTS.md` §"Bus-shaped FSM idiom").
- Pads are push-pull-capable (SB_IO push-pull mode) so the
  `drive_high` bit of the per-bit `drive` field can actively
  drive high for I3C PP. External pull-ups still present so OD
  "1" works for I2C / I3C OD; push-pull always wins against the
  pull-up.
- The drive field decodes per line (SDA, SCL) per ROADMAP
  §"Drive field". The engine does not know which mode it is in
  --- it just applies `drive_high` / `bit_value` /
  `drive_enable` to the pad each quarter.
- Quarter-bit timer is a down-counter loaded with the current
  `quarterPeriodCycles` value; the bit FSM advances one
  quarter-state per timer underflow.
- `Revision.scala` reads `sys.props.getOrElse("revision.major",
  "0").toInt` etc. with defaults matching the Makefile.

**Sim:** Step 9.

### 🔲 Step 9 --- `BitCycleEngineSmokeSim`

**Goal:** load a hand-encoded program that emits one byte (e.g.
`0x55`) onto the sim open-drain bus, then `HALT`. Assert the
wired-AND bus trace matches the expected quarter-bit pattern.

Cover both encodings of "1":
- **OD release** (`drive_high=0, bit_value=1, drive_enable=1`)
  --- bus floats, sim pull-up wins.
- **PP high**   (`drive_high=1, bit_value=1, drive_enable=1`)
  --- DUT actively drives the wired-AND high; pull-up irrelevant.

A sim helper that distinguishes "pulled high" from "driven high"
on the wired-AND bus is what catches an engine that silently
ignores `drive_high`.

**Files:** `src/sim/BitCycleEngineSmokeSim.scala`.

**Makefile:** uncomment `sim-engine-smoke`.

### 🔲 Step 10 --- Async waits + stretch

**Goal:** implement `EMIT_QUARTER`, `STRETCH_SCL`,
`WAIT_SCL_RELEASE`, `WAIT_SDA_LOW`. These are the
"non-fixed-cadence" opcodes --- they pause the quarter-bit timer
on external bus state.

**Files:** extend `BitCycleEngineCore.scala`.

**Design notes:**
- `STRETCH_SCL n` reuses the registered SCL drive: forces it low
  for `n` quarters of the *programmed* (not measured) period.
- `WAIT_SCL_RELEASE` / `WAIT_SDA_LOW` are timeout-bounded async
  escapes; on timeout, the engine sets a fault bit and continues
  to the next instruction (host post-processes the fault).

### 🔲 Step 11 --- Control flow + bookkeeping + timing override

**Goal:** implement `JMP`, `BRANCH_ON_MISMATCH`, `MARK`,
`LOAD_TIMING`. After this step the engine is ISA-complete for v0.

**Files:** extend `BitCycleEngineCore.scala`.

**Design notes:**
- `BRANCH_ON_MISMATCH` keys off a single-bit "last EMIT
  mismatched its expect" reg.
- `MARK` writes a (label, implicit-timestamp) tuple into the
  result ring.
- `LOAD_TIMING reg word` updates the quarter-bit divider word for
  the selected timing register (pp / od / i2c). Takes effect on
  the **next** `EMIT_*`.

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

## Out of scope for this TODO

- Scheme compiler / SDK (host-side; lives under `../../crates/`).
- Error-injection PRNG (host-side; see ROADMAP §"Error injection
  model").
- RP2350 transport (v1; postcard-rpc).
- HDR-DDR (v2; ECP5-45K).
- PHY daughter card (v2; programmable VIO).
