#import "../lib.typ": *
#import "../figures/quarter-bit-timing.typ": quarter-bit-figure

// Quarter-bit timing.

#section-slide("03", "Timing")

#stat-slide(
  "4",
  "quarters per bit",
  caption: [The fabric clock #emph[is] the quarter-bit clock.],
)

#content-slide(
  "Why four?",
  kicker-text: "Three reasons",
)[
  #bullets(
    [*Setup / hold.* SDA stable across SCL edges.],
    [*Glitch budget.* One quarter = minimum disturbance.],
    [*Repeated start.* Falls out for free.],
  )
  #v(0.5em)
  #align(center)[
    #text(font: font-serif, size: 18pt, style: "italic", fill: muted)[
      No PLLs. No half-tick fudge.
    ]
  ]
]

#content-slide(
  "Comfortable on iCE40.",
  kicker-text: "Headroom",
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
  #v(0.8em)
  #align(center)[
    #text(font: font-serif, size: 18pt, style: "italic", fill: muted)[
      Trivially within the UP5K's reach.
    ]
  ]
]

#content-slide(
  "What `EMIT_BIT` emits.",
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
  #v(0.6em)
  #align(center)[
    #text(font: font-serif, size: 15pt, style: "italic", fill: muted)[
      SDA stable across the SCL edge. Need finer control? `EMIT_QUARTER` overrides any quarter.
    ]
  ]
]
