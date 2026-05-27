package mole

import spinal.core._
import spinal.core.sim._
import spinal.lib._

/** Sim DUT for [[BitCycleEngineTargetSim]]: one [[BitCycleEngineCore]]
  * elaborated with `role = EngineRole.Target` plus the same loader + debug-read
  * scaffolding [[BitCycleEngineFullDut]] uses. The test harness drives
  * `bus.sda.read` and `bus.scl.read` directly to mimic an external controller;
  * the engine's drivers are observable on the same bundle.
  */
case class BitCycleEngineTargetDut(cfg: MoleConfig) extends Component {

  require(
    cfg.role == EngineRole.Target,
    s"BitCycleEngineTargetDut requires role=Target, got ${cfg.role}"
  )

  val addrWidth: Int =
    log2Up(cfg.programWordCount + (cfg.resultRingByteCount + 1) / 2)

  val io = new Bundle {
    val bus = master(MoleBus())
    val loaderWrite = slave Stream SpramWriteCmd(addrWidth)
    val start = in Bool ()
    val done = out Bool ()
    val debugReadCmd = slave Stream UInt(addrWidth bits)
    val debugReadResp = master Flow Bits(16 bits)
  }

  val engine = BitCycleEngineCore(cfg)
  val spram = SpramController(cfg, useBlackBox = false)

  when(engine.io.done) {
    spram.io.readCmd.valid := io.debugReadCmd.valid
    spram.io.readCmd.payload := io.debugReadCmd.payload
    io.debugReadCmd.ready := spram.io.readCmd.ready
    engine.io.programReadCmd.ready := False
  } otherwise {
    spram.io.readCmd.valid := engine.io.programReadCmd.valid
    spram.io.readCmd.payload := engine.io.programReadCmd.payload
    engine.io.programReadCmd.ready := spram.io.readCmd.ready
    io.debugReadCmd.ready := False
  }

  engine.io.programReadResp.valid := spram.io.readResp.valid
  engine.io.programReadResp.payload := spram.io.readResp.payload
  io.debugReadResp.valid := spram.io.readResp.valid
  io.debugReadResp.payload := spram.io.readResp.payload

  spram.io.resultWrite << engine.io.resultWrite
  spram.io.loaderWrite.valid := io.loaderWrite.valid
  spram.io.loaderWrite.payload := io.loaderWrite.payload
  io.loaderWrite.ready := spram.io.loaderWrite.ready

  io.bus <> engine.io.bus
  engine.io.start := io.start
  io.done := engine.io.done
}
