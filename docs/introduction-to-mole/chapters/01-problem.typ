#import "../lib.typ": *

// The problem.

#section-slide("01", "The problem")

#content-slide(
  "Two questions, one bus",
  kicker-text: "Compliance vs conformance",
)[
  #v(0.3em)
  #two-col[
    #tag("Compliance", color: accent)
    #v(0.3em)
    #text(size: 20pt, fill: ink-soft)[Does it obey the spec?]
    #v(0.2em)
    #text(size: 15pt, fill: muted, style: "italic")[Every edge. Every window.]
  ][
    #tag("Conformance", color: secondary)
    #v(0.3em)
    #text(size: 20pt, fill: ink-soft)[Does it play nicely?]
    #v(0.2em)
    #text(size: 15pt, fill: muted, style: "italic")[The grey zone nobody tests.]
  ]
  #v(1em)
  #align(center)[
    #text(font: font-serif, size: 22pt, style: "italic", fill: ink)[
      Both matter. Both are missing.
    ]
  ]
]

#content-slide("Your three options today")[
  #bullets(
    [*Sealed compliance rig* --- gold standard, behind a paywall.],
    [*Logic analyzer* --- observes, never injects.],
    [*MCU bit-banger* --- too slow, too flaky.],
  )
  #v(0.6em)
  #align(center)[
    #text(font: font-serif, size: 18pt, style: "italic", fill: muted)[
      None scale to every developer's desk.
    ]
  ]
]

#stat-slide(
  "2",
  "resistors",
  caption: [Plus one icebreaker and the DUT. That's the entire BoM for v0.],
)
