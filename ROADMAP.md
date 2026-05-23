# Mole --- I3C / I2C compliance test rig --- feasibility & plan

**Project**: Mole (product line). SKUs: **Mole Verde** (pocket / per-dev
dongle), **Mole Rojo** (bench / compliance), **Mole Negro** (future
certification tier).

## Problem

Build a MIPI I3C / I2C compliance and conformance test rig, work-alike
to the MCCI Model 2710 SuperMITT, scoped to SDR + HDR-DDR (no
HDR-Ternary, so no analog I3C PHY needed). The rig must drive any
legal *and* illegal SDR / HDR-DDR / I2C sequences with deterministic
timing; observe the bus with quarter-bit resolution; inject errors
exactly when (and only when) the test author requested; exercise
CCC compliance and DAA edge cases; act as either controller *or*
target; emulate well-known I2C / I3C peripheral devices; and present
a scriptable surface to the developer.

## Verdict (TL;DR)

Feasible, architecturally clean, and the first prototype can be
built entirely on hardware you already own (1bitsquared icebreaker
+ a 3.3V DUT + two pull-up resistors + the FT2232H UART that's
already on the icebreaker).

The architecture is a **bit-stream + Scheme SDK** design:

- **FPGA hosts a tiny, protocol-agnostic bit-cycle engine** ("Layer 0"):
  ~15 opcodes, ~1800 LUTs, no knowledge of I2C / I3C, just drives
  quarter-bit patterns on SDA/SCL and compares them against expects.
- **Host hosts a Scheme SDK** ("Layer 1"): all of I2C and I3C lives
  here as Scheme source --- spec-compliant primitives (`sdr/write-byte`,
  `ccc/setdasa`, `i2c/start`) layered on top of raw bit-level
  primitives (`drive-bit`, `drive-quarter`). The SDK is the living,
  diffable encoding of the spec.
- **DSL is a Lisp/Scheme dialect** compiling to Layer-0 bytecode.

Five consequences worth naming explicitly:

1. **Same engine drives both I2C and I3C.** At the wire layer the buses
   are identical; the difference is which Scheme namespace your test
   imports.
2. **Same engine plays both controller and target.** Role is a config
   bit; the SDK exposes `i3c/controller/*` and `i3c/target/*`
   namespaces over a shared set of primitives. In target role the
   engine does **not** drive SCL --- it samples SCL edges driven
   by the external controller and reacts via dedicated
   `WAIT_START`, `WAIT_STOP`, `SAMPLE_BIT_ON_SCL`, and
   `DRIVE_BIT_ON_SCL` opcodes (see ISA section).
3. **Error injection is exact-by-construction.** All PRNG lives in the
   host compiler, never in the engine. `error_ratio = 0` produces a
   bytecode with zero errors written into it; reproducibility is
   `seed + ratio`.
4. **Peripheral emulation is a first-class SDK deliverable.** Same
   target-role machinery lets us ship Scheme libraries that emulate
   TMP108, INA4230, accelerometers, fuel gauges, etc. at the wire
   level, so developers can validate firmware against a virtual
   sensor catalog without owning the parts.
5. **Two hardware tiers from one design**: pocket (iCE40 UP5K,
   SDR/I2C) and bench (ECP5-45K, adds HDR-DDR + deep capture).
   Same Scheme SDK, same bytecode format, same firmware story.

## Architecture --- v0 (icebreaker only, no Pico)

```
   Host (your laptop)
   +-------------------------------------------------+
   | Scheme compiler                                 |
   |   test.lisp -> bytecode.bin                     |
   | Result post-processor                           |
   |   results.bin -> human/machine report           |
   +-------------------+-----------------------------+
                       | USB (FT2232H, two channels)
                       |   ttyUSB0: UART  (program + results)
                       |   ttyUSB1: bitstream programming
                       v
   icebreaker (iCE40 UP5K-SG48)
   +-------------------------------------------------+
   | UART RX/TX (115k--3M baud, FT2232H)             |
   |    |                                            |
   |    v                                            |
   | SPRAM (128 KB) -- program + result ring         |
   |    |                                            |
   |    v                                            |
   | Bit-cycle engine (Layer 0)                      |
   |   - quarter-bit FSM                             |
   |   - 15-opcode decoder                           |
   |   - expect comparator                           |
   |   - capture path                                |
   |   - timing dividers (pp/od/i2c-freq)            |
   +--------+-----------------+----------------------+
            |                 |
            v                 v
       PMOD header        PMOD header (optional probe / scope)
            |
       2x pull-up resistors (jumpered values)
            |
            v
       DUT @ 3.3V (e.g., MCXA dev board running embassy-mcxa)
```

No Pico, no external flash chip, no PHY daughter card, no custom
PCB. Just the icebreaker, two resistors, and the DUT.

## Architecture --- v1+ (Pico polish layer)

```
        Host (USB)
          |
          v
  RP2350 (Rust + Embassy)
   - postcard-rpc transport
   - USB CDC + bulk
   - Bytecode upload, result fetch
   - Firmware upgrade / bitstream loading
          |
          v  SPI / QSPI (PMOD)
  icebreaker (unchanged)
```

The bit engine, SDK, and bytecode are unchanged. The Pico replaces
the FT2232H UART path with a structured RPC transport, enabling:

- USB CDC for the simple developer flow.
- USB bulk endpoint for high-throughput result fetch.
- Eventual postcard-rpc commands for richer host tooling.
- Field deployment without an FT2232H attached.

## Architecture --- v2 (bench tier, ECP5-45K)

Same engine, larger fabric → HDR-DDR support + deep capture +
optional PHY daughter card with programmable VIO. Same Scheme SDK,
same bytecode format. Bytecode programs that don't use HDR-DDR
opcodes run unchanged on either tier; the compiler statically
rejects programs that overrun the pocket tier's timing budget.

## Layer 0 --- the bit-cycle engine

### Design principle

The engine knows nothing about I2C or I3C. It is a wire-level
choreographer:

- Drives SDA / SCL at quarter-bit resolution.
- Compares sampled wire values against expected patterns + masks.
- Records samples to a result ring when the program says so.
- Has minimal control flow so a program can react to mismatches and
  loop.

Every protocol-aware shortcut we resist baking into the engine is a
place where "fix a spec bug" becomes "edit Scheme" instead of
"respin the bitstream". This is the architectural win.

### ISA (v0 --- 15 opcodes, 16-bit encoding)

The ISA splits into four buckets:

- **Wire engine --- role-agnostic primitives** (`EMIT_BIT`,
  `EMIT_QUARTER`, `STRETCH_SCL`): valid in both controller and
  target roles. Timed by the engine's local quarter-bit clock
  (free-running from `LOAD_TIMING`). In controller role they
  produce normal SDA/SCL waveforms; in target role they are the
  primitives for **asynchronous SDA glitch injection and
  invalid-transfer fuzzing**, bracketed by `WAIT_*` sync points.
  See "Target-side glitch and invalid-transfer injection" below.
- **Wire engine --- controller-role helpers** (`WAIT_SCL_RELEASE`,
  `WAIT_SDA_LOW`, `SET_BUS_MODE`): meaningful when the engine
  generates SCL.
- **Wire engine --- target-role helpers** (`WAIT_START`,
  `WAIT_STOP`, `SAMPLE_BIT_ON_SCL`, `DRIVE_BIT_ON_SCL`):
  meaningful when an external controller generates SCL; gate on
  observed SCL edges rather than the engine's local divider.
- **Control flow / bookkeeping** (`JMP`, `BRANCH_ON`, `HALT`,
  `MARK`, `LOAD_TIMING`): role-agnostic.

Role is a config bit, not an opcode: the same program may issue
both role-agnostic ops and either-role helpers, but in practice
a given test runs the engine in one role or the other.

Wire engine --- role-agnostic primitives:

- `EMIT_BIT drive_sda expect capture_en` --- one full bit (4
  quarters at canonical positions). `drive_sda` = **3-bit** drive
  field for SDA only (see "Drive field" below); `expect` = compare
  value + mask; `capture_en` = also write sampled SDA to result
  ring. **SCL is not in the bitstream** --- in controller role
  the engine generates the canonical SCL waveform per the current
  `BUS_MODE` register (see "Bus mode register" below); in target
  role the engine releases SCL (Hi-Z) and the external controller
  drives it. Timed by the engine's local quarter clock either
  way. In target role, useful for **asynchronous** SDA glitch /
  fake-byte injection only --- canonical target byte handling
  uses `DRIVE_BIT_ON_SCL` / `SAMPLE_BIT_ON_SCL` because they
  slave to external SCL edges and do not drift.
- `EMIT_QUARTER drive_sda drive_scl expect capture_en` --- single
  quarter (glitches, sub-bit shaping). Carries **both** SDA and
  SCL 3-bit drive fields --- full per-quarter override of the
  engine's canonical waveform. This is the *only* opcode that
  encodes SCL in the bitstream. In target role, the
  `drive_scl[2]` (drive_high) bit **must be 0** --- the target
  may pull SCL low (stretching / fuzzing) but never PP-drives
  SCL high. The SDK enforces this; the engine ignores
  `drive_scl[2]` when the role bit is target.
