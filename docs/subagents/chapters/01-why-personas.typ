#import "../../presentation-template/lib.typ": *

// 01-why-personas.typ -- the four properties that make subagents
// load-bearing, not cosmetic.

#section-slide("1", "Why personas")

#provocation-slide(
  [Your main agent is *one* generalist with the whole conversation
  in its head. Every turn re-reads everything. When you ask it to
  "review the diff", it's still carrying the architecture argument
  from twenty turns ago. \ \
  What if you could spin up a fresh, scoped, opinionated specialist
  for that one task and throw it away when it's done?],
  kicker-text: "Food for thought",
)

#content-slide("Four properties that earn their keep")[
  #numbered(
    [*Fresh context.* The subagent starts cold. None of your
     accumulated conversation pollutes its reasoning.],
    [*Scoped tools.* You can hand a reviewer read-only access and
     a coder write access. The persona enforces it.],
    [*Model routing.* Cheap specialists for mechanical work,
     premium specialists for hard reasoning (AGENTS.md §9).],
    [*Parallelism.* Independent work runs concurrently; one
     dispatch, four returns.],
  )
  #note[
    The fourth one is the one most teams under-use. Map work,
    fan it out, collect results.
  ]
]

#recap-slide(
  "Recap",
  (
    [Subagents are not "prompts with a hat on".],
    [Fresh context + scoped tools + cheap routing + parallelism.],
    [Each one is a tool with a sharp edge for one job.],
  ),
  next: "Anatomy of a persona file.",
)
