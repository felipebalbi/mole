#import "../../presentation-template/lib.typ": *

// 02-anatomy.typ -- field-by-field walk through a real persona.

#section-slide("2", "Anatomy")

#content-slide("Where they live")[
  #bullets(
    [Project-scoped: `.opencode/agent/<name>.md`],
    [User-scoped:   `~/.config/opencode/agent/<name>.md`],
    [Markdown body, with YAML frontmatter at the top.],
    [opencode discovers them automatically; no registration step.],
  )
]

#code-slide("A real persona, top to bottom")[```markdown
---
description: Use when code or a design needs an independent,
  adversarial correctness review. Trigger for "review",
  "audit", "sanity check", "before I merge".
mode: subagent
permission:
  edit: deny
  bash: ask
  webfetch: allow
---

# Reviewer
You are the Reviewer: independent, deep-thinking verifier...
```
  ]

#content-slide("The frontmatter, field by field")[
  #bullets(
    [`description` --- the *router prompt*. The main agent reads
     this to decide whether to delegate. Include trigger
     phrases.],
    [`mode: subagent` --- this file is not a top-level agent.],
    [`permission.edit` --- `allow` / `ask` / `deny`. The Reviewer
     gets `deny` because it must not silently fix what it
     critiques.],
    [`permission.bash` --- same vocabulary. `ask` means the
     human gates every shell call.],
    [`permission.webfetch` --- read-only access to the open web.],
  )
  #note[
    The description is the single most important field.
    Routers don't read the body. If your description is
    vague, the persona is invisible.
  ]
]

#content-slide("The body is a system prompt")[
  #bullets(
    [No template required --- it's just Markdown.],
    [Mole's house structure: *Stance · What you do · How you work
     · What you do NOT do · Output format*.],
    [The "do NOT" list is load-bearing: it's what stops a
     reviewer from quietly rewriting your code.],
    [Output format pins the return shape so the calling agent
     can act on it without re-parsing prose.],
  )
]

#recap-slide(
  "Recap",
  (
    [Frontmatter wires routing and permissions.],
    [Body is a system prompt with a stance and a return shape.],
    [The "do NOT" list keeps the persona in its lane.],
  ),
  next: "What personas Mole actually ships.",
)
