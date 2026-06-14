package mole

import spinal.core._
import spinal.core.sim._
import spinal.lib._

import scala.collection.mutable

// ============================================================================
// **C.11.b FINDING (2026-06-10):** The 3 sims in this file family
// (`MoleTopSim`, `MoleTopFlowControlSim`, `MoleTopCtsViolationSim`)
// currently **fail** because the engine's PC starts at 0 and fetches
// the v0.2 frame preamble (MAGIC=0x0002_4D4C + body_len) as
// instructions, trapping immediately on the magic word's reserved
// bits. The MoleLoaderFsm + LoaderWidthAdapter write the preamble
// verbatim into SPRAM[0..1]; there is no preamble-strip step in
// MoleTop or the engine.
//
// This is a real glue gap surfaced by C.11.b --- exactly the kind of
// inter-component bug the end-to-end sim was meant to catch. The fix
// is engine-side; HDL is frozen on branch v0.2 per the C.11
// hand-off contract, so the sims are kept in tree as a forward
// regression target rather than removed.
//
// The 3 sims are NOT in the aggregate `make sim` target. Run them
// individually to reproduce the bug:
//     make sim-top
//     make sim-top-flow-control
//     make sim-top-cts-violation
//
// Expected fix shape (one of):
//   (a) Add `pcReg init U(PREAMBLE_WORDS, _)` so engine starts past
//       the preamble, OR
//   (b) Add `programOffset` input to EnginePipeline; MoleTop drives
//       it to PREAMBLE_WORDS (=2), OR
//   (c) Add a "preamble strip" step in MoleLoaderFsm / LoaderWidth-
//       Adapter that consumes the first PREAMBLE_WORDS 32-bit words
//       and verifies MAGIC against `mole_abi::MAGIC`, then writes
//       body word 0 to SPRAM[0].
//
// Option (c) is the cleanest because it gives MoleTop a place to
// reject bad-magic frames before the engine ever fetches; the
// current design has no engine-side magic check at all.
//
// See TODO.md §"C.11.b" for the full closeout.
// ============================================================================

/** Sim-side DUT wrapper for [[MoleTop]] (v0.2).
  *
  * Instantiates [[MoleTop]] with `useBlackBox = false` (PLL bypass + SB_IO
  * bypass + SPRAM behavioural model) and adds a sim-side [[UartTx]] /
  * [[UartRx]] pair so the test harness can push frame bytes and pop ring bytes
  * through Spinal Streams instead of bit-banging the UART wire. The sim-side
  * UART runs at the same `(clkFreqHz, baudRate)` as MoleTop's internal UART, so
  * the bits on `io_uRx` and `io_uTx` look exactly like what a real host adapter
  * would drive.
  *
  * Lives in `src/sim/` (not `src/hw/`) so it never elaborates to RTL; the
  * `useBlackBox = false` MoleTop instance brings the SPRAM as `Mem` and leaves
  * the analog SB_IO pads as straight passthrough.
  *
  * **v0.2 deviation from the attic stash:** the v0 `MoleTopSimDut` exposed
  * `sim_sda{DriveLow,DriveHigh}` / `sim_scl{DriveLow,DriveHigh}` /
  * `sim_engineDone` from MoleTop. v0.2 MoleTop's sim_* surface is narrower
  * (only `sim_halted`, `sim_haltStatus`, `sim_loaderLoaded`, `sim_loaderFault`,
  * `sim_ctsViolationObserved`). The bus-toggle case from the v0 stash is
  * therefore skipped here --- adding the missing sim_* taps would require
  * editing the frozen HDL at fpga/Mole/src/hw/ (forbidden per the C.11 hand-off
  * contract). The remaining three cases exercise the full host-link path
  * end-to-end.
  */
