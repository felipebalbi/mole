# Opcodes

The Mole v0.2 engine implements **26 live opcodes** across three
groups (a fourth group, LOOP, is fully reserved for v0.5). Every
opcode encodes as a fixed-width **32-bit word**. The opcode field
is the 6-bit pair `{group[31:30], sub[29:26]}` — two bits to pick
the group, four bits to pick the sub-opcode inside it. The
remaining `[25:0]` bits carry operands whose layout depends on the
opcode.

This chapter is the user-level walkthrough. The normative
encoding lives in `docs/MOLE-0.2-SPEC.md` §4 (group table) and
§5 (per-opcode field layouts); the assembler implements those
exactly.

## Group structure

| Group  | `[31:30]` | Live opcodes | Purpose                          |
|--------|-----------|--------------|----------------------------------|
| WIRE   | `0b00`    | 10           | Emit / sample / stretch on SDA+SCL|
| CTRL   | `0b01`    | 8            | Branch, wait, configure          |
| DATA   | `0b10`    | 8            | ALU + 32-bit register file       |
| LOOP   | `0b11`    | 0 (reserved) | Held for v0.5                    |

Within each group the `sub` field picks the specific opcode
(0..15). Reserved sub-slots trap to `HALT` with `STATUS_TRAP`
(`0x1F`).

## Flag triple convention

The eight WIRE-bearer opcodes that *can* sample SDA share a fixed
flag triple at bits `[2:0]`:

| Field   | Bit   | Description                                                     |
|---------|-------|-----------------------------------------------------------------|
| expect  | `[2]` | Expected SDA value (only meaningful when `mask=1`).             |
| mask    | `[1]` | If 1, set `MISMATCH_FLAG` on `sampled != expect`.               |
| capture | `[0]` | If 1, push the sampled SDA bit to the result ring and to R7[0]. |

The eight bearers are `EMIT_BIT_IMM`, `EMIT_BIT_REG`,
`EMIT_QUARTER_IMM`, `EMIT_QUARTER_REG`, `EMIT_BYTE_IMM`,
`EMIT_BYTE_REG`, `SAMPLE_BIT_ON_SCL`, and `DRIVE_BIT_ON_SCL`. The
stretch pair (`STRETCH_SCL_IMM`, `STRETCH_SCL_REG`) does **not**
carry flags.

# WIRE group (10 opcodes)

WIRE opcodes drive (or sample) bits on SDA / SCL. All ten are
controller-role-only except `SAMPLE_BIT_ON_SCL` and
`DRIVE_BIT_ON_SCL`, which only run in target role.

## `EMIT_BIT_IMM` / `EMIT_BIT_REG`

Emit one full bit-time on the bus using the canonical SCL pattern
(low / low / high / high). SDA is held at a `tx_symbol` for the
whole bit cell. The flag triple, if set, samples SDA at the
Q2→Q3 transition (centre of the high half).

| Variant       | `sub`   | tx source                       |
|---------------|---------|---------------------------------|
| `EMIT_BIT_IMM`| `0b0000`| Instruction word `[4:3]`        |
| `EMIT_BIT_REG`| `0b0001`| `R7[1:0]` at runtime            |

```moleasm
EMIT_BIT_IMM tx=dominant                          ; emit a '0' bit
EMIT_BIT_IMM tx=recessive                         ; emit a '1' bit
EMIT_BIT_IMM tx=hiz   expect=0 mask=1 capture=1   ; ACK slot
EMIT_BIT_IMM tx=hiz   capture=1                   ; sample without checking

LOAD_IMM R7, 0b01
EMIT_BIT_REG                                      ; emits 'recessive' from R7[1:0]
```

Reserved tx code `0b11` is rejected at parse time for `_IMM`; for
`_REG` a runtime `R7[1:0] = 0b11` is an engine trap
(`STATUS_TRAP`).

