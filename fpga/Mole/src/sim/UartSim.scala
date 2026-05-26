package mole

import spinal.core._
import spinal.core.sim._
import spinal.lib._

/** TX → wire → RX loopback DUT for [[UartSim]].
  *
  * Wires a single [[UartTx]] directly to a single [[UartRx]] (no cable model,
  * no glitch injection by default — both are controlled by the sim) and buries
  * the per-side `baudPhaseInc` constants inside the DUT so the test harness can
  * focus on driving the Stream interfaces. Both halves share one [[UartConfig]]
  * so a frame format mismatch (e.g. 8N1 vs 8N2) is impossible by construction;
  * the only thing the loopback exercises is the wire-level handoff.
  *
  * Lives in `src/sim/` rather than `src/hw/` because it never elaborates to RTL
  * — `UartLoopbackDut` is built only by [[UartSim]] under
  * `SimConfig.compile(...)`. Keeping it out of `src/hw/` is what stops `make`
  * picking it up when generating `MoleTop.v`.
  */
case class UartLoopbackDut(cfg: UartConfig) extends Component {

  /** TX-side phase increment (1 tick per bit period). */
  val txPhaseInc: BigInt =
    BigInt(BaudGenerator.phaseIncFor(cfg.clkFreqHz, cfg.baudRate))

  /** RX-side phase increment (oversample × bit rate). */
  val rxPhaseInc: BigInt =
    BigInt(
      BaudGenerator.phaseIncFor(cfg.clkFreqHz, cfg.baudRate * cfg.oversample)
    )

  val io = new Bundle {

    /** Byte input fed to the TX side. */
    val data = slave Stream (Bits(cfg.dataBits bits))

    /** Byte output drained from the RX side. */
    val rx = master Stream (Bits(cfg.dataBits bits))

    /** Mirrored RX status flags so the harness can assert on them. */
    val framingError = out Bool ()
    val parityError = out Bool ()
    val overrun = out Bool ()

    /** Tap onto the wire between TX and RX. Lets the harness inject a
      * single-cycle glitch (overrides what TX is driving) and exposes the line
      * value for waveform inspection.
      */
    val wireOverride = in Bool ()
    val wireOverrideEnable = in Bool ()
    val wireRead = out Bool ()
  }

  val tx = UartTx(cfg)
  val rx = UartRx(cfg)

  tx.io.data << io.data
  tx.io.baudPhaseInc := U(txPhaseInc, BaudGenerator.defaultAccWidth bits)

  val wire = io.wireOverrideEnable ? io.wireOverride | tx.io.tx
  rx.io.rx := wire
  io.wireRead := wire
  rx.io.baudPhaseInc := U(rxPhaseInc, BaudGenerator.defaultAccWidth bits)

  io.rx << rx.io.payload
  io.framingError := rx.io.framingError
  io.parityError := rx.io.parityError
  io.overrun := rx.io.overrun
}

