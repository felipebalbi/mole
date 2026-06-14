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
  *   - [[MoleLoaderFsm]] emits 32-bit writes directly into [[SpramController]]
  *     (v0.2 ISA is 32-bit; the v0-era [[LoaderWidthAdapter]] is retired).
  *   - Loader and drainer stay in `engineCd` for C.6. The CDC to `uartCd` (via
  *     `StreamFifoCC`) lands in C.10.
  *   - UART is still in `engineCd` for C.6 (same as v0). `uartCd` plumbing
  *     lands in C.10.
  *   - `programLength` comes from [[MoleLoaderFsm]].`programLength` (32-bit
  *     word count latched at `lenHi`).
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
    // 8× RX oversample paired with the 2 Mbaud uartBaud default
    // (MoleConfig). baudRate * oversample = 16 MHz < 24 MHz uartClk,
    // satisfying the UartConfig elaboration guard with ~33% headroom.
    // For 1 Mbaud × 16× fallback (see MoleConfig docstring), set
    // oversample = 16 here.
    oversample = 8
  )

  // Resync gap for the loader: 20 UART bit periods of idle on RX.
  val idleGapCycles: Int = 20 * uartCfg.ticksPerBit

  // SPRAM address width derived from cfg directly (not from `pipeline`).
  // `SpramController` is constructed before `pipeline` inside the engine
  // ClockingArea; routing this through `pipeline.spramAddrWidth` would
  // dereference a not-yet-assigned field (SpinalHDL elaborates Component
  // bodies eagerly). The pipeline computes the same value from the same
  // inputs — see EnginePipeline.scala:233.
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
    val io_ledFault = out Bool ()
    val io_ledRunning = out Bool ()
    val io_ledHeartbeat = out Bool ()

    // Sim-only debug taps (absent when useBlackBox=true → synthesis).
    val sim_halted = (!useBlackBox) generate (out Bool ())
    val sim_haltStatus = (!useBlackBox) generate (out UInt (5 bits))
    val sim_loaderLoaded = (!useBlackBox) generate (out Bool ())
    val sim_loaderFault = (!useBlackBox) generate (out Bool ())
    val sim_ctsViolationObserved = (!useBlackBox) generate (out Bool ())
    // Diagnostic taps for C.11.f debug (TODO: remove once silicon settles).
    val sim_programLength = (!useBlackBox) generate (out UInt (16 bits))
    val sim_engineStart = (!useBlackBox) generate (out Bool ())
    val sim_engineStarted = (!useBlackBox) generate (out Bool ())
    val sim_pc = (!useBlackBox) generate (out UInt (13 bits))
    val sim_fetchActive = (!useBlackBox) generate (out Bool ())
    val sim_haltInFlight = (!useBlackBox) generate (out Bool ())
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
      programWordCount = cfg.programWordCount, // 32-bit word count (v0.2 ISA)
      addrWidth = spram.addrWidth,
      idleGapCycles = idleGapCycles
    )

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
          // engineStart is a LEVEL signal to the pipeline (consumed in
          // EnginePipeline.fetchActive), not a pulse. Drive it True for
          // the entire runningState lifetime so F1 keeps issuing
          // fetches until the engine halts. The `engineStarted` Reg
          // tracks whether we've started (used elsewhere); the actual
          // drive is just "we're in runningState".
          engineStartDrv := True
          when(!engineStarted) {
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
    pipeline.io.programLength := loader.io.programLength
      .resize(pipeline.programLenWidth bits)
    drainer.io.triggerDrain := drainTriggerComb
    loader.io.acceptRx := acceptRxComb

    // ---- SPRAM port wiring -------------------------------------------------

    // Loader writes: v0.2 loader emits 32-bit words directly to SPRAM.
    spram.io.loaderWrite <> loader.io.programWrite

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
    drainer.io.readResp.payload := spram.io.readResp.payload

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
    //
    // The on-board status LEDs are wired anode-to-3.3V with the FPGA pin
    // as the cathode. They are **active-low**: drive pin LOW to light
    // the LED, drive pin HIGH to turn it off. Every `io_led*` assignment
    // below therefore wraps the user-facing "LED should be on" expression
    // in `!(...)` so the pin polarity is correct on real silicon. v0
    // silicon-validated this by accident: v0's `io_led* := !engine.done`
    // produced pin HIGH at boot (engine.done=False) which read as "LED
    // off"; v0.2's new `engineStarted && !halted` form evaluates False
    // at boot, which without the inversion lit ALL three LEDs out of
    // reset because every other driver also defaults to 0 = pin LOW =
    // LED on.
    //
    // Naming convention: the signals are named by FUNCTION (Fault /
    // Running / Heartbeat), not by physical LED color, because the
    // iCEbreaker silkscreen color labels disagree with what's actually
    // populated on this board revision (the "B" channel of the on-board
    // RGB LED lights GREEN, not blue). See `icebreaker.pcf` for the
    // function → pin → physical-LED mapping. Functional naming
    // survives a future board respin.

    // io_ledFault: pulse-stretched loader fault OR sticky CTS violation.
    // Physically a small red 0603 SMD on this board rev (silkscreen LEDR,
    // pin 11). Pulse-stretch holds the LED on for ~2^22 cycles ≈ 175 ms
    // at 24 MHz so a one-cycle fault is visible to the human eye.
    val faultStretchWidth = 22
    val faultStretchMax = (1 << faultStretchWidth) - 1
    val faultCounter = Reg(UInt(faultStretchWidth bits)) init 0
    when(loader.io.fault) {
      faultCounter := U(faultStretchMax, faultStretchWidth bits)
    } elsewhen (faultCounter =/= 0) {
      faultCounter := faultCounter - 1
    }
    io.io_ledFault := !((faultCounter =/= 0) || ctsViolationObservedReg)

    // io_ledRunning: lit while the engine is executing program
    // instructions (i.e. started AND not halted). Goes off the moment
    // HALT commits. Physically a small green 0603 SMD on this board
    // rev (silkscreen LEDG, pin 37).
    io.io_ledRunning := !(engineStarted && !pipeline.io.halted)

    // io_ledHeartbeat: ~1.4 s on / 1.4 s off blink WHILE THE ENGINE IS
    // IDLE (acceptLoad waiting for a frame, OR just halted post-run).
    // Stops blinking the moment the engine starts executing.
    // Physically the green channel of the big on-board RGB LED on
    // this board rev (silkscreen LED_RGB1, pin 40 --- the iCEbreaker
    // calls this the "B" channel of the RGB, which is misleading
    // because the physically populated LED is green).
    val heartbeatWidth = 26
    val heartbeatCounter = Reg(UInt(heartbeatWidth bits)) init 0
    when(pipeline.io.halted || !engineStarted) {
      heartbeatCounter := heartbeatCounter + 1
    }
    io.io_ledHeartbeat :=
      !(heartbeatCounter.msb && (pipeline.io.halted || !engineStarted))

    // ---- Sim-only debug taps -----------------------------------------------
    if (!useBlackBox) {
      io.sim_halted := pipeline.io.halted
      io.sim_haltStatus := pipeline.io.haltStatus
      io.sim_loaderLoaded := loader.io.loaded
      io.sim_loaderFault := loader.io.fault
      io.sim_ctsViolationObserved := ctsViolationObservedReg
      io.sim_programLength := loader.io.programLength.resize(16 bits)
      io.sim_engineStart := engineStartDrv
      io.sim_engineStarted := engineStarted
      io.sim_pc := pipeline.io.simPc.resize(13 bits)
      io.sim_fetchActive := pipeline.io.simFetchActive
      io.sim_haltInFlight := pipeline.io.simHaltInFlight
    }
  }
}
