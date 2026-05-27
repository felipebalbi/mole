// lib.typ -- design system for the Mole introduction deck.
//
// Conventions:
//   - One slide kind per public function.
//   - Token colors live at the top so the whole deck can be retuned
//     by editing a handful of lines.
//   - Slide kinds wrap polylux's `#slide` so chapters can stay terse.
//   - Notes mode is opt-in: `typst compile --input notes=true ...`
//     renders an italic "Speaker note" block at the bottom of every
//     content-bearing slide. In slide mode notes are inert.

#import "@preview/polylux:0.4.0": *

// ---- Tokens --------------------------------------------------------

// Palette: ef-melissa-light (Protesilaos Stavrou, ef-themes).
// "Melissa" = bee. Warm honey paper, dark olive ink, warm accents.

#let bg-page    = rgb("#fff6d8")  // bg-main      -- honey cream
#let bg-tint    = rgb("#fbeec3")  // halfway between bg-page and bg-subtle
#let bg-subtle  = rgb("#f5e9cb")  // bg-dim       -- dimmer cream panel
#let bg-dark    = rgb("#2a2520")  // warm near-black for cover / thanks
#let bg-code    = rgb("#2a2520")  // code panels match cover

#let ink         = rgb("#484431")  // fg-main     -- warm dark olive
#let ink-soft    = rgb("#56503a")  // bumped from #6a6147 for 4.5:1 contrast
#let ink-invert  = rgb("#fff6d8")  // bg-main on dark
#let ink-code    = rgb("#fff6d8")

#let accent      = rgb("#ba5205")  // yellow-warmer -- burnt honey
#let secondary   = rgb("#0f708a")  // cyan-cooler   -- deep teal
#let tertiary    = rgb("#007a0a")  // green
#let success     = rgb("#1a7a1a")  // darker green for body-size text
#let danger      = rgb("#c1190c")  // warm red, fits palette
#let muted       = rgb("#80431a")  // fg-alt        -- warm chestnut
#let muted-light = rgb("#a89682")
#let divider-c   = rgb("#c5baa6")  // border

#let font-sans  = ("Aporetic Sans", "Inter", "Helvetica Neue")
#let font-serif = ("Aporetic Serif", "EB Garamond", "Georgia")
#let font-mono  = ("Aporetic Sans Mono", "Cascadia Mono", "Consolas")

// ---- Notes mode ----------------------------------------------------
//
// `typst compile --input notes=true` switches the deck into notes
// mode: every #note(...) is rendered at the bottom of its slide as
// faint italic prose. The default slide build is unchanged.

#let notes-mode = sys.inputs.at("notes", default: "false") == "true"

// `note(body)` is a no-op in slide mode; in notes mode (--input
// notes=true) it renders the body as a faint italic block at the
// bottom of the slide. We deliberately don't emit pdfpc metadata
// here because polylux's speaker-note accepts only strings or raw
// blocks, and our notes carry rich content (#emph, links, code).
// If pdfpc presenter view is wanted later, add a separate raw-only
// helper.
#let note(body) = {
  if notes-mode {
    place(
      bottom + left,
      dx: 0pt, dy: -4pt,
      box(width: 100%, fill: bg-tint, inset: 10pt, radius: 4pt)[
        #text(
          font: font-serif, size: 10pt, fill: ink-soft, style: "italic",
        )[
          *Speaker note.* #body
        ]
      ],
    )
  }
}

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

// Callout box for asides, warnings, encouragement. Tints to the kind:
//   info     -- secondary teal, neutral
//   success  -- green, "you got this right"
//   warn     -- accent honey, "watch out"
//   danger   -- red, hard rule violation
#let callout(body, kind: "info", icon: none) = {
  let c = if kind == "success" { success }
    else if kind == "warn" { accent }
    else if kind == "danger" { danger }
    else { secondary }
  block(
    fill: c.lighten(88%),
    stroke: (left: 4pt + c),
    inset: (x: 14pt, y: 10pt),
    radius: (right: 4pt),
    width: 100%,
  )[
    #if icon != none [
      #text(fill: c, weight: "bold")[#icon ]
    ]
    #text(fill: ink-soft, size: 14pt)[#body]
  ]
}

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

