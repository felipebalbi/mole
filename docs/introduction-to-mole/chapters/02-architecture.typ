#import "../../presentation-template/lib.typ": *
#import "../figures/architecture.typ": architecture-figure

// Part 2: Architecture. Three boxes -> two layers -> the contract
// between them -> compile-time determinism. The whole purpose is to
// hand the audience a mental model strong enough to hold the ISA
// section that follows.

#section-slide("02", "Architecture")

#content-slide(
  "The three boxes",
  kicker-text: "What's on the desk",
)[
  #stack(
    spacing: 0.8em,
    align(center, box(width: 95%, architecture-figure)),
    align(center, text(
      font: font-serif, size: 16pt, style: "italic", fill: muted,
    )[Your laptop, an FPGA, and the part you're trying to talk to.]),
  )
  #note[
    Three boxes is the whole thing -- no rack, no DUT carrier, no
    instrumentation server. The host is your existing dev
    environment; the DUT is whatever you're characterising; Mole is
    the little FPGA in the middle that turns one into the other.
  ]
]

#compare-slide(
  "Two layers. One contract.",
  kicker-text: "Host above, engine below",
  "Layer 1 -- Host", stack(
    spacing: 0.4em,
    text(font: font-serif, size: 18pt, fill: ink)[Knows the spec.],
    text(size: 15pt, fill: muted)[
      `mole-asm` compiles `.moleasm` to bytecode.
      `mole-loader` ships it and decodes results.
    ],
  ),
  "Layer 0 -- Bit-cycle engine", stack(
    spacing: 0.4em,
    text(font: font-serif, size: 18pt, fill: ink)[Knows the wire.],
    text(size: 15pt, fill: muted)[
      15 opcodes. Drives every quarter-bit.
      No protocol awareness whatsoever.
    ],
  ),
  verdict: [A 16-bit bytecode is the contract between them.],
)

#content-slide(
  "Zero runtime randomness",
  kicker-text: "Determinism by construction",
)[
  #stack(
    spacing: 1em,
    bullets(
      [PRNG lives on the #emph[host], not in the FPGA.],
      [`ratio = 0` -> output is byte-identical to the clean build.],
      [Same seed, same wire pattern, every single time.],
    ),
    align(center, text(
      font: font-serif, size: 22pt, style: "italic", fill: accent,
    )[Every bug replayable.]),
  )
  #note[
    This is the property that makes Mole interesting as a
    #emph[fuzzing] rig, not just a compliance rig. Find a flake,
    save the seed, replay forever -- on any Mole, on any desk.
  ]
]

#recap-slide(
  "What Part 2 leaves you with",
  (
    [Three physical boxes; two logical layers.],
    [A 16-bit bytecode pins the contract between them.],
    [Compile-time determinism: every bug has a seed.],
  ),
  next: [the 15-opcode ISA -- and the quarter-bit clock that drives it.],
  deeper: [`book/src/mental-model.md` -- the layered view in long form.],
)
