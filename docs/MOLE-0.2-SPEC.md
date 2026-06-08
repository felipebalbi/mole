# Mole 0.2 ISA and Wire-Format Specification

**Status:** Draft. Phase B input. Wire format becomes a stable contract
after the first tagged Phase 0 encoder release; until then this document
is mutable and NOT a release contract. See §1 and §10 for the caveat in
full.

**Supersedes:** v0 ISA as documented implicitly in
`mole-asm/src/encoder.rs`, `mole-asm/tests/fixtures/mole-asm.py`, and
`mole-abi/src/lib.rs`. Those files remain authoritative for the v0
*implementation* until the v0.2 encoder rewrite lands.

---

## 1. Scope and non-goals

This specification defines:

- The **v0.2 instruction set architecture**: 32-bit fixed-width
  instructions, 6-bit opcode space, 8-entry register file, 5-stage
  pipeline.
- The **v0.2 wire format**: preamble magic, version word, length word,
  instruction stream layout, HALT-word bit positions.
- The **moleasm v0.2 grammar**: canonical syntax, sugar forms, and
  assembler error conditions.
- The **ABI constants** that must be frozen in `mole-abi` by Phase B5
  before any encoder or loader implementation may ship.

This specification does **not** define:

- The host-to-Mole UART framing envelope (length prefix + CRC-16/XMODEM).
  That layer is unchanged from v0; see `mole-asm/tests/fixtures/mole-asm.py`
  `build_frame()`.
- The result ring record format (CAPTURE, MARK, HALT tags in the result
  stream). That is unchanged from v0; see `mole-abi/src/lib.rs`.
- SpinalHDL implementation details. Those live in `fpga/Mole/`.
- The Scheme SDK (Layer 1). The SDK compiles down to the bytecode defined
  here; its own spec is out of scope.

### Pre-Phase-0 mutability caveat

The v0.2 wire format is **NOT** a stable contract until Phase 0 ships
its first tagged encoder release. Until that tag exists, any field in
the instruction encoding or wire-format preamble may change without a
version bump. The golden fixtures under `mole-asm/tests/fixtures/` are
proof of byte-stability *within* a build, not across revisions. Do not
deploy v0.2 bytecode against v0 engine firmware; they are not
cross-compatible.

---

## 2. Top-level architecture

### Layer model

- **Layer 0 (engine):** An FPGA bit-cycle engine that executes the ISA
  defined here. It has zero protocol knowledge. It does not know what
  "I2C" or "I3C" means. `BUS_MODE` owns the symbol-to-electrical
  mapping; the ISA owns nothing else protocol-specific.
- **Layer 1 (SDK):** A Scheme host library that compiles I3C/I2C
  protocol behaviour into Layer-0 bytecode. Spec-compliant primitives
  live in `i2c/`, `i3c/`, `ccc/`, `hdr-ddr/` SDK namespaces. Raw
  bit-level escapes live in `raw/` and require explicit opt-in.

### Clock domains

| Domain      | Nominal     | Fallback         | Notes                              |
|-------------|-------------|------------------|------------------------------------|
| `engineClk` | 48 MHz      | 36/32 MHz UP5K   | Quarter-bit clock; fabric clock IS |
|             |             | 48–72 MHz ECP5   | the quarter-bit clock (no PLL mul) |
| `uartClk`   | 24 MHz      | —                | Fixed; drives UART baud generator  |

Cross-domain FIFOs use SpinalHDL `StreamFifoCC` only. No combinational
signals cross clock domains.

### UART defaults

| Baud rate | Oversampling | Use                          |
|-----------|-------------|------------------------------|
| 2 Mbaud   | 8×          | Default at 48 MHz engineClk  |
| 1 Mbaud   | 16×         | Fallback at 48 MHz or below  |

### Pipeline

Five stages: **F**etch → **D**ecode → **R**egRead → **E**xecute →
**W**riteback. Implemented in Phase C using SpinalHDL
`spinal.lib.pipeline.Pipeline`. See §14 for hazard resolution rules.

### Register file

8 entries, R0–R7, each 32 bits wide. R7 is the canonical capture-result
register. R6 is the conventional loop counter (used by `LOAD_LOOP`
sugar). See §8 for full register-file spec.

### `tx_symbol` vocabulary

The only per-bit drive vocabulary. Two bits, four encodings:

| Encoding | Name       | Meaning                                     |
|----------|------------|---------------------------------------------|
| `0b00`   | `dominant` | Active-drive low (OD) or active PP drive    |
| `0b01`   | `recessive`| Release / Hi-Z + pull (OD) or active high  |
| `0b10`   | `hiz`      | Tri-state (neither driving nor pulling)     |
| `0b11`   | reserved   | Held for v0.5 `raw_override`; encoder error |

Short forms `dom` and `rec` are accepted by the assembler wherever
`dominant` and `recessive` appear.

Do **not** reintroduce `drive_sda`, `drive_scl`, `od_low`, `od_release`,
`pp_high`, `pp_low`, `drive_high`, or `drive_enable`. Those were
removed when the engine became bus-agnostic.

---

## 3. Instruction layout

All instructions are **32-bit fixed width**, little-endian on the wire.

```text
Bit  31 30 29 28 27 26 25 24 23 22 21 20 19 18 17 16
     [  group  ][      sub      ][  dst  ][  src  ][aux
      31:30      29:26            25:23    22:20    19:17

Bit  15 14 13 12 11 10  9  8  7  6  5  4  3  2  1  0
     ...aux][           payload (14 bits)           ][flg]
      19:17   16:3                                    2:0
```

Field summary:

| Field     | Bits    | Width | Description                              |
|-----------|---------|-------|------------------------------------------|
| `group`   | `[31:30]` | 2   | Opcode group (see §4)                    |
| `sub`     | `[29:26]` | 4   | Sub-opcode within group (see §4)         |
| `dst`     | `[25:23]` | 3   | Destination register R0–R7               |
| `src`     | `[22:20]` | 3   | Source register R0–R7                    |
| `aux`     | `[19:17]` | 3   | Auxiliary register R0–R7                 |
| `payload` | `[16: 3]` | 14  | Immediate, offset, mask, or opcode-spec  |
| `flags`   | `[ 2: 0]` | 3   | Flag triple (see below)                  |

**Opcode** is `{group, sub}`: 6 bits, 64 slots, 4 groups of 16
sub-opcodes each.

**Flag triple** at `[2:0]` is present on WIRE opcodes that carry
SDA/SCL samples. Bit positions are fixed:

| Bit | Name      | Meaning                                         |
|-----|-----------|--------------------------------------------------|
| `[2]` | `expect` | Expected wire value (0=dominant, 1=recessive)  |
| `[1]` | `mask`   | 1 = compare sampled bit against `expect`       |
| `[0]` | `capture`| 1 = write sampled bit to R7                    |

On **all non-bearer opcodes** `[2:0]` is reserved-must-be-zero. The
hardware decoder checks this bit-triple on every instruction not in the
WIRE-bearer set; reserved-non-zero encodes to a trap. Bearer opcodes ---
EMIT_BIT_IMM, EMIT_BIT_REG, EMIT_QUARTER_IMM, EMIT_QUARTER_REG,
EMIT_BYTE, SAMPLE_BIT_ON_SCL, DRIVE_BIT_ON_SCL --- use `[2:0]` for the
flag triple as documented above. All other opcodes (every CTRL opcode,
every DATA opcode, plus STRETCH_SCL_IMM and STRETCH_SCL_REG in the WIRE
group) have `[2:0]` = reserved-must-be-zero.

The `dst [25:23]`, `src [22:20]`, and `aux [19:17]` fields are
*positional conventions* for register operands. The DATA group uses all
three uniformly. WIRE opcodes that take a register operand reuse the
corresponding bit positions (e.g. EMIT_BIT_REG reads `src`;
SAMPLE_BIT_ON_SCL writes `dst`). CTRL and DATA opcodes may reuse the
`dst`, `src`, and `aux` positions for non-register-file payloads when
the per-opcode encoding in §5 documents the reuse. Examples:
LOAD_TIMING uses `[19:17]` for its timing-register selector; SHIFT uses
`[19]` for `arith` and `[18]` for `dir`. Each per-opcode encoding in §5
explicitly states which bit positions are in use. Fields not listed in
an opcode's encoding are reserved-must-be-zero on that opcode.

---

## 4. Opcode groups

### 4.1 WIRE group (`group = 0b00`)

Wire-emission and sampling opcodes. Nine live sub-opcodes; seven
reserved.

| Sub (`[29:26]`) | Mnemonic           | Live? |
|------------------|--------------------|-------|
| `0b0000`         | EMIT_BIT_IMM       | yes   |
| `0b0001`         | EMIT_BIT_REG       | yes   |
| `0b0010`         | EMIT_QUARTER_IMM   | yes   |
| `0b0011`         | EMIT_QUARTER_REG   | yes   |
| `0b0100`         | EMIT_BYTE          | yes   |
| `0b0101`         | SAMPLE_BIT_ON_SCL  | yes   |
| `0b0110`         | DRIVE_BIT_ON_SCL   | yes   |
| `0b0111`         | STRETCH_SCL_IMM    | yes   |
| `0b1000`         | STRETCH_SCL_REG    | yes   |
| `0b1001`–`0b1111`| (reserved)         | no    |

### 4.2 CTRL group (`group = 0b01`)

Control-flow and configuration opcodes. Eight live sub-opcodes; eight
reserved.

| Sub (`[29:26]`) | Mnemonic     | Live? |
|------------------|--------------|-------|
| `0b0000`         | HALT         | yes   |
| `0b0001`         | BRANCH_ON    | yes   |
| `0b0010`         | WAIT_ON      | yes   |
| `0b0011`         | SET_BUS_MODE | yes   |
| `0b0100`         | SET_ROLE     | yes   |
| `0b0101`         | FLAG_CLEAR   | yes   |
| `0b0110`         | MARK         | yes   |
| `0b0111`         | LOAD_TIMING  | yes   |
| `0b1000`–`0b1111`| (reserved)  | no    |

**CTRL named-field summary** (below the 6-bit opcode = bits `[25:0]`):

| sub  | Mnemonic     | Named bits used                              | Reserved bits       |
|------|--------------|----------------------------------------------|---------------------|
| 0    | HALT         | `[7:3]` status                               | `[25:8]`, `[2:0]`   |
| 1    | BRANCH_ON    | `[16:13]` cond, `[12:3]` offset              | `[25:17]`, `[2:0]`  |
| 2    | WAIT_ON      | `[16:13]` cond, `[12:3]` timeout             | `[25:17]`, `[2:0]`  |
| 3    | SET_BUS_MODE | `[6:3]` mode                                 | `[25:7]`, `[2:0]`   |
| 4    | SET_ROLE     | `[3]` role                                   | `[25:4]`, `[2:0]`   |
| 5    | FLAG_CLEAR   | `[7:3]` mask                                 | `[25:8]`, `[2:0]`   |
| 6    | MARK         | `[16:3]` label                               | `[25:17]`, `[2:0]`  |
| 7    | LOAD_TIMING  | `[19:17]` reg, `[16:3]` divider              | `[25:20]`, `[2:0]`  |

Each row uses exactly 26 bits below the 6-bit opcode prefix, totalling
32 bits per instruction. Rows can be verified: named-bit count +
reserved-bit count = 26.

