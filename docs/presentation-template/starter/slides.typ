// slides.typ -- entry point for the deck.
//
// This file does NOT compile in place. It is a skeleton meant to
// be copied to docs/<your-deck>/, at which point the relative
// import below resolves correctly.
//
//   cp -r docs/presentation-template/starter docs/my-new-deck
//   cd docs/my-new-deck && make
//
// Compile:
//   typst compile slides.typ                    -> slides.pdf (light)
//   typst compile --input theme=dark slides.typ -> dark theme
//   typst compile --input notes=true slides.typ -> with speaker notes

#import "../presentation-template/lib.typ": *

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

// Global raw-block rule: any fenced ```...``` in a chapter source
// lands on the shared chrome, so it looks identical to a
// `code-panel` or `code-slide` body.
#show raw: set text(font: font-mono, size: 15pt, fill: ink)
#show raw.where(block: true): it => code-chrome-block(
  text(fill: ink, it),
)

#include "chapters/00-cover.typ"
