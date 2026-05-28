#import "../lib.typ": *
#import "../figures/bus-mode-table.typ": bus-mode-table-figure
#import "../figures/sticky-flag-lifecycle.typ": sticky-flag-lifecycle-figure

// Part 4: The ISA + moleasm. Tour the 15 opcodes, the bus-agnostic
// symbol vocabulary, the BUS_MODE indirection, sticky flags, and the
// assembly syntax that wraps it all.

#section-slide("04", "The instruction set")

#stat-slide(
  "15",
  "opcodes",
  caption: [Sixteen bits each. Opcode always in `[15:11]`. 17 slots reserved.],
)

#let isa-row(op, desc) = (
  [#pill(op)],
  text(size: 14pt, fill: ink-soft)[#desc],
)

#content-slide(
  "Four families",
  kicker-text: "Half the ISA per slide -- here's all of it",
)[
  #v(0.3em)
  #grid(
    columns: (auto, 1fr, auto, 1fr),
    column-gutter: (16pt, 32pt, 16pt),
    row-gutter: 10pt,
    align: (left, left, left, left),
    ..isa-row("EMIT_BIT",          "canonical bit on SDA"),
    ..isa-row("BRANCH_ON",         "branch on cond code"),
    ..isa-row("EMIT_QUARTER",      "drive one quarter"),
    ..isa-row("WAIT_ON",           "block on cond"),
    ..isa-row("SAMPLE_BIT_ON_SCL", "target: sample SDA"),
    ..isa-row("LOAD_LOOP",         "set loop counter"),
    ..isa-row("DRIVE_BIT_ON_SCL",  "target: drive SDA"),
    ..isa-row("DEC_BRANCH",        "decrement + branch"),
    ..isa-row("SET_BUS_MODE",      "switch electrical class"),
    ..isa-row("JMP",               "unconditional branch"),
    ..isa-row("SET_ROLE",          "switch engine role"),
    ..isa-row("MARK",              "push host breadcrumb"),
    ..isa-row("LOAD_TIMING",       "set quarter divider"),
    ..isa-row("HALT",              "stop the engine"),
    ..isa-row("STRETCH_SCL",       "target: hold SCL low"),
    [], [],
  )
  #note[
    Four loose families: drive, observe, control flow, and setup. The
    grid layout is a hint -- left column is mostly drive/observe, right
    column is mostly control. Don't memorise; just notice the shape.
  ]
]

#content-slide(
  "Four symbols",
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
    [The engine has zero protocol knowledge.],
    [`BUS_MODE` is the only thing that maps symbols to volts.],
  )
]

#definition-slide(
  "BUS_MODE",
  sub: [One register. Four buses. Where the protocol awareness lives.],
)[
  #v(0.2em)
  #align(center)[
    #box(width: 90%)[#bus-mode-table-figure]
  ]
  #v(0.3em)
  #align(center)[
    #text(font: font-serif, size: 14pt, style: "italic", fill: muted)[
      Adding a new bus (SMBus, PMBus, LIN, 1-Wire, CAN) =
      one more table entry. Zero ISA churn.
    ]
  ]
  #note[
    This is the load-bearing layering decision. The ISA is dumb wire,
    BUS_MODE is the spec lookup. Keep them separate and the engine
    outlives every protocol it ever serves.
  ]
]

#definition-slide(
  "Sticky flags",
  sub: [`MISMATCH`, `TIMEOUT`, `START`, `STOP`. Write-once, until rewritten.],
)[
  #v(0.2em)
  #align(center)[
    #box(width: 80%)[#sticky-flag-lifecycle-figure]
  ]
  #v(0.4em)
  #align(center)[
    #text(font: font-serif, size: 15pt, style: "italic", fill: muted)[
      That's why `BRANCH_ON MISMATCH` "just works" -- you don't
      have to remember to clear anything.
    ]
  ]
]

#content-slide(
  "moleasm: the human face",
  kicker-text: "One line, one 16-bit instruction",
)[
  #v(0.2em)
  #code-panel(size: 18pt)[
```
; Drive a recessive bit. Capture what we see.
EMIT_BIT  tx=recessive capture=1
```
  ]
  #v(0.5em)
  #bullets(
    [`tx=dominant|recessive|hiz` (short forms `dom` / `rec` accepted)],
    [`expect=` / `mask=` / `capture=` on bearer opcodes],
    [Branch targets are #emph[labels], never raw offsets],
  )
]

#content-slide(
  "moleasm: two directives",
  kicker-text: "Everything else is an opcode",
)[
  #v(0.2em)
  #code-panel(size: 16pt)[
```
.equ slow_div, 60         ; ~100 kHz at 24 MHz fabric
.dw  0xC000               ; raw word, escape hatch

        LOAD_TIMING   i2c_freq, slow_div
        SET_BUS_MODE  i2c
```
  ]
  #v(0.4em)
  #align(center)[
    #text(font: font-serif, size: 16pt, style: "italic", fill: muted)[
      Bus mode and timing are opcodes -- they live on the wire.
    ]
  ]
]

#recap-slide(
  "What Part 4 leaves you with",
  (
    [15 opcodes, 4 loose families. You can already skim someone else's moleasm.],
    [`tx_symbol` is the engine's vocabulary; `BUS_MODE` is the dictionary.],
    [Sticky flags + branch-on-condition is how control flow works.],
  ),
  next: [your first end-to-end test -- writing one byte to a real EEPROM.],
  deeper: [`book/src/{syntax,opcodes}.md` -- the full ISA reference.],
)
