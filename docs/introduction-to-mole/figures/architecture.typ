// figures/architecture.typ
//
// Three-block diagram: Host (Rust + Scheme) ↔ Mole FPGA ↔ DUT.
// Drawn with cetz so it ships in-tree as code, not as a binary.

#import "../lib.typ": *
#import "@preview/cetz:0.4.2"

#let architecture-figure = cetz.canvas({
  import cetz.draw: *

  set-style(
    stroke: (paint: ink, thickness: 1pt),
    content: (padding: 0.2),
  )

  // Host
  rect((0, 0), (4.6, 2.4),
    stroke: 1.5pt + secondary,
    fill: secondary.lighten(88%),
    radius: 0.1)
  content((2.3, 1.95),
    text(font: font-sans, size: 11pt, weight: "semibold", fill: secondary)[HOST])
  content((2.3, 1.45),
    text(font: font-serif, size: 13pt, fill: ink)[Rust + Scheme])
  content((2.3, 1.0),
    text(font: font-mono, size: 9pt, fill: ink-soft)[moleasm -> bytecode])
  content((2.3, 0.55),
    text(font: font-mono, size: 9pt, fill: ink-soft)[result decoder])

  // FPGA
  rect((6.2, 0), (10.8, 2.4),
    stroke: 1.5pt + accent,
    fill: accent.lighten(88%),
    radius: 0.1)
  content((8.5, 1.95),
    text(font: font-sans, size: 11pt, weight: "semibold", fill: accent)[MOLE FPGA])
  content((8.5, 1.45),
    text(font: font-serif, size: 13pt, fill: ink)[Bit-cycle engine])
  content((8.5, 1.0),
    text(font: font-mono, size: 9pt, fill: ink-soft)[14 opcodes])
  content((8.5, 0.55),
    text(font: font-mono, size: 9pt, fill: ink-soft)[~1700 LUTs])

  // DUT
  rect((12.4, 0), (16.4, 2.4),
    stroke: 1.5pt + tertiary,
    fill: tertiary.lighten(88%),
    radius: 0.1)
  content((14.4, 1.95),
    text(font: font-sans, size: 11pt, weight: "semibold", fill: tertiary)[DUT])
  content((14.4, 1.3),
    text(font: font-serif, size: 13pt, fill: ink)[Your peripheral])

  // Link host <-> FPGA: UART
  line((4.6, 1.2), (6.2, 1.2),
    mark: (start: ">", end: ">", fill: ink))
  content((5.4, 1.5),
    text(font: font-mono, size: 9pt, fill: muted)[UART])

  // Link FPGA <-> DUT: SDA / SCL
  line((10.8, 1.5), (12.4, 1.5),
    mark: (start: ">", end: ">", fill: ink))
  content((11.6, 1.8),
    text(font: font-mono, size: 9pt, fill: muted)[SDA])
  line((10.8, 0.9), (12.4, 0.9),
    mark: (start: ">", end: ">", fill: ink))
  content((11.6, 1.2),
    text(font: font-mono, size: 9pt, fill: muted)[SCL])

  // Layer labels below
  content((2.3, -0.5),
    text(font: font-sans, size: 9pt, tracking: 2pt, fill: muted)[LAYER 1])
  content((8.5, -0.5),
    text(font: font-sans, size: 9pt, tracking: 2pt, fill: muted)[LAYER 0])
})
