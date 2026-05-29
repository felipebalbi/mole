// figures/quarter-bit-timing.typ
//
// Canonical EMIT_BIT waveform with quarter-bit grid, sample point,
// and optional capture / glitch annotations. Parameterized so the
// same figure code serves chapter 3 (intro) and chapter 7 (glitch).
//
// SCL is low for Q0 + Q1, rises at the Q1->Q2 boundary, and stays
// high for Q2 + Q3 (per ROADMAP "Canonical EMIT_BIT shape" and
// fpga/Mole/AGENTS.md "Quarter-bit is the timing unit"). The
// receiver samples on the rising edge; Mole captures in Q2.

#import "../../presentation-template/lib.typ": *
#import "@preview/cetz:0.4.2"

// sda-bits: 4-tuple of values for Q0..Q3, one of "lo"/"hi"/"glitch".
// capture: which quarter (0..3) to highlight as the capture point,
//   or `none` for no highlight.
// title: optional sub-caption rendered below the waveform.
#let quarter-bit-figure(
  sda-bits: ("hi", "hi", "hi", "hi"),
  capture: 2,
  title: none,
) = cetz.canvas({
  import cetz.draw: *

  set-style(
    stroke: (paint: ink, thickness: 1pt),
    content: (padding: 0.15),
  )

  let qw = 2.6   // quarter width
  let baseline-scl = 0
  let baseline-sda = 2.4
  let hi-h = 1.0

  // Helper: y for a symbol within its track.
  let y-of(track-base, sym) = if sym == "hi" {
    track-base + hi-h
  } else if sym == "glitch" {
    // Glitch is a brief dip in an otherwise-high quarter; render as
    // a notch in the middle of the quarter.
    track-base + hi-h
  } else {
    track-base
  }

  // Vertical quarter dividers.
  for i in range(5) {
    line((i * qw, baseline-scl - 0.3), (i * qw, baseline-sda + hi-h + 0.6),
      stroke: (paint: divider-c, dash: "dotted", thickness: 0.5pt))
  }

  // Capture-quarter highlight, drawn BEFORE the waveforms so the SDA
  // line sits on top of the tint instead of being painted over by it.
  // Fill is intentionally very faint so the waveform inside it stays
  // readable.
  if capture != none {
    rect(
      (capture * qw, baseline-sda - 0.3),
      ((capture + 1) * qw, baseline-sda + hi-h + 0.3),
      stroke: 1pt + success,
      fill: success.lighten(96%).transparentize(40%),
      radius: 0.05,
    )
  }

  // Quarter labels along the top.
  for i in range(4) {
    content((i * qw + qw / 2, baseline-sda + hi-h + 0.45),
      text(font: font-sans, size: 10pt, fill: muted, tracking: 1pt)[Q#i])
  }

  // SCL waveform: low Q0+Q1, high Q2+Q3.
  let scl-pts = (
    (0,           baseline-scl),
    (2 * qw,      baseline-scl),
    (2 * qw,      baseline-scl + hi-h),
    (4 * qw,      baseline-scl + hi-h),
  )
  line(..scl-pts, stroke: 1.4pt + accent)
  content((-0.7, baseline-scl + hi-h / 2),
    text(font: font-mono, size: 11pt, fill: accent)[SCL])

  // SDA waveform: one segment per quarter, allowing glitches.
  let sda-segments = ()
  let x = 0
  for sym in sda-bits {
    if sym == "glitch" {
      // High - dip to low for the middle 50% - back to high.
      sda-segments.push((x, baseline-sda + hi-h))
      sda-segments.push((x + qw * 0.25, baseline-sda + hi-h))
      sda-segments.push((x + qw * 0.25, baseline-sda))
      sda-segments.push((x + qw * 0.75, baseline-sda))
      sda-segments.push((x + qw * 0.75, baseline-sda + hi-h))
      sda-segments.push((x + qw,        baseline-sda + hi-h))
    } else {
      let y = y-of(baseline-sda, sym)
      sda-segments.push((x, y))
      sda-segments.push((x + qw, y))
    }
    x = x + qw
  }
  line(..sda-segments, stroke: 1.4pt + secondary)
  content((-0.7, baseline-sda + hi-h / 2),
    text(font: font-mono, size: 11pt, fill: secondary)[SDA])

  // SCL rising-edge marker at Q1->Q2.
  circle((2 * qw, baseline-scl + hi-h),
    radius: 0.12, fill: accent, stroke: none)
  content((2 * qw, baseline-scl - 0.4),
    text(font: font-sans, size: 9pt, fill: muted, style: "italic")[
      rising edge -- receiver samples
    ])

  // Capture-quarter label, drawn after the waveform so it sits on top.
  if capture != none {
    content((capture * qw + qw / 2, baseline-sda + hi-h + 0.85),
      text(font: font-sans, size: 9pt, fill: success, weight: "semibold")[
        Mole captures here
      ])
  }

  // Optional caption.
  if title != none {
    content((4 * qw / 2, baseline-scl - 0.95),
      text(font: font-serif, size: 11pt, style: "italic", fill: muted)[#title])
  }
})
