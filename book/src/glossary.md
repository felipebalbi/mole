# Glossary

Vocabulary you will encounter in moleasm sources, the Mole codebase,
and the surrounding I2C / I3C spec language. Where a word has a
narrow Mole-specific meaning, that is the one given here; the
broader industry definition is footnoted only when the two could be
confused.

---

**ACK.** *Acknowledge.* In I2C / I3C, a bit driven *low* (dominant)
in the slot immediately after a byte to confirm reception. In
moleasm the ACK slot is conventionally
`EMIT_BIT tx=hiz expect=0 mask=1 capture=1`.

**Bit-cycle engine.** The FPGA-resident state machine that executes
moleasm bytecode at quarter-bit-time resolution. See [Mental
Model](./mental-model.md).

**Branch.** A PC-relative jump (`BRANCH_ON`). Range `-128..+127`
quarters of PC. For longer reach, use `JMP`.

**Bus mode.** One of `i2c`, `i3c-od`, `i3c-pp`, `hdr-ddr`. Selected
by `SET_BUS_MODE`. Decides the electrical meaning of `recessive` and
the active timing register.

**Capture.** Push the SDA value sampled this quarter into the
result ring. Controlled by the `capture=1` flag on `EMIT_BIT`,
`EMIT_QUARTER`, `SAMPLE_BIT_ON_SCL`, and `DRIVE_BIT_ON_SCL`.

**CCC.** *Common Command Code.* I3C in-band command. Out of scope
for moleasm itself, which works at the bit layer; a higher-level SDK
will generate CCC sequences as straight-line moleasm.

**Cond code.** One of `ALWAYS`, `MISMATCH`, ..., used by `BRANCH_ON`
and `WAIT_ON`. See the [Reference](./reference.md).

**Controller.** The bus party that drives SCL. Sometimes "master".
The Mole engine acts as controller by default.

**Dominant.** Tx symbol that drives the line *low*. In I2C / OD-I3C
this is the only direction that crosses the pull-up; in PP-I3C it
is electrically asserted.

**Drainer.** Host-side component that empties the result ring over
UART. Not part of the engine; lives in the host runtime (future
crate).

**`.dw`.** Directive that emits a literal 16-bit word at the current
PC. Escape hatch for opcodes the assembler refuses to emit.

**`.equ`.** Directive that binds a name to an integer value. Names
defined with `.equ` are *not* labels; they cannot be used as branch
or jump targets.

**EMIT_BIT.** Opcode that emits one full bit-time. Controller role.

**EMIT_QUARTER.** Opcode that emits one quarter-bit-time edge.
Independent control of SDA and SCL. The surgical tool for
fault-injection.

**Expect.** Flag (`expect=0|1|X`) on capturing opcodes; the value
the engine compares against the read sample when `mask=1`. `X`
means "don't care" and cannot be combined with `mask=1`.

**Fabric clock.** The FPGA's main clock, which on Mole *is* the
quarter-bit clock. There is no PLL multiplication; everything in a
program lives on the same quarter-tick grid.

**Frame.** The wire format the on-target loader expects:
`len_lo, len_hi, words(LE), crc_lo, crc_hi`. CRC-16/XMODEM over
everything except itself. 1..=2048 words per frame.

**Halt.** Stop the engine. The `HALT` opcode's status field is the
caller-defined exit code surfaced to the host.

**HDR-DDR.** I3C *High Data Rate, Double Data Rate* mode. One of
the four bus modes Mole supports (the others being I2C, I3C-OD,
I3C-PP).

**Hi-Z.** *High impedance.* Tx symbol `hiz`. The pad is not actively
driving. Used during ACK sampling and target-role release.

**I3C-OD.** I3C *Open Drain* mode. `recessive` releases the line.

**I3C-PP.** I3C *Push-Pull* mode. `recessive` actively drives the
line high.

**Instruction.** A 16-bit word. Opcode in `[15:11]`; the rest of the
layout depends on the opcode.

**Label.** A name bound to a PC value. Defined with `name:`. Cannot
contain hyphens (in contrast to `.equ` names, which can).

**Layer 0.** The FPGA bit-cycle engine. Bus-agnostic --- it knows
nothing about I2C or I3C.

**Layer 1.** The host-side compiler stack. The protocol knowledge
lives here: spec-correct I2C / I3C / CCC / HDR-DDR primitives plus
fault-injection knobs. moleasm is the lowest level of Layer 1 a
human writes directly; a higher-level SDK is planned to sit above it.

