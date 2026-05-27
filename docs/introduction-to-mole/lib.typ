// Shared definitions for the Mole introduction deck.
//
// Imported by `slides.typ` and by every chapter under
// `chapters/`. Each chapter file starts with:
//
//   #import "../lib.typ": *
//
// because Typst evaluates `#include`d files in their own scope ---
// definitions from the including file are *not* visible.

#import "@preview/polylux:0.4.0": *

#let accent = rgb("#9a3324")
#let muted  = rgb("#6b6b6b")

#let cover-slide(title, subtitle, presenter, date) = slide[
  #set align(center + horizon)
  #text(size: 56pt, weight: "bold", fill: accent)[#title] \
  #v(0.6em)
  #text(size: 28pt, font: "Aporetic Serif", style: "italic")[#subtitle] \
  #v(2em)
  #text(size: 22pt)[#presenter] \
  #text(size: 18pt, fill: muted)[#date]
]

#let section-slide(name) = slide[
  #set align(center + horizon)
  #text(size: 48pt, weight: "bold", fill: accent)[#name]
]
