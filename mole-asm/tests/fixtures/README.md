# mole-asm v0.2 fixtures

The Python reference assembler (`mole-asm.py`), the bundled
`.moleasm` source programs, and the assembled `.molecode` /
`.mole.bin` artifacts they produce. These fixtures serve three
roles:

1. **Golden tests for the Rust `mole-asm` crate** (`../golden.rs`):
   for each fixture, the Rust assembler's `assemble` /
   `assemble_to_frame` output must equal the committed
   `.molecode` / `.mole.bin` bytes byte-for-byte.
2. **Python ↔ Rust cross-encoder check**: every fixture is
   regenerable from either implementation. Both MUST produce
   identical bytes; the committed bytes are the single source
   of truth.
3. **Partner-bench reproducers and bring-up programs** for live
   hardware (currently `i2c-soak` and `i3c-write-byte`).

## What's here

The fixture set splits into two groups: **6 v0-era programs**
regenerated against the v0.2 encoder during Phase B4, and
**5 adversarial probes** that lock specific spec invariants.

### v0 programs (regenerated for v0.2)

| File                          | Notes                                                                                                                  |
|-------------------------------|------------------------------------------------------------------------------------------------------------------------|
| `first-light.moleasm`         | Infinite loop, no `HALT`. Scope-sanity program (~98.8 kHz SCL, 0x90 pattern).                                          |
| `tmp108.moleasm`              | Full TMP108 read: START / write addr+ptr / Sr / read 16 bits / STOP / HALT. Triggers 8192-byte result drain.           |
| `i2c-write-one-byte.moleasm`  | ROADMAP §"I2C write-one-byte" worked example; locked as a published-doc golden.                                        |
| `loop-counter-demo.moleasm`   | Bounded-loop worked example using v0.2 `LOAD_LOOP` (R6 sugar) + `LOAD_IMM R5` (outer) + `DEC R{5,6}` + `BRANCH_ON {NOT_}REG_ZERO`. |
| `i2c-soak.moleasm`            | Back-to-back combined-format I²C transactions at 400 kHz against the embassy-imxrt I²C slave on rt685s-evk; partner-bench reproducer for OpenDevicePartnership/embassy-imxrt PR #565. |
| `i3c-write-byte.moleasm`      | Minimal I3C SDR controller-write to a target at 7-bit address 0x4A; OD address phase + PP data byte + T-bit (odd parity); first-light fixture for Step 18 (MCXA dev board as I3C target). |

### Phase B4 adversarial probes

Probes are small, focused programs whose only job is to lock a
single spec invariant in bytecode. Each probe's header comment
cites the exact spec sections it exercises.

| File                                  | Locks                                                                                                                  |
|---------------------------------------|------------------------------------------------------------------------------------------------------------------------|
| `pipeline-hazard-emit-dec.moleasm`    | §14 pipeline-hazard regression: DEC + BRANCH_ON pair is forwarding-immune; LINT-001 must NOT fire on WIRE/CTRL/MARK intermediaries (only DATA opcodes trigger the lint). |
| `emit-byte-throughput-100.moleasm`    | §5.5 + §10 size arithmetic: 100 fire-and-forget EMIT_BYTE words ⇒ 420 .molecode bytes, 424 .mole.bin bytes. Framing-overhead drift tripwire. |
| `duty-cycle-warp.moleasm`             | §3 tx_symbol vocabulary × §3.13 PP-class target-role gate: every legal (sda, scl) symbol pair for every (bus_mode, role) the assembler accepts. Omitted illegal rows live in `../adversarial.rs`. |
| `watchdog-trip.moleasm`               | §5.12 WAIT_ON timeout path + §7 TIMEOUT_FLAG sticky semantics + §6 cond-code 8 BRANCH_ON dispatch (3 scenarios: finite-timeout-expired ×2, ALWAYS-no-timeout ×1).         |
| `dec-branch-loop-deep.moleasm`        | §5.21 + §5.19 + §6 + §5.11 nested-loop convention: R5 outer (raw `LOAD_IMM`) + R6 inner (`LOAD_LOOP` sugar), 255×255×8 = 520,200 inner pulses in 18 body words. |

### Per-fixture artifacts

For every `<name>.moleasm` source, two committed artifacts:

| File                  | Notes                                                                                                       |
|-----------------------|-------------------------------------------------------------------------------------------------------------|
| `<name>.molecode`     | Raw SPRAM image: 2-word preamble + body, packed 32-bit little-endian. No UART envelope.                     |
| `<name>.mole.bin`     | UART frame: 2-byte LE length prefix + `.molecode` bytes + 2-byte CRC-16/XMODEM trailer.                     |

The repo's top-level `.gitignore` ignores `*.molecode` and
`*.mole.bin` globally; a cascading un-ignore block in this
directory's `.gitignore` keeps these fixture artifacts checked in
so the Rust goldens run offline.

