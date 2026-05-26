package mole

// Imported from felipebalbi/icebreaker-spinalhdl-examples@98c06a8cf39f6c212d3b46d98868aca398a82186 Uart/src/hw/BaudGenerator.scala.
// Modified for Mole only as documented inline (search for 'Mole').

import spinal.core._

/** Baud-rate generator using Direct Digital Synthesis (DDS).
  *
  * Why DDS instead of a divide-by-N counter? A counter approach picks `N =
  * round(clkFreqHz / baudRate)` and pulses every N cycles. The achievable rate
  * is then quantised to clkFreqHz/N, which can carry several percent error for
  * awkward (clk, baud) pairs. DDS keeps a fractional phase, so the *long-term*
  * average rate matches the requested baud to ppm-level accuracy. Individual
  * bit periods jitter by ±1 system clock, which is irrelevant for UART (the
  * receiver samples in the middle of each bit).
  *
  * How it works: Each clock cycle, add a constant `phaseInc` to the phase
  * accumulator. The accumulator is `accWidth + 1` bits wide; the bottom
  * `accWidth` bits hold the running phase, and the extra MSB captures the
  * carry-out of the addition. Whenever the addition overflows the `accWidth`
  * bits, the MSB pulses high for one cycle — that is our baud tick.
  *
  * By masking the MSB to 0 each cycle (via `acc(accWidth-1 downto 0)` then
  * resizing back up), we ensure the MSB is purely "did we just overflow?" and
  * not a sticky high bit.
  *
  * Phase increment math: tick_rate = clkFreqHz * phaseInc / 2^accWidth \=>
  * phaseInc = baudRate * 2^accWidth / clkFreqHz
  *
  * With accWidth=24 and 12 MHz / 115200 baud: phaseInc ≈ 161061, actual baud ≈
  * 115199.81 (~0.0002% error).
  *
  * Runtime vs. compile-time phaseInc: The phase increment is now fed in via
  * [[io.phaseInc]] rather than computed at elaboration. This lets a
  * higher-level wrapper (e.g. [[UartController]]) drive it from a memory-mapped
  * register so firmware can change baud at runtime.
  *
  * Bare instantiations of [[UartTx]] / [[UartRx]] still want a
  * compile-time-correct default, so [[BaudGenerator.phaseIncFor]] exposes the
  * same rounding math as a pure-Scala helper. Those wrappers wire `phaseInc` to
  * the constant returned by that helper, which the synth tool will fold into
  * the adder.
  *
  * @param accWidth
  *   Phase accumulator width in bits. Larger = more accurate baud rate (and
  *   slightly more LUTs). 24 is plenty for any realistic UART.
  */
case class BaudGenerator(accWidth: Int = 24) extends Component {
  val io = new Bundle {

    /** Hold low to keep the generator quiescent (acc = 0, no ticks). Raise high
      * to start producing ticks at the configured baud. The TX FSM holds this
      * low when idle and raises it the cycle a byte is accepted, so the *first*
      * tick lands a clean full bit period later — giving a properly-sized start
      * bit.
      */
    val enable = in Bool ()

    /** DDS phase increment. With `accWidth` = 24 and a 12 MHz system clock, a
      * value of 161 061 produces ~115 200 baud. See
      * [[BaudGenerator.phaseIncFor]] for the standard rounding rule. The
      * generator samples this every cycle; updates take effect on the next
      * tick.
      */
    val phaseInc = in UInt (accWidth bits)

    /** One-cycle pulse, on average once every `2^accWidth / phaseInc` cycles.
      * Registered, so glitch-free.
      */
    val tick = out Bool ()
  }

  /** Phase accumulator. The extra MSB captures overflow from the add. */
  val acc = Reg(UInt(accWidth + 1 bits)) init (0)

  when(!io.enable) {
    acc := 0
  } otherwise {
    // Take only the bottom accWidth bits of the previous accumulator
    // (i.e. clear last cycle's carry), zero-extend, then add phaseInc.
    // Any carry into bit `accWidth` is this cycle's tick.
    acc := acc(accWidth - 1 downto 0).resize(accWidth + 1) + io.phaseInc
  }

  // Register the tick to keep the output glitch-free and timing clean.
  // Costs 1 cycle of latency, irrelevant at baud-rate timescales.
  io.tick := RegNext(acc.msb) init (False)
}

object BaudGenerator {

  /** Default DDS accumulator width. 24 bits is enough for ppm-level baud
    * accuracy at any realistic UART rate; making it a constant lets every
    * consumer (BaudGenerator instance, UartTx/UartRx io ports, regif BAUD
    * field) agree on the same width without threading a parameter through the
    * entire stack.
    */
  val defaultAccWidth: Int = 24

  /** Pure-Scala helper: compute the phase increment needed to hit `baudRate` on
    * a clock of `clkFreqHz`, with `accWidth` accumulator bits. Round-to-
    * nearest; matches the rule the original compile-time generator used.
    */
  def phaseIncFor(clkFreqHz: Int, baudRate: Int, accWidth: Int = 24): Long = {
    require(clkFreqHz > 0 && baudRate > 0, "clkFreqHz and baudRate must be > 0")
    (((BigInt(baudRate) << accWidth) + (BigInt(clkFreqHz) >> 1))
      / clkFreqHz).toLong
  }

  /** Same, but pulled straight from a [[UartConfig]] for the common case. */
  def phaseIncFor(cfg: UartConfig, accWidth: Int): Long =
    phaseIncFor(cfg.clkFreqHz, cfg.baudRate, accWidth)
}
