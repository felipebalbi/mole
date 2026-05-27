// Placeholder for an architecture diagram.
//
// Drawn entirely in Typst so the figure ships in-tree without
// binary assets.

#let architecture-diagram = box[
  // TODO: actual diagram.  Sketch:
  //
  //   +---------------------------+         +---------------+
  //   |  Host (Rust / Scheme)     |         |  DUT          |
  //   |                           |         |               |
  //   |  moleasm -> bytecode -+-->| UART/USB|               |
  //   |                       |   |    +    |   SDA / SCL   |
  //   |  result decoder  <----+---|<---+----+               |
  //   +---------------------------+    |    +---------------+
  //                                    v
  //                              +-----------+
  //                              | Mole FPGA |
  //                              | bit eng.  |
  //                              +-----------+
  text(fill: muted, style: "italic")[architecture diagram --- TODO]
]
