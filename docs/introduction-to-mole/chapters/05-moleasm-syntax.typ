#import "../lib.typ": *

// moleasm syntax.

#section-slide("05", "moleasm")

#content-slide(
  "The human face.",
  kicker-text: "What it is",
)[
  #v(0.2em)
  #code-panel(size: 18pt)[
```
// Drive a recessive bit. Capture what we see.
emit_bit tx=recessive capture=1
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
label:  mnemonic  field=value  field=value ...
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
  "Directives",
  kicker-text: "Set the stage",
)[
  #v(0.2em)
  #code-panel(size: 16pt)[
```
.bus_mode      i2c
.timing        div=15        // 400 kHz on 24 MHz fabric
.seed          0xdead_beef   // host-side PRNG
.glitch_ratio  0             // exactly zero
```
  ]
  #v(0.4em)
  #align(center)[
    #text(font: font-serif, size: 16pt, style: "italic", fill: muted)[
      `seed` and `glitch_ratio` never reach the wire.
    ]
  ]
]
