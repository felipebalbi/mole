# Patterns

A handful of idioms turn up over and over again. Internalising them
will get you past "I can read moleasm" to "I can write
production-quality moleasm" much faster than memorising the opcodes
ever would.

## START and STOP

I2C / I3C START and STOP are *always* spelled out as `EMIT_QUARTER`
triples. The canonical patterns:

```text
; START: SDA falls while SCL is high
EMIT_QUARTER sda=recessive scl=recessive     ; idle
EMIT_QUARTER sda=dominant  scl=recessive     ; pull SDA low
EMIT_QUARTER sda=dominant  scl=dominant      ; pull SCL low

; STOP: SDA rises while SCL is high
EMIT_QUARTER sda=dominant  scl=dominant      ; both low
EMIT_QUARTER sda=dominant  scl=recessive     ; release SCL
EMIT_QUARTER sda=recessive scl=recessive     ; release SDA
```

There is no opcode named `START` or `STOP`. They are deliberately
spelled out at the quarter level because that is the level where
your fault-injection knobs live. When you want to violate setup or
hold time, this is where you do it.

## Repeated START

```text
; STOP-free transition from data into a fresh START
EMIT_QUARTER sda=recessive scl=dominant       ; release SDA while SCL low
EMIT_QUARTER sda=recessive scl=recessive      ; release SCL (both high)
EMIT_QUARTER sda=dominant  scl=recessive      ; pull SDA low (START)
EMIT_QUARTER sda=dominant  scl=dominant       ; pull SCL low
```

The hallmark of a repeated START is that there is no idle state
between the previous data bit and the new START --- compare with the
two-line transition idle / START above.

## Sending an address or data byte (controller role)

Eight `EMIT_BIT`s, MSB first, with the last bit being the R/W flag
on address bytes:

```text
; 0x90 = 1001 0000 -> address 0x48, write
EMIT_BIT tx=recessive
EMIT_BIT tx=dominant
EMIT_BIT tx=recessive
EMIT_BIT tx=dominant
EMIT_BIT tx=dominant
EMIT_BIT tx=dominant
EMIT_BIT tx=dominant
EMIT_BIT tx=dominant
```

There is no "byte" opcode. The reason is that every individual bit
is a candidate for injection or measurement --- e.g. holding a single
data bit at Hi-Z to test how the target reacts.

## ACK slot

```text
; Controller listens for the target to ACK
EMIT_BIT tx=hiz expect=0 mask=1 capture=1
BRANCH_ON MISMATCH, nak_handler
```

`tx=hiz` releases SDA so the target can drive it. `expect=0` says we
expect a low (ACK). `mask=1` makes the engine compare and set
`MISMATCH_FLAG` if the target NACKed. `capture=1` pushes the actual
sample into the result ring so the host can see the *value*, not
just the *outcome*.

For the reverse direction --- controller ACKing or NACKing a byte
the target wrote:

```text
EMIT_BIT tx=dominant      ; controller ACK
EMIT_BIT tx=recessive     ; controller NACK (last byte before STOP)
```

## ACK-then-branch (fail-fast escape)

The combined pattern of "do something that might fail, then branch
out if it did" is the entire structured error-handling vocabulary in
moleasm. There is no `if`, no `try`/`catch`, no exception. There is
this:

```text
    EMIT_BIT      tx=hiz expect=0 mask=1 capture=1
    BRANCH_ON     MISMATCH, fail
    ; ... happy path continues here ...
    JMP           done

fail:
    MARK          label=2
    HALT          status=1

done:
    MARK          label=1
    HALT          status=0
```

Three rules that make this pattern bullet-proof:

1. **Every fail path has its own `MARK`.** The host can distinguish
   "failed at the address ACK" from "failed at the data ACK" just
   by reading the marks back.
2. **The branch target is always *after* the failing instruction.**
   `BRANCH_ON` doesn't reset `MISMATCH_FLAG`; you want the branch to
   reflect *that* mismatch, not an older one.
3. **Halt cleanly on both paths.** The engine doesn't auto-stop;
   forget the trailing `HALT` and it falls into whatever was next.

## Bounded retry loops

```text
.equ retry_limit, 3
.equ slot_idx,    0       ; placeholder; you would track this off-chip
; ...
loop:
    ; ... do one attempt ...
    EMIT_BIT      tx=hiz expect=0 mask=1 capture=1
    BRANCH_ON     NOT_MISMATCH, done
    ; NB: there is no counter in the engine; retry counts are encoded
    ; by laying out the attempts in the source.
    BRANCH_ON     ALWAYS, loop
done:
    HALT
```

The engine has **no general-purpose registers**, so a counted retry
loop is genuinely impossible at the engine level: you express it by
unrolling. For larger counts, lean on `JMP` and on layering above
moleasm.

## Capture-only reads

When you just want to see what the bus does without comparing
against an expectation:

```text
EMIT_BIT          tx=hiz capture=1            ; controller-role
SAMPLE_BIT_ON_SCL capture=1                   ; target-role
```

The host reads the captured bits in order. Drop a `MARK` between
groups of reads to separate them.

## Marking phases

For longer programs, drop a `MARK` whenever you cross a logical
boundary:

```text
MARK label=0x10           ; "starting address phase"
; ... address bits + ACK ...
MARK label=0x11           ; "starting data phase"
; ... data bits + ACK ...
MARK label=0x12           ; "STOP"
```

Result-ring decoders use the marks to chunk captures into logical
records. A consistent labelling scheme across your test corpus
(e.g. high nibble = phase, low nibble = attempt) pays off quickly.

## Bus-mode handoff

For programs that move between I2C and I3C, treat every transition
explicitly:

```text
SET_BUS_MODE  i2c         ; recessive = Hi-Z
LOAD_TIMING   i2c_freq, 250
; ... I2C transaction ...

SET_BUS_MODE  i3c-od      ; recessive = Hi-Z (different timing reg)
LOAD_TIMING   i3c_od_freq, 6
; ... I3C broadcast ...

SET_BUS_MODE  i3c-pp      ; recessive = push-pull high
LOAD_TIMING   i3c_pp_freq, 3
; ... I3C push-pull traffic ...
```

`LOAD_TIMING` always targets the timing register *for the current
bus mode*. You can write it before or after `SET_BUS_MODE` as long
as you've set the mode you intend by the time you start emitting
bits.

## Synchronising on external events

Use `WAIT_ON` when the next instruction must not run until the bus
reaches a specific state:

```text
WAIT_ON START_SEEN, 200        ; sleep until a START or 200 quarters
BRANCH_ON TIMEOUT, no_start

WAIT_ON SDA_LOW, 64
BRANCH_ON TIMEOUT, sda_stuck_high
```

`WAIT_ON` sets `TIMEOUT_FLAG` when its timer expires; the immediate
`BRANCH_ON TIMEOUT, ...` is the standard idiom for distinguishing
"condition occurred" from "we gave up". Without a follow-up branch,
the timeout is silently ignored.

## When you really need a raw word

Sometimes you want to emit an opcode the assembler refuses (a
reserved-v0.5 mnemonic, an experiment with the reserved tx code
`0b11`). `.dw` is the escape:

```text
; Reserved v0.5 FLAG_CLEAR (opcode 0xC, encoding TBD)
.dw 0xC000
```

There are exactly two reasons to do this:

1. You are writing test bytecode for the engine itself, deliberately
   exercising opcodes the language won't yet emit.
2. You are working around an assembler limitation while a fix is in
   flight.

Use sparingly; comment loudly; track an issue.

That's the pattern catalog. With these in your head, you can read or
write any moleasm program in the repository. The remaining chapters
are reference material.
