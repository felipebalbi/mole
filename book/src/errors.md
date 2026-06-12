# Errors

The `mole-asm` library classifies every assembly-time problem under
one of four `Kind`s. This chapter lists every variant the assembler
can produce, why it fires, and how to fix it. The classification
matters for tooling (tests pattern-match on `Kind`); the human
message is what you actually read at the terminal.

The `Kind` discriminant lives in `mole_asm::Kind` (see the rustdoc
for the type). Every `Syntax` error also carries a `SourceLocation`
(filename + 1-based line number) so editors can jump straight to the
offending line.

## `Kind::Lex` --- lexical and structural issues

### Unknown mnemonic

```text
FROBNICATE foo
```

Result:

```text
file.moleasm:1: unknown mnemonic: 'FROBNICATE'
```

Fix: check the spelling; mnemonics are `UPPER_CASE`. See the
[Opcodes](./opcodes.md) chapter for the full list.

### Lower-case mnemonic

```text
halt status=0
```

Result:

```text
file.moleasm:1: mnemonic must be UPPER CASE: got 'halt'
```

Fix: capitalise the mnemonic. The case-sensitivity is deliberate ---
it makes the source easier to scan for opcodes vs. operands.

### Reserved-v0.5 mnemonic

```text
FLAG_CLEAR
```

Result:

```text
file.moleasm:1: FLAG_CLEAR is a reserved-v0.5 opcode; use `.dw` to
                inject the raw word if you really mean it
```

Fix: either rework the program to avoid the opcode (the v0 ISA
deliberately doesn't include flag-clear; sticky flags survive across
instructions and are overwritten by the next flag-setting opcode), or
use `.dw 0xC000` if you are testing the engine.

### Unknown directive

```text
.include common.moleasm
```

Result:

```text
file.moleasm:1: unknown directive '.include' (allowed: .equ, .dw)
```

Fix: moleasm has no include mechanism today. Inline what you need or
post-process source before assembly.

### Malformed label or directive

These usually surface as the directive / mnemonic checks above when
the broken line happens to start with a `.` or an alphabetic token.

## `Kind::Symbol` --- symbol-table problems

### Duplicate label

```text
loop:
    HALT
loop:
    HALT
```

Result:

```text
file.moleasm:3: label 'loop' re-defined (prior label on line 1)
```

Fix: pick a different name. There is no scoping; every label is
global.

### Duplicate `.equ`

Same as above, but for `.equ`.

### Reserved-name collision

```text
.equ HALT, 1
```

Result:

```text
file.moleasm:1: equate name 'HALT' collides with a reserved
                mnemonic / symbol / alias
```

Fix: rename the `.equ`. Reserved names cover the 26 v0.2
mnemonics, the v0.5 reserved mnemonics (`CAPTURE_RUN`, `CALL`,
`RET`), the sugar names (`JMP`, `LOAD_LOOP`), the tx-symbol
names (`dominant`, `recessive`, `hiz`, `dom`, `rec`), the
bus-mode names (`i2c`, `i3c-od`, `i3c-pp`, `hdr-ddr`), the cond
codes, and the eight GP register names (`R0`..`R7`).

### Undefined symbol

```text
LOAD_TIMING reg=0, divider=slow_div
```

(with no prior `.equ slow_div, ...`). Result:

```text
file.moleasm:1: undefined symbol: 'slow_div'
```

Fix: define the symbol with `.equ` before the line that uses it. The
assembler is single-pass for `.equ`; forward references are
deliberately rejected.

### Label used where an equate is expected (or vice versa)

```text
loop:
    HALT
LOAD_TIMING reg=0, divider=loop      ; oops: loop is a label, not an .equ
```

Result:

```text
file.moleasm:3: 'loop' is a label (PC address), not a constant;
                expected an .equ value here
```

Fix: either define `loop` as `.equ` (if you want a constant) or
provide a different operand to `LOAD_TIMING`. The check exists
because mixing labels and equates silently has bitten too many users
in too many other assemblers.

### `.dw` with a label operand

```text
loop:
    .dw loop
```

Result:

```text
file.moleasm:2: 'loop' is a label (PC address), not a constant;
                expected an .equ value here
```

Fix: use a numeric literal or an `.equ`. `.dw` is for raw words, not
for forward-jump computation.

### Branch / jump to an unknown target

```text
BRANCH_ON ALWAYS, never_defined
```

Result:

```text
file.moleasm:1: undefined branch target: 'never_defined'
```

Fix: define the label, or pick the right name.

## `Kind::Range` --- numeric range violations

### Branch offset out of range

```text
BRANCH_ON ALWAYS, faraway
; ... 200 instructions ...
faraway:
    HALT
```

Result:

```text
file.moleasm:1: BRANCH_ON offset 514 out of signed 10-bit range
                (branch_pc=0)
```

