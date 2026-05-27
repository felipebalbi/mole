# Mental Model

Before learning the syntax, you need a clear picture of the machine
moleasm targets. This chapter takes ~10 minutes to read and pays
back the rest of the book several times over.

## The bit-cycle engine in one paragraph

The engine is a small state machine inside the FPGA. Each
**quarter-bit clock tick** it does at most three things: drive the
SDA and SCL pads with the current word's symbols, sample what SDA
actually reads back, and (every fourth tick) advance the
**program counter (PC)** to the next 16-bit instruction in SPRAM.
That's the whole machine. There is no ALU, no general-purpose
register file, no stack. Just a tiny PC, a handful of sticky flags,
a result ring, and the two pads.

## Quarter-bit time

A **quarter-bit** is one tick of the engine's clock. Bits on the
wire are four quarters wide; SCL is high for two quarters and low
for two quarters in the nominal pattern. Every timing parameter you
ever set in moleasm is expressed in quarters:

- `LOAD_TIMING i2c_freq, 250` means "make the bit period 250 quarters".
- `STRETCH_SCL 12` means "hold the line in its current state for
  12 extra quarters".
- `WAIT_ON ..., 64` means "wait up to 64 quarters before timing out".

The fabric clock *is* the quarter-bit clock --- there is no PLL
multiplying it up. That's a deliberate ROADMAP constraint
(§"Quarter-bit timing") that keeps the engine analyzable: every
event in a moleasm program lives on the same quarter-tick grid.

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
PMBus support tomorrow? Add a `BUS_MODE` table entry and an SDK
wrapper. Zero ISA churn.

## The four sticky flags

The engine remembers four single-bit flags. They are *sticky*: once
set, they stay set until overwritten by the next opcode that would
write them (or, in v0.5, until an explicit `FLAG_CLEAR`).

| Flag             | Set when                                                    |
|------------------|-------------------------------------------------------------|
| `MISMATCH_FLAG`  | A capturing opcode (`EMIT_BIT`, `SAMPLE_BIT_ON_SCL`, ...) sampled a bit value that did not match its `expect` field, when `mask=1`. |
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
absolute `JMP` and PC-relative `BRANCH_ON`.

The **result ring** is a small FIFO. Capturing opcodes push to it;
the host's drainer empties it back over UART. When the engine
`HALT`s, the host reads:

- the HALT `status` code (4 bits, from the opcode's status field),
- the sticky-flag snapshot,
- any captured bits, in chronological order.

That's the complete output channel. Nothing else leaves the engine.

## Putting it together: anatomy of a tiny program

Here is the smallest non-trivial program: drive one I2C START.

```text
LOAD_TIMING   i2c_freq, 250         ; 250 quarters per bit -> ~100 kHz
SET_BUS_MODE  i2c                   ; recessive = Hi-Z
EMIT_QUARTER  sda=recessive scl=recessive   ; idle: both high
EMIT_QUARTER  sda=dominant  scl=recessive   ; pull SDA low -> START
EMIT_QUARTER  sda=dominant  scl=dominant    ; now pull SCL low
HALT          status=0
```

Reading top to bottom:

- The first two instructions configure the engine before any
  waveform appears.
- The three `EMIT_QUARTER`s walk the engine through the three
  quarter-bit-time edges of an I2C START condition.
- `HALT` stops the engine and records status 0.

Six instructions, twelve bytes of bytecode. The rest of the book is
about filling in everything between START and STOP.

You now have a working mental model. The next chapter formalises
the syntax you've been seeing in the snippets.