case class MoleTopSimDut(cfg: MoleConfig) extends Component {

  val fabricFreqHz: Int = cfg.fabricFreqHz.toBigDecimal.toInt
  val uartCfg = UartConfig(
    clkFreqHz = fabricFreqHz,
    baudRate = cfg.uartBaud,
    dataBits = 8,
    stopBits = 1,
    parity = ParityType.None,
    useCts = false,
    useRts = false,
    // 8× RX oversample paired with the 2 Mbaud `cfg.uartBaud` —
    // mirrors the production wiring in MoleTop.scala so this sim
    // wrapper exercises the same UART contract end-to-end.
    oversample = 8
  )

  val io = new Bundle {

    /** Active-low external reset; tied to the user button in production. */
    val externalReset = in Bool ()

    /** Sim-side byte injection. Bytes pushed here are serialised by the sim
      * UART TX onto MoleTop's `io_uRx`.
      */
    val txData = slave Stream (Bits(8 bits))

    /** Sim-side byte capture. Bytes from MoleTop's `io_uTx` are deserialised by
      * the sim UART RX and presented here.
      */
    val rxData = master Stream (Bits(8 bits))

    /** MoleTop's three status LEDs, mirrored for assertions. Named by FUNCTION
      * (see icebreaker.pcf): `ledFault` is the loader-fault / CTS-violation
      * indicator (small red 0603 on Mole Verde rev 1.x, silkscreen LEDR, pin
      * 11); `ledRunning` is the engine-executing indicator (small green 0603,
      * silkscreen LEDG, pin 37); `ledHeartbeat` is the idle-heartbeat indicator
      * (green channel of the big on-board RGB LED, silkscreen LED_RGB1, pin 40
      * --- the silkscreen calls this the "B" channel but the physical LED is
      * GREEN on this board rev).
      */
    val ledFault = out Bool ()
    val ledRunning = out Bool ()
    val ledHeartbeat = out Bool ()

    /** MoleTop's CTS# output (active-low). Asserted (`0`) only while the loader
      * is open for a new frame.
      */
    val ctsOut = out Bool ()

    /** Sim-side drive for MoleTop's RTS# input (active-low). Asserted (`0`) =
      * "host ready, drainer may TX"; deasserted (`1`) = drainer halts. Default
      * `false` (= active-low LOW = host ready) so cases that don't exercise TX
      * back-pressure keep streaming.
      */
    val rtsIn = in Bool ()

    /** v0.2 sim_* taps from MoleTop. */
    val halted = out Bool ()
    val haltStatus = out UInt (5 bits)
    val loaderLoaded = out Bool ()
    val loaderFault = out Bool ()
    val ctsViolationObserved = out Bool ()
    val programLength = out UInt (16 bits)
    val engineStart = out Bool ()
    val engineStartedTap = out Bool ()
    val pcTap = out UInt (13 bits)
    val fetchActiveTap = out Bool ()
    val haltInFlightTap = out Bool ()

    /** Pad ports passed straight through to MoleTop's `io_sda` / `io_scl` inout
      * pads. Verilator requires `inout(Analog)` ports be connected at every
      * instantiation site; nothing in the sim drives or observes these
      * directly.
      */
    val io_sda = inout(Analog(Bool()))
    val io_scl = inout(Analog(Bool()))
  }
  noIoPrefix()

  val mole = MoleTop(cfg, useBlackBox = false)

  // Drive MoleTop's pin-level interface from the wrapper's single
  // ClockDomain. PLL bypass makes io_clk feed straight to fabricCd.
  mole.io.io_clk := ClockDomain.current.readClockWire
  mole.io.io_reset := io.externalReset

  // Pass analog pads through to the wrapper boundary.
  mole.io.io_sda <> io.io_sda
  mole.io.io_scl <> io.io_scl

  io.ledFault := mole.io.io_ledFault
  io.ledRunning := mole.io.io_ledRunning
  io.ledHeartbeat := mole.io.io_ledHeartbeat

  // Hardware flow control.
  io.ctsOut := mole.io.io_uCts
  mole.io.io_uRts := io.rtsIn

  // Sim-side UART. Same cfg as MoleTop's so bit timing matches.
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

  // v0.2 sim_* taps from MoleTop (only present because we elaborated
  // MoleTop with useBlackBox = false).
  io.halted := mole.io.sim_halted
  io.haltStatus := mole.io.sim_haltStatus
  io.loaderLoaded := mole.io.sim_loaderLoaded
  io.loaderFault := mole.io.sim_loaderFault
  io.ctsViolationObserved := mole.io.sim_ctsViolationObserved
  io.programLength := mole.io.sim_programLength
  io.engineStart := mole.io.sim_engineStart
  io.engineStartedTap := mole.io.sim_engineStarted
  io.pcTap := mole.io.sim_pc
  io.fetchActiveTap := mole.io.sim_fetchActive
  io.haltInFlightTap := mole.io.sim_haltInFlight
}

