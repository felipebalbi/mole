# Glossary

Vocabulary you will encounter in moleasm sources, the Mole codebase,
and the surrounding I2C / I3C spec language. Where a word has a
narrow Mole-specific meaning, that is the one given here; the
broader industry definition is footnoted only when the two could be
confused.

---

**ACK.** *Acknowledge.* In I2C / I3C, a bit driven *low* (dominant)
in the slot immediately after a byte to confirm reception. In
moleasm the controller-driven ACK slot is conventionally
`EMIT_BIT_IMM tx=hiz expect=0 mask=1 capture=1`; a slave-ACK
observation rides on an `EMIT_BYTE_IMM` or `EMIT_BYTE_REG` with
`expect=0 mask=1 capture=1`.

**Bit-cycle engine.** The FPGA-resident state machine that executes
moleasm bytecode at quarter-bit-time resolution. The v0.2 engine
is the 5-stage F1 / F2 / D / R / X / W pipeline implemented in
`fpga/Mole/src/hw/EnginePipeline.scala`. See [Mental
Model](./mental-model.md).

**Branch.** A PC-relative jump (`BRANCH_ON`). Range `-512..+511`
instructions (signed 10-bit PC-relative offset). For longer reach,
use `JMP` (sugar for `BRANCH_ON ALWAYS, <label>`).

**Bus mode.** One of `i2c`, `i3c-od`, `i3c-pp`, `hdr-ddr`. Selected
by `SET_BUS_MODE`. Decides the electrical meaning of `recessive`
and the active timing divider register.

**Capture.** Push the SDA value sampled this quarter into the
result ring as a CAPTURE record (1 word, tag `0b00`, sda at
bit 0). Controlled by the `capture=1` flag on `EMIT_BIT_IMM`,
`EMIT_BIT_REG`, `EMIT_QUARTER_IMM`, `EMIT_QUARTER_REG`,
`EMIT_BYTE_IMM`, `EMIT_BYTE_REG`, `SAMPLE_BIT_ON_SCL`, and
`DRIVE_BIT_ON_SCL`. The captured bit is also written to
`R7[0]` (with `R7[31:1]` zeroed) so subsequent ALU / branch
ops can react inline.

**CCC.** *Common Command Code.* I3C in-band command. Out of scope
for moleasm itself, which works at the bit layer; a higher-level
Scheme SDK (Layer 1, planned) will generate CCC sequences as
straight-line moleasm.

**Cond code.** One of `ALWAYS`, `MISMATCH`, `NOT_MISMATCH`,
`START_SEEN`, `STOP_SEEN`, `SDA_LOW`, `SDA_HIGH`, `SCL_HIGH`,
`TIMEOUT`, `NOT_TIMEOUT`, `REG_ZERO`, `NOT_REG_ZERO`. Used by
both `BRANCH_ON` and `WAIT_ON`. See the
[Reference](./reference.md).

**Controller.** The bus party that drives SCL. Sometimes "master".
Selected at run time by `SET_ROLE controller` (the boot default).

**Dominant.** Tx symbol that drives the line *low*. In I2C / OD-I3C
this is the only direction that crosses the pull-up; in PP-I3C it
is electrically asserted by an NMOS pull-down.

**Drainer.** Host-side and FPGA-side components that empty the
result ring over UART. The FPGA-side `MoleDrainerFsm` sweeps the
SPRAM result region; the host-side `mole-loader` decodes the
incoming byte stream into typed records.

**`.dw`.** Directive that emits a literal 32-bit word at the
current PC. Escape hatch for opcodes the assembler refuses to
emit. Values are bounded to `0..0xFFFF_FFFF`.

**`.equ`.** Directive that binds a name to an integer value. Names
defined with `.equ` are *not* labels; they cannot be used as
branch or jump targets.

**EMIT_BIT_IMM / EMIT_BIT_REG.** Opcodes that emit one full
bit-time of the canonical SCL pattern (low / low / high / high)
with SDA held at a `tx_symbol`. `_IMM` takes the symbol in the
instruction word; `_REG` reads it from `R7[1:0]` at run time.
Controller role.

**EMIT_BYTE_IMM / EMIT_BYTE_REG.** Opcodes that emit eight data
bits MSB-first plus a ninth `hiz`-SDA ACK slot. `_IMM` takes the
byte in the instruction word (`[10:3]`); `_REG` reads it from
`R7[7:0]`. The flag triple applies to the ACK slot. Controller
role.

**EMIT_QUARTER_IMM / EMIT_QUARTER_REG.** Opcodes that emit one
quarter-bit-time edge with independent control of SDA and SCL.
The surgical tool for START / STOP / Sr / fault-injection edges.

**Expect.** Flag (`expect=0|1|X`) on capturing opcodes; the value
the engine compares against the read sample when `mask=1`. `X`
means "don't care" and cannot be combined with `mask=1`.

**Fabric clock.** The FPGA's main clock, which on Mole *is* the
quarter-bit clock. The Verde build runs at 24 MHz; the
`LOAD_TIMING` divider sets fabric cycles per quarter-bit at run
time. There is no sub-quarter-bit timing knob.