### 4.3 DATA group (`group = 0b10`)

Register-file ALU and load opcodes. Eight live sub-opcodes; eight
reserved.

| Sub (`[29:26]`) | Mnemonic  | Live? |
|------------------|-----------|-------|
| `0b0000`         | LOAD_IMM  | yes   |
| `0b0001`         | MOV       | yes   |
| `0b0010`         | ADD_IMM   | yes   |
| `0b0011`         | DEC       | yes   |
| `0b0100`         | AND_IMM   | yes   |
| `0b0101`         | OR_IMM    | yes   |
| `0b0110`         | XOR_IMM   | yes   |
| `0b0111`         | SHIFT     | yes   |
| `0b1000`–`0b1111`| (reserved)| no   |

### 4.4 LOOP group (`group = 0b11`) — reserved

All 16 sub-codes are reserved. No live opcodes. Programs that encode
a LOOP-group instruction trigger `STATUS_TRAP` on the engine. The
assembler rejects all LOOP-group mnemonics with error E-LEX-003.

---

## 5. Per-opcode semantics

Each opcode's per-encoding table in this section is authoritative for
its sub-code. Where a field is omitted from the table, it is
reserved-must-be-zero on that opcode. Field layouts in CTRL sub-sections
(§5.10–§5.17) and in register-operand WIRE sub-sections override the
default `dst/src/aux/payload` partition from §3 wherever the per-opcode
table specifies different bit assignments.

Fields not listed in a per-opcode encoding table are
reserved-must-be-zero unless stated otherwise. A non-zero reserved
field causes the engine to halt with `STATUS_TRAP`.

### 5.1 WIRE.EMIT_BIT_IMM

Emit one SDA bit with `tx_symbol` encoded directly in the instruction.
The SCL pulse is generated by the WIRE sub-block.

**Encoding:**

```text
[31:30] group  = 0b00
[29:26] sub    = 0b0000
[25: 5] reserved = 0
[ 4: 3] tx     (2 bits: dominant=00, recessive=01, hiz=10)
[ 2: 0] flags  (expect[2], mask[1], capture[0])
```

**Operands (moleasm):**

```moleasm
EMIT_BIT_IMM tx=dominant
EMIT_BIT_IMM tx=recessive expect=0 mask=1 capture=1
EMIT_BIT_IMM tx=hiz expect=1 mask=1
```

**Stage behaviour:** D decodes tx and flags. E drives SDA for one
full-bit period, sampling SDA at the centre quarter if `capture=1`
(result written to R7 in W). `expect`/`mask` comparison sets
`MISMATCH_FLAG` in E.

**Flag effects:** If `mask=0`, this opcode does not write `MISMATCH_FLAG`;
the prior sticky value is preserved. If `mask=1`, `MISMATCH_FLAG` is
set when the sampled bit differs from `expect`, and *cleared* when they
match. A program that needs per-operation observability of
`MISMATCH_FLAG` must either (a) consume the flag with `BRANCH_ON
MISMATCH` (or `BRANCH_ON NOT_MISMATCH`) immediately after the
bearer instruction, before any other bearer with `mask=1` runs, or (b)
clear the flag with `FLAG_CLEAR mask=0b00001` (bit 0 set) between
bearer instructions so the next `mask=1` operation writes to a
known-fresh state. `capture=1` writes sampled SDA bit into R7[0] in W
(R7[31:1] zeroed).

**Errors:** `tx=0b11` (reserved) → E-WIRE-001. `expect=X` combined with
`mask=1` → E-OP-005.

---

### 5.2 WIRE.EMIT_BIT_REG

Emit one SDA bit with `tx_symbol` read from a register at runtime.

**Encoding:**

```text
[31:30] group  = 0b00
[29:26] sub    = 0b0001
[25:23] reserved = 0
[22:20] src    (3 bits: register R0–R7; low 2 bits used as tx_symbol)
[19: 3] reserved = 0
[ 2: 0] flags  (expect[2], mask[1], capture[0])
```

The engine takes `R[src][1:0]` as the `tx_symbol`. If `R[src][1:0]
= 0b11` at runtime, the engine halts with `STATUS_TRAP`.

**Operands (moleasm):**

```moleasm
EMIT_BIT_REG src=R0
EMIT_BIT_REG src=R2 capture=1
```

**Stage behaviour:** R reads `R[src]`. E drives SDA using the low 2
bits; remainder of semantics identical to EMIT_BIT_IMM.

**Flag effects:** Same as EMIT_BIT_IMM.

**Pipeline note:** Load-use hazard: `LOAD_IMM Rx; EMIT_BIT_REG src=Rx`
requires one stall cycle. See §14.

**Errors:** Runtime `R[src][1:0] = 0b11` → engine trap STATUS_TRAP.

---

### 5.3 WIRE.EMIT_QUARTER_IMM

Emit one quarter-bit period with independent SDA and SCL symbols
encoded directly in the instruction.

**Encoding:**

```text
[31:30] group  = 0b00
[29:26] sub    = 0b0010
[25: 7] reserved = 0
[ 6: 5] scl    (2 bits: tx_symbol for SCL)
[ 4: 3] sda    (2 bits: tx_symbol for SDA)
[ 2: 0] flags  (expect[2], mask[1], capture[0])
```

**Operands (moleasm):**

```moleasm
EMIT_QUARTER_IMM sda=recessive scl=recessive
EMIT_QUARTER_IMM sda=dominant  scl=hiz
EMIT_QUARTER_IMM sda=hiz scl=dominant expect=1 mask=1 capture=1
```

**Stage behaviour:** D decodes SDA/SCL fields. E drives both lines for
exactly one quarter-bit period (one `engineClk` cycle). SDA is sampled
at the end of the quarter when `capture=1` (written to R7 in W).

**Flag effects:** If `mask=0`, this opcode does not write `MISMATCH_FLAG`;
the prior sticky value is preserved. If `mask=1`, `MISMATCH_FLAG` is
set when the sampled bit differs from `expect`, cleared when they match.

**Target-role constraint (AGENTS §3.13):** When active `BUS_MODE` is a
PP class (`i3c-PP` or `hdr-ddr`) and the current role is target, SCL
`scl=recessive` is illegal — that is active PP-high drive of SCL from
the target, which violates the I3C spec. The assembler rejects this
combination at assembly time (E-WIRE-002). The engine ignores it as
defence-in-depth (emits `scl=hiz` instead). `scl=dominant` (stretch /
fuzz) and `scl=hiz` (release) are always legal in target role.

**Errors:** `sda=0b11` or `scl=0b11` → E-WIRE-001. Target-role PP
`scl=recessive` → E-WIRE-002.

---

### 5.4 WIRE.EMIT_QUARTER_REG

Emit one quarter-bit period with SDA and SCL symbols read from a
register at runtime.

**Encoding:**

```text
[31:30] group  = 0b00
[29:26] sub    = 0b0011
[25:23] reserved = 0
[22:20] src    (3 bits: register R0–R7)
[19: 3] reserved = 0
[ 2: 0] flags  (expect[2], mask[1], capture[0])
```

`R[src][3:2]` = SCL symbol; `R[src][1:0]` = SDA symbol.
Runtime reserved-encoding check applies to both symbol fields.

**Operands (moleasm):**

```moleasm
EMIT_QUARTER_REG src=R3
EMIT_QUARTER_REG src=R1 capture=1
```

**Stage behaviour:** R reads `R[src]`. E extracts SDA/SCL from bit
fields as above; remainder identical to EMIT_QUARTER_IMM.

**Flag effects:** Same as EMIT_QUARTER_IMM.

**Errors:** Runtime reserved encoding in SDA or SCL symbol field → STATUS_TRAP.

---

### 5.5 WIRE.EMIT_BYTE

Emit eight consecutive SDA bits from R7[7:0], MSB first, each with a
full SCL pulse. Equivalent to eight sequential EMIT_BIT_IMM operations
reading successive bits of R7[7:0] with `tx_symbol` derived from each
bit: 0 → dominant, 1 → recessive.

**Encoding:**

```text
[31:30] group  = 0b00
[29:26] sub    = 0b0100
[25: 3] reserved = 0
[ 2: 0] flags  (expect[2], mask[1], capture[0])
```

The flags apply to the **ninth bit** (ACK/NAK slot): after the eighth
data bit the engine clocks one more SCL pulse with SDA driven to `hiz`,
sampling the line against `expect`/`mask`/`capture`. This means
`capture=1` on EMIT_BYTE captures the ACK/NAK into R7[0] (and the data
byte is consumed from R7 before the ACK sample overwrites it; if the
caller needs the data byte after ACK capture, copy it before issuing
EMIT_BYTE).

**Operands (moleasm):**

```moleasm
EMIT_BYTE
EMIT_BYTE expect=0 mask=1 capture=1
```

**Stage behaviour:** D decodes flags. E iterates eight SCL pulses
shifting out R7[7:0] MSB first, then one ACK-slot pulse with `hiz` SDA.
Total: 9 bit periods × 4 quarter-bit clocks per bit = 36 quarter-bit
clocks. This is an atomic multi-cycle E-stage operation; no other
pipeline stages advance during the loop.

**Flag effects:** If `mask=0`, this opcode does not write `MISMATCH_FLAG`;
the prior sticky value is preserved. If `mask=1`, `MISMATCH_FLAG` is
written on the ACK slot (set if sampled bit ≠ `expect`, cleared
otherwise). `capture=1` writes ACK sample to R7[0]; R7[31:1] zeroed.

**ACK pairing rule:** Every `EMIT_BYTE` with `mask=1` MUST be followed
textually (in program-counter order, ignoring labels) by one of:

1. `BRANCH_ON MISMATCH, <target>` — to handle a NAK, OR
2. `FLAG_CLEAR <mask>` where bit 0 of `<mask>` is set (i.e.
   `mask=0b00001` or any mask with bit 0 = 1) — to discard the ACK
   result.

`EMIT_BYTE` with `mask=0` (fire-and-forget transmit, typical of probe
traffic where ACK is intentionally ignored) is exempt from this
requirement; no follow-up is required.

Labels between the EMIT_BYTE and the pairing instruction do not break
the pairing analysis (the assembler walks past labels). A program
declaring `(use-raw-primitives)` at the top of the source is exempt
from this check regardless of mask; the engine performs no enforcement.

---

### 5.6 WIRE.SAMPLE_BIT_ON_SCL

Target-role opcode. Slave to the external controller's SCL: wait for
SCL to go high, then sample SDA.

**Encoding:**

```text
[31:30] group  = 0b00
[29:26] sub    = 0b0101
[25:23] dst    (3 bits: must be R7; assembler errors otherwise
                unless raw/ pragma)
[22: 3] reserved = 0
[ 2: 0] flags  (expect[2], mask[1], capture[0])
```

**Operands (moleasm):**

```moleasm
SAMPLE_BIT_ON_SCL capture=1
SAMPLE_BIT_ON_SCL expect=0 mask=1 capture=1
```

**Stage behaviour:** D decodes flags. E waits for the rising edge of
the external SCL, then samples SDA. The sampled value is written to
R7[0] in W (R7[31:1] zeroed) when `capture=1`.

**Flag effects:** If `mask=0`, this opcode does not write `MISMATCH_FLAG`;
the prior sticky value is preserved. If `mask=1`, `MISMATCH_FLAG` is
set when sample ≠ `expect`, cleared when they match.

