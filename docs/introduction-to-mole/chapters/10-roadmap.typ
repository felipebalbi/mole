#import "../lib.typ": *

// Roadmap + Q&A.

#section-slide("Roadmap")

#slide[
  = What ships today
  - *Bit-cycle engine* --- 14 opcodes, controller role, all
    sims green on Verilator + SpinalSim.
  - *moleasm + assembler* --- Rust workspace, full ISA
    coverage, round-trip vectors for every opcode.
  - *mdBook documentation* --- quickstart, opcode reference,
    worked examples, integration patterns.
  - *Mole Verde bring-up* --- icebreaker board, UART control
    channel with RTS/CTS, result-ring drain working.
  - *Result-ring decoder* --- host-side, matches program PC
    to source line.
]

#slide[
  = What's actively in progress
  - *Target role* --- `SAMPLE_BIT_ON_SCL` and
    `DRIVE_BIT_ON_SCL` paced off an external controller's SCL,
    plus DAA arbitration in I3C. Branch `phase5-target-role`.
    Sims still being chased down; the FSM is split into clean
    sub-states but a stale-read race needs more work.
  - *MCXA-as-target fuzz harness* --- two `moleasm` programs
    (compliant + glitchy) plus a fresh Embassy example,
    stress-testing the LPI2C3 target driver.
  - *Mole Rojo* --- ECP5 bring-up; SDRAM ring controller.
]

#slide[
  = What's coming next
  - *Scheme SDK (Layer 1)* --- compile spec-correct I2C / I3C
    transactions from S-expressions.
  - *Verde-DAA addon* --- piggyback PCB for I3C DAA capture
    on Verde-class hardware.
  - *Mole Negro* --- CertusPro-NX tier, certification-grade.
  - *Phase-0 release* --- once the wire format locks, bytecode
    becomes a stable contract.
]

#slide[
  = Thank you
  #set align(center + horizon)
  #text(size: 42pt, weight: "bold", fill: accent)[Questions?]
  #v(1em)
  #text(size: 18pt, fill: muted)[github.com/6mil-Labs/mole]
]