**LCR.** *Loop counter register.* One of two 8-bit hardware
counters (`lcr0`, `lcr1`) primed by `LOAD_LOOP` and decremented by
`DEC_BRANCH`. Independent registers enable one level of nested
loops without spilling to a scratch slot.

**Mark.** An 8-bit marker pushed into the result ring (`MARK
label=...`). Lets the host decoder discriminate between captures
from different parts of the program.

**Mask.** Flag (`mask=0|1`) on capturing opcodes. `mask=1` makes
the engine compare the read sample against `expect` and set
`MISMATCH_FLAG` on disagreement.

**Mismatch flag.** Sticky engine flag set when a capturing opcode
with `mask=1` saw a read that disagreed with `expect`. Cleared by
the next opcode that writes a flag.

**Mole Verde / Rojo / Negro.** The three SKUs in the Mole product
line. Verde is the pocket / per-developer dongle, Rojo the bench
tier, Negro the future certification tier. The bytecode contract is
identical across all three.

**moleasm.** The assembly language for Mole bytecode. This book is
about it.

**`.molecode`.** Bytecode file extension: packed little-endian
16-bit words, no header.

**`.mole.bin`.** Frame file extension: `len + words + CRC`, ready
to ship over UART.

**Open drain.** Drive-style where a pad can only sink current; the
line floats high through an external pull-up. The model for I2C and
I3C-OD electrical behaviour.

**Opcode.** The 5-bit field at `[15:11]` of every instruction. 15
v0 opcodes plus 17 reserved-v0.5 slots.

**Pass 1 / Pass 2.** The two phases of the assembler. Pass 1 lexes,
builds the symbol table, and assigns PC values. Pass 2 encodes each
statement into a 16-bit word.

**PC.** *Program counter.* The 12-bit address of the next
instruction in SPRAM. Advances by one per executed opcode unless a
branch / jump moves it.

**Push-pull.** Drive-style where a pad can both source and sink
current. The model for I3C-PP and HDR-DDR. Recessive in these
modes actively drives high; in OD modes recessive releases.

**Quarter / quarter-bit-time.** One tick of the engine's clock.
Every timing in moleasm (bit periods, stretch durations, wait
timeouts) is measured in quarters.

**Recessive.** Tx symbol that releases / asserts the line high
depending on bus mode. In OD modes it's a release (Hi-Z + external
pull-up); in PP modes it's an active drive.

**Repeated START.** Two STARTs without an intervening STOP. Spelled
out at the quarter level in moleasm; see the
[Patterns](./patterns.md) chapter.

**Reserved (v0.5).** Mnemonics, tx codes, and cond codes that the
ISA holds for a future revision. The assembler rejects them; `.dw`
exists for cases where you really need the raw bits.

**Result ring.** Small FIFO in the engine that holds captured bits
and marks until the host drainer reads them out.

**Sample.** Read SDA into the engine. Done implicitly by every
capturing opcode; explicit by `SAMPLE_BIT_ON_SCL` in target role.

**SCL.** I2C / I3C clock line. Driven by the controller.

**SDA.** I2C / I3C data line. Driven by either party, depending on
role and bus state.

**Sticky flag.** A single-bit engine state (mismatch, timeout,
start, stop) that, once set, stays set until overwritten by the
next flag-writing opcode.

**Status.** The 4-bit caller-defined value `HALT` records on
shutdown. By convention 0 = success.

**Target.** The bus party that follows SCL (i.e., the I2C "slave",
or the I3C target). Mole supports a target role via the
`SAMPLE_BIT_ON_SCL` and `DRIVE_BIT_ON_SCL` opcodes.

**Timing register.** One of four 10-bit registers that hold the
quarter-bit divider for the four bus modes. The active register is
selected by the current `BUS_MODE`. Written by `LOAD_TIMING`.

**Tx symbol.** One of `dominant`, `recessive`, `hiz` (plus the
reserved `0b11`). The per-bit / per-quarter drive vocabulary that
makes the engine bus-agnostic. See `AGENTS.md` §3.11.

**UART.** *Universal Asynchronous Receiver/Transmitter.* The
host link to Mole. Frames go in, results come out.

**Wire format.** The byte-level contract for what crosses UART
between host and engine. See `ROADMAP.md` §"Wire format" or
[Reference](./reference.md).

---

If a term shows up in source but not here, it almost certainly
lives in `ROADMAP.md` --- and probably should land here too the
next time you trip over it.
