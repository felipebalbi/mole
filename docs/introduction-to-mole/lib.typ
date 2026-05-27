// lib.typ -- design system for the Mole introduction deck.
//
// Conventions:
//   - One slide kind per public function.
//   - Token colors live at the top so the whole deck can be retuned
//     by editing a handful of lines.
//   - Slide kinds wrap polylux's `#slide` so chapters can stay terse.

#import "@preview/polylux:0.4.0": *

// ---- Tokens --------------------------------------------------------

// Palette: ef-melissa-light (Protesilaos Stavrou, ef-themes).
// "Melissa" = bee. Warm honey paper, dark olive ink, warm accents.

#let bg-page    = rgb("#fff6d8")  // bg-main      -- honey cream
#let bg-subtle  = rgb("#f5e9cb")  // bg-dim       -- dimmer cream panel
#let bg-dark    = rgb("#2a2520")  // warm near-black for cover / thanks
#let bg-code    = rgb("#2a2520")  // code panels match cover

#let ink         = rgb("#484431")  // fg-main     -- warm dark olive
#let ink-soft    = rgb("#6a6147")  // softened
#let ink-invert  = rgb("#fff6d8")  // bg-main on dark
#let ink-code    = rgb("#fff6d8")

#let accent      = rgb("#ba5205")  // yellow-warmer -- burnt honey
#let secondary   = rgb("#0f708a")  // cyan-cooler   -- deep teal
#let tertiary    = rgb("#007a0a")  // green
#let muted       = rgb("#80431a")  // fg-alt        -- warm chestnut
#let muted-light = rgb("#a89682")
#let divider-c   = rgb("#c5baa6")  // border

#let font-sans  = ("Aporetic Sans", "Inter", "Helvetica Neue")
#let font-serif = ("Aporetic Serif", "EB Garamond", "Georgia")
#let font-mono  = ("Aporetic Sans Mono", "Cascadia Mono", "Consolas")

// ---- Atoms ---------------------------------------------------------

#let kicker(s, color: accent) = text(
  font: font-sans, size: 12pt, weight: "semibold",
  tracking: 3pt, fill: color,
)[#upper(s)]

#let rule(w: 60pt, color: accent) = box(width: w, height: 3pt, fill: color)

#let pill(body, color: secondary) = box(
  fill: color.lighten(85%),
  stroke: 1pt + color,
  inset: (x: 10pt, y: 4pt),
  radius: 14pt,
  text(font: font-mono, size: 14pt, fill: color)[#body],
)

#let tag(body, color: accent) = text(
  font: font-sans, size: 12pt, weight: "semibold",
  tracking: 2pt, fill: color,
)[#upper(body)]

#let slide-title(s, kicker-text: none) = block[
  #if kicker-text != none [
    #kicker(kicker-text)
    #v(0.2em)
  ]
  #text(font: font-serif, size: 32pt, weight: "semibold", fill: ink)[#s]
  #v(0.15em)
  #rule()
  #v(0.4em)
]

#let bullets(..items) = {
  set text(size: 18pt, fill: ink-soft)
  set par(leading: 0.55em)
  list(
    marker: text(fill: accent)[●],
    spacing: 0.7em,
    ..items.pos(),
  )
}

#let numbered(..items) = {
  set text(size: 18pt, fill: ink-soft)
  set par(leading: 0.55em)
  enum(
    spacing: 0.7em,
    ..items.pos(),
  )
}

#let stat-block(value, label) = align(center)[
  #text(font: font-serif, size: 110pt, weight: "bold", fill: accent)[#value]
  #v(-0.3em)
  #text(font: font-sans, size: 18pt, fill: muted, tracking: 3pt)[#upper(label)]
]

#let two-col(left, right) = grid(
  columns: (1fr, 1fr),
  column-gutter: 40pt,
  left, right,
)

#let three-col(a, b, c) = grid(
  columns: (1fr, 1fr, 1fr),
  column-gutter: 28pt,
  a, b, c,
)

// A dark code panel for use inside content-slides.  The inner show rule
// undoes the global cream raw wrap so text is light-on-dark.
#let code-panel(body, size: 16pt) = block(
  fill: bg-code,
  inset: 16pt,
  radius: 6pt,
  width: 100%,
)[
  #show raw.where(block: true): it => block(
    fill: none, inset: 0pt, width: 100%,
    text(font: font-mono, fill: ink-code, size: size, it),
  )
  #show raw.where(block: false): it => (
    text(font: font-mono, fill: ink-code, size: size, it)
  )
  #body
]

// ---- Slide kinds ---------------------------------------------------

#let cover-slide(title, subtitle, author, date) = slide[
  #set page(fill: bg-dark, margin: (x: 70pt, y: 50pt))
  #set text(fill: ink-invert)
  #place(
    left + top, dx: 0pt, dy: 0pt,
    box(width: 6pt, height: 100%, fill: accent),
  )
  #v(1fr)
  #kicker("Introducing", color: accent)
  #v(0.3em)
  #text(font: font-serif, size: 100pt, weight: "bold", fill: ink-invert)[#title]
  #v(0.3em)
  #text(font: font-serif, size: 24pt, style: "italic", fill: muted-light)[#subtitle]
  #v(1fr)
  #grid(
    columns: (1fr, auto),
    align: (left, right),
    text(font: font-sans, size: 14pt, fill: muted-light)[#author],
    text(font: font-sans, size: 14pt, fill: muted-light)[#date],
  )
]

#let section-slide(num, title) = slide[
  #set page(fill: bg-page)
  #v(1fr)
  #kicker("Part " + num, color: accent)
  #v(0.5em)
  #text(font: font-serif, size: 64pt, weight: "semibold", fill: ink)[#title]
  #v(0.4em)
  #rule(w: 100pt)
  #v(1fr)
]

#let content-slide(title, kicker-text: none, body) = slide[
  #slide-title(title, kicker-text: kicker-text)
  #body
]

#let stat-slide(value, label, caption: none) = slide[
  #v(1fr)
  #stat-block(value, label)
  #if caption != none [
    #v(0.8em)
    #align(center)[
      #text(font: font-serif, size: 20pt, style: "italic", fill: muted)[#caption]
    ]
  ]
  #v(1fr)
]

#let quote-slide(body, by: none) = slide[
  #v(1fr)
  #align(center)[
    #box(width: 78%)[
      #text(font: font-serif, size: 30pt, style: "italic", fill: ink)[
        \"#body\"
      ]
      #if by != none [
        #v(0.8em)
        #align(right)[
          #text(font: font-sans, size: 14pt, fill: muted, tracking: 2pt)[#upper("-- " + by)]
        ]
      ]
    ]
  ]
  #v(1fr)
]

// Code slide: scoped raw-block rule renders straight onto a dark panel.
// The body is just one or more raw blocks (and optional prose).
#let code-slide(title, kicker-text: none, body) = slide[
  #slide-title(title, kicker-text: kicker-text)
  #show raw.where(block: true): it => block(
    fill: bg-code, inset: 16pt, radius: 6pt, width: 100%,
    text(font: font-mono, fill: ink-code, size: 16pt, it),
  )
  #body
]

#let thank-you-slide(link-text) = slide[
  #set page(fill: bg-dark)
  #set text(fill: ink-invert)
  #v(1fr)
  #align(center)[
    #text(font: font-serif, size: 96pt, weight: "bold", fill: ink-invert)[Thanks.]
    #v(1em)
    #text(font: font-mono, size: 18pt, fill: accent)[#link-text]
  ]
  #v(1fr)
]