// Check-marked recap items. Use in recap-slide.
#let checks(..items) = {
  set text(size: 18pt, fill: ink-soft)
  set par(leading: 0.55em)
  list(
    marker: text(fill: success)[✓],
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
//
// `highlight:` is a list of (pattern, color) pairs. Each pattern is a
// `regex(...)` value matched inside the raw body; matches are recoloured
// to the given fill. Useful for spotlighting `expect=`, `mask=`,
// `capture=`, opcode mnemonics, etc.
#let code-panel(body, size: 16pt, highlight: ()) = block(
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
  #for (pat, color) in highlight {
    show pat: set text(fill: color, weight: "semibold")
  }
  #body
]

// ---- Chrome --------------------------------------------------------
//
// Footer pinned to the bottom-right of content slides. Carries the
// current part label (set by section-slide) and the slide number, so
// an attendee skimming the deck always knows where they are.

#let current-part = state("mole-part", none)

#let chrome() = place(
  bottom + right, dx: 0pt, dy: 0pt,
  context {
    let p = current-part.get()
    let n = counter(page).get().first()
    text(font: font-sans, size: 9pt, fill: muted-light, tracking: 1pt)[
      #if p != none [#upper(p) · ]#n
    ]
  },
)

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
  #current-part.update("Part " + num + " · " + title)
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
  #chrome()
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
  #chrome()
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
  #chrome()
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
  #chrome()
]

// Definition slide: big word, optional etymology / sublabel, body
// explanation. Used to introduce a single new term per slide
// (quarter-bit, sticky flag, BUS_MODE, ...).
#let definition-slide(term, sub: none, kicker-text: "Definition", body) = slide[
  #slide-title(term, kicker-text: kicker-text)
  #if sub != none [
    #text(font: font-serif, size: 18pt, style: "italic", fill: muted)[#sub]
    #v(0.6em)
  ]
  #body
  #chrome()
]

// Try-it slide: poses a thought-experiment and asks the audience to
// pause before the answer lands on the next slide. The prompt is the
// content; `hint` is an optional faint nudge under it.
//
// Both the "answer on the next slide" banner and the chrome footer
// are anchored with #place so a long prompt can't push them onto a
// new page (polylux auto-paginates content that overflows).
#let try-it-slide(prompt, hint: none, kicker-text: "Try it") = slide[
  #slide-title("Pause and think.", kicker-text: kicker-text)
  #v(0.4em)
  #box(width: 100%, fill: bg-tint, inset: 18pt, radius: 6pt)[
    #text(font: font-serif, size: 20pt, fill: ink)[#prompt]
    #if hint != none [
      #v(0.6em)
      #text(font: font-serif, size: 13pt, style: "italic", fill: muted)[
        Hint: #hint
      ]
    ]
  ]
  #place(
    bottom + center, dy: -36pt,
    text(font: font-sans, size: 12pt, fill: muted-light, tracking: 3pt)[
      #upper("answer on the next slide")
    ],
  )
  #chrome()
]

// Compare slide: two-column compare/contrast, optional bottom verdict.
// Useful for before/after, controller-vs-target, OD-vs-PP, etc.
#let compare-slide(
  title,
  left-title, left,
  right-title, right,
  kicker-text: none,
  verdict: none,
) = slide[
  #slide-title(title, kicker-text: kicker-text)
  #grid(
    columns: (1fr, 1fr),
    column-gutter: 32pt,
    [
      #tag(left-title, color: secondary)
      #v(0.4em)
      #left
    ],
    [
      #tag(right-title, color: accent)
      #v(0.4em)
      #right
    ],
  )
  #if verdict != none [
    #v(0.8em)
    #align(center)[
      #text(font: font-serif, size: 18pt, style: "italic", fill: muted)[
        #verdict
      ]
    ]
  ]
  #chrome()
]

// Recap slide: end-of-part summary with checkmarks. Optional `next`
// pointer to the next part, so the audience sees the through-line.
#let recap-slide(title, points, next: none, kicker-text: "Recap") = slide[
  #slide-title(title, kicker-text: kicker-text)
  #checks(..points)
  #if next != none [
    #v(0.8em)
    #callout(
      kind: "info",
      icon: "→",
    )[Next up: #next]
  ]
  #chrome()
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