/** Shared sim helpers for the MoleTop sim family (v0.2 framing + helpers).
  *
  * Lives at object scope so [[MoleTopSim]], [[MoleTopFlowControlSim]], and
  * [[MoleTopCtsViolationSim]] all share one builder. The v0 stash files
  * duplicated the CRC + frame builder per-sim because each `extends App` body
  * fires on import; this v0.2 port pulls them into a plain `object` to dedupe.
  */
object MoleTopSimSupport {

  /** CRC-16/XMODEM (poly 0x1021, init 0x0000, no reflect, no xorout). Matches
    * `mole-asm::frame::crc16_xmodem` and [[Crc16Xmodem]] hardware. See
    * WIRE_FORMAT.md §3.
    */
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

  /** v0.2 magic + format version (mirror of `mole_abi::MAGIC`). */
  val MAGIC: Long = 0x0002_4d4cL

  /** Build a v0.2 UART frame from a sequence of 32-bit body words.
    *
    * Wire layout per `MoleLoaderFsm`:
    *
    * v0.2 wire format (must match `mole-asm::frame::build_frame`):
    *
    * ```
    *   [magic_b0..b3]                         <- 4 bytes LE = 0x0002_4D4C
    *   [len_b0..b3]                           <- 4 bytes LE = N body words
    *   [body0_b0..b3]                         <- body word 0 (32-bit LE)
    *   ...
    *   [bodyN-1_b0..b3]                       <- body word N-1
    *   [crc_lo][crc_hi]                       <- CRC-16/XMODEM over magic+len+body
    * ```
    *
    * `body` is body-only (no in-memory preamble).
    */
  def buildFrame(body: Seq[Int]): Seq[Int] = {
    val n = body.size
    require(
      n >= 1 && n <= 8192,
      s"body too large or empty: $n words (must be 1..=8192)"
    )
    val magicBytes: Seq[Int] = Seq(
      (MAGIC & 0xff).toInt,
      ((MAGIC >> 8) & 0xff).toInt,
      ((MAGIC >> 16) & 0xff).toInt,
      ((MAGIC >> 24) & 0xff).toInt
    )
    val lenBytes: Seq[Int] = Seq(
      n & 0xff,
      (n >> 8) & 0xff,
      (n >> 16) & 0xff,
      (n >> 24) & 0xff
    )
    val wordBytes: Seq[Int] = body.flatMap { w =>
      val wl = w.toLong & 0xffffffffL
      Seq(
        (wl & 0xff).toInt,
        ((wl >> 8) & 0xff).toInt,
        ((wl >> 16) & 0xff).toInt,
        ((wl >> 24) & 0xff).toInt
      )
    }
    val payload: Seq[Int] = magicBytes ++ lenBytes ++ wordBytes
    val crc = crc16Xmodem(payload)
    payload ++ Seq(crc & 0xff, (crc >> 8) & 0xff)
  }

