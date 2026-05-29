#import "../../presentation-template/lib.typ": *

// Part 1: The problem. Frame compliance vs conformance, walk through
// the three existing options, end with the six-item wishlist that
// the rest of the deck will tick off, point by point.

#section-slide("01", "The problem")

#content-slide(
  "Two questions, one bus",
  kicker-text: "Compliance vs conformance",
)[
  #stack(
    spacing: 1.2em,
    two-col(
      stack(
        spacing: 0.4em,
        tag("Compliance", color: accent),
        text(size: 20pt, fill: ink-soft)[Does it obey the spec?],
        text(
          size: 15pt, fill: muted, style: "italic",
        )[Every edge. Every window.],
      ),
      stack(
        spacing: 0.4em,
        tag("Conformance", color: secondary),
        text(size: 20pt, fill: ink-soft)[Does it play nicely?],
        text(
          size: 15pt, fill: muted, style: "italic",
        )[The grey zone nobody tests.],
      ),
    ),
    align(center, text(
      font: font-serif, size: 22pt, style: "italic", fill: ink,
    )[Both matter. Both are missing on most desks.]),
  )
  #note[
    Compliance is the formal one -- pass/fail against a test suite.
    Conformance is squishier: does my part #emph[get along] with the
    rest of the system, even when neither side is strictly wrong?
    Mole was built to answer both with the same tooling.
  ]
]

#content-slide(
  "The bug you can't reproduce",
  kicker-text: "Why this is hard today",
)[
  #stack(
    spacing: 1em,
    bullets(
      [Target NACKs once every ~10k transactions. No pattern.],
      [Logic analyser shows the same waveform every time it does fire.],
      [The MCU bit-banger you built can't slow down to repro it.],
      [Sealed compliance rig sits in a lab three time zones away.],
    ),
    align(center, text(
      font: font-serif, size: 18pt, style: "italic", fill: muted,
    )[You need to drive the wire on purpose, not watch it after the fact.]),
  )
  #note[
    This is the concrete pain. Every bullet maps to something the
    audience has actually lived through. Hold here -- the wishlist
    two slides on is the answer.
  ]
]

#content-slide(
  "Your three options today",
  kicker-text: "Each one falls short",
)[
  #stack(
    spacing: 1em,
    bullets(
      [*Sealed compliance rig* --- gold standard, behind a paywall.],
      [*Logic analyzer* --- observes the bus, never injects.],
      [*MCU bit-banger* --- too slow, too flaky, no quarter-bit timing.],
    ),
    align(center, text(
      font: font-serif, size: 18pt, style: "italic", fill: muted,
    )[None of them scale to every developer's desk.]),
  )
  #note[
    The point is not that any one of these tools is bad. The point is
    that none of them is the kind of tool you keep next to your
    laptop and reach for #emph[every day], the way a developer
    reaches for a debugger.
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
    [Compliance + conformance both matter; both are under-tooled.],
    [The three off-the-shelf options each miss part of the desk.],
    [A six-item wishlist that the rest of the deck answers point by point.],
  ),
  next: [how the architecture splits the work between host and FPGA.],
  deeper: [`book/src/introduction.md` -- why this exists at all.],
)
