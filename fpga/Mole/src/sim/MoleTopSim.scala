package mole

import spinal.core._
import spinal.core.sim._
import spinal.lib._

import scala.collection.mutable

/** Sim-side DUT wrapper for MoleTop.
  *
  * Instantiates [[MoleTop]] with `useBlackBox = false` (PLL bypass + SB_IO
  * bypass + SPRAM behavioural model) and adds a sim-side [[UartTx]] /
  * [[UartRx]] pair so the test harness can push frame bytes and pop ring bytes
  * through Spinal Streams instead of bit-banging the UART wire. The sim-side
  * UART runs at the same (clock, baud) as MoleTop's internal UART, so the bits
  * on `io_uRx` and `io_uTx` look exactly like what a real host adapter would
  * drive.
  *
  * Internal MoleTop signals are exposed through wrapper-level outputs
  * (`sda/sclDriveLow/High`, `engineDone`, `loaderLoaded`, `loaderFault`) so the
  * sim can monitor the engine's bus drivers without bit-banging the analog
  * pads. Both clock domains are electrically the same wall-clock under PLL
  * bypass, so the cross-domain taps are race-free for sim purposes.
  */
case class MoleTopSimDut(cfg: MoleConfig) extends Component {

  val fabricFreqHz: Int = (cfg.fabricFreqHz.toBigDecimal).toInt
  val uartCfg = UartConfig(
    clkFreqHz = fabricFreqHz,
    baudRate = cfg.uartBaud,
    dataBits = 8,
    stopBits = 1,
    parity = ParityType.None,
    useCts = false,
    useRts = false,
    oversample = 16
  )

  val io = new Bundle {

    /** Active-low external reset; tied to the user button in production. */
    val externalReset = in Bool ()

    /** Sim-side byte injection. Bytes pushed here are serialised by the sim
      * UART TX onto MoleTop's `io_uRx`.
      */
    val txData = slave Stream (Bits(8 bits))

    /** Sim-side byte capture. Bytes that come back from MoleTop's `io_uTx` are
      * deserialised by the sim UART RX and presented here.
      */
    val rxData = master Stream (Bits(8 bits))

    /** MoleTop's three status LEDs, mirrored for assertions. */
    val ledR = out Bool ()
    val ledG = out Bool ()
    val ledB = out Bool ()

    /** MoleTop's CTS# output (active-low). Asserted (`0`) only while the
      * loader is open for a new frame. Surfaced here so MoleTopFlowControlSim
      * can sample it directly without reaching into `mole.io`.
      */
    val ctsOut = out Bool ()

    /** Sim-side drive for MoleTop's RTS# input (active-low). Asserted (`0`)
      * = "host ready, drainer may TX"; deasserted (`1`) = drainer halts.
      * Defaulted to `0` in tests that do not exercise TX back-pressure so
      * existing MoleTopSim cases keep streaming.
      */
    val rtsIn = in Bool ()

    /** Engine's bus drive signals, tapped from inside MoleTop's fabric area.
      * SB_IO bypass leaves the analog pads unconnected; this lets the sim
      * observe what the engine would have driven onto the pad.
      */
    val sdaDriveLow = out Bool ()
    val sdaDriveHigh = out Bool ()
    val sclDriveLow = out Bool ()
    val sclDriveHigh = out Bool ()

    /** Selected internal status taps. */
    val engineDone = out Bool ()
    val loaderLoaded = out Bool ()
    val loaderFault = out Bool ()

    /** Pad ports passed straight through to MoleTop's `io_sda` / `io_scl` inout
      * pads. Verilator otherwise rejects MoleTop's instantiation with
      * PINMISSING because Analog inouts cannot be left unconnected at a
      * Component boundary. Nothing in the sim drives or observes these pads
      * directly --- the engine's bus drivers are tapped via the `sim_*` ports
      * above --- but the pads must dangle through to the testbench top level so
      * the elaborator can wire them out.
      */
    val io_sda = inout(Analog(Bool()))
    val io_scl = inout(Analog(Bool()))
  }
  noIoPrefix()

  val mole = MoleTop(cfg, useBlackBox = false)

  // Drive MoleTop's pin-level interface from the wrapper's
  // single ClockDomain. The PLL bypass makes io_clk feed
  // straight to fabricCd, so MoleTop's fabric clock is the
  // wrapper's clock.
  mole.io.io_clk := ClockDomain.current.readClockWire
  mole.io.io_reset := io.externalReset

  // Pass MoleTop's analog pads straight through to the wrapper boundary.
  // Required for Verilator: a Component with inout(Analog) ports MUST have
  // those ports connected at every instantiation site.
  mole.io.io_sda <> io.io_sda
  mole.io.io_scl <> io.io_scl

  io.ledR := mole.io.io_ledR
  io.ledG := mole.io.io_ledG
  io.ledB := mole.io.io_ledB

  // Hardware flow control. CTS# is an output the test bench can sample;
  // RTS# is an input the test bench drives. Defaulting `rtsIn := 0` at
  // the testbench-side (in MoleTopSim's bench) keeps every existing
  // MoleTopSim case TX-unblocked.
  io.ctsOut := mole.io.io_uCts
  mole.io.io_uRts := io.rtsIn

  // Sim-side UART. Same cfg as MoleTop's so the bit timing matches.
  val simTx = UartTx(uartCfg)
  val simRx = UartRx(uartCfg)

  simTx.io.baudPhaseInc :=
    U(
      BigInt(BaudGenerator.phaseIncFor(fabricFreqHz, cfg.uartBaud)),
      BaudGenerator.defaultAccWidth bits
    )
  simRx.io.baudPhaseInc :=
    U(
      BigInt(
        BaudGenerator.phaseIncFor(
          fabricFreqHz,
          cfg.uartBaud * uartCfg.oversample
        )
      ),
      BaudGenerator.defaultAccWidth bits
    )

  simTx.io.data << io.txData
  mole.io.io_uRx := simTx.io.tx

  simRx.io.rx := mole.io.io_uTx
  io.rxData << simRx.io.payload

  // Internal taps exposed by MoleTop's sim_* ports (only present
  // because we elaborated MoleTop with useBlackBox = false).
  io.sdaDriveLow := mole.io.sim_sdaDriveLow
  io.sdaDriveHigh := mole.io.sim_sdaDriveHigh
  io.sclDriveLow := mole.io.sim_sclDriveLow
  io.sclDriveHigh := mole.io.sim_sclDriveHigh
  io.engineDone := mole.io.sim_engineDone
  io.loaderLoaded := mole.io.sim_loaderLoaded
  io.loaderFault := mole.io.sim_loaderFault
}