**Role/mode dispatch:** This is a target-role opcode. When the engine
is in controller role at dispatch time, the opcode is treated as a
no-op: no wire activity occurs and no flags are updated. The assembler
does not currently check role/mode consistency at assembly time
(that would require dataflow analysis across SET_ROLE; future work).

**raw/ override:** Under the `(use-raw-primitives)` pragma, the engine
respects the encoded `dst` field — the capture is written to `R[dst]`,
not necessarily R7. The assembler suppresses E-REG-001 in this mode;
the engine performs no runtime check.

**Errors:** `dst` field other than R7 without raw/ pragma → E-REG-001.

---

### 5.7 WIRE.DRIVE_BIT_ON_SCL

Target-role opcode. Drive SDA with the given `tx_symbol` and slave to
the external controller's SCL pulse.

**Encoding:**

```text
[31:30] group  = 0b00
[29:26] sub    = 0b0110
[25:23] dst    (3 bits: must be R7 when capture=1; assembler errors
                otherwise unless raw/ pragma)
[22:20] reserved = 0
[19:17] reserved = 0
[16: 5] reserved = 0
[ 4: 3] tx     (2 bits: tx_symbol for SDA)
[ 2: 0] flags  (expect[2], mask[1], capture[0])
```

**Operands (moleasm):**

```moleasm
DRIVE_BIT_ON_SCL tx=dominant
DRIVE_BIT_ON_SCL tx=hiz expect=0 mask=1 capture=1
```

**Stage behaviour:** D decodes tx and flags. E drives SDA to `tx` and
waits for the external SCL pulse; SDA is released after the SCL
falling edge. If `capture=1`, SDA is sampled at the SCL midpoint and
written to R7[0] in W.

**Flag effects:** If `mask=0`, this opcode does not write `MISMATCH_FLAG`;
the prior sticky value is preserved. If `mask=1`, `MISMATCH_FLAG` is
set when the sampled bit differs from `expect`, cleared when they match.

**Role/mode dispatch:** This is a target-role opcode. When the engine
is in controller role at dispatch time, the opcode is treated as a
no-op: no wire activity occurs and no flags are updated. The assembler
does not currently check role/mode consistency at assembly time
(that would require dataflow analysis across SET_ROLE; future work).

**raw/ override:** Under the `(use-raw-primitives)` pragma, the engine
respects the encoded `dst` field for the capture destination. The
assembler suppresses E-REG-001 in this mode; the engine performs no
runtime check.

**Errors:** `tx=0b11` → E-WIRE-001. `capture=1` with dst ≠ R7 without
raw/ → E-REG-001. When `capture=0`, the `dst` field is ignored by the
engine. The assembler emits `dst=0b000` (R0) as the canonical
no-capture encoding; encoders MUST emit this exact value for
bit-identical golden fixtures.

---

### 5.8 WIRE.STRETCH_SCL_IMM

Hold SCL low for a fixed number of additional quarter-bit periods (clock
stretching). Legal in both controller and target role.

**Encoding:**

```text
[31:30] group  = 0b00
[29:26] sub    = 0b0111
[25:17] reserved = 0
[16: 3] n_quarters (14 bits, unsigned, 0..16383)
[ 2: 0] reserved = 0
```

**Operands (moleasm):**

```moleasm
STRETCH_SCL_IMM 4
STRETCH_SCL_IMM 400
```

**Stage behaviour:** E pulls SCL dominant for `n_quarters` additional
engineClk cycles before releasing. `n_quarters = 0` is a no-op.

**Flag effects:** None.

---

### 5.9 WIRE.STRETCH_SCL_REG

Hold SCL low for a number of quarter-bit periods taken from a register.

**Encoding:**

```text
[31:30] group  = 0b00
[29:26] sub    = 0b1000
[25:23] reserved = 0
[22:20] src    (3 bits: register R0–R7; R[src][13:0] used as count)
[19: 3] reserved = 0
[ 2: 0] reserved = 0
```

**Operands (moleasm):**

```moleasm
STRETCH_SCL_REG src=R2
```

**Stage behaviour:** R reads `R[src]`. E uses `R[src][13:0]` as the
stretch count; behaviour otherwise identical to STRETCH_SCL_IMM.

**Flag effects:** None.

---

### 5.10 CTRL.HALT

Field layouts in this section override the default `dst/src/aux/payload`
partition from §3.

Stop execution. The engine writes a HALT word to the result ring and
halts the program counter. See §11 for the HALT-word layout.

**Encoding:**

```text
[31:30] group    = 0b01
[29:26] sub      = 0b0000
[25: 8] reserved = 0  (18 bits)
[ 7: 3] status   (5 bits, 0x00..0x1F)
[ 2: 0] reserved = 0
```

**Note on result-ring layout:** The result-ring HALT word (§11) uses
different bit positions for status and the overflow/mismatch latches.
The instruction's status field at `[7:3]` is the *source* of the
result-word's status field at `[27:23]`; the engine's W stage performs
the position shift when committing the result-ring HALT record. The
result-ring overflow latch at `[29]` and mismatch snapshot at `[28]`
are distinct fields — they are not the same bits as anything in this
instruction encoding.

User programs should use `status` values in `0x00..0x1C` (29 user-
defined halt codes). Values `0x1D`–`0x1E` are reserved; the loader
rejects programs that emit them. Value `0x1F` is the engine trap.

**Operands (moleasm):**

```moleasm
HALT
HALT status=0
HALT status=5
```

Default: `status=0` when omitted.

**Stage behaviour:** F/D/R pass through. E snapshots the current
`MISMATCH_FLAG` into the result-ring HALT word's mismatch field at
`[28]`, and latches any pending result-ring overflow into `[29]`, then
writes the HALT word to the result ring. W halts the PC.

**Flag effects:** None written. All sticky flags are snapshot-read and
preserved.

---

### 5.11 CTRL.BRANCH_ON

Field layouts in this section override the default `dst/src/aux/payload`
partition from §3.

Conditional PC-relative branch. Shares the 4-bit condition-code
namespace with WAIT_ON (see §6). Operand is a signed 10-bit PC-relative
offset (-512..511).

**Encoding:**

```text
[31:30] group    = 0b01
[29:26] sub      = 0b0001
[25:17] reserved = 0
[16:13] cond     (4 bits: condition code 0..15)
[12: 3] offset   (10 bits, signed two's-complement, -512..511)
[ 2: 0] reserved = 0
```

**Branch target PC calculation:**

```
next_pc = branch_pc + 1 + offset
```

Offset is computed at assembly time for label targets:
`offset = label_pc - branch_pc - 1`.

**Operands (moleasm):**

```moleasm
BRANCH_ON MISMATCH, nak_label
BRANCH_ON NOT_MISMATCH, ok_label
BRANCH_ON REG_ZERO, loop_top
BRANCH_ON ALWAYS, retry
```

**Stage behaviour:** D decodes cond and offset. E evaluates the
condition against the sticky-flag set and the REG_ZERO_FLAG (see §7).
If true, the PC is updated to `branch_pc + 1 + offset`; otherwise
execution falls through.

**Flag effects:** None written.

**Errors:** Offset out of −512..511 → E-RNG-003. Unknown cond-code
token → E-LEX-004. Reserved cond-code value 12–15 → E-CTRL-001.
Unresolved label → E-SYM-003.

---

### 5.12 CTRL.WAIT_ON

Field layouts in this section override the default `dst/src/aux/payload`
partition from §3.

Spin-wait until a condition is true or a timeout expires. Shares the
4-bit condition-code namespace with BRANCH_ON (see §6). Operand is an
unsigned 10-bit quarter-bit-clock timeout count (0 = wait forever).

**Encoding:**

```text
[31:30] group    = 0b01
[29:26] sub      = 0b0010
[25:17] reserved = 0
[16:13] cond     (4 bits: condition code 0..15)
[12: 3] timeout  (10 bits, unsigned, 0..1023 quarter-bit periods;
                  0 = infinite wait)
[ 2: 0] reserved = 0
```

**Operands (moleasm):**

```moleasm
WAIT_ON SDA_LOW, 128
WAIT_ON START_SEEN, 0
WAIT_ON SCL_HIGH, 64
```

**Stage behaviour:** E polls the condition every engineClk cycle.
If the condition becomes true before the timeout, execution proceeds.
If the timeout expires first, `TIMEOUT_FLAG` is set. A `timeout = 0`
polls indefinitely (no timeout).

**Flag effects:** `TIMEOUT_FLAG` set on timeout expiry; cleared when
the condition is met first. `START_FLAG` / `STOP_FLAG` are set by the
engine asynchronously when start/stop conditions are detected on the
bus; WAIT_ON consumes (reads) them but does not clear them — clearing
requires FLAG_CLEAR.

**Errors:** Reserved cond-code 12–15 → E-CTRL-001. Timeout out of
0..1023 → E-RNG-004.

---

### 5.13 CTRL.SET_BUS_MODE

Field layouts in this section override the default `dst/src/aux/payload`
partition from §3.

Load the `BUS_MODE` register with a 4-bit immediate. See §9 for the
full BUS_MODE encoding table.

**Encoding:**

```text
[31:30] group  = 0b01
[29:26] sub    = 0b0011
[25: 7] reserved = 0
[ 6: 3] mode   (4 bits: 0b0000..0b0011 live; 0b0100..0b1111 reserved)
[ 2: 0] reserved = 0
```

**Operands (moleasm):**

```moleasm
SET_BUS_MODE i2c
SET_BUS_MODE i3c-OD
SET_BUS_MODE i3c-PP
SET_BUS_MODE hdr-ddr
```

**Stage behaviour:** E writes the 4-bit mode value to the `BUS_MODE`
register. The new mapping takes effect from the next instruction
executed.

**Flag effects:** None.

**Errors:** Unknown bus-mode name → E-OP-006. Reserved mode value
`0b0100`–`0b1111` in `.dw` raw injection → engine trap STATUS_TRAP.
The `mode` operand MUST be one of the named identifiers (`i2c`,
`i3c-OD`, `i3c-PP`, `hdr-ddr`); numeric literals are rejected with
E-OP-006 (unknown name). Programs needing to set a reserved mode value
must declare `(use-raw-primitives)` and use a `.dw` directive.

---

### 5.14 CTRL.SET_ROLE

Field layouts in this section override the default `dst/src/aux/payload`
partition from §3.

Switch the engine between controller (0) and target (1) roles at
runtime. Power-on default is read from `MoleConfig.role`.

**Encoding:**

```text
[31:30] group    = 0b01
[29:26] sub      = 0b0100
[25: 4] reserved = 0
[ 3]    role     (1 bit: 0=controller, 1=target)
[ 2: 0] reserved = 0
```

**Operands (moleasm):**

```moleasm
SET_ROLE controller
SET_ROLE target
```

**Stage behaviour:** E writes the role bit. The engine dispatches
role-sensitive opcodes (SAMPLE_BIT_ON_SCL, DRIVE_BIT_ON_SCL) only
when the role bit is set to target; when role is controller those
opcodes execute as no-ops.

**Flag effects:** None.

**Errors:** Operand not `controller` or `target` → E-OP-008.

---

### 5.15 CTRL.FLAG_CLEAR

Field layouts in this section override the default `dst/src/aux/payload`
partition from §3.

Explicitly clear a subset of the sticky flags (see §7). The operand
is a 5-bit mask selecting which flags to clear.

