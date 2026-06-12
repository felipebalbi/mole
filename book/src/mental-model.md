# Mental Model

Before learning the syntax, you need a clear picture of the machine
moleasm targets. This chapter takes ~10 minutes to read and pays
back the rest of the book several times over.

## The bit-cycle engine in one paragraph

The engine is a small state machine inside the FPGA. Each
**quarter-bit clock tick** it does at most three things: drive the
SDA and SCL pads with the current word's symbols, sample what SDA
actually reads back, and (every fourth tick) advance the
**program counter (PC)** to the next 32-bit instruction in SPRAM.
The machine has a small ALU (DATA-group opcodes), an
eight-entry 32-bit register file (`R0`..`R7`), a 13-bit PC, a
five-flag sticky set (MISMATCH / TIMEOUT / START / STOP / REG_ZERO),
a result ring, and the two pads. The architecture is
straight-line plus PC-relative branches; there is no call
stack or interrupt handler.

## Quarter-bit time

A **quarter-bit** is one tick of the engine's quarter-bit timer.
Bits on the wire are four quarters wide; SCL is high for two
quarters and low for two quarters in the nominal pattern. Every
timing parameter you ever set in moleasm is expressed in quarters
or fabric-cycles-per-quarter:

- `LOAD_TIMING reg=0, divider=59` means "make every quarter 60
  fabric cycles long" (see [Picking a divider](#picking-a-divider)
  below). At Verde's 24 MHz fabric, that lands at ~100 kHz on the
  wire.
- `STRETCH_SCL_IMM 12` means "hold the line in its current state for
  12 extra quarters".
- `WAIT_ON ..., 64` means "wait up to 64 quarters before timing
  out".

The fabric clock *is* the engine's clock --- there is no PLL
multiplying it up. That's a deliberate ROADMAP constraint
(§"Quarter-bit timing") that keeps the engine analyzable: every
event in a moleasm program lives on the same fabric-cycle grid.
What's programmable is *how many fabric cycles long a single
quarter is*; the wire-side bit rate falls out of that choice via
`bit_rate = fabric / (4 * (N + 1))`.

## Picking a divider

`LOAD_TIMING reg=R, divider=N` writes the 14-bit value `N` into
one of 8 timing registers in the WIRE sub-block. The
quarter-bit divider registers (regs 0, 2, 4, 6) feed the
**quarter-bit timer**, which produces one tick every `N + 1`
fabric cycles. Four ticks make one bit on the wire, so the
formula is:

```text
bit_rate = fabric_clock / (4 * (N + 1))
```

The 8 timing registers map to (BUS_MODE, knob) pairs:

| `reg` | BUS_MODE  | Knob                              |
|-------|-----------|-----------------------------------|
| 0     | `i2c`     | Quarter-bit divider (`tQB_OD`)    |
| 1     | `i2c`     | Data hold (`tHD_DAT`)             |
| 2     | `i3c-OD`  | Quarter-bit divider (`tQB_OD`)    |
| 3     | `i3c-OD`  | Data set-up (`tSU_DAT`)           |
| 4     | `i3c-PP`  | Quarter-bit divider (`tQB_PP`)    |
| 5     | `i3c-PP`  | Data set-up (`tSU_DAT`)           |
| 6     | `hdr-ddr` | Quarter-bit divider (`tQB_PP`)    |
| 7     | `hdr-ddr` | Data set-up (`tSU_DAT`)           |

For Verde's 24 MHz fabric, the quarter-bit divider (`reg=0` for
I2C) covers:

| Target bit rate          | `N`  | Achieved          | Notes                                                                            |
|--------------------------|------|-------------------|----------------------------------------------------------------------------------|
| 100 kHz (Standard mode)  | 59   | 100 kHz exactly   | Use `divider=59`; the round number `60` lands at ~98.4 kHz, also in spec.        |
| 400 kHz (Fast mode)      | 14   | 400 kHz exactly   |                                                                                  |
| 1 MHz (Fast-mode Plus)   | 5    | 1 MHz exactly     | The reset default (`quarterPeriodCyclesReset = 6 -> reload = 5`).                |
| 3 MHz                    | 1    | 3 MHz exactly     | Two fabric cycles per quarter.                                                   |
| 6 MHz                    | 0    | 6 MHz exactly     | One fabric cycle per quarter --- the cap on Verde.                               |
| 3.4 MHz (High-speed)     | --   | **not achievable**| Closest dividers (`1`, `0`) land at 3 MHz / 6 MHz. See "Why no 3.4 MHz" below.   |

### Why no 3.4 MHz on Verde

3.4 MHz I2C High-speed mode wants the quarter-bit timer to tick
at `4 * 3.4 MHz = 13.6 MHz`. The ROADMAP fixes that **the fabric
clock IS the quarter-bit clock --- there is no PLL multiplying
it up.** So the only quarter rates Verde's 24 MHz fabric can
produce are integer divisions of 24 MHz: 24, 12, 8, 6, 4.8, 4,
... MHz. None of them is 13.6 MHz, and so no `N` produces
exactly 3.4 MHz. The closest you can get is `N = 1` -> 3 MHz, or
`N = 0` -> 6 MHz.

To hit 3.4 MHz exactly you need a board whose fabric clock is a
multiple of 13.6 MHz (27.2 MHz, 40.8 MHz, 54.4 MHz, ...). That's
a board respin --- the **Negro** tier on the ROADMAP. The
trade-off is conscious: keeping the fabric / quarter-bit clocks
identical is what makes every timing budget in the engine a
single number of fabric cycles, which is what made closing
24 MHz on the iCEbreaker UP5K possible at all.

## The two-symbol vocabulary

Everything the engine drives on the bus is one of three values per
pad:

