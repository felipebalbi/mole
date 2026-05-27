#import "../lib.typ": *

// Worked example 2: glitch injection.

#section-slide("Worked example: glitch injection")

#slide[
  = Goal
  Take the same TMP108 write and inject a *single-quarter
  glitch* on SDA, mid-byte. See if the target NACKs, hangs,
  or shrugs it off and ACKs anyway.

  This is the kind of test you cannot do with a logic
  analyzer. You cannot do it reliably with an MCU bit-banger.
  Mole does it byte-deterministically.
]

#slide[
  = The injection point
  Same address byte as before --- but on bit 4 (the `1` in
  `1001 0000`), force SDA *low* for a single quarter mid-bit
  while SCL is high.

  Spec-compliant target should NACK because SDA changed
  during SCL high (which is the START/STOP signature, not
  data).

  ```
  // Bit 4 of 0x48 << 1 -- normally tx=rec.
  // Inject: drive quarter-by-quarter to violate setup/hold.
  emit_quarter sda=rec scl=dom        // Q0: load new SDA, SCL low
  emit_quarter sda=rec scl=hiz        // Q1: SCL rises
  emit_quarter sda=dom scl=hiz        // Q2: GLITCH -- SDA drops
  emit_quarter sda=rec scl=dom        // Q3: SCL falls
  ```
]

#slide[
  = Capturing the response
  After the corrupted bit, the program continues as before
  through to the ACK slot. The capture says it all:

  ```
  emit_bit tx=hiz expect=0 capture=1
  ```

  Three possible outcomes, all interesting:

  + `capture = 0` --- target ACKed despite the glitch. *Bug*:
    the target is not enforcing setup/hold.
  + `capture = 1` --- target NACKed. *Spec-correct*.
  + Engine reports a `START_FLAG` set unexpectedly --- some
    targets interpret the glitch as a *new* START.
]

#slide[
  = Determinism is the point
  Run the program a thousand times. Same seed (we used none).
  Same bytecode. Same quarter-bit pattern on the wire.

  If the target *sometimes* ACKs and *sometimes* NACKs, that's
  not a Mole flakiness --- that's *the target* being flaky, and
  you've just caught it.

  Compare with a logic-analyzer + MCU bit-banger setup, where
  every run is slightly different and you'll spend a week
  arguing about whether the bug is in your test rig.
]
