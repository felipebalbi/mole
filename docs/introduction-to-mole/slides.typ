// slides.typ -- entry point for the Mole introduction deck.

#import "lib.typ": *

#set page(
  paper: "presentation-16-9",
  margin: (x: 50pt, y: 40pt),
  fill: bg-page,
)

#set text(
  font: font-sans,
  size: 18pt,
  fill: ink,
)

// Global rules: inline raw is mono-only; block raw lands on a subtle
// cream panel with explicit dark fill so it never vanishes when wrapped
// in a styled context.  `code-slide` overrides this to render straight
// onto a dark panel.  `code-panel` (in lib.typ) does the same inline.
#show raw: set text(font: font-mono, size: 15pt, fill: ink)
#show raw.where(block: true): it => block(
  fill: bg-subtle,
  inset: 12pt,
  radius: 4pt,
  width: 100%,
  text(fill: ink, it),
)

#include "chapters/00-promise.typ"
#include "chapters/01-the-problem.typ"
#include "chapters/02-architecture.typ"
#include "chapters/03-timing.typ"
#include "chapters/04-isa-and-moleasm.typ"
#include "chapters/05-first-test.typ"
#include "chapters/06-breaking-it.typ"
#include "chapters/07-where-this-goes.typ"
