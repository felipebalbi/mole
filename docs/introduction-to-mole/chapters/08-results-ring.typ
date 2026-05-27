#import "../lib.typ": *

// Result ring + post-processing.

#section-slide("08", "Results ring")

#content-slide(
  "Four record types",
  kicker-text: "What the engine writes",
)[
  #v(0.4em)
  #align(center)[
    #pill("REVISION") #h(0.6em)
    #pill("CAPTURE") #h(0.6em)
    #pill("MARK") #h(0.6em)
    #pill("HALT")
  ]
  #v(0.8em)
  #bullets(
    [Bounded ring in FPGA RAM.],
    [Host drains it over UART after `HALT`.],
    [Wrap is detectable. History is honest.],
  )
]

#content-slide(
  "Why a ring, not a stream?",
  kicker-text: "Back-pressure goes the wrong way",
)[
  #v(0.4em)
  #two-col[
    #align(center)[
      #text(font: font-serif, size: 44pt, weight: "bold", fill: accent)[24 MHz]
      #v(-0.3em)
      #text(size: 12pt, fill: muted, tracking: 2pt)[#upper("Fabric")]
    ]
  ][
    #align(center)[
      #text(font: font-serif, size: 44pt, weight: "bold", fill: secondary)[1 Mbaud]
      #v(-0.3em)
      #text(size: 12pt, fill: muted, tracking: 2pt)[#upper("UART out")]
    ]
  ]
  #v(0.8em)
  #align(center)[
    #text(font: font-serif, size: 18pt, style: "italic", fill: muted)[
      Capture at fabric speed. Drain at human speed.
    ]
  ]
]

#content-slide(
  "Decoded on the host",
  kicker-text: "The host already has the program",
)[
  #code-panel(size: 14pt)[
```
line 14  capture #0  ACK   obs=0  exp=0   OK
line 23  capture #1  ACK   obs=0  exp=0   OK
line 32  capture #2  ACK   obs=1  exp=0   MISMATCH
```
  ]
  #v(0.4em)
  #align(center)[
    #text(font: font-serif, size: 16pt, style: "italic", fill: muted)[
      Mismatches highlighted. Flags decoded. Source lines linked.
    ]
  ]
]