/** Top-level UART TX → RX loopback sim.
  *
  * Wires a [[UartTx]] directly to a [[UartRx]] inside [[UartLoopbackDut]] and
  * verifies that bytes pushed through the TX Stream re-emerge intact on the RX
  * Stream. Three configurations are exercised to cover Mole's deployment
  * matrix:
  *
  *   1. **12 MHz / 115 200 baud** — sibling project default; sanity check that
  *      the upstream import still works at the original clock rate.
  *   2. **48 MHz / 115 200 baud** — Mole "early dev" config; same baud, fast
  *      fabric. Catches BaudGenerator phase increment sizing regressions
  *      introduced by the higher clock.
  *   3. **48 MHz / 2 Mbaud** — Mole production default per
  *      `MoleConfig.uartBaud`. 2 Mbaud × 16× oversample = 32 MHz tick rate,
  *      which fits comfortably in the 24-bit DDS at 48 MHz fabric (`phaseInc ≈
  *      11_184_811 = 0xAAA_AAB`, well below the 2^24 ceiling). iCEBreaker's
  *      FT2232H supports up to 12 Mbaud, so 2 Mbaud has plenty of host-side
  *      headroom.
  *
  * Coverage at each config:
  *   - Single-byte round trip across a representative pattern set (`0x00`,
  *     `0xFF`, `0xAA`, `0x55`, `0xAD`, `0x80`, `0x01`).
  *   - Back-to-back burst: `valid` held high while payload swaps, proving the
  *     FSM accepts the next byte the cycle it returns to idle.
  *   - Single-cycle wire glitch injection mid-idle — the receiver must NOT
  *     mistake a 1-cycle pulse for a start bit (the debouncing comes from the
  *     oversample windowing in `RxFsm`), and any subsequent clean frame must
  *     still decode correctly.
  *
  * Not covered here — the sub-block sims already nail these:
  *   - DDS phase accuracy (BaudGeneratorSim).
  *   - RxSync metastability behaviour (RxSyncSim).
  *   - Shift-register direction (Tx/RxShiftRegSim).
  *   - FSM frame-format edge cases (Tx/RxFsmSim).
  *   - Parity, framing-error, overrun (UartRxSim).
  *   - CTS / RTS flow control (UartTxSim).
  *
  * Mid-bit sampling is not needed here because the entire chain is inside one
  * simulation — the byte that comes out of `UartRx` is already a fully parsed
  * Stream payload. Compared to the sub-block sims (which had to manually decode
  * the TX wire or generate the RX wire bit-by-bit), this is the most "natural"
  * test in the UART suite.
  *
  * Run: `sbt "runMain mole.UartSim"`
  */
object UartSim {

  private val patterns: Seq[Int] =
    Seq(0x00, 0xff, 0xaa, 0x55, 0xad, 0x80, 0x01)

  private val burst: Seq[Int] =
    Seq(0x00, 0x01, 0x02, 0x55, 0xaa, 0x7e, 0xfe, 0xff)

  /** Drive a single byte through TX (Stream handshake) and wait for it to
    * arrive on RX (Stream handshake). Cap the wall-clock wait at ~12 × bit
    * period so a hung test fails fast.
    */
  private def roundTripOne(dut: UartLoopbackDut, byte: Int): Unit = {
    val cd = dut.clockDomain
    val cfg = dut.cfg
    // Worst-case frame: start + dataBits + parity? + stopBits, plus
    // RxSync 2-cycle latency and a fudge factor. 16 bit periods is
    // safely above any realistic case.
    val maxWaitCycles = cfg.ticksPerBit * (cfg.dataBits + 8) * 4

    dut.io.rx.ready #= true
    dut.io.data.payload #= byte
    dut.io.data.valid #= true
    cd.waitSamplingWhere(dut.io.data.ready.toBoolean)
    dut.io.data.valid #= false

    val deadline = simTime() + maxWaitCycles * 10
    var got: Option[Int] = None
    while (got.isEmpty && simTime() < deadline) {
      cd.waitSampling()
      if (dut.io.rx.valid.toBoolean) {
        got = Some(dut.io.rx.payload.toInt)
      }
    }
    assert(
      got.isDefined,
      s"timeout waiting for RX byte 0x${byte.toHexString} (waited $maxWaitCycles cycles)"
    )
    assert(
      got.contains(byte & ((1 << cfg.dataBits) - 1)),
      f"loopback mismatch: sent 0x$byte%02x, got 0x${got.get}%02x"
    )
  }

