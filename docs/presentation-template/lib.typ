// lib.typ -- design system for Mole presentation decks.
//
// Extracted from docs/introduction-to-mole/lib.typ and refactored
// to be theme-switchable via theme.typ. Behaviour is otherwise
// identical: same slide kinds, same atoms, same notes-mode switch.
//
// Conventions:
//   - One slide kind per public function.
//   - Color tokens come from `theme.typ`; never hardcode rgb(...)
//     in this file. Adding a third theme should be a one-file
//     change in theme.typ.
//   - Slide kinds wrap polylux's `#slide` so chapters can stay
//     terse.
//   - Notes mode is opt-in: `typst compile --input notes=true ...`
//     renders an italic "Speaker note" block at the bottom of every
//     content-bearing slide. In slide mode notes are inert.
//
// Theme selection (from a consumer deck):
//   typst compile slides.typ                    -> melissa-light
//   typst compile --input theme=dark slides.typ -> melissa-dark

#import "@preview/polylux:0.4.0": *
#import "theme.typ"

// ---- Tokens (sourced from active theme) ---------------------------
//
// Token names follow ef-themes. The handful of legacy aliases
// (bg-page, ink, ink-soft, bg-subtle, ...) are kept so existing
// chapter sources keep working while the design system is in
// flight; new code should prefer the ef-themes names.

// Background tiers.
#let bg-main = theme.active.bg-main
#let bg-dim  = theme.active.bg-dim
#let bg-alt  = theme.active.bg-alt

// Foreground tiers.
#let fg-main = theme.active.fg-main
#let fg-dim  = theme.active.fg-dim
#let fg-alt  = theme.active.fg-alt

// Named hues (ink + matching subtle background per hue).
#let red               = theme.active.red
#let green             = theme.active.green
#let yellow            = theme.active.yellow
#let blue              = theme.active.blue
#let magenta           = theme.active.magenta
#let cyan              = theme.active.cyan
#let bg-red-subtle     = theme.active.bg-red-subtle
#let bg-green-subtle   = theme.active.bg-green-subtle
#let bg-yellow-subtle  = theme.active.bg-yellow-subtle
#let bg-blue-subtle    = theme.active.bg-blue-subtle
#let bg-magenta-subtle = theme.active.bg-magenta-subtle
#let bg-cyan-subtle    = theme.active.bg-cyan-subtle

// Identity / chrome.
#let accent      = theme.active.accent
#let secondary   = theme.active.secondary
#let muted       = theme.active.muted
#let muted-light = theme.active.muted-light
#let divider-c   = theme.active.divider

// Legacy aliases. New code: use the ef-themes names above.
#let bg-page    = bg-main
#let bg-tint    = bg-dim
#let bg-subtle  = bg-dim
#let bg-code    = bg-alt
#let cover-fill = bg-alt
#let cover-ink  = fg-main
#let bg-dark    = bg-alt
#let ink        = fg-main
#let ink-soft   = fg-dim
#let ink-invert = fg-main
#let ink-code   = fg-main
#let tertiary   = green
#let success    = green
#let danger     = red

// Font families. We list Aporetic alone -- typst 0.14 warns once
// per unresolved family in a fallback chain, so a cross-platform
// list like ("Aporetic Sans", "Inter", "Helvetica Neue") is just
// noise on a box that has Aporetic installed (the common case) and
// is *more* noise on a box that has none of them. The README makes
// Aporetic a hard requirement; if it's missing, typst falls back to
// its own default and emits one honest warning per family, which is
// the correct outcome.
#let font-sans  = ("Aporetic Sans",)
#let font-serif = ("Aporetic Serif",)
#let font-mono  = ("Aporetic Sans Mono",)

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
      box(width: 100%, fill: bg-tint, inset: 0.8em, radius: 4pt)[
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

#let pill(body, kind: "info") = {
  let c = theme.active.callout.at(kind)
  box(
    fill: c.fill,
    stroke: 1pt + c.ink,
    inset: (x: 10pt, y: 4pt),
    radius: 14pt,
    text(font: font-mono, size: 14pt, fill: c.ink)[#body],
  )
}

#let tag(body, color: accent) = text(
  font: font-sans, size: 12pt, weight: "semibold",
  tracking: 2pt, fill: color,
)[#upper(body)]

// Callout box for asides, warnings, encouragement. Looks up the
// (fill, ink) pair for the requested kind from the active theme:
//   info     -- bg-blue-subtle    + blue
//   warn     -- bg-yellow-subtle  + yellow
//   success  -- bg-green-subtle   + green
//   danger   -- bg-red-subtle     + red
//
// No alpha math, no derived colors -- both values are pulled
// verbatim from theme.typ. The stripe on the left edge uses the
// same ink color so the callout reads as one coloured semantic
// object.
#let callout(body, kind: "info", icon: none) = {
  let c = theme.active.callout.at(kind)
  block(
    fill: c.fill,
    stroke: (left: 4pt + c.ink),
    inset: (x: 14pt, y: 10pt),
    radius: (right: 4pt),
    width: 100%,
  )[
    #if icon != none [
      #text(fill: c.ink, weight: "bold")[#icon ]
    ]
    #text(fill: c.ink, size: 14pt)[#body]
  ]
}

