// SPDX-FileCopyrightText: 2026 Felipe Balbi
// SPDX-License-Identifier: CERN-OHL-W-2.0

package mole

import spinal.core._
import spinal.lib._

/** Two-engine DUT for DAA arbitration tests.
  *
  * Wraps two [[EnginePipeline]] instances (each with their own
  * [[SpramController]]). The test drives `busA.sda.read` and `busB.sda.read`
  * with a sim-side wired-AND model to test I3C DAA arbitration.
  *
  * C.7 resurrection: replaces the v0 [[BitCycleEngineTwoTargetDut]] which
  * wrapped two [[BitCycleEngineCore]] instances. IO uses the new v0.2 names.
  */
case class BitCycleEngineTwoTargetDut(cfg: MoleConfig) extends Component {

  val resultWordCount: Int = (cfg.resultRingByteCount + 3) / 4
  val addrWidth: Int = log2Up(cfg.programWordCount + resultWordCount)
  val programLenWidth: Int = log2Up(Instruction.MAX_PROGRAM_WORDS + 1)

  val io = new Bundle {
    val busA = master(MoleBus())
    val busB = master(MoleBus())
    val loaderWriteA = slave Stream SpramWriteCmd(addrWidth)
    val loaderWriteB = slave Stream SpramWriteCmd(addrWidth)
    val startA = in Bool ()
    val startB = in Bool ()
    val programLengthA = in UInt (programLenWidth bits)
    val programLengthB = in UInt (programLenWidth bits)
    val haltedA = out Bool ()
    val haltedB = out Bool ()
    val debugReadCmdA = slave Stream UInt(addrWidth bits)
    val debugReadCmdB = slave Stream UInt(addrWidth bits)
    val debugReadRespA = master Flow Bits(32 bits)
    val debugReadRespB = master Flow Bits(32 bits)
  }

  val engineA = EnginePipeline(cfg)
  val engineB = EnginePipeline(cfg)
  val spramA = SpramController(cfg, useBlackBox = false)
  val spramB = SpramController(cfg, useBlackBox = false)

  // Engine A wiring
  when(engineA.io.halted) {
    spramA.io.readCmd.valid := io.debugReadCmdA.valid
    spramA.io.readCmd.payload := io.debugReadCmdA.payload
    io.debugReadCmdA.ready := spramA.io.readCmd.ready
    engineA.io.spramRead.ready := False
  } otherwise {
    spramA.io.readCmd.valid := engineA.io.spramRead.valid
    spramA.io.readCmd.payload := engineA.io.spramRead.payload
    engineA.io.spramRead.ready := spramA.io.readCmd.ready
    io.debugReadCmdA.ready := False
  }
  engineA.io.spramResp.valid := spramA.io.readResp.valid
  engineA.io.spramResp.payload := spramA.io.readResp.payload
  io.debugReadRespA.valid := spramA.io.readResp.valid
  io.debugReadRespA.payload := spramA.io.readResp.payload
  spramA.io.resultWrite <> engineA.io.ringWrite
  spramA.io.loaderWrite.valid := io.loaderWriteA.valid
  spramA.io.loaderWrite.payload := io.loaderWriteA.payload
  io.loaderWriteA.ready := spramA.io.loaderWrite.ready
  io.busA.sda <> engineA.io.sda
  io.busA.scl <> engineA.io.scl
  engineA.io.sdaSampled := io.busA.sda.read
  engineA.io.sclSampled := io.busA.scl.read
  engineA.io.engineStart := io.startA
  engineA.io.programLength := io.programLengthA
  io.haltedA := engineA.io.halted

  // Engine B wiring
  when(engineB.io.halted) {
    spramB.io.readCmd.valid := io.debugReadCmdB.valid
    spramB.io.readCmd.payload := io.debugReadCmdB.payload
    io.debugReadCmdB.ready := spramB.io.readCmd.ready
    engineB.io.spramRead.ready := False
  } otherwise {
    spramB.io.readCmd.valid := engineB.io.spramRead.valid
    spramB.io.readCmd.payload := engineB.io.spramRead.payload
    engineB.io.spramRead.ready := spramB.io.readCmd.ready
    io.debugReadCmdB.ready := False
  }
  engineB.io.spramResp.valid := spramB.io.readResp.valid
  engineB.io.spramResp.payload := spramB.io.readResp.payload
  io.debugReadRespB.valid := spramB.io.readResp.valid
  io.debugReadRespB.payload := spramB.io.readResp.payload
  spramB.io.resultWrite <> engineB.io.ringWrite
  spramB.io.loaderWrite.valid := io.loaderWriteB.valid
  spramB.io.loaderWrite.payload := io.loaderWriteB.payload
  io.loaderWriteB.ready := spramB.io.loaderWrite.ready
  io.busB.sda <> engineB.io.sda
  io.busB.scl <> engineB.io.scl
  engineB.io.sdaSampled := io.busB.sda.read
  engineB.io.sclSampled := io.busB.scl.read
  engineB.io.engineStart := io.startB
  engineB.io.programLength := io.programLengthB
  io.haltedB := engineB.io.halted
}
