#import "../../presentation-template/lib.typ": *

// 06-cost-discipline.typ -- AGENTS.md §9, distilled.

#section-slide("6", "Cost discipline")

#quote-slide(
  [Premium models cost an order of magnitude more than standard
  models. Most steps in a typical task do not need premium
  reasoning, and over-using premium models wastes credits
  without improving outcomes.],
  by: "AGENTS.md §9",
)

#content-slide("Default posture")[
  #bullets(
    [Default to the cheapest model that can do the job.],
    [Plan with premium, execute with cheap.],
    [Never bump "just in case" — articulate why the cheaper
     model would fail.],
  )
]

#compare-slide(
  "When to reach for premium / when not",
  "escalate (premium)",
  bullets(
    [Cross-module refactor or new API.],
    [Concurrency, `unsafe`, FFI ABI.],
    [Survived one cheap attempt.],
    [Safety- or money-critical review.],
    [Diff cannot be predicted.],
  ),
  "de-escalate (cheap)",
  bullets(
    [Searching, reading, summarizing.],
    [Mechanical edits, format, lint.],
    [Tests for code that already works.],
    [Builds; pass/fail reporting.],
    [Diff is predictable.],
  ),
  verdict: [Wrong answer caught in seconds (tests, review) —
  or weeks (production incident)?],
)

#code-slide("Sub-agent routing table (AGENTS.md §9)")[
```text
sub-agent type     default    override to
-----------------  ---------  ----------------------------------
explore            cheap      keep cheap
task (run cmd)     cheap      keep cheap
research           cheap      premium only for final synthesis
general-purpose    match task cheap for mechanical, premium for design
rubber-duck        premium    keep premium -- reasoning pays off
code-review        premium    cheap on cosmetic / mechanical diffs
```
  ]

#content-slide("Fleet mode multiplies the cost")[
  #bullets(
    [Cost = per-worker cost × fleet width. Apply the rules
     above *per worker*, not in aggregate.],
    [Split a fleet along complexity: cheap workers for file
     edits and test runs; premium reserved for the few that
     need real reasoning.],
    [If every worker needs premium, the decomposition is
     probably wrong. Reconsider before paying N× premium.],
  )
]

#recap-slide(
  "Recap",
  (
    [Cheap by default; premium only on a named escalation
     trigger.],
    [Set `model:` explicitly in `task` calls; don't inherit.],
    [Fleet width is a cost multiplier — route each worker
     independently.],
  ),
  next: "Anti-patterns and how to spot them.",
)