#let slide-title(s, kicker-text: none) = block(below: 0.8em)[
  #stack(
    spacing: 1em,
    if kicker-text != none { kicker(kicker-text) },
    text(font: font-serif, size: 32pt, weight: "semibold", fill: ink)[#s],
    rule(),
  )
]

#let bullets(..items) = {
  set text(size: 18pt, fill: ink-soft)
  set par(leading: 1em)
  list(
    marker: text(fill: accent)[●],
    spacing: 1em,
    ..items.pos(),
  )
}

#let numbered(..items) = {
  set text(size: 18pt, fill: ink-soft)
  set par(leading: 1em)
  enum(
    spacing: 1em,
    ..items.pos(),
  )
}

// Check-marked recap items. Use in recap-slide.
#let checks(..items) = {
  set text(size: 18pt, fill: ink-soft)
  set par(leading: 1em)
  list(
    marker: text(fill: success)[✓],
    spacing: 1em,
    ..items.pos(),
  )
}

#let stat-block(value, label) = align(center)[
  #stack(
    spacing: 1em,
    text(font: font-serif, size: 110pt, weight: "bold", fill: accent)[#value],
    text(font: font-sans, size: 18pt, fill: muted, tracking: 3pt)[#upper(label)],
  )
]

#let two-col(left, right) = grid(
  columns: (1fr, 1fr),
  column-gutter: 4%,
  align: top,
  left, right,
)

#let three-col(a, b, c) = grid(
  columns: (1fr, 1fr, 1fr),
  column-gutter: 3%,
  align: top,
  a, b, c,
)

// Unified chrome for every code block on a content slide. Used by
// the global raw-block show rule in slides.typ, by `code-panel`
// (inline), and by `code-slide` (titled). Single source of truth
// so the cream / radius / inset don't drift apart between render
// paths.
//
// No stroke: the tone difference between bg-subtle (panel) and
// bg-page (slide) carries the boundary on its own ("card on
// paper" idiom).
//
// breakable: false so the chrome always encloses every line of
// the block. If a code sample doesn't fit, it overflows
// honestly off the slide bottom (a content problem) instead of
// silently shedding its border (a chrome lie).
#let code-chrome-block(body) = block(
  fill: bg-subtle,
  inset: 1em,
  radius: 4pt,
  width: 100%,
  breakable: false,
  body,
)

// A code panel for use inline inside content-slides.  Reads as a
// soft panel with body-colored text -- the chrome should hint at
// "code" without competing with the slide title.
//
// `highlight:` is a list of (pattern, color) pairs. Each pattern is a
// `regex(...)` value matched inside the raw body; matches are recoloured
// to the given fill. Useful for spotlighting field names, mnemonics,
// keywords, etc.
#let code-panel(body, size: 16pt, highlight: ()) = code-chrome-block[
  #set text(size: size)
  #show raw.where(block: true): it => block(
    fill: none, inset: 0pt, width: 100%,
    text(font: font-mono, fill: ink, size: size, it),
  )
  #show raw.where(block: false): it => (
    text(font: font-mono, fill: ink, size: size, it)
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

#let current-part = state("deck-part", none)

