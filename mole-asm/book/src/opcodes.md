# Opcodes

The Mole engine implements **12 v0 opcodes**, each encoded as a
16-bit word. This chapter walks through them in roughly the order
you will reach for them as a new user. Each section gives the
mnemonic, its operands, a short description, the wire encoding
(reproduced from `Instruction.scala` for reference), and at least
one realistic example.

The opcode field is bits `[15:12]` of every instruction. The
encodings are reproduced verbatim from the FPGA --- they are the
contract you would have to honor if you ever wanted to write
bytecode by hand.

## `HALT`

Stops the engine and records a 4-bit status code.

| Field   | Bits     | Description                                         |
|---------|----------|-----------------------------------------------------|
| opcode  | `[15:12]`| `0x0`                                               |
| status  | `[11:8]` | Caller-defined 4-bit value, surfaced to the host.   |

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
| opcode  | `[15:12]`| `0x1`                                                    |
| tx      | `[11:10]`| tx symbol (`dominant`/`recessive`/`hiz`/reserved).       |
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
| opcode  | `[15:12]`| `0x2`                                                    |
| sda     | `[11:10]`| tx symbol for SDA this quarter.                          |
| scl     | `[9:8]`  | tx symbol for SCL this quarter.                          |
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
| opcode       | `[15:12]`| `0x3`                                  |
| n_quarters   | `[11:0]` | Hold duration in quarters (0..4095).   |

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
| opcode   | `[15:12]`| `0x4`                                          |
| cond     | `[11:8]` | Cond code (see [Reference](./reference.md)).  |
| timeout  | `[7:0]`  | Max quarters to wait (0..255). 0 = no timeout. |

```text
WAIT_ON START_SEEN, 200     ; up to 200 quarters for a START on the bus
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
| opcode   | `[15:12]`| `0x5`                                                       |
| cond     | `[11:8]` | Cond code.                                                  |
| offset   | `[7:0]`  | Signed 8-bit PC offset (`-128..+127`).                      |

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

Offsets outside `-128..+127` are rejected at assemble time --- the
fix is to either rearrange your program or jump via `JMP`.

## `JMP`

Absolute jump within the program-memory budget.

| Field   | Bits     | Description                                                 |
|---------|----------|-------------------------------------------------------------|
| opcode  | `[15:12]`| `0x6`                                                       |
| addr    | `[11:0]` | Absolute PC target (0..4095).                              |

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
| opcode   | `[15:12]`| `0x7`                                        |
| mode_wire| `[11:9]` | Encoded bus-mode value (0, 1, 6, or 7).      |

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

Writes a 10-bit divider into one of four per-mode timing registers.
The active timing register is selected by the current `BUS_MODE`.

| Field        | Bits     | Description                              |
|--------------|----------|------------------------------------------|
| opcode       | `[15:12]`| `0x8`                                    |
| reg          | `[11:10]`| Timing register index (0..3).            |
| divider_word | `[9:0]`  | 10-bit divider value (0..1023).          |

The register index can be a numeric literal (`0`) or one of the four
named aliases:

| Alias              | Reg | Active in mode |
|--------------------|-----|----------------|
| `i2c_freq`         | 0   | `i2c`          |
| `i3c_od_freq`      | 1   | `i3c-od`       |
| `i3c_pp_freq`      | 2   | `i3c-pp`       |
| `hdr_ddr_freq`     | 3   | `hdr-ddr`      |

```text
LOAD_TIMING i2c_freq,   250    ; 250-quarter bit period
LOAD_TIMING i3c_od_freq, 24    ; ~1 MHz at Verde 24 MHz fabric
```

Recompute the divider when you change board / clock. The Verde
default is 24 MHz, so a 100 kHz I2C bit period is `24_000_000 /
(100_000 * 4) ≈ 60` --- but `250` empirically lands at the right
shape on hardware (see the `first-light` fixture commentary).

## `MARK`

Pushes an 8-bit user label into the result ring. Lets a host-side
decoder discriminate between captures from different points in the
program.

| Field   | Bits     | Description                              |
|---------|----------|------------------------------------------|
| opcode  | `[15:12]`| `0x9`                                    |
| label   | `[11:4]` | 8-bit label.                             |

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
| opcode  | `[15:12]`| `0xA`                                             |
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
| opcode  | `[15:12]`| `0xB`                                             |
| tx      | `[11:10]`| tx symbol to drive.                               |
| expect  | `[2]`    | Expected SDA value (only meaningful when `mask=1`).|
| mask    | `[1]`    | Set `MISMATCH_FLAG` if read != expect.            |
| capture | `[0]`    | Push read bit into the ring.                      |

```text
DRIVE_BIT_ON_SCL tx=dominant                  ; target ACK
DRIVE_BIT_ON_SCL tx=hiz capture=1             ; release; record what was seen
```

## A note on the reserved-v0.5 mnemonics

The mnemonics `WAIT_ADDRESSED`, `MISMATCH_CLEAR`, `FLAG_CLEAR`, and
`CAPTURE_RUN` are reserved for a future opcode expansion (see
`AGENTS.md` §3.14). The assembler rejects them at parse time and
points you at `.dw` for raw injection. Do not be tempted to
re-encode them by hand even with `.dw` unless you really mean to:
the engine will currently ignore those opcode bits as no-ops, but
that will change.

You now have a complete tour of the v0 ISA. The next chapter ties
it together with three end-to-end programs.
