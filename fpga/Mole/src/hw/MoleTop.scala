package mole

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

/** Top-level wiring for the Mole iCEbreaker bring-up.
  *
  * Wires the host-link loader, the SPRAM, the [[BitCycleEngineCore]], the
  * result-ring drainer and the UART together, and exposes the iCEbreaker pinout
  * from `icebreaker.pcf`.
  *
  * ==Clocking and reset==
  *
  *   - The 12 MHz package pin `io_clk` feeds a [[MolePllUp5k]] that produces a
  *     48 MHz fabric clock.
  *   - The external `io_reset` button is active-low. The reset bridge
  *     async-asserts the fabric reset on either `!pll.locked` or `!io_reset`,
  *     and sync-deasserts it through a 2-FF chain in the fabric clock domain.
  *     The boot domain (the one that hosts the 2-FF chain) has its own async
  *     reset wired straight to `resetAsync`, so any fresh assertion of either
  *     source pulls the fabric back into reset immediately.
  *   - All synchronous logic lives in `fabricCd`. The outer Component never
  *     instantiates a flop in its own clock domain, so the generated Verilog
  *     only has the listed package-pin ports --- no spurious `clk` / `reset`
  *     top-level.
  *
  * ==Top-level phase FSM==
  *
  *   - `acceptLoadState` --- loader owns `loaderWrite`; engine is held off
  *     (`start := False`); drainer is idle; UART RX is gated open for the
  *     loader. Transitions to `runningState` on `loader.io.loaded`.
  *   - `runningState` --- engine owns the SPRAM read port and `resultWrite`.
  *     The wrapper drives `engine.start := True` until the engine actually
  *     leaves idle (tracked by `engineStarted`), then drops it so the engine
  *     cannot self-restart on the cycle it reports `done` again. Transitions to
  *     `drainingState` the cycle `engine.done` rises (i.e.
  *     `engineStarted && engine.done`) and pulses `drainer.triggerDrain` in the
  *     same cycle.
  *   - `drainingState` --- drainer owns the SPRAM read port and the UART TX
  *     stream. Transitions back to `acceptLoadState` the cycle
  *     `drainer.drainComplete` pulses.
  *
  * The loader's `acceptRx` gate is high only in `acceptLoadState`. A mid-frame
  * phase change (e.g. the engine halts and we move to `drainingState` while the
  * host is still streaming) faults the loader's partial state via the
  * gate-drop, which is what we want --- the host MUST honour the resync gap
  * between frames, as documented in `WIRE_FORMAT.md` and the bring-up doc.
  *
  * ==Closed-phase RX drain==
  *
  * When `acceptRx = False`, MoleTop drives `uartRx.payload.ready := True` so
  * any bytes that arrive during `runningState` or `drainingState` are consumed
  * and discarded at the UART boundary. Without this, `UartRx.payload.valid`
  * stays sticky on the last received byte until the consumer asserts `ready`,
  * and the loader would happily latch that stale value as the first byte of a
  * new frame the cycle `acceptRx` re-opens. The loader's own `rx.valid` input
  * is also gated by `acceptRx` here so its idleState peek cannot see the stale
  * byte either.
  *
  * The phase FSM directly implements the spec's `runInFlight` latch:
  * `drainingState` is reachable only from `runningState`, which is reachable
  * only from a `loader.io.loaded` pulse. The very first power-up therefore
  * enters `acceptLoadState` (not `drainingState`) even though the engine
  * reports `done` from the start.
  *
  * ==SPRAM port arbitration==
  *
  *   - Reads: muxed by phase between `engine.programReadCmd` (during
  *     `runningState`) and `drainer.readCmd` (during `drainingState`). During
  *     `acceptLoadState` no consumer issues a read, so the SPRAM controller
  *     idles. The 1-cycle SPRAM read latency is fine across the running ->
  *     draining edge because the engine has halted before we transition, so no
  *     `programReadCmd.fire` from the engine is ever in flight at the boundary;
  *     symmetrically, the drainer is in `idleState` at the draining ->
  *     acceptLoad boundary so no `readCmd.fire` from the drainer is in flight
  *     either.
  *   - Writes: `loaderWrite` is fed unconditionally from the loader's
  *     `programWrite` (the loader only writes during acceptLoad anyway, because
  *     its `acceptRx` gate is closed otherwise); `resultWrite` is fed
  *     unconditionally from the engine's `resultWrite` (the engine only writes
  *     during a real run).
  *
  * ==LED state==
  *
  *   - `io_ledR` --- pulse-stretched loader fault. When `loader.fault` fires, a
  *     22-bit downcounter is loaded; the LED stays lit until it decays (~87 ms
  *     at 48 MHz, comfortably visible).
  *   - `io_ledG` --- `!engine.done`, i.e. high whenever the engine is not in
  *     its idle state. Direct visual indication of "the engine is busy".
  *   - `io_ledB` --- heartbeat. A 26-bit counter that only increments while the
  *     engine is idle; the LED follows the counter's top bit AND `engine.done`.
  *     While running, the LED is off; while idle, it blinks at about 0.7 Hz so
  *     a freshly-flashed board announces itself.
  *
  * @param cfg
  *   Mole compile-time configuration. Pin and clock topology come straight from
  *   the iCEbreaker; only the UART baud, ring sizes and program word count are
  *   configurable here.
  * @param useBlackBox
  *   When true (default), instantiate the synth-only primitives
  *   (`SB_PLL40_PAD`, `SB_IO`, `SB_SPRAM256KA`). When false, swap each for the
  *   sim-only bypass model so MoleTopSim can run under Verilator.
  */
