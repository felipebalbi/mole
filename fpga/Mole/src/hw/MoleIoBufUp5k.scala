package mole

import spinal.core._
import spinal.lib._

/** SB_IO pad wrappers for Mole's bidirectional bus (sda + scl) on iCE40 UP5K.
  *
  * The engine speaks the 3-signal `MoleBus` per line (`driveLow`, `driveHigh`,
  * `read`). The wired-AND segment between MoleTop and the rest of the world is
  * realised by an `SB_IO` primitive per line, instantiated explicitly here (not
  * inferred by synth_ice40 from a conditional drive pattern as the sibling
  * `icebreaker-spinalhdl-examples/I2c` project does). The reason: Mole has to
  * support I3C-PP and HDR-DDR push-pull bus modes, where the pad actively
  * drives high; the inferred open-drain pattern only ever drives low or
  * releases. An explicit SB_IO with `OUTPUT_ENABLE := driveLow || driveHigh`
  * and `D_OUT_0 := driveHigh` covers both classes from a single pad shape and
  * leaves the OD-vs-PP decision to `BUS_MODE` in the engine.
  *
  * Per-line truth table (matches `OpenDrainBusSim.wiredAnd` for a single
  * participant + external pull-up):
  *
  * {{{
  *   driveLow driveHigh  | OE D_OUT_0  pad
  *   --------+----------+----+--------+----------
  *     0        0       |  0    X     pull-up wins -> high
  *     1        0       |  1    0     NMOS drives low
  *     0        1       |  1    1     PMOS drives high (PP)
  *     1        1       |  1    1     ILLEGAL: bus contention
  * }}}
  *
  * The `driveLow && driveHigh` case is a semantic violation: the engine should
  * never simultaneously request both meanings ("pull low" and "drive high") for
  * the same line. The symbol decoder (`BusMode.SymbolDecoder`) produces no
  * `(tx_symbol, BUS_MODE)` combination that asserts both. A SpinalHDL
  * `assert(...)` here catches future regressions during simulation. Note: with
  * the wiring above, both inputs true would actually elaborate to
  * `OE=1, D_OUT_0=1` (PP drive high) -- the assert is enforcing the engine-side
  * invariant, not preventing a literal pad shoot-through.
  *
  * `PIN_TYPE = 6'b101000` decomposes as:
  *   - bits[5:2] = 4'b1010 -> PIN_OUTPUT_TRISTATE: the pad's output stage is
  *     tristateable; OUTPUT_ENABLE controls whether D_OUT_0 reaches the pad or
  *     the pad floats.
  *   - bits[1:0] = 2'b00 -> PIN_INPUT (live, unregistered): D_IN_0 is the live
  *     pad value with no flop in the way, so the engine sees the same value the
  *     bus electrics produced this cycle. Mole synchronises this into the
  *     fabric domain at the engine boundary, not at the pad.
  *
  * Bypass strategy (mirrors `MolePllUp5k.useBlackBox`):
  *   - `useBlackBox = true` (default, synthesis path): instantiate one SB_IO
  *     per line. The pad is an `inout(Analog(Bool()))` that the top-level wires
  *     to its own pad port; nextpnr resolves it onto the physical package pin
  *     via the `.pcf` file.
  *   - `useBlackBox = false` (sim path): skip the BlackBox entirely (Verilator
  *     has no model of SB_IO) and synthesise `read` from a wired-AND of one
  *     driver plus an assumed external pull-up. The pad inout is still declared
  *     (same IO shape both ways) but is left dangling under sim -- no testbench
  *     drives it. MoleTopSim drives the engine's `MoleBus` drivers and samples
  *     `read` through this same wrapper.
  *
  * @param useBlackBox
  *   When true (default), instantiate two SB_IO primitives. When false, route
  *   `read` from a sim-only wired-AND model and leave the pad ports
  *   unconnected.
  */
