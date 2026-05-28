#import "../lib.typ": *
#import "../figures/quarter-bit-timing.typ": quarter-bit-figure

// Part 6: Breaking it on purpose. Same engine, same SDA/SCL, but now
// we deliberately violate setup/hold to see how the target reacts.
// Mirrors uppercase mnemonics + `;` comments of the golden fixtures.

#section-slide("06", "Breaking it on purpose")

#content-slide(
  "The goal",
  kicker-text: "From clean test to glitch fuzz",
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
  #note[
    The whole point of having a per-quarter ISA is that you can ask
    this kind of question. A logic analyser only watches; an MCU is
    too slow. Mole drives the wire one quarter at a time, on purpose.
  ]
]

#content-slide(
  "The shape on the wire",
  kicker-text: "Q2 dips while SCL rises",
)[
  #v(0.3em)
  #align(center)[
    #box(width: 92%)[
      #quarter-bit-figure(
        sda-bits: ("hi", "hi", "glitch", "hi"),
        capture: none,
        title: [
          A bit that should look recessive end-to-end has a deliberate
          dip on its sample edge.
        ],
      )
    ]
  ]
]

#code-slide(
  "The injection",
  kicker-text: "Bit 4 of `0x90` -- normally tx=recessive",
)[
```
        ; Drive quarter-by-quarter to violate setup / hold.
        EMIT_QUARTER  sda=recessive scl=dominant    ; Q0: load,    SCL low
        EMIT_QUARTER  sda=recessive scl=dominant    ; Q1: hold,    SCL low
        EMIT_QUARTER  sda=dominant  scl=recessive   ; Q2: GLITCH,  SCL rises
        EMIT_QUARTER  sda=recessive scl=recessive   ; Q3: recover, SCL high
```
]

#content-slide(
  "Three possible outcomes",
  kicker-text: "All interesting",
)[
  #v(0.4em)
  #numbered(
    [#tag("ACK",   color: accent)    #h(0.4em) Target didn't enforce setup / hold. *Bug.*],
    [#tag("NACK",  color: secondary) #h(0.4em) Target rejected the byte. *Spec-correct.*],
    [#tag("START", color: muted)     #h(0.4em) Target re-armed as if a new START arrived. *Brittle.*],
  )
  #v(0.6em)
  #align(center)[
    #text(font: font-serif, size: 16pt, style: "italic", fill: muted)[
      All three tell you something useful about the part on the desk.
    ]
  ]
]

#quote-slide(
  [Run it a thousand times. Same bytecode. Same pattern. If the target
  waffles, it's the target -- not the rig.],
  by: [the whole point of compile-time injection],
)

#recap-slide(
  "What Part 6 leaves you with",
  (
    [`EMIT_QUARTER` is the per-quarter escape hatch you reach for to fuzz.],
    [A single bit can be perfectly compliant or deliberately bent.],
    [Compile-time determinism means \"flake\" is a thing of the past.],
  ),
  next: [the tooling, the SKUs, and where Mole is going next.],
  deeper: [`book/src/patterns.md` -- recipes for the common cases.],
)
