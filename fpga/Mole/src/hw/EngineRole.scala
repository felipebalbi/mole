package mole

/** Engine-role boot-default for [[MoleConfig]].
  *
  * Post-Step-21 Mole carries a runtime `roleReg` initialised from
  * [[MoleConfig.role]]; the [[mole.Opcode.setRole]] opcode flips it at any PC.
  * A program that never issues `SET_ROLE` keeps the boot-default behaviour,
  * matching the pre-Step-21 compile-time-only contract.
  *
  * Both halves of the bit-cycle FSM (the `SclWaveformGen`-paced controller emit
  * path and the external-SCL-paced target sample/drive states) elaborate
  * unconditionally so a single bitstream can play either role.
  *
  * See [[EngineRole.Controller]] and [[EngineRole.Target]] for per-role
  * wire-level contracts.
  */
sealed trait EngineRole

object EngineRole {

  /** Boot up with the engine driving SCL via [[SclWaveformGen]] (canonical
    * 4-quarter pulse low / low / high / high). Workhorses: `EMIT_BIT`,
    * `EMIT_QUARTER`; `STRETCH_SCL` extends the low half for fuzz-injected late
    * releases. The target-role helpers (`SAMPLE_BIT_ON_SCL` /
    * `DRIVE_BIT_ON_SCL`) trap to halt under `!roleReg` because they have no
    * meaningful semantics here --- the engine *is* the SCL master, nothing to
    * slave to.
    *
    * Production default for Mole Verde and the existing v0 sim fleet through
    * Step 18. A subsequent `SET_ROLE target` opcode lifts the engine into
    * target mode at runtime; without one it stays in controller mode for the
    * entire program.
    */
  case object Controller extends EngineRole

  /** Boot up with the engine releasing SCL by default and slaving to the
    * external controller's clock edges. Workhorses: `SAMPLE_BIT_ON_SCL` /
    * `DRIVE_BIT_ON_SCL`; `STRETCH_SCL` is the only path by which the target
    * actively drives SCL (pulled low for canonical clock stretching).
    *
    * `EMIT_BIT` / `EMIT_QUARTER` remain *legal* in target role --- they give an
    * asynchronous SDA-glitch-injection path between transactions (useful for
    * fuzz tests that pump bits onto the bus while no controller is clocking
    * it). The SDK warns when one shows up inside an active transfer per AGENTS
    * section 3.13. Engine-side enforcement: in target role the SCL driver path
    * stays released for the entire `EMIT_BIT` so the lint slip cannot drive SCL
    * accidentally.
    *
    * Selected by `MoleConfig(role = EngineRole.Target)`. A subsequent
    * `SET_ROLE controller` opcode drops the engine into controller mode at
    * runtime; without one it stays in target mode for the entire program.
    */
  case object Target extends EngineRole
}
