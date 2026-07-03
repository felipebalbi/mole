// SPDX-FileCopyrightText: 2026 Felipe Balbi
// SPDX-License-Identifier: CERN-OHL-W-2.0

package mole

import spinal.core._

/** Compile-time configuration record for the Mole v0.2 bit-cycle engine.
  *
  * Held as a `case class` so every sub-block (engine, SPRAM controller, UART,
  * pad wrapper, ...) receives one immutable value and derives its widths and
  * counter constants from it at elaboration. Nothing in this class survives
  * into hardware — it only shapes how the hardware is built.
  *
  * One `MoleConfig` instance is constructed by `MoleTop` (Phase 2) and threaded
  * through every sub-block. Sub-blocks **never** re-derive timing or sizing
  * from raw `fabricFreqHz`; they consume the already-derived helpers
  * (`quarterPeriodCyclesFor(...)` etc.). This mirrors the `I2cConfig`
  * discipline in the sibling `icebreaker-spinalhdl-examples/I2c` project —
  * single source of truth for all derived counters.
  *
  * @param fabricFreqHz
  *   Post-PLL fabric clock, i.e. the `engineClk` domain. v0.2 target is **24
  *   MHz** on the iCE40 UP5K (Mole Verde) — the same fabric clock the v0 engine
  *   ran on, with comfortable Fmax margin. Phase X attempted a 48 MHz target as
  *   Verde's headroom goal; the X.1–X.5 perf chain on UP5K-SG48 plateaued at
  *   ~32 MHz best across 10 seeds (paths shifted but ceiling didn't), at which
  *   point the call was made to keep 24 MHz to stay safely off the compliance
  *   edge rather than chase a moving Fmax target. Full-rate I3C SDR (12.5 MHz
  *   SCL) and HDR-DDR are Mole Rojo (ECP5) territory by design — see ROADMAP
  *   §"Hardware tiers" and §"Clocks". ROADMAP §"Quarter-bit timing" notes the
  *   fabric clock IS the quarter-bit clock — every state in the bit FSM
  *   advances on a quarter-bit boundary, never sub-quarter. Type is
  *   `HertzNumber` (not `Int`) so the type system catches Hz vs MHz mismatches
  *   at elaboration.
  *
  * Integer-divider audit at 24 MHz (fabricFreqHz / (bitHz × 4)):
  *   - I2C 100 kHz: 24_000_000 / (100_000 × 4) = 60 (integer, clean)
  *   - I2C 400 kHz: 24_000_000 / (400_000 × 4) = 15 (integer, clean)
  *   - I2C 1 MHz: 24_000_000 / (1_000_000 × 4) = 6 (integer, clean)
  *   - I3C OD 2 MHz: 24_000_000 / (2_000_000 × 4) = 3 (integer, clean)
  *   - I3C OD 4 MHz: 24_000_000 / (4_000_000 × 4) = 1.5 → NOT integer. 4 MHz
  *     I3C-OD is NOT supported on Mole Verde at 24 MHz; 2 MHz is the achievable
  *     ceiling here. Full-rate I3C SDR / HDR-DDR are Mole Rojo territory.
  *
  * Four of five standard I2C and I3C-OD rates are integer dividers at 24 MHz;
  * I3C OD 4 MHz is the one we lose vs. the (aspirational) 48 MHz audit.
  *
  * @param uartFreqHz
  *   UART clock domain frequency in Hz. Equal to `fabricFreqHz` (24 MHz); both
  *   clocks run off the same PLL output. The dual-clock-domain plumbing in
  *   `MolePllUp5k` is preserved as IO shape so a future `engineClk` retarget
  *   (e.g. on Mole Rojo with ECP5 headroom) can re-enable the ÷2 toggle without
  *   re-shaping consumers. A `require` guards `uartFreqHz ≤ fabricFreqHz` and
  *   that the ratio is either 1 (current Verde topology) or 2 (the aspirational
  *   48/24 topology).
  *
  * @param quarterPeriodCyclesReset
  *   Power-on default for the quarter-bit divider, in fabric cycles.
  *   Overridable at runtime via `LOAD_TIMING` (Step 11). Default 6 → quarter
  *   rate = 4 MHz at 24 MHz fabric → bit rate = 1 MHz, well inside I²C
  *   fast-plus and I³C OD-low ranges.
  *
  * @param programWordCount
  *   Depth of the SPRAM-backed program memory, in 32-bit words. Each ISA v0.2
  *   instruction is exactly one 32-bit word (spec §3 — 32-bit fixed). The
  *   absolute cap is `MAX_PROGRAM_WORDS = 8192` (spec §10, mirrored by
  *   `mole-abi::MAX_PROGRAM_WORDS`); above 8192 the SPRAM tile-pair budget is
  *   exhausted. The default of 4096 words (= 16 KB) is half the tile-pair
  *   budget, leaving 12 KB for the result ring and future expansion. The
  *   `BRANCH_ON` signed 10-bit offset (±512) and `JMP` / `BranchOn ALWAYS` as
  *   long-range trampoline are the only jump-range constraints; programs up to
  *   8192 words are reachable by indirect-jump sequences.
  *
  * @param resultRingByteCount
  *   Depth of the result ring, in bytes. The engine streams sampled bits,
  *   mismatch flags, `MARK` records, and the `HALT` status into this ring; the
  *   UART TX drains it back to the host. The default of 8192 bytes = 2048 v0.2
  *   32-bit words (unchanged from v0's 4096 v0 16-bit words — same byte count,
  *   half the word count because the grain doubled). A short ring is fine for
  *   typical I³C SDR traffic; longer rings are possible up to the 16 KB
  *   tile-pair headroom above the program body.
  *
  * @param captureMaxBits
  *   Per-program cap on capturable bits. Defines the back-pressure boundary in
  *   the result ring producer. The compiler rejects programs whose total
  *   `capture=1` count exceeds this number, so the engine never has to deal
  *   with mid-run ring overflow.
  *
  * @param uartBaud
  *   Default UART baud rate for both directions of the host-link UART.
  *   iCEBreaker's USB-UART bridge is an FT2232H, which comfortably supports up
  *   to 12 Mbaud. The Mole Verde production default is **2 Mbaud** — paired
  *   with **8× RX oversample** (set at the [[UartConfig]] instantiation in
  *   [[MoleTop]]) on the 24 MHz uartClk fabric. At 2 Mbaud × 8× the
  *   `baudRate * oversample` product is `2_000_000 × 8 = 16_000_000`, which
  *   fits the `< clkFreqHz = 24_000_000` guard in [[UartConfig]] with
  *   comfortable headroom; the 24-bit DDS phase increment is
  *   `round(2_000_000 * 8 * 2^24 / 24_000_000) = 11_184_811` (`0xAAA_AAB`),
  *   fitting cleanly in the 24-bit accumulator with ppm-level baud accuracy. 8×
  *   is below the textbook 16× floor commonly cited for asynchronous UART
  *   receivers, but is comfortably above the 3× theoretical minimum and is the
  *   standard choice once link-quality concerns are dominated by the short,
  *   low-noise USB-to-FPGA hop on an iCEbreaker / FT2232H setup. The
  *   ~20-bit-period resync gap [[MoleTop]] requires (~10 µs at 2 Mbaud)
  *   continues to bound how a host can recover from a half-sent frame.
  *
  * Earlier history: the Verde production default was 1 Mbaud × 16× (the
  * conservative pin that matches every textbook UART implementation, reused
  * from sibling projects); 2 Mbaud × 8× was originally framed as "Rojo-only" in
  * early v0.2 drafts. The 1 Mbaud fallback remains available by passing
  * `--baud 1000000` to mole-loader and rebuilding the engine with
  * `uartBaud = 1_000_000` + `oversample = 16` if a particular host adapter
  * struggles with 2 Mbaud.
  *
  * @param stretchTimeoutCycles
  *   Stretch-wait timeout in fabric cycles, applied at the Q1->Q2 boundary of
  *   every controller-role `EMIT_BIT` when the slave is observed to be
  *   stretching SCL low. Default 2^20 = 1_048_576 cycles ÷ 24 MHz ≈ 43.7 ms at
  *   24 MHz fabric, matching v0 silicon-validated behaviour. Comfortably above
  *   SMBus tTIMEOUT (35 ms) and any plausible I2C/I3C wakeup; small enough that
  *   a genuinely wedged slave produces a deterministic HALT rather than an
  *   infinite spin. See `ROADMAP.md` §"Stretch-aware Q2 entry" for the
  *   engine-side contract. Set to 1 in a custom MoleConfig to make the engine
  *   HALT immediately on any observed stretch (useful for compliance tests that
  *   need to surface stretch as a violation rather than tolerate it).
  *
  * @param role
  *   Boot-default engine role. Defaults to [[EngineRole.Controller]] ---
  *   today's shipping behaviour and the only role exercised by Steps 1..18. Set
  *   to [[EngineRole.Target]] to power up with the external-SCL-slaved FSM
  *   active; see [[EngineRole]] for the per-role wire-level contracts.
  *
  * Post-Step-21 the bit-cycle engine carries a runtime `roleReg` initialised
  * from this field, and the `SET_ROLE` opcode flips it at any PC --- one
  * bitstream can play either role over its lifetime. A program that never
  * issues `SET_ROLE` keeps the boot-default behaviour, matching the pre-Step-21
  * compile-time-only contract.
  */