The Q1→Q2 boundary is the one place in controller-role
`EMIT_BIT_*` that consults the wire: the stretch-aware guard
pauses the engine until SCL rises (slave released) or
`stretchTimeoutCycles` elapses (PP-class slave-stretch is treated
as a compliance violation and immediately HALTs with
`STATUS_TRAP`).

## `EMIT_QUARTER_IMM` / `EMIT_QUARTER_REG`

Emit exactly one quarter-bit-time with **independent control of
SDA and SCL**. This is the surgical tool: where `EMIT_BIT_*`
walks a canonical bit pattern, `EMIT_QUARTER_*` lets you assemble
any waveform edge by edge — START / STOP, repeated-start, SCL
glitches, you name it.

| Variant            | `sub`    | sda source          | scl source          |
|--------------------|----------|---------------------|---------------------|
| `EMIT_QUARTER_IMM` | `0b0010` | Instruction `[4:3]` | Instruction `[6:5]` |
| `EMIT_QUARTER_REG` | `0b0011` | `R7[1:0]`           | `R7[3:2]`           |

```moleasm
; I2C START (canonical: 4 idle + 2 SDA-falls + 2 SCL-down)
EMIT_QUARTER_IMM sda=recessive scl=recessive
EMIT_QUARTER_IMM sda=recessive scl=recessive
EMIT_QUARTER_IMM sda=recessive scl=recessive
EMIT_QUARTER_IMM sda=recessive scl=recessive
EMIT_QUARTER_IMM sda=dominant  scl=recessive
EMIT_QUARTER_IMM sda=dominant  scl=recessive
EMIT_QUARTER_IMM sda=dominant  scl=dominant
EMIT_QUARTER_IMM sda=dominant  scl=dominant

; I2C STOP (canonical: 2 SCL-up + 2 SDA-up)
EMIT_QUARTER_IMM sda=dominant  scl=dominant
EMIT_QUARTER_IMM sda=dominant  scl=dominant
EMIT_QUARTER_IMM sda=dominant  scl=recessive
EMIT_QUARTER_IMM sda=dominant  scl=recessive
EMIT_QUARTER_IMM sda=recessive scl=recessive
EMIT_QUARTER_IMM sda=recessive scl=recessive
```

In target role under a PP-class bus mode (`i3c-pp`, `hdr-ddr`)
`EMIT_QUARTER_*` with `scl=recessive` is rejected by the SDK
(would request a PP-drive of SCL while in follower role) and
ignored by the engine as defence-in-depth. `scl=dominant`
(pull-low: stretch, fuzz) and `scl=hiz` (release) are always
legal.

## `EMIT_BYTE_IMM` / `EMIT_BYTE_REG`

Emit eight data bits MSB-first followed by a ninth `hiz`-SDA ACK
slot. The flag triple applies to the ACK slot (so
`expect=0 mask=1 capture=1` records and validates the slave's
ACK in one instruction). Each of the eight data bits uses the
canonical SCL pattern.

| Variant         | `sub`    | byte source                  |
|-----------------|----------|------------------------------|
| `EMIT_BYTE_IMM` | `0b1001` | Instruction `[10:3]`         |
| `EMIT_BYTE_REG` | `0b0100` | `R7[7:0]` at runtime         |

```moleasm
; I2C address 0x48, R/W=0 (write), expect ACK
EMIT_BYTE_IMM imm=0x90 expect=0 mask=1 capture=1

; Same byte sourced from R7
LOAD_IMM      R7, 0x90
EMIT_BYTE_REG expect=0 mask=1 capture=1
```

`EMIT_BYTE_IMM` is the right choice for compile-time-known bytes
(addresses, CCC, register pointers). `EMIT_BYTE_REG` is for
bytes computed at runtime (typical of write-then-read flows
where R7 holds a captured / shifted value).

The bare mnemonic `EMIT_BYTE` (no suffix) was sugar for
`EMIT_BYTE_REG` in early v0.2 drafts; it is **retired** and the
assembler raises `E-LEX-006`.

