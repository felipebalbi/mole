# Introduction

`moleasm` is the assembly language of the **Mole bit-cycle engine** ---
the FPGA-resident state machine inside the Mole I3C / I2C compliance
rig. Where most assemblers target a CPU's instruction set, moleasm
targets a much narrower machine: one that emits **bus symbols** on
SDA and SCL at quarter-bit-time resolution, samples back what shows
up, and reports the results.

This book teaches that machine and its language end-to-end. By the
end you should be able to:

- Read any moleasm source in the repository and explain what waveform
  it produces.
- Write your own programs for bring-up, fault-injection, and
  conformance testing.
- Debug a program when the captured result doesn't match expectations.
- Look up the exact bit layout of any opcode without leaving the book.

## Why an assembly language at all?

Mole is split into two layers (see `ROADMAP.md` for the full
rationale):

- **Layer 0** is the FPGA bit-cycle engine. It executes 12 fixed-width
  16-bit opcodes and knows *nothing* about I2C, I3C, SMBus, or any
  other protocol. It only knows how to drive and sample symbols on a
  two-wire bus at a configured quarter-bit period.
- **Layer 1** is the host-side compiler stack. It is where the
  protocol knowledge lives: every I2C START, every I3C CCC, every
  HDR-DDR transition is *compiled* into Layer 0 bytecode.

`moleasm` is the lowest level of Layer 1 that humans write directly.
Above it sit higher-level languages (the eventual Scheme SDK,
ROADMAP §"Layer 1"); below it sits only the wire format
itself. When you write moleasm you are spelling out the bus
behaviour bit by bit, with no protocol layer hiding mistakes.

That sounds tedious --- and it would be, for everyday I2C / I3C
traffic. But moleasm exists precisely for the cases where everyday
abstractions get in the way: writing a START with a deliberately
short tHD;STA, holding SDA half a quarter-bit longer than the
spec allows, capturing what the target does on the eighth ACK
cycle. The whole point of the rig is to misbehave on purpose, and
moleasm is the precision tool for that job.

## Who this book is for

You are most likely:

- **A Mole contributor** writing bring-up programs, regression
  fixtures, or new test recipes.
- **A protocol engineer** investigating a misbehaving I2C or I3C
  target and reaching for Mole to construct a witness sequence.
- **A future-you** returning to a `.moleasm` file six months later
  and wondering what it does.

The book assumes you have read, or will read, **`ROADMAP.md` §"moleasm
conventions"** and **`AGENTS.md` §3 (the hard rules)**. Those are the
source of truth; this book teaches what's in them, with examples and
context the spec deliberately omits.

## How to read this book

The book has three sections.

| Section   | Chapters | When to read                                                                                  |
|-----------|----------|-----------------------------------------------------------------------------------------------|
| Tutorial  | 2--7     | Read straight through the first time. Each chapter builds on the previous one.                |
| Reference | 8--10    | Skim once so you know what's there. Come back when you need a table or a vocabulary refresher.|

Code samples are all real --- they assemble cleanly under the
committed `mole-asm` crate and either run on hardware or sit under
`mole-asm/tests/fixtures/` as goldens.

## Conventions used in this book

- **Filenames**, paths, and shell commands appear in `code style`.
- **Source listings** are fenced with `text` for moleasm fragments and
  with `rust` / `sh` for the corresponding host code.
- The terms **dominant**, **recessive**, and **Hi-Z** mean what they
  mean in the engine's `tx_symbol` vocabulary (see chapter "Mental
  Model"); they are *not* I2C / I3C jargon.
- Cross-references to other repository docs use exact section
  numbers, e.g. *AGENTS.md §3.10*, so the link stays useful even if
  the headings move.

Ready? On to the [Quickstart](./quickstart.md).
