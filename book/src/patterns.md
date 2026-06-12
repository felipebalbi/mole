# Patterns

A handful of idioms turn up over and over again. Internalising them
will get you past "I can read moleasm" to "I can write
production-quality moleasm" much faster than memorising the opcodes
ever would.

## START and STOP

I2C / I3C START and STOP are *always* spelled out as
`EMIT_QUARTER_IMM` runs. The canonical patterns (each quarter
held for two ticks to match the i2c-soak / tmp108 fixtures):

```text
; START: SDA falls while SCL is high (8 quarters)
EMIT_QUARTER_IMM sda=recessive scl=recessive     ; idle
EMIT_QUARTER_IMM sda=recessive scl=recessive
EMIT_QUARTER_IMM sda=recessive scl=recessive
EMIT_QUARTER_IMM sda=recessive scl=recessive
EMIT_QUARTER_IMM sda=dominant  scl=recessive     ; pull SDA low
EMIT_QUARTER_IMM sda=dominant  scl=recessive
EMIT_QUARTER_IMM sda=dominant  scl=dominant      ; pull SCL low
EMIT_QUARTER_IMM sda=dominant  scl=dominant

; STOP: SDA rises while SCL is high (6 quarters)
EMIT_QUARTER_IMM sda=dominant  scl=dominant      ; both low
EMIT_QUARTER_IMM sda=dominant  scl=dominant
EMIT_QUARTER_IMM sda=dominant  scl=recessive     ; release SCL
EMIT_QUARTER_IMM sda=dominant  scl=recessive
EMIT_QUARTER_IMM sda=recessive scl=recessive     ; release SDA
EMIT_QUARTER_IMM sda=recessive scl=recessive
```

There is no opcode named `START` or `STOP`. They are deliberately
spelled out at the quarter level because that is the level where
your fault-injection knobs live. When you want to violate setup
or hold time, this is where you do it.

## Repeated START (Sr)

```text
; STOP-free transition from data into a fresh START (10 quarters)
EMIT_QUARTER_IMM sda=dominant  scl=dominant       ; both low from last bit
EMIT_QUARTER_IMM sda=dominant  scl=dominant
EMIT_QUARTER_IMM sda=recessive scl=dominant       ; release SDA while SCL low
EMIT_QUARTER_IMM sda=recessive scl=dominant
EMIT_QUARTER_IMM sda=recessive scl=recessive      ; release SCL (both high)
EMIT_QUARTER_IMM sda=recessive scl=recessive
EMIT_QUARTER_IMM sda=dominant  scl=recessive      ; pull SDA low (START)
EMIT_QUARTER_IMM sda=dominant  scl=recessive
EMIT_QUARTER_IMM sda=dominant  scl=dominant       ; pull SCL low
EMIT_QUARTER_IMM sda=dominant  scl=dominant
```

The hallmark of a repeated START is that there is no STOP between
the previous data bit and the new START.

## Sending an address or data byte (controller role)

Two equally legal forms. Pick by whether the byte is known at
compile time or computed at run time.

### Compile-time byte: `EMIT_BYTE_IMM`

```text
; 0x90 = address 0x48, R/W=0 (write); slave ACK observed inline
EMIT_BYTE_IMM imm=0x90 expect=0 mask=1 capture=1
```

One instruction emits the eight data bits MSB-first AND the
ninth ACK slot. The flag triple applies to the ACK slot. This
is the right tool for I2C addresses, CCC, register pointers —
any byte the program knows up front.

### Runtime byte: `EMIT_BYTE_REG`

```text
; Byte to send was captured / computed earlier; it's in R7
LOAD_IMM      R7, 0x90
EMIT_BYTE_REG expect=0 mask=1 capture=1
```

`EMIT_BYTE_REG` reads `R7[7:0]` at execute time. Useful in
write-then-read flows where the byte was captured earlier and
needs to be retransmitted, or when the byte is computed by
`ADD_IMM` / `SHIFT` / `OR_IMM`.

### Bit-by-bit: 8 × `EMIT_BIT_IMM`

```text
; 0x90 = 1001 0000, MSB first
EMIT_BIT_IMM tx=recessive       ; bit 7 = 1
EMIT_BIT_IMM tx=dominant        ; bit 6 = 0
EMIT_BIT_IMM tx=dominant        ; bit 5 = 0
EMIT_BIT_IMM tx=recessive       ; bit 4 = 1
EMIT_BIT_IMM tx=dominant        ; bit 3 = 0
EMIT_BIT_IMM tx=dominant        ; bit 2 = 0
EMIT_BIT_IMM tx=dominant        ; bit 1 = 0
EMIT_BIT_IMM tx=dominant        ; bit 0 = 0
; ACK slot:
EMIT_BIT_IMM tx=hiz expect=0 mask=1 capture=1
```

Use the bit-by-bit form when every individual bit is a candidate
for fault injection (setup / hold violation, single-bit glitch,
mid-byte stretching). The byte forms are a convenience for the
common case.

## ACK slot

```text
; Controller listens for the target to ACK
EMIT_BIT_IMM tx=hiz expect=0 mask=1 capture=1
BRANCH_ON    MISMATCH, nak_handler
```

`tx=hiz` releases SDA so the target can drive it. `expect=0`
says we expect a low (ACK). `mask=1` makes the engine compare
and set `MISMATCH_FLAG` if the target NACKed. `capture=1` pushes
the actual sample into the result ring so the host can see the
*value*, not just the *outcome*.

For the reverse direction — controller ACKing or NACKing a byte
the target wrote:

