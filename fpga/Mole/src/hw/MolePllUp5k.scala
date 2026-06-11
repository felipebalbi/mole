package mole

import spinal.core._

/** Dual-output PLL wrapper for the iCE40 UP5K: 12 MHz pad input → 24 MHz
  * engineClk + 24 MHz uartClk (Verde topology).
  *
  * The iCEbreaker drives its 12 MHz clock onto a dedicated PLL input pin (pad
  * 35), which is the only pad on UP5K wired to the PLL's reference. That
  * physical constraint is what dictates the SB_PLL40_PAD primitive (the "_PAD"
  * variant routes the reference straight from the pad rather than out of the
  * core fabric). The iCE40 UP5K technology library only has one PLL tile, so
  * MoleTop instantiates exactly one of these.
  *
  * Recipe (from the iCE40 sysCLOCK Design Guide, "Simple PLL" feedback path):
  *
  * {{{
  *   F_pllout = F_ref * (DIVF + 1) / ((DIVR + 1) * 2^DIVQ)
  *   F_pfd    = F_ref / (DIVR + 1)   // must fall in 10..133 MHz
  * }}}
  *
  * For 12 MHz → 24 MHz (Verde target): F_pllout = 12 * 32 / (1 * 16) = 24 MHz,
  * F_pfd = 12 MHz.
  *
  * FILTER_RANGE = 1 follows the datasheet's loop-filter table for F_pfd in (7,
  * 17] MHz. The VCO ladder (DIVF = 31, VCO at 384 MHz) is the same recipe a 48
  * MHz output would use; only the post-divider DIVQ changes.
  *
  *   - DIVR = 0 reference divider; F_pfd = F_ref / (DIVR + 1)
  *   - DIVF = 31 feedback divider; multiplies up by 32 (VCO at 384 MHz)
  *   - DIVQ = 4 output divider; divides down by 2^4 = 16
  *   - FILTER_RANGE = 1 loop filter setting for F_pfd in (7, 17] MHz
  *
  * Verde Fmax history: v0 silicon validated at 24 MHz with `nextpnr --seed 3`
  * pinned (~37 % seed pass rate at 24 MHz, best Fmax ~24.5 MHz). Phase C
  * (X.1..X.5 perf chain) attempted to lift Verde to 48 MHz on UP5K-SG48; the
  * v0.2 5-stage pipeline plateaued at ~32 MHz best across 10 seeds (paths
  * shifted but ceiling didn't move structurally). To keep comfortable margin
  * off the compliance edge rather than chase a moving target, Verde returns to
  * the v0 silicon-validated 24 MHz fabric. Full-rate I3C SDR / HDR-DDR are Mole
  * Rojo (ECP5) territory.
  *
  * Outputs:
  *   - `clkOutEngine` (24 MHz) is taken from PLLOUTGLOBAL so the fabric sees
  *     the clock through the iCE40 global clock network (low skew, the standard
  *     choice for a fabric clock). PLLOUTCORE is left unconnected.
  *   - `clkOutUart` (24 MHz) is wired directly from `clkOutEngine` — the
  *     consumer (MoleTop) wraps each in its own ClockDomain, but they share a
  *     clock source. The dual-output IO shape is preserved so a future
  *     `engineClk` retarget (e.g. on Mole Rojo with ECP5 headroom) can
  *     re-enable a ÷2 toggle for a 2:1 ratio topology without re-shaping
  *     consumers.
  *   - `locked` is the primitive's async LOCK output. Treat it as
  *     asynchronous-to-fabric; the top-level reset bridge synchronises it into
  *     the 24 MHz domain (async-assert, sync-deassert).
  *
  * Bypass strategy:
  *   - `useBlackBox = true` (default, synthesis path) instantiates SB_PLL40_PAD
  *     with the parameters above.
  *   - `useBlackBox = false` (sim path) routes `clkIn` straight to both
  *     `clkOutEngine` and `clkOutUart`, with `locked` tied high. Verilator sims
  *     do not have the iCE40 PLL cell model. The bypass path produces 12 MHz on
  *     both outputs (instead of 24 MHz) but preserves the 1:1 frequency
  *     relationship between the two outputs.
  *
  * SB_PLL40_PAD pin notes:
  *   - PACKAGEPIN takes the reference straight from the pad; do not route it
  *     through fabric or a clock buffer first.
  *   - BYPASS is hard-tied LOW here: the PLL is the only clock source we want
  *     in production. The Scala-level `useBlackBox = false` is the only bypass
  *     mechanism Mole uses.
  *   - RESETB is **active LOW** (note the B suffix). MoleTop drives it from the
  *     synchronised, inverted external reset button.
  *
  * @param useBlackBox
  *   When true (default), instantiate the SB_PLL40_PAD primitive. When false,
  *   pass `clkIn` through unchanged (bypass) and report `locked = True`
  *   immediately — the only path Verilator can run.
  */
