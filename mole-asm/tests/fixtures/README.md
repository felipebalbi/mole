# Phase 3 bring-up assets

The Python reference assembler (`mole-asm.py`), the bundled
`.moleasm` source programs, and the assembled `.molecode` /
`.mole.bin` artifacts they produce. Used to validate the Phase 2
bitstream against real silicon, seed the Rust `mole-asm`
crate's golden-test suite (see `../golden.rs`), and host
partner-bench reproducers (currently `i2c-soak`).

## What's here

| File                          | Notes                                                                                                                  |
|-------------------------------|------------------------------------------------------------------------------------------------------------------------|
| `mole-asm.py`                 | Python reference assembler. The Rust `mole-asm` crate is byte-for-byte diffed against it. Mirrors `Instruction.scala::encode`. |
| `first-light.moleasm`         | Infinite loop, no `HALT`. Scope-sanity program (~98.8 kHz SCL, 0x90 pattern).                                          |
| `tmp108.moleasm`              | Full TMP108 read: START / write addr+ptr / Sr / read 16 bits / STOP / HALT. Triggers 8192-byte result drain.           |
| `i2c-write-one-byte.moleasm`  | ROADMAP §"I2C write-one-byte" example, used as an assembler golden in the self-check suite.                            |
| `loop-counter-demo.moleasm`   | Bounded-loop worked example (LOAD_LOOP / DEC_BRANCH with both LCRs nested), used as the loop-counter golden.           |
| `i2c-soak.moleasm`            | Back-to-back combined-format I²C transactions at 400 kHz against the embassy-imxrt I²C slave on rt685s-evk; partner-bench reproducer for OpenDevicePartnership/embassy-imxrt PR #565. |
| `i3c-write-byte.moleasm`      | Minimal I3C SDR controller-write to a target at 7-bit address 0x4A; OD address phase + PP data byte + T-bit (odd parity); first-light fixture for Step 18 (MCXA dev board as I3C target). |
| `*.molecode`                  | Raw 16-bit LE bytecode (2 bytes × N instructions). Committed (un-ignored under this directory) so Rust goldens run offline. |
| `*.mole.bin`                  | Framed UART payload (len + words + CRC-16/XMODEM). Committed (un-ignored under this directory) so Rust goldens run offline. |

The repo's top-level `.gitignore` ignores `*.molecode` and
`*.mole.bin` globally, but a cascading un-ignore block lets the
artifacts in this directory remain checked in --- they are the
wire-format goldens the Rust assembler is regression-tested
against. Regenerate them at any time:

```sh
python mole-asm.py
```

The default `__main__` behaviour assembles every `*.moleasm` source
next to the script and emits both `.molecode` (raw bytecode) and
`.mole.bin` (framed), and runs the full self-check suite (CRC catalog
+ WIRE_FORMAT §6 frame example + ROADMAP §1118 worked example +
`.equ`/`.dw` round-trip).

## CLI

```text
python mole-asm.py [-h] [--frame] [-o OUT] INPUT.moleasm
```

- Default output: `INPUT.molecode` (raw bytecode, no frame).
- `--frame`: also writes `INPUT.mole.bin` (framed for UART).
- No `INPUT`: runs the bundled batch (every `*.moleasm` source
  listed in `_BUNDLED_PROGRAMS` above) + self-checks.

## moleasm grammar (locked --- AGENTS §3.16, ROADMAP §"moleasm conventions")

- One statement per line. `;` (any count) starts a comment to
  end-of-line.
