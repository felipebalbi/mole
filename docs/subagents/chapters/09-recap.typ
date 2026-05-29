#import "../../presentation-template/lib.typ": *

// 09-recap.typ -- starter persona, final recap, thank-you.

#section-slide("9", "Take this home")

#code-slide("Starter persona — frontmatter")[
```markdown
---
description: Use when <name the artifact and the inputs>.
  Produces <name the deliverable>; does not <name the boundary>.
  Trigger for "<phrase 1>", "<phrase 2>", "<phrase 3>".
mode: subagent
permission:
  edit: deny       # or allow, if this persona writes code
  bash: ask        # safe default
  webfetch: allow
---
```
  ]

#code-slide("Starter persona — body")[
```markdown
# <Persona name>
You are the <Name>: <one-sentence role>. Goal: <one-sentence outcome>.

## Stance
- <One line of voice and posture.>
## What you do
- <Bullet per responsibility; see the anatomy chapter.>
## How you work
- <Workflow steps the persona follows.>
## What you do NOT do
- <Explicit non-goals; the sharp edge.>
## Output format
- <Return shape the caller can rely on.>
```
  ]

#stat-slide(
  "8",
  "Personas ship in Mole today",
  caption: [Yours is next.],
)

#recap-slide(
  "What to remember",
  (
    [Subagents = fresh context + scoped tools + model
     routing + parallelism.],
    [The description is the persona; the body enforces it.],
    [Cheap by default; premium on a named trigger.],
    [No kitchen sinks, no duplicates, no secrets in prompts.],
  ),
  deeper: [`.opencode/agent/` in this repo, and
  `docs/presentation-template/` for your own decks.],
)

#thank-you-slide(
  "github.com/<your-org>/mole",
  book: "docs/introduction-to-mole/",
  roadmap: "ROADMAP.md",
  contributing: "AGENTS.md",
  headline: "Go write one.",
  tagline: [Single purpose, sharp edge, clear return shape.],
)
