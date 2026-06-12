# Syntax

A `.moleasm` source file is a flat sequence of lines. Each line is
**at most one** of:

- a blank line or a comment-only line,
- a label definition,
- an instruction (optionally preceded by a label on the same line),
- a directive (`.equ` or `.dw`, optionally preceded by a label).

There is no nesting, no scoping, no preprocessor. What you read is
what gets assembled, in source order.

## Comments

A semicolon starts a comment that runs to the end of the line.
Multiple semicolons in a row are still a single comment delimiter:

```text
HALT status=0      ; legal
HALT status=0      ;;; also legal --- nothing special about ";;;"
; line-only comment
```

There are no block comments. If you want a multi-line note, prefix
each line with `;`.

## Whitespace

ASCII spaces and tabs are both legal anywhere whitespace appears.
Multiple-whitespace runs collapse to one separator everywhere except
inside `key=value` operands (where they aren't allowed). These all
parse identically:

```text
HALT status=0
HALT  status=0
HALT	status=0
HALT		status=0
```

Trailing whitespace and trailing blank lines are ignored. End your
files with a single trailing newline (AGENTS §4) and you'll be
fine.

## Identifiers

Identifiers --- the names of labels, `.equ` constants, and the
named symbols built into the language --- match the pattern
`[A-Za-z_][A-Za-z0-9_-]*`. Labels are slightly stricter and may
not contain dashes; `.equ` names may.

Two name spaces are *reserved* and your own identifiers may not
collide with them:

- The 26 v0.2 mnemonics (`HALT`, `EMIT_BIT_IMM`, `EMIT_BIT_REG`,
  `EMIT_QUARTER_IMM`, `EMIT_QUARTER_REG`, `EMIT_BYTE_IMM`,
  `EMIT_BYTE_REG`, `STRETCH_SCL_IMM`, `STRETCH_SCL_REG`,
  `SAMPLE_BIT_ON_SCL`, `DRIVE_BIT_ON_SCL`, `BRANCH_ON`,
  `WAIT_ON`, `SET_BUS_MODE`, `SET_ROLE`, `FLAG_CLEAR`, `MARK`,
  `LOAD_TIMING`, `LOAD_IMM`, `MOV`, `ADD_IMM`, `DEC`, `AND_IMM`,
  `OR_IMM`, `XOR_IMM`, `SHIFT`), the sugar mnemonics (`JMP`,
  `LOAD_LOOP`), and the v0.5 reserved mnemonics (`CAPTURE_RUN`,
  `CALL`, `RET`).
- The named-symbol tables: `dominant`, `recessive`, `hiz`, `dom`,
  `rec` (tx symbols); `i2c`, `i3c-od`, `i3c-pp`, `hdr-ddr` (bus
  modes); the condition codes (`ALWAYS`, `MISMATCH`,
  `NOT_MISMATCH`, `START_SEEN`, `STOP_SEEN`, `SDA_LOW`,
  `SDA_HIGH`, `SCL_HIGH`, `TIMEOUT`, `NOT_TIMEOUT`, `REG_ZERO`,
  `NOT_REG_ZERO`); and the eight general-purpose register names
  (`R0`..`R7`).

The assembler rejects a colliding name with a clear error pointing
at the conflict. See the [Errors](./errors.md) chapter for the full
catalog.

## Labels

A label is a name followed by a colon. It binds the name to the
current PC value. Two forms work:

```text
loop:                            ; label on its own line
    EMIT_BIT_IMM tx=recessive
    BRANCH_ON ALWAYS, loop       ; reference by name (JMP loop is sugar)

stretch: STRETCH_SCL_IMM 8       ; label on the same line as an instruction
```

Labels are global: there is no scope. Re-defining a label is an
error. A label binds to whatever PC the *next* instruction occupies
--- a label on its own line followed by another label on the next
line means both labels point at the same PC.

## Numeric literals

| Form     | Example       | Meaning                       |
|----------|---------------|-------------------------------|
| Decimal  | `250`, `-1`   | Base 10, optional leading `-`.|
| Hex      | `0x1234`      | Base 16, lower-case `x` or `X`.|
| Binary   | `0b00110011`  | Base 2, lower-case `b` or `B`.|

A leading `-` makes a literal signed; useful for `BRANCH_ON`
offsets when you want to spell out the offset by hand instead of
naming a label.

## Operands

There are two operand styles depending on the opcode:

- **Positional** --- a comma-separated list of tokens, used by opcodes
  whose operands have an unambiguous order (`STRETCH_SCL_IMM n`,
  `BRANCH_ON cond, target`, `LOAD_IMM Rd, imm`, etc.).
- **Key/value** --- `key=value` pairs separated by whitespace or
  commas, used by opcodes with several modal flags
  (`EMIT_BIT_IMM tx=... expect=... mask=... capture=...`).

Within a single key/value list:

- Order of keys does not matter.
- Each key may appear at most once.
- Unrecognised keys are rejected with the list of allowed keys.
- Missing required keys (`tx=` on `EMIT_BIT_IMM`, `sda=` / `scl=`
  on `EMIT_QUARTER_IMM`, `imm=` on `EMIT_BYTE_IMM`) are rejected.
- Optional flag keys default to: `expect=X`, `mask=0`, `capture=0`.

## Directives

There are exactly two directives. The leading `.` distinguishes them
from mnemonics. Both are case-insensitive.

### `.equ NAME, VALUE`

Binds `NAME` to the constant `VALUE` (an integer literal or a
previously-defined `.equ`). The value is *not* a label PC --- you
cannot use an `.equ` name where a label is expected (e.g. as a
`BRANCH_ON` target), and vice versa.

```text
.equ slow_div, 59           ; 100 kHz at the Verde 24 MHz clock
.equ fast_div, 5            ; ~1 MHz at the Verde 24 MHz clock (= reset default)
LOAD_TIMING reg=0, divider=slow_div
```

`.equ` cannot forward-reference: the value must already be defined
on a previous line. This rule keeps the assembler single-pass-cheap
and surfaces typos early.

### `.dw VALUE [, VALUE...]`

Emits a literal 32-bit word at the current PC. The values must be
integer literals or previously-defined `.equ` names; labels are
rejected.

```text
.dw 0xC000_0000             ; raw HALT-tagged word --- e.g. a reserved encoding
.dw slow_div, fast_div      ; two words, in source order
```

`.dw` is the escape hatch for emitting opcodes or symbols the
assembler refuses to encode directly --- for example, the
reserved-v0.5 mnemonics (`CAPTURE_RUN`, `CALL`, `RET`) or the
reserved `tx_symbol` code `0b11`. The compiler's refusal is a
guard rail; `.dw` lets you cross it when you really need to.

Note that the assembler does not advance PC for `.equ` and advances
PC by `len(operands)` for `.dw`. Labels bound on a `.dw` line bind
to the PC of the *first* emitted word.

## Putting the syntax together

A complete, mostly-trivial source file:

```text
; one-shot I2C scan-or-fail
.equ slow_div, 59

start:
    LOAD_TIMING       reg=0, divider=slow_div
    SET_BUS_MODE      i2c

    ; START
    EMIT_QUARTER_IMM  sda=recessive scl=recessive
    EMIT_QUARTER_IMM  sda=dominant  scl=recessive
    EMIT_QUARTER_IMM  sda=dominant  scl=dominant

    ; one bit, capture for the host
    EMIT_BIT_IMM      tx=hiz expect=0 mask=1 capture=1
    BRANCH_ON         MISMATCH, nak

    HALT              status=0

nak:
    HALT              status=1
```

You now have everything you need to read any moleasm source you
encounter. The next chapter goes through the opcodes in detail.