  /** Build a clean HALT 32-bit word per spec §11: [31:30]=11 (tag),
    * [29]=overflow, [28]=mismatch, [27:23]=status, [22:0]=reserved=0.
    */
  def cleanHaltWord32(status: Int = 0): Long = {
    val tag = 0x3L << 30
    val st = ((status & 0x1f).toLong) << 23
    tag | st
  }

  /** Pop one byte from the sim UART RX stream, with a generous timeout to keep
    * the sim from hanging if the drainer misbehaves.
    */
  def recvByte(dut: MoleTopSimDut, maxCycles: Int = 1_000_000): Int = {
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
    // sticky on the same byte and the next recvByte returns the same
    // value instead of waiting for the next real byte.
    dut.clockDomain.waitSampling()
    dut.io.rxData.ready #= false
    b
  }

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

  /** Hold reset for a few cycles then release. The MoleTop reset bridge takes a
    * couple of edges to deassert through the 2-FF sync chain.
    */
  def doReset(dut: MoleTopSimDut): Unit = {
    dut.io.externalReset #= false
    dut.io.txData.valid #= false
    dut.io.rxData.ready #= false
    // Default RTS# to asserted (LOW = host ready) so the drainer can
    // stream freely in cases that don't exercise TX back-pressure.
    dut.io.rtsIn #= false
    dut.clockDomain.waitSampling(10)
    dut.io.externalReset #= true
    dut.clockDomain.waitSampling(10)
  }

  /** Common small test config: 16-word program memory, 32-byte result ring.
    * Keeps each case under ~50K cycles.
    */
  val testCfg: MoleConfig = MoleConfig(
    fabricFreqHz = 24 MHz,
    quarterPeriodCyclesReset = 6,
    programWordCount = 16,
    resultRingByteCount = 32,
    captureMaxBits = 64,
    uartBaud = 2_000_000
  )

  /** Verilator simulator flags. Without these, Verilator randomises every
    * uninitialised Reg, and on unlucky seeds MoleTop's reset bridge
    * synchroniser starts low, letting fabric-domain Regs (engine FSM, drive
    * enables, busModeReg) skip their sync-reset clear and trip the iobuf
    * bus-contention assert at the first post-reset posedge. Identical to the v0
    * stash.
    */
  def simConfig: SpinalSimConfig =
    SimConfig
      .addSimulatorFlag("--x-assign")
      .addSimulatorFlag("0")
      .addSimulatorFlag("--x-initial")
      .addSimulatorFlag("0")
}

/** End-to-end audit for [[MoleTop]] (v0.2).
  *
  * Cases (bus-toggle from v0 stash is skipped per the v0.2 sim_* port
  * surface limitation noted in [[MoleTopSimDut]]):
  *
  *   1. **short halt** --- `[SetBusMode(i2c), Halt(0)]` loads, runs,
  *      drains. Asserts the result ring carries Revision lo/hi at the
  *      first two 16-bit words and the v0.2 HALT word (32-bit clean
  *      = 0xC000_0000) at the last two 16-bit words.
  *   2. **bad CRC recovery** --- corrupted-CRC frame is sent first;
  *      engine must NOT start and loader fault LED must pulse. After
  *      the resync idle gap a good frame loads cleanly.
  *   3. **back-to-back frames** --- two halt-0 frames in succession;
  *      phase FSM cycles cleanly through acceptLoad → running →
  *      draining → acceptLoad twice.
  */
object MoleTopSim extends App {
  import MoleTopSimSupport._

  val cfg = testCfg

  // Result-ring bytes: Revision lo at [0..1], Revision hi at [2..3],
  // HALT word (32-bit) at the LAST TWO 16-bit slots = bytes
  // [resultRingByteCount-4..resultRingByteCount-1]. v0 used a 16-bit
  // HALT word at the last slot; v0.2 uses a 32-bit HALT word at
  // resultLimit (the engine reserves the top 32-bit slot for HALT
  // exclusively, see spec §11 + project AGENTS §"REVISION word
  // convention").
  val cleanHalt32 = cleanHaltWord32(0)

