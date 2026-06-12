# Introduction to Mole --- slide deck

A Typst-based introduction to the Mole I3C / I2C compliance and
conformance test rig. Built for a ~30-minute training slot: what
Mole is, why it exists, how the engine + host compiler split, and
what writing -- and intentionally breaking -- a test program looks
like end to end.

The deck is structured around eight labelled parts. Each one opens
with a section divider, ends with a recap of the two or three
things to remember, and seeds a "try it" thought-experiment before
the answer lands on the following slide. The closing slide points
to the long-form mdBook (`book/`) for everything the deck
only had time to sketch.

Built on the shared `docs/presentation-template/` design system
(see that directory's `README.md` for theme switching, slide
kinds, and authoring guidance).

## Build

Requirements:

- [Typst](https://typst.app) 0.14.2 or newer.
- Polylux 0.4.0 and cetz 0.4.2 (resolved automatically from the
  typst package cache; pinned in the shared template and in the
  figures under `figures/`).
- The [Aporetic](https://github.com/SaschaSommer/aporetic) font
  family installed on the OS font path (`Aporetic Sans`,
  `Aporetic Serif`, `Aporetic Sans Mono`). The template declares
  Aporetic as a hard requirement; without it typst falls back to
  its bundled default and emits one warning per family until you
  install it.
- GNU `make` for the convenience wrapper.

From this directory:

```sh
make             # builds slides.pdf (melissa-light)
make notes       # builds slides-notes.pdf with speaker notes
make dark        # builds slides-dark.pdf (melissa-dark)
make all         # all three
make watch       # auto-rebuild slides.pdf while editing
make clean       # remove built PDFs
```

Or invoke Typst directly. The `--root ..` flag is mandatory:
Typst forbids imports that escape the project root, and the
shared template sits one level up at
`../presentation-template/`.

```sh
typst compile --root .. slides.typ
typst compile --root .. --input notes=true slides.typ
typst compile --root .. --input theme=dark slides.typ
```

All produced PDFs (`slides.pdf`, `slides-notes.pdf`,
`slides-dark.pdf`) are gitignored.

## Layout

```text
slides.typ            ; entry point: page setup, fonts,
                      ;   include list across the eight parts.
chapters/             ; one .typ file per part.
  00-promise.typ      ; cover, what-you-get, opening hook.
  01-the-problem.typ  ; compliance vs conformance, today's gaps.
  02-architecture.typ ; three boxes, two layers, contract.
  03-isa.typ          ; quarter-bit, 26 opcodes, BUS_MODE,
                      ;   sticky flags.
  04-assembler.typ    ; moleasm syntax + the mole-asm CLI.
  05-loader.typ       ; mole-loader: wire contract + ring decode.
  06-first-test.typ   ; worked I2C single-byte write.
  07-breaking-it.typ  ; same write with a deliberate glitch.
  08-where-this-goes.typ
                      ; sims, three SKUs, status, thank-you.
figures/              ; reusable cetz diagrams (typst code,
                      ;   not images).
Makefile              ; build wrapper.
```

Every chapter and every figure imports the shared design system
explicitly:

```typst
#import "../../presentation-template/lib.typ": *
```

Typst's `#include` does not propagate import scope, so each file
that uses any slide kind, atom, or token must `#import` the
template itself.

## Slide kinds

The shared template (`../presentation-template/lib.typ`) exposes
a small vocabulary of slide kinds so chapters can stay terse and
the styling stays consistent: `cover-slide`, `section-slide`,
`content-slide`, `stat-slide`, `definition-slide`, `try-it-slide`,
`provocation-slide`, `compare-slide`, `code-slide`, `quote-slide`,
`recap-slide`, `thank-you-slide`. See
`../presentation-template/README.md` for the per-kind reference.

## Speaker notes

Wrap any prose inside `#note[...]` and it stays invisible in the
default slide build. Pass `--input notes=true` (or run
`make notes`) and the same content renders as a faint italic block
at the bottom of each slide -- useful for self-study and for
rehearsing the live talk.

## Palette + typography

The default `melissa-light` theme is warm honey paper with dark
olive ink and a burnt-honey accent; `melissa-dark` is the
inverted companion. Both palettes and the Aporetic typography
choices live in `../presentation-template/theme.typ`; this deck
inherits them unchanged.