## Implementations: keep them in lock-step

| File                          | Notes                                                                                                                  |
|-------------------------------|------------------------------------------------------------------------------------------------------------------------|
| `mole-asm.py`                 | Python reference oracle for the v0.2 assembler and encoder. Self-check suite (47 checks) covers the CRC catalog, frame layout, §12 grammar, all 25 opcodes, and the worked examples from `docs/MOLE-0.2-SPEC.md` §12.4. |

The Rust crate at `../../` is the production implementation; the
Python script is the byte-for-byte oracle it is regression-tested
against. Both encoders MUST produce identical bytes for every
fixture committed in this directory. If they diverge:

1. Diff `python3 mole-asm.py <fixture>.moleasm` vs.
   `cargo run -p mole-asm-cli -- assemble <fixture>.moleasm`.
2. The bug is in whichever implementation disagrees with
   `docs/MOLE-0.2-SPEC.md` --- consult the spec first, not the
   other implementation.
3. STOP and report rather than papering over the diff by
   updating the committed bytes.

## Regenerating a fixture

A fixture's committed bytes are updated **only** when the source
`.moleasm` changes deliberately. The procedure is:

```sh
# From the repo root:

# 1. Regenerate using the Python oracle.
python3 mole-asm/tests/fixtures/mole-asm.py --frame \
    mole-asm/tests/fixtures/<name>.moleasm

# 2. Cross-check with the Rust assembler --- the diffs MUST be
#    empty. Any non-empty diff is a bug in one of the two
#    implementations; do NOT commit a fixture where they
#    disagree.
cargo run -p mole-asm-cli -- assemble --frame \
    -o /tmp/<name>.rust.molecode \
    --frame-output /tmp/<name>.rust.mole.bin \
    mole-asm/tests/fixtures/<name>.moleasm

diff mole-asm/tests/fixtures/<name>.molecode \
     /tmp/<name>.rust.molecode
diff mole-asm/tests/fixtures/<name>.mole.bin \
     /tmp/<name>.rust.mole.bin

# 3. Confirm the golden tests pass with the new bytes.
cargo test -p mole-asm --test golden
```

The Python `mole-asm.py` has no batch-regeneration mode --- you
invoke it once per source file. The Rust CLI is similarly
per-file. This is deliberate: regenerating in batch hides which
fixtures actually changed, and the byte-level diffs are the
review surface.

## Python CLI

```text
python3 mole-asm.py [-h] [--frame] [-o OUT] [INPUT.moleasm]
```

- No `INPUT`: runs the 47-check self-test suite.
- With `INPUT`: writes `INPUT.molecode` (raw bytecode); add
  `--frame` to additionally write `INPUT.mole.bin` (UART frame).
- `-o OUT` overrides the `.molecode` output path; the
  `--frame` output is always `<INPUT>.mole.bin`.

## moleasm grammar (locked --- AGENTS §3.16, `docs/MOLE-0.2-SPEC.md` §12)

- One statement per line. `;` starts a comment to end-of-line.
- Labels: `name:` on its own line or before a statement.
  Identifiers: `[A-Za-z_][A-Za-z0-9_]*`.
- Mnemonics case-insensitive; canonical form upper-case.
- Numeric literals: decimal default, `0x` hex, `0b` binary,
  leading `-` for signed offsets.
- **Symbolic vocabulary** for the per-bit / per-quarter drive
  field (`tx=` / `sda=` / `scl=`): `dominant`, `recessive`, `hiz`
  (short forms `dom`, `rec` accepted). The 4th encoding (`0b11`)
  is reserved and requires the `(use-raw-primitives)` pragma.
