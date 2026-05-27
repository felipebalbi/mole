#import "../lib.typ": *

// moleasm syntax.

#section-slide("moleasm")

#slide[
  = What `moleasm` is
  The human-readable face of Layer-0 bytecode. One line per
  instruction. Labels are first-class. Comments are `//`.

  Example --- the canonical "drive a 1 bit and capture":

  ```
  // Drive a recessive bit, capture whatever the bus does.
  emit_bit tx=recessive capture=1
  ```

  Compiles to a single 16-bit instruction.
]

#slide[
  = Line shape
  ```
  label:  mnemonic  field=value  field=value  ...
  ```

  Field rules:
  - `tx=dominant|recessive|hiz` on bit-drive opcodes.
    Shortened: `tx=dom` / `tx=rec`.
  - `sda=...` and `scl=...` on `EMIT_QUARTER`.
  - `expect=0|1|X`, `mask=0|1`, `capture=0|1` on bearer
    opcodes. `expect=X` (don't-care), `mask=0`, `capture=0`
    are the defaults and are omitted when unset.
  - Branch / wait targets are *labels*, not raw offsets.
    The assembler computes the offset for you.
]

#slide[
  = Worked snippet --- expected vs observed
  ```
  // Send the START bit; capture whether bus actually starts.
  start: emit_quarter sda=dom scl=hiz expect=0 capture=1
         emit_quarter sda=dom scl=dom

  // Now address byte. ACK bit expects dominant from target.
  emit_bit tx=dom            // bit 6
  emit_bit tx=dom            // bit 5
  // ... etc ...
  emit_bit tx=hiz expect=0 capture=1   // ACK slot
  branch_on mismatch nack_path
  ```

  `expect` says what the SDK *thinks* will happen. `capture`
  says "and push the answer into the result ring so the host
  can confirm".
]

#slide[
  = Directives
  ```
  .bus_mode i2c          // sets BUS_MODE for the rest of file
  .timing div=125        // SDR-12.5 MHz / 50 MHz fabric
  .seed 0xdead_beef      // PRNG seed for any glitch macros
  .glitch_ratio 0        // exactly zero == byte-identical
  ```

  Top-of-file directives compile to the equivalent
  `SET_BUS_MODE` / `LOAD_TIMING` opcodes plus encoder state.
  `seed` and `glitch_ratio` are *encoder*-only --- they never
  appear on the wire.
]
