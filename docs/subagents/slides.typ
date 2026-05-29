// slides.typ -- "Subagents: personas for your repo".
//
// Compile:
//   typst compile slides.typ                    -> slides.pdf
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

#show raw: set text(font: font-mono, size: 15pt, fill: ink)
#show raw.where(block: true): it => code-chrome-block(
  text(fill: ink, it),
)

#include "chapters/00-cover.typ"
#include "chapters/01-why-personas.typ"
#include "chapters/02-anatomy.typ"
#include "chapters/03-mole-personas.typ"
#include "chapters/04-invocation.typ"
#include "chapters/05-authoring.typ"
#include "chapters/06-cost-discipline.typ"
#include "chapters/07-anti-patterns.typ"
#include "chapters/08-claude-parity.typ"
#include "chapters/09-recap.typ"