- `STRETCH_SCL n` --- hold SCL low for `n` quarters. In
  controller role: measured bus-hold / forced stretching for
  fuzzing. In target role: the canonical clock-stretching
  primitive; the target actively pulls SCL low for `n` quarters
  even though it does not generate the nominal SCL waveform.

Wire engine --- controller-role helpers:

- `WAIT_SCL_RELEASE timeout` --- async escape: pause the quarter
  clock until SCL goes high externally, or timeout fires.
- `WAIT_SDA_LOW timeout` --- needed for IBI / Hot-Join (target
  signals by pulling SDA between Stop and next Start).
- `SET_BUS_MODE mode` --- set the engine's `BUS_MODE` register
  (3 bits: active timing divider {`i2c`, `i3c-OD`, `i3c-PP`,
  `hdr-ddr`} plus SCL high-half drive class {OD-release,
  PP-high}). Affects all subsequent `EMIT_BIT`s until the next
  `SET_BUS_MODE`. See "Bus mode register" below. In target role
  the `mode[2]` SCL drive-class bit is ignored; only the
  timing-divider selection matters.

Wire engine --- target-role helpers (engine does **not** drive
SCL; it samples SCL edges generated by the external controller
and reacts):

- `WAIT_START timeout` --- block until a Start condition (SDA
  falling while SCL is high) or Repeated Start is observed on
  the bus, or until `timeout` quarter-bit ticks elapse. The
  target entry point: every target program begins with this
  (or with `WAIT_ADDRESSED`, see "reserved opcode slot" below).
- `WAIT_STOP timeout` --- block until a Stop condition (SDA
  rising while SCL is high) is observed, or timeout. Used to
  bound a target transaction.
- `SAMPLE_BIT_ON_SCL expect mask capture_en` --- wait for the
  next SCL rising edge (driven externally), sample SDA at the
  canonical sample point in the high half, compare against
  `expect`/`mask`, and optionally write the sampled value to
  the result ring. Target-side read of one bit (address bit,
  controller-write data bit, controller-driven ACK / NAK).
- `DRIVE_BIT_ON_SCL drive_sda expect mask capture_en` --- on
  the next SCL falling edge (driven externally), drive SDA per
  `drive_sda` (3-bit drive field, same encoding as `EMIT_BIT`)
  until the following SCL falling edge --- i.e. for one full
  controller-clocked bit. **Concurrently**, on the SCL rising
  edge inside that bit cell, sample SDA at the canonical sample
  point, compare against `expect`/`mask`, and optionally write
  to the result ring. The simultaneous drive + sample is what
  enables **I3C DAA arbitration**: the target drives its PID
  bit and observes the wire; if it drives 1 but the wire shows
  0, another target won this bit and `MISMATCH_FLAG` is set so
  `BRANCH_ON MISMATCH` can route to a drop-out handler.
  Target-side write of one bit (ACK, T-bit, controller-read
  data, IBI payload bit). Combined with `STRETCH_SCL`, this is
  also how the target paces the controller: pull SCL low before
  releasing SDA.

The four target-role helpers carry the same quarter-bit timing
alignment guarantees as `EMIT_BIT`; the difference is that the
quarter clock is *gated by externally observed SCL edges* rather
than free-running off the engine's divider.

Control flow:

- `JMP addr` --- unconditional jump to absolute 12-bit
  instruction address (4096-instruction range, whole program).
- `BRANCH_ON cond, offset` --- conditional jump to a
  PC-relative signed 8-bit offset (±128 instructions, local
  loops). `cond` is a 4-bit condition code selecting which
  engine state to test. The unified branch opcode replaces all
  per-condition branch instructions: a new condition is a new
  `cond_code` value, not a new opcode. v0 implements four
  condition codes (`ALWAYS`, `NEVER`, `MISMATCH`,
  `NOT_MISMATCH`); see "Engine flags" and "Reserved for v0.5"
  below for the rest.
- `HALT status` --- end of program, status code returned to host.

Bookkeeping:

- `MARK label_id` --- insert labeled marker in result ring (also
  carries an implicit timestamp).
- `LOAD_TIMING reg word` --- load divider words for `pp-freq`,
  `od-freq`, `i2c-freq`, `hdr-ddr-freq`. Programmable per-test.
  Pure --- does not change the active mode (use `SET_BUS_MODE`
  for that).

Total: 15 opcodes. The 4-bit opcode field holds 16 codes; one
slot is intentionally reserved for a future `WAIT_ADDRESSED`
target-side accelerator (address compare in hardware) if the
SDK-level loop of `SAMPLE_BIT_ON_SCL × 7 + compare + ACK` proves
too slow for real targets at I3C SDR rates. A future
`MISMATCH_CLEAR` opcode (see "Engine flags") would, if needed,
repurpose this same slot --- one or the other, not both.

#### Encoding width --- 16-bit fixed

The instruction word is **16 bits fixed-width**. Per-opcode field
budget (post-SCL-move, locked):

```
EMIT_BIT           [15:12]op [11:9]drive_sda [8]expect [7]mask [6]capture [5:0]reserved
EMIT_QUARTER       [15:12]op [11:9]drive_sda [8:6]drive_scl [5]expect [4]mask [3]capture [2:0]reserved
STRETCH_SCL        [15:12]op [11:0]n_quarters
WAIT_SCL_RELEASE   [15:12]op [11:0]timeout_quarters
WAIT_SDA_LOW       [15:12]op [11:0]timeout_quarters
SET_BUS_MODE       [15:12]op [11:9]mode [8:0]reserved
WAIT_START         [15:12]op [11:0]timeout_quarters
WAIT_STOP          [15:12]op [11:0]timeout_quarters
SAMPLE_BIT_ON_SCL  [15:12]op [11]expect [10]mask [9]capture [8:0]reserved
DRIVE_BIT_ON_SCL   [15:12]op [11:9]drive_sda [8]expect [7]mask [6]capture [5:0]reserved
JMP                [15:12]op [11:0]addr
BRANCH_ON          [15:12]op [11:8]cond_code [7:0]pc_rel_offset_signed
HALT               [15:12]op [11:8]status [7:0]reserved
MARK               [15:12]op [11:4]label [3:0]reserved
LOAD_TIMING        [15:12]op [11:10]reg [9:0]divider_word
```

The four target-role opcodes reuse field shapes already present
in the controller-role opcodes: `WAIT_START` / `WAIT_STOP` mirror
`WAIT_SCL_RELEASE` / `WAIT_SDA_LOW` (12-bit timeout in quarters);
`SAMPLE_BIT_ON_SCL` mirrors the `expect`/`mask`/`capture` triple
from `EMIT_BIT`; `DRIVE_BIT_ON_SCL` reuses the 3-bit `drive_sda`
field **and** the `expect`/`mask`/`capture` triple (the simul-
taneous drive + sample is what enables I3C DAA arbitration ---
see "Engine flags" and the target-side examples). No new
decoder shapes, just two new control verbs
(wait-for-edge vs. wait-for-divider) and the inversion of who
sources SCL.

Two smaller widths were considered and rejected:

- **8-bit fixed.** Only `SET_BUS_MODE` (7 bits) fits.
  `EMIT_QUARTER` alone needs **13** bits (4 op + 3 SDA + 3 SCL + 3
  expect/mask/capture), `EMIT_BIT` needs 10, and every
  control-flow / timing / wait opcode needs the full 16 (4 op +
  12-bit operand). Half the ISA would need multi-word encoding,
  which kills the single-cycle decoder.
- **Variable-length (8 + 16 hybrid, RISC-V "C"-style).** The only
  opcode that could realistically go short is `EMIT_BIT`, and only
  by giving up `capture_en` and/or inline `expect`/`mask` ---
  features we just locked in. Saves ~1 byte per frame for the
  ~4 `SET_BUS_MODE`s, at the cost of variable instruction fetch,
  variable PC increment, alignment handling at branch targets,
  and a substantially more complex decoder, sim, and
  disassembler. Cost ≫ benefit.
- **12-bit fixed.** `EMIT_QUARTER` still does not fit (13 bits);
  branch/wait operands have no headroom.

The SPRAM headroom on UP5K (256 Kbit = 16 K instructions at
16-bit) makes the savings unspendable anyway: a worst-case I3C
SDR 256-byte payload is ~2,500 instructions ≈ 15 % of one SPRAM
bank. We are not memory-bound.

**JMP / BRANCH address space.** `JMP` carries a 12-bit absolute
address: **4096 instructions = 8 KB program max**. Comfortably
fits ~80 typical compliance tests (~50--100 insn each) in one
program. `BRANCH_ON` carries an 8-bit **signed PC-relative
offset** (±128 instructions) --- intentionally narrow: branches
are local loops (address compare, DAA polling, retry on
mismatch), not cross-program jumps. Long-distance control flow
goes through `JMP`. If a future workload ever needs more than
4096-instruction absolute range, a v1 "jumbo-address" opcode is
a follow-up, not a v0 blocker. The fixed-width encoding is part
of the wire-format stability contract --- changing it counts as
a bytecode-version bump, same as reordering opcodes.

