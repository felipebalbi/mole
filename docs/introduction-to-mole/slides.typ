// Mole --- Introduction deck.
//
// Build with `typst compile slides.typ` from this directory, or
// `make` (see the local Makefile). Output goes to `slides.pdf`,
// which is gitignored.
//
// Polylux 0.4.x.  Aporetic Sans for body, Aporetic Serif for
// headings, Aporetic Sans Mono for code.  Install the fonts in
// the typical OS font path before building; see README.md.
//
// Shared helpers and the polylux import live in `lib.typ` so the
// chapter files (`#include`d below, each evaluated in its own
// scope) can also reach them via `#import "../lib.typ": *`.

#import "lib.typ": *

#set page(
  paper: "presentation-16-9",
  margin: (x: 2cm, y: 1.5cm),
  fill: rgb("#fdfdfb"),
)

#set text(
  font: "Aporetic Sans",
  size: 22pt,
  fill: rgb("#1a1a1a"),
)

#show heading: set text(font: "Aporetic Serif", weight: "semibold")
#show heading.where(level: 1): set text(size: 38pt)
#show heading.where(level: 2): set text(size: 30pt)
#show heading.where(level: 3): set text(size: 24pt)

#show raw: set text(font: "Aporetic Sans Mono", size: 18pt)

// --- Deck order -------------------------------------------------
#include "chapters/00-cover.typ"
#include "chapters/01-problem.typ"
#include "chapters/02-architecture.typ"
#include "chapters/03-quarter-bits.typ"
#include "chapters/04-isa-tour.typ"
#include "chapters/05-moleasm-syntax.typ"
#include "chapters/06-worked-example-i2c.typ"
#include "chapters/07-worked-example-glitch.typ"
#include "chapters/08-results-ring.typ"
#include "chapters/09-tooling.typ"
#include "chapters/10-roadmap.typ"
