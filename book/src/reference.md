# Reference Tables

Single-page lookup for everything moleasm names. Each table is
copy-correct against `docs/MOLE-0.2-SPEC.md` --- the spec is the
source of truth for wire values; this book mirrors them.

## Opcode summary (26 live opcodes)

The opcode field is the 6-bit pair `{group[31:30], sub[29:26]}`.

### WIRE group (`group = 0b00`, 10 opcodes)

| Opcode               | `sub`    | Operand shape                                       |
|----------------------|----------|-----------------------------------------------------|
| `EMIT_BIT_IMM`       | `0b0000` | `tx=... [expect=..] [mask=..] [capture=..]`         |
| `EMIT_BIT_REG`       | `0b0001` | `[expect=..] [mask=..] [capture=..]` (tx from R7)   |
| `EMIT_QUARTER_IMM`   | `0b0010` | `sda=... scl=... [expect/mask/capture]`             |
| `EMIT_QUARTER_REG`   | `0b0011` | `[expect/mask/capture]` (sda/scl from R7)           |
| `EMIT_BYTE_REG`      | `0b0100` | `[expect/mask/capture]` (byte from R7[7:0])         |
| `SAMPLE_BIT_ON_SCL`  | `0b0101` | `[expect/mask/capture]` (target role)               |
| `DRIVE_BIT_ON_SCL`   | `0b0110` | `tx=... [expect/mask/capture]` (target role)        |
| `STRETCH_SCL_IMM`    | `0b0111` | `n_quarters (0..2047)`                              |
| `STRETCH_SCL_REG`    | `0b1000` | (n_quarters from R7[10:0])                          |
| `EMIT_BYTE_IMM`      | `0b1001` | `imm=0..255 [expect/mask/capture]`                  |

### CTRL group (`group = 0b01`, 8 opcodes)

| Opcode         | `sub`    | Operand shape                                       |
|----------------|----------|-----------------------------------------------------|
| `HALT`         | `0b0000` | `[status=0..30]` (31 reserved as STATUS_TRAP)       |
| `BRANCH_ON`    | `0b0001` | `cond, target (label or signed-10 offset)`          |
| `WAIT_ON`      | `0b0010` | `cond, timeout (0..1023)`                           |
| `SET_BUS_MODE` | `0b0011` | `<bus-mode>`                                        |
| `SET_ROLE`     | `0b0100` | `controller|target` (or `0|1`)                      |
| `FLAG_CLEAR`   | `0b0101` | `mask=0b00000..0b11111`                             |
| `MARK`         | `0b0110` | `label=0..0x3FFF`                                   |
| `LOAD_TIMING`  | `0b0111` | `reg=0..7, divider=0..16383`                        |

### DATA group (`group = 0b10`, 8 opcodes)

| Opcode      | `sub`    | Operand shape                                       |
|-------------|----------|-----------------------------------------------------|
| `LOAD_IMM`  | `0b0000` | `Rd, imm14 (0..16383)`                              |
| `MOV`       | `0b0001` | `Rd, Rs`                                            |
| `ADD_IMM`   | `0b0010` | `Rd, Rs, imm8 (-128..127)`                          |
| `DEC`       | `0b0011` | `Rd` (dst = src)                                    |
| `AND_IMM`   | `0b0100` | `Rd, Rs, imm14`                                     |
| `OR_IMM`    | `0b0101` | `Rd, Rs, imm14`                                     |
| `XOR_IMM`   | `0b0110` | `Rd, Rs, imm14`                                     |
| `SHIFT`     | `0b0111` | `Rd, Rs, {left|right}, amount (0..31), [arith]`     |

### LOOP group (`group = 0b11`)

Fully reserved for v0.5. Any LOOP-group instruction traps to
`HALT` with `STATUS_TRAP`.

## Sugar forms

| Sugar               | Expands to                  | Notes                                           |
|---------------------|-----------------------------|-------------------------------------------------|
| `JMP <label>`       | `BRANCH_ON ALWAYS, <label>` | Unconditional jump.                             |
| `LOAD_LOOP n`       | `LOAD_IMM R6, n`            | Prime loop counter in R6 (hard-wired).          |
| `HALT`              | `HALT status=0`             | Default status 0.                               |

## Flag triple (shared across capturing WIRE bearers)