**Encoding:**

```text
[31:30] group  = 0b01
[29:26] sub    = 0b0101
[25: 8] reserved = 0
[ 7: 3] mask   (5 bits; bit N=1 clears flag N; see §7)
[ 2: 0] reserved = 0
```

**Operands (moleasm):**

```moleasm
FLAG_CLEAR 0b00001         ; clear MISMATCH_FLAG only
FLAG_CLEAR 0b11111         ; clear all five sticky flags
FLAG_CLEAR 0b00110         ; clear TIMEOUT_FLAG and START_FLAG
```

**Stage behaviour:** E applies the mask to the flag register:
`flags = flags & ~mask`.

**Flag effects:** Clears exactly the flags whose bit position in the
5-bit mask is 1. See §7 for the bit-to-flag mapping.

**Errors:** mask out of 0..31 (5-bit range) → E-OP-009.

---

### 5.16 CTRL.MARK

Field layouts in this section override the default `dst/src/aux/payload`
partition from §3.

Write a 3-word MARK record to the result ring, stamped with the current
free-running quarter-bit timestamp. Used by the host to correlate
program positions with capture timestamps.

**Encoding:**

```text
[31:30] group    = 0b01
[29:26] sub      = 0b0110
[25:17] reserved = 0
[16: 3] label    (14 bits, unsigned, 0..16383)
[ 2: 0] reserved = 0
```

**Operands (moleasm):**

```moleasm
MARK label=42
MARK label=0
```

**Stage behaviour:** F/D/R/E pass through. W stage atomically commits a
3-word record to the result ring:

1. Header word: `tag=0b10` (MARK) at `[31:30]`, label in low bits.
2. `ts_lo`: low 16 bits of the free-running quarter-bit timestamp.
3. `ts_hi`: high 16 bits of the free-running quarter-bit timestamp.

No other ring writer runs during this 3-word commit window.

**Flag effects:** None.

**Errors:** label out of 0..16383 → E-RNG-001. Result-ring overflow
during commit latches the overflow bit in the HALT word at next HALT.

**ABI constants:**

```rust
pub const RECORD_TAG_MARK: u8       = 0b10;
pub const RECORD_WIDTH_MARK: usize  = 3;
```

---

### 5.17 CTRL.LOAD_TIMING

Field layouts in this section override the default `dst/src/aux/payload`
partition from §3.

Write a 14-bit divider value to one of 8 timing registers in the WIRE
sub-block. Allows runtime selection of bus timing without re-programming.

**Encoding:**

```text
[31:30] group    = 0b01
[29:26] sub      = 0b0111
[25:20] reserved = 0
[19:17] reg      (3 bits: timing-register selector 0..7)
[16: 3] divider  (14 bits, unsigned, 0..16383)
[ 2: 0] reserved = 0
```

Note: `reg` reuses the aux field position `[19:17]` but is NOT a
register-file index — it indexes one of 8 named timing registers in
the WIRE sub-block.

**Timing register map:**

| `reg` | BUS_MODE consulted | Timing knob   | Description                        |
|-------|--------------------|---------------|------------------------------------|
| 0     | `i2c`              | tQB_OD        | Quarter-bit clock divider, OD      |
| 1     | `i2c`              | tHD_DAT       | Data hold time, I2C                |
| 2     | `i3c-OD`           | tQB_OD        | Quarter-bit clock divider, i3c-OD  |
| 3     | `i3c-OD`           | tSU_DAT       | Data set-up time, i3c-OD           |
| 4     | `i3c-PP`           | tQB_PP        | Quarter-bit clock divider, PP SDR  |
| 5     | `i3c-PP`           | tSU_DAT       | Data set-up time, PP SDR           |
| 6     | `hdr-ddr`          | tQB_PP        | Quarter-bit clock divider, HDR-DDR |
| 7     | `hdr-ddr`          | tSU_DAT       | Data set-up time, HDR-DDR          |

The engine reads the timing register(s) selected by the active BUS_MODE
at each emit. The assembler does NOT range-check `reg` against the active
BUS_MODE — it accepts `reg=0..7` and trusts the program to set the right
divider for the active mode (a mismatch silently affects a different mode's
timing).

**Operands (moleasm):**

```moleasm
LOAD_TIMING reg=0, divider=480   ; set i2c standard-mode OD divider
LOAD_TIMING reg=4, divider=60    ; set i3c-PP fast-mode divider
```

**Stage behaviour:** F/D/R/E pass through. W stage writes `divider` to
timing register `reg`.

**Flag effects:** None.

**Errors:** `reg > 7` → E-RNG-001. `divider > 16383` → E-RNG-001.

---

### 5.18 DATA.LOAD_IMM

Load a 14-bit zero-extended immediate into a register.

**Encoding:**

```text
[31:30] group   = 0b10
[29:26] sub     = 0b0000
[25:23] dst     (3 bits: destination register R0–R7)
[22:17] reserved = 0
[16: 3] imm14   (14 bits, unsigned, 0..16383)
[ 2: 0] reserved = 0
```

**Operands (moleasm):**

```moleasm
LOAD_IMM R0, 42
LOAD_IMM R6, 255
LOAD_IMM R7, 0x1FFF
```

**Stage behaviour:** R stage passes immediate. E zero-extends imm14 to
32 bits. W writes result to `R[dst]`.

**Flag effects:** `REG_ZERO_FLAG` written: set if result = 0, cleared
otherwise. (A `LOAD_IMM Rd, 0` sets REG_ZERO_FLAG; all other values
clear it.)

**Sugar:** `LOAD_LOOP n` assembles as `LOAD_IMM R6, n`. R6 is the
conventional loop counter register.

---

### 5.19 DATA.MOV

Copy a register value to another register.

**Encoding:**

```text
[31:30] group   = 0b10
[29:26] sub     = 0b0001
[25:23] dst     (3 bits)
[22:20] src     (3 bits)
[19: 3] reserved = 0
[ 2: 0] reserved = 0
```

**Operands (moleasm):**

```moleasm
MOV R1, R0
MOV R6, R7
```

**Stage behaviour:** R reads `R[src]`. W writes to `R[dst]`.

**Flag effects:** `REG_ZERO_FLAG` written based on the moved value.

---

### 5.20 DATA.ADD_IMM

Add a sign-extended 14-bit immediate to a register.

**Encoding:**

```text
[31:30] group   = 0b10
[29:26] sub     = 0b0010
[25:23] dst     (3 bits)
[22:20] src     (3 bits; source operand register)
[19:17] reserved = 0
[16: 3] imm14   (14 bits, signed two's-complement, -8192..8191)
[ 2: 0] reserved = 0
```

**Operands (moleasm):**

```moleasm
ADD_IMM R0, R0, 1
ADD_IMM R1, R2, -8
```

Assembler form: `ADD_IMM dst, src, imm`.

**Wire encoding note:** The 14-bit immediate is encoded as two's
complement in the `[16:3]` payload field; the engine sign-extends to
32 bits at execute. Legal range: `-8192..8191`. E-RNG-001's diagnostic
distinguishes signed vs unsigned range violations by quoting the legal
range, e.g. `ADD_IMM imm14 must be -8192..8191, got 9000`.

**Stage behaviour:** R reads `R[src]`. E computes `R[src] + sign_ext(imm14)`.
W writes to `R[dst]`.

**Flag effects:** `REG_ZERO_FLAG` set if result = 0.

---

### 5.21 DATA.DEC

Decrement a register by 1. Wraps at 0 (0 → 0xFFFFFFFF).

**Encoding:**

```text
[31:30] group   = 0b10
[29:26] sub     = 0b0011
[25:23] dst     (3 bits; dst = src: result written back to same reg)
[22:20] src     (3 bits; should equal dst; assembler warns if not)
[19: 3] reserved = 0
[ 2: 0] reserved = 0
```

**Operands (moleasm):**

```moleasm
DEC R6
DEC R0
```

Assembler encodes `DEC Rx` as `dst=src=x`.

**Stage behaviour:** R reads `R[src]`. E computes `R[src] - 1` with
unsigned 32-bit wrap. W writes to `R[dst]`.

**Flag effects:** `REG_ZERO_FLAG` set if post-decrement result = 0.
This is the flag that pairs with `BRANCH_ON REG_ZERO` for counted loops.

**Assembler lint (LINT-001):** If any DATA opcode (LOAD_IMM, MOV,
ADD_IMM, AND_IMM, OR_IMM, XOR_IMM, SHIFT) appears between a DEC and a
subsequent `BRANCH_ON REG_ZERO` or `BRANCH_ON NOT_REG_ZERO` that
consumes `REG_ZERO_FLAG`, the assembler emits a lint warning:
```
LINT-001: REG_ZERO_FLAG overwritten by <opcode> at line N
before BRANCH_ON REG_ZERO at line M; branch may never fire
```
This is a **warning** (LINT), not an error. Severity: advisory.

---

### 5.22 DATA.AND_IMM

Bitwise AND a register with a 14-bit zero-extended mask.

**Encoding:**

```text
[31:30] group   = 0b10
[29:26] sub     = 0b0100
[25:23] dst     (3 bits)
[22:20] src     (3 bits)
[19:17] reserved = 0
[16: 3] imm14   (14 bits, zero-extended mask)
[ 2: 0] reserved = 0
```

**Operands (moleasm):**

```moleasm
AND_IMM R0, R0, 0xFF
AND_IMM R3, R1, 0x3FFF
```

**Flag effects:** `REG_ZERO_FLAG` set if result = 0.

---

### 5.23 DATA.OR_IMM

Bitwise OR a register with a 14-bit zero-extended immediate.

**Encoding:** Identical layout to AND_IMM with `sub = 0b0101`.

**Operands (moleasm):**

```moleasm
OR_IMM R0, R0, 0x01
```

**Flag effects:** `REG_ZERO_FLAG` set if result = 0.

---

### 5.24 DATA.XOR_IMM

Bitwise XOR a register with a 14-bit zero-extended immediate.

**Encoding:** Identical layout to AND_IMM with `sub = 0b0110`.

**Operands (moleasm):**

```moleasm
XOR_IMM R0, R0, 0xFF
```

**Flag effects:** `REG_ZERO_FLAG` set if result = 0.

---

### 5.25 DATA.SHIFT

Logical or arithmetic shift of a register.

**Encoding:**

```text
[31:30] group   = 0b10
[29:26] sub     = 0b0111
[25:23] dst     (3 bits)
[22:20] src     (3 bits)
[19]    arith   (1 bit: 0=logical, 1=arithmetic)
[18]    dir     (1 bit: 0=left, 1=right)
[17]    reserved = 0
[16: 8] reserved = 0
[ 7: 3] shamt   (5 bits: shift amount 0..31)
[ 2: 0] reserved = 0
```

`arith` and `dir` occupy the aux field `[19:17]` (with `[17]` reserved)
so that `[2:0]` is genuinely reserved-must-be-zero on this opcode.

**Operands (moleasm):**

```moleasm
SHIFT R0, R0, left, 1       ; logical left shift by 1
SHIFT R1, R2, right, 4      ; logical right shift by 4
SHIFT R3, R3, aright, 8     ; arithmetic right shift by 8
```

Keyword `left` → `arith=0, dir=0`. `right` → `arith=0, dir=1`.
`aright` → `arith=1, dir=1`. `aleft` is an assembler error (arithmetic
left = logical left; use `left`).

