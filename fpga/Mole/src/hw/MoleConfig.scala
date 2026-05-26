package mole

import spinal.core._

/** Compile-time configuration record for the Mole v0 bit-cycle engine.
  *
  * Held as a `case class` so every sub-block (engine, SPRAM controller,
  * UART, pad wrapper, ...) receives one immutable value and derives its
  * widths and counter constants from it at elaboration. Nothing in this
  * class survives into hardware — it only shapes how the hardware is
  * built.
  *
  * One `MoleConfig` instance is constructed by `MoleTop` (Phase 2) and
  * threaded through every sub-block. Sub-blocks **never** re-derive
  * timing or sizing from raw `fabricFreqHz`; they consume the already-
  * derived helpers (`quarterPeriodCyclesFor(...)` etc.). This mirrors
  * the `I2cConfig` discipline in the sibling
  * `icebreaker-spinalhdl-examples/I2c` project — single source of truth
  * for all derived counters.
  *
  * @param fabricFreqHz
  *   Post-PLL fabric clock. v0 default is 48 MHz, which is comfortable
  *   on the iCE40 UP5K timing report. ROADMAP §"Quarter-bit timing"
  *   notes the fabric clock IS the quarter-bit clock — every state in
  *   the bit FSM advances on a quarter-bit boundary, never sub-quarter.
  *   Type is `HertzNumber` (not `Int`) so the type system catches Hz vs
  *   MHz mismatches at elaboration.
  *
  * @param quarterPeriodCyclesReset
  *   Power-on default for the quarter-bit divider, in fabric cycles.
  *   Overridable at runtime via `LOAD_TIMING` (Step 11). Default 12 →
  *   quarter rate = 4 MHz at 48 MHz fabric → bit rate = 1 MHz, well
  *   inside I²C fast-plus and I³C OD-low ranges.
  *
  * @param programWordCount
  *   Depth of the SPRAM-backed program memory, in 16-bit words. Each
  *   ISA instruction is exactly one word (ROADMAP §"Encoding width" —
  *   16-bit fixed). The 4096-instruction cap follows the 12-bit
  *   absolute `JMP` operand: above 4096, `JMP` cannot reach all of
  *   memory and the encoder must reject the program. Long-range
  *   conditional branches expand to `BRANCH_ON cond, near` + `JMP far`
  *   in the SDK to keep within ±128 of `BRANCH_ON`'s signed offset
  *   while still spanning the full 4096-instruction range.
  *
  * @param resultRingByteCount
  *   Depth of the result ring, in bytes. The engine streams sampled
  *   bits, mismatch flags, `MARK` records, and the `HALT` status into
  *   this ring; the UART TX drains it back to the host. A short ring
  *   is fine for v0 — a worst-case I³C SDR write is ~2 500 bits ~
  *   320 bytes of sampled-bit traffic.
  *
  * @param captureMaxBits
  *   Per-program cap on capturable bits. Defines the back-pressure
  *   boundary in the result ring producer. The compiler rejects
  *   programs whose total `capture=1` count exceeds this number, so
  *   the engine never has to deal with mid-run ring overflow.
  *
  * @param uartBaud
  *   Default UART baud rate for both directions of the host-link UART.
  *   iCEBreaker's USB-UART bridge is an FT2232H, which comfortably
  *   supports up to 12 Mbaud. The Mole production default is
  *   **2 Mbaud** — the highest rate that still keeps the textbook 16×
  *   RX oversample at 48 MHz fabric. At 2 Mbaud × 16× oversample the
  *   24-bit DDS phase increment is
  *   `round(2_000_000 * 16 * 2^24 / 48_000_000) = 11_184_811`
  *   (`0xAAA_AAB`), fitting cleanly in the 24-bit accumulator with
  *   ppm-level baud accuracy. Pushing higher (e.g. 3 Mbaud) would
  *   either overflow the DDS at 16× or force dropping oversample to
  *   8× — neither is justified for v0. 2 Mbaud easily streams
  *   ring-buffer drain traffic without throttling the engine.
  */
case class MoleConfig(
    fabricFreqHz: HertzNumber = 48 MHz, // .MHz method from spinal.core._; postfix form is documented sugar
    quarterPeriodCyclesReset: Int = 12,
    programWordCount: Int = 4096,
    resultRingByteCount: Int = 8192,
    captureMaxBits: Int = 65536,
    uartBaud: Int = 2_000_000
) {

  require(
    quarterPeriodCyclesReset >= 1,
    s"quarterPeriodCyclesReset=$quarterPeriodCyclesReset must be >= 1"
  )

  require(
    programWordCount >= 1,
    s"programWordCount=$programWordCount must be >= 1"
  )

  // 12-bit absolute JMP operand (ROADMAP §"Encoding width") caps the
  // program at 4096 instructions = 4096 × 16-bit words = 8 KiB. No
  // `isPow2` requirement here — JMP indexes directly and doesn't need
  // a power-of-two mask. A future v1 jumbo-address opcode could lift
  // the cap if a workload ever needs it.
  require(
    programWordCount <= 4096,
    s"programWordCount=$programWordCount exceeds 4096 (12-bit JMP addr cap)"
  )

  require(
    resultRingByteCount >= 1,
    s"resultRingByteCount=$resultRingByteCount must be >= 1"
  )

  require(
    captureMaxBits >= 1,
    s"captureMaxBits=$captureMaxBits must be >= 1"
  )

  require(uartBaud >= 1, s"uartBaud=$uartBaud must be >= 1")

  /** Convert a target wire-quarter rate into a fabric-cycle divider.
    *
    * The fabric clock is the quarter-bit clock; one fabric cycle = one
    * quarter-bit time at the reset divider. Different bus modes pick
    * different dividers via `LOAD_TIMING` + `SET_BUS_MODE`. This
    * helper sizes a divider at elaboration time so sub-blocks don't
    * have to repeat the math.
    *
    * Returns the integer divider; rounding is truncation toward zero.
    * The caller is responsible for asserting `>= 1`.
    */
  def quarterPeriodCyclesFor(quarterHz: HertzNumber): Int =
    (fabricFreqHz.toBigDecimal / quarterHz.toBigDecimal).toInt
}
