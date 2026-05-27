#import "../lib.typ": *

// Architecture.

#section-slide("Architecture")

#slide[
  = Two layers, one contract
  *Layer 0 --- the bit-cycle engine.* Tiny FPGA core. Knows nothing
  about I2C, I3C, CCCs, addresses, parity, or HDR. Executes a
  16-bit fixed-width ISA over quarter-bit drive symbols on SDA
  and SCL.

  *Layer 1 --- the host compiler.* Rust today, Scheme eventually.
  Knows every spec in painful detail. Compiles human-readable
  `moleasm` (and later, S-expressions) down to the Layer-0
  bytecode.

  The wire format between them is a *stable contract* once
  Phase-0 ships. Bytecode versions are not free.
]

#slide[
  = Why bus-agnostic?
  The engine's drive vocabulary is exactly four symbols: \
  `dominant` \/ `recessive` \/ `hiz` \/ `reserved`.

  *No* `i2c_low`. *No* `i3c_push_pull_high`. *No* "drive SCL".
  Just symbols.

  A single register --- `BUS_MODE` --- maps those symbols to the
  electrical reality of the active bus class (`i2c`, `i3c-OD`,
  `i3c-PP`, `hdr-ddr`). Changing class is *one opcode*.

  Adding SMBus, PMBus, LIN, 1-Wire, or CAN to a future Mole is
  a `BUS_MODE` table entry plus an SDK macro layer. *Zero* ISA
  churn.
]

#slide[
  = Compile-time error injection
  Mole has *zero* runtime randomness.

  Glitches, bit-flips, premature stops, malformed CCCs --- all
  decided *at compile time* on the host. The PRNG is in the
  host. The seed and ratio are inputs to the compiler. The
  output is a deterministic, fully-baked program.

  Consequences:
  - `ratio = 0` produces a byte-identical bytecode to the
    no-injection build.
  - Every bug is replayable. The same seed reproduces the
    same wire pattern, the same fault, the same response.
  - The FPGA stays small. No on-chip RNG, no on-chip
    decision logic.
]

#slide[
  = Two tiers from one design
  Mole Verde *and* Mole Rojo run the same RTL. The difference is
  the FPGA budget and the I/O front-end.

  #table(
    columns: (1fr, 1.5fr, 1.5fr),
    align: (left, left, left),
    stroke: 0.5pt + muted,
    [], [*Mole Verde*], [*Mole Rojo*],
    [FPGA],     [iCE40 UP5K (\$5)],   [ECP5-45K (\$25)],
    [Buffer],   [SPRAM, ~256 KB],     [SDRAM, megabytes],
    [Channels], [1],                   [2 + sync],
    [Target],   [developer's desk],   [bench, compliance],
    [Eventual], [Verde-DAA addon],    [Rojo-CertusPro NX],
  )
]
