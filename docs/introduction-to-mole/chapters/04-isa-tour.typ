#import "../lib.typ": *

// ISA tour.

#section-slide("The ISA")

#slide[
  = Fourteen opcodes, sixteen bits each
  Fixed-width 16-bit instructions. Opcode is *always* in
  `[15:12]`. Flag triple (`expect` \/ `mask` \/ `capture`) on
  bearer opcodes is *always* at `[2:0]`. Symbol fields live at
  the high end; reserved bits fill the middle.

  This alignment is what makes the hardware decoder cheap.
  Don't unalign it.
]

#slide[
  = The opcodes (drive + observe)

  #table(
    columns: (auto, 1fr),
    stroke: 0.5pt + muted,
    [`EMIT_BIT`],            [drive one canonical bit on SDA],
    [`EMIT_QUARTER`],        [drive one quarter explicitly],
    [`SAMPLE_BIT_ON_SCL`],   [target-role: sample SDA on SCL high],
    [`DRIVE_BIT_ON_SCL`],    [target-role: drive SDA paced by external SCL],
    [`SET_BUS_MODE`],        [switch electrical class (i2c, i3c-OD, i3c-PP, hdr-ddr)],
    [`LOAD_TIMING`],         [load the quarter-bit clock divider],
    [`STRETCH_SCL`],         [target-role: hold SCL low for N quarters],
  )
]

#slide[
  = The opcodes (flow + status)

  #table(
    columns: (auto, 1fr),
    stroke: 0.5pt + muted,
    [`BRANCH_ON`],   [signed PC-offset branch on a cond code],
    [`WAIT_ON`],     [block up to N quarters on a cond code],
    [`LOAD_LOOP`],   [`LCR[reg] <- imm8` (new in v0.5)],
    [`DEC_BRANCH`],  [decrement LCR, branch if non-zero],
    [`CAPTURE`],     [push an event into the result ring],
    [`MARK`],        [push a host-provided marker into the ring],
    [`HALT`],        [stop the engine cleanly],
  )

  Four opcode slots remain reserved for v0.5 candidates
  (`FLAG_CLEAR`, `CAPTURE_RUN`, two more).
]

#slide[
  = `BRANCH_ON` / `WAIT_ON` share a namespace
  Same instruction shape:

  ```
  [15:12] op   [11:8] cond_code   [7:0] operand
  ```

  Same cond codes:
  ```
  0  always         5  scl_high
  1  mismatch       6  scl_low
  2  timeout        7  sda_high
  3  start_seen     8  sda_low
  4  stop_seen      9  capture_full
  ```

  Codes 10..15 reserved for v0.5. The only difference is what
  the operand means: signed PC offset for `BRANCH_ON`,
  unsigned quarter-tick timeout for `WAIT_ON`.
]

#slide[
  = Sticky flags
  Four sticky flags survive across opcodes:

  - `MISMATCH_FLAG` --- last `expect` didn't match observed.
  - `TIMEOUT_FLAG` --- last `WAIT_ON` aged out.
  - `START_FLAG` --- a START was seen on the bus.
  - `STOP_FLAG` --- a STOP was seen on the bus.

  Set by the engine. Cleared *only* by the next opcode that
  would write them. (A v0.5 `FLAG_CLEAR` is reserved for the
  case where you want to clear without driving anything.)

  This is why `BRANCH_ON MISMATCH` "just works" after the
  last `EMIT_BIT`.
]