**Flag effects:** `REG_ZERO_FLAG` set if result = 0.

---

## 6. Condition-code namespace

BRANCH_ON and WAIT_ON share a 4-bit condition-code field. Codes 0–11
are live; 12–15 are reserved.

| Code | Name           | Condition tested                                  |
|------|----------------|---------------------------------------------------|
| 0    | `ALWAYS`       | Unconditional (always true)                       |
| 1    | `MISMATCH`     | MISMATCH_FLAG is set                              |
| 2    | `NOT_MISMATCH` | MISMATCH_FLAG is clear                            |
| 3    | `START_SEEN`   | START_FLAG is set                                 |
| 4    | `STOP_SEEN`    | STOP_FLAG is set                                  |
| 5    | `SDA_LOW`      | SDA line is currently dominant (low)              |
| 6    | `SDA_HIGH`     | SDA line is currently recessive (high or hi-z)   |
| 7    | `SCL_HIGH`     | SCL line is currently recessive (high or hi-z)   |
| 8    | `TIMEOUT`      | TIMEOUT_FLAG is set                               |
| 9    | `NOT_TIMEOUT`  | TIMEOUT_FLAG is clear                             |
| 10   | `REG_ZERO`     | REG_ZERO_FLAG is set                              |
| 11   | `NOT_REG_ZERO` | REG_ZERO_FLAG is clear                            |
| 12–15| (reserved)     | Assembler error E-CTRL-001; engine trap STATUS_TRAP|

**Key invariant:** Adding a code in a reserved slot (12–15) is NOT a
wire-format break. Repurposing an in-use code (0–11) IS a break and
requires a version bump.

**No `SCL_LOW` condition:** There is no `SCL_LOW` code. In controller
role the engine owns SCL, so the controller already knows when SCL is
low — it does not need to wait for it. In target role, `SCL_HIGH` is
the relevant event (clock-release detection); `SCL_LOW` as a wait
condition would be redundant. A future role-aware extension could add
it in a reserved slot without a wire-format break.

**Sugar:** `JMP <label>` assembles as `BRANCH_ON ALWAYS, <label>`.

---

## 7. Sticky flags

The engine maintains five sticky flags. Flags are write-once until
cleared by an opcode that writes them or by FLAG_CLEAR.

| Bit | Name            | Set by                              | Cleared by                    |
|-----|-----------------|-------------------------------------|-------------------------------|
| 0   | `MISMATCH_FLAG` | EMIT_BIT_*, EMIT_QUARTER_*,         | Same opcode when condition     |
|     |                 | SAMPLE_BIT_ON_SCL, DRIVE_BIT_ON_SCL | not met; FLAG_CLEAR bit 0      |
| 1   | `TIMEOUT_FLAG`  | WAIT_ON on timeout expiry           | WAIT_ON on condition met;      |
|     |                 |                                     | FLAG_CLEAR bit 1               |
| 2   | `START_FLAG`    | Engine: START condition detected    | FLAG_CLEAR bit 2               |
| 3   | `STOP_FLAG`     | Engine: STOP condition detected     | FLAG_CLEAR bit 3               |
| 4   | `REG_ZERO_FLAG` | DEC, ADD_IMM, MOV, LOAD_IMM,        | Same opcode when result ≠ 0;   |
|     |                 | AND_IMM, OR_IMM, XOR_IMM, SHIFT     | FLAG_CLEAR bit 4               |

FLAG_CLEAR mask encoding: bit N of the 5-bit mask clears flag N. So
`mask = 0b00001` clears only `MISMATCH_FLAG`; `mask = 0b11111` clears
all flags.

`MISMATCH_FLAG` is sticky: once set, it remains set until explicitly
cleared by FLAG_CLEAR or until the next flag-writing opcode writes it
(which may clear or re-set it). The HALT word snapshots its state at
halt entry (see §11).

**mask=0 non-write invariant:** A flag-writing opcode with `mask=0`
(where applicable to the opcode) is treated as a non-write and does
not affect the prior sticky value of `MISMATCH_FLAG`. Specifically,
EMIT_BIT_IMM, EMIT_BIT_REG, EMIT_QUARTER_IMM, EMIT_QUARTER_REG,
EMIT_BYTE, SAMPLE_BIT_ON_SCL, and DRIVE_BIT_ON_SCL with `mask=0`
pass through without touching the flag register.

---

## 8. Register file

| Register | Alias         | Convention                                        |
|----------|---------------|---------------------------------------------------|
| R0       | —             | General purpose                                   |
| R1       | —             | General purpose                                   |
| R2       | —             | General purpose                                   |
| R3       | —             | General purpose                                   |
| R4       | —             | General purpose                                   |
| R5       | —             | General purpose                                   |
| R6       | (loop)        | Conventional loop counter; target of `LOAD_LOOP` |
| R7       | (capture)     | Canonical capture-result sink; target of samples |

**R7 constraint:** Capture-bearing opcodes (SAMPLE_BIT_ON_SCL,
DRIVE_BIT_ON_SCL, EMIT_BIT_*, EMIT_QUARTER_* with `capture=1`) write
into R7 by convention. The assembler **errors** (E-REG-001) if the user
steers a capture to any other register. Sources declaring
`(use-raw-primitives)` in the Scheme SDK, or the moleasm `raw/`-pragma
equivalent, may override this restriction.

**R6 convention:** The `LOAD_LOOP n` sugar assembles as `LOAD_IMM R6,
n`. Using R6 for purposes other than a loop counter is legal but
discouraged; the assembler does not enforce R6's role.

**Reset values:** All registers are undefined at power-on. Programs
must initialise any register before reading it. The assembler does not
track register liveness; uninitialised-read is not diagnosed.

**Width:** 32 bits each, unsigned. ALU operations wrap at 2^32.

---

## 9. BUS_MODE register

Loaded by SET_BUS_MODE. The 4-bit wire encoding:

| Value    | Name       | Class | Description                           |
|----------|------------|-------|---------------------------------------|
| `0b0000` | `i2c`      | OD    | I2C, open-drain, SDA+SCL              |
| `0b0001` | `i3c-OD`   | OD    | I3C open-drain phase (HDR exit, CCC) |
| `0b0010` | `i3c-PP`   | PP    | I3C push-pull SDR phase               |
| `0b0011` | `hdr-ddr`  | PP    | I3C HDR-DDR phase                     |
| `0b0100`–`0b1111` | (reserved) | — | Engine trap STATUS_TRAP if SET     |

**OD class behaviour:** Engine drives dominant (low) and releases
recessive (Hi-Z, external pull-up provides high). `dominant` =
active-drive low; `recessive` = Hi-Z.

**PP class behaviour:** Engine drives both dominant (active low) and
recessive (active high).

**Target-role SCL constraint (AGENTS §3.13):**

- Target role NEVER PP-drives SCL.
- EMIT_QUARTER_IMM with `scl=recessive` under `i3c-PP` or `hdr-ddr`
  in target role → assembler error E-WIRE-002; engine ignores and
  substitutes `scl=hiz` as defence-in-depth.
- `scl=dominant` (pull low: stretch / fuzz) and `scl=hiz` (release)
  are always legal in target role.
- Legal target BUS_MODEs are `i2c` and `i3c-OD`.

**`BUS_MODE` vs `tx_symbol` asymmetry (AGENTS §8):**
`BUS_MODE` is slow state (changes a handful of times per transaction).
`tx_symbol` is fast data (per-bit value). Do not fold one into the
other.

**Timing registers and BUS_MODE:** Each BUS_MODE class reads timing
from dedicated timing registers loaded by LOAD_TIMING (§5.17). See
§5.17 for the full timing-register-to-BUS_MODE mapping table. The
engine selects the applicable timing registers based on the active
BUS_MODE at each emit operation.

---

## 10. Wire format

### Pre-Phase-0 caveat

The v0.2 wire format is **NOT** a stable contract until Phase 0 ships
its first tagged encoder release. Until then any field may change
without a version bump. See §1.

### Preamble (2 words = 8 bytes)

Instructions are transmitted inside the existing host-to-Mole UART
frame (length prefix + CRC-16/XMODEM). The bytecode body within that
frame begins with a 2-word preamble:

| Word (32-bit LE) | Bytes  | Value        | Description                    |
|------------------|--------|--------------|--------------------------------|
| Word 0           | [3:0]  | `0x0002_4D4C`| Magic `"ML"` + version 0x0002  |
| Word 1           | [7:4]  | N (≤ 8192)   | Program length in 32-bit words |

**Magic:** The magic field is the u16 value `0x4D4C`; on the wire
little-endian it appears as bytes `4C 4D` (which reads as ASCII "LM"
when treated as a byte string). The loader matches on the u16 value
`0x4D4C`, not on the byte sequence. High 16 bits of word 0 = format
version `0x0002`. Full 32-bit word little-endian on wire: bytes
`4C 4D 02 00`.

**Version:** `0x0002` identifies this v0.2 specification. The v0
format had no preamble; v0.2 is not backwards-compatible.

**Length word:** Count of 32-bit instruction words in the program body.
Excludes the two preamble words. Maximum value:
`MAX_PROGRAM_WORDS = 8192`. Loader rejects frames where the length
field exceeds this limit, before writing any data to SPRAM.

### Program body

Words 2 through N+1 (N = length field): N 32-bit instructions,
each little-endian. The body MUST contain at least one instruction
word; see §13 E-FRM-003.

### Total wire frame size

```text
UART frame = UART framing envelope (length prefix + CRC)
           = 8 + 4*N bytes bytecode body
           = 2 preamble words + N instruction words
             (where N ≤ 8192)
```

### Constants (to be frozen in `mole-abi` at Phase B5)

```rust
pub const MAGIC: u32 = 0x0002_4D4C;
pub const FORMAT_VERSION: u16 = 0x0002;
pub const MAX_PROGRAM_WORDS: usize = 8192;
```

---

## 11. HALT word layout

The HALT word is a 32-bit result-ring record written by the engine when
a HALT instruction executes (or on a trap). The 2-bit tag at `[31:30]`
identifies it in the result ring.

```text
[31:30] tag         = 0b11  (HALT record; identifies this word in result ring)
[29]    overflow    (sticky overflow latch: 1 if result ring overflowed)
[28]    mismatch    (snapshot of MISMATCH_FLAG at halt entry)
[27:23] status      (5 bits, 0x00..0x1F)
[22: 0] reserved    (must be 0; loader verifies)
```

**Status partitioning (5-bit space, 32 values):**

| Range       | Count | Meaning                                          |
|-------------|-------|--------------------------------------------------|
| `0x00..0x1C`| 29    | User-defined halt codes                          |
| `0x1D..0x1E`| 2     | Reserved; loader rejects programs emitting these |
| `0x1F`      | 1     | Engine trap (malformed instruction, bad magic,   |
|             |       | unknown opcode, reserved cond-code, etc.)        |

**ABI constants:**

```rust
pub const STATUS_USER_MAX: u8       = 0x1C;
pub const STATUS_RESERVED_LOW: u8   = 0x1D;
pub const STATUS_RESERVED_HIGH: u8  = 0x1E;
pub const STATUS_TRAP: u8           = 0x1F;
```

**Invariant:** `STATUS_USER_MAX < STATUS_RESERVED_LOW <
STATUS_RESERVED_HIGH < STATUS_TRAP`.

---

## 12. moleasm grammar

### 12.1 Lexical conventions

