#import "../lib.typ": *
#import "../figures/architecture.typ": architecture-figure

// Part 2: Architecture. Three boxes -> two layers -> the contract
// between them -> compile-time determinism.

#section-slide("02", "Architecture")

#content-slide(
  "The three boxes",
  kicker-text: "What's on the desk",
)[
  #v(0.3em)
  #align(center)[
    #box(width: 95%)[#architecture-figure]
  ]
  #v(0.4em)
  #align(center)[
    #text(font: font-serif, size: 16pt, style: "italic", fill: muted)[
      Your laptop, an FPGA, and the part you're trying to talk to.
    ]
  ]
  #note[
    Three boxes is the whole thing -- no rack, no DUT carrier, no
    instrumentation server. The host is your existing dev environment;
    the DUT is whatever you're characterising; Mole is the little FPGA
    in the middle that turns one into the other.
  ]
]

#compare-slide(
  "Two layers. One contract.",
  kicker-text: "Host above, engine below",
  "Layer 1 -- Host compiler", [
    #v(0.3em)
    #text(font: font-serif, size: 18pt, fill: ink)[Knows the spec.]
    #v(0.2em)
    #text(size: 15pt, fill: muted)[
      I2C, I3C, CCC, HDR-DDR primitives in Rust + Scheme. Emits bytecode.
    ]
  ],
  "Layer 0 -- Bit-cycle engine", [
    #v(0.3em)
    #text(font: font-serif, size: 18pt, fill: ink)[Knows the wire.]
    #v(0.2em)
    #text(size: 15pt, fill: muted)[
      14 opcodes. Drives every quarter-bit. No protocol awareness.
    ]
  ],
  verdict: [The 16-bit bytecode between them is the contract.],
)

#content-slide(
  "Zero runtime randomness",
  kicker-text: "Determinism by construction",
)[
  #bullets(
    [PRNG lives on the #emph[host], not in the FPGA.],
    [`ratio = 0` → output is byte-identical to the clean build.],
    [Same seed, same wire pattern, every single time.],
  )
  #v(0.4em)
  #align(center)[
    #text(font: font-serif, size: 22pt, style: "italic", fill: accent)[
      Every bug replayable.
    ]
  ]
  #note[
    This is the property that makes Mole interesting as a #emph[fuzzing]
    rig, not just a compliance rig. Find a flake, save the seed, replay
    forever.
  ]
]

#recap-slide(
  "What Part 2 leaves you with",
  (
    [Three physical boxes; two logical layers.],
    [A 16-bit bytecode that pins the contract between them.],
    [Compile-time determinism: every bug has a seed.],
  ),
  next: [the clock that makes all of this tick -- one quarter-bit at a time.],
  deeper: [`mole-asm/book/src/mental-model.md` -- the layered view in long form.],
)
