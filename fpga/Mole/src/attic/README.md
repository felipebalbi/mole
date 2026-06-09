# Stashed v0 sources (Phase C in flight)

These files reference v0 symbols (`Opcode.emitBit`, `Opcode.loadLoop`,
`Opcode.decBranch`, `Instruction.WORD_WIDTH = 16`, the monolithic
`BitCycleEngineCore` StateMachine, v0 `MoleTop` wiring, etc.) that no
longer exist after commit C.2.

They are stashed here so the rest of the workspace keeps compiling and
the non-engine sims keep passing.

## Phase C resurrection plan

| File | Original path | Resurrection |
|------|---------------|--------------|
| `BitCycleEngineCore.scala.v0-stash` | `src/hw/BitCycleEngineCore.scala` | **REPLACED** by the new `EnginePipeline.scala` in C.6 and the v0.2 `BitCycleEngineCore` wrapper at the same commit |
| `MoleTop.scala.v0-stash` | `src/hw/MoleTop.scala` | Resurrected with v0.2 wiring in C.6 |
| `MoleTopVerilog.scala.v0-stash` | `src/hw/MoleTopVerilog.scala` | Resurrected with v0.2 wiring in C.6 |
| `BitCycleEngineSim.scala.v0-stash` | `src/sim/BitCycleEngineSim.scala` | Re-encoded with v0.2 test programs in C.8 (CTRL — depends on JMP/BRANCH_ON/LOAD_TIMING) |
| `BitCycleEngineSmokeSim.scala.v0-stash` | `src/sim/BitCycleEngineSmokeSim.scala` | **Resurrected in C.7** |
| `BitCycleEngineStretchSim.scala.v0-stash` | `src/sim/BitCycleEngineStretchSim.scala` | Resurrected in C.8 (depends on LOAD_TIMING) |
| `BitCycleEngineStretchRoleSim.scala.v0-stash` | `src/sim/BitCycleEngineStretchRoleSim.scala` | **Resurrected in C.7** |
| `BitCycleEngineTargetSim.scala.v0-stash` | `src/sim/BitCycleEngineTargetSim.scala` | Resurrected in C.8 (depends on MARK) |
| `BitCycleEngineTargetDut.scala.v0-stash` | `src/sim/BitCycleEngineTargetDut.scala` | **Resurrected in C.7** |
| `BitCycleEngineTwoTargetDut.scala.v0-stash` | `src/sim/BitCycleEngineTwoTargetDut.scala` | **Resurrected in C.7** |
| `BitCycleEngineJmpBoundarySim.scala.v0-stash` | `src/sim/BitCycleEngineJmpBoundarySim.scala` | Resurrected in C.8 (CTRL) |
| `BitCycleEngineEmitBitDataHoldSim.scala.v0-stash` | `src/sim/BitCycleEngineEmitBitDataHoldSim.scala` | **Resurrected in C.7** |
| `MoleTopSim.scala.v0-stash` | `src/sim/MoleTopSim.scala` | Resurrected in C.10 (end-to-end) |
| `MoleTopFlowControlSim.scala.v0-stash` | `src/sim/MoleTopFlowControlSim.scala` | Resurrected in C.10 (end-to-end) |
| `MoleTopCtsViolationSim.scala.v0-stash` | `src/sim/MoleTopCtsViolationSim.scala` | Resurrected in C.10 (end-to-end) |

Once the Phase C plan completes (C.11), this directory should be empty
and removed. Until then, **do NOT delete or edit anything here**.
