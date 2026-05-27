#import "../lib.typ": *

// Part 1: The problem. Frame compliance vs conformance, walk through
// the three existing options, end with what we wish existed.

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
      Both matter. Both are missing on most desks.
    ]
  ]
  #note[
    Compliance is the formal one -- pass/fail against a test suite.
    Conformance is squishier: does my part #emph[get along] with the rest of
    the system, even when neither side is strictly wrong? Mole was built to
    answer both with the same tooling.
  ]
]

#content-slide(
  "Your three options today",
  kicker-text: "Each one falls short",
)[
  #bullets(
    [*Sealed compliance rig* --- gold standard, behind a paywall.],
    [*Logic analyzer* --- observes the bus, never injects.],
    [*MCU bit-banger* --- too slow, too flaky, no quarter-bit timing.],
  )
  #v(0.6em)
  #align(center)[
    #text(font: font-serif, size: 18pt, style: "italic", fill: muted)[
      None of them scale to every developer's desk.
    ]
  ]
  #note[
    The point is not that any one of these tools is bad. The point is
    that none of them is the kind of tool you keep next to your laptop
    and reach for #emph[every day], the way a developer reaches for a
    debugger.
  ]
]

#compare-slide(
  "What we wish we had",
  kicker-text: "The shopping list",
  "Observe", [
    #bullets(
      [Captures every quarter-bit, lossless.],
      [Replays the exact wire pattern next run.],
      [Decodes back to source-line breadcrumbs.],
    )
  ],
  "Inject", [
    #bullets(
      [Drives any bit, any quarter, any voltage class.],
      [Deterministic error injection -- seed-reproducible.],
      [Plays target #emph[or] controller, slaved to the DUT's clock.],
    )
  ],
  verdict: [One tool that does all six. That's the gap Mole fills.],
)

#recap-slide(
  "What Part 1 leaves you with",
  (
    [The compliance / conformance split, and why both deserve tooling.],
    [Why the three off-the-shelf options each miss part of the desk.],
    [A six-item wishlist that the rest of the deck answers point by point.],
  ),
  next: [how the architecture splits the work between host and FPGA.],
)