## `STRETCH_SCL_IMM` / `STRETCH_SCL_REG`

Hold the bus in its current state for `n` extra quarter-bit
clocks. Useful for emitting clock stretching (target role) or any
arbitrary delay measured in quarters (controller role).

| Variant           | `sub`    | n source                             |
|-------------------|----------|--------------------------------------|
| `STRETCH_SCL_IMM` | `0b0111` | Instruction `[13:3]` (11 bits, 0..2047) |
| `STRETCH_SCL_REG` | `0b1000` | `R7[10:0]` (11 bits, 0..2047)        |

```moleasm
STRETCH_SCL_IMM 12        ; pause 12 quarters in the current state
STRETCH_SCL_IMM 0         ; no-op (still consumes one instruction slot)
LOAD_IMM        R7, 400
STRETCH_SCL_REG           ; pause 400 quarters from R7
```

`STRETCH_SCL_*` does not change the values driven on SDA or SCL
— it simply blocks the PC from advancing for `n` extra quarter
ticks. The two variants do **not** carry the flag triple.

## `SAMPLE_BIT_ON_SCL`

Target-role companion to `EMIT_BIT_*`. Reads one bit by slaving
to the external controller's SCL: waits for SCL high, samples
SDA, waits for SCL low, never drives anything. The flag triple
has the same semantics as `EMIT_BIT_*`.

| Variant             | `sub`    |
|---------------------|----------|
| `SAMPLE_BIT_ON_SCL` | `0b0101` |

```moleasm
SAMPLE_BIT_ON_SCL capture=1                 ; record whatever came in
SAMPLE_BIT_ON_SCL expect=0 mask=1           ; assert it was 0
```

## `DRIVE_BIT_ON_SCL`

Target-role companion to `EMIT_BIT_*` for the *drive* direction:
slaves to the controller's SCL, drives SDA to the supplied tx
symbol while SCL is low, releases / holds appropriately while
SCL is high. The flag triple is the same as `EMIT_BIT_*`.

| Variant            | `sub`    | tx source                                  |
|--------------------|----------|--------------------------------------------|
| `DRIVE_BIT_ON_SCL` | `0b0110` | Instruction `[4:3]` (no `_REG` variant)    |

```moleasm
DRIVE_BIT_ON_SCL tx=dominant                ; target ACK
DRIVE_BIT_ON_SCL tx=hiz capture=1           ; release; record what was seen
```

# CTRL group (8 opcodes)

CTRL opcodes drive program control flow (branch, wait, halt) and
configure the engine (bus mode, role, timing, flags). All CTRL
opcodes are role-agnostic.

## `HALT`

Stop the engine and record a 5-bit status code. The 32-bit ring
HALT word carries that status along with `overflow` (sticky) and
`mismatch` (snapshot of `MISMATCH_FLAG`) flags.

| Field   | Bits     | Description                                                       |
|---------|----------|-------------------------------------------------------------------|
| sub     | `[29:26]`| `0b0000`                                                          |
| status  | `[7:3]`  | Caller-defined 5-bit value (0x00..0x1F). `0x1F` is `STATUS_TRAP`. |

```moleasm
HALT status=0       ; completed normally
HALT status=1       ; conventional "failed" return code
HALT                ; sugar: status defaults to 0
```

The assembler rejects programs that emit `HALT status=0x1F` so a
user-defined HALT is always distinguishable from an
engine-detected trap.

## `BRANCH_ON`

PC-relative branch, taken iff a named cond is true. Cond-code
namespace is shared with `WAIT_ON`.

| Field    | Bits     | Description                                                |
|----------|----------|------------------------------------------------------------|
| sub      | `[29:26]`| `0b0001`                                                   |
| cond     | `[16:13]`| Cond code (4 bits; see Reference).                         |
| offset   | `[12:3]` | Signed 10-bit PC-relative offset (-512..+511).             |

Use a label name; the assembler computes the offset.

