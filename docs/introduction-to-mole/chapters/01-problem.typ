#import "../lib.typ": *

// The problem.

#section-slide("The problem")

#slide[
  = Compliance vs conformance

  *Compliance* --- does this part obey the MIPI I3C / I2C spec?
  Every edge, every timing window, every CCC reply, every
  arbitration outcome. Pass/fail.

  *Conformance* --- does this part obey the spec *in the way the
  rest of the ecosystem expects*? The grey zone of "technically
  legal but nobody does that".

  Both matter. Both are missing from the toolbox of most teams
  shipping I3C silicon today.
]

#slide[
  = What the market offers

  - *MCCI Model 2710 SuperMITT* --- the reference compliance rig.
    Excellent. Sealed. Expensive. One per company, locked in the
    lab of whoever bought it first.

  - *Logic analyzer + decoder plug-in* --- great at observing.
    Cannot *inject*. Cannot deliberately violate the spec to see
    how the DUT behaves.

  - *Roll-your-own MCU bit-banger* --- cheap. Cannot hit I3C
    timing. Cannot inject quarter-bit glitches. Cannot replay a
    bug deterministically a thousand times.

  None of these scale to "every developer's desk".
]

#slide[
  = The Mole bet

  A *bit-cycle engine* small enough to fit a USD\$5 iCE40 UP5K,
  *flexible* enough to drive I3C SDR + HDR-DDR at up to 6 MHz, and
  *deterministic* enough that a host can pre-compile every glitch,
  every retry, every malformed CCC --- and replay them byte-identical,
  run after run.
]
