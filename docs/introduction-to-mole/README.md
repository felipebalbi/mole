# Introduction to Mole --- slide deck

A Typst-based introduction deck for the Mole compliance / conformance
test rig. Targets a ~30-minute audience-of-engineers talk: what Mole
is, why it exists, how the architecture splits into a tiny FPGA
engine plus a host-side compiler, and what writing a test program
looks like end-to-end.

## Build

Requirements:

- [Typst](https://typst.app) 0.14.2 or newer.
- The [Aporetic](https://github.com/SaschaSommer/aporetic) font
  family installed on the OS font path. The deck expects the
  family names `Aporetic Sans`, `Aporetic Serif`, and
  `Aporetic Sans Mono`. If your install registers different
  face names, tweak the `#set text` blocks at the top of
  `slides.typ`.
- GNU `make` for the convenience wrapper.

From this directory:

```sh
make            # builds slides.pdf
make watch      # auto-rebuild while editing
make clean      # remove slides.pdf
```

Or invoke Typst directly:

```sh
typst compile slides.typ
```

The produced `slides.pdf` is gitignored.

## Layout

```text
slides.typ            ; entry point: page setup, fonts, chapter
                      ;   include list.
chapters/             ; one .typ file per logical section.
  00-cover.typ        ; title slide + agenda.
  01-problem.typ      ; what compliance testing is, why existing
                      ;   tools cost too much.
  02-architecture.typ ; engine + compiler split.
  03-quarter-bits.typ ; the timing model.
  04-isa-tour.typ     ; the 14-opcode ISA.
  05-moleasm-syntax.typ
  06-worked-example-i2c.typ
  07-worked-example-glitch.typ
  08-results-ring.typ
  09-tooling.typ
  10-roadmap.typ
figures/              ; reusable diagrams (typst code, not images).
Makefile              ; build wrapper.
```

## Scope

This first cut covers the **controller-role** half of Mole: what
ships today, what you can write `moleasm` for today, what the host
toolchain looks like today. **Target-role** (Mole replying as a
peripheral, including I3C DAA arbitration) is under development on
the `phase5-target-role` branch and is mentioned in the roadmap
chapter at the end of the deck rather than walked through in
detail. Once target role lands, a follow-up chapter slots in
between the worked-example glitch chapter and the results-ring
chapter.

## Style

- Polylux 0.4.x slide template, 16:9 aspect ratio.
- Body text in Aporetic Sans 22pt, code in Aporetic Sans Mono
  18pt, headings in Aporetic Serif.
- A single deep-red accent (`#9a3324`) for titles and emphasis;
  a muted grey for secondary text. No other colour --- the deck
  reads cleanly when projected or printed.
