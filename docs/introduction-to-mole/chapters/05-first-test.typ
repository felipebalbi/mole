#import "../lib.typ": *
#import "../figures/ring-layout.typ": ring-layout-figure

// Part 5: First test. Walk through a real I2C single-byte write
// against the i2c-write-one-byte fixture, then show what the host
// reads back from the result ring.

#section-slide("05", "Your first test")

#content-slide(
  "The goal",
  kicker-text: "Worked example",
)[
  #v(0.6em)
  #align(center)[
    #text(font: font-serif, size: 26pt, weight: "semibold", fill: ink)[
      Write one byte to address `0x50`.
    ]
    #v(0.8em)
    #pill("2 bytes") #h(0.8em)
    #pill("2 ACKs") #h(0.8em)
    #pill("1 STOP")
  ]
  #note[
    The smallest I2C transaction that actually does something useful.
    Mirrors `mole-asm/tests/fixtures/i2c-write-one-byte.moleasm`
    byte-for-byte -- pull the file up alongside and read along.
  ]
]

#try-it-slide(
  [Before you see the code: roughly how many `EMIT_BIT` calls do you
  expect for the address byte alone?],
  hint: [Don't forget the ACK probe -- and remember the start condition is a quarter-bit dance, not a bit.],
)

#code-slide(
  "Setup",
  kicker-text: "Mode + timing as opcodes",
)[
```
        LOAD_TIMING   i2c_freq, 60          ; ~100 kHz @ 24 MHz
        SET_BUS_MODE  i2c                   ; SDA + SCL open-drain
```
]

#code-slide(
  "Start + address byte `0xA0`",
  kicker-text: "0x50 << 1 | W = 0xA0",
)[
```
        EMIT_QUARTER  sda=recessive scl=recessive   ; idle
        EMIT_QUARTER  sda=dominant  scl=recessive   ; START: SDA low
        EMIT_QUARTER  sda=dominant  scl=dominant    ; SCL low

        EMIT_BIT      tx=recessive          ; 1
        EMIT_BIT      tx=dominant           ; 0
        EMIT_BIT      tx=recessive          ; 1
        EMIT_BIT      tx=dominant           ; 0
        EMIT_BIT      tx=dominant           ; 0
        EMIT_BIT      tx=dominant           ; 0
        EMIT_BIT      tx=dominant           ; 0
        EMIT_BIT      tx=dominant           ; W = 0

        EMIT_BIT      tx=hiz expect=0 mask=1 capture=1
        BRANCH_ON     MISMATCH, nak
```
]

#code-slide(
  "Data byte, STOP, halt",
  kicker-text: "Same shape -- second ACK then STOP",
)[
```
        ; data byte 0xAB             (EMIT_BIT x8 elided)
        EMIT_BIT      tx=hiz expect=0 mask=1 capture=1
        BRANCH_ON     MISMATCH, nak

        EMIT_QUARTER  sda=dominant  scl=dominant
        EMIT_QUARTER  sda=dominant  scl=recessive   ; SCL rises
        EMIT_QUARTER  sda=recessive scl=recessive   ; SDA rises -> STOP

        MARK          label=1
        HALT          status=0
nak:    MARK          label=2
        HALT          status=1
```
]

#content-slide(
  "What the engine writes back",
  kicker-text: "Result ring after HALT",
)[
  #v(0.3em)
  #align(center)[
    #box(width: 96%)[#ring-layout-figure]
  ]
  #v(0.4em)
  #bullets(
    [Revision header identifies the engine build at the ring's base.],
    [Each `EMIT_BIT capture=1` lands as a CAPTURE record.],
    [Each `MARK` is a host-visible breadcrumb. `HALT` ends the stream.],
  )
]

#content-slide(
  "What the host shows you",
  kicker-text: "Decoder pairs records back with source lines",
)[
  #code-panel(size: 14pt)[
```
line 14  capture #0  ACK   obs=0  exp=0   OK
line 23  capture #1  ACK   obs=0  exp=0   OK
mark     label=1                          ; took the ok path
halt     status=0
```
  ]
  #v(0.5em)
  #align(center)[
    #text(font: font-serif, size: 16pt, style: "italic", fill: muted)[
      Mismatches highlighted. Flags decoded. Source lines linked.
    ]
  ]
]

#recap-slide(
  "What Part 5 leaves you with",
  (
    [Start / address / ACK / data / ACK / STOP -- in moleasm, no surprises.],
    [`capture=1` is how a probe turns into a CAPTURE record on the ring.],
    [The decoder closes the loop back to your source lines.],
  ),
  next: [breaking it on purpose -- injecting a single-quarter glitch.],
  deeper: [`mole-asm/book/src/{quickstart,worked-examples}.md` -- the same write, end to end.],
)
