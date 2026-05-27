#import "../lib.typ": *

// Result ring + post-processing.

#section-slide("Results ring")

#slide[
  = What the engine writes
  A bounded ring buffer in FPGA RAM. The host reads it back
  over UART (Verde) or USB (Rojo) after `HALT`.

  Record types:
  - `REVISION` --- bytecode format version + engine git hash.
    Written once, at program start.
  - `CAPTURE` --- the bit (or quarter) observed at a `capture=1`
    point, plus any sticky flags that fired since the last
    record.
  - `MARK` --- a host-provided 16-bit marker that the program
    wrote via the `MARK` opcode. Useful as a "we got to
    line 42" breadcrumb.
  - `HALT` --- terminal record. Engine is idle.
]

#slide[
  = Why a ring, not a stream?
  Two reasons.

  + *Back-pressure goes the wrong way.* The engine runs at
    fabric clock; UART runs at 115200 bps. If we streamed
    every quarter live, we'd lose data the moment something
    interesting happened.
  + *Storage is cheap, host RAM is infinite.* Run the
    program; let the ring fill at fabric speed; drain it at
    UART speed after `HALT`. If the ring wraps, you lost
    the earlier history --- but you can see *that* it
    wrapped from the record headers.

  Verde's ring is sized for ~16K records. Rojo's SDRAM is
  effectively unbounded.
]

#slide[
  = Host-side decoding
  The host already has the program. Each `CAPTURE` record
  carries an *index* (program counter at the `CAPTURE`
  opcode). The host walks the ring, matches indices to
  source lines, and produces:

  ```
  line 14 (capture #0): ACK   observed=0  expected=0  OK
  line 23 (capture #1): ACK   observed=0  expected=0  OK
  line 32 (capture #2): ACK   observed=1  expected=0  MISMATCH
  ```

  Mismatches are highlighted. Sticky flags are decoded. The
  output is what an engineer actually wants to see --- not
  a hex dump.
]
