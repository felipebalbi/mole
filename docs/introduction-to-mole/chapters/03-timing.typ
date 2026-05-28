#import "../lib.typ": *
#import "../figures/quarter-bit-timing.typ": quarter-bit-figure

// Part 3: Timing. The whole engine is built around the quarter-bit
// clock, and that single choice falls out into everything else.

#section-slide("03", "The clock")

#definition-slide(
  "Quarter-bit",
  sub: [The fundamental time unit of the engine.],
)[
  #v(0.4em)
  #bullets(
    [One I3C / I2C bit is sliced into four equal quarters: Q0, Q1, Q2, Q3.],
    [The fabric clock #emph[is] the quarter-bit clock. No PLLs, no fractions.],
    [Every drive, sample, and capture lines up on a quarter boundary.],
  )
  #note[
    Quarter-bit time is the timing currency for the entire ISA. Once you
    internalise that one fact, everything else in Part 3 is consequence,
    not surprise.
  ]
]

#stat-slide(
  "4",
  "quarters per bit",
  caption: [Setup window, hold window, sample point, recovery -- one each.],
)

#try-it-slide(
  [Why slice each bit into #emph[four] quarters? Why not two, or eight, or sixteen?],
  hint: [Think about what an I2C controller has to observe and what it has to drive, separately.],
)

#content-slide(
  "Why four?",
  kicker-text: "Three reasons -- one per quarter",
)[
  #bullets(
    [*Setup* (Q0+Q1). SDA goes stable while SCL is still low.],
    [*Sample* (Q2). SCL rises, the receiver latches the bit.],
    [*Hold + recovery* (Q3). SCL stays high; SDA may move at the end.],
  )
  #v(0.5em)
  #align(center)[
    #text(font: font-serif, size: 18pt, style: "italic", fill: muted)[
      Two would lose setup. Eight would buy nothing. Four is the
      smallest split that lets every legal moment be addressed.
    ]
  ]
]

#content-slide(
  "What `EMIT_BIT` emits",
  kicker-text: "Canonical waveform",
)[
  #v(0.2em)
  #align(center)[
    #box(width: 92%)[
      #quarter-bit-figure(
        sda-bits: ("hi", "hi", "hi", "hi"),
        capture: 2,
      )
    ]
  ]
  #v(0.4em)
  #align(center)[
    #text(font: font-serif, size: 15pt, style: "italic", fill: muted)[
      SDA stable across the SCL edge. Need finer control?
      `EMIT_QUARTER` overrides any quarter.
    ]
  ]
  #note[
    This is the shape every controller-mode bit makes when you write
    `EMIT_BIT` in moleasm. Want to do something off-canonical?
    `EMIT_QUARTER` lets you address each quarter individually.
  ]
]

#content-slide(
  "Comfortable on iCE40",
  kicker-text: "Speed budget",
)[
  #v(0.3em)
  #three-col[
    #align(center)[
      #text(font: font-serif, size: 40pt, weight: "bold", fill: accent)[400 kHz]
      #v(-0.3em)
      #text(size: 12pt, fill: muted, tracking: 2pt)[#upper("I2C fast")]
      #v(0.3em)
      #text(font: font-mono, size: 14pt, fill: secondary)[1.6 MHz fabric]
    ]
  ][
    #align(center)[
      #text(font: font-serif, size: 40pt, weight: "bold", fill: accent)[1 MHz]
      #v(-0.3em)
      #text(size: 12pt, fill: muted, tracking: 2pt)[#upper("I2C fast+")]
      #v(0.3em)
      #text(font: font-mono, size: 14pt, fill: secondary)[4 MHz fabric]
    ]
  ][
    #align(center)[
      #text(font: font-serif, size: 40pt, weight: "bold", fill: accent)[6 MHz]
      #v(-0.3em)
      #text(size: 12pt, fill: muted, tracking: 2pt)[#upper("I3C SDR")]
      #v(0.3em)
      #text(font: font-mono, size: 14pt, fill: secondary)[24 MHz fabric]
    ]
  ]
  #v(0.6em)
  #align(center)[
    #text(font: font-serif, size: 18pt, style: "italic", fill: muted)[
      All three trivially within the UP5K's reach.
    ]
  ]
]

#recap-slide(
  "What Part 3 leaves you with",
  (
    [Quarter-bit time is the engine's clock -- no PLLs, no fractions.],
    [Four quarters give setup, sample, and hold each their own slot.],
    [Even the fastest target spec is comfortable on the smallest iCE40.],
  ),
  next: [the 15-opcode ISA that consumes those quarter-bits.],
  deeper: [`mole-asm/book/src/mental-model.md` -- "Quarter-bit time" section.],
)
