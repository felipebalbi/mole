#import "../lib.typ": *

// Worked example 1: clean I2C write.

#section-slide("06", "I2C write, end to end")

#content-slide(
  "The goal",
  kicker-text: "Worked example",
)[
  #v(0.6em)
  #align(center)[
    #text(font: font-serif, size: 26pt, weight: "semibold", fill: ink)[
      Write `0x42` to TMP108 at `0x48`, register `0x01`.
    ]
    #v(0.8em)
    #pill("3 bytes") #h(0.8em)
    #pill("3 ACKs") #h(0.8em)
    #pill("3 captures")
  ]
]

#code-slide(
  "Setup",
  kicker-text: "Compliant. No glitches.",
)[
```
.bus_mode      i2c
.timing        div=15     // 400 kHz on 24 MHz fabric

// SDA + SCL idle high.  Pull-ups already wired.
```
]

#code-slide(
  "Address phase",
  kicker-text: "0x48 << 1 | W  =  0x90",
)[
```
start:  emit_quarter sda=dom scl=hiz    // SDA falls, SCL high
        emit_quarter sda=dom scl=dom    // SCL low

        emit_bit tx=rec      // 1
        emit_bit tx=dom      // 0
        emit_bit tx=dom      // 0
        emit_bit tx=rec      // 1
        emit_bit tx=dom      // 0
        emit_bit tx=dom      // 0
        emit_bit tx=dom      // 0
        emit_bit tx=dom      // 0   -- W
        emit_bit tx=hiz expect=0 capture=1   // ACK
```
]

#code-slide(
  "Register, data, stop",
  kicker-text: "Same shape, three more bytes",
)[
```
        // register 0x01      ...emit_bit x8...
        emit_bit tx=hiz expect=0 capture=1   // ACK

        // data 0x42          ...emit_bit x8...
        emit_bit tx=hiz expect=0 capture=1   // ACK

stop:   emit_quarter sda=dom scl=hiz
        emit_quarter sda=hiz scl=hiz
        halt
```
]

#content-slide(
  "What the host reads",
  kicker-text: "Result ring after HALT",
)[
  #code-panel(size: 16pt)[
```
capture[0] = 0   target ACKed address
capture[1] = 0   target ACKed register
capture[2] = 0   target ACKed data
```
  ]
  #v(0.4em)
  #align(center)[
    #text(font: font-serif, size: 16pt, style: "italic", fill: muted)[
      Any `1` and you know exactly which byte was NACKed.
    ]
  ]
]
