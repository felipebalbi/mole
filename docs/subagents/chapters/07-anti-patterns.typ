#import "../../presentation-template/lib.typ": *

// 07-anti-patterns.typ -- four ways to ruin a persona.

#section-slide("7", "Anti-patterns")

#content-slide("The kitchen-sink persona")[
  #callout(kind: "danger", icon: "✗")[
    `description: Use for any task involving code, design,
    review, tests, docs, or release.`
  ]
  #v(0.8em)
  #bullets(
    [The router can't distinguish it from the main agent.],
    [Either gets called for everything or for nothing.],
    [Cure: pick *one* of those nouns and write a real
     description.],
  )
]

#content-slide("The duplicate")[
  #callout(kind: "warn", icon: "!")[
    A `senior-coder` persona that does what the main agent
    already does, only with more adjectives in its body.
  ]
  #v(0.8em)
  #bullets(
    [A persona exists to be *different* — different stance,
     different permissions, different model, different
     return shape.],
    [If the only difference is the body prose, you don't have
     a persona; you have a longer prompt.],
  )
]

#content-slide("No escalation triggers")[
  #callout(kind: "warn", icon: "!")[
    A persona pinned to a premium model "to be safe", with no
    description of when *not* to use it.
  ]
  #v(0.8em)
  #bullets(
    [Routes to itself for trivial work; burns credits silently.],
    [Cure: state in the description what kinds of work the
     persona is *wrong* for. Routers respect negatives too.],
  )
]

#content-slide("Secrets in the prompt")[
  #callout(kind: "danger", icon: "✗")[
    `permission: bash: allow` plus an API key embedded in the
    body so "the agent can just deploy".
  ]
  #v(0.8em)
  #bullets(
    [Personas live in git. The body ships to model providers
     on every call.],
    [Secrets belong in environment variables, fetched at the
     boundary; never in the persona file.],
    [If a persona needs credentials, it needs a human in the
     loop (`bash: ask`), not a hardcoded token.],
  )
]

#recap-slide(
  "Recap",
  (
    [No kitchen sinks. No duplicates of the main agent.],
    [State the negatives — what the persona is wrong for.],
    [Never put a secret in a file that ships to a model.],
  ),
  next: "Portability across coding harnesses.",
)
