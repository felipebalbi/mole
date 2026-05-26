package mole

// Imported from felipebalbi/icebreaker-spinalhdl-examples@98c06a8cf39f6c212d3b46d98868aca398a82186 Uart/src/hw/TxFsm.scala.
// Modified for Mole only as documented inline (search for 'Mole').

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

/** UART transmit frame sequencer (the "control" half of the TX path).
  *
  * Sequences the line through `Idle → Start → Data×N → [Parity] → Stop×M →
  * Idle`, one bit boundary per [[BaudGenerator]] tick. Knows nothing about how
  * the tick is generated, nothing about how the byte is stored
  * ([[TxShiftReg]]), and nothing about the byte-stream handshake — all three
  * concerns live in [[UartTx]] which wires this FSM, the baud generator, and
  * the shift register together.
  *
  * Frame on the wire (8N1 by default; parity slot present only when `cfg.parity
  * != ParityType.None`):
  * {{{
  *   idle   : line stays high
  *   start  : single low bit, one bit period long
  *   data   : `cfg.dataBits` bits, LSB first  (UART convention)
  *   parity : (optional) one bit; even/odd makes total 1-count even/odd
  *   stop   : `cfg.stopBits` high bits
  *   idle   : line returns high
  * }}}
  *
  * ==Parity==
  *
  * Implemented as an FSM-local accumulator. On entry to `dataState`,
  * `parityBit` is seeded to `False` for Even or `True` for Odd. On each Data
  * tick (including the final one), the current data bit is XOR'd into
  * `parityBit`. By the time the FSM hands over to `parityState`, `parityBit`
  * holds the bit to transmit: `xor(data)` for Even, `~xor(data)` for Odd. For
  * `ParityType.None` the seed and accumulate are elided at elaboration time and
  * `parityState` is unreachable.
  *
  * ==Timing contract with the rest of UartTx==
  *
  * The [[BaudGenerator]] must be quiescent (no ticks) while we are in
  * `idleState`. The standard wiring `baud.io.enable := fsm.io.busy` guarantees
  * this. The reason is the start bit: with the baud generator disabled in Idle,
  * its phase accumulator sits at zero. Once `busy` rises (the cycle the FSM
  * enters `startState`), the accumulator starts climbing and the *first* tick
  * lands almost exactly one bit period later — so the start bit is one full bit
  * period wide. If you ever pre-enable the baud generator, the start bit can be
  * anywhere from 1 to ticksPerBit clocks wide depending on accumulator phase.
  * Don't.
  *
  * ==The classic off-by-one==
  *
  * The bit-counting boundary is the easiest place in any UART TX to be off by
  * one. We count *ticks within Data* rather than *bits transmitted*:
  *
  *   - On entry to Data, the LSB is exposed on `shiftRegBit` (the wrapper
  *     pulsed `loadReg` two states ago, latching the byte into [[TxShiftReg]]).
  *   - That bit lives on the wire from the cycle after Data entry until the
  *     first tick. On each tick we pulse `shiftReg` to expose the next bit and
  *     bump `bitCounter`.
  *   - On the tick where `bitCounter === dataBits - 1` (i.e. we've already
  *     exposed `dataBits - 1` bits and are sitting on the last one), we
  *     transition to Stop *without* shifting again — the shift register won't
  *     be sampled until the next `loadReg` anyway, so suppressing the dangling
  *     shift just keeps waveforms tidy.
  *
  * Net effect: exactly `dataBits` bit periods elapse in Data — one per data
  * bit. [[TxFsmSim]] verifies this by sampling at the middle of each bit period
  * (which is also how a real UART RX recovers data).
  *
  * ==Why `txBit` is registered, and why every state drives it from
 `whenIsActive` (not `onEntry`)==
  *
  * `txBit` is driven from a register (`txReg`), not a combinational mux over
  * state and `shiftRegBit`. Either would be functionally correct, but the
  * register makes the output wave clean (one transition per bit boundary, no
  * glitches across state-change combinational paths) and keeps timing slack on
  * this output trivially easy.
  *
  * The non-obvious choice is *where* in each state to drive `txReg`. An earlier
  * revision used `onEntry` blocks for Start and Stop and `whenIsActive` for
  * Data:
  *
  *   - SpinalHDL `onEntry` assignments commit at the *same* clock edge as the
  *     state transition itself.
  *   - `whenIsActive` assignments commit one edge later (they only run once the
  *     state register has updated).
  *
  * Mixing those gives uneven bit periods. Specifically, the Stop `onEntry`'s
  * `txReg := True` commits at the same edge as `goto(stopState)`, beating
  * Data's `whenIsActive` `txReg := bit7` — so the last data bit ends up only
  * `ticksPerBit - 1` cycles wide, while the start bit (set by `onEntry`, also
  * "instant") is `ticksPerBit + 1` cycles wide. Frame width sums to the correct
  * value, but the bit boundaries are skewed by one cycle (~10% per-bit timing
  * error at ticksPerBit=10; ~1% at the realistic 12 MHz / 115200 ratio — within
  * the ~3% tolerance of any sane UART RX, but still wrong).
  *
  * The fix used here: every state drives `txReg` from its `whenIsActive` block.
  * That gives every state the same 1-cycle pipeline delay between "FSM enters
  * this state" and "the line reflects this state's bit value". All bit periods
  * come out exactly `ticksPerBit` cycles wide.
  *
  * ==Sub-blocks called out in TODO.md==
  *
  *   - Parity: implemented (Even / Odd / None, see "Parity" above).
  *   - `cfg.stopBits` is honoured (1 or 2), enforced by `UartConfig`.
  *   - The wrapper-level Stream/CTS handshake is `UartTx`'s problem, not ours.
  */
