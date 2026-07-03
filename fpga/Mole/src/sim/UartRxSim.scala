// SPDX-FileCopyrightText: 2026 Felipe Balbi
// SPDX-License-Identifier: CERN-OHL-W-2.0

package mole

// Imported from felipebalbi/icebreaker-spinalhdl-examples@98c06a8cf39f6c212d3b46d98868aca398a82186 Uart/src/sim/UartRxSim.scala.
// Modified for Mole only as documented inline (search for 'Mole').

import spinal.core._
import spinal.core.sim._

/** Standalone sim for [[UartRx]] — black-box test of the wrapper.
  *
  * `UartRx` is just composition (RxSync + BaudGenerator + RxFsm). The
  * sub-blocks are already covered by their own sims; the value of this sim is
  * exclusively in catching wiring mistakes between them.
  *
  * Strategy
  *   - Drive `io.rx` as a fake UART line, level-by-level, holding each bit for
  *     one full bit period (`clkFreqHz / baudRate` system clocks).
  *   - Watch `io.payload` for the assembled byte and the side-band flags for
  *     errors.
  *   - **No internal tick fork** — unlike [[RxFsmSim]] the BaudGenerator is now
  *     inside the DUT. We just feed it cycles by running the clock.
  *   - **No fake RxSync either** — the real one runs and adds 2 cycles of
  *     latency. We pad bit periods generously so this is invisible.
  *
  * What we verify
  *   1. **Post-reset idle.** No `valid`, no error flags. 2. **Byte sweep at
  *      8N1.** A handful of patterns chosen for shift-direction coverage (0x00,
  *      0xFF, 0xAA, 0x55, 0x80, 0x01, 0xAD). 3. **Config-matrix smoke.** Single
  *      byte through 8N2, 8E1, 8O1 to prove the parity/stop-bit knobs are
  *      plumbed all the way through the wrapper. 4. **Framing error.** Stop bit
  *      forced low — expect `framingError`, no `valid`. Then send a clean frame
  *      to verify recovery. 5. **Parity error.** Parity bit deliberately wrong
  *      — expect `parityError`, no `valid`. 6. **Overrun.** Two frames with
  *      `ready` held low — expect `overrun`, `valid` stays high throughout. 7.
  *      **`useRts` smoke.** When `cfg.useRts = true`, `io.rts` mirrors
  *      `io.payload.ready`.
  *
  * Important: every fork that drives `io.rx` is captured to a `val` and
  * `.join()`'d before the main thread writes `io.rx` again. This is the lesson
  * from `RxFsmSim` — two threads racing for the same DUT input is undefined
  * behaviour in SpinalSim.
  *
  * Run: `sbt "runMain mole.UartRxSim"`
  */
object UartRxSim {