  /** Drive a back-to-back burst. `data.valid` is held high across the whole
    * sequence; the payload swaps between handshakes. The RX side consumes as
    * fast as it can.
    */
  private def burstRoundTrip(dut: UartLoopbackDut, bytes: Seq[Int]): Unit = {
    val cd = dut.clockDomain
    val cfg = dut.cfg
    val mask = (1 << cfg.dataBits) - 1
    val maxWaitCycles = cfg.ticksPerBit * (cfg.dataBits + 8) * bytes.size * 4

    dut.io.rx.ready #= true

    val producer = fork {
      for (b <- bytes) {
        dut.io.data.payload #= b
        dut.io.data.valid #= true
        cd.waitSamplingWhere(dut.io.data.ready.toBoolean)
      }
      dut.io.data.valid #= false
    }

    val received = scala.collection.mutable.ArrayBuffer.empty[Int]
    val consumer = fork {
      val deadline = simTime() + maxWaitCycles * 10
      while (received.size < bytes.size && simTime() < deadline) {
        cd.waitSampling()
        if (dut.io.rx.valid.toBoolean && dut.io.rx.ready.toBoolean) {
          received += dut.io.rx.payload.toInt
        }
      }
    }

    producer.join()
    consumer.join()

    assert(
      received.size == bytes.size,
      s"burst: expected ${bytes.size} bytes, got ${received.size}: $received"
    )
    bytes.zip(received).foreach { case (sent, got) =>
      assert(
        (sent & mask) == got,
        f"burst: sent 0x$sent%02x, got 0x$got%02x"
      )
    }
  }

  /** Inject a single-cycle pulse on the wire while the link is idle (line
    * high), then send a clean frame. The RX must not have latched the pulse as
    * a start bit and must decode the clean frame correctly.
    */
  private def glitchRecovery(dut: UartLoopbackDut, byte: Int): Unit = {
    val cd = dut.clockDomain
    val cfg = dut.cfg

    dut.io.rx.ready #= true
    // Pulse low for one cycle.
    dut.io.wireOverride #= false
    dut.io.wireOverrideEnable #= true
    cd.waitSampling()
    dut.io.wireOverrideEnable #= false
    // Wait long enough for RxFsm to confirm the glitch was not a
    // real start bit (oversample windowing in the start state) and
    // return to idle.
    cd.waitSampling(cfg.ticksPerBit * 2)
    assert(
      !dut.io.rx.valid.toBoolean,
      "glitch was latched as a frame — RX should have rejected it"
    )
    assert(
      !dut.io.framingError.toBoolean,
      "glitch produced a framingError — RX should debounce sub-bit pulses"
    )

    // Now send a clean byte and confirm it arrives.
    roundTripOne(dut, byte)
  }

  /** Build, compile, and run the full plan for one config. */
  private def runOne(label: String, cfg: UartConfig): Unit = {
    println(s"--- UartSim: $label ---")
    SimConfig.withWave
      .compile(UartLoopbackDut(cfg))
      .doSim(label) { dut =>
        dut.clockDomain.forkStimulus(period = 10)

        // Defaults.
        dut.io.data.valid #= false
        dut.io.data.payload #= 0
        dut.io.rx.ready #= false
        dut.io.wireOverride #= true
        dut.io.wireOverrideEnable #= false
        dut.clockDomain.waitSampling(20)

        // 1. Single-byte round-trip across the pattern set.
        for (b <- patterns) roundTripOne(dut, b)

        // 2. Back-to-back burst.
        burstRoundTrip(dut, burst)

        // 3. Glitch recovery.
        glitchRecovery(dut, 0xa5)

        println(
          s"$label: ${patterns.size} bytes + ${burst.size}-byte burst + glitch recovery OK"
        )
      }
  }

  def main(args: Array[String]): Unit = {
    val configs = Seq(
      "12MHz_115200" -> UartConfig(clkFreqHz = 12000000, baudRate = 115200),
      "48MHz_115200" -> UartConfig(clkFreqHz = 48000000, baudRate = 115200),
      "48MHz_2000000" -> UartConfig(clkFreqHz = 48000000, baudRate = 2000000)
    )
    configs.foreach { case (label, cfg) => runOne(label, cfg) }
    println("UartSim OK")
  }
}
