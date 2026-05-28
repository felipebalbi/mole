#import "../lib.typ": *

// Part 7: Where this goes. CLI, sims, bring-up, the three SKUs as
// reference designs, and a snapshot of what ships vs what's in flight.

#section-slide("07", "Where this goes")

#code-slide(
  "Compile",
  kicker-text: "mole-asm CLI",
)[
```
$ mole-asm program.moleasm
  -> program.molecode               ; packed 16-bit words

$ mole-asm --frame program.moleasm
  -> program.molecode
  -> program.mole.bin               ; length + words + CRC, UART-ready
```
]

#code-slide(
  "Simulate",
  kicker-text: "SpinalSim + Verilator",
)[
```
$ cd fpga/Mole
$ make sim-engine-smoke   ; bit-cycle engine: fast smoke
$ make sim-engine-full    ; bit-cycle engine: full sweep
$ make sim-uart           ; UART block
$ make sim-spram          ; result-ring controller
$ make sim                ; everything
```
]

#content-slide(
  "Bring-up",
  kicker-text: "iCEBreaker, end to end",
)[
  #v(0.2em)
  #code-panel(size: 16pt)[
```
$ make             ; yosys + nextpnr-ice40
$ make flash       ; iceprog
```
  ]
  #v(0.5em)
  #bullets(
    [Bring-up notes live in `fpga/Mole/BRINGUP.md`.],
    [Pin maps, level-shifter notes, known-good toolchain versions.],
  )
]

#content-slide(
  "Three SKUs, one ISA",
  kicker-text: "Reference designs across the line",
)[
  #v(0.3em)
  #three-col[
    #align(center)[
      #text(font: font-serif, size: 32pt, weight: "bold", fill: accent)[Verde]
      #v(-0.2em)
      #text(size: 12pt, tracking: 2pt, fill: muted)[#upper("Dongle / per-dev")]
      #v(0.3em)
      #text(font: font-mono, size: 13pt, fill: secondary)[iCE40 UP5K]
      #v(0.2em)
      #text(font: font-serif, size: 13pt, fill: ink-soft)[I3C SDR, I2C, HDR-DDR]
    ]
  ][
    #align(center)[
      #text(font: font-serif, size: 32pt, weight: "bold", fill: accent)[Rojo]
      #v(-0.2em)
      #text(size: 12pt, tracking: 2pt, fill: muted)[#upper("Bench / compliance")]
      #v(0.3em)
      #text(font: font-mono, size: 13pt, fill: secondary)[ECP5-45K]
      #v(0.2em)
      #text(font: font-serif, size: 13pt, fill: ink-soft)[I3C SDR, I2C, HDR-DDR]
    ]
  ][
    #align(center)[
      #text(font: font-serif, size: 32pt, weight: "bold", fill: accent)[Negro]
      #v(-0.2em)
      #text(size: 12pt, tracking: 2pt, fill: muted)[#upper("Certification")]
      #v(0.3em)
      #text(font: font-mono, size: 13pt, fill: secondary)[CertusPro-NX]
      #v(0.2em)
      #text(font: font-serif, size: 13pt, fill: ink-soft)[future tier]
    ]
  ]
  #v(0.6em)
  #align(center)[
    #text(font: font-serif, size: 16pt, style: "italic", fill: muted)[
      Open hardware. Same bit-cycle engine. Pick the tier that fits your bench.
    ]
  ]
]

#compare-slide(
  "Today vs in flight",
  kicker-text: "Snapshot of the tree",
  "Shipping", [
    #bullets(
      [Bit-cycle engine -- controller role, sims green.],
      [`moleasm` assembler -- full ISA coverage.],
      [Bring-up notes for iCEBreaker (Verde-class).],
      [mdBook tutorial: quickstart, opcodes, patterns.],
    )
  ],
  "In flight", [
    #bullets(
      [Target role -- sample / drive paced by external SCL.],
      [DAA arbitration for I3C.],
      [Result-ring decoder with source-line cross-link.],
      [Scheme SDK on top of the assembler.],
    )
  ],
  verdict: [Wire format locks at Phase 0's first tagged release.],
)

#recap-slide(
  "What you walk out with",
  (
    [A mental model: two layers, one bytecode contract, quarter-bit clock.],
    [The full ISA at a glance and the assembly syntax to drive it.],
    [A worked I2C test, a glitch fuzz, and the result ring that records both.],
  ),
  next: [`book` -- the mdBook tutorial picks up from here.],
  deeper: [`ROADMAP.md` for the design rationale; `AGENTS.md` for how to contribute.],
)

#thank-you-slide(
  "github.com/felipebalbi/mole",
  book: "book/",
  roadmap: "ROADMAP.md",
  contributing: "AGENTS.md",
)