case class MoleConfig(
    fabricFreqHz: HertzNumber =
      24 MHz, // .MHz method from spinal.core._; postfix form is documented sugar
    uartFreqHz: Int =
      24_000_000, // uartClk = engineClk (1:1) on Verde; see MolePllUp5k
    quarterPeriodCyclesReset: Int = 6,
    programWordCount: Int = 4096,
    resultRingByteCount: Int = 8192,
    captureMaxBits: Int = 65536,
    uartBaud: Int = 2_000_000,
    stretchTimeoutCycles: Int = 1 << 20,
    role: EngineRole = EngineRole.Controller
) {

  // uartFreqHz must divide evenly into fabricFreqHz and the ratio
  // must be 1 (current Verde topology: both clocks at 24 MHz off the
  // same PLL output) or 2 (the aspirational 48/24 topology with the
  // ÷2 toggle FF in MolePllUp5k re-enabled). Documented in
  // fpga/Mole/AGENTS.md §"Quarter-bit is the timing unit on the wire".
  require(
    fabricFreqHz.toBigDecimal.toInt % uartFreqHz == 0,
    s"fabricFreqHz (${fabricFreqHz.toBigDecimal} Hz) must be a multiple " +
      s"of uartFreqHz ($uartFreqHz Hz)"
  )
  require(
    {
      val ratio = fabricFreqHz.toBigDecimal.toInt / uartFreqHz
      ratio == 1 || ratio == 2
    },
    s"uartFreqHz ($uartFreqHz Hz) must be equal to or half of fabricFreqHz " +
      s"(${fabricFreqHz.toBigDecimal} Hz); got ratio " +
      s"${fabricFreqHz.toBigDecimal.toInt / uartFreqHz}"
  )

  require(
    quarterPeriodCyclesReset >= 1,
    s"quarterPeriodCyclesReset=$quarterPeriodCyclesReset must be >= 1"
  )

  require(
    programWordCount >= 1,
    s"programWordCount=$programWordCount must be >= 1"
  )

  // v0.2 caps programWordCount at MAX_PROGRAM_WORDS = 8192 (spec §10,
  // mirrored in mole-abi::MAX_PROGRAM_WORDS). At 32-bit words this is
  // 32 KB, which fits in one SB_SPRAM256KA tile-pair (16K × 32-bit =
  // 64 KB). There is no isPow2 requirement; addresses are linear.
  require(
    programWordCount <= 8192,
    s"programWordCount=$programWordCount exceeds MAX_PROGRAM_WORDS=8192 (spec §10)"
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

  require(
    stretchTimeoutCycles >= 1,
    s"stretchTimeoutCycles=$stretchTimeoutCycles must be >= 1"
  )

  /** Convert a target wire-quarter rate into a fabric-cycle divider.
    *
    * The fabric clock is the quarter-bit clock; one fabric cycle = one
    * quarter-bit time at the reset divider. Different bus modes pick different
    * dividers via `LOAD_TIMING` + `SET_BUS_MODE`. This helper sizes a divider
    * at elaboration time so sub-blocks don't have to repeat the math.
    *
    * Returns the integer divider; rounding is truncation toward zero. The
    * caller is responsible for asserting `>= 1`.
    */
  def quarterPeriodCyclesFor(quarterHz: HertzNumber): Int =
    (fabricFreqHz.toBigDecimal / quarterHz.toBigDecimal).toInt
}