```text
EMIT_BIT_IMM tx=dominant      ; controller ACK
EMIT_BIT_IMM tx=recessive     ; controller NACK (last byte before STOP)
```

## ACK-then-branch (fail-fast escape)

The combined pattern of "do something that might fail, then
branch out if it did" is the entire structured error-handling
vocabulary in moleasm. There is no `if`, no `try`/`catch`, no
exception. There is this:

```text
    EMIT_BIT_IMM  tx=hiz expect=0 mask=1 capture=1
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

Pick any GP register, prime it with `LOAD_IMM` (or `LOAD_LOOP`
sugar for R6), do the work, end with `DEC` + `BRANCH_ON
NOT_REG_ZERO` back to the start:

```text
.equ retry_limit, 3

        LOAD_LOOP     retry_limit         ; R6 = 3
attempt:
        ; ... do one attempt ...
        EMIT_BIT_IMM  tx=hiz expect=0 mask=1 capture=1
        BRANCH_ON     NOT_MISMATCH, done  ; success: skip the back-edge
        DEC           R6
        BRANCH_ON     NOT_REG_ZERO, attempt  ; failure: count down, retry
        ; fall through here when retries are exhausted
        HALT          status=1
done:
        HALT          status=0
```

Three notes:

1. `DEC R6` decrements *before* `BRANCH_ON` consumes
   `REG_ZERO_FLAG`, so `LOAD_LOOP N` runs the loop body exactly
   `N` times before falling through.
2. `DEC` writes `REG_ZERO_FLAG` but **not** `MISMATCH_FLAG`, so a
   `BRANCH_ON MISMATCH` inside the loop still observes the most
   recent capture's mismatch (not a stale one from elsewhere).
3. The assembler emits `LINT-001` if any other ALU op sits
   between the `DEC` and the consuming `BRANCH_ON REG_ZERO` /
   `NOT_REG_ZERO`. The interloper would silently clobber
   `REG_ZERO_FLAG`.

For nested loops, use any register pair: outer on `R5`, inner
on `R6`, deeper inner on `R4`, etc. The [Bounded
loops](./bounded-loops.md) chapter walks through worked
examples.

## Capture-only reads

When you just want to see what the bus does without comparing
against an expectation:

```text
EMIT_BIT_IMM      tx=hiz capture=1            ; controller-role
SAMPLE_BIT_ON_SCL capture=1                   ; target-role
```

The host reads the captured bits in order. Drop a `MARK` between
groups of reads to separate them. Each capture also lands in
`R7[0]` so subsequent ALU / branch ops can react inline.

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

Result-ring decoders use the marks to chunk captures into
logical records. A consistent labelling scheme across your test
corpus (e.g. high byte = phase, low byte = attempt) pays off
quickly.

## Bus-mode handoff

For programs that move between I2C and I3C, treat every
transition explicitly:

```text
SET_BUS_MODE  i2c              ; recessive = Hi-Z
LOAD_TIMING   reg=0, divider=59
; ... I2C transaction ...

SET_BUS_MODE  i3c-od           ; recessive = Hi-Z (different timing reg)
LOAD_TIMING   reg=2, divider=5
; ... I3C broadcast ...

SET_BUS_MODE  i3c-pp           ; recessive = push-pull high
LOAD_TIMING   reg=4, divider=3
; ... I3C push-pull traffic ...
```

`LOAD_TIMING` writes a specific timing register by index (0..7).
You can write registers in any order, before or after
`SET_BUS_MODE`, as long as the register the active `BUS_MODE`
will consult is correct by the time you start emitting bits.

## Synchronising on external events

Use `WAIT_ON` when the next instruction must not run until the
bus reaches a specific state:

```text
WAIT_ON START_SEEN, 200        ; sleep until a START or 200 quarters
BRANCH_ON TIMEOUT, no_start

WAIT_ON SDA_LOW, 64
BRANCH_ON TIMEOUT, sda_stuck_high
```

`WAIT_ON` sets `TIMEOUT_FLAG` when its timer expires; the
immediate `BRANCH_ON TIMEOUT, ...` is the standard idiom for
distinguishing "condition occurred" from "we gave up". Without a
follow-up branch, the timeout is silently ignored.

## Computed values with the DATA group

v0.2 adds an 8x32-bit GP register file and a small ALU. Use them
when the program needs to derive a value:

```text
; Read MSB and LSB of a 16-bit temperature, combine into R0
EMIT_BIT_IMM  tx=recessive capture=1     ; MSB bit 7 -> R7[0]
SHIFT         R0, R0, left, 1            ; R0 := R0 << 1
OR_IMM        R0, R0, 1                  ; R0[0] := R7[0] via tail bit
; ... repeat for the other 15 bits ...
```

Most programs do not need this — captures land in the host's
result ring and the host can do the math offline. The on-engine
ALU is for cases where the program needs to *branch* on a
computed value before it sends the next instruction.

## When you really need a raw word

Sometimes you want to emit an opcode the assembler refuses (a
reserved-v0.5 mnemonic, an experiment with the reserved tx code
`0b11`). `.dw` is the escape:

```text
; Reserved v0.5 CAPTURE_RUN (encoding TBD)
.dw 0xC000_0000
```

There are exactly two reasons to do this:

1. You are writing test bytecode for the engine itself,
   deliberately exercising opcodes the language won't yet emit.
2. You are working around an assembler limitation while a fix is
   in flight.

Use sparingly; comment loudly; track an issue.

That's the pattern catalog. With these in your head, you can
read or write any moleasm program in the repository. The
remaining chapters are reference material.