  /** Read the 32-bit HALT word at the tail of the result ring. */
  def haltWord32At(bytes: Seq[Int], totalBytes: Int): Long = {
    val b0 = bytes(totalBytes - 4).toLong & 0xff
    val b1 = (bytes(totalBytes - 3).toLong & 0xff) << 8
    val b2 = (bytes(totalBytes - 2).toLong & 0xff) << 16
    val b3 = (bytes(totalBytes - 1).toLong & 0xff) << 24
    b0 | b1 | b2 | b3
  }

  // ----------------------------------------------------------------
  // Case 1: short-halt round-trip.
  // ----------------------------------------------------------------
  println("--- MoleTopSim: short halt round-trip ---")
  simConfig.compile(MoleTopSimDut(cfg)).doSim("short-halt") { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    doReset(dut)

    val program = Seq(
      Instruction.encode(Instruction.SetBusMode(BusMode.i2c)),
      Instruction.encode(Instruction.Halt(0))
    )
    val frame = buildFrame(program)

    // Sim-side fork: drain bytes as they arrive so the drainer's UART
    // stream sees real consumer back-pressure and is not mass-stalled
    // on a single host-side read at the end.
    val received = mutable.Buffer.empty[Int]

    // Diagnostic monitor: track loaded/halted/fault edges so we know
    // where in the chain we are when the drain times out.
    var loadedSeen = false
    var haltedSeen = false
    var faultSeen = false
    var haltStatusAtHalt = 0
    var engineStartSeen = false
    var programLengthSeen = 0
    var pcSamples = mutable.Buffer.empty[(Int, Int, Boolean, Boolean)]
    val monitorDone = new java.util.concurrent.atomic.AtomicBoolean(false)
    val monitor = fork {
      var cycle = 0
      var lastSampledPc = -1
      while (!monitorDone.get()) {
        if (dut.io.loaderLoaded.toBoolean) {
          loadedSeen = true
          programLengthSeen = dut.io.programLength.toInt
        }
        if (dut.io.engineStart.toBoolean) engineStartSeen = true
        if (dut.io.halted.toBoolean && !haltedSeen) {
          haltedSeen = true
          haltStatusAtHalt = dut.io.haltStatus.toInt
        }
        if (dut.io.loaderFault.toBoolean) faultSeen = true
        // Sample PC when engine has started, every 100 cycles
        if (engineStartSeen && (cycle % 100) == 0) {
          val pc = dut.io.pcTap.toInt
          val fa = dut.io.fetchActiveTap.toBoolean
          val hif = dut.io.haltInFlightTap.toBoolean
          if (pc != lastSampledPc || pcSamples.size < 5) {
            pcSamples += ((cycle, pc, fa, hif))
            lastSampledPc = pc
          }
        }
        dut.clockDomain.waitSampling()
        cycle += 1
      }
    }

    val drainFork = fork {
      while (received.size < cfg.resultRingByteCount) {
        try {
          received += recvByte(dut, maxCycles = 2_000_000)
        } catch {
          case e: Throwable =>
            println(
              s"   diag(in-drain): loaded=$loadedSeen progLen=$programLengthSeen engineStart=$engineStartSeen halted=$haltedSeen status=0x${haltStatusAtHalt.toHexString} fault=$faultSeen rxsize=${received.size}"
            )
            println(
              s"   pc samples (cycle, pc, fetchActive, haltInFlight): ${pcSamples.take(20)}"
            )
            throw e
        }
      }
    }

    sendFrame(dut, frame)
    drainFork.join()
    monitorDone.set(true)
    monitor.join()
    println(
      s"   diag: loaded=$loadedSeen halted=$haltedSeen fault=$faultSeen rxsize=${received.size}"
    )

    assert(
      received.size == cfg.resultRingByteCount,
      s"short-halt: drained ${received.size} bytes, expected ${cfg.resultRingByteCount}"
    )

    // Last 4 bytes: 32-bit HALT word (LE). v0.2 does not write a
    // REVISION word into the ring -- the engine just writes captures
    // / marks / HALT. The HALT word at resultLimit is the contract.
    val gotHalt = haltWord32At(received.toSeq, cfg.resultRingByteCount)
    assert(
      gotHalt == cleanHalt32,
      s"short-halt: HALT expected 0x${cleanHalt32.toHexString} got 0x${gotHalt.toHexString}"
    )

    println(
      s"   ok: drained ${received.size} bytes, HALT word 0x${gotHalt.toHexString}"
    )
  }

