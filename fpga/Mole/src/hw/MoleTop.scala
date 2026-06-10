package mole

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

/** Top-level wiring for the Mole v0.2 iCEbreaker bring-up.
  *
  * ==v0.2 changes vs v0 stash==
  *
  *   - Two `ClockDomain`s: `engineCd` (48 MHz from PLL) and `uartCd` (24 MHz
  *     from the PLL's ÷2 toggle FF). The v0 stash used a single `fabricCd` at
  *     24 MHz; v0.2 doubles the engine clock.
  *   - [[EnginePipeline]] replaces the v0 monolithic [[BitCycleEngineCore]]
  *     (which is stashed under `src/attic/`).
  *   - [[LoaderWidthAdapter]] bridges the v0 [[MoleLoaderFsm]] (16-bit output)
  *     to the v0.2 32-bit [[SpramController]].
  *   - Loader and drainer stay in `engineCd` for C.6. The CDC to `uartCd` (via
  *     `StreamFifoCC`) lands in C.10.
  *   - UART is still in `engineCd` for C.6 (same as v0). `uartCd` plumbing
  *     lands in C.10.
  *   - `programLength` comes from [[LoaderWidthAdapter]].programLength32.
  *
  * ==Reset bridge==
  *
  * Preserved from v0 stash: async-assert on `!pll.locked || !io_reset`,
  * sync-deassert through a 2-FF chain in `bootCd`.
  *
  * ==Phase FSM==
  *
  *   - `acceptLoadState` --- loader owns SPRAM writes; engine held by
  *     `engineStart`. Transitions to `runningState` on `loader.io.loaded`.
  *   - `runningState` --- engine runs. Transitions to `drainingState` when
  *     `engine.io.halted` rises and the drainer trigger fires.
  *   - `drainingState` --- drainer sweeps the result ring. Transitions back to
  *     `acceptLoadState` on `drainer.io.drainComplete`.
  *
  * ==SPRAM port arbitration==
  *
  * `SpramController` owns arbitration among its three ports: read (highest
  * priority), resultWrite, loaderWrite (lowest). MoleTop muxes the single SPRAM
  * `readCmd` between the engine and the drainer based on `drainerOwnsRead`.
  *
  * @param cfg
  *   Mole compile-time configuration.
  * @param useBlackBox
  *   When true, instantiate synth-only primitives (PLL, SB_IO, SPRAM BlackBox).
  *   When false, use sim-bypass models (Verilator-compatible).
  */