#### Engine flags

The engine maintains a small set of **implicit flags** that are
set by certain opcodes and read by `BRANCH_ON cond`. Flags are
deliberately implicit (no opcode reads or writes a named flag
register) so that programs stay short: an `EMIT_BIT` followed by
`BRANCH_ON MISMATCH` is two words, not four.

**`MISMATCH_FLAG`** (the only flag in v0):

| Aspect       | Definition                                                                                                                                                                       |
|--------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Width        | 1 bit                                                                                                                                                                            |
| Reset value  | 0 (cleared at program start / engine reset)                                                                                                                                      |
| Set by       | `EMIT_BIT`, `EMIT_QUARTER`, `SAMPLE_BIT_ON_SCL`, `DRIVE_BIT_ON_SCL` whose `expect`/`mask` compare against the observed SDA value **fails**                                       |
| Cleared by   | the same four opcodes when their compare **passes**                                                                                                                              |
| Untouched by | every other opcode (`STRETCH_SCL`, `WAIT_*`, `SET_BUS_MODE`, `LOAD_TIMING`, `MARK`, `JMP`, `BRANCH_ON`, `HALT`) --- flag is **sticky** across non-expect ops                     |
| Read by      | `BRANCH_ON MISMATCH` / `BRANCH_ON NOT_MISMATCH`                                                                                                                                  |

Stickiness is the load-bearing property: it lets a program emit
N bits and then branch once on "any of them failed", instead of
having to bracket each emit with a clear. The "auto-clear on
next passing compare" rule is what keeps the flag from going
permanently stale: any successful expect resets it.

v0 deliberately does **not** ship an explicit `MISMATCH_CLEAR`
opcode; the one reserved opcode slot in §"ISA" (today held for
`WAIT_ADDRESSED`) could be repurposed for it in v0.5 if a
program ever needs to clear the flag without consuming a wire
bit. One or the other --- not both, without an ISA-width bump.

**`BRANCH_ON cond, offset`** condition codes:

| code  | name           | v0   | semantics                                 |
|-------|----------------|------|-------------------------------------------|
| 0     | `ALWAYS`       | yes  | unconditional --- short relative jump     |
| 1     | `NEVER`        | yes  | typed no-op / placeholder                 |
| 2     | `MISMATCH`     | yes  | `MISMATCH_FLAG == 1`                      |
| 3     | `NOT_MISMATCH` | yes  | `MISMATCH_FLAG == 0`                      |
| 4     | `TIMEOUT`      | v0.5 | last `WAIT_*` opcode timed out            |
| 5     | `NOT_TIMEOUT`  | v0.5 | last `WAIT_*` did not time out            |
| 6     | `CAPTURE_LOW`  | v0.5 | last captured SDA bit was 0               |
| 7     | `CAPTURE_HIGH` | v0.5 | last captured SDA bit was 1               |
| 8--15 | reserved       | ---  | future: `REG_MASK_EQ`, `IBI_PENDING`, ... |

Adding a v0.5 condition is **not** a wire-format break: existing
programs that never emit those codes continue to assemble and
execute unchanged. Reordering or repurposing a code already in
use **is** a break --- same status as reordering opcodes.

#### Target-side glitch and invalid-transfer injection