| Field   | Bit   | Description                                                       |
|---------|-------|-------------------------------------------------------------------|
| expect  | `[2]` | Expected SDA value (only meaningful when `mask=1`).               |
| mask    | `[1]` | If 1, write `MISMATCH_FLAG` based on `sampled != expect`.         |
| capture | `[0]` | If 1, push sampled SDA to the result ring and to R7[0].           |

Carried by `EMIT_BIT_IMM`, `EMIT_BIT_REG`, `EMIT_QUARTER_IMM`,
`EMIT_QUARTER_REG`, `EMIT_BYTE_IMM`, `EMIT_BYTE_REG`,
`SAMPLE_BIT_ON_SCL`, and `DRIVE_BIT_ON_SCL`. **Not** carried by
`STRETCH_SCL_IMM` / `STRETCH_SCL_REG`.

## Tx symbols

| Source name | Short form | Wire code | Meaning                                                      |
|-------------|------------|-----------|--------------------------------------------------------------|
| `dominant`  | `dom`      | `0b00`    | Drive low (override pull-up).                                |
| `recessive` | `rec`      | `0b01`    | Release (Hi-Z under OD), or push-pull high (under PP).       |
| `hiz`       | ---        | `0b10`    | Tri-state; never actively drives.                            |
| (reserved)  | ---        | `0b11`    | Held for v0.5 `raw_override`. Rejected by the assembler.     |

## Bus modes

| Source name | Wire value | `recessive` electrical | Class | Active quarter-bit reg |
|-------------|------------|------------------------|-------|------------------------|
| `i2c`       | `0b000`    | Hi-Z                   | OD    | reg 0                  |
| `i3c-od`    | `0b001`    | Hi-Z                   | OD    | reg 2                  |
| `i3c-pp`    | `0b010`    | Push-pull high         | PP    | reg 4                  |
| `hdr-ddr`   | `0b011`    | Push-pull high         | PP    | reg 6                  |

Values `0b100..0b111` are reserved; a runtime `SET_BUS_MODE` with
a reserved value traps to `HALT` with `STATUS_TRAP`.

## Condition codes (`WAIT_ON` and `BRANCH_ON` share the namespace)

| Source name      | Code | True when                                                       |
|------------------|------|-----------------------------------------------------------------|
| `ALWAYS`         | `0`  | Always.                                                         |
| `MISMATCH`       | `1`  | `MISMATCH_FLAG` is set.                                         |
| `NOT_MISMATCH`   | `2`  | `MISMATCH_FLAG` is clear.                                       |
| `START_SEEN`     | `3`  | `START_FLAG` is set (a bus START was observed).                 |
| `STOP_SEEN`      | `4`  | `STOP_FLAG` is set (a bus STOP was observed).                   |
| `SDA_LOW`        | `5`  | SDA sampled low this quarter.                                   |
| `SDA_HIGH`       | `6`  | SDA sampled high this quarter.                                  |
| `SCL_HIGH`       | `7`  | SCL sampled high this quarter.                                  |
| `TIMEOUT`        | `8`  | `TIMEOUT_FLAG` is set (most recent `WAIT_ON` expired).          |
| `NOT_TIMEOUT`    | `9`  | `TIMEOUT_FLAG` is clear.                                        |
| `REG_ZERO`       | `10` | `REG_ZERO_FLAG` is set (most recent ALU op wrote zero).         |
| `NOT_REG_ZERO`   | `11` | `REG_ZERO_FLAG` is clear.                                       |
| (reserved v0.5)  | `12..15` | --- Engine trap `STATUS_TRAP`; assembler error `E-CTRL-001`. |

There is no `SCL_LOW` condition by design (the controller already
knows when it's holding SCL low; targets care about
SCL-released, which is `SCL_HIGH`).

## Timing-register map

| `reg` | BUS_MODE  | Knob                                          |
|-------|-----------|-----------------------------------------------|
| 0     | `i2c`     | Quarter-bit clock divider (`tQB_OD`)          |
| 1     | `i2c`     | Data hold time (`tHD_DAT`)                    |
| 2     | `i3c-OD`  | Quarter-bit clock divider (`tQB_OD`)          |
| 3     | `i3c-OD`  | Data set-up time (`tSU_DAT`)                  |
| 4     | `i3c-PP`  | Quarter-bit clock divider (`tQB_PP`)          |
| 5     | `i3c-PP`  | Data set-up time (`tSU_DAT`)                  |
| 6     | `hdr-ddr` | Quarter-bit clock divider (`tQB_PP`)          |
| 7     | `hdr-ddr` | Data set-up time (`tSU_DAT`)                  |

