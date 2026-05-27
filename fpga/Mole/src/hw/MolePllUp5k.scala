package mole

import spinal.core._

/** 2x PLL wrapper for the iCE40 UP5K: 12 MHz pad input -> 24 MHz fabric clock.
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
  * For 12 MHz -> 24 MHz the values below give F_pllout = 12 * 32 / (1 * 16) =
  * 24 MHz, F_pfd = 12 MHz. FILTER_RANGE = 1 follows the datasheet's loop-filter
  * table for F_pfd in (7, 17] MHz. The VCO ladder (DIVF = 31) is unchanged from
  * the original 48 MHz recipe — only the post-divider DIVQ moves from 3 (÷8)
  * to 4 (÷16), so the loop-filter geometry stays valid.
  *
  *   - DIVR = 0 reference divider; F_pfd = F_ref / (DIVR + 1)
  *   - DIVF = 31 feedback divider; multiplies up by 32 (VCO at 384 MHz)
  *   - DIVQ = 4 output divider; divides down by 2^4 = 16
  *   - FILTER_RANGE = 1 loop filter setting for F_pfd in (7, 17] MHz
  *
  * Outputs:
  *   - `clkOut` is taken from PLLOUTGLOBAL so the fabric sees the 24 MHz clock
  *     through the iCE40 global clock network (low skew, the standard choice
  *     for a fabric clock). PLLOUTCORE is left unconnected; it would route
  *     through ordinary fabric routing and is reserved for the rare case where
  *     a clock has to stay off the global net.
  *   - `locked` is the primitive's async LOCK output. Treat it as
  *     asynchronous-to-fabric; the top-level reset bridge synchronises it into
  *     the 24 MHz domain (async-assert, sync-deassert).
  *
  * Bypass strategy:
  *   - `useBlackBox = true` (default, synthesis path) instantiates SB_PLL40_PAD
  *     with the parameters above.
  *   - `useBlackBox = false` (sim path) routes `clkIn` straight to `clkOut` and
  *     ties `locked` high. Verilator sims do not have the iCE40 PLL cell model,
  *     so the BlackBox path cannot be elaborated under simulation. MoleTopSim
  *     sets `useBlackBox = false` and pretends the 12 MHz pin is already the 24
  *     MHz fabric clock --- the cycle-count translation does not matter for
  *     functional sim, and the wrapper logic that depends on `locked` (the
  *     reset bridge) is exercised both ways by toggling `io_reset` at sim
  *     start.
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
  * Why 24 MHz and not 48 MHz: first real synth on the UP5K SG48I missed timing
  * at 48 MHz (Fmax ~28.4 MHz after two rounds of register retiming on the
  * loader FSM critical path). Closing 48 MHz on this part would take a
  * multi-PR refactor of the result-ring drainer and the UART RX baud chain
  * with no guaranteed win on UP5K's modest fabric. Mole Verde is the
  * pocket / per-dev tier; 24 MHz fabric still supports I2C all modes and I3C
  * up to ~6 MHz SCL (quarter-bit-clock math: 24 MHz / 4 = 6 MHz max bit
  * rate), which covers the bulk of compliance tests Verde is positioned for.
  * Full-rate I3C SDR (12.5 MHz SCL) and HDR-DDR are Mole Rojo (ECP5) territory
  * by design — see ROADMAP §"Hardware tiers".
  *
  * @param useBlackBox
  *   When true (default), instantiate the SB_PLL40_PAD primitive. When false,
  *   pass `clkIn` through unchanged and report `locked = True` immediately --
  *   the only path Verilator can run.
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

    /** 24 MHz fabric clock. Wrap this in a `ClockDomain` at the call site;
      * MolePllUp5k itself stays free of clock-domain assumptions.
      */
    val clkOut = out Bool ()

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
    io.clkOut := pll.io.PLLOUTGLOBAL
    io.locked := pll.io.LOCK
  } else {
    // Sim path: pass the reference through unchanged and report lock the
    // instant we are out of reset. Verilator has no model of SB_PLL40_PAD;
    // the BlackBox path cannot be elaborated under simulation. The reset
    // bridge in MoleTop is the only thing that actually consumes `locked`
    // and it tolerates this constant-True drive cleanly.
    io.clkOut := io.clkIn
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
  // doc; the divider triple is the 2x ratio computed above.
  addGeneric("FEEDBACK_PATH", "SIMPLE")
  addGeneric("DIVR", B"0000")
  addGeneric("DIVF", B"0011111")
  addGeneric("DIVQ", B"100")
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
