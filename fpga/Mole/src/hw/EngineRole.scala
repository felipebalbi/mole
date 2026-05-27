package mole

/** Engine-role compile-time toggle for [[MoleConfig]].
  *
  * Mole plays exactly one role per bitstream. Choice is a Scala `sealed trait`
  * (compile-time `if`) not a Spinal `when` over a runtime register so the
  * unused half of the FSM disappears from synthesis. One bitstream per role; no
  * runtime switching.
  *
  * See [[EngineRole.Controller]] and [[EngineRole.Target]] for per-role
  * wire-level contracts.
  */
sealed trait EngineRole

object EngineRole {

  /** Engine drives SCL via [[SclWaveformGen]] (canonical 4-quarter pulse low /
    * low / high / high). Workhorses: `EMIT_BIT`, `EMIT_QUARTER`; `STRETCH_SCL`
    * extends the low half for fuzz-injected late releases. The target-role
    * helpers (`SAMPLE_BIT_ON_SCL` / `DRIVE_BIT_ON_SCL`) trap to halt at the FSM
    * `default` arm because they have no meaningful semantics here --- the
    * engine *is* the SCL master, nothing to slave to.
    *
    * Production default for Mole Verde and the existing v0 sim fleet through
    * Step 18. No code change required to keep using this role.
    */
  case object Controller extends EngineRole

  /** Engine releases SCL by default and slaves to the external controller's
    * clock edges. Workhorses: `SAMPLE_BIT_ON_SCL` / `DRIVE_BIT_ON_SCL` (Step 19
    * FSM states); `STRETCH_SCL` is the only path by which the target actively
    * drives SCL (pulled low for canonical clock stretching).
    *
    * `EMIT_BIT` / `EMIT_QUARTER` remain *legal* in target role --- they give an
    * asynchronous SDA-glitch-injection path between transactions (useful for
    * fuzz tests that pump bits onto the bus while no controller is clocking
    * it). The SDK warns when one shows up inside an active transfer per AGENTS
    * section 3.13. Engine-side enforcement: in target role `SclWaveformGen` is
    * bypassed so an `EMIT_BIT` here releases SCL instead of driving it,
    * defending against the lint slip.
    *
    * Selected by `MoleConfig(role = EngineRole.Target)`. Requires a separate
    * Verilog-generation entrypoint (parallel to `MoleTopVerilog`) so the right
    * bitstream lands on a target Mole.
    */
  case object Target extends EngineRole
}
