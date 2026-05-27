// figures/sticky-flag-lifecycle.typ
//
// Sticky engine flag lifecycle: clear -> set by the bearer opcode
// that triggers it -> stays set across unrelated opcodes -> cleared
// only by the next opcode that would write the same flag. Per
// AGENTS §3.15.

#import "../lib.typ": *
#import "@preview/cetz:0.4.2"

#let sticky-flag-lifecycle-figure = cetz.canvas({
  import cetz.draw: *

  set-style(
    stroke: (paint: ink, thickness: 1pt),
    content: (padding: 0.15),
  )

  // Three states on a horizontal timeline.
  let states = (
    (0,  "CLEAR",         muted,    "Flag is 0. Default after reset."),
    (5,  "SET",           accent,   "An EMIT/SAMPLE wrote 1 into it."),
    (10, "OVERWRITTEN",   secondary, "Next opcode that would write\nit replaces the value."),
  )

  for (x, label, color, sub) in states {
    circle((x, 1.5),
      radius: 0.6, fill: color.lighten(85%), stroke: 1.4pt + color)
    content((x, 1.5),
      text(font: font-sans, size: 11pt, weight: "semibold", fill: color)[#label])
    content((x, 0.3),
      text(font: font-serif, size: 10pt, fill: ink-soft)[#sub])
  }

  // Arrows between states.
  line((0.7, 1.5), (4.3, 1.5),
    mark: (end: ">", fill: ink),
    stroke: 1pt + ink-soft)
  content((2.5, 2.0),
    text(font: font-mono, size: 10pt, fill: muted)[mismatch / start / ...])

  line((5.7, 1.5), (9.3, 1.5),
    mark: (end: ">", fill: ink),
    stroke: 1pt + ink-soft)
  content((7.5, 2.0),
    text(font: font-mono, size: 10pt, fill: muted)[next bearer opcode])

  // The "sticky" loop on SET: unrelated opcodes don't clear it.
  arc((5, 2.15), start: 30deg, stop: 150deg, radius: 0.9,
    mark: (end: ">", fill: muted), stroke: 0.8pt + muted-light)
  content((5, 3.3),
    text(font: font-sans, size: 9pt, fill: muted, style: "italic")[
      stays set across unrelated opcodes
    ])

  // Caption.
  content((5, -0.7),
    text(font: font-serif, size: 11pt, style: "italic", fill: muted)[
      Sticky: write-once until the next write would overwrite.
    ])
})