A bare numeric literal (`0..7`) is the only operand form; there
are no named aliases in v0.2.

## Register file

| Register | Width   | Conventional use                                                       |
|----------|---------|------------------------------------------------------------------------|
| `R0`     | 32 bits | General purpose.                                                       |
| `R1`     | 32 bits | General purpose.                                                       |
| `R2`     | 32 bits | General purpose.                                                       |
| `R3`     | 32 bits | General purpose.                                                       |
| `R4`     | 32 bits | General purpose.                                                       |
| `R5`     | 32 bits | General purpose (often outer loop counter; see [Bounded loops](./bounded-loops.md)). |
| `R6`     | 32 bits | Conventional loop counter (`LOAD_LOOP n` sugar targets it).            |
| `R7`     | 32 bits | Conventional capture / byte register: `EMIT_BIT_REG` reads `R7[1:0]`, `EMIT_BYTE_REG` reads `R7[7:0]`, captures write to `R7[0]` with the rest zeroed. |

The register conventions are not enforced by the engine — `R0`
is just as usable as `R7` for arbitrary data — but the SDK
macros and bundled fixtures follow them.

## Sticky flags

| Flag              | Set by                                                                                          |
|-------------------|-------------------------------------------------------------------------------------------------|
| `MISMATCH_FLAG`   | Capturing opcode with `mask=1` where the read sample disagrees with `expect`.                   |
| `TIMEOUT_FLAG`    | `WAIT_ON` that exhausts its timeout; controller-role `EMIT_BIT_*` stretch-timeout.              |
| `START_FLAG`      | Bus-level I2C / I3C START observed (set asynchronously by the engine's observer).               |
| `STOP_FLAG`       | Bus-level STOP observed.                                                                        |
| `REG_ZERO_FLAG`   | Any DATA-group opcode that wrote a zero result.                                                 |

All five flags are cleared by the next opcode that writes them,
or explicitly by `FLAG_CLEAR mask=...` (5-bit per-flag mask).
They are also cleared by the per-program reset on the
`io.engineStart` rising edge (back-to-back program runs see a
fresh flag set without an FPGA power cycle).

## Directives

| Directive       | Operands                | PC advance              | Notes                                              |
|-----------------|-------------------------|-------------------------|----------------------------------------------------|
| `.equ NAME, V`  | name + literal-or-equate| 0                       | Binds name to value; cannot forward-reference.     |
| `.dw V[,V...]`  | 1+ literal-or-equate    | `len(operands)`         | Emits raw 32-bit words; rejects label operands.    |

## Library entry points

| Function                          | Returns                  | Notes                                       |
|-----------------------------------|--------------------------|---------------------------------------------|
| `mole_asm::assemble(src, fn)`     | `Result<Vec<u32>>`       | Assemble; 32-bit words in PC order.         |
| `mole_asm::assemble_to_frame`     | `Result<Vec<u8>>`        | Frame the assembled program for UART.       |
| `mole_asm::frame::pack_bytecode`  | `Vec<u8>`                | Pack words little-endian (no frame).        |
| `mole_asm::frame::build_frame`    | `Result<Vec<u8>>`        | `MAGIC + LEN + body + CRC`. `1..=8192` words. |
| `mole_asm::crc::xmodem`           | `u16`                    | CRC-16/XMODEM, poly `0x1021`.               |

## Error kinds

| `Kind`       | When                                                                                       |
|--------------|--------------------------------------------------------------------------------------------|
| `Lex`        | Unknown / mis-cased / reserved-v0.5 mnemonic; unknown directive; malformed label or directive. |
| `Symbol`     | Duplicate label, duplicate `.equ`, undefined name, label-vs-equate confusion, reserved-name collision. |
| `Range`      | Numeric overflow; `BRANCH_ON` offset out of `-512..+511`; `WAIT_ON` timeout out of `0..1023`; program exceeds 8192-word budget; `.dw` value > 0xFFFF_FFFF. |
| `Operand`    | Missing required key; unknown operand key; duplicate key; `expect=X` with `mask=1`; arity mismatch. |

`AsmError::FrameTooLarge` sits outside this taxonomy because the
frame builder is also reachable without source code.

For the full error catalog (E-LEX-NNN, E-RNG-NNN, etc.) see
`docs/MOLE-0.2-SPEC.md` §13 or the [Errors](./errors.md) chapter.

That's the entire moleasm vocabulary on one page. Bookmark this
chapter; the Glossary is next.
