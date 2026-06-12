#import "../../presentation-template/lib.typ": *
#import "../figures/quarter-bit-timing.typ": quarter-bit-figure
#import "../figures/bus-mode-table.typ": bus-mode-table-figure
#import "../figures/sticky-flag-lifecycle.typ": sticky-flag-lifecycle-figure

// Part 3: The instruction set. Starts from the clock the engine
// thinks in (quarter-bit), then climbs into the 26 v0.2 opcodes,
// the bus-agnostic symbol vocabulary, the BUS_MODE indirection,
// and the sticky-flag control-flow primitive. By the end the
// audience can read a moleasm listing line by line.

#section-slide("03", "The engine and its language")

#definition-slide(
  "Quarter-bit",
  sub: [The fundamental time unit of the engine.],
)[
  #bullets(
    [One I3C / I2C bit is sliced into four equal quarters: Q0, Q1, Q2, Q3.],
    [The fabric clock #emph[is] the quarter-bit clock. No PLLs, no fractions.],
    [Every drive, sample, and capture lines up on a quarter boundary.],
  )
  #note[
    Quarter-bit time is the timing currency for the entire ISA. Once
    you internalise that one fact, everything else in this part is
    consequence, not surprise.
  ]
]

#try-it-slide(
  [Why slice each bit into #emph[four] quarters? Why not two, or
  eight, or sixteen?],
  hint: [Think about what a receiver has to observe and what a
  driver has to deliver, separately.],
)

#content-slide(
  "Why four?",
  kicker-text: "One job per quarter",
)[
  #stack(
    spacing: 1em,
    bullets(
      [*Setup* (Q0 + Q1). SDA goes stable while SCL is still low.],
      [*Sample* (Q2). SCL rises, the receiver latches the bit.],
      [*Hold + recovery* (Q3). SCL stays high; SDA may move at the end.],
    ),
    align(center, text(
      font: font-serif, size: 18pt, style: "italic", fill: muted,
    )[
      Two would lose setup. Eight would buy nothing.
      Four is the smallest split that names every legal moment.
    ]),
  )
]

#content-slide(
  "What `EMIT_BIT_IMM` emits",
  kicker-text: "Canonical waveform",
)[
  #stack(
    spacing: 0.8em,
    align(center, box(width: 92%, quarter-bit-figure(
      sda-bits: ("hi", "hi", "hi", "hi"),
      capture: 2,
    ))),
    align(center, text(
      font: font-serif, size: 15pt, style: "italic", fill: muted,
    )[
      SDA stable across the SCL edge. Need finer control?
      `EMIT_QUARTER_IMM` overrides any quarter.
    ]),
  )
]

#stat-slide(
  "26",
  "opcodes",
  caption: [
    32 bits each. Opcode is `{group[31:30], sub[29:26]}`.
    Three live groups + LOOP reserved for v0.5.
  ],
)

#let isa-row(op, desc) = (
  [#pill(op)],
  text(size: 14pt, fill: ink-soft)[#desc],
)

#content-slide(
  "Four families",
  kicker-text: "Most of it on one slide",
)[
  #grid(
    columns: (auto, 1fr, auto, 1fr),
    column-gutter: (1em, 2em, 1em),
    row-gutter: 0.7em,
    align: (left, left, left, left),
    ..isa-row("EMIT_BIT_IMM",      "canonical bit on SDA"),
    ..isa-row("BRANCH_ON",         "branch on cond code"),
    ..isa-row("EMIT_QUARTER_IMM",  "drive one quarter"),
    ..isa-row("WAIT_ON",           "block on cond"),
    ..isa-row("EMIT_BYTE_IMM",     "8 bits + ACK in one op"),
    ..isa-row("JMP",               "sugar: unconditional"),
    ..isa-row("SAMPLE_BIT_ON_SCL", "target: sample SDA"),
    ..isa-row("MARK",              "push host breadcrumb"),
    ..isa-row("DRIVE_BIT_ON_SCL",  "target: drive SDA"),
    ..isa-row("HALT",              "stop the engine"),
    ..isa-row("STRETCH_SCL_IMM",   "hold SCL n quarters"),
    ..isa-row("LOAD_IMM",          "Rd := imm14"),
    ..isa-row("SET_BUS_MODE",      "switch electrical class"),
    ..isa-row("DEC",               "Rd--; sets REG_ZERO"),
    ..isa-row("SET_ROLE",          "switch engine role"),
    ..isa-row("FLAG_CLEAR",        "clear sticky flags"),
    ..isa-row("LOAD_TIMING",       "set quarter divider"),
    ..isa-row("ADD_IMM",           "Rd := Rs + imm8"),
  )
  #note[
    Four loose families: drive / observe / control / setup. Plus a
    new DATA-group ALU (LOAD_IMM, DEC, ADD_IMM, MOV, AND_IMM,
    OR_IMM, XOR_IMM, SHIFT) for on-engine arithmetic. Don't
    memorise the list; just notice the shape. The worked example
    uses only drive + control + a single LOAD_TIMING.
  ]
]

#content-slide(
  "Four symbols",
  kicker-text: "Bus-agnostic by design",
)[
  #stack(
    spacing: 1.2em,
    align(center, stack(
      dir: ltr,
      spacing: 1em,
      pill("dominant"),
      pill("recessive"),
      pill("hiz"),
      pill("reserved"),
    )),
    bullets(
      [No `i2c_low`. No `i3c_push_pull_high`. Just symbols.],
      [The engine has zero protocol knowledge.],
      [`BUS_MODE` is the only thing that maps symbols to volts.],
    ),
  )
]

#definition-slide(
  "BUS_MODE",
  sub: [One register. Four buses. Where the protocol awareness lives.],
)[
  #stack(
    spacing: 0.8em,
    align(center, box(width: 90%, bus-mode-table-figure)),
    align(center, text(
      font: font-serif, size: 14pt, style: "italic", fill: muted,
    )[
      Adding a new bus (SMBus, PMBus, LIN, 1-Wire, CAN) =
      one more table entry. Zero ISA churn.
    ]),
  )
]

#definition-slide(
  "Sticky flags",
  sub: [`MISMATCH`, `TIMEOUT`, `START`, `STOP`, `REG_ZERO`. Write-once, until rewritten.],
)[
  #stack(
    spacing: 0.8em,
    align(center, box(width: 80%, sticky-flag-lifecycle-figure)),
    align(center, text(
      font: font-serif, size: 15pt, style: "italic", fill: muted,
    )[
      That is why `BRANCH_ON MISMATCH` "just works" -- you don't
      have to remember to clear anything.
    ]),
  )
]

#recap-slide(
  "What Part 3 leaves you with",
  (
    [Quarter-bit time is the engine's clock. Four quarters per bit, one job each.],
    [26 opcodes in four groups (WIRE / CTRL / DATA / LOOP). `EMIT_BIT_IMM` does most of the work.],
    [`tx_symbol` is the vocabulary; `BUS_MODE` is the dictionary; sticky flags drive control.],
  ),
  next: [moleasm and `mole-asm` -- turning the ISA into something you can type.],
  deeper: [`book/src/{mental-model,opcodes}.md` -- timing and the full ISA.],
)
