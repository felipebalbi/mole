#import "../../presentation-template/lib.typ": *

// Part 7: Where this goes. CLI, sims, bring-up, the three SKUs as
// reference designs, and a snapshot of what ships vs what's in flight.

#section-slide("08", "Where this goes")

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
  #stack(
    spacing: 1em,
    code-panel(size: 16pt)[
```
$ make             ; yosys + nextpnr-ice40
$ make flash       ; iceprog
```
    ],
    bullets(
      [Bring-up notes live in `fpga/Mole/BRINGUP.md`.],
      [Pin maps, level-shifter notes, known-good toolchain versions.],
    ),
  )
]

#let sku(name, role, fpga, scope) = align(center, stack(
  spacing: 0.4em,
  text(font: font-serif, size: 32pt, weight: "bold", fill: accent)[#name],
  text(size: 12pt, tracking: 2pt, fill: muted)[#upper(role)],
  text(font: font-mono, size: 13pt, fill: secondary)[#fpga],
  text(font: font-serif, size: 13pt, fill: ink-soft)[#scope],
))

#content-slide(
  "Three SKUs, one ISA",
  kicker-text: "Reference designs across the line",
)[
  #stack(
    spacing: 1.2em,
    three-col(
      sku("Verde", "Dongle / per-dev",   "iCE40 UP5K",   "I3C SDR, I2C"),
      sku("Rojo",  "Bench / compliance", "ECP5-45K",     "I3C SDR, I2C, HDR-DDR"),
      sku("Negro", "Certification",      "CertusPro-NX", "future tier"),
    ),
    align(center, text(
      font: font-serif, size: 16pt, style: "italic", fill: muted,
    )[
      Open hardware. Same bit-cycle engine.
      Pick the tier that fits your bench.
    ]),
  )
]

#compare-slide(
  "Today vs in flight",
  kicker-text: "Snapshot of the tree",
  "Shipping", [
    #bullets(
      [Bit-cycle engine (controller). Sims green.],
      [`mole-asm`: full ISA coverage.],
      [`mole-loader`: verify, round-trip, ring decode.],
      [iCEBreaker bring-up notes; mdBook quickstart.],
    )
  ],
  "In flight", [
    #bullets(
      [Target role: sample / drive paced by external SCL.],
      [DAA arbitration for I3C.],
      [Source-line cross-ref in loader output.],
      [Spec-level SDK on the assembler (TBD).],
    )
  ],
  verdict: [Wire format locks at Phase 0's first tagged release.],
)

#recap-slide(
  "What you walk out with",
  (
    [A mental model: two layers, one bytecode contract, quarter-bit clock.],
    [The full ISA at a glance, the assembler, and the loader to round-trip it.],
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
  tagline: "Now go break something on purpose.",
)