- Source encoding: UTF-8.
- Line endings: LF (Unix). CRLF tolerated by the assembler but
  not produced by any canonical tool.
- Comments: from `;` to end of line.
- Identifiers (labels, `.equ` names):
  `[A-Za-z_][A-Za-z0-9_]*` (no embedded dashes in labels).
- Bus-mode names in SET_BUS_MODE operands allow dashes:
  `[A-Za-z_][A-Za-z0-9_-]*`. Bus-mode operand names (`i2c`,
  `i3c-OD`, `i3c-PP`, `hdr-ddr`) are case-insensitive; the
  canonical form is lowercase with hyphens as shown.
- **Mnemonics are case-insensitive in v0.2.** Canonical diagnostic
  form is UPPERCASE. The assembler normalises to uppercase internally.
- Numeric literals: decimal, `0x`/`0X` hex, `0b`/`0B` binary.
  Leading `-` for signed values.
- Register names: `R0`–`R7` or `r0`–`r7` (case-insensitive).

**raw/ pragma:** A source file that uses off-spec features (raw register
capture, reserved `tx_symbol=0b11`, suppressed EMIT_BYTE pairing checks)
must declare the pragma as the first non-comment line:

```moleasm
(use-raw-primitives)
```

This must be exactly that token — no other syntax is accepted. The
assembler raises E-RAW-001 if a raw feature is used without the pragma,
E-RAW-002 if the pragma appears after the first instruction, and
E-RAW-003 if the pragma text is malformed. A source declaring
`(use-raw-primitives)` that uses NO raw features is legal (advisory-
only, not a lint).

### 12.2 EBNF (approximate)

```text
program       ::= [ raw_pragma ] { line } EOF
raw_pragma    ::= "(use-raw-primitives)" EOL
line          ::= [ label ":" ] ( instruction | directive | "" ) comment? EOL
label         ::= ident
instruction   ::= mnemonic { operand }
directive     ::= ".equ" ident "," literal_or_equ
               |  ".dw"  literal_or_equ { "," literal_or_equ }
operand       ::= positional | key_value
positional    ::= ident | literal | shift_dir
key_value     ::= key "=" value
key           ::= "tx" | "sda" | "scl" | "expect" | "mask" | "capture"
               |  "status" | "src" | "dst" | "reg" | "divider" | "label"
shift_dir     ::= "left" | "right" | "aright"
               ; only valid as SHIFT positional argument; not a generic key
value         ::= ident | literal | "0" | "1" | "X"
literal       ::= decimal | hex | binary
decimal       ::= ["-"] digit+
hex           ::= ["-"] "0x" hexdigit+
binary        ::= ["-"] "0b" bindigit+
register      ::= ( "R" | "r" ) digit     ; digit in 0..7
comment       ::= ";" text_to_eol
```

**Constraint:** `program` must contain at least one instruction
(see §13 E-FRM-003); the grammar permits empty `program` for
editor / partial-compilation convenience but the assembler
rejects it.

### 12.3 Sugar forms

| Sugar                | Expands to              | Notes                             |
|----------------------|-------------------------|-----------------------------------|
| `JMP <label>`        | `BRANCH_ON ALWAYS, <label>` | Unconditional jump            |
| `LOAD_LOOP n`        | `LOAD_IMM R6, n`        | Prime loop counter in R6          |
| `HALT`               | `HALT status=0`         | Default status 0                  |

`LOAD_LOOP n` is sugar only. It does not expose a reg argument; the
register is always R6. If you need a different register, write
`LOAD_IMM Rx, n` directly.

### 12.4 Worked examples (one per live opcode)

Each example shows canonical moleasm source and the resulting 32-bit
word in hex.

```moleasm
; §5.1  EMIT_BIT_IMM  tx=recessive
;   group=00 sub=0000, tx=0b01 at [4:3] → 0x08
EMIT_BIT_IMM tx=recessive
; word = 0x0000_0008

; §5.2  EMIT_BIT_REG  src=R2 capture=1
;   group=00 sub=0001, src=0b010 at [22:20] → 0x0020_0000; flags=0b001
EMIT_BIT_REG src=R2 capture=1
; word = 0x0420_0001

; §5.3  EMIT_QUARTER_IMM  sda=dominant scl=hiz
;   group=00 sub=0010, scl=0b10(hiz) at [6:5]=0x40, sda=0b00(dom) at [4:3]=0
EMIT_QUARTER_IMM sda=dominant scl=hiz
; word = 0x0800_0040

; §5.4  EMIT_QUARTER_REG  src=R1
;   group=00 sub=0011, src=0b001 at [22:20] → 0x0010_0000
EMIT_QUARTER_REG src=R1
; word = 0x0C10_0000

; §5.5  EMIT_BYTE  expect=0 mask=1 capture=1
;   group=00 sub=0100, flags=0b011=3
EMIT_BYTE expect=0 mask=1 capture=1
; word = 0x1000_0003

; §5.6  SAMPLE_BIT_ON_SCL  capture=1
;   group=00 sub=0101, dst=R7 at [25:23]=0b111 → 7<<23=0x0380_0000; flags=0b001
SAMPLE_BIT_ON_SCL capture=1
; word = 0x1780_0001

; §5.7  DRIVE_BIT_ON_SCL  tx=dominant
;   group=00 sub=0110, tx=0b00(dom) at [4:3]=0
DRIVE_BIT_ON_SCL tx=dominant
; word = 0x1800_0000

; §5.8  STRETCH_SCL_IMM  400
;   group=00 sub=0111, n=400 at [16:3] → 400<<3=3200=0x0C80
STRETCH_SCL_IMM 400
; word = 0x1C00_0C80

; §5.9  STRETCH_SCL_REG  src=R0
;   group=00 sub=1000, src=0b000 at [22:20]=0
STRETCH_SCL_REG src=R0
; word = 0x2000_0000

; §5.10 HALT  status=2
;   group=01 sub=0000, status=2 at [7:3] → 2<<3=0x10
HALT status=2
; word = 0x4000_0010

; §5.11 BRANCH_ON  MISMATCH, loop_top
;   (assume loop_top is 4 words back: offset=-4)
;   New layout: group=01 sub=0001
;     cond=MISMATCH=1 at [16:13] → 1<<13 = 0x0000_2000
;     offset=-4 at [12:3]: signed 10-bit two's-comp of -4 = 0x3FC → 0x3FC<<3 = 0x1FE0
;     [25:17] reserved=0, [2:0] reserved=0
;   word bits: 0x4400_0000 | 0x0000_2000 | 0x0000_1FE0 = 0x4400_3FE0
BRANCH_ON MISMATCH, loop_top
; word = 0x4400_3FE0

; §5.12 WAIT_ON  SDA_LOW, 64
;   New layout: group=01 sub=0010
;     cond=SDA_LOW=5 at [16:13] → 5<<13 = 0x0000_A000
;     timeout=64 at [12:3] → 64<<3 = 0x0000_0200
;     [25:17] reserved=0, [2:0] reserved=0
;   word bits: 0x4800_0000 | 0x0000_A000 | 0x0000_0200 = 0x4800_A200
WAIT_ON SDA_LOW, 64
; word = 0x4800_A200

; §5.13 SET_BUS_MODE  i3c-PP
;   group=01 sub=0011, mode=0b0010 at [6:3] → 2<<3=0x10
SET_BUS_MODE i3c-PP
; word = 0x4C00_0010

; §5.14 SET_ROLE  target
;   group=01 sub=0100, role=1 at [3] → 1<<3=0x08
SET_ROLE target
; word = 0x5000_0008

; §5.15 FLAG_CLEAR  0b00001
;   group=01 sub=0101, mask=0b00001 at [7:3] → 1<<3=0x08
FLAG_CLEAR 0b00001
; word = 0x5400_0008

; §5.16 MARK  label=42
;   group=01 sub=0110, label=42 at [16:3] → 42<<3=336=0x0150
;   [25:17] reserved=0, [2:0] reserved=0
MARK label=42
; word = 0x5800_0150

; §5.17 LOAD_TIMING  reg=0, divider=480
;   group=01 sub=0111, reg=0 at [19:17]=0; divider=480 at [16:3] → 480<<3=3840=0x0F00
;   [25:20] reserved=0, [2:0] reserved=0
LOAD_TIMING reg=0, divider=480
; word = 0x5C00_0F00

; §5.18 LOAD_IMM  R3, 100
;   group=10 sub=0000, dst=3 at [25:23] → 3<<23=0x0180_0000
;   imm=100 at [16:3] → 100<<3=0x320
LOAD_IMM R3, 100
; word = 0x8180_0320

; §5.19 MOV  R1, R0
;   group=10 sub=0001, dst=1 at [25:23] → 1<<23=0x0080_0000
;   src=0 at [22:20]
MOV R1, R0
; word = 0x8480_0000

; §5.20 ADD_IMM  R0, R0, 4
;   group=10 sub=0010, dst=0 src=0, imm=4 at [16:3] → 4<<3=0x20
ADD_IMM R0, R0, 4
; word = 0x8800_0020

; §5.21 DEC  R6
;   group=10 sub=0011, dst=6 at [25:23] → 6<<23=0x0300_0000
;   src=6 at [22:20] → 6<<20=0x0060_0000
DEC R6
; word = 0x8F60_0000

; §5.22 AND_IMM  R0, R0, 0xFF
;   group=10 sub=0100, imm=255 at [16:3] → 255<<3=0x7F8
AND_IMM R0, R0, 0xFF
; word = 0x9000_07F8

; §5.23 OR_IMM  R0, R0, 0x01
;   group=10 sub=0101, imm=1 at [16:3] → 1<<3=0x08
OR_IMM R0, R0, 0x01
; word = 0x9400_0008

; §5.24 XOR_IMM  R0, R0, 0xFF
;   group=10 sub=0110, imm=255<<3=0x7F8
XOR_IMM R0, R0, 0xFF
; word = 0x9800_07F8

; §5.25 SHIFT  R0, R0, left, 1
;   New layout: group=10 sub=0111, dst=0 src=0
;     arith=0 at [19]=0; dir=0(left) at [18]=0; [17] reserved=0
;     shamt=1 at [7:3] → 1<<3=0x08; [2:0] reserved=0
SHIFT R0, R0, left, 1
; word = 0x9C00_0008
```

---

## 13. Assembler errors

Error codes use the form `E-<CATEGORY>-<NNN>`. LINT codes are advisory
warnings, not errors. Internal numbering within each category is not a
stable contract; gaps may appear when codes are removed (noted below).

