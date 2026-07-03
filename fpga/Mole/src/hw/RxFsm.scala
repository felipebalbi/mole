// SPDX-FileCopyrightText: 2026 Felipe Balbi
// SPDX-License-Identifier: CERN-OHL-W-2.0

package mole

// Imported from felipebalbi/icebreaker-spinalhdl-examples@98c06a8cf39f6c212d3b46d98868aca398a82186 Uart/src/hw/RxFsm.scala.
// Modified for Mole only as documented inline (search for 'Mole').

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

/** UART receive frame sequencer (the "control" half of the RX path).
  *
  * Mirror of [[TxFsm]]: drives the receive path through `Idle → StartVerify →
  * Data×N → [Parity] → Stop×M → Idle` and emits the assembled byte on a
  * [[Stream]] when a frame completes cleanly. Composes a [[RxShiftReg]] for the
  * actual bit accumulation; this FSM owns timing and sequencing only.
  *
  * ==Frame on the wire==
  *
  * UART RX is the inverse of TX. The line idles high; a frame is bracketed by a
  * low start bit and one or two high stop bits, with `cfg.dataBits` data bits
  * LSB-first in between (and an optional parity bit before the stop bits when
  * `cfg.parity != ParityType.None`).
  * {{{
  *   idle   : line stays high
  *   start  : single low bit, one bit period long
  *   data   : `cfg.dataBits` bits, LSB first
  *   parity : (optional) one bit
  *   stop   : `cfg.stopBits` high bits
  *   idle   : line returns high
  * }}}
  *
  * ==Why oversampling==
  *
  * The transmitter and receiver have independent clocks. We have no way to
  * recover the sender's bit-clock phase exactly — we can only detect the
  * falling edge of the start bit and then sample at what we *think* is the
  * middle of each subsequent bit. To keep the sample point inside the bit
  * window despite ±2–3% baud skew between the two ends and despite a noisy
  * start-bit edge, we use 16× oversampling: the [[BaudGenerator]] (wired to the
  * wrapper, not to this FSM directly) produces a tick at `oversample ×
  * baudRate`, and this FSM counts those ticks to land samples at bit centres.
  *
  * The standard trick for "land at bit centre after a noisy edge" is the
  * **half-bit verify**: when we see a falling edge in IDLE, we don't just call
  * it a start bit and proceed. Instead we wait `oversample/2` ticks (half a bit
  * period) and resample; only if the line is *still* low do we commit to the
  * frame. A 1-cycle line glitch (induction crosstalk, ESD, crap on the cable)
  * is rejected — without this check, every glitch on an idle line would corrupt
  * a frame.
  *
  * After the half-bit verify the FSM samples every `oversample` ticks. The
  * first such sample lands at the centre of bit 0 (the LSB), and the subsequent
  * samples land at the centres of bits 1, 2, …, dataBits−1, then (optionally)
  * parity, then stop. End-to-end, samples land at:
  * {{{
  *   start (verify only)  ─┐
  *   bit 0 centre          │ +oversample
  *   bit 1 centre          │ +oversample
  *   ...                   │
  *   bit N-1 centre        │
  *   parity centre (opt.)  │
  *   stop bit centre       ▼
  * }}}
  *
  * ==Tick contract — different from TX!==
  *
  * The [[BaudGenerator]] feeding `io.tick` runs at `oversample × baudRate` and
  * **must run continuously**, not gated by `busy`. Reason: we have to detect a
  * start-bit falling edge while the FSM is in `idleState`, and the half-bit
  * verify needs ticks immediately after the edge — there is no "enable on busy"
  * hook that could ramp the generator up in time. A free-running RX baud
  * generator costs nothing measurable in power but gives us a consistent
  * sampling phase reference at all times.
  *
  * (Contrast with TX: the TX baud generator IS gated by `busy`, because the
  * start bit's width depends on phase being zero at the start.)
  *
  * ==Composition with RxShiftReg==
  *
  * We instantiate [[RxShiftReg]] internally rather than carrying a private
  * shift register, mirroring the [[TxFsm]] / [[TxShiftReg]] / [[UartTx]] split:
  *   - On entry to `dataState`, pulse `rsr.io.clear := True` for one cycle so
  *     the shifter starts each frame at zero. Doing this on `dataState.onEntry`
  *     (rather than `idleState → startVerifyState`) means a rejected glitch
  *     doesn't cost us a clear pulse — only confirmed start bits do.
  *   - On each oversample-tick boundary inside `dataState`, pulse `rsr.io.shift
  *     := True`. `rsr.io.sample` is wired to `io.rx` continuously, so the bit
  *     shifted in is whatever the line shows at that edge (which, by
  *     construction, is at the centre of a data bit).
  *   - The assembled byte is exposed on `rsr.io.data`, which we wire straight
  *     to `io.payload.payload`.
  *
  * RxShiftReg's `clear-wins-over-shift` priority means the dataState entry
  * cycle (which only raises `clear`) is unambiguous, and the FSM never raises
  * both in the same cycle anyway.
  *
  * ==Error semantics==
  *
  *   - `framingError`: pulsed if the stop bit reads low (the frame's framing is
  *     broken — usually means a baud-rate mismatch or a mid-frame
  *     disconnection).
  *   - `parityError`: pulsed if the received parity bit doesn't match the
  *     expected value computed from the data bits. When `cfg.parity ==
  *     ParityType.None` the parity state is unreachable and this signal never
  *     fires.
  *   - `overrun`: pulsed if a frame completes (stop bit verified) while a
  *     previous frame's `payloadValidReg` is still high — the consumer hasn't
  *     fired the Stream handshake yet, so we have nowhere to put the new byte.
  *
  * All three are *one-cycle combinational pulses*: they go high in the exact
  * cycle the FSM detects the condition (the same cycle as the `goto(idleState)`
  * for framing/parity, or the cycle the new frame's stop bit verifies for
  * overrun). They fall back to `False` the next cycle on their own — there is
  * no register stage and no idle-state clear. By contrast, `valid` is
  * sticky-until-fire. A consumer that needs to observe an error must either
  * latch it on the same cycle (e.g. by sampling alongside `valid`) or push it
  * into a sticky upstream register such as the regif RC field in
  * `UartController`.
  *
  * ==Parity==
  *
  * Same accumulator pattern as [[TxFsm]]: seed `parityBit` to `False` for Even
  * or `True` for Odd on entry to `dataState`, XOR each received data bit into
  * it on each Data tick. By the time we reach `parityState`, `parityBit` holds
  * the value the *received* parity bit should equal — `xor(data)` for Even,
  * `~xor(data)` for Odd — and we just compare it to the line.
  *
  * ==Sub-blocks called out in TODO.md==
  *
  *   - Glitch rejection: implemented via the half-bit verify in
  *     `startVerifyState`.
  *   - Framing / parity / overrun flags: all wired up.
  *   - `cfg.stopBits` honoured (1 or 2).
  *   - The wrapper-level Stream handshake is `UartRx`'s problem, not ours.
  */