```moleasm
loop:
    EMIT_BIT_IMM tx=dominant
    EMIT_BIT_IMM tx=hiz expect=0 mask=1 capture=1
    BRANCH_ON    MISMATCH, abort     ; forward branch
    JMP          loop                 ; sugar: BRANCH_ON ALWAYS, loop
abort:
    HALT         status=1
```

Offsets outside `-512..+511` raise `E-RNG-003`; hop via `JMP`.

## `WAIT_ON`

Wait until a named bus condition becomes true, or until a
quarter-bit timeout expires. Shares the cond-code namespace with
`BRANCH_ON`.

| Field    | Bits     | Description                                                |
|----------|----------|------------------------------------------------------------|
| sub      | `[29:26]`| `0b0010`                                                   |
| cond     | `[16:13]`| Cond code (4 bits).                                        |
| timeout  | `[12:3]` | Unsigned 10-bit quarter-bit timeout (0..1023; 0 = forever).|

```moleasm
WAIT_ON START_SEEN, 100     ; up to 100 quarters for a START
WAIT_ON SDA_LOW,     64     ; wait for SDA low
WAIT_ON ALWAYS,       0     ; degenerate no-op (cond always true)
```

On timeout, `TIMEOUT_FLAG` is set; a subsequent `BRANCH_ON
TIMEOUT, ...` can react.

## `SET_BUS_MODE`

Switch the active `BUS_MODE`. This re-defines what `recessive`
and `hiz` mean electrically, and which timing register is
active.

| Field    | Bits     | Description                                  |
|----------|----------|----------------------------------------------|
| sub      | `[29:26]`| `0b0011`                                     |
| mode     | `[5:3]`  | Encoded bus-mode value (0..3; 4..7 trap).    |

```moleasm
SET_BUS_MODE i2c          ; recessive = Hi-Z, timing reg 0
SET_BUS_MODE i3c-OD       ; recessive = Hi-Z, timing reg 1
SET_BUS_MODE i3c-PP       ; recessive = push-pull high, timing reg 2
SET_BUS_MODE hdr-ddr      ; recessive = push-pull high, timing reg 3
```

## `SET_ROLE`

Select the engine's role at runtime. Power-on default is
`MoleConfig.role` (controller on the Verde build).

| Field    | Bits     | Description                                                |
|----------|----------|------------------------------------------------------------|
| sub      | `[29:26]`| `0b0100`                                                   |
| role     | `[3]`    | `0` = controller, `1` = target.                            |

```moleasm
SET_ROLE controller         ; explicit; matches the v0.2 default
SET_ROLE target             ; switch to follower
```

A mid-program role switch releases all bus drivers before
writing the role register, so the bus is left in a clean Hi-Z
state regardless of which role was driving last.

## `FLAG_CLEAR`

Explicitly clear one or more sticky flags. The 5-bit mask
selects which flags to clear; a bit set in the mask clears the
corresponding flag.

| Field    | Bits     | Description                                                |
|----------|----------|------------------------------------------------------------|
| sub      | `[29:26]`| `0b0101`                                                   |
| mask     | `[7:3]`  | 5-bit mask: `[0]=MISMATCH`, `[1]=TIMEOUT`, `[2]=START`, `[3]=STOP`, `[4]=REG_ZERO`. |

```moleasm
FLAG_CLEAR mask=0b00001     ; clear MISMATCH only
FLAG_CLEAR mask=0b11111     ; clear all five sticky flags
```

The most common use is `FLAG_CLEAR mask=0b00001` between two
`mask=1` capturing opcodes when the program needs
per-operation observability of `MISMATCH_FLAG`.

## `MARK`

Push a 3-word marker record into the result ring. The label is
14 bits of user-defined value; the engine attaches a 32-bit
timestamp (cycles since program start) split across two
follow-up ring words.

| Field    | Bits     | Description                                                |
|----------|----------|------------------------------------------------------------|
| sub      | `[29:26]`| `0b0110`                                                   |
| label    | `[16:3]` | 14-bit user label (0..0x3FFF).                             |

