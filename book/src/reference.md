# Reference Tables

Single-page lookup for everything moleasm names. Each table is
copy-correct against `fpga/Mole/src/hw/Instruction.scala` --- the
engine is the source of truth for wire values; this book mirrors
them.

## Opcode summary

| Opcode              | OP code   | Operand shape                              | What it does                                              |
|---------------------|-----------|--------------------------------------------|-----------------------------------------------------------|
| `HALT`              | `0x00`    | `[status=0..15]`                           | Stop engine; record 4-bit status.                         |
| `EMIT_BIT`          | `0x01`    | `tx=... [expect=..] [mask=..] [capture=..]`| Emit one bit on the bus (controller role).                |
| `EMIT_QUARTER`      | `0x02`    | `sda=... scl=... [expect/mask/capture]`    | Drive one quarter-bit edge with independent SDA/SCL.      |
| `STRETCH_SCL`       | `0x03`    | `n_quarters (0..2047)`                     | Hold current state for `n` extra quarters.                |
| `WAIT_ON`           | `0x04`    | `cond, timeout (0..127)`                   | Wait for cond, or set TIMEOUT_FLAG.                       |
| `BRANCH_ON`         | `0x05`    | `cond, target (label or signed-7 offset)`  | PC-relative branch on cond.                               |
| `JMP`               | `0x06`    | `addr (label or 0..2047)`                  | Absolute jump.                                            |
| `SET_BUS_MODE`      | `0x07`    | `<bus-mode>`                               | Switch the active bus-mode register.                      |
| `LOAD_TIMING`       | `0x08`    | `reg, divider (0..511)`                    | Write 9-bit divider into a timing register.               |
| `MARK`              | `0x09`    | `label=0..255`                             | Push an 8-bit marker into the result ring.                |
| `SAMPLE_BIT_ON_SCL` | `0x0A`    | `[expect/mask/capture]`                    | Target-role: sample SDA, slaved to controller SCL.        |
| `DRIVE_BIT_ON_SCL`  | `0x0B`    | `tx=... [expect/mask/capture]`             | Target-role: drive SDA while slaved to controller SCL.    |
| `LOAD_LOOP`         | `0x0C`    | `<lcr0|lcr1>, imm (0..255)`                | Prime loop counter LCR[reg] with 8-bit immediate.         |
| `DEC_BRANCH`        | `0x0D`    | `<lcr0|lcr1>, target (label or signed-8)`  | Decrement LCR[reg]; PC-relative branch if non-zero.       |
| `SET_ROLE`          | `0x10`    | `controller|target` (or `0|1`)             | Select engine role at runtime (default = `MoleConfig.role`).|
| (reserved v0.5)     | `0x0E..0x0F`, `0x11..0x1F` | --- (rejected, reach via `.dw`) | Reserved: `FLAG_CLEAR`, `CAPTURE_RUN`, etc.       |

## Instruction word layout

All opcodes share these field positions:

| Field        | Bits   | Notes                                                            |
|--------------|--------|------------------------------------------------------------------|
| `opcode`     | 15:11  | 5-bit opcode (15 used + 17 reserved-v0.5).                       |
| `expect`     | 2      | Present on `EMIT_BIT`, `EMIT_QUARTER`, `SAMPLE_BIT_ON_SCL`, `DRIVE_BIT_ON_SCL`. |
| `mask`       | 1      | Same opcodes as `expect`.                                        |
| `capture`    | 0      | Same opcodes as `expect`.                                        |

Per-opcode field positions are detailed in the [Opcodes](./opcodes.md)
chapter; the canonical bit layout lives in `Instruction.scala::encode`.

## Tx symbols

| Source name | Short form | Wire code | Meaning                                                      |
|-------------|-----------|-----------|--------------------------------------------------------------|
| `dominant`  | `dom`     | `0b00`    | Drive low (override pull-up).                                |
| `recessive` | `rec`     | `0b01`    | Release (Hi-Z under OD), or push-pull high (under PP).        |
| `hiz`       | ---       | `0b10`    | Tri-state; never actively drives.                            |
| (reserved)  | ---       | `0b11`    | Held for v0.5 `raw_override`. Rejected by the assembler.     |

## Bus modes

| Source name | Wire value | `recessive` electrical | Active timing register |
|-------------|-----------|------------------------|------------------------|
| `i2c`       | `0b000`   | Hi-Z                   | `i2c_freq` (reg 0)     |
| `i3c-od`    | `0b001`   | Hi-Z                   | `i3c_od_freq` (reg 1)  |
| `i3c-pp`    | `0b110`   | Push-pull high         | `i3c_pp_freq` (reg 2)  |
| `hdr-ddr`   | `0b111`   | Push-pull high         | `hdr_ddr_freq` (reg 3) |

Bus-mode wire values are intentionally non-sequential: bit
`[2]` distinguishes PP from OD, bits `[1:0]` select the timing
register.

## Condition codes (`WAIT_ON` and `BRANCH_ON` share the namespace)

