#import "../lib.typ": *

// moleasm syntax -- mirror the golden fixtures byte-for-byte.

#section-slide("05", "moleasm")

#content-slide(
  "The human face.",
  kicker-text: "What it is",
)[
  #v(0.2em)
  #code-panel(size: 18pt)[
```
; Drive a recessive bit. Capture what we see.
EMIT_BIT  tx=recessive capture=1
```
  ]
  #v(0.5em)
  #align(center)[
    #text(font: font-serif, size: 18pt, style: "italic", fill: muted)[
      One line. One sixteen-bit instruction.
    ]
  ]
]

#content-slide(
  "Line shape",
  kicker-text: "Always the same",
)[
  #v(0.2em)
  #code-panel(size: 18pt)[
```
label:  MNEMONIC  field=value  field=value ...
```
  ]
  #v(0.6em)
  #bullets(
    [`tx=dominant|recessive|hiz` (or `dom`/`rec`)],
    [`expect=` / `mask=` / `capture=` on bearer opcodes],
    [Branch targets are *labels*, never raw offsets],
  )
]

#content-slide(
  "Two directives",
  kicker-text: "Everything else is an opcode",
)[
  #v(0.2em)
  #code-panel(size: 16pt)[
```
.equ slow_div, 59         ; ~100 kHz at 24 MHz fabric
.dw  0xC000               ; raw word, escape hatch

        LOAD_TIMING   i2c_freq, slow_div
        SET_BUS_MODE  i2c
```
  ]
  #v(0.4em)
  #align(center)[
    #text(font: font-serif, size: 16pt, style: "italic", fill: muted)[
      Bus mode and timing are opcodes -- they live on the wire.
    ]
  ]
]