Compliance testing requires deliberately ill-formed traffic ---
fake Start edges, mid-bit SDA flips, ACK released a quarter too
early, IBI requested at illegal moments, drop-out partway
through DAA. None of these need a new opcode: they all fall out
of the orthogonality between the role-agnostic primitives
(`EMIT_BIT`, `EMIT_QUARTER`, `STRETCH_SCL` --- timed by the
engine's local quarter clock) and the target-role helpers
(`SAMPLE_BIT_ON_SCL`, `DRIVE_BIT_ON_SCL`, `WAIT_*` --- timed by
externally observed SCL edges). A target program brackets a
free-running glitch with `WAIT_*` sync points to anchor it to
the controller's frame.

Worked sketches:

| Glitch                                       | Sequence sketch                                                                |
|----------------------------------------------|--------------------------------------------------------------------------------|
| SDA flip mid-bit during controller read      | `SAMPLE_BIT_ON_SCL` → `EMIT_QUARTER × k` (drive SDA opposite) → resume         |
| ACK released too early / too late            | `WAIT_SCL_RELEASE` → `EMIT_QUARTER × k` shifting the release point             |
| Fake Start / Stop edge while addressed       | `WAIT_*` → `EMIT_QUARTER × 2` (SDA edge while SCL high)                        |
| IBI request at illegal moment                | `WAIT_STOP` → `EMIT_QUARTER × N` (pull SDA low between Stop and next Start)    |
| Non-canonical SCL stretch                    | `STRETCH_SCL n` at an arbitrary point in the byte                              |
| Bad T-bit / parity                           | `DRIVE_BIT_ON_SCL drive_sda=<wrong>` in place of the correct T-bit            |
| DAA drop-out partway through                 | `DRIVE_BIT_ON_SCL` with `expect/mask` + `BRANCH_ON MISMATCH lost_arbitration` |

The orthogonality fits a four-quadrant table:

|                | engine-clocked (free-running)         | external-SCL-gated                |
|----------------|---------------------------------------|-----------------------------------|
| Sample only    | `EMIT_BIT` with `drive_sda=Hi-Z`      | `SAMPLE_BIT_ON_SCL`               |
| Drive only     | `EMIT_BIT` with `capture=0`           | `DRIVE_BIT_ON_SCL` (no expect)    |
| Drive + sample | `EMIT_BIT` full form                  | `DRIVE_BIT_ON_SCL` with `expect`  |
| Per-quarter    | `EMIT_QUARTER`                        | `WAIT_*` then `EMIT_QUARTER`      |

Two SDK-level lints follow from this and are documented here
(enforced by the host compiler, not the engine):

1. In target role, reject `EMIT_QUARTER` whose `drive_scl[2]`
   bit is 1. The target never PP-drives SCL; the engine
   ignores the bit but the SDK refuses to emit it so the
   bytecode disassembly stays honest.
2. In target role, warn (not reject) on `EMIT_BIT` used for
   normal byte handling --- it free-runs off the engine
   divider and will drift relative to the controller's SCL.
   Canonical target byte handling uses `DRIVE_BIT_ON_SCL` /
   `SAMPLE_BIT_ON_SCL`. `EMIT_BIT` in target programs is
   reserved for deliberate asynchronous glitch injection.

### Canonical `EMIT_BIT` shape

`EMIT_BIT` produces **one full I2C/I3C bit on the wire** in 4
quarter-bit cycles, with a fixed canonical shape:

```
Quarter:           Q0      Q1      Q2      Q3
SCL:               low     low     high    high
SDA:               <held at the specified bit_value throughout>
Sample point:                       ^        (target reads SDA mid-high)
```

Concretely:

- SCL is **engine-generated**, not encoded in the `EMIT_BIT`
  bitstream. The engine drives SCL low for the first half of the
  bit (Q0, Q1) and high for the second half (Q2, Q3); the
  "high" half is OD-release or PP-driven-high depending on the
  current `BUS_MODE` register.
- SDA holds the specified `bit_value` across all 4 quarters,
  driven per the `drive_sda` field.
- The receiving side samples SDA at the SCL rising edge (Q1→Q2);
  Mole's own capture, when enabled, samples in Q2 to align with
  that point.
- One `EMIT_BIT` therefore = one complete clock period on the
  wire. A complete I2C/I3C frame is `EMIT_BIT` × N where each
  bit is one instruction, bracketed by `SET_BUS_MODE`s when the
  phase changes between OD and PP.

This is the *contract* between the SDK and the engine: the SDK
emits one `EMIT_BIT` per wire bit, never has to spell out the SCL
waveform, and cannot accidentally produce a non-canonical bit
shape. Per-quarter deviations (SCL or SDA glitches, non-canonical
bit shapes for compliance error injection) are expressed by
emitting 4 explicit `EMIT_QUARTER`s in place of one `EMIT_BIT` ---
that is the only path to per-quarter SCL control.

#### Worked SDK example

```scheme
(i3c/sdr/emit-bit sda-value)
```

compiles to a single 16-bit instruction:

```
[15:12] opcode      = EMIT_BIT
[11:9]  drive_sda   = (drive_high=1, bit_value=sda-value, drive_enable=1)
[8:5]   expect+mask = (expect=sda-value, mask=1, ...)
[4]     capture_en  = 0
[3:0]   reserved    (~3 bits free for future expect/mask growth)
```

SCL is absent. The engine consults `BUS_MODE` and drives
`low / low / OD-release-or-PP-high / OD-release-or-PP-high`
appropriately. Disassembly of a write frame reads as literal SDA
data, not as clock-and-data.

This is also why `EMIT_BIT` is the right grain --- see "Why not
`EMIT_QUARTER`-only?" below.

### When to use `EMIT_QUARTER`

`EMIT_QUARTER` is the **escape hatch** for any wire shape that is
not a canonical SDR data bit. Its use cases are finite and
bounded:

1. **Bus management primitives (always).** Start, Stop, Repeated
   Start --- SDA edges *while SCL is high* are not a canonical bit
   shape (canonical bits drop SCL first). 2-4 `EMIT_QUARTER`s
   each, depending on how setup/hold dwell is encoded.
   Mode-agnostic; same for I2C, I3C SDR, and I3C HDR. SDK exposes
   these as macros (`i3c/start`, `i3c/stop`,
   `i3c/repeated-start`).
2. **HDR data bits (when HDR is added).** HDR-DDR transmits data
   on *both* SCL edges with a different per-bit shape than SDR's
   canonical low/low/high/high; each HDR-DDR bit compiles to 4
   `EMIT_QUARTER`s. Same for HDR-TSP / HDR-TSL when those land.
3. **Compliance violations (the entire reason Mole exists).**
   Setup-time and hold-time violations, SCL glitches, SDA
   glitches, early- or late-SCL-release, pre-Start bus
   disturbance. The compliance test catalog *is* the EMIT_QUARTER
   workload --- it's how Mole goes from "well-formed protocol" to
   "deliberately malformed protocol."
4. **Optional: bus-idle (tBUF) waits.** Holding both lines high
   for a measured idle interval between Stop and the next Start.
   Can also be done with `STRETCH_SCL` + idle `EMIT_QUARTER`
   combos; SDK picks per readability.

`EMIT_QUARTER` is **not** used for:

- SDR address bits, data bits, ACK / T-bits.
- CCC headers (broadcast 0x7E + CCC code + defining bytes). Each
  byte is 9 canonical bits → `EMIT_BIT × 9`.
- Anything where SCL is canonical and you just want a particular
  SDA value at the canonical sample point. `EMIT_BIT` is correct.

**Frequency in a typical program.** A representative I3C SDR
write of a 16-byte payload:

| Phase                 | Count | Opcode used               |
|-----------------------|------:|---------------------------|
| Start                 |     1 | `EMIT_QUARTER` × ~3       |
| Address + RnW + ACK   |     1 | `EMIT_BIT` × 9            |
| `SET_BUS_MODE i3c-PP` |     1 | `SET_BUS_MODE`            |
| Data + T-bit × 16     |    16 | `EMIT_BIT` × 9 each (144) |
| `SET_BUS_MODE i3c-OD` |     1 | `SET_BUS_MODE`            |
| Stop                  |     1 | `EMIT_QUARTER` × ~3       |

≈ 156 instructions total, of which ~6 are `EMIT_QUARTER` ---
about **4 %**. The rest is `EMIT_BIT`. That ratio is the design
validation: `EMIT_BIT` is the right dominant opcode and
`EMIT_QUARTER` is the right minority escape hatch.

If a future program is *dominated* by `EMIT_QUARTER`, that is a
signal the SDK macro layer is missing an abstraction, not that
the ISA is wrong.

### Bus mode register --- how the engine knows SCL drive style

The engine carries one small piece of state, `BUS_MODE`, with one
writer (`SET_BUS_MODE`) and two readers (the SCL waveform
generator and the timing-divider mux). It is the only protocol
context the engine maintains; everything else lives in the
bitstream.

```
BUS_MODE register (3 bits):
  mode[1:0] = active mode:   00 = i2c
                             01 = i3c-OD
                             10 = i3c-PP
                             11 = hdr-ddr
  mode[2]   = SCL drive class: 0 = OD release on high half
                              (Hi-Z, external pull-up wins)
                             1 = PP active drive high on high half
```

`mode[1:0]` selects which of the four `LOAD_TIMING` divider
registers (`pp-freq`, `od-freq`, `i2c-freq`, `hdr-ddr-freq`)
feeds the quarter-bit timer for subsequent `EMIT_BIT`s.
`mode[2]` directly controls SCL's high-half drive style during
`EMIT_BIT`.

For convenience, `SET_BUS_MODE` accepts a named symbol that the
encoder maps to the right `mode[2:0]` combination:

| Symbol    | mode[1:0] | mode[2] | SCL high-half  | Used for                                 |
|-----------|-----------|---------|----------------|------------------------------------------|
| `i2c`     | 00        | 0       | OD release     | I2C transactions                         |
| `i3c-OD`  | 01        | 0       | OD release     | I3C Start / address / ACK slot / CCC hdr |
| `i3c-PP`  | 10        | 1       | PP active high | I3C SDR data + T-bit                     |
| `hdr-ddr` | 11        | 1       | PP active high | I3C HDR-DDR data words (16b + parity)    |

`hdr-ddr` uses the same push-pull SCL driver class as `i3c-PP`
but reads its quarter-bit timing word from a separate
`LOAD_TIMING reg=11` slot (`hdr-ddr-freq`) so that HDR-DDR's
higher bit rate does not require reloading `pp-freq` whenever a
program toggles between SDR data phases and HDR-DDR data words.
HDR-DDR data words themselves (16 bits + parity + CRC-5
framing) are still expressed as **compile-time-unrolled**
`EMIT_BIT` / `EMIT_QUARTER` sequences emitted by the SDK ---
no new opcode is needed for HDR-DDR. Programs that never use
HDR-DDR never load `hdr-ddr-freq`. HDR-TSP / HDR-TSL remain
explicitly out of scope (require analog PHY --- see §"Problem").

A typical I3C SDR write frame becomes:

```
SET_BUS_MODE i3c-OD       ; Start + 7-bit address are OD
... EMIT_BIT × 9 ...       ; addr + RnW + ACK slot
SET_BUS_MODE i3c-PP       ; switch to PP for SDR data
... EMIT_BIT × 9 × N ...   ; data bytes (data + T-bit)
SET_BUS_MODE i3c-OD       ; ACK slot back to OD
... EMIT_BIT × 1 ...       ; ack slot
HALT
```

Three to four `SET_BUS_MODE`s per frame. Negligible memory cost;
huge disassembly-readability win.

**Target-role usage.** `BUS_MODE` still selects the active
timing-divider register (so `STRETCH_SCL` durations and any
`SAMPLE_BIT_ON_SCL` timeout are interpreted in the correct
quarter-bit unit) and still gates SDA's OD-vs-PP class via the
SDK's `drive_sda` choices, but `mode[2]` (SCL high-half drive
class) is **ignored** in target role: the target never drives
SCL high. The legal target modes are `i2c` and `i3c-OD`; the
SDK rejects `i3c-PP` and `hdr-ddr` in target context because
their `mode[2]=1` would request SCL push-pull, which a target
cannot do. Receiving HDR-DDR data as a target is still
possible: it is built from `SAMPLE_BIT_ON_SCL` /
`DRIVE_BIT_ON_SCL` sequences clocked by the external SCL, with
the `i3c-OD` bus mode active for divider-unit purposes.

#### Why SDA does *not* live in `BUS_MODE`

Symmetry check: could `BUS_MODE` also dictate SDA drive style and
shrink `EMIT_BIT` to a single bit (the SDA value)? No --- and the
asymmetry is structural enough to write down:

SDA drive style changes **inside** a wire byte:

- I3C address byte: 8 bits SDA-OD controller-driven, then 1 ACK
  bit SDA-OD released so the target can drive --- driver flips
  *inside* the byte.
- I2C write data: 8 bits SDA-OD controller, then 1 ACK bit
  SDA-OD released for target.
- I2C read data: 8 bits SDA-OD target, then 1 ACK/NAK bit SDA-OD
  controller.
- I3C SDR data: 8 bits SDA-PP controller, then 1 T-bit SDA-PP
  controller --- uniform driver, but the 9th's *value* is
  parity, not data.

SDA drive style changes every 8 or 9 bits, sometimes mid-byte. A
bus-mode register tracking this would have to become a per-byte
state machine --- exactly the "protocol-aware shortcut" the
ROADMAP explicitly rejects. SCL's drive style, by contrast,
changes a handful of times per transaction, all at frame-phase
boundaries.

The asymmetry: **SCL drive style is slow state; SDA drive style
is fast data.** Slow state lives in `BUS_MODE`. Fast data lives
in the bitstream.

### Drive field --- OD / PP / I2C as bitstream data

The `drive` field is a **3-bit per-line** encoding that selects
open-drain, push-pull, or total Hi-Z for that line during its
emit window. It appears in two places:

- **`EMIT_BIT` carries one `drive_sda` field (3 bits).** SCL is
  engine-generated from `BUS_MODE`; not in the bitstream.
- **`EMIT_QUARTER` carries both `drive_sda` and `drive_scl` (6
  bits total).** Per-quarter override of SCL is the whole point
  of `EMIT_QUARTER` --- glitch injection, early/late SCL release,
  non-canonical bit shapes.

The per-line encoding:

```
drive[2] = drive_high      0 = Hi-Z when bit_value=1 (open-drain)
                           1 = actively drive high   (push-pull)
drive[1] = bit_value       0 = low, 1 = high
drive[0] = drive_enable    0 = total Hi-Z, ignore bit_value
                           1 = drive per bit_value
```

Four useful combinations (apply to SDA in `EMIT_BIT`; apply to
either SDA or SCL in `EMIT_QUARTER`):

