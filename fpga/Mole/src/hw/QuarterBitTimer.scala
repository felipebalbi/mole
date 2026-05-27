package mole

import spinal.core._

/** Programmable down-counter that emits a single-cycle `tick` pulse on
  * underflow and re-loads itself with the current `reload` value.
  *
  * The block is the engine's *one* abstraction over wall-clock pacing.
  * `EMIT_BIT` advances one quarter-state per `tick`; `STRETCH_SCL` counts ticks
  * to dwell; `WAIT_ON` decrements its timeout per tick (Step 10). Because the
  * period is an `io.reload` input (not baked in at elaboration), `LOAD_TIMING`
  * (Step 11) can swap the active divider without re-synthesising anything.
  *
  * ==Timing model==
  *
  *   - On `io.load` (level): `counter := io.reload` on the next edge. No tick
  *     is emitted on this edge. Use this when entering a state that needs to
  *     start a fresh period.
  *   - Otherwise, when `io.enable`:
  *     - if `counter === 0`: `io.tick := True` (combinationally, same cycle)
  *       AND `counter := io.reload` (registered, takes effect next edge).
  *     - else: `counter := counter - 1`.
  *   - When `!io.enable && !io.load`: the counter holds its value (timer is
  *     paused; no ticks emitted).
  *
  * Concretely, after a `load` with `reload = R`, the timer dwells for `R + 1`
  * enable cycles before the first tick fires (counter at R, R-1, ..., 1, 0; the
  * 0 cycle is the tick cycle). Subsequent ticks follow every `R + 1` enable
  * cycles.
  *
  * Pause semantics matter for `STRETCH_SCL` (engine drops `enable` while
  * waiting on the next instruction's setup) and for `WAIT_ON` (timer doesn't
  * decrement while the FSM is in the wait state's "sampling" pre-period).
  *
  * @param maxReloadValue
  *   Largest legal `reload` value, used to size the counter at elaboration. The
  *   default (`1023`) matches the 10-bit `LOAD_TIMING` divider field so the
  *   same timer instance survives from Step 8's reset-only path through Step
  *   11's runtime reloads without re-elaboration.
  */
case class QuarterBitTimer(maxReloadValue: Int = 1023) extends Component {

  require(
    maxReloadValue >= 1,
    s"QuarterBitTimer maxReloadValue=$maxReloadValue must be >= 1"
  )

  /** Width of the counter and of the `reload` input. */
  val counterWidth: Int = log2Up(maxReloadValue + 1)

  val io = new Bundle {

    /** Period operand --- the value loaded into the counter on `load` and
      * re-loaded on the cycle a `tick` fires. Sampled combinationally; the
      * engine usually wires this to the output of the timing-divider mux (Step
      * 11) and updates it on `LOAD_TIMING`.
      */
    val reload = in UInt (counterWidth bits)

    /** Single-cycle (or held) "reset the period" strobe. When True, `counter`
      * is overwritten with `reload` next edge regardless of `enable`. No tick
      * emitted on the load cycle itself. Lets the engine enter `EMIT_BIT` /
      * `STRETCH_SCL` / `WAIT_ON` with a fresh, predictable period rather than
      * inheriting the previous state's residual count.
      */
    val load = in Bool ()

    /** Run gate. While False the counter holds and no ticks fire. Engine raises
      * this in active execute states and drops it during fetch / decode (so
      * quarter-bit pacing pauses during fetch overhead --- the next bit's Q0
      * still gets a full quarter of low SCL).
      */
    val enable = in Bool ()

    /** Single-cycle pulse fired on the cycle the counter hits zero while
      * enabled. The engine consumes this as the "advance to the next quarter"
      * signal.
      */
    val tick = out Bool ()
  }

  /** Down-counter state. Resets to zero, but the engine is required to `load`
    * before the first `enable` so the first run of the timer starts from a
    * well-defined `reload` value.
    */
  val counter = Reg(UInt(counterWidth bits)) init (0)

  // Default: no tick. States below override only when firing.
  io.tick := False

  when(io.load) {
    counter := io.reload
  } elsewhen (io.enable) {
    when(counter === 0) {
      io.tick := True
      counter := io.reload
    } otherwise {
      counter := counter - 1
    }
  }
}