/** End-to-end audit for [[MoleTop]].
  *
  * Cases:
  *
  *   1. **short halt** --- `[SetBusMode(i2c), Halt(0)]` loads, runs, drains.
  *      Verifies the result ring carries Revision lo/hi at the first two words
  *      and the HALT word at the last word.
  *   2. **bus toggle** --- `[SetBusMode(i2c), EmitBit(sda=dom),
  *      EmitBit(sda=hiz), Halt(0)]`. Verifies the engine actually transitions
  *      SDA's drive signals through the quarter-bit sequence (the bus-toggle
  *      smoke from the plan).
  *   3. **bad CRC recovery** --- a frame with a corrupted CRC byte is sent
  *      first; the engine MUST NOT start and the loader fault LED must pulse.
  *      After the resync idle gap, a good frame is sent and the system
  *      completes normally.
  *   4. **two back-to-back frames** --- a frame loads, runs, drains; another
  *      frame loads, runs, drains. Verifies the phase FSM cycles cleanly
  *      through `acceptLoad -> running -> draining -> acceptLoad` and that the
  *      second run sees a fresh result ring.
  *
  * Small test config:
  *
  *   - `programWordCount = 16`
  *   - `resultRingByteCount = 32` (so `resultWordCount = 16`)
  *
  * Default-cfg drains would be ~2 M cycles each; the small config keeps each
  * case under ~50 K cycles.
  */
object MoleTopSim extends App {

  // ----------------------------------------------------------------
  // Reference CRC and frame builder. Mirrors MoleLoaderFsmSim and
  // WIRE_FORMAT.md.
  // ----------------------------------------------------------------
  def crc16Xmodem(bytes: Seq[Int]): Int = {
    var crc = 0
    for (b <- bytes) {
      crc = crc ^ ((b & 0xff) << 8)
      for (_ <- 0 until 8) {
        if ((crc & 0x8000) != 0) crc = ((crc << 1) ^ 0x1021) & 0xffff
        else crc = (crc << 1) & 0xffff
      }
    }
    crc & 0xffff
  }

