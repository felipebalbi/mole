#import "../lib.typ": *

// Worked example 1: clean I2C write -- mirrors
// mole-asm/tests/fixtures/i2c-write-one-byte.moleasm syntax.

#section-slide("06", "I2C write, end to end")

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
]

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
  "Start + address byte 0xA0",
  kicker-text: "0x50 << 1 | W  =  0xA0",
)[
```
        EMIT_QUARTER  sda=recessive scl=recessive   ; idle
        EMIT_QUARTER  sda=dominant  scl=recessive   ; SDA low
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
  "Data, stop, halt",
  kicker-text: "Same shape -- second ACK then STOP",
)[
```
        ; data byte 0xAB             (EMIT_BIT x8 elided)
        EMIT_BIT      tx=hiz expect=0 mask=1 capture=1
        BRANCH_ON     MISMATCH, nak

        EMIT_QUARTER  sda=dominant  scl=dominant
        EMIT_QUARTER  sda=dominant  scl=recessive
        EMIT_QUARTER  sda=recessive scl=recessive

        MARK          label=1
        HALT          status=0
nak:    MARK          label=2
        HALT          status=1
```
]

#content-slide(
  "What the host reads",
  kicker-text: "Result ring after HALT",
)[
  #code-panel(size: 16pt)[
```
capture #0  obs=0  exp=0   OK   ; address ACKed
capture #1  obs=0  exp=0   OK   ; data ACKed
mark    #1                      ; took the ok path
halt    status=0
```
  ]
  #v(0.4em)
  #align(center)[
    #text(font: font-serif, size: 16pt, style: "italic", fill: muted)[
      Any mismatch and `BRANCH_ON MISMATCH` jumps to the nak path.
    ]
  ]
]
