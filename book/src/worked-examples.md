# Worked Examples

The fixtures in this chapter live as goldens under
`mole-asm/tests/fixtures/`. They are the same files the
assembler's integration tests verify byte-for-byte. Here we walk
through them line by line.

## first-light

The simplest meaningful program: drive a sustained square wave
on SDA and SCL so a scope user can verify the pads are alive.
Source:

```text
; first-light --- the smallest interesting Mole program.
;
; Configures I2C timing at 100 kHz, sets bus mode, then loops
; forever toggling SDA and SCL one quarter at a time.

.equ slow_div, 59

start:
    LOAD_TIMING       reg=0, divider=slow_div
    SET_BUS_MODE      i2c

loop:
    EMIT_QUARTER_IMM  sda=recessive scl=recessive
    EMIT_QUARTER_IMM  sda=dominant  scl=recessive
    EMIT_QUARTER_IMM  sda=dominant  scl=dominant
    EMIT_QUARTER_IMM  sda=recessive scl=dominant
    JMP               loop
```

Reading top to bottom:

- `.equ slow_div, 59` — the divider for exactly 100 kHz at
  Verde's 24 MHz fabric (`bit_rate = 24_000_000 / (4 * (59 + 1))
  = 100_000`). We name it so the relationship with
  `LOAD_TIMING` is visible at a glance.
- `LOAD_TIMING reg=0, divider=slow_div` — writes the divider into
  the I2C quarter-bit timing register (reg 0). The bus mode at
  reset is `i2c`, so this register is the active one.
- `SET_BUS_MODE i2c` — explicit (matches the reset default).
  `recessive` means "release the line; rely on the pull-up".
- The four `EMIT_QUARTER_IMM`s emit four quarter-bit edges
  manually — one full bit-time of bus toggle. Driving the
  *quarter* directly (rather than using `EMIT_BIT_IMM`) is
  intentional: it produces a guaranteed square wave on *both*
  lines, which is what makes the scope view unambiguous.
- `JMP loop` — sugar for `BRANCH_ON ALWAYS, loop`. Ties the
  program into an infinite loop; the engine never halts.

What you see on hardware: two 100 kHz square waves with a
deliberate phase shift between SDA and SCL.

## i2c-write-one-byte

Drives a complete I2C write transaction — START, address, ACK
check, data, ACK check, STOP — and halts with a status that
encodes whether either ACK was missed. The v0.2 form uses
`EMIT_BYTE_IMM` for both byte phases, which collapses each byte
+ ACK observation into a single instruction.

```text
.equ slow_div, 59           ; 100 kHz at 24 MHz fabric

        LOAD_TIMING       reg=0, divider=slow_div
        SET_BUS_MODE      i2c

        ; START (8 quarters; doubled-edge per i2c-soak convention)
        EMIT_QUARTER_IMM  sda=recessive scl=recessive
        EMIT_QUARTER_IMM  sda=recessive scl=recessive
        EMIT_QUARTER_IMM  sda=recessive scl=recessive
        EMIT_QUARTER_IMM  sda=recessive scl=recessive
        EMIT_QUARTER_IMM  sda=dominant  scl=recessive
        EMIT_QUARTER_IMM  sda=dominant  scl=recessive
        EMIT_QUARTER_IMM  sda=dominant  scl=dominant
        EMIT_QUARTER_IMM  sda=dominant  scl=dominant

        ; Address byte 0x90 (= 0x48 << 1 | W) + slave ACK
        EMIT_BYTE_IMM     imm=0x90 expect=0 mask=1 capture=1
        BRANCH_ON         MISMATCH, nak

        ; Data byte 0xAA + slave ACK
        EMIT_BYTE_IMM     imm=0xAA expect=0 mask=1 capture=1
        BRANCH_ON         MISMATCH, nak

        ; STOP (6 quarters)
        EMIT_QUARTER_IMM  sda=dominant  scl=dominant
        EMIT_QUARTER_IMM  sda=dominant  scl=dominant
        EMIT_QUARTER_IMM  sda=dominant  scl=recessive
        EMIT_QUARTER_IMM  sda=dominant  scl=recessive
        EMIT_QUARTER_IMM  sda=recessive scl=recessive
        EMIT_QUARTER_IMM  sda=recessive scl=recessive

        MARK              label=1
        HALT              status=0
nak:
        MARK              label=2
        HALT              status=1
