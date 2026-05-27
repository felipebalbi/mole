// figures/bus-mode-table.typ
//
// BUS_MODE register: maps the engine's bus-agnostic tx_symbol
// vocabulary (dominant / recessive / hi-Z) onto an electrical
// drive class. Per ROADMAP §"Bus mode register" and AGENTS §3.12.

#import "../lib.typ": *
#import "@preview/cetz:0.4.2"

#let bus-mode-table-figure = cetz.canvas({
  import cetz.draw: *

  set-style(
    stroke: (paint: ink, thickness: 1pt),
    content: (padding: 0.15),
  )

  let cw = 2.6   // column width
  let ch = 0.85  // row height
  let cols = 4
  let rows = 5   // 1 header + 4 modes

  // Grid.
  for r in range(rows + 1) {
    line((0, -r * ch), (cols * cw, -r * ch),
      stroke: (paint: divider-c, thickness: 0.5pt))
  }
  for c in range(cols + 1) {
    line((c * cw, 0), (c * cw, -rows * ch),
      stroke: (paint: divider-c, thickness: 0.5pt))
  }

  // Cell helper.
  let cell(c, r, body, fill-color: none, weight: "regular", color: ink) = {
    if fill-color != none {
      rect((c * cw, -r * ch), ((c + 1) * cw, -(r + 1) * ch),
        stroke: none, fill: fill-color)
    }
    content(
      (c * cw + cw / 2, -(r * ch + ch / 2)),
      text(font: font-sans, size: 11pt, weight: weight, fill: color)[#body],
    )
  }

  // Header row.
  cell(0, 0, "BUS_MODE", weight: "semibold", color: muted,
    fill-color: bg-subtle)
  cell(1, 0, "dominant", weight: "semibold", color: muted,
    fill-color: bg-subtle)
  cell(2, 0, "recessive", weight: "semibold", color: muted,
    fill-color: bg-subtle)
  cell(3, 0, "hi-Z", weight: "semibold", color: muted,
    fill-color: bg-subtle)

  // Data rows. (BUS_MODE name, dominant, recessive, hi-Z)
  let rows-data = (
    ("i2c",     "drive low", "release",     "release"),
    ("i3c-OD",  "drive low", "release",     "release"),
    ("i3c-PP",  "drive low", "drive high",  "release"),
    ("hdr-ddr", "drive low", "drive high",  "release"),
  )

  let i = 1
  for (mode, d, r, z) in rows-data {
    cell(0, i, raw(mode), weight: "semibold", color: accent)
    cell(1, i, d, color: ink-soft)
    cell(2, i, r, color: ink-soft)
    cell(3, i, z, color: ink-soft)
    i = i + 1
  }

  // Caption.
  content((cols * cw / 2, -(rows * ch + 0.55)),
    text(font: font-serif, size: 11pt, style: "italic", fill: muted)[
      The engine speaks symbols. BUS_MODE picks the volts.
    ])
})