| `drive_high` | `bit_value` | `drive_enable` | Effect                       | Used for                         |
|--------------|-------------|----------------|------------------------------|----------------------------------|
| 0            | 0           | 1              | pull low (NMOS on)           | I2C/I3C "0"                      |
| 0            | 1           | 1              | release (Hi-Z, pull-up wins) | I2C/I3C OD "1"                   |
| 1            | 0           | 1              | drive low (push-pull)        | I3C PP "0"                       |
| 1            | 1           | 1              | drive high (push-pull)       | I3C PP "1"                       |
| -            | -           | 0              | total Hi-Z                   | reads, target idle, bus hand-off |

The engine has **no knowledge** of which mode applies where. The
Scheme SDK encodes the OD-vs-PP choice for **SDA** in the
bitstream itself by setting `drive_sda.drive_high` per bit; the
SCL OD-vs-PP choice for the same phase is set once by the
preceding `SET_BUS_MODE` (see "Bus mode register" above), since
SCL drive style is per-phase, not per-bit:

- `i2c/*` namespaces: `SET_BUS_MODE i2c`; SDA `drive_high = 0`.
- `i3c/sdr/start`, `i3c/sdr/addr-byte`, `i3c/sdr/ack-slot`,
  `ccc/header-broadcast`: `SET_BUS_MODE i3c-OD`; SDA
  `drive_high = 0`.
- `i3c/sdr/write-data`, `i3c/sdr/parity-t-bit`, post-ACK payload:
  `SET_BUS_MODE i3c-PP`; SDA `drive_high = 1`.
- The OD ⇄ PP transition is a `SET_BUS_MODE` (one instruction)
  followed by `EMIT_BIT`s with the matching SDA `drive_high`.

This is the same architectural premise that keeps `Start` and `Stop`
out of the engine: the SDK is the spec, the engine is dumb wire.

`LOAD_TIMING reg word` (already in the ISA) carries the
frequency-per-mode choice: separate divider words for `pp-freq`,
`od-freq`, `i2c-freq`. The SDK loads the right ones once at
program start; `SET_BUS_MODE`'s `mode[1:0]` picks which divider
is active.

**Pad-level reality (iCE40 UP5K v0):** the SB_IO primitives are
configured push-pull-capable from the start, with external pull-ups
present so OD "1" still works. "PP high" then competes against the
pull-up benignly (push-pull always wins). For v2 / PHY card, the
PHY can additionally vary slew rate and VIO per phase --- that's a
knob, not an architectural change.

**Compliance bonus:** because the OD ⇄ PP seam is just where the
SDK chose to flip `drive_high`, compliance tests can deliberately
mis-time the seam (e.g., flip a bit early or late around the ACK
slot) to fuzz controller / target implementations. Glitches inherit
the same `drive` field via `EMIT_QUARTER`, so PP-high glitches onto
what should be an OD release are first-class.

### Why no byte-level emit (rejected: `EMIT_BYTE`)

The natural first instinct is to add an `EMIT_BYTE` instruction
that emits 8 bits in one fetch. **We don't.** Reason number one is
that a "byte" on the wire is **9 bits, not 8**, and the 9th bit is
structurally different from the first 8:

| Phase                         | Bits | 9th bit                                                |
|-------------------------------|------|--------------------------------------------------------|
| I3C SDR controller-write data | 9    | T-bit (PP, driven)                                     |
| I3C SDR controller-read data  | 9    | T-bit (controller-driven ACK-of-continue / NAK-to-end) |
| I3C SDR / I2C address byte    | 9    | ACK (target, OD)                                       |
| I2C data byte (write)         | 9    | ACK (target, OD)                                       |
| I2C data byte (read)          | 9    | ACK/NAK (controller, OD)                               |
| CCC code byte                 | 9    | T-bit / parity                                         |

The 9th bit always:

- comes from a different driver than the first 8 (target vs
  controller or vice versa),
- has a different `drive` field --- the T-bit is PP while the ACK
  is OD, mid-burst,
- has a different `expect` value (we're checking ACK semantics,
  not echoing what we drove),
- often needs `capture_en` even when the data bits don't.

So `(i3c/sdr/write-byte #x55)` expands to **9 `EMIT_BIT`s**, not
8, and the 9th carries different operands from the first 8.

The other reasons reinforce the call:

- **16-bit encoding can't hold a byte-level expect.** 8 bits data
  + 8 bits expect + 8 bits mask + 6 bits `drive` + opcode ≈ 36
  bits. Promoting to 32-bit encoding doubles program memory cost
  for every instruction, not just byte ones.
- **SPRAM is plentiful.** UP5K has 1 Mbit = 64 K 16-bit words. A
  worst-case 256-byte I3C payload ≈ 2400 instructions ≈ 4 % of
  SPRAM. We are nowhere near a memory wall.
- **Engine FSM simplicity.** One instruction = one bit-cycle means
  fetch / decode / execute overlaps cleanly with the 4 quarter-bit
  times. A byte-level instruction forces an internal shift
  register, an internal bit counter, and a different "this
  instruction takes 4 vs 36 quarter cycles" timing path. Cost in
  LUTs is non-trivial on UP5K.
- **`EMIT_QUARTER` already broke the byte abstraction.** Sub-bit
  shaping is incompatible with a byte-level emit on its face.
- **SDK loses no expressiveness.** Multi-byte bursts come free at
  the SDK layer via standard list mapping:
  ```scheme
  (map write-byte '(#x55 #x55 #x11 #xaa #xde #xad #xbe #xef))
  ```
  unrolls to 8 × 9 = **72 `EMIT_BIT`s at compile time** with no
  engine support. Macro expansion can encode the ACK / T-bit
  correctly for each position; `EMIT_BYTE` cannot.
- **Authoring discipline.** Forcing every wire event through
  `EMIT_BIT` keeps the spec / SDK code-review surface uniform.
  Anyone reading a generated bitstream sees exactly one
  instruction shape per bit.

Treat any future proposal to re-add a byte-level emit as a sign
the SDK macro layer needs the missing ergonomic instead.

### Why not `EMIT_QUARTER`-only? (the dual question)

Symmetry check: if a byte's substructure (9 bits) makes
`EMIT_BYTE` wrong, doesn't a bit's substructure (4 quarters)
make `EMIT_BIT` equally wrong? Shouldn't we drop to
`EMIT_QUARTER`-only and let the SDK macro-expand every bit into
4 quarters?

**Answer: no, and the asymmetry is precisely what saves
`EMIT_BIT`.**

| Container        | Substructure             | Substructure uniform in normal operation?                                    | Escape hatch for non-uniform case                |
|------------------|--------------------------|------------------------------------------------------------------------------|--------------------------------------------------|
| Byte (9 bits)    | 8 data + 1 ACK/T-bit     | **No** --- 9th bit always has a different driver, OD/PP class, and `expect`. | --- (no opcode could carry it)                   |
| Bit (4 quarters) | canonical SCL + SDA hold | **Yes** --- canonical Q0--Q3 shape applies to every normal bit.              | `EMIT_QUARTER` for glitches and sub-bit shaping. |

The byte's substructure is *structurally non-uniform*; the bit's
substructure is *structurally uniform* in normal operation. The
non-uniform case for the bit (glitch injection) is exactly what
`EMIT_QUARTER` exists for --- structurally analogous to how
per-bit chains of `EMIT_BIT` handle the non-uniform 9th bit
inside a byte.

Going `EMIT_QUARTER`-only would also cost:

- **4× program memory.** A 256-byte I3C payload jumps from
  ~2400 instructions (~4 % of SPRAM) to ~9600 instructions
  (~15 %). It fits, but the headroom for long compliance test
  catalogs shrinks meaningfully.
- **4× fetch rate.** `EMIT_QUARTER` must complete in one
  quarter-bit time, leaving the engine zero cycles to overlap
  fetch / decode / branch resolution with execute. `EMIT_BIT`
  gives the engine 4 cycles per instruction --- comfortable
  headroom on UP5K's fabric.
- **No engineering payoff.** The thing being compressed
  (canonical 4-quarter SCL pulse + steady SDA) is genuinely
  fixed in normal operation. The compression is therefore
  lossless and free.

The grain `EMIT_BIT` picks is not arbitrary; it is the smallest
wire unit at which substructure becomes naturally uniform.
`EMIT_QUARTER` and `EMIT_BIT` together cover the full
expressiveness needed for compliance with no overlap and no
wasted memory.

### Reserved for v0.5 (do not implement yet)

Opcodes (held in the single free opcode-field slot --- see
§"ISA"):

- `WAIT_ADDRESSED my_addr, timeout` --- target-side accelerator:
  wait for Start, sample 8 bits, compare against `my_addr`, ACK
  on hit / release on miss --- all in hardware. Defer until the
  SDK-level expansion (`WAIT_START` + 8 × `SAMPLE_BIT_ON_SCL` +
  host-compiled compare + conditional `DRIVE_BIT_ON_SCL`) proves
  too slow for I3C SDR target emulation at full rate.
