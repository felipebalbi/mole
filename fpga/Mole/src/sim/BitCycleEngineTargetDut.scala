// SPDX-FileCopyrightText: 2026 Felipe Balbi
// SPDX-License-Identifier: CERN-OHL-W-2.0

package mole

import spinal.core._
import spinal.lib._

/** Sim DUT wrapping [[EnginePipeline]] + [[SpramController]] for the C.7
  * WIRE-opcode sims.
  *
  * This component replaces the v0 [[BitCycleEngineSmokeDut]] (which wrapped
  * [[BitCycleEngineCore]]). Key differences from the v0 wrapper:
  *
  *   - IO uses `engineStart` / `halted` instead of `start` / `done`.
  *   - `programLength` must be set before `engineStart` is asserted.
  *   - Bus pads are split into `sda` and `scl` (two `MoleBusLine`s).
  *   - Debug read port is available when `halted` is True.
  *   - Ring word count is `(resultRingByteCount + 3) / 4` (32-bit words).
  *   - The HALT ring word lands at `resultBase + ringWrPtr_at_halt`; the DUT
  *     exposes `ringWrPtr` so the test can compute the address.
  *
  * Lives in `src/sim/` — this component never elaborates to RTL.
  */
case class BitCycleEngineSmokeDut(cfg: MoleConfig) extends Component {

  val resultWordCount: Int = (cfg.resultRingByteCount + 3) / 4
  val addrWidth: Int = log2Up(cfg.programWordCount + resultWordCount)
  val programLenWidth: Int = log2Up(Instruction.MAX_PROGRAM_WORDS + 1)

  val io = new Bundle {
    // Bus pads (split into SDA and SCL to match EnginePipeline.io).
    val sda = master(MoleBusLine())
    val scl = master(MoleBusLine())

    // Loader write port.
    val loaderWrite = slave Stream SpramWriteCmd(addrWidth)

    // Engine control.
    val engineStart = in Bool ()
    val programLength = in UInt (programLenWidth bits)
    val halted = out Bool ()
    val haltStatus = out UInt (5 bits)
    val mismatchFlag = out Bool ()

    // Debug read (available when halted; muxed into SPRAM read port).
    val debugReadCmd = slave Stream UInt(addrWidth bits)
    val debugReadResp = master Flow Bits(32 bits)
  }

  val engine = EnginePipeline(cfg)
  val spram = SpramController(cfg, useBlackBox = false)

  // ---- SPRAM read port mux: engine fetch vs debug read when halted ------
  when(engine.io.halted) {
    spram.io.readCmd.valid := io.debugReadCmd.valid
    spram.io.readCmd.payload := io.debugReadCmd.payload
    io.debugReadCmd.ready := spram.io.readCmd.ready
    engine.io.spramRead.ready := False
  } otherwise {
    spram.io.readCmd.valid := engine.io.spramRead.valid
    spram.io.readCmd.payload := engine.io.spramRead.payload
    engine.io.spramRead.ready := spram.io.readCmd.ready
    io.debugReadCmd.ready := False
  }

  // SPRAM read response fans out to both consumers.
  engine.io.spramResp.valid := spram.io.readResp.valid
  engine.io.spramResp.payload := spram.io.readResp.payload
  io.debugReadResp.valid := spram.io.readResp.valid
  io.debugReadResp.payload := spram.io.readResp.payload

  // ---- Write ports -------------------------------------------------------
  spram.io.resultWrite <> engine.io.ringWrite
  spram.io.loaderWrite.valid := io.loaderWrite.valid
  spram.io.loaderWrite.payload := io.loaderWrite.payload
  io.loaderWrite.ready := spram.io.loaderWrite.ready

  // ---- Bus pads ----------------------------------------------------------
  io.sda <> engine.io.sda
  io.scl <> engine.io.scl

  // Tie sampled inputs from the pad reads.
  engine.io.sdaSampled := io.sda.read
  engine.io.sclSampled := io.scl.read

  // ---- Control -----------------------------------------------------------
  engine.io.engineStart := io.engineStart
  engine.io.programLength := io.programLength
  io.halted := engine.io.halted
  io.haltStatus := engine.io.haltStatus
  io.mismatchFlag := engine.io.mismatchFlag
}

/** Sim DUT for target-role tests: same as [[BitCycleEngineSmokeDut]] but used
  * for the target-role and stretch sims. The name matches the v0 stash so the
  * sim files can reference it directly.
  */
case class BitCycleEngineTargetDut(cfg: MoleConfig) extends Component {

  val resultWordCount: Int = (cfg.resultRingByteCount + 3) / 4
  val addrWidth: Int = log2Up(cfg.programWordCount + resultWordCount)
  val programLenWidth: Int = log2Up(Instruction.MAX_PROGRAM_WORDS + 1)

  val io = new Bundle {
    val sda = master(MoleBusLine())
    val scl = master(MoleBusLine())
    val loaderWrite = slave Stream SpramWriteCmd(addrWidth)
    val engineStart = in Bool ()
    val programLength = in UInt (programLenWidth bits)
    val halted = out Bool ()
    val haltStatus = out UInt (5 bits)
    val mismatchFlag = out Bool ()
    val debugReadCmd = slave Stream UInt(addrWidth bits)
    val debugReadResp = master Flow Bits(32 bits)
  }

  val engine = EnginePipeline(cfg)
  val spram = SpramController(cfg, useBlackBox = false)

  when(engine.io.halted) {
    spram.io.readCmd.valid := io.debugReadCmd.valid
    spram.io.readCmd.payload := io.debugReadCmd.payload
    io.debugReadCmd.ready := spram.io.readCmd.ready
    engine.io.spramRead.ready := False
  } otherwise {
    spram.io.readCmd.valid := engine.io.spramRead.valid
    spram.io.readCmd.payload := engine.io.spramRead.payload
    engine.io.spramRead.ready := spram.io.readCmd.ready
    io.debugReadCmd.ready := False
  }

  engine.io.spramResp.valid := spram.io.readResp.valid
  engine.io.spramResp.payload := spram.io.readResp.payload
  io.debugReadResp.valid := spram.io.readResp.valid
  io.debugReadResp.payload := spram.io.readResp.payload

  spram.io.resultWrite <> engine.io.ringWrite
  spram.io.loaderWrite.valid := io.loaderWrite.valid
  spram.io.loaderWrite.payload := io.loaderWrite.payload
  io.loaderWrite.ready := spram.io.loaderWrite.ready

  io.sda <> engine.io.sda
  io.scl <> engine.io.scl
  engine.io.sdaSampled := io.sda.read
  engine.io.sclSampled := io.scl.read

  engine.io.engineStart := io.engineStart
  engine.io.programLength := io.programLength
  io.halted := engine.io.halted
  io.haltStatus := engine.io.haltStatus
  io.mismatchFlag := engine.io.mismatchFlag
}