- **Bus-mode symbols** for `SET_BUS_MODE`: `i2c`, `i3c-OD`,
  `i3c-PP`, `hdr-ddr` (case-insensitive; wire values 0..3 ---
  NOT v0's 0, 1, 6, 7).
- **Condition codes** for `BRANCH_ON` / `WAIT_ON` (§6): `ALWAYS`,
  `MISMATCH`, `NOT_MISMATCH`, `START_SEEN`, `STOP_SEEN`,
  `SDA_LOW`, `SDA_HIGH`, `SCL_HIGH`, `TIMEOUT`, `NOT_TIMEOUT`,
  `REG_ZERO`, `NOT_REG_ZERO`. Codes 12..15 reserved
  (E-CTRL-001).
- `LOAD_TIMING` is positional `reg=, divider=`. v0-era register
  aliases (`i2c_freq`, `i3c_od_freq`, etc.) are RETIRED; use
  `reg=0` for i2c, `reg=2` for i3c-OD, `reg=4` for i3c-PP,
  `reg=6` for hdr-ddr (§5.17).
- Flag-bearing opcodes (`EMIT_BIT_IMM`, `EMIT_QUARTER_IMM`,
  `EMIT_BYTE`, `SAMPLE_BIT_ON_SCL`, `DRIVE_BIT_ON_SCL`):
  `key=value` pairs, space-separated. Defaults `expect=X`,
  `mask=0`, `capture=0` are omitted when unset.
- `HALT status=N` / `MARK label=N`.
- `BRANCH_ON` / `JMP` targets: label name OR raw signed offset
  (10-bit, ±511 per §5.11). `WAIT_ON timeout`: unsigned 10-bit
  (0..1023; 0 = infinite per §5.12).
- Sugar: `JMP <label>` = `BRANCH_ON ALWAYS, <label>` (§6);
  `LOAD_LOOP n` = `LOAD_IMM R6, n` (§5.18). v0's fused
  `DEC_BRANCH` is RETIRED; use `DEC Rx` + `BRANCH_ON
  {REG_ZERO|NOT_REG_ZERO}, label` (§14 hazard-free pair).
- `expect=X` (don't-care) cannot be combined with `mask=1`
  (E-OP-006: would mean "check against unspecified").
- `EMIT_BYTE` with `mask=1` MUST be followed by `BRANCH_ON
  MISMATCH, …` or `FLAG_CLEAR` with mask bit 0 set
  (E-WIRE-003, §5.5). `mask=0` (fire-and-forget) is exempt.

## Directives

- **`.equ NAME, VALUE`** --- name a numeric constant. `VALUE` is
  a literal or a previously-defined `.equ` name. Labels (PC
  addresses) cannot be referenced here. Reserved-name collisions
  rejected (E-SYM-002).
- **`.dw EXPR[, EXPR ...]`** --- emit raw 32-bit words at the
  current PC, one per `EXPR`. Each `EXPR` is a literal or
  `.equ` name. Used for v0.5-reserved opcode escapes,
  hand-crafted wire-format tests, or anything without a
  mnemonic. Subject to MAX_PROGRAM_WORDS = 8192 (E-FRM-001).

## How to send

UART: `/dev/ttyUSBx`, **1 000 000 baud**, 8N1, no software flow
control. The FT2232H RTS#/CTS# pair carries HW flow control
(see `fpga/Mole/BRINGUP.md` §3).

```python
import serial
port = serial.Serial("/dev/ttyUSB0", 1_000_000, timeout=2)

with open("first-light.mole.bin", "rb") as f:
    port.write(f.read())
# Engine auto-starts on the CRC-valid frame.
# Press the iCEbreaker reset button to stop the forever loop.
```

For `tmp108.mole.bin`, after `port.write(...)` read back the full
8192-byte ring:

```python
data = port.read(8192)
print(f"got {len(data)} bytes; revision = {data[:4].hex()}")
```

For a no-Python first-light send (one-shot, drop and watch):

```sh
stty -F /dev/ttyUSB0 1000000 cs8 -cstopb -parenb \
    crtscts -ixon -ixoff -ixany raw
cat first-light.mole.bin > /dev/ttyUSB0
```

`crtscts` enables HW flow control on RTS#/CTS#; `-ixon -ixoff
-ixany` explicitly disables software (XON/XOFF) flow control,
which Mole does not understand and which would otherwise turn
arbitrary frame bytes into flow-control codes.

## What you should see

**`first-light.mole.bin`** (scope on PMOD1A.1=SCL, PMOD1A.2=SDA,
4.7 kΩ pull-ups to 3V3):

- SCL: ~98.8 kHz square wave (100 kHz I2C Standard-mode target,
  derated by ~1 % for the engine's 3-cycle Fetch overhead per bit).
- SDA: holds high through the idle preamble, drops low for the
  START, then clocks out **1001_0000** (0x90, TMP108 7-bit
  address 0x48 << 1, R/W=0) in sync with SCL falling edges,
  repeating forever. Press the reset button (or power-cycle)
  to stop.

To change the bit rate, edit `slow_div` in `first-light.moleasm`
(or `tmp108.moleasm`). The divider formula at 24 MHz fabric:
`divider = (24_000_000 / bit_hz) / 4 - 1`. The branch back to
the top of the loop targets the body, not the one-time
`LOAD_TIMING` + `SET_BUS_MODE` setup pair.

**`tmp108.mole.bin`**: the full 8192-byte ring drains back over
the UART. First 4 bytes are the `Revision` word
(`major | minor | patch_lo | patch_hi`, little-endian). The next
~20 bytes are capture records (3 slave-ACK bits + 16 read bits)
and the `HALT` record; bytes after `HALT` are stale or zero.

If no TMP108 is wired up, the 3 ACK bits will read `recessive`
(pulled high) instead of `dominant`, and `MISMATCH_FLAG` fires
silently --- the engine does not abort, the read bits still
come back as whatever the bus is doing (typically all 1s).
