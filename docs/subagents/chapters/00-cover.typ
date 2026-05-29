#import "../../presentation-template/lib.typ": *

// 00-cover.typ -- cover + opening promise.

#cover-slide(
  "Subagents",
  "Personas for your repo.",
  "Felipe Balbi",
  "2026",
)

#section-slide("0", "What you'll leave with")

#content-slide("The promise")[
  #bullets(
    [Why a subagent is more than "a longer prompt".],
    [How Mole's personas are wired into `.opencode/agent/`.],
    [How to write one that the router will actually reach for.],
    [How to spend AI credits without lighting them on fire.],
  )
  #note[
    Frame this as 30 minutes, then go. The deck ends with a
    starter persona file you can copy on Monday.
  ]
]