```

Worth unpacking:

- **The address.** `EMIT_BYTE_IMM imm=0x90` emits 1001 0000
  MSB-first plus a ninth `hiz`-SDA ACK slot. One instruction
  per byte, including the ACK check. The flag triple
  (`expect=0 mask=1 capture=1`) applies to the ACK slot.
- **The branch.** `BRANCH_ON MISMATCH, nak` is the entire
  error-handling story. If the ACK was missed, jump to `nak`;
  that path halts with status 1 and a different `MARK`.
  Otherwise, fall through to the data byte.
- **`MARK` discriminates.** A host decoder reading the result
  ring sees either `MARK label=1` (success path) or `MARK
  label=2` (NAK path), with no ambiguity about which HALT
  status it's looking at.

This is the canonical "happy path with explicit failure exit"
pattern. Almost every protocol-level program follows it.

## tmp108

`tmp108.moleasm` is the real-world TMP108 temperature-read
sequence that drove the v0.2 silicon bring-up. The full source
is in `mole-asm/tests/fixtures/tmp108.moleasm`; the structure
follows the same shape as `i2c-write-one-byte` extended to
include a repeated START and the bit-by-bit read of two data
bytes:

1. **Write phase.** START, address `0x90` (write) + slave ACK,
   register pointer `0x00` + slave ACK.
2. **Repeated START.** Stop-free transition into a fresh
   START.
3. **Read phase.** Address `0x91` (read) + slave ACK, 8 bits
   of MSB (each `EMIT_BIT_IMM tx=recessive capture=1`), a
   controller ACK, 8 bits of LSB, a controller NACK.
4. **STOP.**

The 48-word fixture is bench-validated on iCEbreaker UP5K
silicon (REVISION 0.1.1): the loader reports 19 CAPTUREs (3
EMIT_BYTE_IMM ACKs + 8 MSB bits + 8 LSB bits) and a clean
`HALT status=0`.

Assemble it locally and inspect:

```sh
mole-asm assemble mole-asm/tests/fixtures/tmp108.moleasm --frame
mole-asm disassemble /tmp/tmp108.molecode    # see every word back
```

The interesting structural feature is the **repeated START
(Sr)**:

```text
        ; STOP-free transition: SDA high while SCL goes high,
        ; then a fresh START (10 quarters)
        EMIT_QUARTER_IMM  sda=dominant  scl=dominant
        EMIT_QUARTER_IMM  sda=dominant  scl=dominant
        EMIT_QUARTER_IMM  sda=recessive scl=dominant
        EMIT_QUARTER_IMM  sda=recessive scl=dominant
        EMIT_QUARTER_IMM  sda=recessive scl=recessive
        EMIT_QUARTER_IMM  sda=recessive scl=recessive
        EMIT_QUARTER_IMM  sda=dominant  scl=recessive
        EMIT_QUARTER_IMM  sda=dominant  scl=recessive
        EMIT_QUARTER_IMM  sda=dominant  scl=dominant
        EMIT_QUARTER_IMM  sda=dominant  scl=dominant
```

Ten quarter-bit edges — you cannot express this with
`EMIT_BIT_IMM` alone, which is exactly why `EMIT_QUARTER_IMM`
exists.

## tmp108-loop (sustained-load soak)

`tmp108-loop.moleasm` wraps the tmp108 read transaction in a
1000-iteration outer loop. Its purpose is to stress-test the
engine over a long sustained burst and to exercise the
result-ring overflow latch:

```text
.equ slow_div, 59
.equ iter_count, 1000

        LOAD_TIMING       reg=0, divider=slow_div
        SET_BUS_MODE      i2c
        SET_ROLE          controller

        LOAD_IMM          R5, iter_count    ; outer counter

loop_top:
        ; ... entire tmp108 read transaction ...
        STRETCH_SCL_IMM   3                 ; tBUF >= 7.5us
        DEC               R5
        BRANCH_ON         NOT_REG_ZERO, loop_top
        HALT              status=0
```

The 53-word program emits 19 CAPTUREs per iteration. The 2048-
word Verde result ring holds 2046 record slots (slot 0 =
REVISION, slot 2047 = HALT), so after 107 iterations the ring
fills, `ringOverflow` latches, and the remaining 893
iterations run normally on the wire but their captures are
dropped. The loader reports `halt: status=0x0 mismatch=false
overflow=true` along with 2046 captured bits.

This fixture is the canonical demonstration that the engine
runs back-to-back programs cleanly (per-program state — `R5`,
`MISMATCH_FLAG`, `ringWrPtr`, `revisionPending` — all reset on
the `io.engineStart` rising edge).

## What to take away

- Repetition and ladder structure dominate moleasm programs.
  The language deliberately doesn't have macros; the
  assembler's job is fidelity to the wire, not concision.
- Branching is sparse. Most programs are a straight line down
  with one or two `BRANCH_ON MISMATCH, ...` escape hatches and
  a `DEC` + `BRANCH_ON NOT_REG_ZERO, ...` back-edge if there
  is an outer loop.
- The hard part of writing moleasm isn't the syntax. It's
  deciding what tx symbols belong in each quarter so the
  resulting waveform matches the spec you're targeting.
  That's where the protocol knowledge lives, and that's why a
  planned Scheme SDK (Layer 1) exists — to spell those
  sequences once and reuse them.

The next chapter, [Patterns](./patterns.md), distills the
recurring moves from these examples into a small set of named
idioms.