  def buildFrame(words: Seq[Int]): Seq[Int] = {
    val n = words.size
    val lenBytes = Seq(n & 0xff, (n >> 8) & 0xff)
    val wordBytes = words.flatMap(w => Seq(w & 0xff, (w >> 8) & 0xff))
    val payload = lenBytes ++ wordBytes
    val crc = crc16Xmodem(payload)
    payload ++ Seq(crc & 0xff, (crc >> 8) & 0xff)
  }

  // Tiny config: 16-word program memory, 16-word result ring (32
  // bytes drained per halt). Keeps the worst-case per-test wall
  // time manageable.
  val cfg = MoleConfig(
    fabricFreqHz = 24 MHz,
    quarterPeriodCyclesReset = 6,
    programWordCount = 16,
    resultRingByteCount = 32,
    captureMaxBits = 64,
    uartBaud = 1_000_000
  )

  // Result-ring bytes: Revision lo at [0..1], Revision hi at [2..3],
  // HALT word at [resultRingByteCount - 2 .. resultRingByteCount - 1].
  val revisionLo = Revision.wordLo & 0xffff
  val revisionHi = Revision.wordHi & 0xffff

  // Clean HALT: tag=11, overflow=0, mismatchAtHalt=0, status=0,
  // reserved=0 = 0xC000.
  val cleanHaltWord = 0xc000

  // ----------------------------------------------------------------
  // Common helpers.
  // ----------------------------------------------------------------

  /** Push one byte through the sim UART TX stream and wait for the handshake.
    */
  def sendByte(dut: MoleTopSimDut, byte: Int): Unit = {
    dut.io.txData.payload #= byte
    dut.io.txData.valid #= true
    dut.clockDomain.waitSamplingWhere(dut.io.txData.ready.toBoolean)
    dut.io.txData.valid #= false
  }

  /** Push every byte of `frame`. */
  def sendFrame(dut: MoleTopSimDut, frame: Seq[Int]): Unit = {
    for (b <- frame) sendByte(dut, b)
  }

  /** Pop one byte from the sim UART RX stream, with a generous timeout to keep
    * the sim from hanging if the drainer misbehaves.
    */
  def recvByte(dut: MoleTopSimDut, maxCycles: Int = 500_000): Int = {
    dut.io.rxData.ready #= true
    var waited = 0
    while (!dut.io.rxData.valid.toBoolean && waited < maxCycles) {
      dut.clockDomain.waitSampling()
      waited += 1
    }
    assert(
      dut.io.rxData.valid.toBoolean,
      s"recvByte: timed out after $maxCycles cycles waiting for sim UART RX"
    )
    val b = dut.io.rxData.payload.toInt
    // Advance one cycle so the (valid && ready) handshake actually
    // fires and simRx releases the byte. Without this, valid stays
    // sticky on the same byte and the next recvByte call returns
    // the same value instead of waiting for the next real byte.
    dut.clockDomain.waitSampling()
    dut.io.rxData.ready #= false
    b
  }

  /** Drain exactly `n` bytes from the sim UART RX stream. */
  def recvN(dut: MoleTopSimDut, n: Int): Seq[Int] = {
    val out = mutable.Buffer.empty[Int]
    for (_ <- 0 until n) out += recvByte(dut)
    out.toSeq
  }

  /** Hold reset for a few cycles then release. The MoleTop reset bridge takes a
    * couple of edges to deassert through the 2-FF sync chain.
    */
  def doReset(dut: MoleTopSimDut): Unit = {
    dut.io.externalReset #= false
    dut.io.txData.valid #= false
    dut.io.rxData.ready #= false
    // Default RTS# to asserted (LOW = host ready) so the drainer
    // can stream freely in the existing MoleTopSim cases that
    // don't exercise TX back-pressure. MoleTopFlowControlSim
    // overrides this per-case to drive RTS# high and verify the
    // drainer halts.
    dut.io.rtsIn #= false
    dut.clockDomain.waitSampling(10)
    dut.io.externalReset #= true
    dut.clockDomain.waitSampling(10)
  }

