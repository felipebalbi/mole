# Worked Examples

The three programs in this chapter live as goldens under
`mole-asm/tests/fixtures/`. They are the same files the assembler's
integration tests verify byte-for-byte. Here we walk through them
line by line.

## first-light

The simplest meaningful program: drive a sustained square wave on
SDA and SCL so a scope user can verify the pads are alive. Source:

```text
; first-light --- the smallest interesting Mole program.
;
; Configures I2C timing at ~100 kHz, sets bus mode, then loops
; forever toggling SDA and SCL one quarter at a time.

.equ slow_div, 250

start:
    LOAD_TIMING   i2c_freq, slow_div
    SET_BUS_MODE  i2c

loop:
    EMIT_QUARTER  sda=recessive scl=recessive
    EMIT_QUARTER  sda=dominant  scl=recessive
    EMIT_QUARTER  sda=dominant  scl=dominant
    EMIT_QUARTER  sda=recessive scl=dominant
    JMP           loop
```

Reading top to bottom:

- `.equ slow_div, 250` --- the divider for ~100 kHz. We name it so
  the relationship with `LOAD_TIMING` is visible at a glance.
- `LOAD_TIMING i2c_freq, slow_div` --- writes the divider into the
  I2C timing register (reg 0).
- `SET_BUS_MODE i2c` --- selects the open-drain electrical mode.
  `recessive` now means "release the line; rely on the pull-up".
- The four `EMIT_QUARTER`s emit the four quarter-bit edges of a
  single bit-time. Driving the *quarter* directly (rather than
  using `EMIT_BIT`) is intentional: it produces a guaranteed
  square wave on *both* lines, which is what makes the scope view
  unambiguous.
- `JMP loop` ties the program into an infinite loop. The engine
  never halts.

What you see on hardware: two ~98 kHz square waves with a small
phase shift between SDA and SCL. (98 kHz, not exactly 100, because
the integer divider rounds; the [Errors](./errors.md) chapter has a
note on how to dial that in for your board.)

## i2c-write-one-byte

The `ROADMAP.md` §"Example: I2C write-one-byte in moleasm" worked
example. Drives a complete I2C write transaction --- START, address,
ACK check, data, ACK check, STOP --- and halts with a status that
encodes whether either ACK was missed.

```text
        LOAD_TIMING   i2c_freq, 250
        SET_BUS_MODE  i2c

        ; START
        EMIT_QUARTER  sda=recessive scl=recessive
        EMIT_QUARTER  sda=dominant  scl=recessive
        EMIT_QUARTER  sda=dominant  scl=dominant

        ; Address byte: 0x48 << 1 | W = 0x90 -> 1001 0000
        EMIT_BIT      tx=recessive       ; bit 7
        EMIT_BIT      tx=dominant        ; bit 6
        EMIT_BIT      tx=recessive       ; bit 5
        EMIT_BIT      tx=dominant        ; bit 4
        EMIT_BIT      tx=dominant        ; bit 3
        EMIT_BIT      tx=dominant        ; bit 2
        EMIT_BIT      tx=dominant        ; bit 1
        EMIT_BIT      tx=dominant        ; bit 0 (W = 0)

        ; ACK slot
        EMIT_BIT      tx=hiz expect=0 mask=1 capture=1
        BRANCH_ON     MISMATCH, nak

        ; Data byte: 0xAA -> 1010 1010
        EMIT_BIT      tx=recessive
        EMIT_BIT      tx=dominant
        EMIT_BIT      tx=recessive
        EMIT_BIT      tx=dominant
        EMIT_BIT      tx=recessive
        EMIT_BIT      tx=dominant
        EMIT_BIT      tx=recessive
        EMIT_BIT      tx=recessive

        ; ACK slot
        EMIT_BIT      tx=hiz expect=0 mask=1 capture=1
        BRANCH_ON     MISMATCH, nak

        ; STOP
        EMIT_QUARTER  sda=dominant  scl=dominant
        EMIT_QUARTER  sda=dominant  scl=recessive
        EMIT_QUARTER  sda=recessive scl=recessive

        MARK          label=1
        HALT          status=0
nak:
        MARK          label=2
        HALT          status=1
```

Worth unpacking:

- **The address.** I2C wants `address << 1 | RW` on the wire,
  MSB-first. We spell out each bit, alternating `dominant` (0) and
  `recessive` (1). The final bit is the R/W flag --- here 0 for a
  write.
- **The ACK slot.** `tx=hiz` releases SDA. `expect=0 mask=1` says
  "the target is supposed to pull SDA low here; flag a mismatch if
  not." `capture=1` pushes the actual read value into the result
  ring so the host can see what really happened.
- **The branch.** `BRANCH_ON MISMATCH, nak` is the entire
  error-handling story. If the ACK was missed, jump to `nak`; that
  path halts with status 1 and a different `MARK`. Otherwise, fall
  through to the data byte.
- **`MARK` discriminates.** A host decoder reading the result ring
  sees either `1, <captured bits>` (success) or `2, <captured
  bits>` (NAK), with no ambiguity.

This is the canonical "happy path with explicit failure exit"
pattern. Almost every protocol-level program follows it.

## tmp108

`tmp108.moleasm` is the real-world TMP108 temperature-read sequence
that drove much of phase-3 bring-up. The file is too long to inline
here in full but it follows the same shape as `i2c-write-one-byte`
twice in a row:

1. **Write phase.** START, address (write), ACK, register pointer,
   ACK, repeated START.
2. **Read phase.** Address (read), ACK, two data bytes (MSB then
   LSB), with the controller ACKing the MSB and NACKing the LSB,
   then STOP.

The fixture is 72 words long --- still small by general-purpose-CPU
standards, but a useful upper bound for "how much program does a
realistic single-transaction recipe take?"

The bytecode is committed; assemble it locally and look at the
side-by-side with the source if you want to see every detail:

```sh
mole-asm mole-asm/tests/fixtures/tmp108.moleasm --frame
```

The interesting structural feature is the **repeated START**:

```text
        ; STOP-free transition: hold SDA high while SCL goes high,
        ; then a fresh START
        EMIT_QUARTER  sda=recessive scl=dominant
        EMIT_QUARTER  sda=recessive scl=recessive
        EMIT_QUARTER  sda=dominant  scl=recessive
        EMIT_QUARTER  sda=dominant  scl=dominant
```

Four quarter-bit edges --- you cannot express this with `EMIT_BIT`
alone, which is exactly why `EMIT_QUARTER` exists.

## What to take away

- Repetition and ladder structure dominate moleasm programs. The
  language deliberately doesn't have macros; the assembler's job is
  fidelity to the wire, not concision.
- Branching is sparse. Most programs are a straight line down with
  one or two `BRANCH_ON MISMATCH, ...` escape hatches.
- The hard part of writing moleasm isn't the syntax. It's deciding
  what tx symbols belong in each quarter so the resulting waveform
  matches the spec you're targeting. That's where the protocol
  knowledge lives, and that's why the eventual Scheme SDK exists ---
  to spell those sequences once and reuse them.

The next chapter, [Patterns](./patterns.md), distills the recurring
moves from these examples into a small set of named idioms.