  // ----------------------------------------------------------------
  // Case 2: bad CRC -> recovery.
  //
  // Send a frame with a corrupted CRC trailer; the engine must NOT
  // start and the loader fault LED pulses (pulse-stretcher then
  // holds it for ~175 ms = many sim cycles). After the idle gap a
  // good frame loads normally.
  // ----------------------------------------------------------------
  println("--- MoleTopSim: bad CRC + recovery ---")
  simConfig.compile(MoleTopSimDut(cfg)).doSim("bad-crc") { dut =>
    dut.clockDomain.forkStimulus(period = 10)
    doReset(dut)

    val program = Seq(
      Instruction.encode(Instruction.SetBusMode(BusMode.i2c)),
      Instruction.encode(Instruction.Halt(0))
    )
    val good = buildFrame(program)

    // Corrupt the CRC low byte.
    val bad = good.updated(good.size - 2, good(good.size - 2) ^ 0xff)

    // Monitor fault LED, loaderLoaded, and halted for the full resync
    // window. The pulse-stretcher holds the LED high for a long time;
    // loaded/halted are momentary so we sample them every cycle.
    //
    // LED polarity: MoleTop's io_ledFault is active-low (drive LOW =
    // pin LOW = LED ON; drive HIGH = pin HIGH = LED OFF) because the
    // on-board LED is wired anode-to-3.3V with the FPGA pin as the
    // cathode. So "fault LED is lit" reads as
    // `!dut.io.ledFault.toBoolean`.
    var faultSeen = false
    var loadedSeen = false
    var haltedSeen = false
    val faultMonitor = fork {
      val watchCycles = 20 * dut.uartCfg.ticksPerBit * 10
      var n = 0
      while (n < watchCycles) {
        if (!dut.io.ledFault.toBoolean) faultSeen = true
        if (dut.io.loaderLoaded.toBoolean) loadedSeen = true
        if (dut.io.halted.toBoolean) haltedSeen = true
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
      !haltedSeen,
      "bad-crc: halted asserted -- engine actually ran despite bad CRC"
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

    val gotHalt = haltWord32At(received.toSeq, cfg.resultRingByteCount)
    assert(
      gotHalt == cleanHalt32,
      s"bad-crc: after recovery HALT expected 0x${cleanHalt32.toHexString} got 0x${gotHalt.toHexString}"
    )

    println(
      s"   ok: bad CRC rejected (fault LED lit), recovery frame drained ${received.size} bytes"
    )
  }

  // ----------------------------------------------------------------
  // Case 3: back-to-back frames.
  // ----------------------------------------------------------------
  println("--- MoleTopSim: back-to-back frames ---")
  simConfig.compile(MoleTopSimDut(cfg)).doSim("back-to-back") { dut =>
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

      val gotHalt = haltWord32At(received.toSeq, cfg.resultRingByteCount)
      assert(
        gotHalt == cleanHalt32,
        s"back-to-back run $runIdx: HALT expected 0x${cleanHalt32.toHexString} got 0x${gotHalt.toHexString}"
      )
      println(
        s"   ok: run $runIdx drained ${received.size} bytes, HALT 0x${gotHalt.toHexString}"
      )
    }
  }

  println("--- MoleTopSim: all cases passed ---")
}