**Frame.** The wire format the on-target loader expects:
`[MAGIC 4B LE = 0x0002_4D4C][LEN 4B LE = body word count][body
4×N bytes, each instruction LE][CRC 2B LE]`. CRC-16/XMODEM over
MAGIC + LEN + body. Body length is `1..=8192` 32-bit words. See
spec §10 and `mole_abi::MAGIC`.

**Halt.** Stop the engine. The `HALT` opcode's status field (5
bits, `0..0x1F`) is the caller-defined exit code surfaced to the
host. `0x1F` is reserved as `STATUS_TRAP` (engine-detected fault
such as a reserved-opcode trap or stretch-timeout); the assembler
rejects programs that emit it directly.

**HDR-DDR.** I3C *High Data Rate, Double Data Rate* mode. One of
the four bus modes Mole supports (the others being I2C, I3C-OD,
I3C-PP).

**Hi-Z.** *High impedance.* Tx symbol `hiz`. The pad is not actively
driving. Used during ACK sampling and target-role release.

**I3C-OD.** I3C *Open Drain* mode. `recessive` releases the line.

**I3C-PP.** I3C *Push-Pull* mode. `recessive` actively drives the
line high.

**Instruction.** A 32-bit word. Opcode is the 6-bit field
`{group[31:30], sub[29:26]}` (4 groups × 16 sub-slots = 64
opcode slots; 26 live in v0.2, 38 reserved). The rest of the
layout depends on the opcode.

**Label.** A name bound to a PC value. Defined with `name:`. Cannot
contain hyphens (in contrast to `.equ` names, which can).

**Layer 0.** The FPGA bit-cycle engine. Bus-agnostic --- it knows
nothing about I2C or I3C.

**Layer 1.** The host-side compiler stack. The protocol knowledge
lives here: spec-correct I2C / I3C / CCC / HDR-DDR primitives plus
fault-injection knobs. moleasm is the lowest level of Layer 1 a
human writes directly; a higher-level Scheme SDK is planned to sit
above it.

**LINT-001.** Assembler warning emitted when an ALU op (any DATA-
group opcode) sits between a flag writer and the `BRANCH_ON`
consumer that needs the flag. Almost always indicates a bug in
the program's control flow; the typical pattern is `DEC R6` →
*other ALU op* → `BRANCH_ON REG_ZERO`, where the intervening op
silently clobbers `REG_ZERO_FLAG`.

**LOAD_LOOP.** Assembler sugar in v0.2 (not an opcode). `LOAD_LOOP
n` expands to `LOAD_IMM R6, n` — primes the conventional loop
counter `R6` with a 14-bit immediate (`0..16383`). Use
`LOAD_IMM Rx, n` directly when the counter lives in another
register. The retired v0 opcode of the same name has no
equivalent in v0.2.

**Mark.** A 3-word record pushed into the result ring (`MARK
label=...`). The label is a 14-bit user value; the engine
attaches a 32-bit timestamp (cycles since program start) split
across the two timestamp words. Lets the host decoder
discriminate between captures from different parts of the
program.

**Mask.** Flag (`mask=0|1`) on capturing opcodes. `mask=1` makes
the engine compare the read sample against `expect` and write
`MISMATCH_FLAG`. With `mask=0` the flag's prior sticky value is
preserved.

**Mismatch flag.** Sticky engine flag set when a capturing opcode
with `mask=1` saw a read that disagreed with `expect`. Cleared
by the next opcode that writes a flag, or explicitly by
`FLAG_CLEAR mask=0b00001` (bit 0).

**Mole Verde / Rojo / Negro.** The three SKUs in the Mole product
line. Verde is the pocket / per-developer dongle (iCE40 UP5K),
Rojo the bench tier (ECP5-45K), Negro the future certification
tier (CertusPro-NX). The bytecode contract is identical across
all three.

**moleasm.** The assembly language for Mole bytecode. This book is
about it.

**`.molecode`.** Bytecode file extension: packed little-endian
32-bit instruction words, no header. Pre-Phase-0 the format is
mutable; post-Phase-0 it becomes a stable contract per AGENTS.md
§3.17.

**`.mole.bin`.** Frame file extension: `MAGIC + LEN + body + CRC`,
ready to ship over UART.

**Open drain.** Drive-style where a pad can only sink current; the
line floats high through an external pull-up. The model for I2C
and I3C-OD electrical behaviour.

**Opcode.** The 6-bit field `{group[31:30], sub[29:26]}` of every
instruction. 26 live opcodes in v0.2 (10 WIRE + 8 CTRL + 8 DATA);
the LOOP group is fully reserved for v0.5.

**Pass 1 / Pass 2.** The two phases of the assembler. Pass 1 lexes,
builds the symbol table, and assigns PC values. Pass 2 encodes
each statement into a 32-bit word.

**PC.** *Program counter.* The 13-bit address of the next
instruction in SPRAM (max 8192 instruction words). Advances by
one per executed opcode unless a branch / jump moves it.