case class RxFsm(cfg: UartConfig) extends Component {
  val io = new Bundle {

    /** Synchronised RX line — i.e. the output of [[RxSync]], in our clock
      * domain. Idles high; falls low to mark a start bit. The FSM samples this
      * on every relevant `io.tick` boundary; for everything else it only cares
      * about `io.rx.fall` (used to detect start-bit edges in `idleState`).
      */
    val rx = in Bool ()

    /** Free-running oversample-rate strobe from the [[BaudGenerator]] — one
      * pulse per `clkFreqHz / (baudRate * cfg.oversample)` cycles. Unlike the
      * TX side, this generator is NOT gated by `busy`; the half-bit verify
      * after a start-bit edge needs ticks immediately, and the FSM has no way
      * to enable the generator early enough.
      */
    val tick = in Bool ()

    /** Received-byte output, ready/valid handshake. The FSM raises `valid` on
      * the cycle the stop bit is verified (and never raises it in the cycle of
      * a framing or parity error). `payload` is the assembled byte, LSB-first
      * in bit 0. The handshake is driven by the consumer firing `ready` while
      * `valid` is high; that cycle clears the internal `payloadValidReg`.
      */
    val payload = master Stream (Bits(cfg.dataBits bits))

    /** One-cycle pulse the cycle a frame ends with the stop bit observed low.
      * The frame is dropped (no `valid` pulse). Slow consumers should latch
      * this into a sticky register — `UartController` does so via its regif RC
      * field.
      */
    val framingError = out Bool ()

    /** One-cycle pulse the cycle a frame ends with the parity bit mismatched
      * against the accumulated XOR-of-data. The frame is dropped (no `valid`
      * pulse). Only ever fires when `cfg.parity != ParityType.None`. Latch as
      * for `framingError`.
      */
    val parityError = out Bool ()

    /** One-cycle pulse the cycle a fresh frame's stop bit verifies while a
      * previous frame's payload has not yet been consumed. The new byte is
      * emitted on `valid` (and the previous one is lost — see the review notes
      * in TODO.md). Latch as for `framingError`.
      */
    val overrun = out Bool ()

    /** High whenever the FSM is **not** in Idle. Diagnostic — the wrapper may
      * use it to drive an LED or to gate `RTS` low when a frame is in flight.
      */
    val busy = out Bool ()
  }

  // --------------------------------------------------------------------------
  // Error / status flags.
  //
  // Combinational one-cycle pulses, NOT registers. Default to False
  // here; each error's detection arm in the FSM overrides with True
  // for that one cycle. Sticky behaviour is the consumer's job —
  // UartController's regif RC fields latch them with `.set()`.
  //
  // Earlier versions of this file used `Reg(Bool())` plus an
  // idleState-driven clear. That defeated W1C/RC semantics upstream:
  // a fresh error during the held-high window was masked because the
  // level was already high. Pulses fix that.
  // --------------------------------------------------------------------------
  val framingError = False
  val parityError = False
  val overrun = False

  // --------------------------------------------------------------------------
  // Timing counters.
  //
  // Ticks here means oversample-rate ticks from `io.tick`, NOT system
  // clock cycles.
  // --------------------------------------------------------------------------

  // Counts ticks during the half-bit verify in startVerifyState.
  // Sized to hold values 0..(oversample/2 - 1).
  val halfBitCounter = Reg(UInt(log2Up(cfg.oversample / 2) bits)) init (0)

  // Counts ticks during a single bit period in dataState / parityState
  // / stopState. Sized to hold values 0..(oversample - 1).
  val fullBitCounter = Reg(UInt(log2Up(cfg.oversample) bits)) init (0)

  // Counts data bits already shifted in during dataState. Sized to hold
  // values 0..(dataBits - 1).
  val bitCounter = Reg(UInt(log2Up(cfg.dataBits) bits)) init (0)

  // Counts stop bits already verified during stopState. Sized to hold
  // values 0..(stopBits - 1); +1 in log2Up so stopBits=1 still gets a
  // legal width.
  val stopCounter = Reg(UInt(log2Up(cfg.stopBits + 1) bits)) init (0)

  // --------------------------------------------------------------------------
  // RxShiftReg sub-block.
  //
  // The FSM produces `clear` (once per frame, on dataState entry) and
  // `shift` (once per oversample-bit boundary in dataState) pulses,
  // and exposes the line as `sample` continuously. RxShiftReg
  // accumulates the bits LSB-first and exposes the assembled byte on
  // its `data` port.
  //
  // Defaulting `clear` and `shift` to False at module scope means any
  // FSM state that doesn't explicitly drive them contributes a hold
  // cycle — no spurious activity outside dataState.
  // --------------------------------------------------------------------------
  val rsr = RxShiftReg(cfg)
  rsr.io.sample := io.rx
  rsr.io.clear := False
  rsr.io.shift := False

  // --------------------------------------------------------------------------
  // Parity accumulator.
  //
  // Seeded on entry to dataState (False for Even, True for Odd), XORed
  // with each incoming data bit on each Data tick. By the time we
  // enter parityState, `parityBit` holds the expected value of the
  // received parity bit — comparing equal means parity is OK.
  //
  // `allowUnsetRegToAvoidLatch` because `cfg.parity == None` elides
  // the seed and accumulate at elaboration time, leaving the register
  // unwritten — which would otherwise be a latch-inference warning.
  // --------------------------------------------------------------------------
  val parityBit = Reg(Bool()) init (False) allowUnsetRegToAvoidLatch

  // --------------------------------------------------------------------------
  // Stream-valid register.
  //
  // Sticky: set by the FSM on a clean stop, cleared by `io.payload.fire`.
  // (Asymmetric with the error pulses above — see Scaladoc.)
  // --------------------------------------------------------------------------
  val payloadValidReg = Reg(Bool()) init (False)

  // --------------------------------------------------------------------------
  // The FSM
  // --------------------------------------------------------------------------

  val fsm = new StateMachine {

    // ---------------- Idle --------------------------------------------------
    val idleState: State = new State with EntryPoint {
      whenIsActive {
        // Edge detect: SpinalHDL's `.fall` is a one-cycle high signal
        // when `io.rx` was high last cycle and is low this cycle.
        // io.rx is already in our clock domain (it's the output of
        // RxSync), so `.fall` is glitch-safe wrt metastability — a
        // raw async input would not be.
        when(io.rx.fall) {
          goto(startVerifyState)
        }
      }
    }

    // ---------------- StartVerify -------------------------------------------
    //
    // Wait half a bit period and resample. If the line is still low,
    // commit to the frame. If it has gone back high, treat the
    // original edge as a glitch and return to idle.
    //
    // The ½-bit wait also has the side effect of placing all subsequent
    // sample points at bit centres — see the Scaladoc.
    val startVerifyState: State = new State {
      onEntry {
        halfBitCounter := 0
      }

      whenIsActive {
        when(io.tick) {
          when(halfBitCounter === (cfg.oversample / 2) - 1) {
            // Re-sample at bit centre.
            when(!io.rx) {
              // Real start bit. Subsequent ticks land at bit centres
              // of the data bits.
              goto(dataState)
            } otherwise {
              // Line went back high — original fall was a glitch.
              // Return to idle WITHOUT pulsing the shifter's clear
              // (we never entered dataState, so the shifter wasn't
              // touched).
              goto(idleState)
            }
          } otherwise {
            halfBitCounter := halfBitCounter + 1
          }
        }
      }
    }

    // ---------------- Data --------------------------------------------------
    //
    // Sample one data bit every `oversample` ticks. Counter
    // arithmetic: we enter at fullBitCounter=0 and trigger on
    // `=== oversample - 1`, so the first shift fires `oversample`
    // ticks after entry — i.e. at the centre of bit 0.
    val dataState: State = new State {
      onEntry {
        fullBitCounter := 0
        bitCounter := 0

        // Pulse the shifter's clear once at frame start so we begin
        // accumulating into a known-zero register. We do this here
        // (not on idle→startVerify) so a rejected glitch in
        // startVerifyState doesn't waste a clear pulse — only
        // confirmed start bits cost a clear. RxShiftReg's
        // "clear-wins-over-shift" priority makes this safe even if
        // some future revision overlaps the two pulses; in this
        // implementation `shift` is never raised during dataState's
        // entry cycle.
        rsr.io.clear := True

        // Seed the parity accumulator. Skipped at elaboration time
        // for ParityType.None (along with the parityState transition
        // below), so the register and the parityState end up
        // optimised away when parity is disabled.
        if (cfg.parity != ParityType.None) {
          parityBit := Bool(cfg.parity == ParityType.Odd)
        }
      }

      whenIsActive {
        when(io.tick) {
          when(fullBitCounter === cfg.oversample - 1) {
            fullBitCounter := 0

            // Accumulate parity from the bit we're about to shift in
            // (parity-state will compare against this final value).
            if (cfg.parity != ParityType.None) {
              parityBit := parityBit ^ io.rx
            }

            // Drive the shifter rather than maintaining our own
            // reg. RxShiftReg performs the LSB-first shift-in
            // internally. `rsr.io.sample` is `io.rx`, so the bit
            // shifted in is the line value at this exact edge.
            rsr.io.shift := True

            when(bitCounter === cfg.dataBits - 1) {
              // Last data bit — hand over to parity (if enabled) or
              // straight to stop. Elaboration-time `if` lets the
              // parityState arm of the FSM elide entirely for
              // ParityType.None.
              if (cfg.parity == ParityType.None) {
                goto(stopState)
              } else {
                goto(parityState)
              }
            } otherwise {
              bitCounter := bitCounter + 1
            }
          } otherwise {
            fullBitCounter := fullBitCounter + 1
          }
        }
      }
    }

    // ---------------- Parity ------------------------------------------------
    //
    // One sample at the centre of the parity bit. If the line value
    // matches the accumulated `parityBit`, the frame is OK and we
    // move to stop verification. If not, raise `parityError` and
    // drop the frame.
    //
    // Always declared so the FSM elaborates uniformly across all
    // `cfg.parity` values; for `None` the elaboration-time guard in
    // dataState's last-tick branch means this state is unreachable
    // and synthesises away.
    val parityState: State = new State {
      onEntry {
        fullBitCounter := 0
      }

      whenIsActive {
        when(io.tick) {
          when(fullBitCounter === cfg.oversample - 1) {
            fullBitCounter := 0

            when(parityBit === io.rx) {
              goto(stopState)
            } otherwise {
              // Parity mismatch — flag it, drop the frame, return
              // to idle. payloadValidReg is left untouched: this
              // frame's bytes are lost.
              parityError := True
              goto(idleState)
            }
          } otherwise {
            fullBitCounter := fullBitCounter + 1
          }
        }
      }
    }

    // ---------------- Stop --------------------------------------------------
    //
    // Verify `cfg.stopBits` bits at the high level, one at a time.
    // The first low we see in this state is a framing error. If all
    // stop bits verify high, the frame is good and we hand the byte
    // off (or raise `overrun` if the previous one is still pending).
    val stopState: State = new State {
      onEntry {
        fullBitCounter := 0
        stopCounter := 0
      }

      whenIsActive {
        when(io.tick) {
          when(fullBitCounter === cfg.oversample - 1) {

            fullBitCounter := 0

            when(!io.rx) {
              // Stop bit was low — framing is broken. Flag, drop,
              // return to idle. As with parity errors, no `valid`
              // pulse for this frame.
              framingError := True
              goto(idleState)

            } otherwise {

              when(stopCounter === cfg.stopBits - 1) {

                // All stop bits verified high.
                when(payloadValidReg) {
                  // Previous payload still pending — overrun. The
                  // new byte's data is sitting in `rsr.io.data`
                  // already; valid stays high (sticky) so the
                  // consumer still sees the new payload, but
                  // tagged with the carried-over valid. (See
                  // TODO.md review notes — current behaviour
                  // differs from the more conventional "drop new,
                  // keep old".)
                  overrun := True
                } otherwise {
                  // Normal completion — raise valid for the new
                  // byte. The consumer fires the handshake to
                  // clear it.
                  payloadValidReg := True
                }

                goto(idleState)

              } otherwise {
                stopCounter := stopCounter + 1
              }
            }

          } otherwise {
            fullBitCounter := fullBitCounter + 1
          }
        }
      }
    }
  }

  // --------------------------------------------------------------------------
  // Stream handshake: any cycle the consumer fires, drop validReg.
  // --------------------------------------------------------------------------
  when(io.payload.fire) {
    payloadValidReg := False
  }

  io.payload.payload := rsr.io.data
  io.payload.valid := payloadValidReg
  io.framingError := framingError
  io.parityError := parityError
  io.overrun := overrun

  // `busy` is "anything that isn't Idle". Same idiom as TxFsm —
  // single source of truth, immune to "I added a state and forgot
  // to set busy" bugs.
  io.busy := !fsm.isActive(fsm.idleState)
}