| Code       | Trigger                                        | Template                                                                      |
|------------|------------------------------------------------|-------------------------------------------------------------------------------|
| E-LEX-001  | Unknown mnemonic                               | `unknown mnemonic: '<MNE>'`                                                   |
| ~~E-LEX-002~~ | *(removed in v0.2; mnemonics are case-insensitive)* | —                                                                  |
| E-LEX-003  | LOOP group mnemonic used                       | `LOOP group is entirely reserved; use '.dw' for raw injection`                |
| E-LEX-004  | Unknown condition-code token                   | `cond code '<TOK>' is not named (allowed: [list])`                            |
| E-LEX-005  | Unknown directive                              | `unknown directive '<DIR>' (allowed: .equ, .dw)`                             |
| E-SYM-001  | Duplicate label                                | `label '<LBL>' re-defined (prior label on line N)`                            |
| E-SYM-002  | Duplicate `.equ`                               | `equate '<NAME>' re-defined (prior equate on line N)`                         |
| E-SYM-003  | Unresolved label in branch target              | `undefined branch target: '<LBL>'`                                            |
| E-SYM-004  | Unresolved symbol in operand                   | `undefined symbol: '<SYM>'`                                                   |
| E-SYM-005  | `.equ` name collides with reserved mnemonic    | `equate name '<NAME>' collides with a reserved mnemonic / symbol`             |
| E-RNG-001  | Immediate out of range for field width         | `<OPCODE> <FIELD> must be <RANGE>, got <VAL>`                                 |
| E-RNG-002  | Program exceeds MAX_PROGRAM_WORDS = 8192       | `program exceeds 8192 instruction slots (PC overflow)`                        |
| E-RNG-003  | BRANCH_ON offset out of −512..511              | `BRANCH_ON offset <N> out of signed 10-bit range (branch_pc=<PC>)`            |
| E-RNG-004  | WAIT_ON timeout out of 0..1023                 | `WAIT_ON timeout must be 0..1023 quarters, got <N>`                           |
| E-OP-001   | Missing required operand                       | `missing required operand: <KEY>=<desc>`                                      |
| E-OP-002   | Unknown operand key                            | `unknown operand key '<KEY>' (allowed: [list])`                               |
| E-OP-003   | Duplicate operand key                          | `duplicate operand key '<KEY>'`                                               |
| E-OP-004   | Positional token where key=value expected      | `expected key=value operand, got positional token '<TOK>'`                    |
| E-OP-005   | `expect=X` combined with `mask=1`              | `expect=X is don't-care and cannot be combined with mask=1`                   |
| E-OP-006   | Unknown bus-mode name                          | `bus mode '<NAME>' is not named (allowed: [list])`                            |
| E-OP-007   | Bad register name (e.g. `R8`, `R9`)            | `register '<NAME>' is not valid; use R0..R7`                                  |
| E-OP-008   | SET_ROLE bad operand                           | `SET_ROLE operand must be 'controller' or 'target'`                           |
| E-OP-009   | FLAG_CLEAR mask out of range                   | `FLAG_CLEAR mask must be a 5-bit value (0..31), got <N>`                      |
| E-OP-010   | LOOP group opcode used (same as E-LEX-003)     | `opcode group 0b11 (LOOP) is reserved in v0.2; no live opcodes`               |
| E-REG-001  | Capture steered to non-R7 without raw/ pragma  | `capture must write to R7; use raw/ pragma to override`                       |
| E-WIRE-001 | `tx_symbol = 0b11` (reserved) used directly    | `tx=reserved (0b11) requires raw/ pragma; use .dw or declare raw/`            |
| E-WIRE-002 | PP-class target: `scl=recessive` in EMIT_QTR   | `EMIT_QUARTER scl=recessive is illegal in target role under PP-class`         |
| E-WIRE-003 | `EMIT_BYTE` with `mask=1` not followed by `BRANCH_ON MISMATCH` or `FLAG_CLEAR` with bit 0 set | `EMIT_BYTE mask=1 at line N must be followed by BRANCH_ON MISMATCH or FLAG_CLEAR mask=0b00001; declare '(use-raw-primitives)' to suppress` |
| E-CTRL-001 | Reserved cond-code value 12–15 in source       | `cond code <N> is reserved; values 12..15 require raw/ pragma`                |
| E-RAW-001  | Raw feature used without `(use-raw-primitives)` pragma | `use of '<feature>' requires '(use-raw-primitives)' declared at top of source` |
| E-RAW-002  | `(use-raw-primitives)` pragma appears after first instruction | `raw pragma must be the first non-comment line`                  |
| E-RAW-003  | `(use-raw-primitives)` pragma malformed        | `pragma must read exactly '(use-raw-primitives)'`                             |
| E-FRM-001  | `.dw` operand count > MAX_PROGRAM_WORDS        | `.dw operand count <N> exceeds program-memory budget of 8192 words`           |
| E-FRM-002  | `.dw` value out of 32-bit range                | `.dw value <V> out of 32-bit range`                                           |
| E-FRM-003  | Empty program body                             | `program contains no instructions (body would be zero words; the canonical minimum is a single HALT)`. Raised when the source compiles to zero body words: a fully-empty source, a source containing only comments / blank lines / labels, a source containing only `.equ` directives, or one whose only `.dw` directives have zero operands. A program must contain at least one instruction word; the canonical minimum is `HALT`. |
| LINT-001   | Intervening ALU op between DEC and BRANCH_ON   | `REG_ZERO_FLAG overwritten by <OP> at line N before BRANCH_ON at line M`      |

**Example inputs:**

```moleasm
; E-LEX-001: unknown mnemonic
FROBNICATE

; E-OP-007: bad register
LOAD_IMM R8, 1

; E-RNG-001: out-of-range immediate
LOAD_IMM R0, 16384

; E-RNG-003: out-of-range branch offset (label 600 words ahead, beyond 511)
BRANCH_ON ALWAYS, far_label

; E-WIRE-001: reserved tx_symbol without raw/
EMIT_BIT_IMM tx=0b11

; E-REG-001: capture to non-R7
SAMPLE_BIT_ON_SCL capture=1     ; OK (defaults to R7)
; To steer elsewhere: (use-raw-primitives) pragma required.

; E-WIRE-003: unpaired EMIT_BYTE with mask=1 (no raw/ pragma)
EMIT_BYTE expect=0 mask=1 capture=1
HALT                            ; must be preceded by BRANCH_ON MISMATCH
                                ; or FLAG_CLEAR mask=0b00001

; E-RAW-001: raw feature without pragma
SAMPLE_BIT_ON_SCL dst=R3 capture=1   ; capture-to-non-R7 without (use-raw-primitives)

; LINT-001: REG_ZERO overwritten
DEC R6
LOAD_IMM R0, 1                  ; clobbers REG_ZERO_FLAG
BRANCH_ON REG_ZERO, top         ; lint: flag may have been overwritten
```

---

## 14. Pipeline hazards

### Read-after-write (RAW) hazards

The 5-stage pipeline (F→D→R→E→W) produces RAW hazards when a register
written in W is read in R of a subsequent instruction. The engine
resolves data hazards via a **full bypass network from W back to R**.
No programmer action is required for the bypass cases. A **single-cycle
stall** is inserted for the load-use case only.

**Load-use hazard:** `LOAD_IMM Rd` followed immediately by any opcode
reading `Rd` causes one stall cycle:

```moleasm
LOAD_IMM R2, 42
EMIT_BIT_REG src=R2    ; one stall cycle inserted by hardware
```

**Full RAW hazard table:**

| Writer opcode                  | Written reg | Reader opcodes                                  |
|-------------------------------|-------------|--------------------------------------------------|
| LOAD_IMM Rd                   | Rd          | EMIT_BIT_REG (src), EMIT_QUARTER_REG (src),      |
|                               |             | STRETCH_SCL_REG (src), MOV (src), ADD_IMM (src), |
|                               |             | DEC (src), AND_IMM (src), OR_IMM (src),          |
|                               |             | XOR_IMM (src), SHIFT (src), EMIT_BYTE (reads R7) |
| MOV Rd, Rs                    | Rd          | (same reader set as LOAD_IMM)                    |
| ADD_IMM Rd, Rs, imm           | Rd          | (same)                                           |
| DEC Rd                        | Rd          | (same)                                           |
| AND_IMM Rd, Rs, imm           | Rd          | (same)                                           |
| OR_IMM Rd, Rs, imm            | Rd          | (same)                                           |
| XOR_IMM Rd, Rs, imm           | Rd          | (same)                                           |
| SHIFT Rd, Rs, dir, amt        | Rd          | (same)                                           |
| SAMPLE_BIT_ON_SCL (capture=1) | R7          | (same reader set, with src=R7)                   |
| DRIVE_BIT_ON_SCL (capture=1)  | R7          | (same reader set, with src=R7)                   |
| EMIT_BIT_* (capture=1)        | R7          | (same, including EMIT_BYTE as writer when cap=1) |
| EMIT_QUARTER_* (capture=1)    | R7          | (same)                                           |
| EMIT_BYTE (capture=1)         | R7          | (capture writes ACK bit to R7[0]; same readers)  |

**EMIT_BYTE hazard notes:**

- *As a reader of R7:* EMIT_BYTE always reads R7[7:0] as the payload
  byte. The load-use hazard applies: `LOAD_IMM R7, 0xAB; EMIT_BYTE`
  requires one stall cycle (inserted by hardware). The bypass resolves
  any other write-then-read within the in-flight window.
- *As a writer of R7:* When `capture=1`, EMIT_BYTE writes the ACK bit
  to R7[0] at W stage, with the same write timing as SAMPLE_BIT_ON_SCL
  / DRIVE_BIT_ON_SCL. The bypass operates on the W tick that follows
  the atomic E loop's final quarter-bit clock; subsequent register
  reads see the ACK value with zero additional latency.

```moleasm
LOAD_IMM R7, 0xAB
EMIT_BYTE                ; one stall cycle: LOAD_IMM R7 → EMIT_BYTE reads R7
```

All entries are resolved by the W-to-R bypass network with zero stall
cycles **except** LOAD_IMM/MOV/ADD_IMM/DEC/AND_IMM/OR_IMM/XOR_IMM/
SHIFT into an immediate-consumer that follows in the very next
instruction slot — that pair requires one stall. (The bypass covers
in-flight W → R for cycles where the producer is in E/W and the
consumer is in R.)

### Flag hazards

`REG_ZERO_FLAG` is computed in E and consumed by a subsequent
BRANCH_ON in D (the branch evaluates the flag stored in the flag
register, which is updated from E of the DEC instruction). A
back-to-back `DEC Rx; BRANCH_ON REG_ZERO` sequence is **hazard-free
by design**: the flag is written at the end of E and read from storage
in the following D stage, with no stall needed. This is why the lint
(LINT-001) warns specifically on *interleaved* ALU operations between
DEC and its paired branch: that overwrites the flag before the branch
reads it.

`MISMATCH_FLAG`, `TIMEOUT_FLAG`, `START_FLAG`, and `STOP_FLAG` are
updated at the W stage of the opcode that writes them and are forwarded
combinationally to the D stage of the immediately-following BRANCH_ON
or WAIT_ON. No NOP is required between a flag-writing wire op and a
flag-consuming branch; the design guarantees **zero-cycle visibility**.
If timing closure proves this combinational forwarding infeasible during
Phase C, the contract may relax to one-cycle latency, in which case the
assembler will need to insert a NOP equivalent — a candidate placeholder
is `MOV R0, R0`, which costs one cycle without side effect.

---

## 15. Error-injection contract

Error injection is **decided at compile time** by the host encoder.
The bit engine has zero runtime randomness. The engine contains no
"injection" opcode; injected errors exist entirely as choices made
by the Layer-1 assembler (or Scheme SDK) when it selects `tx_symbol`
values, swaps `expect` bits, omits ACKs, and so on.

### Compile-time model

The host encoder accepts `(seed, ratio)`. A deterministic PRNG seeded
by `seed` and gated by `ratio` decides, for each bit position, whether
to inject an error at assembly time. When a bit is to be injected,
the assembler substitutes the "wrong" `tx_symbol` or sets up a
EMIT_BIT_IMM with the incorrect value baked in.

