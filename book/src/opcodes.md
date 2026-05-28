# Opcodes

The Mole engine implements **15 v0 opcodes**, each encoded as a
16-bit word. This chapter walks through them in roughly the order
you will reach for them as a new user. Each section gives the
mnemonic, its operands, a short description, the wire encoding
(reproduced from `Instruction.scala` for reference), and at least
one realistic example.

The opcode field is bits `[15:11]` of every instruction. The
encodings are reproduced verbatim from the FPGA --- they are the
contract you would have to honor if you ever wanted to write
bytecode by hand.

## `HALT`

Stops the engine and records a 4-bit status code.

| Field   | Bits     | Description                                         |
|---------|----------|-----------------------------------------------------|
| opcode  | `[15:11]`| `0x0`                                               |
| status  | `[10:7]` | Caller-defined 4-bit value, surfaced to the host.   |

```text
HALT status=0        ; completed normally
HALT status=1        ; conventional "failed" return code
HALT                 ; status defaults to 0
```

Convention: status `0` means success; non-zero status values are
program-defined error codes. The drainer ships the status verbatim;
nothing in the engine cares what it means.

## `EMIT_BIT`

Emits one full bit-time on the bus and (optionally) checks /
captures the value read back on SDA. SCL follows the standard
two-quarters-low / two-quarters-high pattern for the active
`BUS_MODE`.

| Field   | Bits     | Description                                              |
|---------|----------|----------------------------------------------------------|
| opcode  | `[15:11]`| `0x1`                                                    |
| tx      | `[10:9]` | tx symbol (`dominant`/`recessive`/`hiz`/reserved).       |
| expect  | `[2]`    | Expected SDA value (only meaningful when `mask=1`).      |
| mask    | `[1]`    | If 1, set `MISMATCH_FLAG` on `read != expect`.           |
| capture | `[0]`    | If 1, push the read bit to the result ring.              |

```text
EMIT_BIT tx=dominant                          ; emit a '0' bit
EMIT_BIT tx=recessive                         ; emit a '1' bit
EMIT_BIT tx=hiz   expect=0 mask=1 capture=1   ; ACK slot
EMIT_BIT tx=hiz   capture=1                   ; sample without checking
```

The reserved tx code (`0b11`) is rejected at parse time; reach it
via `.dw` if you really need to.

## `EMIT_QUARTER`

Emits exactly one quarter-bit-time with independent control of SDA
and SCL. This is the surgical tool: where `EMIT_BIT` walks a
canonical bit pattern, `EMIT_QUARTER` lets you assemble any waveform
edge by edge --- START / STOP, repeated-start, SCL stretching, you
name it.

| Field   | Bits     | Description                                              |
|---------|----------|----------------------------------------------------------|
| opcode  | `[15:11]`| `0x2`                                                    |
| sda     | `[10:9]` | tx symbol for SDA this quarter.                          |
| scl     | `[8:7]`  | tx symbol for SCL this quarter.                          |
| expect  | `[2]`    | (same as `EMIT_BIT`)                                     |
| mask    | `[1]`    | (same as `EMIT_BIT`)                                     |
| capture | `[0]`    | (same as `EMIT_BIT`)                                     |

```text
; I2C START
EMIT_QUARTER sda=recessive scl=recessive   ; idle
EMIT_QUARTER sda=dominant  scl=recessive   ; SDA low while SCL high
EMIT_QUARTER sda=dominant  scl=dominant    ; now pull SCL low

; I2C STOP
EMIT_QUARTER sda=dominant  scl=dominant
EMIT_QUARTER sda=dominant  scl=recessive
EMIT_QUARTER sda=recessive scl=recessive
```

In target role (where the engine is the bus *follower*, not the
controller), `EMIT_QUARTER`'s `scl_symbol` is constrained: you can
pull SCL low (stretch / fuzz) or release it, but you cannot
push-pull it high. The compiler enforces this for PP-class bus
modes; the engine ignores the bad combination at runtime as defense
in depth.

## `STRETCH_SCL`

Holds the bus in its current state for `n_quarters` extra
quarter-bit clocks. Useful for emitting clock stretching or any
arbitrary delay measured in quarters.

| Field        | Bits     | Description                            |
|--------------|----------|----------------------------------------|
| opcode       | `[15:11]`| `0x3`                                  |
| n_quarters   | `[10:0]` | Hold duration in quarters (0..2047).   |

