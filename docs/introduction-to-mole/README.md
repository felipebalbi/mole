# Introduction to Mole --- slide deck

A Typst-based introduction to the Mole I3C / I2C compliance and
conformance test rig. Built for a ~30-minute training slot: what
Mole is, why it exists, how the engine + host compiler split, and
what writing -- and intentionally breaking -- a test program looks
like end to end.

The deck is structured around seven labelled parts. Each one opens
with a section divider, ends with a recap of the two or three
things to remember, and seeds a "try it" thought-experiment before
the answer lands on the following slide. The closing slide points
to the long-form mdBook (`mole-asm/book/`) for everything the deck
only had time to sketch.

## Build

Requirements:

- [Typst](https://typst.app) 0.14.2 or newer.
- Polylux 0.4.0 and cetz 0.4.2 (resolved automatically from the
  typst package cache; pinned in `lib.typ` and the figures under
  `figures/`).
- The [Aporetic](https://github.com/SaschaSommer/aporetic) font
  family installed on the OS font path (`Aporetic Sans`,
  `Aporetic Serif`, `Aporetic Sans Mono`). The deck declares
  Aporetic as a hard requirement; without it typst falls back to
  its bundled default and emits one warning per family until you
  install it.
- GNU `make` for the convenience wrapper.

From this directory:

```sh
make             # builds slides.pdf
make notes       # builds slides-notes.pdf (speaker notes inlined)
make all         # both
make watch       # auto-rebuild slides.pdf while editing
make clean       # remove built PDFs
```

Or invoke Typst directly:

```sh
typst compile slides.typ                       # slides only
typst compile --input notes=true slides.typ    # with speaker notes
```

Both produced PDFs (`slides.pdf`, `slides-notes.pdf`) are
gitignored.

## Layout

```text
slides.typ            ; entry point: page setup, fonts,
                      ;   include list across the seven parts.
lib.typ               ; design system: tokens, atoms,
                      ;   slide kinds, notes-mode switch.
chapters/             ; one .typ file per part.
  00-promise.typ      ; cover, what-you-get, opening hook.
  01-the-problem.typ  ; compliance vs conformance, today's gaps.
  02-architecture.typ ; three boxes, two layers, contract.
  03-timing.typ       ; quarter-bit time and why four.
  04-isa-and-moleasm.typ
                      ; 14 opcodes, BUS_MODE, sticky flags,
                      ;   the assembly syntax.
  05-first-test.typ   ; worked I2C single-byte write.
  06-breaking-it.typ  ; same write with a deliberate glitch.
  07-where-this-goes.typ
                      ; CLI, sims, three SKUs, thank-you.
figures/              ; reusable cetz diagrams (typst code,
                      ;   not images).
Makefile              ; build wrapper.
```

## Slide kinds

`lib.typ` exposes a small vocabulary of slide kinds so chapters can
stay terse and the styling stays consistent:

- `cover-slide`, `section-slide`, `thank-you-slide` -- dark chrome,
  no footer.
- `content-slide` -- the everyday slide.
- `stat-slide` -- one big number, optional caption.
- `definition-slide` -- one word, italic subtitle, body.
- `try-it-slide` -- prompt + hint + "answer on the next slide"
  banner, anchored so a long prompt can't push the banner onto
  an orphan page.
- `compare-slide` -- two columns + optional verdict.
- `code-slide` -- titled slide with a dark code panel as body.
- `quote-slide` -- big italic pull-quote.
- `recap-slide` -- end-of-part summary with checkmarks, optional
  "next up" callout, optional "go deeper" pointer into the
  mdBook.

## Speaker notes

Wrap any prose inside `#note[...]` and it stays invisible in the
default slide build. Pass `--input notes=true` (or run
`make notes`) and the same content renders as a faint italic block
at the bottom of each slide -- useful for self-study and for
rehearsing the live talk.

## Palette + typography

- ef-melissa-light (Protesilaos Stavrou) palette: warm honey
  paper, dark olive ink, burnt-honey accent.
- Aporetic Sans for kickers / chrome, Aporetic Serif for titles
  and body, Aporetic Sans Mono for code.
- Single accent for emphasis, secondary teal for left-side
  compare cues, muted chestnut for asides.
