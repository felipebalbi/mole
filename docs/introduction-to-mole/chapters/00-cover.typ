#import "../lib.typ": *

// Cover + agenda.

#cover-slide(
  "Mole",
  "An I3C / I2C compliance test rig you can build yourself",
  "Felipe Balbi",
  "6mil Labs --- 2026",
)

#slide[
  = Agenda
  + The problem --- and why existing tools hurt.
  + Architecture --- one engine, two layers.
  + The timing model --- quarter-bits and why.
  + The ISA --- fourteen opcodes, sixteen bits each.
  + `moleasm` --- the human-readable face.
  + Worked example 1 --- a clean I2C write.
  + Worked example 2 --- injecting a bit-flip.
  + Results ring --- how the host reads back what happened.
  + Tooling --- CLI, sims, bring-up.
  + Roadmap --- what's done, what's next.
]
