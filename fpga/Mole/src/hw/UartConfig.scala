package mole

// Imported from felipebalbi/icebreaker-spinalhdl-examples@98c06a8cf39f6c212d3b46d98868aca398a82186 Uart/src/hw/UartConfig.scala.
// Modified for Mole only as documented inline (search for 'Mole').

import spinal.core._

/** Parity scheme for a UART frame.
  *
  *   - `None` : no parity bit transmitted (default; the "N" in 8N1).
  *   - `Even` : parity bit set so that the *total* count of 1s in (data +
  *     parity) is even.
  *   - `Odd` : parity bit set so that the *total* count of 1s in (data +
  *     parity) is odd.
  */
object ParityType extends SpinalEnum {
  val None, Even, Odd = newElement()
}

/** Compile-time configuration for the UART transmitter and receiver.
  *
  * Held as a `case class` so it can be passed by value into every sub-block
  * (BaudGenerator, FSM, shift register, etc.) and used to derive widths /
  * constants at elaboration time. Nothing in this class survives into hardware
  * — it only shapes how the hardware is built.
  *
  * Both halves of the link share one config so a single instance can drive a
  * symmetric TX + RX pair without risk of one side being built for, say, 8N1
  * while the other is built for 8E2.
  *
  * @param clkFreqHz
  *   System clock frequency in Hz. Used to size the DDS phase increment so that
  *   ticks land at the requested baud (TX) or at the oversample rate (RX).
  * @param baudRate
  *   Target line rate (bits per second).
  * @param dataBits
  *   Number of data bits per frame (5..9 in the wild; 8 is by far the most
  *   common — the "8" in 8N1).
  * @param stopBits
  *   Number of stop bits (1 or 2). Stop bits are just extra high-level idle
  *   time at the end of a frame.
  * @param oversample
  *   RX-only: how many times per bit period the receiver samples the line. 16×
  *   is the industry standard — high enough to tolerate ±2–3% baud skew between
  *   the two ends and to land samples close to the bit centre even after a
  *   noisy start-bit edge, low enough to keep the BaudGenerator divider
  *   (`clkFreqHz / (baudRate * oversample)`) coarse enough to fit. Ignored by
  *   the TX path.
  * @param parity
  *   Parity scheme — see [[ParityType]]. On TX, drives both the parity bit on
  *   the wire and whether a `parityState` exists in the FSM at all. On RX,
  *   drives whether a parity sample is taken and compared.
  * @param useCts
  *   TX-side flow control. If `true`, expose a `cts` *input* pin and gate the
  *   start of each frame on it being high (active-high "the far end can
  *   receive"). If `false` (Mole default), the `cts` port is omitted entirely
  *   and frames start as soon as a byte is offered. Mole flips the upstream
  *   default to `false` because the host-link UART runs over an FT2232H with no
  *   flow-control pins wired through.
  * @param useRts
  *   RX-side flow control. If `true`, expose an `rts` *output* pin that we
  *   drive high while we can accept more data and low when we cannot
  *   (active-high "I can receive"). The far end's TX should gate on this. If
  *   `false` (Mole default), the `rts` port is omitted entirely and the
  *   receiver simply asserts `overrun` if a byte arrives while downstream isn't
  *   ready. Note the asymmetry with `useCts`: CTS gates *our* TX frame starts,
  *   RTS announces *our* RX readiness to the other side. Mole flips the
  *   upstream default to `false` for the same reason as `useCts`.
  *
  * NOTE FROM MOLE: the upstream `UartConfig` also carried `txFifoDepth` and
  * `rxFifoDepth` fields used exclusively by `UartController` (the Apb3 wrapper
  * Mole does not import). They are removed here. Mole's wrapper-level
  * back-pressure flows directly through the Stream interfaces exposed by
  * [[UartTx]] / [[UartRx]] and ultimately through the engine's result ring.
  */
case class UartConfig(
    clkFreqHz: Int = 12000000,
    baudRate: Int = 115200,
    dataBits: Int = 8,
    stopBits: Int = 1,
    parity: ParityType.E = ParityType.None,
    useCts: Boolean = false,
    oversample: Int = 16,
    useRts: Boolean = false
) {

  /** Average number of system-clock cycles per UART bit period.
    *
    * Used as a sanity check / reference value for sims and assertions. The DDS
    * BaudGenerator does not actually count to this number — it accumulates a
    * phase increment — but on average it ticks every `ticksPerBit` cycles.
    */
  val ticksPerBit: Int = clkFreqHz / baudRate

  require(ticksPerBit >= 1, "clkFreqHz must be >= baudRate")
  require(dataBits >= 5 && dataBits <= 9, "dataBits must be 5..9")
  require(stopBits == 1 || stopBits == 2, "stopBits must be 1 or 2")
  require(oversample >= 1, "oversample must be >= 1")
  // Mole-added: the RX-side BaudGenerator runs at `baudRate * oversample`, and
  // its 24-bit DDS phase increment is `round(baudRate * oversample * 2^24 /
  // clkFreqHz)`. If `baudRate * oversample >= clkFreqHz` the increment
  // saturates / overflows the field and the receiver silently misses bits.
  // Reject at elaboration so the configuration error surfaces early.
  require(
    baudRate.toLong * oversample < clkFreqHz,
    s"UartRx DDS needs baudRate*oversample (${baudRate.toLong * oversample}) < clkFreqHz ($clkFreqHz)"
  )
}