```text
STRETCH_SCL 12        ; pause 12 quarters in the current state
STRETCH_SCL 0         ; no-op (still consumes one instruction slot)
```

`STRETCH_SCL` does not change the values driven on SDA or SCL --- it
simply blocks the PC from advancing for `n_quarters` clock ticks.

## `WAIT_ON`

Waits until a named bus condition becomes true, or until a timeout
expires. Useful when synchronising the engine against external
events.

| Field    | Bits     | Description                                    |
|----------|----------|------------------------------------------------|
| opcode   | `[15:11]`| `0x4`                                          |
| cond     | `[10:7]` | Cond code (see [Reference](./reference.md)).  |
| timeout  | `[6:0]`  | Max quarters to wait (0..127). 0 = no timeout. |

```text
WAIT_ON START_SEEN, 100     ; up to 100 quarters for a START on the bus
WAIT_ON SDA_LOW,     64     ; wait for SDA to be sampled low
WAIT_ON ALWAYS,       0     ; degenerate: condition is immediately true
```

On timeout, `TIMEOUT_FLAG` is set; subsequent `BRANCH_ON TIMEOUT, ...`
can branch on it.

## `BRANCH_ON`

PC-relative branch, taken iff a named condition is true. Cond code
namespace is shared with `WAIT_ON`.

| Field    | Bits     | Description                                                 |
|----------|----------|-------------------------------------------------------------|
| opcode   | `[15:11]`| `0x5`                                                       |
| cond     | `[10:7]` | Cond code.                                                  |
| offset   | `[6:0]`  | Signed 7-bit PC offset (`-64..+63`).                        |

The offset is *relative* to the instruction after the branch:
`offset = target_pc - branch_pc - 1`. When you use a label name, the
assembler computes the offset for you.

```text
loop:
    EMIT_BIT      tx=dominant
    EMIT_BIT      tx=hiz expect=0 mask=1 capture=1
    BRANCH_ON     MISMATCH, abort      ; forward branch
    JMP           loop                  ; we'll see JMP shortly
abort:
    HALT          status=1
```

Offsets outside `-64..+63` are rejected at assemble time --- the
fix is to either rearrange your program or jump via `JMP`.

## `JMP`

Absolute jump within the program-memory budget.

| Field   | Bits     | Description                                                 |
|---------|----------|-------------------------------------------------------------|
| opcode  | `[15:11]`| `0x6`                                                       |
| addr    | `[10:0]` | Absolute PC target (0..2047).                              |

```text
JMP loop          ; named target
JMP 0             ; restart from PC 0
JMP 0x100         ; raw numeric target
```

Use `JMP` for long-range jumps and `BRANCH_ON ALWAYS, ...` only
when the short PC offset actually fits.

## `SET_BUS_MODE`

Switches the active `BUS_MODE`. This re-defines what `recessive` and
`hiz` mean electrically and which timing register is active.

| Field    | Bits     | Description                                  |
|----------|----------|----------------------------------------------|
| opcode   | `[15:11]`| `0x7`                                        |
| mode_wire| `[10:8]` | Encoded bus-mode value (0, 1, 6, or 7).      |

```text
SET_BUS_MODE i2c          ; recessive = Hi-Z, timing reg 0
SET_BUS_MODE i3c-od       ; recessive = Hi-Z, timing reg 1
SET_BUS_MODE i3c-pp       ; recessive = push-pull high, timing reg 2
SET_BUS_MODE hdr-ddr      ; recessive = push-pull high, timing reg 3
```

The bus-mode names are case-insensitive at the source level
(`i2c` and `I2C` both work). The hyphenated names (`i3c-od`,
`i3c-pp`, `hdr-ddr`) are intentionally not separated by underscores
to discourage confusion with `_div`-style register aliases.

## `LOAD_TIMING`

Writes a 9-bit divider into one of four per-mode timing registers.
The active timing register is selected by the current `BUS_MODE`.

| Field        | Bits     | Description                              |
|--------------|----------|------------------------------------------|
| opcode       | `[15:11]`| `0x8`                                    |
| reg          | `[10:9]` | Timing register index (0..3).            |
| divider_word | `[8:0]`  | 9-bit divider value (0..511).            |