**Push-pull.** Drive-style where a pad can both source and sink
current. The model for I3C-PP and HDR-DDR. Recessive in these
modes actively drives high; in OD modes recessive releases.

**Quarter / quarter-bit-time.** One tick of the engine's clock
divided by the active `LOAD_TIMING` divider. Every timing in
moleasm (bit periods, stretch durations, wait timeouts) is
measured in quarters.

**R0..R7.** The eight 32-bit general-purpose registers. R6 is the
conventional loop counter (`LOAD_LOOP` sugar targets it); R7 is
the conventional capture / byte register (`EMIT_BIT_REG` reads
`R7[1:0]`; `EMIT_BYTE_REG` reads `R7[7:0]`; captures write to
`R7[0]` with the rest zeroed). The remaining registers are free
for the program.

**Recessive.** Tx symbol that releases / asserts the line high
depending on bus mode. In OD modes it's a release (Hi-Z + external
pull-up); in PP modes it's an active drive.

**REG_ZERO_FLAG.** Sticky engine flag set when the most recent
ALU op (`DEC`, `ADD_IMM`, `MOV`, `LOAD_IMM`, `AND_IMM`,
`OR_IMM`, `XOR_IMM`, `SHIFT`) wrote a zero result. Read by
`BRANCH_ON REG_ZERO` / `NOT_REG_ZERO`. The pair `DEC Rx` +
`BRANCH_ON NOT_REG_ZERO, label` is the canonical loop back-edge.

**Repeated START.** Two STARTs without an intervening STOP.
Spelled out at the quarter level in moleasm; see the
[Patterns](./patterns.md) chapter.

**Reserved (v0.5).** Mnemonics, tx codes, and cond codes that the
ISA holds for a future revision. The assembler rejects them;
`.dw` exists for cases where you really need the raw bits.

**REVISION.** The 32-bit version word the engine emits at the
head of the result ring (`word[0]`) on every program start.
Layout: `[31:24]=major [23:16]=minor [15:0]=patch`. Current
silicon value is `0x0001_0001` (REVISION 0.1.1).

**Result ring.** 32-bit-grained record stream the engine writes
into the SPRAM result region. Layout: `REVISION` at slot 0,
then a contiguous stream of `CAPTURE` (1 word, tag `0b00`) and
`MARK` (3 words, tag `0b10`) records, then `HALT` (1 word, tag
`0b11`) at the last slot (`resultLimit`). On overflow the
engine drops further records and latches `overflow=1` in the
HALT word's `[29]` bit.

**Sample.** Read SDA into the engine. Done implicitly by every
capturing opcode; explicit by `SAMPLE_BIT_ON_SCL` in target role.

**SCL.** I2C / I3C clock line. Driven by the controller in
controller role; released by the engine in target role (the
external controller's SCL paces the target's bit FSM).

**SDA.** I2C / I3C data line. Driven by either party, depending on
role and bus state.

**Sticky flag.** A single-bit engine state (mismatch, timeout,
start, stop, reg_zero) that, once set, stays set until
overwritten by the next flag-writing opcode, or explicitly
cleared by `FLAG_CLEAR` (per-bit mask).

**Status.** The 5-bit caller-defined value `HALT` records on
shutdown. By convention 0 = success. `0x1F` is reserved as
`STATUS_TRAP`.

**STATUS_TRAP.** The reserved HALT status `0x1F`, raised by the
engine itself on any unrecoverable fault: reserved opcode,
reserved cond code, runtime `R[src][1:0] = 0b11` on an
`_REG`-variant tx field, PP-class slave stretch, BUS_MODE
divider register out of range, etc. The assembler rejects
programs that emit `0x1F` directly so a user `HALT status=N` is
always distinguishable from an engine-detected trap.

**Target.** The bus party that follows SCL (i.e., the I2C "slave",
or the I3C target). Selected at run time by `SET_ROLE target`.
The controller-side opcodes (`EMIT_BIT_*`, `EMIT_QUARTER_*`,
`EMIT_BYTE_*`) are replaced by `SAMPLE_BIT_ON_SCL` and
`DRIVE_BIT_ON_SCL`, which pace off external SCL edges.

**Timing register.** One of four registers that hold the
quarter-bit divider for the four bus modes. The active register
is selected by the current `BUS_MODE`. Written by `LOAD_TIMING
reg=<0..3>, divider=<N>` at run time.

**Tx symbol.** One of `dominant`, `recessive`, `hiz` (plus the
reserved `0b11`). The per-bit / per-quarter drive vocabulary that
makes the engine bus-agnostic. See `AGENTS.md` §3.11.

**UART.** *Universal Asynchronous Receiver/Transmitter.* The
host link to Mole. Frames go in, results come out. The Verde
build uses 8N1 with hardware RTS/CTS at 2 Mbaud (1 Mbaud
fallback).

**Wire format.** The byte-level contract for what crosses UART
between host and engine. See `docs/MOLE-0.2-SPEC.md` §10 (frame
layout) and §11 (result-ring layout).

---

If a term shows up in source but not here, it almost certainly
lives in `docs/MOLE-0.2-SPEC.md` --- and probably should land here
too the next time you trip over it.
