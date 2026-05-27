#import "../lib.typ": *

// Architecture.

#section-slide("02", "Architecture")

#content-slide(
  "Two layers. One contract.",
  kicker-text: "How it fits together",
)[
  #v(0.3em)
  #two-col[
    #tag("Layer 1", color: secondary)
    #v(0.3em)
    #text(font: font-serif, size: 22pt, weight: "semibold")[Host compiler]
    #v(0.2em)
    #text(size: 16pt, fill: muted)[Knows the spec.]
    #linebreak()
    #text(size: 16pt, fill: muted)[Speaks `moleasm`.]
  ][
    #tag("Layer 0", color: accent)
    #v(0.3em)
    #text(font: font-serif, size: 22pt, weight: "semibold")[Bit-cycle engine]
    #v(0.2em)
    #text(size: 16pt, fill: muted)[Knows nothing.]
    #linebreak()
    #text(size: 16pt, fill: muted)[Drives the wire.]
  ]
  #v(0.8em)
  #align(center)[
    #text(font: font-serif, size: 18pt, style: "italic", fill: ink-soft)[
      The bytecode between them is the contract.
    ]
  ]
]

#content-slide(
  "Four symbols.",
  kicker-text: "Bus-agnostic by design",
)[
  #v(0.4em)
  #align(center)[
    #pill("dominant") #h(0.6em)
    #pill("recessive") #h(0.6em)
    #pill("hiz") #h(0.6em)
    #pill("reserved")
  ]
  #v(0.8em)
  #bullets(
    [No `i2c_low`. No `i3c_push_pull_high`. Just symbols.],
    [`BUS_MODE` maps symbols to electrical reality.],
    [New bus = new table entry. Zero ISA churn.],
  )
]

#content-slide(
  "Zero runtime randomness.",
  kicker-text: "Error injection",
)[
  #bullets(
    [PRNG lives on the *host*. Output is baked.],
    [`ratio = 0` → byte-identical to clean build.],
    [Same seed, same wire pattern, every time.],
  )
  #v(0.4em)
  #align(center)[
    #text(font: font-serif, size: 22pt, style: "italic", fill: accent)[
      Every bug replayable.
    ]
  ]
]