case class MoleTop(
    cfg: MoleConfig = MoleConfig(),
    useBlackBox: Boolean = true
) extends Component {

  // Spinal accepts HertzNumber arithmetic; turn it into an Int once
  // for UartConfig and the idle-gap math below.
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

  // Loader resync gap: 20 UART bit periods of continuous idle-high on
  // RX before the loader re-opens for the next frame.
  val idleGapCycles: Int = 20 * uartCfg.ticksPerBit

  val io = new Bundle {

    /** 12 MHz package-pin clock from the iCEbreaker oscillator. Feeds the PLL
      * only; no fabric flop is in this domain.
      */
    val io_clk = in Bool ()

    /** Active-low external reset button (iCEbreaker user button, pulled high
      * externally).
      */
    val io_reset = in Bool ()

    /** USB-UART RX line (host to FPGA). Asynchronous; the loader and the UART
      * receiver each cross it into the fabric domain themselves.
      */
    val io_uRx = in Bool ()

    /** USB-UART TX line (FPGA to host). Idles high. */
    val io_uTx = out Bool ()

    /** I3C / I2C SCL pad. */
    val io_scl = inout(Analog(Bool()))

    /** I3C / I2C SDA pad. */
    val io_sda = inout(Analog(Bool()))

    /** Red LED: pulse-stretched loader fault. */
    val io_ledR = out Bool ()

    /** Green LED: engine is busy (`!engine.done`). */
    val io_ledG = out Bool ()

    /** Blue LED: heartbeat while idle. */
    val io_ledB = out Bool ()

    /** Sim-only debug taps for engine bus drivers and key status signals.
      *
      * These are present only when `useBlackBox = false` (i.e. the sim-bypass
      * elaboration path). MoleTopSim reads them through the standard IO
      * boundary instead of reaching into `fabric.engine.io.bus` directly, which
      * would violate SpinalHDL's hierarchy check.
      *
      * Synth bitstreams have `useBlackBox = true` and so do not pay for these
      * outputs at all.
      */
    val sim_sdaDriveLow = (!useBlackBox) generate (out Bool ())
    val sim_sdaDriveHigh = (!useBlackBox) generate (out Bool ())
    val sim_sclDriveLow = (!useBlackBox) generate (out Bool ())
    val sim_sclDriveHigh = (!useBlackBox) generate (out Bool ())
    val sim_engineDone = (!useBlackBox) generate (out Bool ())
    val sim_loaderLoaded = (!useBlackBox) generate (out Bool ())
    val sim_loaderFault = (!useBlackBox) generate (out Bool ())
    val sim_engineStart = (!useBlackBox) generate (out Bool ())
    val sim_engineResultWriteFire = (!useBlackBox) generate (out Bool ())
    val sim_drainTrigger = (!useBlackBox) generate (out Bool ())
    val sim_phaseRunning = (!useBlackBox) generate (out Bool ())
    val sim_phaseDraining = (!useBlackBox) generate (out Bool ())
  }
  noIoPrefix()

  // PLL: 12 MHz -> 48 MHz. The sim-bypass variant just passes io_clk
  // through and reports `locked = True` so MoleTopSim does not need
  // an SB_PLL40_PAD model.
  val pll = MolePllUp5k(useBlackBox = useBlackBox)
  pll.io.clkIn := io.io_clk
  pll.io.resetB := io.io_reset

  // Reset bridge: any one of !pll.locked or !io_reset asserts the
  // fabric reset asynchronously. Deassertion is synchronised through
  // a 2-FF chain whose own reset input is `resetAsync`, so a fresh
  // assertion immediately re-flushes the chain.
  val resetAsync: Bool = !pll.io.locked || !io.io_reset

  val bootCd = ClockDomain(
    clock = pll.io.clkOut,
    reset = resetAsync,
    config = ClockDomainConfig(
      clockEdge = RISING,
      resetKind = ASYNC,
      resetActiveLevel = HIGH
    )
  )

  val resetSync: Bool = bootCd {
    // Both FFs reset asynchronously to True (asserted); they shift
    // False through on each clock edge once `resetAsync` goes low.
    val s0 = RegNext(False) init True
    val s1 = RegNext(s0) init True
    s1
  }

  val fabricCd = ClockDomain(
    clock = pll.io.clkOut,
    reset = resetSync,
    config = ClockDomainConfig(
      clockEdge = RISING,
      resetKind = SYNC,
      resetActiveLevel = HIGH
    )
  )

  val fabric = new ClockingArea(fabricCd) {

    // ----------------------------------------------------------------
    // Sub-blocks
    // ----------------------------------------------------------------

    val uartRx = UartRx(uartCfg)
    val uartTx = UartTx(uartCfg)

    val spram = SpramController(cfg, useBlackBox = useBlackBox)

    val loader = MoleLoaderFsm(
      programWordCount = cfg.programWordCount,
      addrWidth = spram.addrWidth,
      idleGapCycles = idleGapCycles
    )

    val engine = BitCycleEngineCore(cfg)

    val drainer = MoleDrainerFsm(
      resultBase = engine.resultBase,
      resultWordCount = engine.resultWordCount,
      addrWidth = engine.addrWidth
    )

    val iobuf = MoleIoBufUp5k(useBlackBox = useBlackBox)

    // ----------------------------------------------------------------
    // UART wiring
    // ----------------------------------------------------------------

    // Constant phase increments synthesised into the DDS adders. The
    // RX side oversamples at 16x.
    val txPhaseInc = BaudGenerator.phaseIncFor(fabricFreqHz, cfg.uartBaud)
    val rxPhaseInc =
      BaudGenerator.phaseIncFor(fabricFreqHz, cfg.uartBaud * uartCfg.oversample)

    uartTx.io.baudPhaseInc :=
      U(BigInt(txPhaseInc), BaudGenerator.defaultAccWidth bits)
    uartRx.io.baudPhaseInc :=
      U(BigInt(rxPhaseInc), BaudGenerator.defaultAccWidth bits)

    uartRx.io.rx := io.io_uRx
    io.io_uTx := uartTx.io.tx

    // ----------------------------------------------------------------
    // IO bus (SDA / SCL pads)
    // ----------------------------------------------------------------

    iobuf.io.bus <> engine.io.bus
    iobuf.io.sdaPad <> io.io_sda
    iobuf.io.sclPad <> io.io_scl

    // ----------------------------------------------------------------
    // Top-level phase FSM
    // ----------------------------------------------------------------

    // Default drives for phase-gated signals; the FSM overrides as
    // needed inside each state. These are wires (Bool()), not Regs.
    val engineStartDrv = Bool()
    val drainTriggerComb = Bool()
    val acceptRxComb = Bool()

    engineStartDrv := False
    drainTriggerComb := False
    acceptRxComb := False

    // `engineStarted` latches True as soon as the engine leaves idle
    // (engine.done goes low). It is read inside `runningState` to
    // drop `engine.start` once the engine is truly running, so when
    // the engine reports `done` again at the end of the program the
    // wrapper does NOT keep `start` high and trigger another run.
    val engineStarted = Reg(Bool()) init False

    val phase = new StateMachine {

      val acceptLoadState: State = new State with EntryPoint {
        whenIsActive {
          acceptRxComb := True
          when(loader.io.loaded) {
            engineStarted := False
            goto(runningState)
          }
        }
      }

      val runningState: State = new State {
        whenIsActive {
          // Hold start high until the engine has actually moved off
          // idle. Once `engineStarted` latches True, drop start so
          // the engine re-enters idle naturally at the end of the
          // program and stays there.
          engineStartDrv := !engineStarted
          when(!engine.io.done) {
            engineStarted := True
          }
          when(engineStarted && engine.io.done) {
            // Same cycle the engine reports done: trigger the
            // drainer and transition. `engineStartDrv` is already
            // False here (because `engineStarted` is True), so the
            // engine sees start = 0 on the cycle it returns to
            // idle and will not auto-restart.
            drainTriggerComb := True
            goto(drainingState)
          }
        }
      }

      val drainingState: State = new State {
        whenIsActive {
          when(drainer.io.drainComplete) {
            goto(acceptLoadState)
          }
        }
      }
    }

    engine.io.start := engineStartDrv
    drainer.io.triggerDrain := drainTriggerComb
    loader.io.acceptRx := acceptRxComb

    // ----------------------------------------------------------------
    // Loader <-> UART RX
    //
    // When the phase FSM closes the loader (acceptRx = False) we
    // ALSO drive `uartRx.payload.ready := True` so any bytes the host
    // sends during a run are consumed and discarded at the UartRx
    // boundary. Without this drain, UartRx's `payload.valid` stays
    // sticky on the last byte it received, and the loader would
    // consume that stale byte as the first byte of the next frame
    // the instant `acceptRx` re-opens. Mute the loader's `rx.valid`
    // input during closed phases so the loader's idleState peek
    // (`when(io.rx.valid && io.acceptRx)`) never fires on a stale
    // value either.
    // ----------------------------------------------------------------

    loader.io.rx.valid := uartRx.io.payload.valid && acceptRxComb
    loader.io.rx.payload := uartRx.io.payload.payload
    uartRx.io.payload.ready := Mux(
      acceptRxComb,
      loader.io.rx.ready,
      True
    )

    loader.io.rxFramingError := uartRx.io.framingError && acceptRxComb
    loader.io.rxParityError := uartRx.io.parityError && acceptRxComb
    loader.io.rxOverrun := uartRx.io.overrun && acceptRxComb
    loader.io.uRxRaw := io.io_uRx

    // ----------------------------------------------------------------
    // SPRAM port wiring
    // ----------------------------------------------------------------

    // Loader writes feed `loaderWrite` directly. The loader's
    // `acceptRx` gate keeps it from issuing writes outside
    // acceptLoad, so no phase mux is needed.
    spram.io.loaderWrite <> loader.io.programWrite

    // Engine result-ring writes feed `resultWrite` directly. The
    // engine only writes while busy (running), so likewise no phase
    // mux is needed.
    spram.io.resultWrite <> engine.io.resultWrite

    // Read port: muxed by phase between the engine and the drainer.
    // In acceptLoad neither asserts valid; in running the engine
    // wins; in draining the drainer wins. This is the spec's "read
    // mux gated by phase, not raw engine.done" requirement.
    val drainerOwnsRead = phase.isActive(phase.drainingState)

    spram.io.readCmd.valid := Mux(
      drainerOwnsRead,
      drainer.io.readCmd.valid,
      engine.io.programReadCmd.valid
    )
    spram.io.readCmd.payload := Mux(
      drainerOwnsRead,
      drainer.io.readCmd.payload,
      engine.io.programReadCmd.payload
    )

    engine.io.programReadCmd.ready := spram.io.readCmd.ready && !drainerOwnsRead
    drainer.io.readCmd.ready := spram.io.readCmd.ready && drainerOwnsRead

    // Read response fans out to both consumers; the inactive one's
    // FSM stays in its waiting state but never issued a read in the
    // current phase so the spurious `valid` cannot cause progress.
    // (The engine's fetchWait expects exactly one response per
    // issued read; the drainer's waitResp expects the same. Because
    // the mux above never lets both issue reads simultaneously, only
    // one consumer ever has a read in flight at a time.)
    engine.io.programReadResp.valid := spram.io.readResp.valid && !drainerOwnsRead
    engine.io.programReadResp.payload := spram.io.readResp.payload
    drainer.io.readResp.valid := spram.io.readResp.valid && drainerOwnsRead
    drainer.io.readResp.payload := spram.io.readResp.payload

    // ----------------------------------------------------------------
    // Drainer -> UART TX
    // ----------------------------------------------------------------

    uartTx.io.data << drainer.io.txData

    // ----------------------------------------------------------------
    // LEDs
    // ----------------------------------------------------------------

    // Red: pulse-stretched loader fault. ~87 ms at 48 MHz so a
    // 1-cycle pulse is comfortably visible.
    val faultStretchWidth = 22
    val faultStretchMax = (1 << faultStretchWidth) - 1
    val faultCounter = Reg(UInt(faultStretchWidth bits)) init 0
    when(loader.io.fault) {
      faultCounter := U(faultStretchMax, faultStretchWidth bits)
    } elsewhen (faultCounter =/= 0) {
      faultCounter := faultCounter - 1
    }
    io.io_ledR := faultCounter =/= 0

    // Green: engine running (engine is busy, not in idle).
    io.io_ledG := !engine.io.done

    // Blue: heartbeat. Increments only while engine is idle so it
    // freezes during a run; gating with `engine.done` keeps the LED
    // dark while running regardless of where the counter froze.
    val heartbeatWidth = 26
    val heartbeatCounter = Reg(UInt(heartbeatWidth bits)) init 0
    when(engine.io.done) {
      heartbeatCounter := heartbeatCounter + 1
    }
    io.io_ledB := heartbeatCounter.msb && engine.io.done

    // ----------------------------------------------------------------
    // Sim debug taps (only present when useBlackBox = false)
    // ----------------------------------------------------------------

    if (!useBlackBox) {
      io.sim_sdaDriveLow := engine.io.bus.sda.driveLow
      io.sim_sdaDriveHigh := engine.io.bus.sda.driveHigh
      io.sim_sclDriveLow := engine.io.bus.scl.driveLow
      io.sim_sclDriveHigh := engine.io.bus.scl.driveHigh
      io.sim_engineDone := engine.io.done
      io.sim_loaderLoaded := loader.io.loaded
      io.sim_loaderFault := loader.io.fault
      io.sim_engineStart := engineStartDrv
      io.sim_engineResultWriteFire := engine.io.resultWrite.fire
      io.sim_drainTrigger := drainTriggerComb
      io.sim_phaseRunning := phase.isActive(phase.runningState)
      io.sim_phaseDraining := phase.isActive(phase.drainingState)
    }
  }
}
