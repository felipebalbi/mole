// figures/sticky-flag-lifecycle.typ
//
// Sticky engine flag lifecycle: clear -> set by the bearer opcode
// that triggers it -> stays set across unrelated opcodes -> cleared
// only by the next opcode that would write the same flag. Per
// AGENTS §3.15 and book/src/glossary.md ("until overwritten").

#import "../../presentation-template/lib.typ": *
#import "@preview/cetz:0.4.2"

#let sticky-flag-lifecycle-figure = cetz.canvas({
  import cetz.draw: *

  set-style(
    stroke: (paint: ink, thickness: 1pt),
    content: (padding: 0.15),
  )

  // Layout constants. Circle radius is tuned to fit the widest
  // label ("OVERWRITTEN") without text spilling past the rim;
  // step keeps centre-to-centre spacing wide enough that the
  // bottom caption lanes don't collide.
  let r = 1.3
  let step = 7.0
  let lane = 4.0   // bounded measure for the bottom captions
  let y-circle = 1.8
  let y-arrow-label = y-circle + r + 0.45
  let y-loop-label = y-circle + r + 1.1

  // Three states on a horizontal timeline.
  let states = (
    (0,        "CLEAR",       muted,    "Flag is 0. Default after reset."),
    (step,     "SET",         accent,   "An EMIT/SAMPLE wrote 1 into it."),
    (2 * step, "OVERWRITTEN", secondary, "Next opcode that would write it replaces the value."),
  )

  for (x, label, color, sub) in states {
    circle((x, y-circle),
      radius: r, fill: color.lighten(85%), stroke: 1.4pt + color)
    content((x, y-circle),
      text(font: font-sans, size: 11pt, weight: "semibold", fill: color)[#label])
    // Bounded-measure caption so long text wraps in its own lane
    // instead of colliding with the neighbour's caption.
    content((x, y-circle - r - 0.6),
      box(width: lane * 28pt, align(center, text(
        font: font-serif, size: 10pt, fill: ink-soft,
      )[#sub])))
  }

  // Straight transition arrows between circles.
  line((r + 0.1, y-circle), (step - r - 0.1, y-circle),
    mark: (end: ">", fill: ink),
    stroke: 1pt + ink-soft)
  content((step / 2, y-arrow-label),
    text(font: font-mono, size: 10pt, fill: muted)[mismatch / start / ...])

  line((step + r + 0.1, y-circle), (2 * step - r - 0.1, y-circle),
    mark: (end: ">", fill: ink),
    stroke: 1pt + ink-soft)
  content((step + step / 2, y-arrow-label),
    text(font: font-mono, size: 10pt, fill: muted)[next bearer opcode])

  // The "sticky" loop on SET, raised above the straight-edge labels
  // so the two text bands don't overlap.
  arc((step, y-loop-label - 0.1), start: 30deg, stop: 150deg,
    radius: 0.9,
    mark: (end: ">", fill: muted), stroke: 0.8pt + muted-light)
  content((step, y-loop-label + 1.0),
    text(font: font-sans, size: 9pt, fill: muted, style: "italic")[
      stays set across unrelated opcodes
    ])

  // Caption.
  content((step, y-circle - r - 2.0),
    text(font: font-serif, size: 11pt, style: "italic", fill: muted)[
      Sticky: write-once until the next write would overwrite.
    ])
})
