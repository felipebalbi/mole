#import "../lib.typ": *

// ISA tour -- two-column tables so opcodes fit on one slide each.

#section-slide("04", "The ISA")

#stat-slide(
  "14",
  "opcodes",
  caption: [Sixteen bits each. Opcode always in [15:12].],
)

#let isa-row(op, desc) = (
  [#pill(op)],
  text(size: 14pt, fill: ink-soft)[#desc],
)

#content-slide(
  "Drive & observe",
  kicker-text: "Half the ISA",
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
    ..isa-row("CAPTURE",           "push sample to ring"),
    ..isa-row("LOAD_TIMING",       "set quarter divider"),
    ..isa-row("MARK",              "push host breadcrumb"),
    ..isa-row("STRETCH_SCL",       "target: hold SCL low"),
    ..isa-row("HALT",              "stop the engine"),
  )
]

#content-slide(
  "Sticky flags",
  kicker-text: "Survive across opcodes",
)[
  #v(0.4em)
  #align(center)[
    #pill("MISMATCH") #h(0.6em)
    #pill("TIMEOUT") #h(0.6em)
    #pill("START") #h(0.6em)
    #pill("STOP")
  ]
  #v(0.8em)
  #bullets(
    [Engine sets them. Next bearer opcode clears them.],
    [That's why `BRANCH_ON MISMATCH` "just works".],
  )
]
