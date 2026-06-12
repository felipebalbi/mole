# Target Role

Mole's bit-cycle engine plays one of two roles, **controller**
(drives SCL, sources bits on its own clock) or **target**
(slaves to an external SCL). The role is **runtime-selectable
via the `SET_ROLE` opcode**: a single bitstream covers both
roles, and a program switches between them as needed.
`MoleConfig.role` only seeds the power-on default for the
`roleReg` register; `SET_ROLE` rewrites it any time after
boot.

Most of moleasm is role-agnostic. Two opcodes plus one
behavioural change cover what you need to know to write
target-role programs.

## The two target-role opcodes

### `SAMPLE_BIT_ON_SCL`

Wait for the next external SCL rising edge, sample SDA at that
edge, optionally compare and capture. Pure observer; drives
nothing.

```text
SAMPLE_BIT_ON_SCL capture=1                  ; record a data bit
SAMPLE_BIT_ON_SCL expect=0 mask=1            ; verify a known bit
```

Use this for byte-receive in target role (the controller sends
a byte, the target captures each bit).

### `DRIVE_BIT_ON_SCL`

Three-phase. (1) Wait for the next SCL falling edge, drive SDA
per `tx_symbol`. (2) Wait for the next SCL rising edge inside
the same cell, sample SDA (the wired-AND result), optionally
compare and capture. (3) Wait for the next falling edge,
release SDA.

```text
DRIVE_BIT_ON_SCL tx=dominant                 ; ACK a byte
DRIVE_BIT_ON_SCL tx=hiz                      ; NACK (release)
```

Use this for byte-send and ACK/NACK in target role.

## Role-changed behaviour

In target role the engine releases SCL by default. Two opcodes
behave differently:

- `EMIT_BIT_IMM` / `EMIT_BIT_REG` release SCL across the entire
  bit (the SCL drive regs stay `False`). The bit still pulses
  through its four quarters on the engine's own timer, but only
  SDA is driven. This is intentional: the EMIT_BIT pair remains
  legal in target role as an asynchronous SDA glitch / fake-byte
  injection path between transactions.
- `STRETCH_SCL_IMM` / `STRETCH_SCL_REG` are the one path by which
  a target may actively drive SCL low (canonical
  clock-stretching). The controller sees SCL stretched and waits.

Everything else --- `WAIT_ON`, `BRANCH_ON` (and its `JMP` sugar),
`MARK`, `HALT`, `LOAD_TIMING`, `SET_BUS_MODE`, `FLAG_CLEAR`, the
DATA-group ALU opcodes, the bounded-loop idiom (`DEC` +
`BRANCH_ON {REG_ZERO|NOT_REG_ZERO}`), sticky flags, and the
result-ring layout --- works identically in both roles.

## DAA arbitration

`DRIVE_BIT_ON_SCL` samples SDA at the rising edge while it is
itself driving. The sample reflects the wired-AND of every
target on the wire. If you drove `recessive` (release) but
read `dominant` (low), another target pulled the line --- you
lost the arbitration step. Combine with mask/expect compare
and `BRANCH_ON MISMATCH`:

```text
        DRIVE_BIT_ON_SCL tx=recessive expect=1 mask=1
        BRANCH_ON        MISMATCH, lost_arb
        ; ... still in the running for this address ...
lost_arb:
        ; release SDA, log the loss, withdraw from DAA
        HALT             status=1
```

This is exactly the I3C Dynamic Address Assignment loss
mechanic. The `BitCycleEngineTargetSim` test
`target-daa-arbitration` exercises it with two engine instances
on a wired-AND sim bus.

## Putting it together: target byte-receive + ACK

```text
.equ my_addr, 0xA5

        SET_BUS_MODE   i2c
        ; Wait for the controller to put a START on the wire.
        WAIT_ON        START_SEEN, 0          ; wait forever

        ; Capture 8 address bits.
        LOAD_LOOP         8
addr_loop:
        SAMPLE_BIT_ON_SCL capture=1
        DEC               R6
        BRANCH_ON         NOT_REG_ZERO, addr_loop

        ; Sample R/W bit.
        SAMPLE_BIT_ON_SCL capture=1

        ; Host compares the captured address to my_addr offline;
        ; for sim purposes, just ACK unconditionally.
        DRIVE_BIT_ON_SCL tx=dominant            ; ACK

        ; ... data phase (similar) ...

        WAIT_ON        STOP_SEEN, 0
        HALT           status=0
```

In a real target program the address compare would
typically be done with `expect`/`mask` on each
`SAMPLE_BIT_ON_SCL` (so `MISMATCH_FLAG` rolls up as a single
"this is not for me" branch), or by capturing all eight bits
then post-processing on the host. Both shapes are valid.

## When to pick controller vs target role

| You need to                        | Pick role  |
|------------------------------------|------------|
| Drive an I2C / I3C bus from scratch | controller |
| Inject glitches as the controller  | controller |
| Emulate a peripheral (TMP108, etc.) | target     |
| Exercise a controller-role driver  | target     |
| DAA-arbitration tests              | target     |

One bitstream; same Mole hardware; switch roles at runtime
with `SET_ROLE`.