| Symbol      | Wire-name code | Meaning on the bus                                    |
|-------------|----------------|-------------------------------------------------------|
| `dominant`  | `dom`          | Drive low (overrides any pull-up).                    |
| `recessive` | `rec`          | Release high (rely on the external pull-up to pull). |
| `hiz`       |                | Tri-state. Same electrical effect as `recessive` but no active drive; reserved for ACK sampling and target-role release. |

Crucially, the *meaning* of `recessive` depends on the active
**bus mode**:

| Bus mode       | `recessive` drives                                  | When to use                          |
|----------------|-----------------------------------------------------|--------------------------------------|
| `i2c`          | Hi-Z (open-drain --- external pull-up does the work)| I2C transactions                     |
| `i3c-od`       | Hi-Z                                                | I3C open-drain bus arbitration       |
| `i3c-pp`       | Push-pull high                                      | I3C push-pull mode (after broadcast) |
| `hdr-ddr`      | Push-pull high                                      | I3C HDR-DDR bursts                   |

This separation is load-bearing. The bytecode never has to know what
protocol you are running --- it just says "now drive the recessive
symbol" --- and the `BUS_MODE` register decides whether that means
"release the line" or "actively pull it high". Want to add SMBus or
PMBus support tomorrow? Add a `BUS_MODE` table entry and a macro
layer on top. Zero ISA churn.

## The four sticky flags

The engine remembers four single-bit flags. They are *sticky*: once
set, they stay set until overwritten by the next opcode that would
write them (or, in v0.5, until an explicit `FLAG_CLEAR`).

| Flag             | Set when                                                    |
|------------------|-------------------------------------------------------------|
| `MISMATCH_FLAG`  | A capturing opcode (`EMIT_BIT_IMM`, `EMIT_BYTE_IMM`, `SAMPLE_BIT_ON_SCL`, ...) sampled a bit value that did not match its `expect` field, when `mask=1`. |
| `TIMEOUT_FLAG`   | A `WAIT_ON` opcode reached its timeout without the condition becoming true. |
| `START_FLAG`     | A bus-level I2C / I3C START was observed.                  |
| `STOP_FLAG`      | A bus-level STOP was observed.                             |

Sticky flags are how programs reason about asynchronous bus events:
write a long sequence that drives bits, branch at the end on whether
anything mismatched, and behave differently depending on what
happened along the way.

## The two flag-bearing operand fields

Opcodes that emit bits also carry an `expect / mask / capture` triple
at bits `[2:0]` of the instruction word. Their meaning is:

- `expect = 0|1` --- the value the engine expects to read back on SDA
  for this bit. The special value `X` ("don't care") leaves the
  expect field at zero but only makes sense with `mask=0`.
- `mask = 0|1` --- whether to enforce the expect. `mask=0` means
  "don't compare"; `mask=1` means "set `MISMATCH_FLAG` if the read
  value differs from `expect`".
- `capture = 0|1` --- whether to push the read bit into the result
  ring for the host to consume.

The combinations have names you will see throughout the book:

| `expect`/`mask`/`capture` | What it does                                                 |
|---------------------------|--------------------------------------------------------------|
| `X / 0 / 0`               | Don't expect anything, don't capture. The default.           |
| `0 / 1 / 0`               | "I expect this bit to be ACKed (dominant). Flag mismatch."   |
| `X / 0 / 1`               | "I don't care what shows up, but record it."                 |
| `0 / 1 / 1`               | "ACK slot: expect low, *and* push to the ring."              |

`expect=X` with `mask=1` is contradictory and is rejected at parse
time --- a don't-care can't fail an expect check.

## The PC and the result ring

The PC advances by one after every instruction unless a branch or
jump moves it. There is no return stack: control flow is just
PC-relative `BRANCH_ON` (with `JMP <label>` sugar for `BRANCH_ON
ALWAYS, <label>`).

The **result ring** is a 32-bit-grained record stream in SPRAM.
The engine emits a `REVISION` word at slot 0 on every program
start, then a contiguous stream of `CAPTURE` (1 word) and `MARK`
(3 words) records, then a `HALT` word at the last slot
(`resultLimit`). On overflow the engine drops further records
and latches `overflow=1` in the HALT word's `[29]` bit. The
host drainer streams the entire ring back over UART when the
engine `HALT`s. When decoded, the host sees:

- the HALT `status` code (5 bits, from the opcode's status field),
- the `MISMATCH_FLAG` snapshot at HALT entry,
- the `overflow` sticky flag,
- any captured bits and marks, in chronological order.

That's the complete output channel. Nothing else leaves the engine.

## Putting it together: anatomy of a tiny program

Here is the smallest non-trivial program: drive one I2C START.

```text
LOAD_TIMING       reg=0, divider=59     ; 60 fabric cycles per quarter -> ~100 kHz @ Verde 24 MHz
SET_BUS_MODE      i2c                   ; recessive = Hi-Z
EMIT_QUARTER_IMM  sda=recessive scl=recessive   ; idle: both high
EMIT_QUARTER_IMM  sda=dominant  scl=recessive   ; pull SDA low -> START
EMIT_QUARTER_IMM  sda=dominant  scl=dominant    ; now pull SCL low
HALT              status=0
```

Reading top to bottom:

- The first two instructions configure the engine before any
  waveform appears.
- The three `EMIT_QUARTER_IMM`s walk the engine through the three
  quarter-bit-time edges of an I2C START condition.
- `HALT` stops the engine and records status 0.

Six instructions, 24 bytes of bytecode (plus the 8-byte preamble
and 2-byte CRC trailer if framed). The rest of the book is
about filling in everything between START and STOP.

You now have a working mental model. The next chapter formalises
the syntax you've been seeing in the snippets.
