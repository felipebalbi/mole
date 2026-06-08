package mole

import spinal.core._

/** Dual-output PLL wrapper for the iCE40 UP5K: 12 MHz pad input →
  * 48 MHz engineClk + 24 MHz uartClk.
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
  * For 12 MHz → 48 MHz (v0.2 target):
  *   F_pllout = 12 * 32 / (1 * 8) = 48 MHz, F_pfd = 12 MHz.
  *
  * FILTER_RANGE = 1 follows the datasheet's loop-filter table for F_pfd in
  * (7, 17] MHz. The VCO ladder (DIVF = 31, VCO at 384 MHz) is unchanged from
  * the v0 24 MHz recipe — only the post-divider DIVQ moves back from 4 (÷16)
  * to 3 (÷8), restoring the "original 48 MHz recipe" the v0 PLL source
  * documents explicitly.
  *
  *   - DIVR = 0 reference divider; F_pfd = F_ref / (DIVR + 1)
  *   - DIVF = 31 feedback divider; multiplies up by 32 (VCO at 384 MHz)
  *   - DIVQ = 3 output divider; divides down by 2^3 = 8
  *   - FILTER_RANGE = 1 loop filter setting for F_pfd in (7, 17] MHz
  *
  * Phase C bet: the 5-stage pipeline (lands in C.6) closes 48 MHz on the UP5K
  * SG48I where the v0 monolithic FSM did not. The v0 design came in at Fmax
  * ~28.4 MHz after two rounds of register-retiming the loader FSM critical
  * path; that result drove the choice of DIVQ = 4 (24 MHz) in v0. C.4 gets
  * the PLL recipe in place; nextpnr timing closure awaits C.6+.
  *
  * Outputs:
  *   - `clkOutEngine` (48 MHz) is taken from PLLOUTGLOBAL so the fabric sees
  *     the clock through the iCE40 global clock network (low skew, the
  *     standard choice for a fabric clock). PLLOUTCORE is left unconnected.
  *   - `clkOutUart` (24 MHz) is derived from `clkOutEngine` by a fabric
  *     toggle FF (÷2 divider), giving a 50 %-duty 24 MHz clock with one
  *     engineClk-cycle skew vs PLLOUTGLOBAL. The consumer (MoleTop, C.6)
  *     wraps `clkOutUart` in its own ClockDomain; this component stays free
  *     of top-level clock-domain assumptions.
  *   - `locked` is the primitive's async LOCK output. Treat it as
  *     asynchronous-to-fabric; the top-level reset bridge synchronises it into
  *     the 48 MHz domain (async-assert, sync-deassert).
  *
  * Bypass strategy:
  *   - `useBlackBox = true` (default, synthesis path) instantiates
  *     SB_PLL40_PAD with the parameters above.
  *   - `useBlackBox = false` (sim path) routes `clkIn` straight to
  *     `clkOutEngine` and derives `clkOutUart` via a toggle FF clocked by
  *     `clkIn`, with `locked` tied high. Verilator sims do not have the iCE40
  *     PLL cell model. The bypass path produces two clocks at a 2:1 ratio
  *     (so 12 MHz / 6 MHz instead of 48 MHz / 24 MHz in sim), preserving the
  *     frequency relationship even though the absolute frequencies are wrong.
  *
  * SB_PLL40_PAD pin notes:
  *   - PACKAGEPIN takes the reference straight from the pad; do not route it
  *     through fabric or a clock buffer first.
  *   - BYPASS is hard-tied LOW here: the PLL is the only clock source we want
  *     in production. The Scala-level `useBlackBox = false` is the only bypass
  *     mechanism Mole uses.
  *   - RESETB is **active LOW** (note the B suffix). MoleTop drives it from
  *     the synchronised, inverted external reset button.
  *
  * I3C SDR 12.5 MHz note: the I3C PP-high target (50 MHz quarter rate) is
  * NOT supported on Mole Verde at 48 MHz engineClk — it requires fabric >
  * 50 MHz or a fractional divider. Full-rate I3C SDR and HDR-DDR are Mole
  * Rojo (ECP5) territory by design — see ROADMAP §"Hardware tiers".
  *
  * @param useBlackBox
  *   When true (default), instantiate the SB_PLL40_PAD primitive. When false,
  *   pass `clkIn` through unchanged (bypass) and report `locked = True`
  *   immediately — the only path Verilator can run.
  */
case class MolePllUp5k(useBlackBox: Boolean = true) extends Component {
  val io = new Bundle {

    /** Reference clock straight from the dedicated PLL input pad (12 MHz on
      * the iCEbreaker). Must be wired to PACKAGEPIN with no intermediate
      * fabric routing.
      */
    val clkIn = in Bool ()

    /** Active-LOW PLL reset. Driven by MoleTop from the synchronised inverted
      * external reset button, so a fresh press of the iCEbreaker reset button
      * also resets the PLL.
      */
    val resetB = in Bool ()

    /** 48 MHz fabric clock (engineClk). Wrap this in a `ClockDomain` at the
      * call site; MolePllUp5k itself stays free of clock-domain assumptions.
      */
    val clkOutEngine = out Bool ()

    /** 24 MHz UART clock (uartClk). Derived from `clkOutEngine` by a fabric
      * ÷2 toggle FF. Wrap in a `ClockDomain` at the call site.
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
    io.locked := pll.io.LOCK

    // 2:1 fabric divider for uartClk. The toggle FF runs on engineClk
    // and produces a 50 %-duty 24 MHz clock with one engineClk-cycle
    // skew vs PLLOUTGLOBAL. A synthetic ClockDomain is created here
    // just for this one FF so elaboration stays clean when this
    // component is used standalone; the consumer (MoleTop, C.6) will
    // rewrap clkOutUart into its own ClockDomain.
    val engDivCd = ClockDomain(
      clock = pll.io.PLLOUTGLOBAL,
      config = ClockDomainConfig(
        clockEdge = RISING,
        resetKind = BOOT,
        resetActiveLevel = HIGH
      )
    )
    val uartDiv = new ClockingArea(engDivCd) {
      val tick = Reg(Bool()) init False
      tick := !tick
    }
    io.clkOutUart := uartDiv.tick

  } else {
    // Sim bypass path: pass the reference through unchanged and report
    // lock high. Verilator has no model of SB_PLL40_PAD; the BlackBox
    // path cannot be elaborated under simulation. The bypass path
    // produces two clocks at a 2:1 ratio (12 MHz / 6 MHz instead of
    // 48 MHz / 24 MHz in sim) so the frequency relationship is
    // representative even though the absolute frequencies are wrong.
    // The reset bridge in MoleTop is the only thing that actually
    // consumes `locked` and it tolerates this constant-True drive.
    io.clkOutEngine := io.clkIn

    // ÷2 toggle FF for the bypass path, clocked by clkIn.
    val simDivCd = ClockDomain(
      clock = io.clkIn,
      config = ClockDomainConfig(
        clockEdge = RISING,
        resetKind = BOOT,
        resetActiveLevel = HIGH
      )
    )
    val simDiv = new ClockingArea(simDivCd) {
      val tick = Reg(Bool()) init False
      tick := !tick
    }
    io.clkOutUart := simDiv.tick
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
  // doc; the divider triple is the 48 MHz recipe (VCO at 384 MHz, DIVQ=3).
  addGeneric("FEEDBACK_PATH", "SIMPLE")
  addGeneric("DIVR", B"0000")
  addGeneric("DIVF", B"0011111")
  addGeneric("DIVQ", B"011") // was B"100" (÷16 = 24 MHz); ÷8 = 48 MHz
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
