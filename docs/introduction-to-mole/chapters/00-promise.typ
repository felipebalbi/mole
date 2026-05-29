#import "../lib.typ": *

// Opening: cover -> what you'll get -> the hook -> a thought
// experiment that sets up Part 1.

#cover-slide(
  "Mole",
  "An I3C / I2C test rig you can build yourself.",
  "Felipe Balbi",
  "2026",
)

#content-slide(
  "What you'll get out of today",
  kicker-text: "The promise",
)[
  #stack(
    spacing: 1em,
    bullets(
      [A clear mental model of what Mole #emph[is] and #emph[isn't].],
      [Enough of the ISA to read someone else's `moleasm` and follow along.],
      [The confidence to write your first test before lunch tomorrow.],
    ),
    align(center, text(
      font: font-serif, size: 16pt, style: "italic", fill: muted,
    )[No prior FPGA experience required. Bring curiosity.]),
  )
  #note[
    Lead with what the audience gets, not what we built. The whole deck
    is shaped around these three outcomes -- check back at the recap of
    each part and you should be ticking these off.
  ]
]

#stat-slide(
  "1",
  "iCEBreaker",
  caption: [Plus 2 resistors and the DUT. That's the entire BoM for v0.],
)

#provocation-slide(
  [How would you test a \$5 I2C EEPROM on your desk #emph[today]?

  Not just \"does it ACK?\" -- does it survive a setup/hold violation? Does it
  honour clock stretching? Does it NACK an unknown register cleanly?],
  hint: [Be honest about the tools you'd actually reach for.],
)
