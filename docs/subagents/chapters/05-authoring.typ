#import "../../presentation-template/lib.typ": *

// 05-authoring.typ -- writing a persona the router will reach for.

#section-slide("5", "Authoring")

#try-it-slide(
  [Your team needs a new persona that drafts release notes from a
  range of commits. What does the *description* field say so the
  router picks it instead of `docs` or `integrator`?],
  hint: [Name the artifact, name the inputs, list trigger
  phrases the user would actually say.],
)

#content-slide("One plausible answer")[
  #code-panel[
```yaml
description: Use when a tagged or about-to-tag release needs a
  human-readable changelog or release-note draft assembled from
  a commit range, PR titles, and issue links. Produces Markdown
  ready for GitHub releases; does not tag, push, or publish.
  Trigger for "release notes", "changelog", "what shipped",
  "draft the v0.X notes", "summarize commits since <ref>".
```
  ]
  #note[
    Three patterns: (1) name the deliverable, (2) name the
    boundary ("does not tag"), (3) front-load the trigger
    phrases the user would naturally use.
  ]
]

#content-slide("Five rules of thumb")[
  #numbered(
    [*Single purpose.* A persona that does two things does
     neither well, and the router can't pick it confidently.],
    [*Description = router prompt.* Optimize it for matching,
     not for the human reader of the file.],
    [*Restrict tools.* `edit: deny` for anyone who critiques.
     `bash: ask` is a safe default for anyone non-trivial.],
    [*Pin a model only with a reason.* The default is the cheap
     model; bumping it should cite the escalation trigger.],
    [*Pin a return shape.* A persona without an output format
     leaks prose; the caller can't act on it programmatically.],
  )
]

#content-slide("Writing the body: Mole's house structure")[
  #bullets(
    [*Stance* — three or four adjectives. Sets the voice.],
    [*What you do* — bulleted. Concrete deliverables.],
    [*How you work* — process notes, references to `AGENTS.md`
     or `ROADMAP.md`.],
    [*What you do NOT do* — load-bearing. Keeps the persona in
     its lane under pressure.],
    [*Output format* — numbered sections the caller can parse.],
  )
]

#recap-slide(
  "Recap",
  (
    [The description is the persona; bodies enforce the
     persona.],
    [Single-purpose, scoped tools, pinned return shape.],
    [The "do NOT" section is what stops scope creep.],
  ),
  next: "Spending credits like an adult.",
)