  def runRxTest(cfg: UartConfig, patterns: Seq[Int]): Unit = {

    // Bit period in system clocks. The internal BaudGenerator sees
    // `cfg.copy(baudRate = baudRate * oversample)`, so for the FSM
    // bit period we still use the original `cfg.baudRate`.
    val bitClocks = cfg.clkFreqHz / cfg.baudRate

    val parityLabel = cfg.parity match {
      case ParityType.None => "N"
      case ParityType.Even => "E"
      case ParityType.Odd  => "O"
    }
    val rtsLabel = if (cfg.useRts) "+rts" else ""
    val cfgLabel = s"${cfg.dataBits}${parityLabel}${cfg.stopBits}$rtsLabel"

    SimConfig.withWave
      .compile(UartRx(cfg))
      .doSim(cfgLabel) { dut =>
        dut.clockDomain.forkStimulus(10)
        dut.io.baudPhaseInc #= BaudGenerator.phaseIncFor(
          cfg.clkFreqHz,
          cfg.baudRate * cfg.oversample,
          BaudGenerator.defaultAccWidth
        )

        // Defaults.
        dut.io.rx #= true
        dut.io.payload.ready #= false
        dut.clockDomain.waitSampling(20)

        // ------------------------------------------------------------------
        // Helpers (closures over `dut` and `cfg`).
        // ------------------------------------------------------------------

        def frameBits(
            byte: Int,
            parityOverride: Option[Boolean] = None,
            stopOverride: Option[Seq[Boolean]] = None
        ): Seq[Boolean] = {
          val mask = (1 << cfg.dataBits) - 1
          val masked = byte & mask
          val dataSeq =
            (0 until cfg.dataBits).map(i => ((masked >> i) & 1) == 1)
          val xorAll = dataSeq.foldLeft(false)(_ ^ _)
          val paritySeq: Seq[Boolean] = cfg.parity match {
            case ParityType.None => Seq.empty
            case ParityType.Even => Seq(parityOverride.getOrElse(xorAll))
            case ParityType.Odd  => Seq(parityOverride.getOrElse(!xorAll))
          }
          val stopSeq =
            stopOverride.getOrElse(Seq.fill(cfg.stopBits)(true))
          Seq(false) ++ dataSeq ++ paritySeq ++ stopSeq
        }

        def driveBits(bits: Seq[Boolean]): Unit = {
          for (b <- bits) {
            dut.io.rx #= b
            dut.clockDomain.waitSampling(bitClocks)
          }
        }

        def idleLine(bitsWide: Int = 2): Unit = {
          dut.io.rx #= true
          dut.clockDomain.waitSampling(bitClocks * bitsWide)
        }

        def waitFor(maxClocks: Int)(cond: => Boolean): Boolean = {
          var n = 0
          while (n < maxClocks && !cond) {
            dut.clockDomain.waitSampling()
            n += 1
          }
          cond
        }

        def expectPayload(
            maxClocks: Int = bitClocks * 30
        ): (Long, Boolean, Boolean, Boolean) = {
          dut.io.payload.ready #= true
          val ok = waitFor(maxClocks)(dut.io.payload.valid.toBoolean)
          assert(
            ok,
            s"[$cfgLabel] expected valid within $maxClocks clocks, never saw it"
          )
          val payload = dut.io.payload.payload.toLong
          val framing = dut.io.framingError.toBoolean
          val parity = dut.io.parityError.toBoolean
          val overrun = dut.io.overrun.toBoolean
          dut.clockDomain.waitSampling()
          dut.io.payload.ready #= false
          (payload, framing, parity, overrun)
        }

        val mask = (1L << cfg.dataBits) - 1L

        // ------------------------------------------------------------------
        // (1) Post-reset idle.
        // ------------------------------------------------------------------
        assert(
          !dut.io.payload.valid.toBoolean,
          s"[$cfgLabel] valid high at reset"
        )
        assert(
          !dut.io.framingError.toBoolean,
          s"[$cfgLabel] framingError high at reset"
        )
        assert(
          !dut.io.parityError.toBoolean,
          s"[$cfgLabel] parityError high at reset"
        )
        assert(!dut.io.overrun.toBoolean, s"[$cfgLabel] overrun high at reset")

        // ------------------------------------------------------------------
        // (2) Byte sweep.
        // ------------------------------------------------------------------
        for (p <- patterns) {
          val drv = fork {
            driveBits(frameBits(p))
            idleLine()
          }
          val (payload, fE, pE, oE) = expectPayload()
          assert(
            (payload & mask) == (p.toLong & mask),
            f"[$cfgLabel byte-sweep] expected 0x${p & mask.toInt}%X, got 0x${payload & mask}%X"
          )
          assert(
            !fE,
            f"[$cfgLabel byte-sweep] unexpected framingError on 0x$p%X"
          )
          assert(
            !pE,
            f"[$cfgLabel byte-sweep] unexpected parityError on 0x$p%X"
          )
          assert(!oE, f"[$cfgLabel byte-sweep] unexpected overrun on 0x$p%X")
          drv.join()
        }

        // ------------------------------------------------------------------
        // (3) Framing error: stop bit low.
        // ------------------------------------------------------------------
        idleLine(2)
        val framingByte = patterns.head
        val framingDrv = fork {
          driveBits(
            frameBits(
              framingByte,
              stopOverride = Some(Seq.fill(cfg.stopBits)(false))
            )
          )
          idleLine()
        }
        var sawFraming = false
        var sawValid = false
        val framingDeadline = bitClocks * 40
        var f = 0
        dut.io.payload.ready #= false
        while (f < framingDeadline && !sawFraming) {
          dut.clockDomain.waitSampling()
          if (dut.io.framingError.toBoolean) sawFraming = true
          if (dut.io.payload.valid.toBoolean) sawValid = true
          f += 1
        }
        assert(
          sawFraming,
          s"[$cfgLabel] framing error: expected framingError pulse"
        )
        assert(
          !sawValid,
          s"[$cfgLabel] framing error: valid raised despite failure"
        )
        framingDrv.join()

        // (3b) Recover with a clean frame.
        idleLine(4)
        val recoveryDrv = fork {
          driveBits(frameBits(patterns.head))
          idleLine()
        }
        val (rec, fE2, pE2, oE2) = expectPayload()
        assert(
          (rec & mask) == (patterns.head.toLong & mask),
          s"[$cfgLabel] recovery after framing: bad payload"
        )
        assert(
          !fE2 && !pE2 && !oE2,
          s"[$cfgLabel] recovery after framing: spurious flags"
        )
        recoveryDrv.join()

        // ------------------------------------------------------------------
        // (4) Parity error (only when parity is enabled).
        // ------------------------------------------------------------------
        if (cfg.parity != ParityType.None) {
          idleLine(2)
          val parityByte = patterns.head
          val maskedP = parityByte & ((1 << cfg.dataBits) - 1)
          val xorAll = (0 until cfg.dataBits)
            .map(i => ((maskedP >> i) & 1) == 1)
            .foldLeft(false)(_ ^ _)
          val correctParity = cfg.parity match {
            case ParityType.Even => xorAll
            case ParityType.Odd  => !xorAll
            case _               => false
          }
          val badParity = !correctParity

          val parityDrv = fork {
            driveBits(frameBits(parityByte, parityOverride = Some(badParity)))
            idleLine()
          }
          var sawParity = false
          var sawValid2 = false
          var p2 = 0
          val parityDeadline = bitClocks * 40
          dut.io.payload.ready #= false
          while (p2 < parityDeadline && !sawParity) {
            dut.clockDomain.waitSampling()
            if (dut.io.parityError.toBoolean) sawParity = true
            if (dut.io.payload.valid.toBoolean) sawValid2 = true
            p2 += 1
          }
          assert(
            sawParity,
            s"[$cfgLabel] parity error: expected parityError pulse"
          )
          assert(
            !sawValid2,
            s"[$cfgLabel] parity error: valid raised despite failure"
          )
          parityDrv.join()
          idleLine(4)
        }

        // ------------------------------------------------------------------
        // (5) Overrun. Two frames, ready held low.
        // ------------------------------------------------------------------
        idleLine(2)
        dut.io.payload.ready #= false
        val firstByte = 0x3a & mask.toInt
        val secondByte = 0x47 & mask.toInt
        val overrunDrv = fork {
          driveBits(frameBits(firstByte))
          driveBits(frameBits(secondByte))
          idleLine()
        }
        assert(
          waitFor(bitClocks * 30)(dut.io.payload.valid.toBoolean),
          s"[$cfgLabel overrun] first frame did not raise valid"
        )
        var sawOverrun = false
        var validDropped = false
        var op = 0
        val overrunDeadline = bitClocks * 30
        while (op < overrunDeadline && !sawOverrun) {
          dut.clockDomain.waitSampling()
          if (dut.io.overrun.toBoolean) sawOverrun = true
          if (!dut.io.payload.valid.toBoolean) validDropped = true
          op += 1
        }
        assert(sawOverrun, s"[$cfgLabel overrun] expected overrun pulse")
        assert(
          !validDropped,
          s"[$cfgLabel overrun] valid dropped without ready firing"
        )
        // Drain.
        dut.io.payload.ready #= true
        dut.clockDomain.waitSampling(bitClocks)
        dut.io.payload.ready #= false
        overrunDrv.join()
        idleLine(4)

        // ------------------------------------------------------------------
        // (6) RTS smoke (only if useRts is enabled).
        // ------------------------------------------------------------------
        if (cfg.useRts) {
          dut.io.payload.ready #= true
          dut.clockDomain.waitSampling()
          assert(
            dut.io.rts.toBoolean,
            s"[$cfgLabel] rts should mirror payload.ready (high)"
          )
          dut.io.payload.ready #= false
          dut.clockDomain.waitSampling()
          assert(
            !dut.io.rts.toBoolean,
            s"[$cfgLabel] rts should mirror payload.ready (low)"
          )
        }

        println(s"OK: UartRx behaves correctly ($cfgLabel)")
      }
  }

  def main(args: Array[String]): Unit = {
    val patterns8 = Seq(0x00, 0xff, 0xaa, 0x55, 0x80, 0x01, 0xad)
    val patterns5 = Seq(0x00, 0x1f, 0x15, 0x0a, 0x10, 0x01, 0x0d)

    // Same scaled-down clock as RxFsmSim: clk=32 kHz, baud=1 kHz,
    // oversample=16. Internal BaudGenerator gets baudRate*oversample
    // = 16 kHz, so each tick is 2 sys clocks. Bit period is
    // clk/baud = 32 sys clocks.
    val clk = 32000
    val baud = 1000

    val configs: Seq[(UartConfig, Seq[Int])] = Seq(
      (
        UartConfig(
          clk,
          baud,
          8,
          1,
          ParityType.None,
          useCts = false,
          useRts = false
        ),
        patterns8
      ),
      (
        UartConfig(
          clk,
          baud,
          8,
          2,
          ParityType.None,
          useCts = false,
          useRts = false
        ),
        patterns8
      ),
      (
        UartConfig(
          clk,
          baud,
          8,
          1,
          ParityType.Even,
          useCts = false,
          useRts = false
        ),
        patterns8
      ),
      (
        UartConfig(
          clk,
          baud,
          8,
          1,
          ParityType.Odd,
          useCts = false,
          useRts = false
        ),
        patterns8
      ),
      (
        UartConfig(
          clk,
          baud,
          5,
          1,
          ParityType.None,
          useCts = false,
          useRts = false
        ),
        patterns5
      ),
      // RTS path
      (
        UartConfig(
          clk,
          baud,
          8,
          1,
          ParityType.None,
          useCts = false,
          useRts = true
        ),
        patterns8
      )
    )

    for ((cfg, pats) <- configs) {
      runRxTest(cfg, pats)
    }
  }
}
