// slides.typ -- entry point for the Mole introduction deck.

#import "lib.typ": *

#set page(
  paper: "presentation-16-9",
  margin: (x: 6%, y: 5%),
  fill: bg-page,
)

#set text(
  font: font-sans,
  size: 18pt,
  fill: ink,
)

// Global rules: inline raw is mono-only; block raw lands on the
// shared cream chrome (see `code-chrome-block` in lib.typ) so any
// fenced ```...``` in a chapter source looks the same as one
// inside a `code-panel` or `code-slide` -- single source of truth
// for the chrome shape.
#show raw: set text(font: font-mono, size: 15pt, fill: ink)
#show raw.where(block: true): it => code-chrome-block(
  text(fill: ink, it),
)

#include "chapters/00-promise.typ"
#include "chapters/01-the-problem.typ"
#include "chapters/02-architecture.typ"
#include "chapters/03-isa.typ"
#include "chapters/04-assembler.typ"
#include "chapters/05-loader.typ"
#include "chapters/06-first-test.typ"
#include "chapters/07-breaking-it.typ"
#include "chapters/08-where-this-goes.typ"
