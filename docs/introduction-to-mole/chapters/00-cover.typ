#import "../lib.typ": *

// Cover + agenda (two-column to fit one slide).

#cover-slide(
  "Mole",
  "An I3C / I2C test rig you can build yourself.",
  "Felipe Balbi",
  "2026",
)

#content-slide(
  "Today",
  kicker-text: "Agenda",
)[
  #v(0.4em)
  #two-col[
    #numbered(
      [The problem],
      [Architecture],
      [The ISA],
    )
  ][
    #set enum(start: 4)
    #numbered(
      [`moleasm` in action],
      [Tooling],
      [What ships, what's next],
    )
  ]
]