case class TxFsm(cfg: UartConfig) extends Component {
  val io = new Bundle {

    /** Begin-frame request, **level**-sensitive. Sampled on every cycle while
      * the FSM is in Idle. The wrapper is responsible for dropping it once the
      * frame is accepted (typically by ANDing it with `!busy`), so the FSM
      * consumes it for exactly one cycle and then transitions out. Holding it
      * high across multiple frames is also fine — Idle is re-entered with
      * `start` already high, and the next frame begins immediately on the next
      * cycle.
      *
      * (An earlier revision used `io.start.rise()` here. That worked only
      * because the wrapper's `!busy` gate guaranteed `start` was low whenever
      * Idle was re-entered. A unit test that drives `start` directly without
      * the gate — e.g. a level-held signal — would deadlock after the first
      * frame because `rise` would never see a low→high transition again.
      * Level-sensitive is strictly more general and equivalent for the
      * wrapper's usage.)
      */
    val start = in Bool ()

    /** One-cycle pulse from the [[BaudGenerator]] marking a bit-period
      * boundary. The FSM advances on every tick: Start → Data, each Data bit,
      * each Stop bit, Stop → Idle.
      */
    val tick = in Bool ()

    /** Combinational bit 0 of the [[TxShiftReg]] — i.e. the bit that should
      * currently be on the wire while we are in Data. Stable between `shiftReg`
      * pulses by contract of the shift register block.
      */
    val shiftRegBit = in Bool ()

    /** High whenever the FSM is **not** in Idle. The wrapper ties this to the
      * BaudGenerator's `enable` (so ticks happen only during a frame — see the
      * timing contract above) and to the *inverse* of the input Stream's
      * `ready` (so the producer is back-pressured for the duration of a frame).
      *
      * Computed combinationally from the FSM's state register
      * (`!isActive(idleState)`) rather than maintained as a per-state default.
      * That makes the invariant explicit and immune to "I added a new state and
      * forgot to set `busy`" bugs.
      */
    val busy = out Bool ()

    /** One-cycle pulse the cycle the FSM accepts a frame. Wraps to
      * [[TxShiftReg]]'s `load` so the byte on the data Stream is latched into
      * the shift register the same cycle the Stream handshake completes.
      */
    val loadReg = out Bool ()

    /** One-cycle pulse on each Data-state tick *except* the last, advancing the
      * shift register so the next bit is exposed on `shiftRegBit` for the
      * following bit period. Deliberately not pulsed on the final data tick —
      * the shift register is not sampled again until the next `loadReg`, so an
      * extra shift would be harmless, but suppressing it keeps the waveform
      * tidy and the intent obvious.
      */
    val shiftReg = out Bool ()

    /** Registered serial bit going to the wire. High in Idle and Stop, low in
      * Start, the current `shiftRegBit` in Data.
      */
    val txBit = out Bool ()
  }

  // --------------------------------------------------------------------------
  // Output / state registers
  // --------------------------------------------------------------------------

  // Init High to match the UART idle convention so the line reads as
  // idle from the very first cycle after reset, before any frame has
  // been requested. Idle's whenIsActive re-asserts this every cycle the
  // FSM is in Idle; the init value only matters for the few cycles
  // between reset release and the first `start` pulse (during which
  // we are in Idle but whenIsActive's assignment hasn't yet propagated
  // through the register pipeline).
  val txReg = Reg(Bool()) init (True)
  io.txBit := txReg

  // Counts data bits already shifted out in Data. Width is one bit
  // wider than strictly necessary so the comparison
  // `bitCounter === dataBits - 1` reads naturally and the +1 increment
  // can never wrap surprisingly. Reset on entry to Data only — its
  // Idle / Start / Stop values are don't-care.
  val bitCounter = Reg(UInt(log2Up(cfg.dataBits + 1) bits)) init (0)

  // Counts stop bits already emitted in Stop. Hoisted to FSM scope
  // (rather than declared inside the State block, which is also legal
  // SpinalHDL but unusual) so it sits next to `bitCounter` and shows
  // up in the same place in the generated Verilog. Reset on entry to
  // Stop.
  val stopCounter = Reg(UInt(log2Up(cfg.stopBits + 1) bits)) init (0)

  // Parity bit driven on the line during `parityState`. Always
  // declared so the FSM elaborates uniformly across all `cfg.parity`
  // values; for `ParityType.None` the register is unused and gets
  // optimised away by synthesis.
  val parityBit = Reg(Bool()) init (False) allowUnsetRegToAvoidLatch

  // --------------------------------------------------------------------------
  // Combinational defaults
  //
  // Pulses default low; each state explicitly raises them when needed.
  // No "default-True for busy" trickery — `busy` is computed from the
  // state register below.
  // --------------------------------------------------------------------------

  io.loadReg := False
  io.shiftReg := False

  // --------------------------------------------------------------------------
  // The FSM
  //
  // Convention: every state drives `txReg` from its `whenIsActive`
  // block, never `onEntry`. See the top-of-file comment for why —
  // mixing the two gives uneven bit periods.
  // --------------------------------------------------------------------------

  val fsm = new StateMachine {

    // ---------------- Idle --------------------------------------------------
    val idleState: State = new State with EntryPoint {
      whenIsActive {
        // Idle line is high. Drive it every cycle so we don't depend
        // on the register's init value past the first few cycles.
        txReg := True

        when(io.start) {
          io.loadReg := True
          goto(startState)
        }
      }
    }

    // ---------------- Start -------------------------------------------------
    val startState: State = new State {
      whenIsActive {
        // Drive the start bit low. Because this is a `whenIsActive`
        // assignment (not `onEntry`), it commits one cycle after the
        // state transition — which is the same 1-cycle pipeline delay
        // every other bit boundary has. Net result: start bit is
        // exactly ticksPerBit cycles wide on the wire.
        txReg := False

        when(io.tick) {
          goto(dataState)
        }
      }
    }

    // ---------------- Data --------------------------------------------------
    val dataState: State = new State {
      onEntry {
        bitCounter := 0

        // Reset the parity accumulator so it starts clean each frame.
        // Only needed (and only emitted) when parity is enabled — for
        // ParityType.None this whole block is elided at elaboration
        // time and `parityBit` is left to be optimised away.
        if (cfg.parity != ParityType.None) {
          parityBit := Bool(cfg.parity == ParityType.Odd)
        }
      }

      whenIsActive {
        // Continuously latch the current shift-reg LSB into `txReg`.
        // On non-tick cycles this is a no-op (shiftRegBit hasn't
        // moved). On the cycle right *after* a `shiftReg` pulse, the
        // shift register has rotated and `shiftRegBit` is the new
        // LSB — this assignment picks it up and the line updates for
        // the next bit period.
        txReg := io.shiftRegBit

        when(io.tick) {
          if (cfg.parity != ParityType.None) {
            parityBit := parityBit ^ io.shiftRegBit
          }

          when(bitCounter === cfg.dataBits - 1) {
            // Final data tick. Don't pulse `shiftReg` (the register
            // isn't sampled again until the next `loadReg`, so an
            // extra shift would be harmless but waveform-noisy) and
            // don't bump the counter (it's about to be reset on the
            // next Data entry anyway). Hand over to Parity if it's
            // enabled, otherwise straight to Stop.
            //
            // The choice is elaboration-time: for ParityType.None
            // the `goto(parityState)` arm doesn't exist in the
            // generated Verilog at all, so the parity state ends up
            // unreachable and is optimised away.
            if (cfg.parity == ParityType.None) {
              goto(stopState)
            } else {
              goto(parityState)
            }
          } otherwise {
            io.shiftReg := True
            bitCounter := bitCounter + 1
          }
        }
      }
    }

    // ---------------- Parity ------------------------------------------------
    //
    // Always declared (keeps the FSM elaboration uniform). Only
    // entered when `cfg.parity != ParityType.None` — see the
    // elaboration-time `if` in `dataState`'s final-tick branch. For
    // `None` the state is unreachable and synthesises away.
    //
    // `parityBit` was seeded on Data entry and accumulated on each
    // Data tick, so by the time we get here it already holds the bit
    // to transmit. We just drive it onto `txReg` from `whenIsActive`
    // (same 1-cycle pipeline rationale as every other state) and
    // hand over to Stop on the next tick.
    val parityState: State = new State {
      whenIsActive {
        txReg := parityBit
        when(io.tick) {
          goto(stopState)
        }
      }
    }

    // ---------------- Stop --------------------------------------------------
    val stopState: State = new State {
      onEntry {
        stopCounter := 0
      }

      whenIsActive {
        // Drive the stop bit high. Same `whenIsActive` rationale as
        // the other states: keeps the bit boundary aligned with the
        // 1-cycle pipeline.
        txReg := True

        when(io.tick) {
          when(stopCounter === cfg.stopBits - 1) {
            goto(idleState)
          } otherwise {
            stopCounter := stopCounter + 1
          }
        }
      }
    }
  }

  // `busy` is "anything that isn't Idle". Defining it after the FSM
  // lets us reference `fsm.idleState` by name. Using a single
  // `!isActive(idleState)` instead of per-state assignments makes the
  // invariant explicit and removes the chance of a future new state
  // accidentally leaving `busy` low.
  io.busy := !fsm.isActive(fsm.idleState)
}