#let chrome() = place(
  bottom + right,
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
  #set page(fill: cover-fill)
  #set text(fill: cover-ink)
  #place(
    left + top,
    box(width: 6pt, height: 100%, fill: accent),
  )
  #pad(left: 2.5em, grid(
    rows: (1fr, auto, 1fr, auto),
    row-gutter: 0pt,
    [],
    stack(
      spacing: 1em,
      kicker("Introducing", color: accent),
      text(
        font: font-serif, size: 100pt, weight: "bold", fill: cover-ink,
      )[#title],
      text(
        font: font-serif, size: 24pt, style: "italic", fill: muted-light,
      )[#subtitle],
    ),
    [],
    grid(
      columns: (1fr, auto),
      align: (left + bottom, right + bottom),
      text(font: font-sans, size: 14pt, fill: muted-light)[#author],
      text(font: font-sans, size: 14pt, fill: muted-light)[#date],
    ),
  ))
]

#let section-slide(num, title) = slide[
  #set page(fill: bg-page)
  #current-part.update("Part " + num + " · " + title)
  #grid(
    rows: (1fr, auto, 1fr),
    [],
    stack(
      spacing: 1em,
      kicker("Part " + num, color: accent),
      text(font: font-serif, size: 64pt, weight: "semibold", fill: ink)[#title],
      rule(w: 100pt),
    ),
    [],
  )
]

#let content-slide(title, kicker-text: none, body) = slide[
  #pad(y: 1em, grid(
    rows: (auto, 1fr),
    slide-title(title, kicker-text: kicker-text),
    align(horizon, body),
  ))
  #chrome()
]

#let stat-slide(value, label, caption: none) = slide[
  #pad(y: 1em, grid(
    columns: 1fr,
    rows: (1fr, auto, 1fr),
    [],
    align(horizon + center, stack(
      spacing: 0.6em,
      align(center, text(
        font: font-serif, size: 110pt, weight: "bold", fill: accent,
      )[#value]),
      align(center, text(
        font: font-sans, size: 18pt, fill: muted, tracking: 3pt,
      )[#upper(label)]),
      if caption != none {
        align(center, text(
          font: font-serif, size: 20pt, style: "italic", fill: muted,
        )[#caption])
      },
    )),
    [],
  ))
  #chrome()
]

#let quote-slide(body, by: none) = slide[
  #pad(y: 1em, grid(
    rows: (1fr, auto, 1fr),
    [],
    align(center, box(width: 78%, stack(
      spacing: 1em,
      text(font: font-serif, size: 30pt, style: "italic", fill: ink)[
        \"#body\"
      ],
      if by != none {
        align(right, text(
          font: font-sans, size: 14pt, fill: muted, tracking: 2pt,
        )[#upper("-- " + by)])
      },
    ))),
    [],
  ))
  #chrome()
]

// Code slide: titled slide whose body is one or more raw blocks.
// The scoped show rule wraps each block in the shared chrome so a
// `code-slide` looks identical to a `content-slide` whose body is
// a `code-panel` -- same fill, stroke, radius, inset.
#let code-slide(title, kicker-text: none, body) = slide[
  #show raw.where(block: true): it => code-chrome-block(
    text(font: font-mono, fill: ink, size: 16pt, it),
  )
  #pad(y: 1em, grid(
    rows: (auto, 1fr),
    slide-title(title, kicker-text: kicker-text),
    body,
  ))
  #chrome()
]

// Definition slide: big word, optional etymology / sublabel, body
// explanation. Used to introduce a single new term per slide.
#let definition-slide(term, sub: none, kicker-text: "Definition", body) = slide[
  #pad(y: 1em, grid(
    rows: (auto, 1fr),
    slide-title(term, kicker-text: kicker-text),
    align(horizon, stack(
      spacing: 1em,
      if sub != none {
        text(
          font: font-serif, size: 18pt, style: "italic", fill: muted,
        )[#sub]
      },
      body,
    )),
  ))
  #chrome()
]

// Try-it slide: poses a thought-experiment and asks the audience to
// pause before the answer lands on the next slide.
#let try-it-slide(prompt, hint: none, kicker-text: "Try it") = slide[
  #pad(y: 1em, grid(
    rows: (auto, auto, 1fr, auto),
    row-gutter: 1em,
    slide-title("Pause and think.", kicker-text: kicker-text),
    block(
      width: 100%, fill: bg-tint, inset: 1em, radius: 6pt,
      breakable: false,
    )[
      #stack(
        spacing: 1em,
        text(font: font-serif, size: 20pt, fill: ink)[#prompt],
        if hint != none {
          text(
            font: font-serif, size: 13pt, style: "italic", fill: muted,
          )[Hint: #hint]
        },
      )
    ],
    [],
    align(center + bottom, text(
      font: font-sans, size: 12pt, fill: muted-light, tracking: 3pt,
    )[#upper("answer on the next slide")]),
  ))
  #chrome()
]

