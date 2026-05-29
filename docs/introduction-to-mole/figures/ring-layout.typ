// figures/ring-layout.typ
//
// Result-ring memory layout: 32-bit Revision prefix at the base of
// the ring, followed by a stream of CAPTURE / MARK / HALT records,
// with a wrap pointer indicating where the next write lands. Per
// fpga/Mole/TODO.md Step 11.

#import "../../presentation-template/lib.typ": *
#import "@preview/cetz:0.4.2"

#let ring-layout-figure = cetz.canvas({
  import cetz.draw: *

  set-style(
    stroke: (paint: ink, thickness: 1pt),
    content: (padding: 0.15),
  )

  let cw = 2.0   // cell width
  let ch = 1.0   // cell height
  let y = 0

  // Cell helper.
  let cell(x, label, sub: none, color: muted, fill-color: bg-tint) = {
    rect((x * cw, y), ((x + 1) * cw, y + ch),
      stroke: 1pt + color,
      fill: fill-color,
      radius: 0.05)
    content((x * cw + cw / 2, y + ch * 0.65),
      text(font: font-sans, size: 11pt, weight: "semibold", fill: color)[#label])
    if sub != none {
      content((x * cw + cw / 2, y + ch * 0.3),
        text(font: font-mono, size: 8pt, fill: ink-soft)[#sub])
    }
  }

  // Address labels along the bottom.
  let addr(x, label) = content((x * cw + cw / 2, y - 0.35),
    text(font: font-mono, size: 9pt, fill: muted-light)[#label])

  // Revision prefix (two words = 32 bits).
  cell(0, "REVISION", sub: "[31:16]", color: secondary, fill-color: secondary.lighten(90%))
  cell(1, "REVISION", sub: "[15:0]",  color: secondary, fill-color: secondary.lighten(90%))
  addr(0, "+0")
  addr(1, "+1")

  // Records.
  cell(2, "CAPTURE", sub: "bit=1", color: accent, fill-color: accent.lighten(90%))
  cell(3, "CAPTURE", sub: "bit=0", color: accent, fill-color: accent.lighten(90%))
  cell(4, "MARK",    sub: "id=01", color: tertiary, fill-color: tertiary.lighten(90%))
  cell(5, "CAPTURE", sub: "bit=1", color: accent, fill-color: accent.lighten(90%))
  cell(6, "HALT",    sub: "ok",    color: muted, fill-color: bg-subtle)
  cell(7, "...",     sub: none, color: muted-light, fill-color: bg-tint)
  addr(2, "+2")
  addr(7, "+N")

  // Write pointer arrow.
  let wp-x = 6.5 * cw
  line((wp-x, y + ch + 0.7), (wp-x, y + ch + 0.05),
    mark: (end: ">", fill: danger),
    stroke: 1.2pt + danger)
  content((wp-x, y + ch + 1.0),
    text(font: font-sans, size: 9pt, fill: danger, weight: "semibold")[
      next write
    ])

  // Caption.
  content((4 * cw, y - 0.95),
    text(font: font-serif, size: 11pt, style: "italic", fill: muted)[
      One 32-bit Revision header + a stream of records.
    ])
})