| Source name      | Code   | True when                                                       |
|------------------|--------|-----------------------------------------------------------------|
| `ALWAYS`         | `0x0`  | Always.                                                         |
| `MISMATCH`       | `0x1`  | `MISMATCH_FLAG` is set.                                         |
| `NOT_MISMATCH`   | `0x2`  | `MISMATCH_FLAG` is clear.                                       |
| `START_SEEN`     | `0x3`  | `START_FLAG` is set (a bus START was observed).                 |
| `STOP_SEEN`      | `0x4`  | `STOP_FLAG` is set (a bus STOP was observed).                   |
| `SDA_LOW`        | `0x5`  | SDA sampled low this quarter.                                   |
| `SDA_HIGH`       | `0x6`  | SDA sampled high this quarter.                                  |
| `SCL_HIGH`       | `0x7`  | SCL sampled high this quarter.                                  |
| `TIMEOUT`        | `0x8`  | `TIMEOUT_FLAG` is set (most recent `WAIT_ON` expired).          |
| `NOT_TIMEOUT`    | `0x9`  | `TIMEOUT_FLAG` is clear.                                        |
| (reserved v0.5)  | `0xA..0xF` | --- Reserved for the v0.5 cond-code expansion.              |

## Timing-register aliases

| Alias            | Reg | Active under bus mode |
|------------------|-----|-----------------------|
| `i2c_freq`       | 0   | `i2c`                 |
| `i3c_od_freq`    | 1   | `i3c-od`              |
| `i3c_pp_freq`    | 2   | `i3c-pp`              |
| `hdr_ddr_freq`   | 3   | `hdr-ddr`             |

A bare numeric literal (`0..3`) is also accepted in place of the
alias.

## Loop registers

| Alias  | Reg | Width  | Used by                 |
|--------|-----|--------|-------------------------|
| `lcr0` | 0   | 8 bits | `LOAD_LOOP`, `DEC_BRANCH` |
| `lcr1` | 1   | 8 bits | `LOAD_LOOP`, `DEC_BRANCH` |

Two independent 8-bit counters. A bare numeric literal (`0` or
`1`) is also accepted. See the [Bounded loops](./bounded-loops.md)
chapter for the canonical idioms.

## Sticky flags (engine state, set by opcodes, observed by `BRANCH_ON`)

| Flag              | Set by                                                                                 |
|-------------------|----------------------------------------------------------------------------------------|
| `MISMATCH_FLAG`   | Capturing opcode with `mask=1` where the read sample disagrees with `expect`.          |
| `TIMEOUT_FLAG`    | `WAIT_ON` that exhausts its timeout.                                                   |
| `START_FLAG`      | Bus-level I2C / I3C START observed (set asynchronously by the engine's monitor).       |
| `STOP_FLAG`       | Bus-level STOP observed.                                                               |

All flags are write-once until overwritten by the next opcode that
writes them (or until a future v0.5 `FLAG_CLEAR`).

## Directives

| Directive       | Operands                | PC advance              | Notes                                              |
|-----------------|-------------------------|-------------------------|----------------------------------------------------|
| `.equ NAME, V`  | name + literal-or-equate| 0                       | Binds name to value; cannot forward-reference.     |
| `.dw V[,V...]`  | 1+ literal-or-equate    | `len(operands)`         | Emits raw 16-bit words; rejects label operands.    |

## Library entry points

| Function                       | Returns                  | Notes                                       |
|-------------------------------|--------------------------|---------------------------------------------|
| `mole_asm::assemble(src, fn)` | `Result<Vec<u16>>`       | Assemble; words in PC order.                |
| `mole_asm::assemble_to_frame` | `Result<Vec<u8>>`        | Frame the assembled program for UART.       |
| `mole_asm::frame::pack_bytecode` | `Vec<u8>`              | Pack words little-endian (no frame).        |
| `mole_asm::frame::build_frame` | `Result<Vec<u8>>`       | `len`, words, CRC. `1..=2048` words.        |
| `mole_asm::crc::xmodem`        | `u16`                    | CRC-16/XMODEM, poly `0x1021`.               |

## Error kinds

| `Kind`       | When                                                                                       |
|--------------|--------------------------------------------------------------------------------------------|
| `Lex`        | Unknown / mis-cased / reserved-v0.5 mnemonic; unknown directive; malformed label or directive. |
| `Symbol`     | Duplicate label, duplicate `.equ`, undefined name, label-vs-equate confusion, reserved-name collision. |
| `Range`      | Numeric overflow; `BRANCH_ON` offset out of `-64..+63` (or `DEC_BRANCH` out of `-128..+127`); program exceeds 2048-word budget; `.dw` value > 0xFFFF. |
| `Operand`    | Missing required key; unknown operand key; duplicate key; `expect=X` with `mask=1`; arity mismatch. |

`AsmError::FrameTooLarge` sits outside this taxonomy because the
frame builder is also reachable without source code.

That's the entire moleasm vocabulary on four pages. Bookmark this
chapter; the Glossary is next.