case class MolePllUp5k(useBlackBox: Boolean = true) extends Component {
  val io = new Bundle {

    /** Reference clock straight from the dedicated PLL input pad (12 MHz on the
      * iCEbreaker). Must be wired to PACKAGEPIN with no intermediate fabric
      * routing.
      */
    val clkIn = in Bool ()

    /** Active-LOW PLL reset. Driven by MoleTop from the synchronised inverted
      * external reset button, so a fresh press of the iCEbreaker reset button
      * also resets the PLL.
      */
    val resetB = in Bool ()

    /** 24 MHz fabric clock (engineClk). Wrap this in a `ClockDomain` at the
      * call site; MolePllUp5k itself stays free of clock-domain assumptions.
      */
    val clkOutEngine = out Bool ()

    /** 24 MHz UART clock (uartClk). Currently wired directly from
      * `clkOutEngine` (1:1 ratio, Verde topology). Wrap in a `ClockDomain` at
      * the call site; the dual-output IO shape is preserved for future retarget
      * where a 2:1 ratio (with an in-fabric ÷2 toggle) would be appropriate.
      */
    val clkOutUart = out Bool ()

    /** PLL LOCK indicator. Asynchronous to fabric; pass through a synchroniser
      * before using as a logic signal.
      */
    val locked = out Bool ()
  }
  noIoPrefix()

  if (useBlackBox) {
    val pll = new SB_PLL40_PAD
    pll.io.PACKAGEPIN := io.clkIn
    pll.io.RESETB := io.resetB
    pll.io.BYPASS := False
    io.clkOutEngine := pll.io.PLLOUTGLOBAL
    io.clkOutUart := pll.io.PLLOUTGLOBAL // 1:1 on Verde
    io.locked := pll.io.LOCK
  } else {
    // Sim bypass path: pass the reference through unchanged on both outputs
    // and report lock high. Verilator has no model of SB_PLL40_PAD; the
    // BlackBox path cannot be elaborated under simulation. The bypass path
    // produces 12 MHz on both outputs (instead of 24 MHz) but preserves the
    // 1:1 frequency relationship. The reset bridge in MoleTop is the only
    // thing that actually consumes `locked` and it tolerates this
    // constant-True drive.
    io.clkOutEngine := io.clkIn
    io.clkOutUart := io.clkIn
    io.locked := True
  }
}

/** `SB_PLL40_PAD` primitive --- iCE40 UP5K "Simple PLL", pad-driven reference
  * variant.
  *
  * Port and parameter names cross-checked against the icestorm `cells_sim.v`
  * model and the iCE40 sysCLOCK Design Guide. The "_PAD" suffix on the cell
  * indicates the PLL's reference comes straight from a physical pad
  * (PACKAGEPIN), not from core fabric. The iCEbreaker brings its 12 MHz
  * oscillator onto exactly such a pad, so this is the correct primitive.
  *
  * Pins (production-relevant only):
  *   - PACKAGEPIN in reference clock from pad
  *   - RESETB in active-low PLL reset
  *   - BYPASS in active-high: routes PACKAGEPIN -> PLLOUT* directly
  *   - PLLOUTCORE out PLL output routed through ordinary fabric routing
  *   - PLLOUTGLOBAL out PLL output routed through a global clock buffer
  *   - LOCK out asynchronous lock indicator
  *
  * Unused production pins (omitted from this BlackBox so yosys does not warn
  * about unconnected nets): EXTFEEDBACK, DYNAMICDELAY, LATCHINPUTVALUE, SDI,
  * SDO, SCLK. They are for advanced features (dynamic phase shift, external
  * feedback loop) that Mole does not need.
  */
class SB_PLL40_PAD extends BlackBox {
  // Parameters are added as generics so the BlackBox elaboration matches the
  // iCE40 primitive macro expansion that yosys's `synth_ice40` recognises.
  // The FEEDBACK_PATH default "SIMPLE" matches the recipe in the class
  // doc; the divider triple is the 24 MHz recipe (VCO at 384 MHz, DIVQ=4).
  addGeneric("FEEDBACK_PATH", "SIMPLE")
  addGeneric("DIVR", B"0000")
  addGeneric("DIVF", B"0011111")
  addGeneric("DIVQ", B"100") // ÷16 = 24 MHz (Verde target; matches v0 silicon)
  addGeneric("FILTER_RANGE", B"001")

  val io = new Bundle {
    val PACKAGEPIN = in Bool ()
    val RESETB = in Bool ()
    val BYPASS = in Bool ()
    val PLLOUTCORE = out Bool ()
    val PLLOUTGLOBAL = out Bool ()
    val LOCK = out Bool ()
  }
  noIoPrefix()
}
