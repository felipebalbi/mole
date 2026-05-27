#import "../lib.typ": *

// Worked example 2: glitch injection.

#section-slide("07", "Injecting a glitch")

#content-slide(
  "The goal",
  kicker-text: "Worked example",
)[
  #v(0.6em)
  #align(center)[
    #text(font: font-serif, size: 26pt, weight: "semibold", fill: ink)[
      Force a single-quarter dip on SDA, mid-byte.
    ]
    #v(0.8em)
    #text(font: font-serif, size: 18pt, style: "italic", fill: muted)[
      Does the target NACK, hang, or shrug it off?
    ]
  ]
]

#code-slide(
  "The injection",
  kicker-text: "Bit 4 of 0x90 --- normally `tx=rec`",
)[
```
// Drive quarter-by-quarter to violate setup/hold.
emit_quarter sda=rec scl=dom    // Q0: load SDA, SCL low
emit_quarter sda=rec scl=hiz    // Q1: SCL rises
emit_quarter sda=dom scl=hiz    // Q2: GLITCH -- SDA drops
emit_quarter sda=rec scl=dom    // Q3: SCL falls
```
]

#content-slide(
  "Three possible outcomes",
  kicker-text: "All interesting",
)[
  #v(0.4em)
  #numbered(
    [#tag("ACK", color: accent)  #h(0.4em) Target didn't enforce setup/hold. *Bug.*],
    [#tag("NACK", color: secondary) #h(0.4em) Target rejected it. *Spec-correct.*],
    [#tag("START", color: muted) #h(0.4em) Read as a new START. *Brittle.*],
  )
]

#quote-slide(
  [Run it a thousand times. Same bytecode. Same pattern. If the target waffles, it's the target.],
  by: [the whole point of compile-time injection],
)