```moleasm
MARK label=1        ; "everything after this came from the ACK path"
MARK label=0x2A     ; arbitrary user marker
```

## `LOAD_TIMING`

Write a 14-bit divider value into one of **8** timing registers in
the WIRE sub-block. The active timing register is selected by the
current `BUS_MODE` and the kind of timing knob being adjusted
(quarter-bit period, data hold, data set-up).

| Field    | Bits     | Description                                                |
|----------|----------|------------------------------------------------------------|
| sub      | `[29:26]`| `0b0111`                                                   |
| reg      | `[19:17]`| Timing register index (0..7; see spec §5.17 table).        |
| divider  | `[16:3]` | 14-bit unsigned divider value (0..16383).                  |

```moleasm
LOAD_TIMING reg=0, divider=59     ; ~100 kHz I2C at Verde 24 MHz fabric
LOAD_TIMING reg=2, divider=5      ; ~1 MHz i3c-OD at Verde 24 MHz fabric
LOAD_TIMING reg=4, divider=2      ; ~2 MHz i3c-PP at Verde 24 MHz fabric
```

Quarter-bit period in fabric cycles = `divider + 1` (for the
`tQB_*` registers — regs 0, 2, 4, 6). The set-up / hold knobs
(regs 1, 3, 5, 7) measure in fabric cycles directly. See
`docs/MOLE-0.2-SPEC.md` §5.17 for the full timing-register map
and `mental-model.md` for a divider table at Verde's 24 MHz
fabric clock.

# DATA group (8 opcodes)

DATA opcodes operate on the 8x32-bit GP register file (`R0`..`R7`).
Every DATA op writes `REG_ZERO_FLAG` based on the post-operation
value, so the `DEC` + `BRANCH_ON REG_ZERO` loop idiom works
uniformly.

## `LOAD_IMM`

Load a 14-bit zero-extended immediate into a register.

| Field   | Bits     | Description                                                 |
|---------|----------|-------------------------------------------------------------|
| sub     | `[29:26]`| `0b0000`                                                    |
| dst     | `[25:23]`| 3-bit destination register (R0..R7).                        |
| imm14   | `[16:3]` | 14-bit unsigned immediate (0..16383).                       |

```moleasm
LOAD_IMM R0, 42
LOAD_IMM R6, 255
LOAD_IMM R7, 0x1FFF
```

`LOAD_LOOP n` is sugar for `LOAD_IMM R6, n`.

## `MOV`

Copy a register value to another register.

| Field   | Bits     | Description                                                 |
|---------|----------|-------------------------------------------------------------|
| sub     | `[29:26]`| `0b0001`                                                    |
| dst     | `[25:23]`| Destination register.                                       |
| src     | `[22:20]`| Source register.                                            |

```moleasm
MOV R3, R7      ; R3 := R7
```

## `ADD_IMM`

Add a signed 8-bit immediate to a register.

| Field   | Bits     | Description                                                 |
|---------|----------|-------------------------------------------------------------|
| sub     | `[29:26]`| `0b0010`                                                    |
| dst     | `[25:23]`| Destination register.                                       |
| src     | `[22:20]`| Source register.                                            |
| imm8    | `[10:3]` | 8-bit signed two's-complement immediate (-128..+127).       |

```moleasm
ADD_IMM R0, R0, 1       ; R0 := R0 + 1
ADD_IMM R5, R5, -3      ; R5 := R5 - 3
```

## `DEC`

In-place decrement. Equivalent to `ADD_IMM Rx, Rx, -1` but
fused into a single opcode for tight loops.

| Field   | Bits     | Description                                                 |
|---------|----------|-------------------------------------------------------------|
| sub     | `[29:26]`| `0b0011`                                                    |
| dst     | `[25:23]`| Register to decrement (dst = src).                          |

```moleasm
DEC R6      ; R6 := R6 - 1; sets REG_ZERO_FLAG
```

