package mole

import spinal.core._
import spinal.lib._

case class BitCycleEngineTwoTargetDut(cfg: MoleConfig) extends Component {

  // Two engines sharing one cfg --- both boot in whatever role `cfg.role`
  // names, runtime SET_ROLE can override either independently.

  val addrWidth: Int =
    log2Up(cfg.programWordCount + (cfg.resultRingByteCount + 1) / 2)

  val io = new Bundle {
    val busA = master(MoleBus())
    val busB = master(MoleBus())
    val loaderWriteA = slave Stream SpramWriteCmd(addrWidth)
    val loaderWriteB = slave Stream SpramWriteCmd(addrWidth)
    val startA = in Bool ()
    val startB = in Bool ()
    val doneA = out Bool ()
    val doneB = out Bool ()
    val debugReadCmdA = slave Stream UInt(addrWidth bits)
    val debugReadCmdB = slave Stream UInt(addrWidth bits)
    val debugReadRespA = master Flow Bits(16 bits)
    val debugReadRespB = master Flow Bits(16 bits)
  }

  val engineA = BitCycleEngineCore(cfg)
  val engineB = BitCycleEngineCore(cfg)
  val spramA = SpramController(cfg, useBlackBox = false)
  val spramB = SpramController(cfg, useBlackBox = false)

  when(engineA.io.done) {
    spramA.io.readCmd.valid := io.debugReadCmdA.valid
    spramA.io.readCmd.payload := io.debugReadCmdA.payload
    io.debugReadCmdA.ready := spramA.io.readCmd.ready
    engineA.io.programReadCmd.ready := False
  } otherwise {
    spramA.io.readCmd.valid := engineA.io.programReadCmd.valid
    spramA.io.readCmd.payload := engineA.io.programReadCmd.payload
    engineA.io.programReadCmd.ready := spramA.io.readCmd.ready
    io.debugReadCmdA.ready := False
  }
  engineA.io.programReadResp.valid := spramA.io.readResp.valid
  engineA.io.programReadResp.payload := spramA.io.readResp.payload
  io.debugReadRespA.valid := spramA.io.readResp.valid
  io.debugReadRespA.payload := spramA.io.readResp.payload
  spramA.io.resultWrite << engineA.io.resultWrite
  spramA.io.loaderWrite.valid := io.loaderWriteA.valid
  spramA.io.loaderWrite.payload := io.loaderWriteA.payload
  io.loaderWriteA.ready := spramA.io.loaderWrite.ready
  io.busA <> engineA.io.bus
  engineA.io.start := io.startA
  io.doneA := engineA.io.done

  when(engineB.io.done) {
    spramB.io.readCmd.valid := io.debugReadCmdB.valid
    spramB.io.readCmd.payload := io.debugReadCmdB.payload
    io.debugReadCmdB.ready := spramB.io.readCmd.ready
    engineB.io.programReadCmd.ready := False
  } otherwise {
    spramB.io.readCmd.valid := engineB.io.programReadCmd.valid
    spramB.io.readCmd.payload := engineB.io.programReadCmd.payload
    engineB.io.programReadCmd.ready := spramB.io.readCmd.ready
    io.debugReadCmdB.ready := False
  }
  engineB.io.programReadResp.valid := spramB.io.readResp.valid
  engineB.io.programReadResp.payload := spramB.io.readResp.payload
  io.debugReadRespB.valid := spramB.io.readResp.valid
  io.debugReadRespB.payload := spramB.io.readResp.payload
  spramB.io.resultWrite << engineB.io.resultWrite
  spramB.io.loaderWrite.valid := io.loaderWriteB.valid
  spramB.io.loaderWrite.payload := io.loaderWriteB.payload
  io.loaderWriteB.ready := spramB.io.loaderWrite.ready
  io.busB <> engineB.io.bus
  engineB.io.start := io.startB
  io.doneB := engineB.io.done
}