**Invariant:** `ratio = 0` produces bytecode **byte-identical** to the
no-injection build. The engine itself cannot detect, at runtime, whether
injection was applied; injection is invisible to the ISA.

### Namespace contract

Per AGENTS §3.5: anything inside `i2c/`, `i3c/`, `ccc/`, `hdr-ddr/`
SDK namespaces MUST emit a spec-correct bitstream. Off-spec behaviour
(including injected errors) belongs under `raw/` and the source must
declare `(use-raw-primitives)` as its first non-comment form. This
makes "does this test inject errors?" answerable by `grep`.

---

## 16. Loader behaviour

Phase C implements the loader FSM on the FPGA. Phase B5 freezes the
ABI constants in `mole-abi`. Phase B6 surfaces version-mismatch as a
distinct loader-CLI exit code.

### Required loader behaviour

1. **Magic check:** Reject any frame whose first word does not match
   `MAGIC = 0x0002_4D4C`. The engine halts with `STATUS_TRAP` and
   reports the error via the result ring.

2. **Version check:** Reject any frame whose version field (high 16
   bits of word 0) is not `FORMAT_VERSION = 0x0002`. The loader-CLI
   exits with a dedicated non-zero exit code (Phase B6 will assign the
   number; the code must be documented in `mole-loader-cli`'s `--help`
   text and distinct from all other exit codes).

3. **Length check:** Reject any frame whose length field (word 1)
   exceeds `MAX_PROGRAM_WORDS = 8192`. This check MUST occur before
   any instruction words are written to SPRAM.

4. **Reserved-status check:** Scan every instruction word for HALT
   opcodes (group=`0b01`, sub=`0b0000`) and reject any program that
   contains a HALT instruction with status in
   `STATUS_RESERVED_LOW..=STATUS_RESERVED_HIGH` (`0x1D`–`0x1E`).
   Words not encoding a HALT opcode are not scanned for this check.
   Programs that use `.dw <hex>` directives to inject literal
   HALT-shaped words are also scanned — the loader treats every word
   as potentially executable. These values are reserved for future
   engine traps; the loader surfaces them as an error before execution
   begins.

5. **Minimum length:** A frame with length field = 0 (empty program)
   is rejected.

### SPRAM write window

On Mole Verde (iCE40 UP5K), the program SPRAM is 256 Kbits = 32 KB.
At 32-bit instructions, `MAX_PROGRAM_WORDS = 8192` words × 4 bytes =
32 KB = exactly the SPRAM capacity. Phase C will audit the Verde memory
map to confirm program memory can claim the full SPRAM, or to document
what other state shares this region.

---

## 17. Glossary

| Term            | Definition                                                     |
|-----------------|----------------------------------------------------------------|
| `tx_symbol`     | 2-bit per-bit drive encoding: `dominant=0b00`,                 |
|                 | `recessive=0b01`, `hiz=0b10`, `reserved=0b11`                 |
| `BUS_MODE`      | 4-bit register selecting the symbol-to-electrical mapping      |
| OD class        | Open-drain BUS_MODE (`i2c`, `i3c-OD`): engine drives low,     |
|                 | releases high via external pull-up                             |
| PP class        | Push-pull BUS_MODE (`i3c-PP`, `hdr-ddr`): engine drives        |
|                 | both low and high actively                                     |
| group           | High 2 bits `[31:30]` of a 32-bit instruction                 |
| sub             | Bits `[29:26]` of a 32-bit instruction (4-bit sub-opcode)     |
| opcode          | 6-bit value `{group, sub}` identifying the instruction         |
| flag triple     | 3-bit field `[2:0]` carrying `expect/mask/capture` on WIRE    |
|                 | bearer opcodes                                                 |
| quarter-bit     | One `engineClk` cycle; the atomic timing unit on the wire     |
| sticky flag     | Engine flag that remains set until explicitly cleared           |
| MISMATCH_FLAG   | Sticky flag set when a sampled bit differs from `expect`        |
|                 | (and `mask=1`); cleared by FLAG_CLEAR bit 0 or a              |
|                 | subsequent flag-write that clears it                           |
| TIMEOUT_FLAG    | Sticky flag set when a WAIT_ON timeout expires before the      |
|                 | condition is met; cleared by FLAG_CLEAR bit 1                  |
| START_FLAG      | Sticky flag set by engine on START condition detection;        |
|                 | cleared by FLAG_CLEAR bit 2                                    |
| STOP_FLAG       | Sticky flag set by engine on STOP condition detection;         |
|                 | cleared by FLAG_CLEAR bit 3                                    |
| REG_ZERO_FLAG   | Flag set by ALU operations when result = 0                     |
| STATUS_TRAP     | HALT status `0x1F`; engine-generated on malformed instr        |
| Layer-0         | The FPGA bit-cycle engine that executes this ISA; has zero     |
|                 | protocol knowledge                                             |
| Layer-1         | The Scheme host SDK that compiles I3C/I2C protocol into        |
|                 | Layer-0 bytecode                                               |
| capture         | The `capture` flag bit: when 1, the sampled wire value is      |
|                 | written to R7[0]                                               |
| expect          | The `expect` flag bit: the value against which the sampled     |
|                 | wire bit is compared when `mask=1`                             |
| mask            | The `mask` flag bit: when 1, enables compare of sampled bit    |
|                 | against `expect` and potential write of MISMATCH_FLAG          |
| bypass network  | Combinational forwarding path from W stage back to R stage     |
|                 | that resolves most RAW hazards without stalls                  |
| stall           | One-cycle pipeline pause inserted by hardware when a load-use  |
|                 | hazard is detected (LOAD_IMM Rd followed immediately by        |
|                 | an instruction reading Rd)                                     |
| RAW hazard      | Read-after-write hazard: an instruction reads a register        |
|                 | that a prior instruction has not yet written back              |
| body-only length| The length field of the wire-format preamble word 1:           |
|                 | count of instruction words excluding the 2 preamble words      |
| controller      | Engine role in which it drives SCL (master role)               |
| target          | Engine role in which it slaves to external SCL                 |
| R6              | Conventional loop-counter register; destination of LOAD_LOOP   |
| R7              | Canonical capture-result register; written by sample opcodes   |
| v0              | Prior 16-bit ISA; not compatible with v0.2                     |
| v0.2            | This specification                                             |
| Phase B5        | Roadmap phase that freezes ABI constants in `mole-abi`         |
| Phase C         | Roadmap phase that implements the loader FSM and pipeline      |
| raw/            | SDK namespace / moleasm pragma requiring                       |
|                 | `(use-raw-primitives)` opt-in                                  |
| LOAD_LOOP       | Sugar for `LOAD_IMM R6, n`; primes the R6 loop counter         |
| JMP             | Sugar for `BRANCH_ON ALWAYS, label`                            |
| LINT-001        | Advisory warning: REG_ZERO_FLAG overwritten between DEC and    |
|                 | its paired BRANCH_ON                                           |

---

## 18. AGENTS.md sections requiring Phase A2 amendment

The following sections of `AGENTS.md` contradict v0.2 and MUST be
amended in Phase A2 before any v0.2 implementation work is merged:

1. **§3.9 (instruction width):** States "instruction width is fixed
   16 bits" and "opcode is always `[15:11]` (5 bits, 32 slots, 15 in
   use + 17 reserved)." Must change to: "instruction width is fixed
   32 bits; opcode is `{group[31:30], sub[29:26]}` (6 bits, 64 slots,
   4 groups of 16 each)."

2. **§3.10 (flag triple position):** States the flag triple is at
   `[2:0]` — still accurate. However, the rationale text says "symbol
   fields stay at the high end; reserved bits fill the middle" in the
   context of a 16-bit word. Needs rewording to reference the 32-bit
   layout where the flag triple remains at `[2:0]` and the `tx_symbol`
   fields appear at `[4:3]` (EMIT_BIT_IMM), `[4:3]`/`[6:5]`
   (EMIT_QUARTER_IMM), etc. Additionally, the entry "there are four
   bearer opcodes with the flag triple" must be updated: v0.2 has
   **eight** WIRE bearer opcodes (EMIT_BIT_IMM, EMIT_BIT_REG,
   EMIT_QUARTER_IMM, EMIT_QUARTER_REG, EMIT_BYTE, SAMPLE_BIT_ON_SCL,
   DRIVE_BIT_ON_SCL, and the stretch pair uses reserved=0 at `[2:0]`
   so the hardware check still passes).

3. **§3.16 (moleasm syntax locked):** States the v0 syntax is locked.
   Must be updated to permit the v0.2 grammar additions:
   - Register operands `R0`–`R7` (case-insensitive).
   - Split opcodes: `EMIT_BIT_IMM` / `EMIT_BIT_REG`; same for
     `EMIT_QUARTER` and `STRETCH_SCL`.
   - Case-insensitive mnemonics (canonical diagnostic form UPPERCASE).
   - `JMP <label>` sugar for `BRANCH_ON ALWAYS`.
   - `LOAD_LOOP n` sugar for `LOAD_IMM R6, n`.
   - `DEC` as a standalone opcode (v0's fused `DEC_BRANCH` is gone).

4. **§3.17 (pre-Phase-0 mutable encoding):** Still accurate in spirit
   but should explicitly reference "v0.2 as the in-development format"
   so future agents do not mistake v0 constants for the current contract.

5. **§7 (workflow expectations):** States "the only design doc that
   lives in-tree is `ROADMAP.md`." Must be updated to: "design
   documents live in `docs/` or as `ROADMAP.md`; planning documents
   (ephemeral notes, session plans) stay out of the tree." The rule
   change is: `docs/MOLE-0.2-SPEC.md` (and future normative specs
   under `docs/`) are in-tree design documents.

6. **§8 (what NOT to do):** The entry "Don't introduce a `WAIT_START`
   / `WAIT_STOP` / etc. opcode … Same for `BRANCH_MISMATCH` /
   `BRANCH_ON_MM` / `BRANCH_ON_CAPTURED_MASK` — unified into
   `BRANCH_ON cond, offset`" remains correct and should be preserved.
   The following clarifications are needed for v0.2:

   - `DEC_BRANCH` remains forbidden: DEC and BRANCH_ON are now separate
     opcodes by design, and the fused form has been retired. Any
     residual mention of `DEC_BRANCH` as a live opcode is a regression
     marker.
   - `LOAD_LOOP` becomes assembler sugar for `LOAD_IMM R6, n` (see
     §5.18). Phase A2 must update §8 to admit the sugar (`LOAD_LOOP`
     as a mnemonic is legal) while preserving the forbid-rule on
     any ISA opcode that would implement it in hardware — no new
     dedicated opcode is introduced.
   - `WAIT_START` / `WAIT_STOP` / `WAIT_SDA_LOW` / `WAIT_SCL_RELEASE`
     remain forbidden (folded into `WAIT_ON cond, timeout`).

7. **§1 one-paragraph architecture:** Mentions "15-opcode ISA
   (16-bit fixed-width instructions, 17 reserved opcode slots)" and
   "`LCR0`/`LCR1` primed by `LOAD_LOOP` and counted down by
   `DEC_BRANCH`." Both need updating: 32-bit instructions, **25 live
   opcodes** across 4 groups (9 WIRE + 8 CTRL + 8 DATA + 0 LOOP), and
   the DEC+BRANCH_ON pattern replaces the fused `DEC_BRANCH`.