- `MISMATCH_CLEAR` --- explicitly clear `MISMATCH_FLAG` without
  consuming a wire bit. If ever needed, it repurposes the
  `WAIT_ADDRESSED` slot above (one or the other, not both ---
  the 4-bit opcode field is full otherwise).
- `CAPTURE_RUN n into addr` --- pure listening for `n` quarters,
  no drive, no expect. Would require another opcode slot;
  defer until loop-based capture shows measurable timing
  jitter.
- `CALL / RET` --- defer; inline SDK macros at compile time.

`BRANCH_ON` condition codes (held in the 12 free `cond_code`
slots --- see §"Engine flags"):

- `TIMEOUT` / `NOT_TIMEOUT` (codes 4, 5) --- branch on whether
  the previous `WAIT_*` opcode timed out. Needs a `TIMEOUT_FLAG`
  setter/clearer entry under "Engine flags".
- `CAPTURE_LOW` / `CAPTURE_HIGH` (codes 6, 7) --- branch on the
  bit most recently written to the result ring. Subsumes the
  "react to PID bits during DAA arbitration" use case originally
  reserved as `BRANCH_ON_CAPTURED_MASK`. Adding multi-bit mask
  compare is a future `REG_MASK_EQ` condition (code 8+), backed
  by a small register file in the engine.
- `IBI_PENDING` and friends --- bus-state observations the
  engine already tracks for `WAIT_SDA_LOW` / `WAIT_START`.

Adding a v0.5 condition code or a v0.5 opcode in a reserved slot
is **not** a wire-format break; reordering or repurposing one
already used **is**.

### Example: I2C write-one-byte in moleasm

The full ISA is small enough that a real transaction fits in
one screen of disassembly. The example below is the canonical
shape the encoder / disassembler emits ("moleasm"); locking
the syntax down here keeps tooling honest.

**moleasm conventions** (locked):

- One instruction per line; `;;` introduces a line comment.
- Opcode mnemonics in upper case, operands in lower case.
- Named symbols for drive fields (`od_low`, `od_release`,
  `pp_high`, `hiz`), bus modes (`i2c`, `i3c-OD`, `i3c-PP`,
  `hdr-ddr`), and `BRANCH_ON` condition codes (`ALWAYS`,
  `MISMATCH`, ...) --- never raw bit values.