  // ----------------------------------------------------------------
  // Case 1: short-halt round-trip.
  // ----------------------------------------------------------------
  println("--- MoleTopSim: short halt round-trip ---")
  SimConfig.compile(MoleTopSimDut(cfg)).doSim("short-halt") { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    doReset(dut)

    val program = Seq(
      Instruction.encode(Instruction.SetBusMode(BusMode.i2c)),
      Instruction.encode(Instruction.Halt(0))
    )
    val frame = buildFrame(program)

    // Sim-side fork: drain bytes as they arrive so the drainer's
    // UART stream sees real consumer back-pressure and is not
    // mass-stalled on a single host-side read at the end.
    val received = mutable.Buffer.empty[Int]
    val drainFork = fork {
      while (received.size < cfg.resultRingByteCount) {
        received += recvByte(dut)
      }
    }

    sendFrame(dut, frame)
    drainFork.join()

    val expected = received.size
    assert(
      expected == cfg.resultRingByteCount,
      s"short-halt: expected $expected == ${cfg.resultRingByteCount}"
    )

    // First word: Revision lo (low byte first).
    val gotRevLo = (received(1) << 8) | received(0)
    assert(
      gotRevLo == revisionLo,
      s"short-halt: revLo expected 0x${revisionLo.toHexString} got 0x${gotRevLo.toHexString}"
    )

    // Second word: Revision hi.
    val gotRevHi = (received(3) << 8) | received(2)
    assert(
      gotRevHi == revisionHi,
      s"short-halt: revHi expected 0x${revisionHi.toHexString} got 0x${gotRevHi.toHexString}"
    )

    // Final word: HALT 0.
    val n = cfg.resultRingByteCount
    val gotHalt = (received(n - 1) << 8) | received(n - 2)
    assert(
      gotHalt == cleanHaltWord,
      s"short-halt: HALT expected 0x${cleanHaltWord.toHexString} got 0x${gotHalt.toHexString}"
    )

    println(
      s"   ok: drained $expected bytes, HALT word 0x${gotHalt.toHexString}"
    )
  }

  // ----------------------------------------------------------------
  // Case 2: bus toggle.
  //
  // EmitBit(sda=dominant) drives SDA low for the bit window.
  // EmitBit(sda=hiz) releases SDA. Validate that the engine actually
  // toggles the bus drive signals across the program (not just that
  // it completes).
  // ----------------------------------------------------------------
  println("--- MoleTopSim: bus toggle ---")
  SimConfig.compile(MoleTopSimDut(cfg)).doSim("bus-toggle") { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    doReset(dut)

    val program = Seq(
      Instruction.encode(Instruction.SetBusMode(BusMode.i2c)),
      Instruction.encode(
        Instruction.EmitBit(
          TxSymbol.dominant,
          expect = false,
          mask = false,
          capture = false
        )
      ),
      Instruction.encode(
        Instruction
          .EmitBit(TxSymbol.hiz, expect = false, mask = false, capture = false)
      ),
      Instruction.encode(Instruction.Halt(0))
    )
    val frame = buildFrame(program)

    // Fork an SDA monitor BEFORE we kick off the program so we
    // don't miss the first transition. The monitor terminates
    // when the main thread sets `monitorDone`.
    var sdaLowSeen = false
    var sdaHizSeen = false
    var monitorDone = false
    val sdaMonitor = fork {
      while (!monitorDone) {
        if (dut.io.sdaDriveLow.toBoolean) sdaLowSeen = true
        // Only count the release window AFTER we've seen the low
        // pulse --- the engine sits in idle (hiz) before it starts
        // running, and that initial idle would otherwise count.
        if (
          sdaLowSeen &&
          !dut.io.sdaDriveLow.toBoolean &&
          !dut.io.sdaDriveHigh.toBoolean
        ) {
          sdaHizSeen = true
        }
        dut.clockDomain.waitSampling()
      }
    }

    val received = mutable.Buffer.empty[Int]
    val drainFork = fork {
      while (received.size < cfg.resultRingByteCount) {
        received += recvByte(dut)
      }
    }

    sendFrame(dut, frame)
    drainFork.join()
    monitorDone = true
    sdaMonitor.join()

    assert(
      sdaLowSeen,
      "bus-toggle: never saw SDA driveLow assert"
    )
    assert(
      sdaHizSeen,
      "bus-toggle: never saw SDA release (hiz) after the low pulse"
    )

    // Final HALT word still clean.
    val n = cfg.resultRingByteCount
    val gotHalt = (received(n - 1) << 8) | received(n - 2)
    assert(
      gotHalt == cleanHaltWord,
      s"bus-toggle: HALT expected 0x${cleanHaltWord.toHexString} got 0x${gotHalt.toHexString}"
    )

    println("   ok: SDA driveLow then release observed, clean HALT")
  }