// Provocation slide: a rhetorical opener / food-for-thought hook
// that does NOT have a follow-up answer slide.
#let provocation-slide(
  prompt,
  hint: none,
  kicker-text: "Food for thought",
  title: "Pause and consider.",
) = slide[
  #pad(y: 1em, grid(
    rows: (auto, auto, 1fr),
    row-gutter: 1em,
    slide-title(title, kicker-text: kicker-text),
    block(
      width: 100%, fill: bg-tint, inset: 1em, radius: 6pt,
      breakable: false,
    )[
      #stack(
        spacing: 1em,
        text(font: font-serif, size: 20pt, fill: ink)[#prompt],
        if hint != none {
          text(
            font: font-serif, size: 13pt, style: "italic", fill: muted,
          )[Hint: #hint]
        },
      )
    ],
    [],
  ))
  #chrome()
]

// Compare slide: two-column compare/contrast, optional bottom verdict.
#let compare-slide(
  title,
  left-title, left,
  right-title, right,
  kicker-text: none,
  verdict: none,
) = slide[
  #pad(y: 1em, grid(
    rows: (auto, auto, 1fr, auto),
    row-gutter: 1em,
    slide-title(title, kicker-text: kicker-text),
    grid(
      columns: (1fr, 1fr),
      column-gutter: 4%,
      align: top,
      stack(
        spacing: 0.5em,
        tag(left-title, color: secondary),
        left,
      ),
      stack(
        spacing: 0.5em,
        tag(right-title, color: accent),
        right,
      ),
    ),
    [],
    if verdict != none {
      align(center, text(
        font: font-serif, size: 18pt, style: "italic", fill: muted,
      )[#verdict])
    },
  ))
  #chrome()
]

// Recap slide: end-of-part summary with checkmarks.
#let recap-slide(
  title, points, next: none, deeper: none, kicker-text: "Recap",
) = slide[
  #pad(y: 1em, grid(
    rows: (auto, auto, 1fr, auto, auto),
    row-gutter: 0.8em,
    slide-title(title, kicker-text: kicker-text),
    checks(..points),
    [],
    if next != none {
      callout(kind: "info", icon: "→")[Next up: #next]
    },
    if deeper != none {
      align(left, text(
        font: font-sans, size: 11pt, fill: muted, tracking: 1pt,
      )[↪ Go deeper: #deeper])
    },
  ))
  #chrome()
]

// Thank-you slide. Inverted-chrome close with a short list of
// pointers (repo, manual, etc). Each pointer is the filesystem /
// URL path itself so the audience can copy/paste.
#let thank-you-slide(
  repo,
  book: none,
  roadmap: none,
  contributing: none,
  headline: "Thanks.",
  tagline: none,
) = slide[
  #set page(fill: cover-fill)
  #set text(fill: cover-ink)
  #grid(
    rows: (1fr, auto, auto, 1fr),
    row-gutter: 1.2em,
    [],
    align(center, stack(
      spacing: 0.5em,
      text(
        font: font-serif, size: 96pt, weight: "bold", fill: cover-ink,
      )[#headline],
      if tagline != none {
        text(
          font: font-serif, size: 20pt, style: "italic", fill: muted-light,
        )[#tagline]
      },
    )),
    align(center, box(width: 78%, grid(
      columns: (auto, 1fr),
      column-gutter: 2%,
      row-gutter: 0.8em,
      align: (right, left),
      text(
        font: font-sans, size: 12pt, fill: accent, tracking: 2pt,
      )[#upper("Repo")],
      text(font: font-mono, size: 14pt, fill: cover-ink)[#repo],
      ..if book != none {(
        text(
          font: font-sans, size: 12pt, fill: accent, tracking: 2pt,
        )[#upper("Manual")],
        text(font: font-mono, size: 14pt, fill: cover-ink)[#book],
      )} else { () },
      ..if roadmap != none {(
        text(
          font: font-sans, size: 12pt, fill: accent, tracking: 2pt,
        )[#upper("Design")],
        text(font: font-mono, size: 14pt, fill: cover-ink)[#roadmap],
      )} else { () },
      ..if contributing != none {(
        text(
          font: font-sans, size: 12pt, fill: accent, tracking: 2pt,
        )[#upper("Hack on it")],
        text(font: font-mono, size: 14pt, fill: cover-ink)[#contributing],
      )} else { () },
    ))),
    [],
  )
]