The register index can be a numeric literal (`0`) or one of the four
named aliases:

| Alias              | Reg | Active in mode |
|--------------------|-----|----------------|
| `i2c_freq`         | 0   | `i2c`          |
| `i3c_od_freq`      | 1   | `i3c-od`       |
| `i3c_pp_freq`      | 2   | `i3c-pp`       |
| `hdr_ddr_freq`     | 3   | `hdr-ddr`      |

```text
LOAD_TIMING i2c_freq,    60    ; ~100 kHz @ Verde 24 MHz fabric (~98.4 kHz exact)
LOAD_TIMING i3c_od_freq,  5    ; ~1 MHz at Verde 24 MHz fabric (= reset default)
```

Recompute the divider when you change board / clock. The Verde
default is 24 MHz, so a 100 kHz I2C bit rate wants `N = 24_000_000
/ (4 * 100_000) - 1 = 59` (or `60` as the round-number figure the
rest of the book uses; the resulting ~98.4 kHz is well within I2C
Standard-mode tolerance). For a full table including 400 kHz, 1
MHz, and a note on why 3.4 MHz is not reachable at 24 MHz, see
the [Picking a divider](./mental-model.md#picking-a-divider)
section in the Mental Model chapter.

## `MARK`

Pushes an 8-bit user label into the result ring. Lets a host-side
decoder discriminate between captures from different points in the
program.

| Field   | Bits     | Description                              |
|---------|----------|------------------------------------------|
| opcode  | `[15:11]`| `0x9`                                    |
| label   | `[10:3]` | 8-bit label.                             |

```text
MARK label=1     ; "everything after this came from the ACK path"
MARK label=0xFF  ; "this is the final dribble"
```

## `SAMPLE_BIT_ON_SCL`

Target-role companion to `EMIT_BIT`. Reads one bit by slaving to the
controller's SCL: waits for SCL high, samples SDA, waits for SCL
low, never drives anything. The flag triple has the same semantics
as `EMIT_BIT`.

| Field   | Bits     | Description                                       |
|---------|----------|---------------------------------------------------|
| opcode  | `[15:11]`| `0xA`                                             |
| expect  | `[2]`    | Expected SDA value (only meaningful when `mask=1`).|
| mask    | `[1]`    | Set `MISMATCH_FLAG` if read != expect.            |
| capture | `[0]`    | Push read bit into the ring.                      |

```text
SAMPLE_BIT_ON_SCL capture=1               ; record whatever came in
SAMPLE_BIT_ON_SCL expect=0 mask=1         ; assert it was 0
```

## `DRIVE_BIT_ON_SCL`

Target-role companion to `EMIT_BIT` for the *drive* direction:
slaves to the controller's SCL, drives SDA to the supplied tx
symbol while SCL is low, releases / holds appropriately while SCL
is high. The flag triple is the same as `EMIT_BIT`.

| Field   | Bits     | Description                                       |
|---------|----------|---------------------------------------------------|
| opcode  | `[15:11]`| `0xB`                                             |
| tx      | `[10:9]` | tx symbol to drive.                               |
| expect  | `[2]`    | Expected SDA value (only meaningful when `mask=1`).|
| mask    | `[1]`    | Set `MISMATCH_FLAG` if read != expect.            |
| capture | `[0]`    | Push read bit into the ring.                      |

```text
DRIVE_BIT_ON_SCL tx=dominant                  ; target ACK
DRIVE_BIT_ON_SCL tx=hiz capture=1             ; release; record what was seen
```

## `LOAD_LOOP`

Primes one of the two 8-bit loop-counter registers (`lcr0` or
`lcr1`) with an unsigned immediate, ready for [`DEC_BRANCH`](#dec_branch)
to count down. The two LCRs are completely independent, which lets
you write nested loops without spilling to a result-ring scratch
slot.

| Field    | Bits     | Description                                       |
|----------|----------|---------------------------------------------------|
| opcode   | `[15:11]`| `0xC`                                             |
| reg      | `[10]`   | `0` = `lcr0`, `1` = `lcr1`.                       |
| reserved | `[9:8]`  | Must be `0` in v1; reserved for a future widening.|
| imm      | `[7:0]`  | Unsigned 8-bit initial value (0..255).            |

```text
LOAD_LOOP lcr0, 8                             ; eight iterations
LOAD_LOOP lcr1, 0                             ; lints in source; runs 256 iters
```

`LOAD_LOOP r, 0` is legal but unusual: the matching `DEC_BRANCH r`
wraps the counter from `0` to `0xFF` on the first iteration and
runs a full 256 passes. The assembler warns when it sees a
literal `0`; if you really need a 256-iteration loop, prefer
`LOAD_LOOP r, 0` with a comment explaining the intent.

The `[9:8]` reserved bits stay zero in v1. A future 4-LCR
widening will use those two bits as additional reg-id bits with
no wire-format break.

## `DEC_BRANCH`

Decrements one of the LCRs and branches by a signed 8-bit
PC-relative offset (±128 instructions) if the post-decrement
value is non-zero. The branch target is usually a label; the
assembler computes the offset for you. Intentionally wider than
[`BRANCH_ON`](#branch_on)'s 7-bit offset because tight inner
loops benefit from longer back-edges.

| Field    | Bits     | Description                                                |
|----------|----------|------------------------------------------------------------|
| opcode   | `[15:11]`| `0xD`                                                      |
| reg      | `[10]`   | `0` = `lcr0`, `1` = `lcr1`.                                |
| reserved | `[9:8]`  | Must be `0` in v1.                                         |
| offset   | `[7:0]`  | Signed 8-bit PC-relative offset (target = PC + 1 + offset).|

Semantics, per fetch:

1. `LCR[reg] <- LCR[reg] - 1` (8-bit wrap; `0` becomes `0xFF`).
2. `if (LCR[reg] != 0) PC <- PC + 1 + offset`.

```text
LOAD_LOOP   lcr0, 8                           ; eight bits
bit_loop:
EMIT_BIT    tx=dominant
DEC_BRANCH  lcr0, bit_loop                    ; back-edge while lcr0 != 0
```

`DEC_BRANCH` does **not** touch any of the sticky engine flags
(`MISMATCH_FLAG`, `TIMEOUT_FLAG`, `START_FLAG`, `STOP_FLAG`),
so it composes cleanly with the `BRANCH_ON MISMATCH ...`
fail-fast idiom inside the loop body.

## `SET_ROLE`

Selects the engine's role at runtime. `role = 0` puts the engine
in controller role (it drives SCL via `EMIT_BIT` / `EMIT_QUARTER`);
`role = 1` puts it in target role (it slaves to the external
controller's SCL via `SAMPLE_BIT_ON_SCL` / `DRIVE_BIT_ON_SCL`).
The power-on default is taken from `MoleConfig.role`, so a
program that never issues `SET_ROLE` keeps the historical
compile-time-style behaviour.

| Field    | Bits     | Description                                                |
|----------|----------|------------------------------------------------------------|
| opcode   | `[15:11]`| `0x10`                                                     |
| role     | `[10]`   | `0` = controller, `1` = target.                            |
| reserved | `[9:0]`  | Must be `0` in v1.                                         |

```text
SET_ROLE controller         ; explicit; matches the v0 default
SET_ROLE target             ; switch to follower
```

The decode arm releases all bus drivers (`sdaDriveLow/High`,
`sclDriveLow/High := False`) before writing the role register, so
a mid-program role switch always leaves the bus in a clean Hi-Z
state regardless of which role was driving last. There is no
"must be first" check --- the SDK convention is to issue
`SET_ROLE` near the top of every program, but the engine accepts
the opcode at any PC.

The short forms `controller`/`target` are case-insensitive; the
numeric forms `0`/`1` are also accepted.

## A note on the reserved-v0.5 mnemonics

The mnemonics `FLAG_CLEAR` and `CAPTURE_RUN` are reserved for a
future opcode expansion (see `AGENTS.md` §3.14). The assembler
rejects them at parse time and points you at `.dw` for raw
injection. Do not be tempted to re-encode them by hand even with
`.dw` unless you really mean to: the engine will currently ignore
those opcode bits as no-ops, but that will change.

(Two slots that used to be reserved --- `WAIT_ADDRESSED` at `0xC`
and `MISMATCH_CLEAR` at `0xD` --- graduated to v0 as `LOAD_LOOP`
and `DEC_BRANCH` above.)

You now have a complete tour of the v0 ISA. The next chapter ties
it together with three end-to-end programs.