case class MoleTop(
    cfg: MoleConfig = MoleConfig(),
    useBlackBox: Boolean = true
) extends Component {

  val fabricFreqHz: Int = cfg.fabricFreqHz.toBigDecimal.toInt

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

  // Resync gap for the loader: 20 UART bit periods of idle on RX.
  val idleGapCycles: Int = 20 * uartCfg.ticksPerBit

  // SPRAM address width derived from cfg directly (not from `pipeline`).
  // `LoaderWidthAdapter` and `SpramController` are constructed before
  // `pipeline` inside the engine ClockingArea; routing this through
  // `pipeline.spramAddrWidth` would dereference a not-yet-assigned field
  // (SpinalHDL elaborates Component bodies eagerly). The pipeline computes
  // the same value from the same inputs — see EnginePipeline.scala:233.
  val spramAddrWidth: Int = log2Up(
    cfg.programWordCount + (cfg.resultRingByteCount + 3) / 4
  )

  val io = new Bundle {
    val io_clk = in Bool ()
    val io_reset = in Bool ()
    val io_uRx = in Bool ()
    val io_uTx = out Bool ()
    val io_uCts = out Bool ()
    val io_uRts = in Bool ()
    val io_scl = inout(Analog(Bool()))
    val io_sda = inout(Analog(Bool()))
    val io_ledR = out Bool ()
    val io_ledG = out Bool ()
    val io_ledB = out Bool ()

    // Sim-only debug taps (absent when useBlackBox=true → synthesis).
    val sim_halted = (!useBlackBox) generate (out Bool ())
    val sim_haltStatus = (!useBlackBox) generate (out UInt (5 bits))
    val sim_loaderLoaded = (!useBlackBox) generate (out Bool ())
    val sim_loaderFault = (!useBlackBox) generate (out Bool ())
    val sim_ctsViolationObserved = (!useBlackBox) generate (out Bool ())
  }
  noIoPrefix()

  // --------------------------------------------------------------------------
  // PLL: 12 MHz → 48 MHz engineClk + 24 MHz uartClk
  // --------------------------------------------------------------------------
  val pll = MolePllUp5k(useBlackBox = useBlackBox)
  pll.io.clkIn := io.io_clk
  pll.io.resetB := io.io_reset

  // --------------------------------------------------------------------------
  // Reset bridge: async-assert on !locked || !io_reset; sync-deassert 2-FF.
  // --------------------------------------------------------------------------
  val resetAsync: Bool = !pll.io.locked || !io.io_reset

  val bootCd = ClockDomain(
    clock = pll.io.clkOutEngine,
    reset = resetAsync,
    config = ClockDomainConfig(
      clockEdge = RISING,
      resetKind = ASYNC,
      resetActiveLevel = HIGH
    )
  )

  val resetSync: Bool = bootCd {
    val s0 = RegNext(False) init True
    val s1 = RegNext(s0) init True
    s1
  }

  // Engine domain: 48 MHz, synchronous reset.
  val engineCd = ClockDomain(
    clock = pll.io.clkOutEngine,
    reset = resetSync,
    config = ClockDomainConfig(
      clockEdge = RISING,
      resetKind = SYNC,
      resetActiveLevel = HIGH
    )
  )

  // UART domain: 24 MHz, synchronous reset.
  // For C.6 the UART is clocked by engineCd; uartCd is prepared here so the
  // port wiring is stable when C.10 migrates the UART blocks into it.
  val uartCd = ClockDomain(
    clock = pll.io.clkOutUart,
    reset = resetSync,
    config = ClockDomainConfig(
      clockEdge = RISING,
      resetKind = SYNC,
      resetActiveLevel = HIGH
    )
  )

  // --------------------------------------------------------------------------
  // All synchronous logic runs in engineCd.
  // --------------------------------------------------------------------------
  val engine = new ClockingArea(engineCd) {

    // ---- Sub-blocks --------------------------------------------------------
    val uartRx = UartRx(uartCfg)
    val uartTx = UartTx(uartCfg)

    val spram = SpramController(cfg, useBlackBox = useBlackBox)

    val loader = MoleLoaderFsm(
      programWordCount =
        cfg.programWordCount * 2, // 16-bit words = 2× 32-bit words
      addrWidth = spram.addrWidth,
      idleGapCycles = idleGapCycles
    )

    val loaderAdapter = LoaderWidthAdapter(spramAddrWidth)

    val pipeline = EnginePipeline(cfg)

    val drainer = MoleDrainerFsm(
      resultBase = cfg.programWordCount,
      resultWordCount = (cfg.resultRingByteCount + 3) / 4,
      addrWidth = spram.addrWidth
    )

    val iobuf = MoleIoBufUp5k(useBlackBox = useBlackBox)

    // ---- UART baud DDS -------------------------------------------------------
    val txPhaseInc = BaudGenerator.phaseIncFor(fabricFreqHz, cfg.uartBaud)
    val rxPhaseInc =
      BaudGenerator.phaseIncFor(fabricFreqHz, cfg.uartBaud * uartCfg.oversample)

    uartTx.io.baudPhaseInc :=
      U(BigInt(txPhaseInc), BaudGenerator.defaultAccWidth bits)
    uartRx.io.baudPhaseInc :=
      U(BigInt(rxPhaseInc), BaudGenerator.defaultAccWidth bits)

    uartRx.io.rx := io.io_uRx
    io.io_uTx := uartTx.io.tx

    // ---- IO bus (SDA / SCL pads) ------------------------------------------
    iobuf.io.bus.sda <> pipeline.io.sda
    iobuf.io.bus.scl <> pipeline.io.scl
    iobuf.io.sdaPad <> io.io_sda
    iobuf.io.sclPad <> io.io_scl

    // Tie SDA/SCL sampled inputs from the IO buffer to the pipeline.
    pipeline.io.sdaSampled := iobuf.io.bus.sda.read
    pipeline.io.sclSampled := iobuf.io.bus.scl.read

    // ---- Top-level phase FSM -----------------------------------------------

    // `engineStarted` latches True once the engine has left idle; used to drop
    // engineStart so a second halt does not auto-restart.
    val engineStarted = Reg(Bool()) init False

    // Combinational phase-control wires; FSM overrides inside each state.
    val engineStartDrv = Bool()
    val drainTriggerComb = Bool()
    val acceptRxComb = Bool()

    engineStartDrv := False
    drainTriggerComb := False
    acceptRxComb := False

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
          engineStartDrv := !engineStarted
          when(!engineStarted && !pipeline.io.halted) {
            // Engine has started (left idle).
          }
          // Once engineStart asserted, track that we started.
          when(engineStartDrv) {
            engineStarted := True
          }
          when(engineStarted && pipeline.io.halted) {
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

    pipeline.io.engineStart := engineStartDrv
    pipeline.io.programLength := loaderAdapter.io.programLength32
      .resize(pipeline.programLenWidth bits)
    drainer.io.triggerDrain := drainTriggerComb
    loader.io.acceptRx := acceptRxComb

    // ---- Loader width adapter: 16-bit → 32-bit ----------------------------
    loaderAdapter.io.loaderIn <> loader.io.programWrite

    // ---- SPRAM port wiring -------------------------------------------------

    // Loader writes: via the width adapter.
    spram.io.loaderWrite <> loaderAdapter.io.spramOut

    // Engine result ring writes.
    spram.io.resultWrite <> pipeline.io.ringWrite

    // Read port: muxed between engine and drainer by phase.
    val drainerOwnsRead = phase.isActive(phase.drainingState)

    spram.io.readCmd.valid := Mux(
      drainerOwnsRead,
      drainer.io.readCmd.valid,
      pipeline.io.spramRead.valid
    )
    spram.io.readCmd.payload := Mux(
      drainerOwnsRead,
      drainer.io.readCmd.payload,
      pipeline.io.spramRead.payload
    )

    pipeline.io.spramRead.ready :=
      spram.io.readCmd.ready && !drainerOwnsRead
    drainer.io.readCmd.ready :=
      spram.io.readCmd.ready && drainerOwnsRead

    // Read response fans out to both; only one has a read in flight at a time.
    pipeline.io.spramResp.valid :=
      spram.io.readResp.valid && !drainerOwnsRead
    pipeline.io.spramResp.payload := spram.io.readResp.payload
    drainer.io.readResp.valid :=
      spram.io.readResp.valid && drainerOwnsRead
    drainer.io.readResp.payload := spram.io.readResp.payload.resize(16 bits)

    // ---- Loader ↔ UART RX --------------------------------------------------

    loader.io.rx.valid := uartRx.io.payload.valid && acceptRxComb
    loader.io.rx.payload := uartRx.io.payload.payload
    uartRx.io.payload.ready := Mux(acceptRxComb, loader.io.rx.ready, True)

    // CTS#-violation sticky observable (F-FPGA-007).
    val ctsViolationObservedReg = Reg(Bool()) init False
    when(!acceptRxComb && uartRx.io.payload.valid) {
      ctsViolationObservedReg := True
    }

    loader.io.rxFramingError := uartRx.io.framingError && acceptRxComb
    loader.io.rxParityError := uartRx.io.parityError && acceptRxComb
    loader.io.rxOverrun := uartRx.io.overrun && acceptRxComb
    loader.io.uRxRaw := io.io_uRx

    // ---- UART hardware flow control ----------------------------------------
    io.io_uCts := !acceptRxComb

    val rtsSync = RxSync()
    rtsSync.io.asyncIn := io.io_uRts
    val rtsDeasserted = rtsSync.io.syncOut

    uartTx.io.data << drainer.io.txData.haltWhen(rtsDeasserted)

    // ---- LEDs --------------------------------------------------------------

    // Red: pulse-stretched loader fault OR CTS violation.
    val faultStretchWidth = 22
    val faultStretchMax = (1 << faultStretchWidth) - 1
    val faultCounter = Reg(UInt(faultStretchWidth bits)) init 0
    when(loader.io.fault) {
      faultCounter := U(faultStretchMax, faultStretchWidth bits)
    } elsewhen (faultCounter =/= 0) {
      faultCounter := faultCounter - 1
    }
    io.io_ledR := (faultCounter =/= 0) || ctsViolationObservedReg

    // Green: engine is running (not halted after a start).
    io.io_ledG := engineStarted && !pipeline.io.halted

    // Blue: heartbeat while idle.
    val heartbeatWidth = 26
    val heartbeatCounter = Reg(UInt(heartbeatWidth bits)) init 0
    when(pipeline.io.halted || !engineStarted) {
      heartbeatCounter := heartbeatCounter + 1
    }
    io.io_ledB := heartbeatCounter.msb && pipeline.io.halted

    // ---- Sim-only debug taps -----------------------------------------------
    if (!useBlackBox) {
      io.sim_halted := pipeline.io.halted
      io.sim_haltStatus := pipeline.io.haltStatus
      io.sim_loaderLoaded := loader.io.loaded
      io.sim_loaderFault := loader.io.fault
      io.sim_ctsViolationObserved := ctsViolationObservedReg
    }
  }
}
