#import "../../presentation-template/lib.typ": *

// 04-invocation.typ -- the three ways a subagent gets called.

#section-slide("4", "Invocation")

#content-slide("Three ways to dispatch")[
  #numbered(
    [*Explicit.* The main agent calls the `task` tool with a
     specific `subagent_type` and a prompt.],
    [*Auto-routing.* The main agent reads each persona's
     `description` and picks the best match for the current
     turn.],
    [*Fleet.* `/fleet` launches N workers in parallel, each
     potentially a different persona.],
  )
]

#code-slide("Explicit dispatch")[
```text
> Have the reviewer audit the diff in mole-asm/src/encoder.rs
> against ROADMAP §3.10. Report back as a critique with severities.

[main agent invokes task tool with subagent_type=reviewer]
[reviewer reads the diff with a fresh context, no chat history]
[reviewer returns: 1 blocker, 2 major, 1 minor, cited file:line]
[main agent presents the critique to the user]
```
  ]

#content-slide("When to use which")[
  #bullets(
    [*Explicit* when you know exactly which persona fits.
     Cheapest cognitive cost for the main agent.],
    [*Auto-routing* when you're describing the work in your own
     words and want the router to pick. Relies on good
     descriptions.],
    [*Fleet* when the work decomposes into independent units.
     Pay N times, get N times the wall-clock throughput — only
     worth it when the units genuinely don't depend on each
     other.],
  )
  #note[
    Fleet's failure mode is wasted spend. If every worker
    needs a premium model, reconsider whether fleet is the
    right shape.
  ]
]

#recap-slide(
  "Recap",
  (
    [Explicit, auto-routing, fleet — three escalating shapes.],
    [The router only reads `description`, never the body.],
    [Fleet multiplies cost by width; decompose deliberately.],
  ),
  next: "Authoring a persona that earns its keep.",
)