The canonical loop back-edge is `DEC Rx` immediately followed by
`BRANCH_ON {REG_ZERO|NOT_REG_ZERO}, <label>`. Any ALU op between
them clobbers `REG_ZERO_FLAG` and triggers assembler warning
`LINT-001`.

## `AND_IMM` / `OR_IMM` / `XOR_IMM`

Bitwise immediate operations. Three opcodes with identical
shape, different operations.

| Field   | Bits     | Description                                                 |
|---------|----------|-------------------------------------------------------------|
| sub     | `[29:26]`| `0b0100` (AND), `0b0101` (OR), `0b0110` (XOR)               |
| dst     | `[25:23]`| Destination register.                                       |
| src     | `[22:20]`| Source register.                                            |
| imm14   | `[16:3]` | 14-bit unsigned immediate (zero-extended for AND/OR; XOR is uniform). |

```moleasm
AND_IMM R0, R0, 0x00FF   ; R0 := R0 & 0x000000FF (mask low byte)
OR_IMM  R1, R1, 0x0001   ; R1 := R1 | 1 (set bit 0)
XOR_IMM R2, R2, 0x3FFF   ; R2 := R2 ^ 0x3FFF (toggle low 14 bits)
```

## `SHIFT`

Logical / arithmetic shift by an immediate amount.

| Field   | Bits     | Description                                                 |
|---------|----------|-------------------------------------------------------------|
| sub     | `[29:26]`| `0b0111`                                                    |
| dst     | `[25:23]`| Destination register.                                       |
| src     | `[22:20]`| Source register.                                            |
| dir     | `[12]`   | `0` = right, `1` = left.                                    |
| arith   | `[11]`   | `0` = logical, `1` = arithmetic (right shift only).         |
| amount  | `[7:3]`  | 5-bit shift amount (0..31).                                 |

```moleasm
SHIFT R0, R0, left,  3            ; R0 := R0 << 3
SHIFT R1, R1, right, 4, logical   ; R1 := R1 >>> 4 (unsigned)
SHIFT R2, R2, right, 4, arith     ; R2 := R2 >> 4  (signed)
```

# LOOP group (reserved)

The LOOP group (`[31:30] = 0b11`) is fully reserved for v0.5.
The engine traps any encountered LOOP-group instruction to
`HALT` with `STATUS_TRAP`. The reservation is documented in
`docs/MOLE-0.2-SPEC.md` §4.4.

# Retired v0 vocabulary

The following names appeared in the v0 ISA but have been
**removed in v0.2**. The assembler rejects them; the bytecode
encoder will never emit them. Use `.dw` only if you genuinely
need to inject raw bits for a fault-injection test.

| Retired name           | Replacement                                                       |
|------------------------|-------------------------------------------------------------------|
| `EMIT_BIT` (bare)      | `EMIT_BIT_IMM` or `EMIT_BIT_REG`                                  |
| `EMIT_QUARTER` (bare)  | `EMIT_QUARTER_IMM` or `EMIT_QUARTER_REG`                          |
| `EMIT_BYTE` (bare)     | `EMIT_BYTE_IMM` or `EMIT_BYTE_REG`                                |
| `STRETCH_SCL` (bare)   | `STRETCH_SCL_IMM` or `STRETCH_SCL_REG`                            |
| `JMP <addr>` opcode    | Sugar for `BRANCH_ON ALWAYS, <label>` — `JMP <label>` still works |
| `DEC_BRANCH`           | `DEC Rx` + `BRANCH_ON {REG_ZERO|NOT_REG_ZERO}, <label>`           |
| `LOAD_LOOP` opcode     | Sugar for `LOAD_IMM R6, n` — `LOAD_LOOP n` still works            |
| `LCR0` / `LCR1`        | `R0`..`R7` general-purpose registers (R6 is the conventional loop counter) |

You now have a complete tour of the v0.2 ISA. The next chapter
ties it together with three end-to-end programs.