- Labels: `name:` on its own line or before a statement.
- Mnemonics UPPER CASE; operands lower case (except `X` for
  don't-care `expect`).
- Numeric literals: decimal default, `0x` hex, `0b` binary, leading
  `-` for signed offsets.
- **Named symbols only** for `tx` / `sda` / `scl`
  (`dominant`/`recessive`/`hiz`, plus `dom`/`rec` shorthand), bus
  modes (`i2c`, `i3c-OD`, `i3c-PP`, `hdr-ddr`), and cond codes
  (`ALWAYS`, `MISMATCH`, `NOT_MISMATCH`, `START_SEEN`, `STOP_SEEN`,
  `SDA_LOW`, `SDA_HIGH`, `SCL_HIGH`, `TIMEOUT`, `NOT_TIMEOUT`).
- `LOAD_TIMING` register aliases: `i2c_freq=0`, `i3c_od_freq=1`,
  `i3c_pp_freq=2`, `hdr_ddr_freq=3`.
- Flag-bearing opcodes (EMIT_BIT, EMIT_QUARTER, SAMPLE_BIT_ON_SCL,
  DRIVE_BIT_ON_SCL): `key=value` pairs space-separated.
- HALT, MARK: `status=` / `label=`.
- WAIT_ON, BRANCH_ON, LOAD_TIMING: positional, comma-separated.
- JMP, SET_BUS_MODE, STRETCH_SCL: single positional arg.
- BRANCH_ON / JMP targets: label name OR raw number.
- `expect=X` (default) cannot be combined with `mask=1` (contradictory).
- Reserved-v0.5 mnemonics (FLAG_CLEAR, CAPTURE_RUN) are refused
  --- use `.dw` to inject the raw 16-bit word.

## Directives

- **`.equ NAME, VALUE`** --- name a numeric constant. `VALUE` is a
  literal or a previously-defined `.equ` name. Labels (PC addresses)
  cannot be referenced here. Reserved-name collisions rejected.
- **`.dw EXPR[, EXPR ...]`** --- emit raw 16-bit words at the current
  PC, one per `EXPR`. Each `EXPR` is a literal or `.equ` name. Used
  for v0.5-reserved opcode escapes, hand-crafted wire-format tests,
  or anything without a mnemonic.

## How to send

UART: `/dev/ttyUSBx`, **1 000 000 baud**, 8N1, no flow control.

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

Or for a no-Python first-light send (one-shot, drop and watch):

```sh
stty -F /dev/ttyUSB0 1000000 cs8 -cstopb -parenb \
    crtscts -ixon -ixoff -ixany raw
cat first-light.mole.bin > /dev/ttyUSB0
```

`crtscts` enables the HW flow control Mole speaks on the FT2232H
RTS#/CTS# pair (see `fpga/Mole/BRINGUP.md` §3); `-ixon -ixoff
-ixany` explicitly disables software (XON/XOFF) flow control,
which Mole does not understand and which would otherwise turn
arbitrary frame bytes into flow-control codes.

## What you should see

**`first-light.mole.bin`** (scope on PMOD1A.1=SCL, PMOD1A.2=SDA,
4.7 kΩ pull-ups to 3V3):

- SCL: ~98.8 kHz square wave (100 kHz I2C Standard-mode target,
  derated by ~1 % for the engine's 3-cycle Fetch overhead per bit).
- SDA: holds high through the idle preamble, drops low for the
  START, then clocks out **1001_0000** (0x90, TMP108 7-bit address
  0x48 << 1, R/W=0) in sync with SCL falling edges, repeating
  forever. Press the reset button (or power-cycle) to stop.

To change the bit rate, edit `slow_div` in `first-light.moleasm` (or
`tmp108.moleasm`). The `divider_word` formula at 24 MHz fabric:
`(24_000_000 / bit_hz) / 4 - 1`. The branch back to the top of the
loop targets the body, not the one-time `LOAD_TIMING` +
`SET_BUS_MODE` setup pair.

**`tmp108.mole.bin`**: the full 8192-byte ring drains back over
the UART. First 4 bytes are the `Revision` word
(`major | minor | patch_lo | patch_hi`, little-endian). The next
~20 bytes are capture records (3 slave-ACK bits + 16 read bits)
and the `HALT` record; bytes after `HALT` are stale or zero.

If no TMP108 is wired up, the 3 ACK bits will read `recessive`
(pulled high) instead of `dominant`, and `MISMATCH_FLAG` fires
silently --- the engine does not abort, the read bits still come
back as whatever the bus is doing (typically all 1s).

## Current status

The Rust `mole-asm` crate (at `../../`) ports this Python reference
in full and is the source-of-truth implementation going forward.
This script remains here as the spec it was diffed against, and as
the oracle for the opt-in `MOLEASM_PYTHON_PARITY=1` integration
test in `mole-asm/tests/golden.rs`. Keep the two implementations in
lockstep or retire the Python only after the SDK has been on the
Rust crate for a release cycle.