Fix: replace the `BRANCH_ON` with a `JMP faraway` (sugar for
`BRANCH_ON ALWAYS, faraway`, but still subject to the same
signed-10 range — so insert intermediate `JMP` hops if the
target is further than 512 instructions away), or rearrange the
program so the target is within `-512..+511`.

### Operand value too large

```text
HALT status=42
```

Result:

```text
file.moleasm:1: HALT status must be 0..30, got 42
                (31 / 0x1F is reserved as STATUS_TRAP)
```

Fix: every opcode field has a bit width; consult
[Reference](./reference.md) for the limits.

### `.dw` value out of 32-bit range

```text
.dw 0x1_0000_0000
```

Result:

```text
file.moleasm:1: .dw value 0x1_0000_0000 out of 32-bit range
```

Fix: split into two `.dw` values or shrink the constant.

### Program exceeds the 8192-word budget

The engine has 8192 program-memory slots; anything past slot 8191 is
rejected:

```text
; ... 8193 HALTs ...
```

Result:

```text
file.moleasm:8193: program exceeds 8192 instruction slots (PC overflow)
```

Fix: shrink the program, or split it across multiple frames if your
host runtime supports streaming (the current loader does not; one
frame = one program).

## `Kind::Operand` --- operand-shape problems

### Missing required key

```text
EMIT_BIT_IMM
```

Result:

```text
file.moleasm:1: missing required operand: tx=<symbol>
```

Fix: add the missing `key=`. `EMIT_BIT_IMM` requires `tx=`;
`EMIT_QUARTER_IMM` requires `sda=` and `scl=`; `EMIT_BYTE_IMM`
requires `imm=`; `MARK` requires `label=`; `LOAD_TIMING` requires
`reg=` and `divider=`.

### Unknown operand key

```text
EMIT_BIT_IMM tx=dominant fudge=1
```

Result:

```text
file.moleasm:1: unknown operand key 'fudge' (allowed: ["capture",
                "expect", "mask", "tx"])
```

Fix: typo or stray copy/paste. The allowed set is opcode-specific.

### Duplicate key

```text
EMIT_BIT_IMM tx=dominant tx=recessive
```

Result:

```text
file.moleasm:1: duplicate operand key 'tx'
```

Fix: pick one.

### Positional operand where a key/value is expected

```text
EMIT_BIT_IMM dominant
```

Result:

```text
file.moleasm:1: expected key=value operand, got positional token 'dominant'
```

Fix: opcodes with named flags want `key=value` operands; rewrite as
`EMIT_BIT_IMM tx=dominant`.

### Contradictory flag combination (`expect=X` + `mask=1`)

```text
EMIT_BIT_IMM tx=hiz expect=X mask=1
```

Result:

```text
file.moleasm:1: expect=X is don't-care and cannot be combined with
                mask=1; set an explicit expect=0|1 if you want to compare
```

Fix: set an explicit `expect=0` or `expect=1`. Don't-care literally
means "the comparison doesn't matter", so combining it with
"actively compare" is meaningless.

### Wrong arity on positional opcodes

```text
STRETCH_SCL_IMM 1 2 3
```

Result:

```text
file.moleasm:1: STRETCH_SCL_IMM takes one positional operand: n_quarters
```

Fix: drop the extras.

## `AsmError::FrameTooLarge`

Distinct from `Syntax` because [`assemble_to_frame`] /
[`frame::build_frame`] can be called with bare word slices that
don't have a source location.

Result:

```text
frame must contain 1..=8192 words, got 0
```

or

```text
frame must contain 1..=8192 words, got 9000
```

Fix: build a frame from 1..=8192 words. An empty program is
deliberately rejected (the engine has no useful behaviour when fed
zero words).

## A note on host-side errors

The CLI surfaces these errors through `color-eyre`, which adds
chained context like `failed to assemble path/to/file.moleasm`
around the underlying `AsmError`. The structured `Kind` is always
preserved on the error chain, so a library caller can still match
on it.

## When the error is not the error

A handful of bug classes look like assembler errors but are
actually elsewhere:

- **Bytecode loads but the engine never makes a sound.** Usually a
  hardware issue: SDA / SCL pulls missing, the wrong pin assigned,
  the wrong board image flashed. See `fpga/Mole/BRINGUP.md`.
- **CRC error from the loader.** Frame got mangled in transport; the
  bytecode itself is fine. Reassemble and re-send.
- **Bytecode runs but the captures are wrong.** Almost always a
  spec-side mistake (wrong tx symbol, wrong bit order, wrong bus
  mode). The assembler can't catch those: the program is
  syntactically valid moleasm even when it's semantically wrong.
  Trace through with a scope and the worked-example diagrams.

The next chapter, [Reference Tables](./reference.md), gives you the
opcodes, cond codes, bus modes, and tx symbols on one page each.