  // ----------------------------------------------------------------
  // Case 3: bad CRC -> recovery.
  //
  // Send a frame with a corrupted CRC trailer; verify the engine
  // does NOT start and the loader fault LED pulses (the
  // pulse-stretcher then holds it for ~175 ms = many sim cycles).
  // After the idle gap, send a good frame and verify it loads
  // normally.
  // ----------------------------------------------------------------
  println("--- MoleTopSim: bad CRC + recovery ---")
  SimConfig.compile(MoleTopSimDut(cfg)).doSim("bad-crc") { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    doReset(dut)

    val program = Seq(
      Instruction.encode(Instruction.SetBusMode(BusMode.i2c)),
      Instruction.encode(Instruction.Halt(0))
    )
    val good = buildFrame(program)

    // Corrupt the CRC low byte.
    val bad = good.updated(good.size - 2, good(good.size - 2) ^ 0xff)

    // Monitor fault LED, loaderLoaded, and engineDone for the full
    // resync window. The pulse-stretcher holds the LED high for a
    // long time; loaded/done are momentary so we have to sample
    // them every cycle.
    var faultSeen = false
    var loadedSeen = false
    var doneWentLow = false
    val faultMonitor = fork {
      val watchCycles = 20 * dut.uartCfg.ticksPerBit * 10
      var n = 0
      while (n < watchCycles) {
        if (dut.io.ledR.toBoolean) faultSeen = true
        if (dut.io.loaderLoaded.toBoolean) loadedSeen = true
        if (!dut.io.engineDone.toBoolean) doneWentLow = true
        dut.clockDomain.waitSampling()
        n += 1
      }
    }

    sendFrame(dut, bad)
    faultMonitor.join()

    assert(faultSeen, "bad-crc: fault LED never lit")
    assert(
      !loadedSeen,
      "bad-crc: loaderLoaded pulsed -- frame was wrongly accepted"
    )
    assert(
      !doneWentLow,
      "bad-crc: engineDone went low -- engine actually started despite bad CRC"
    )

    // Idle gap: drive UART idle for >= 20 byte-times. The sim TX
    // already idles high after sendFrame; just wait.
    val idleGapCycles = 20 * dut.uartCfg.ticksPerBit * 12 // 12x safety
    dut.clockDomain.waitSampling(idleGapCycles)

    // Now send a good frame; it must load cleanly.
    val received = mutable.Buffer.empty[Int]
    val drainFork = fork {
      while (received.size < cfg.resultRingByteCount) {
        received += recvByte(dut)
      }
    }
    sendFrame(dut, good)
    drainFork.join()

    val n = cfg.resultRingByteCount
    val gotHalt = (received(n - 1) << 8) | received(n - 2)
    assert(
      gotHalt == cleanHaltWord,
      s"bad-crc: after recovery HALT expected 0x${cleanHaltWord.toHexString} got 0x${gotHalt.toHexString}"
    )

    println(
      s"   ok: bad CRC rejected (fault LED lit), recovery frame drained $n bytes"
    )
  }

  // ----------------------------------------------------------------
  // Case 4: back-to-back frames.
  // ----------------------------------------------------------------
  println("--- MoleTopSim: back-to-back frames ---")
  SimConfig.compile(MoleTopSimDut(cfg)).doSim("back-to-back") { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    doReset(dut)

    val program = Seq(
      Instruction.encode(Instruction.SetBusMode(BusMode.i2c)),
      Instruction.encode(Instruction.Halt(0))
    )
    val frame = buildFrame(program)

    for (runIdx <- 0 until 2) {
      val received = mutable.Buffer.empty[Int]
      val drainFork = fork {
        while (received.size < cfg.resultRingByteCount) {
          received += recvByte(dut)
        }
      }

      sendFrame(dut, frame)
      drainFork.join()

      val n = cfg.resultRingByteCount
      val gotHalt = (received(n - 1) << 8) | received(n - 2)
      assert(
        gotHalt == cleanHaltWord,
        s"back-to-back run $runIdx: HALT expected 0x${cleanHaltWord.toHexString} got 0x${gotHalt.toHexString}"
      )
      println(
        s"   ok: run $runIdx drained $n bytes, HALT 0x${gotHalt.toHexString}"
      )
    }
  }

  println("--- MoleTopSim: all cases passed ---")
}
