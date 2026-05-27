#import "../lib.typ": *

// Roadmap + thank-you.

#section-slide("10", "What's next")

#content-slide(
  "Today",
  kicker-text: "Shipping right now",
)[
  #bullets(
    [Bit-cycle engine --- controller role, sims green],
    [`moleasm` assembler --- full ISA coverage],
    [Result-ring decoder --- PC ↔ source line],
    [Bring-up --- UART control, RTS/CTS],
    [mdBook docs --- quickstart, opcode reference],
  )
]

#content-slide(
  "In flight",
  kicker-text: "Active branches",
)[
  #bullets(
    [Target role --- sample / drive paced by external SCL],
    [DAA arbitration in I3C],
    [Target-as-DUT fuzz harness --- Embassy + LPI2C3],
  )
]

#content-slide(
  "Next",
  kicker-text: "On the horizon",
)[
  #bullets(
    [Scheme SDK --- spec-correct I2C / I3C from S-expressions],
    [Wire-format lock --- bytecode becomes a stable contract],
    [Compliance test suites against MIPI vectors],
  )
]

#thank-you-slide("github.com/6mil-Labs/mole")