- `EMIT_BIT` / `EMIT_QUARTER` / `SAMPLE_BIT_ON_SCL` /
  `DRIVE_BIT_ON_SCL` operands written
  `sda=<drive> expect=<0|1|X> mask=<0|1> capture=<0|1>`, with
  the defaults `expect=X` (don't-care), `mask=0`, `capture=0`
  omitted when unset.
- Branch targets are labels (`name:`); the assembler resolves
  them to absolute 12-bit addresses (`JMP`) or signed 8-bit
  PC-relative offsets (`BRANCH_ON`) per opcode.

I2C write of byte `0xAB` to 7-bit address `0x50` (address byte
on the wire = `(0x50 << 1) | 0 = 0xA0`, MSB first, R/W = 0):

```moleasm
;; I2C write-one-byte: addr 0x50, data 0xAB

        LOAD_TIMING   i2c_freq, 250          ; ~100 kHz @ 100 MHz fabric (illustrative)
        SET_BUS_MODE  i2c                    ; SCL+SDA both OD-release on high half

        ;; -- Start condition: SDA falling while SCL high --
        EMIT_QUARTER  sda=od_release scl=od_release    ; Q0: idle bus
        EMIT_QUARTER  sda=od_low     scl=od_release    ; Q1: SDA pulled low (Start edge)
        EMIT_QUARTER  sda=od_low     scl=od_low        ; Q2: SCL goes low

        ;; -- Address byte 0xA0 = 1010_0000 (MSB first) + R/W=0 --
        EMIT_BIT      sda=od_release         ; bit 7 = 1
        EMIT_BIT      sda=od_low             ; bit 6 = 0
        EMIT_BIT      sda=od_release         ; bit 5 = 1
        EMIT_BIT      sda=od_low             ; bit 4 = 0
        EMIT_BIT      sda=od_low             ; bit 3 = 0
        EMIT_BIT      sda=od_low             ; bit 2 = 0
        EMIT_BIT      sda=od_low             ; bit 1 = 0
        EMIT_BIT      sda=od_low             ; bit 0 = R/W = 0 (write)

        ;; -- ACK slot: release SDA, expect target to pull low --
        EMIT_BIT      sda=hiz expect=0 mask=1 capture=1
        BRANCH_ON     MISMATCH, nak          ; PC-relative, ±128 insn

        ;; -- Data byte 0xAB = 1010_1011 --
        EMIT_BIT      sda=od_release         ; bit 7 = 1
        EMIT_BIT      sda=od_low             ; bit 6 = 0
        EMIT_BIT      sda=od_release         ; bit 5 = 1
        EMIT_BIT      sda=od_low             ; bit 4 = 0
        EMIT_BIT      sda=od_release         ; bit 3 = 1
        EMIT_BIT      sda=od_low             ; bit 2 = 0
        EMIT_BIT      sda=od_release         ; bit 1 = 1
        EMIT_BIT      sda=od_release         ; bit 0 = 1

        ;; -- ACK slot --
        EMIT_BIT      sda=hiz expect=0 mask=1 capture=1
        BRANCH_ON     MISMATCH, nak

        ;; -- Stop condition: SDA rising while SCL high --
        EMIT_QUARTER  sda=od_low     scl=od_low        ; Q0: both low
        EMIT_QUARTER  sda=od_low     scl=od_release    ; Q1: SCL goes high
        EMIT_QUARTER  sda=od_release scl=od_release    ; Q2: SDA goes high (Stop edge)

        MARK          label=ok
        HALT          status=0

nak:    MARK          label=nak
        HALT          status=1
```

Observations:

- The whole transaction is **31 instructions = 62 bytes** of
  bytecode. A 256-byte payload extrapolates linearly to ~280
  instructions, well inside the 4096-instruction `JMP` range
  and a tiny fraction of one SPRAM bank.
- Eight address bits and eight data bits are literal
  `EMIT_BIT`s --- this is what justifies `EMIT_BIT` being the
  dominant opcode (see §"Why not `EMIT_QUARTER`-only?": if a
  future program is dominated by `EMIT_QUARTER`, the ISA is
  wrong).
- Start and Stop are 3 × `EMIT_QUARTER` each --- the canonical
  SDA-edge-while-SCL-high shapes. No special opcode for them;
  they're an SDK macro expanding to these three quarters.
- ACK handling uses `expect=0 mask=1 capture=1`: the bit is
  written to the result ring **and** compared, with mismatch
  routed via `BRANCH_ON` to an error label. This is the
  canonical I2C / I3C-OD ACK pattern; the SDK exposes it as
  `(i2c/ack-slot)`.
- `MARK` tags the OK / NAK paths so the host result decoder
  distinguishes them without parsing bytecode addresses.

The same transaction at the SDK level (six lines of Scheme,
compiling to the 31 wire instructions above):

```scheme
(define (i2c/write-byte addr byte)
  (i2c/start)
  (i2c/emit-byte (logior (ash addr 1) 0))   ; addr + RnW=0
  (i2c/ack-slot)                            ; halts on NAK
  (i2c/emit-byte byte)
  (i2c/ack-slot)
  (i2c/stop)
  (halt 'ok))
```

The disassembly is the auditable, diff-able truth; the Scheme
is the readable surface.

### Why this fits in iCE40 UP5K (5280 LUTs)

| Block                                 | LUT estimate |
|---------------------------------------|--------------|
| Quarter-bit FSM + timing dividers     | ~250         |
| 15-opcode decoder + dispatch          | ~160         |
| PC + JMP / branch logic               | ~120         |
| Expect comparator + capture path      | ~250         |
| Result ring controller                | ~250         |
| UART RX/TX + framing                  | ~300         |
| SPRAM interface                       | ~200         |
| Misc (timestamps, reset, status LEDs) | ~280         |
| **Total**                             | **~1800**    |

Leaves ~3400 LUTs free for headroom, second engine instance, or
debug instrumentation. SPRAM (1 Mbit = 128 KB) holds the bytecode
program + result ring with no external flash. BRAM (120 Kbit ~ 15 KB)
becomes a small scratch / prefetch buffer if needed.

### Clocks (v0 / icebreaker)

- 12 MHz crystal (on icebreaker) → PLL → ~48 MHz fabric.
- Quarter-bit granularity: ~21 ns per quarter.
- Max I3C bit rate: ~48 MHz / 4 = 12 MHz wire (matches I3C SDR spec
  max of 12.5 MHz with a tiny margin).
- Max I2C bit rate: way above any I2C mode.
- `pp-freq`, `od-freq`, `i2c-freq` are runtime divider words
  loaded via `LOAD_TIMING` --- one PLL, multiple bus speeds.

### Clocks (v2 / ECP5-45K bench tier)

- 24 MHz crystal → PLL → 100 MHz fabric.
- Quarter-bit granularity: 10 ns.
- Max I3C bit rate: 25 MHz (covers HDR-DDR at the upper end of the
  practical range; the rig cap of 25 MHz is also a design choice
  that bounds QSPI / capture bandwidth cleanly).

## Bandwidth math

At 25 MHz wire rate:

- Hot path (well-formed traffic): one `EMIT_BIT` per bit = 25 MHz ×
  ~4 bytes/opcode = **100 MB/s instruction rate**. Fits BRAM trivially
  for short tests; fits Quad-SPI-DDR @ 100 MHz for long streamed tests.
- Glitch path (quarter-bit): 100 MHz × ~2 bytes = 200 MB/s, only used
  for short glitched segments, so the average stays low.

At v0 / icebreaker (12 MHz I3C cap, no QSPI):

- ~50 MB/s instruction rate worst case. SPRAM bandwidth (single port,
  128-bit wide internally at fabric clock) is ample.

## Layer 1 --- the Scheme SDK

### Namespace-based compliance guarantee

The SDK is split into two enforcement tiers by namespace:

- **`i2c/`, `i3c/`, `ccc/`, `hdr-ddr/`** --- guaranteed spec-compliant
  primitives. Always produce correct bit patterns, correct T-bit /
  parity, correct CRC, correct timing within the loaded freq config.
- **`raw/`** --- escape hatch for error injection. `raw/drive-bit`,
  `raw/drive-quarter`, `raw/emit-glitched-bit`, etc. Tests using
  raw primitives must declare `(use-raw-primitives)` at the top of
  the source file; the compiler refuses otherwise. This makes
  "is this test injecting errors?" answerable by `grep`.

### Layering

```scheme
;; Layer 1b (compliant, always spec-correct)
(define (sdr/write-bit value)
  (raw/drive-bit #b0011                       ; SCL: low,low,high,high
                 (if (= value 1) #b1111 #b0000)
                 #b0000))                     ; no expectation

(define (sdr/write-byte b)
  (for-each sdr/write-bit (byte->msb-bits b))
  (sdr/write-t-bit-parity b))                 ; always correct T-bit

(define (sdr/read-bit)
  (raw/drive-bit #b0011                       ; controller clocks SCL
                 #b0000                       ; SDA released
                 #b0000                       ; no expectation
                 #:capture #t))               ; sample, write to ring

(define (sdr/read-byte)
  (let loop ((i 0))
    (when (< i 8) (sdr/read-bit) (loop (+ i 1)))))

;; Layer 1a (raw, opt-in escape hatch)
(define (sdr/write-byte/bad-parity b)
  (for-each sdr/write-bit (byte->msb-bits b))
  (sdr/write-bit (bit-not (parity b))))       ; intentionally wrong
```

### Expectations as first-class

Every observable bus event is expressible as an expectation:

```scheme
(expect-ack)                       ; fail if last T/ACK slot wasn't ACK
(expect-rx-byte #x5C)              ; fail if read byte != 0x5C
(expect-rx-byte/mask #x80 #x80)    ; fail if high bit not set
(expect-ibi-from #x3A (timeout 1ms))
(capture-rx-byte into 'pid-lo)     ; sample + tag in result ring for host
```

Failure policy is per-test:

- `strict` --- first mismatch halts and reports `(pc, expected, actual,
  timestamp)`.
- `collect` --- continue, log every mismatch as a result event, report
  count at end.

### Read primitives

`EMIT_BIT` has a `capture_enable` bit. A read is `EMIT_BIT
drive_en=0 expect_mask=0 capture_en=1`. The sampled bit lands in the
result ring; the host extracts the byte after the run. For
in-program reactions (DAA), the v0.5 register-file + branch-on-mask
opcodes are the path forward; v0 covers compliance scenarios via
post-run analysis.

### Peripheral emulation library

Same target-role plumbing, dressed up:

```scheme
(define (emulate/tmp108 my-addr temperature)
  (loop
    (i3c/target/wait-for-addressed my-addr)
    (case (i3c/target/read-byte)               ; register pointer
      ((#x00) (i3c/target/respond-bytes (temperature->bytes temperature)))
      ((#x01) (i3c/target/respond-bytes default-config))
      (else   (i3c/target/respond-nack)))))
```

`i3c/target/wait-for-addressed` expands to a `WAIT_START`
followed by `SAMPLE_BIT_ON_SCL × 8` (7 address bits + RnW),
a host-compiled address-compare, and a conditional
`DRIVE_BIT_ON_SCL` ACK / NAK; `i3c/target/read-byte` is
`SAMPLE_BIT_ON_SCL × 8` plus a controller-driven T-bit sample;
`i3c/target/respond-bytes` is `DRIVE_BIT_ON_SCL × 9 × N`. Every
target macro is built on the four target-role opcodes; the
engine itself stays protocol-agnostic.

Ship as `i3c-peripheral-emulators` SDK alongside the compliance
suite. Catalog grows organically: TMP108, INA4230, BQ40Z50, the
fuel-gauge and charger catalog we already use in pico-de-gallo, etc.
Lets firmware developers iterate against virtual sensors without
owning the silicon.

## DSL --- Scheme as primary

Confirmed: Scheme/Lisp dialect, compiling to Layer-0 bytecode. YAML
deferred to a possible future declarative-test layer (compiles to
the same bytecode), Rust macro deferred to "tests-in-firmware-repo"
use case (also compiles to the same bytecode).

Implementation options for the compiler:

- **Embed Steel** (Rust-native Scheme) --- fast path to a working
  compiler with a real Scheme runtime.
- **Hand-roll a minimal Scheme** in Rust using `chumsky` for the
  parser --- more work but produces a smaller, dedicated
  compiler with tighter error messages.
- **Use Guile / Chibi as a host-side compiler** --- fastest to
  prototype, ugliest distribution story.

Recommend Steel for the Rust-native story and the alignment with the
pico-de-gallo software stack.

## Error injection model

Compile-time deterministic:

- Compiler takes `(seed, error_ratio)` as inputs.
- PRNG seeded with `seed` runs at compile time.
- For each bit position, compiler decides "inject or not" based on
  PRNG output and ratio threshold.
- Injection mutates the emitted bit pattern (wrong value, wrong T-bit,
  wrong parity, runt SCL, etc.).
- `error_ratio = 0` ⇒ zero PRNG draws cross the threshold ⇒
  zero injections written into the bytecode.

Runtime engine has no randomness. Reproducibility is by construction:
same `(source, seed, ratio)` ⇒ identical bytecode ⇒ identical wire
trace.

## Hardware tiers

| SKU                     | FPGA                     | I3C ceiling | I2C       | HDR-DDR     | Form factor                           | Target price |
|-------------------------|--------------------------|-------------|-----------|-------------|---------------------------------------|--------------|
| **Mole Verde** (pocket) | iCE40 UP5K-SG48          | 12 MHz SDR  | all modes | no          | icebreaker today; USB-stick PCB later | $299         |
| **Mole Rojo** (bench)   | ECP5-45F-CABGA381        | 25 MHz      | all modes | yes         | small custom PCB                      | $599         |
| **Mole Negro** (future) | TBD (CertusPro-NX class) | per spec    | all modes | yes + HDR-T | rack-friendly                         | premium      |

Both tiers share: Scheme SDK, bytecode format, ISA, error-injection
model, peripheral emulation library, transport protocol.

## PHY --- staged

**v0 / v1: none.** 3.3V LVCMOS direct from FPGA I/O bank. Pull-ups
are jumper-selectable footprints on a PMOD adapter (or just two
through-hole resistors). Caps initial DUT support at 3.3V devices ---
acceptable because every dev-board target we care about today
(MCXA, pico-de-gallo, the ODP charger / fuel-gauge boards) is 3.3V.

**v2 PHY daughter card** (additive, drops into bench-tier PCB):

- Discrete level shifters (NLSV2T244 for HDR-DDR edge-rate margin).
- Programmable VIO LDO (TPS62065 + I2C-controlled divider, or
  LP8758).
- Switched-bank pull-ups via analog mux for compliance repeatability.
- Board-to-board mate, not a cable.

## Compliance scope

In scope (any tier):

- Every CCC, DAA edge case, IBI / Hot-Join arbitration, T-bit /
  RDTERM negotiation, error recovery, multi-master arbitration.
- I2C: every mode (SM, FM, FM+, HS), clock stretching, repeated start.
- Error injection: parity, framing, glitches, NACK, bus-busy,
  stretched timing, runt SCL pulses.
- Reproducible scripted test suites: every test is a hashed bytecode
  binary, runnable on any rig.

In scope only at bench tier:

- HDR-DDR controller / target / monitor.

Out of scope (needs lab gear):

- Eye diagrams, edge-rate / jitter measurement, EMI/EMC,
  sub-ns analog characterization, formal MIPI CTS certification.

The rig is a powerful pre-screen and daily-driver tool, not a
substitute for the formal CTS lab.

## Risks and open questions

1. **Scheme compiler maturity**: writing a real compiler with decent
   error messages is non-trivial. Mitigation: start with Steel,
   migrate to a hand-rolled compiler later only if needed.
2. **Engine LUT estimate is rough**: actual synth + place + route on
   UP5K may land higher than 1800 LUTs. Mitigation: build the engine
   incrementally, measure after each block; UP5K has 3400 LUTs of
   headroom over the estimate.
3. **iCE40 UP5K fabric speed**: 60--80 MHz routable in practice;
   12 MHz I3C SDR ceiling is comfortable but HDR-DDR is out of reach
   on this tier. Mitigation: HDR-DDR is bench-tier only by design.
4. **Async clock-stretching semantics**: `WAIT_SCL_RELEASE` is the
   one "real-time, not pre-computed" operation in the engine. Need
   to specify cleanly how it composes with the quarter-bit clock
   (recommend: pauses the divider, resumes from the same quarter).
5. **Result ring overflow on long runs**: 128 KB SPRAM caps single-
   run capture. Mitigation: stream results out over UART concurrently
   with execution, or split long tests into chunks.
6. **PRNG choice for error injection**: must be deterministic across
   compiler versions. Mitigation: pin a specific PRNG (e.g.,
   ChaCha20 with explicit seed) in the compiler spec.

## Phased plan

### v0 --- pocket prototype on icebreaker (no Pico, no flash, no PCB)

- **Phase 0 --- ISA + compiler skeleton**: finalize the 15-opcode ISA
  encoding (controller-role + target-role + control flow). Write a
  host-side bytecode encoder in Rust. Hand-assemble one tiny
  controller test program *and* one tiny target test program.
  Validate the encoding by simulation.
- **Phase 1 --- bit engine in HDL**: SpinalHDL or Amaranth. Implement
  the engine + UART RX/TX + SPRAM controller. Self-test by emitting a
  blinking-LED pattern from a hand-assembled program loaded over UART.
- **Phase 2 --- Layer 1 Scheme SDK seed**: minimal `i2c/`, `i3c/sdr/`,
  `raw/` namespaces. Steel-based compiler. Round-trip
  `cat test.lisp > /dev/ttyUSB0` ⇒ wire activity ⇒ result back
  over the same UART.
- **Phase 3 --- first real test**: drive an MCXA dev board running
  `embassy-mcxa` I3C target. Hand-port one of our existing soak
  scenarios to the Scheme SDK.
- **Phase 4 --- DAA + CCC coverage**: full CCC catalog, DAA edge
  cases. Compliance-style test catalog begins here.
- **Phase 5 --- target role + peripheral emulation**: implement
  `i3c/target/*` in the SDK as macros over the Phase-0 target-role
  opcodes (`WAIT_START`, `WAIT_STOP`, `SAMPLE_BIT_ON_SCL`,
  `DRIVE_BIT_ON_SCL`). Build emulator for one well-known
  peripheral (e.g., TMP108). Validate the rig as a virtual sensor
  against an MCXA controller.
- **Phase 6 --- error injection**: compile-time PRNG-driven
  injection. Reproducibility tests (same seed ⇒ same trace).

### v1 --- Pico polish layer

- **Phase 7 --- RP2350 transport**: postcard-rpc over USB. Pico talks
  SPI/QSPI to the icebreaker. UART path stays as a fallback for dev.
- **Phase 8 --- host tooling**: better DSL editor integration,
  result visualizer, test catalog management. Python bindings for
  scripted regression.

### v2 --- bench tier

- **Phase 9 --- ECP5-45K bring-up board**: small custom PCB. Same
  engine, same SDK, larger fabric.
- **Phase 10 --- HDR-DDR layer**: HDR-DDR Scheme primitives.
  Validate against MCXA HDR-DDR target (when we add HDR-DDR support
  there).
- **Phase 11 --- PHY daughter card**: programmable VIO, switched
  pull-ups, slew control. Unlocks 1.2 V / 1.8 V DUTs.
- **Phase 12 --- compliance test suite**: comprehensive Scheme test
  catalog covering the MIPI compliance checklist. Result-diff CI.

## Productization

### Name

**Mole** --- continues the Latin-cuisine product family started with
pico-de-gallo, with three layered meanings that all reinforce the
product:

- *Mole* the sauce: famously complex, many ingredients carefully
  layered --- mirrors the structure of a compliance SDK.
- *Mole* the animal: burrows underground, sees what is hidden ---
  mirrors what a bus analyzer does.
- *Mole* the SI unit (6.022 × 10²³): scientific gravitas, signals
  "measurement instrument".

Sub-products carry mole varieties --- Verde (green, lighter, everyday),
Rojo (red, deeper, full-featured), Negro (Oaxacan, the darkest and
most complex).

Runner-up names kept in reserve: **Compás** (Spanish for compass +
musical measure --- literally describes a quarter-bit timing engine),
**Berimbau** (Brazilian capoeira instrument, strong rhythm semantics).

### Pricing

| SKU        | Price | Anchor                                | Margin posture                                         |
|------------|-------|---------------------------------------|--------------------------------------------------------|
| Mole Verde | $299  | vs. $30 hobbyist FPGA boards          | 8--12× BoM markup; software/SDK carries the price      |
| Mole Rojo  | $599  | vs. SuperMITT $995 (-40 %)            | 5--7× BoM markup; healthy room for distributor channel |
| Mole Negro | TBD   | premium tier, post-product-market-fit | ---                                                    |

**BoM estimates (small-volume direct)**:

- Mole Verde: UP5K (~$10) + Pico 2 (~$5) + USB-C + LDO + passives +
  small PCB → ~$25--40.
- Mole Rojo: ECP5-45K (~$25) + Pico 2 (~$5) + level shifters / PHY
  (~$15) + USB-C + flash + connectors + LDOs (~$15) + larger PCB
  (~$20) → ~$80--120.

### Positioning vs. MCCI SuperMITT

- **Price**: Mole Rojo $599 vs. SuperMITT $995 --- ~40 % undercut.
- **Software story**: Mole ships an *open*, *diff-able*, *hackable*
  Scheme SDK + peripheral-emulator library + curated compliance test
  catalog. SuperMITT's firmware is closed. This is the defensible
  long-term moat --- hardware can be copied; a living, contributable
  SDK is much harder.
- **Audience**: open-source-friendly, Rust-using, Linux-comfortable
  engineers --- self-serve buyers who do not need (or want)
  sales@mcci.com hand-holding.
- **Distribution**: direct (own webshop) + Crowd Supply launch + small
  distributor footprint later.

### Pricing risks / caveats

1. **Certifications eat early margin**: CE EMC + FCC Part 15B
   unintentional-radiator testing runs ~$5--20k per design depending
   on test house. Budget ~$10k per SKU, ~$20k total fixed cost to
   amortize before first profit dollar.
2. **Support is the hidden tax**: at $299 / $599 in the engineering
   tools market, customers expect real responsiveness. Mitigation:
   excellent docs, excellent Scheme-compiler error messages, public
   issue tracker, community-first support model.
3. **The MCCI $995 includes hand-holding**: their TAM overlaps but
   is not identical to ours. Do not model Mole unit volume off
   SuperMITT sales --- our buyer self-serves, which means smaller
   TAM but higher per-unit margin and lower CAC.
4. **Hobbyist-board comparison risk on Mole Verde**: at $299 a
   developer's instinct is to compare against $30 FPGA dev boards.
   The Mole-Verde value prop must lead with the SDK / docs /
   curated tests / peripheral emulators --- not the silicon. Lean
   on "the cheapest way to put real I3C compliance coverage on
   every developer's desk".

### Volume / break-even sketch

Conservative single-developer-operation assumptions:

- Mole Verde at $299, ~$35 BoM, ~$50 fulfillment+support overhead
  per unit → ~$214 contribution margin.
- Mole Rojo at $599, ~$100 BoM, ~$80 fulfillment+support overhead
  per unit → ~$419 contribution margin.
- Fixed certification + tooling + initial inventory: ~$30--50k.
- Break-even at ~150 Verde *or* ~80 Rojo *or* (more realistic) a
  blended ~100 units across SKUs.

That is a *small* number of units to be cash-positive --- which
matches the "indie engineering tool" profile. The hard part is
demand generation, not unit economics.

### Brand stack

- **pico-de-gallo** --- HIL USB peripheral for firmware automation
  (existing).
- **Mole** --- I3C / I2C compliance and emulation rig (this project).
- Both names live under the same Latin-cuisine family. Future product
  lines can keep the theme open (sauces, condiments, dishes) without
  collision.

## Notes

- The entire pico-de-gallo software investment (postcard-rpc,
  Embassy USB, FFI, Python bindings, HIL test patterns) carries
  over to the v1+ Pico-as-transport role. Nothing duplicated.
- The Scheme SDK + bytecode pair is the durable, hashable, portable
  artifact per-DUT --- a stronger compliance story than what MCCI
  achieves with raw FX3 streaming, because every wire bit is
  explicit in source.
- The peripheral-emulator library is a second-order ecosystem
  asset: "test your firmware against the entire ODP sensor catalog
  without owning the silicon" is a story that sells itself.
- Open-toolchain throughout (yosys + nextpnr-ice40 + icestorm for
  pocket; yosys + nextpnr-ecp5 + prjtrellis for bench). No node-
  locked licenses, no NDAs, reproducible CI.
- v0 needs zero ordered parts. Everything is on your desk today.
