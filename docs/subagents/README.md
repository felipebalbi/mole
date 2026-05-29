# Subagents --- slide deck

A Typst training deck on subagent personas: what they are, how
Mole wires them into `.opencode/agent/`, how to write one the
router will actually reach for, and how to spend AI credits with
discipline.

Targets a ~30-minute training slot for engineers already using
opencode (or Claude Code) day-to-day, with mixed familiarity with
the subagent model.

Built on the shared `docs/presentation-template/` design system
(see that directory's `README.md` for theme switching, slide
kinds, and authoring guidance).

## Build

Requirements: same as `docs/presentation-template/`. From this
directory:

```sh
make             # builds slides.pdf (melissa-light)
make notes       # builds slides-notes.pdf with speaker notes
make dark        # builds slides-dark.pdf (melissa-dark)
make all         # all three
make watch       # auto-rebuild slides.pdf while editing
make clean       # remove built PDFs
```

Or invoke Typst directly:

```sh
typst compile slides.typ
typst compile --input notes=true slides.typ
typst compile --input theme=dark slides.typ
```

Built PDFs are gitignored.

## Layout

```text
slides.typ              entry point: page setup, fonts, chapter
                        include list.
chapters/               one .typ file per part.
  00-cover.typ          cover + opening promise.
  01-why-personas.typ   the four properties that earn their keep.
  02-anatomy.typ        frontmatter + body, field by field.
  03-mole-personas.typ  Mole's eight, in compare-pairs.
  04-invocation.typ     explicit / auto-routing / fleet.
  05-authoring.typ      rules of thumb + house structure.
  06-cost-discipline.typ  AGENTS.md §9 distilled.
  07-anti-patterns.typ  four ways to ruin a persona.
  08-claude-parity.typ  opencode ↔ Claude Code portability.
  09-recap.typ          starter persona, recap, thank-you.
Makefile                build wrapper.
```

## Source material

The deck grounds every example in real files in this repo. If you
edit a persona under `.opencode/agent/`, the corresponding example
in `chapters/02-anatomy.typ` or `chapters/03-mole-personas.typ` may
drift. The cost-discipline chapter pulls directly from `AGENTS.md`
§9; if §9 changes, update `chapters/06-cost-discipline.typ`.
