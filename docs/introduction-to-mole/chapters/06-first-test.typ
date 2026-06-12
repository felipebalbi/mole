#import "../../presentation-template/lib.typ": *

// Part 6: First test. Walk through a real I2C single-byte write
// against the i2c-write-one-byte fixture, then show what the host
// reads back from the result ring (the ring layout itself was
// already introduced in Part 5).

#section-slide("06", "Your first test")

#content-slide(
  "The goal",
  kicker-text: "Worked example",
)[
  #align(center + horizon, stack(
    spacing: 1.2em,
    text(font: font-serif, size: 26pt, weight: "semibold", fill: ink)[
      Write one byte to address `0x50`.
    ],
    stack(
      dir: ltr,
      spacing: 1em,
      pill("2 bytes"),
      pill("2 ACKs"),
      pill("1 STOP"),
    ),
  ))
  #note[
    The smallest I2C transaction that actually does something useful.
    Mirrors `mole-asm/tests/fixtures/i2c-write-one-byte.moleasm`
    byte for byte -- pull the file up alongside and read along.
  ]
]

#try-it-slide(
  [Before you see the code: how many instructions do you expect
  for the address byte + ACK, given v0.2 has `EMIT_BYTE_IMM`?],
  hint: [Don't forget the ACK probe rides on the byte opcode.
  START is still a quarter-bit dance, not a bit.],
)

#code-slide(
  "Setup",
  kicker-text: "Mode + timing as opcodes",
)[
```
        LOAD_TIMING   reg=0, divider=59     ; 100 kHz @ 24 MHz
        SET_BUS_MODE  i2c                   ; SDA + SCL open-drain
```
]

#code-slide(
  "Start + address byte `0xA0`",
  kicker-text: "0x50 << 1 | W = 0xA0",
)[
```
        EMIT_QUARTER_IMM  sda=recessive scl=recessive   ; idle
        EMIT_QUARTER_IMM  sda=dominant  scl=recessive   ; START: SDA low
        EMIT_QUARTER_IMM  sda=dominant  scl=dominant    ; SCL low

        ; one byte + ACK observation -- one instruction
        EMIT_BYTE_IMM     imm=0xA0 expect=0 mask=1 capture=1
        BRANCH_ON         MISMATCH, nak
```
]

#code-slide(
  "What `EMIT_BYTE_IMM` does",
  kicker-text: "Eight data bits + one ACK slot",
)[
```
; Behind the one line:
;   bit 7 (MSB) -> 1   bit 6 -> 0   bit 5 -> 1   bit 4 -> 0
;   bit 3        -> 0   bit 2 -> 0   bit 1 -> 0   bit 0 -> 0   (W=0)
;   ACK slot: SDA hiz; target must drive low; we sample at Q2
```

  #align(center, text(
    font: font-serif, size: 16pt, style: "italic", fill: muted,
  )[
    The flag triple `expect=0 mask=1 capture=1` applies to the
    ACK slot. `MISMATCH_FLAG` set if the target NACKs.
  ])
]

#code-slide(
  "Data byte, STOP, halt",
  kicker-text: "Same shape -- second byte + ACK then STOP",
)[
```
        ; data byte 0xAB + ACK
        EMIT_BYTE_IMM     imm=0xAB expect=0 mask=1 capture=1
        BRANCH_ON         MISMATCH, nak

        EMIT_QUARTER_IMM  sda=dominant  scl=dominant
        EMIT_QUARTER_IMM  sda=dominant  scl=recessive   ; SCL rises
        EMIT_QUARTER_IMM  sda=recessive scl=recessive   ; SDA rises -> STOP

        MARK              label=1
        HALT              status=0
nak:    MARK              label=2
        HALT              status=1
```
]

#content-slide(
  "What `mole-loader` reports",
  kicker-text: "Two captures, one mark, clean halt",
)[
  #stack(
    spacing: 1em,
    code-panel(size: 14pt)[
```
revision: 0.1.1
records:  3 total (2 CAPTURE, 1 MARK)
  [   0] CAPTURE sda=0           ; first ACK: target pulled SDA low
  [   1] CAPTURE sda=0           ; second ACK: same
  [   2] MARK    label=0x01 ts=14380 cycles
halt:     status=0x0 mismatch=false overflow=false
```
    ],
    align(center, text(
      font: font-serif, size: 16pt, style: "italic", fill: muted,
    )[
      `MARK label=1` is the "ok path" breadcrumb the source asked for.
      `label=2` would have meant we took the `nak:` branch.
    ]),
  )
]

#recap-slide(
  "What Part 6 leaves you with",
  (
    [Start / address / ACK / data / ACK / STOP -- in moleasm, no surprises.],
    [`capture=1` is how a probe turns into a CAPTURE record on the ring.],
    [`MARK` labels turn into breadcrumbs the host decoder echoes back.],
  ),
  next: [breaking it on purpose -- injecting a single-quarter glitch.],
  deeper: [`book/src/worked-examples.md` -- the same write, end to end.],
)
