#import "../../presentation-template/lib.typ": *

// 03-mole-personas.typ -- the eight personas Mole ships, in pairs
// that show separation-of-concerns.

#section-slide("3", "Mole's eight")

#content-slide("What lives in `.opencode/agent/`")[
  #three-col(
    bullets(
      [*architect* — design],
      [*coder* — implementation],
      [*reviewer* — critique],
    ),
    bullets(
      [*tester* — adversarial QA],
      [*reliability* — failure modes],
      [*docs* — explanation],
    ),
    bullets(
      [*integrator* — ship it],
      [*coordinator* — orchestration],
    ),
  )
  #note[
    Eight personas, each single-purpose. None of them overlap
    enough that the router has to guess.
  ]
]

#compare-slide(
  "Design vs implementation",
  "architect",
  bullets(
    [`permission.edit: deny`],
    [Produces *specifications*, not patches.],
    [Premium model: real reasoning earns the cost.],
    [Output is a doc you hand to the coder.],
  ),
  "coder",
  bullets(
    [`permission.edit: allow`],
    [Translates a spec into a patch.],
    [Cheap model: forward motion, not design.],
    [Refuses to re-architect mid-flight.],
  ),
  verdict: "Two roles, two permission grants, two model tiers.",
)

#compare-slide(
  "Build vs break",
  "reviewer",
  bullets(
    [Reads diffs adversarially.],
    [`edit: deny` — reads, reasons, reports.],
    [Severities: blocker / major / minor / nit.],
    [Output cites `file:line`, not vibes.],
  ),
  "tester",
  bullets(
    [Hunts edge cases, fuzzes, breaks invariants.],
    [Produces reproducible failure cases.],
    [Asks "what if the input is..." for a living.],
    [Hands a repro to the coder.],
  ),
  verdict: "Critique and abuse are different jobs.",
)

#compare-slide(
  "Plan vs execute",
  "coordinator",
  bullets(
    [Decomposes fuzzy requests.],
    [Routes units to specialists.],
    [Never does specialist work itself.],
    [The smallest moving part in the loop.],
  ),
  "the rest",
  bullets(
    [Do one thing.],
    [Have one return shape.],
    [Stay in scope.],
    [Hand back to the coordinator.],
  ),
  verdict: "The coordinator owns the schedule, not the craft.",
)

#recap-slide(
  "Recap",
  (
    [Eight personas, each single-purpose.],
    [Pairs separate concerns the router can distinguish.],
    [Permissions and model tiers encode the role, not just
     the prose.],
  ),
  next: "How to actually invoke them.",
)