case class MoleIoBufUp5k(useBlackBox: Boolean = true) extends Component {
  val io = new Bundle {

    /** Engine-facing bus: receives `driveLow` / `driveHigh` from the engine and
      * publishes `read` (live pad value, or the wired-AND model under sim).
      */
    val bus = slave(MoleBus())

    /** SDA pad. MoleTop wires this through to its own `inout(Analog(Bool()))`
      * pad port, which the toolchain constrains onto a physical package pin via
      * the `.pcf` `set_io io_sda <pin>` line.
      */
    val sdaPad = inout(Analog(Bool()))

    /** SCL pad. Same shape as `sdaPad`. */
    val sclPad = inout(Analog(Bool()))
  }
  noIoPrefix()

  bindLine(io.bus.sda, io.sdaPad, "sda")
  bindLine(io.bus.scl, io.sclPad, "scl")

  private def bindLine(line: MoleBusLine, pad: Bool, label: String): Unit = {
    // Both modes: catch the symbol decoder's "never-emit" contention case
    // as a simulation-time assertion. `label` is bound at elaboration
    // time so the message identifies which line (sda / scl) tripped
    // without any runtime overhead.
    assert(
      !(line.driveLow && line.driveHigh),
      s"MoleIoBufUp5k: $label bus contention (driveLow && driveHigh)"
    )

    if (useBlackBox) {
      val sb = SB_IO()
      sb.io.OUTPUT_ENABLE := line.driveLow || line.driveHigh
      sb.io.D_OUT_0 := line.driveHigh
      sb.io.PACKAGE_PIN <> pad
      line.read := sb.io.D_IN_0
    } else {
      // Sim wired-AND: legal cases of the truth table above.
      //   (0, 0) -> pull-up wins  -> read = 1 = !driveLow
      //   (1, 0) -> NMOS pulls low -> read = 0 = !driveLow
      //   (0, 1) -> PMOS pulls hi -> read = 1 = !driveLow
      // (driveLow && driveHigh) is rejected by the assert above.
      line.read := !line.driveLow
    }
  }
}

/** `SB_IO` primitive --- iCE40 UP5K bidirectional pad cell.
  *
  * Port and parameter names cross-checked against the icestorm `cells_sim.v`
  * model and the Lattice iCE40 LP/HX/UP family handbook section "sysIO
  * Primitive Definitions".
  *
  * PIN_TYPE = 6'b101000 (PIN_OUTPUT_TRISTATE | PIN_INPUT, unregistered):
  *   - The output stage is tristateable: when OUTPUT_ENABLE is low, the pad
  *     floats and the external pull-up (PMOD adapter, 4.7 kohm for I2C / 1 kohm
  *     for I3C-OD windows) wins.
  *   - When OUTPUT_ENABLE is high, D_OUT_0 reaches the pad: 0 -> NMOS pulls
  *     low, 1 -> PMOS drives high (push-pull, the I3C-PP / HDR-DDR case).
  *   - D_IN_0 is the live pad value with no flop in the way; the fabric
  *     synchronises into its clock domain at the engine boundary, not here.
  *
  * Pins (production-relevant only):
  *   - PACKAGE_PIN inout the physical pad; goes to MoleTop's pad port
  *   - OUTPUT_ENABLE in drive (1) vs tristate (0)
  *   - D_OUT_0 in output value when OE = 1
  *   - D_IN_0 out live pad value (combinational input)
  *
  * Omitted production pins (same convention as `SB_PLL40_PAD`: omitted to keep
  * yosys from warning about unconnected nets, since we use neither the DDR path
  * nor the registered I/O modes): D_OUT_1, D_IN_1 (DDR; PIN_TYPE bit 0 = 0
  * means no DDR output) OUTPUT_CLK, INPUT_CLK, CLOCK_ENABLE (registered I/O;
  * PIN_TYPE[1:0] = 00 means no input register, so no clock is meaningful)
  * LATCH_INPUT_VALUE (input-latch enable; PIN_TYPE[1:0] != 01 makes this a
  * don't-care)
  */
class SB_IO extends BlackBox {
  // 6'b101000 = PIN_OUTPUT_TRISTATE | PIN_INPUT (unregistered).
  // Encoding cross-referenced against the icestorm wiki "SB_IO Primitive"
  // page: bits[5:2] = output type (1010 = tristate), bits[1:0] = input
  // type (00 = live / unregistered).
  addGeneric("PIN_TYPE", B"101000")

  val io = new Bundle {
    val PACKAGE_PIN = inout(Analog(Bool()))
    val OUTPUT_ENABLE = in Bool ()
    val D_OUT_0 = in Bool ()
    val D_IN_0 = out Bool ()
  }
  noIoPrefix()
}

object SB_IO {
  def apply(): SB_IO = new SB_IO
}
