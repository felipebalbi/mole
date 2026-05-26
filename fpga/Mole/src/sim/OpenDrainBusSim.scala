package mole

/** Open-drain / push-pull bus resolution audit for [[MoleBus]].
  *
  * Pure-Scala main, not a SpinalSim DUT. The bundle has no stateful hardware to
  * exercise — what we want to verify at the `sim-opendrain` Makefile target is
  * the *electrical resolution function* the wired-AND of multiple [[MoleBus]]
  * participants implements at the pad level. Future engine sims (Step 9+) that
  * model a controller + one or more targets on the same bus will import and
  * reuse [[OpenDrainBusSim.wiredAnd]] to drive the sampled `read` value of
  * every participant from each cycle's `(driveLow, driveHigh)` tuples.
  *
  * The resolution function is the only "bus model" code in the tree. Keeping it
  * pure-Scala lets unit tests exercise every legal and illegal combination in
  * milliseconds without a simulator backend. The function lives here (alongside
  * the audit) rather than in `src/hw/` because it never elaborates to RTL — it
  * only runs at sim time. Symmetric with `MoleConfigSim`.
  *
  * Run: `sbt "runMain mole.OpenDrainBusSim"`
  */
object OpenDrainBusSim extends App {

  /** Per-participant drive state for one bus wire.
    *
    * Sampled from a `MoleBusLine` in simulation:
    * `Drive(dut.io.bus.sda.driveLow.toBoolean,
    * dut.io.bus.sda.driveHigh.toBoolean)`.
    */
  final case class Drive(low: Boolean, high: Boolean)

  /** Result of resolving N participants on a wired-AND segment with an external
    * pull-up.
    *
    *   - `Some(false)` → at least one participant pulls low (NMOS wins over
    *     PMOS and pull-up; classic wired-AND).
    *   - `Some(true)` → no one drives low, and either someone PP- drives high
    *     or the pull-up wins.
    *   - `None` → contention: at least one participant drives both low *and*
    *     high simultaneously, which is illegal at the bundle level and means
    *     the symbol decoder produced an invalid `(driveLow, driveHigh)` pair.
    *
    * Contention from *different* participants (one driving low, another driving
    * high) is **not** a `None` — that case resolves to low (NMOS dominates
    * physically). Callers that care about that softer "two of you are arguing"
    * case should inspect the participant list directly.
    */
  def wiredAnd(parts: Seq[Drive]): Option[Boolean] = {
    if (parts.exists(p => p.low && p.high)) {
      None
    } else if (parts.exists(_.low)) {
      Some(false)
    } else if (parts.exists(_.high)) {
      Some(true)
    } else {
      Some(true) // no driver active → external pull-up wins
    }
  }

  /** Convenience for the common 2-line case (SCL + SDA). */
  def resolveBus(
      sclParts: Seq[Drive],
      sdaParts: Seq[Drive]
  ): (Option[Boolean], Option[Boolean]) =
    (wiredAnd(sclParts), wiredAnd(sdaParts))

  // --- Assertions ---------------------------------------------------

  println("--- OpenDrainBus wired-AND audit ---")

  // 1. No driver -> pull-up wins -> bus high.
  assert(
    wiredAnd(Seq(Drive(false, false))).contains(true),
    "no driver: pull-up should win (bus high)"
  )
  assert(
    wiredAnd(Seq.empty).contains(true),
    "empty participant list: pull-up should win (bus high)"
  )

  // 2. Single participant pulling low -> bus low.
  assert(
    wiredAnd(Seq(Drive(true, false))).contains(false),
    "single driveLow: bus must go low"
  )

  // 3. Single participant PP-driving high -> bus high.
  assert(
    wiredAnd(Seq(Drive(false, true))).contains(true),
    "single driveHigh: bus must go high"
  )

  // 4. Many participants, all released -> pull-up wins.
  assert(
    wiredAnd(Seq.fill(4)(Drive(false, false))).contains(true),
    "four released drivers: pull-up should win"
  )

  // 5. Many participants, one pulls low -> bus low (NMOS dominates).
  assert(
    wiredAnd(
      Seq(Drive(false, false), Drive(true, false), Drive(false, false))
    ).contains(false),
    "one-of-three pulls low: NMOS dominates wired-AND"
  )

  // 6. NMOS vs PMOS on different participants -> bus low.
  // Physically NMOS-low wins because it can sink whatever the PMOS
  // sources, and the pad's series resistance is the same on both
  // sides. The decoder should not produce this in real life, but if
  // it does the bus value is still well-defined.
  assert(
    wiredAnd(Seq(Drive(true, false), Drive(false, true))).contains(false),
    "low vs high on different drivers: low wins (NMOS dominates)"
  )

  // 7. Single participant with BOTH driveLow and driveHigh asserted
  // -> contention (None). This is the case the symbol decoder is
  // never allowed to produce (AGENTS.md §"Polarity rules").
  assert(
    wiredAnd(Seq(Drive(true, true))).isEmpty,
    "single driver asserting both: must report contention"
  )

  // 8. Contention on one of many participants still surfaces.
  assert(
    wiredAnd(Seq(Drive(false, false), Drive(true, true))).isEmpty,
    "contention on any participant must surface"
  )

  // 9. Bus convenience pair returns the per-wire resolution.
  val (scl, sda) =
    resolveBus(
      sclParts = Seq(Drive(false, false)),
      sdaParts = Seq(Drive(true, false))
    )
  assert(scl.contains(true), s"resolveBus: SCL should be released, got $scl")
  assert(sda.contains(false), s"resolveBus: SDA should be low, got $sda")

  // --- Print a readable summary ------------------------------------

  val cases = Seq(
    ("released", Seq(Drive(false, false))),
    ("one low", Seq(Drive(true, false))),
    ("one high", Seq(Drive(false, true))),
    ("two released", Seq.fill(2)(Drive(false, false))),
    (
      "one of three low",
      Seq(Drive(false, false), Drive(true, false), Drive(false, false))
    ),
    ("low vs high split", Seq(Drive(true, false), Drive(false, true))),
    ("self-contention", Seq(Drive(true, true))),
    ("contention amid releases", Seq(Drive(false, false), Drive(true, true)))
  )
  cases.foreach { case (label, parts) =>
    val r = wiredAnd(parts).fold("contention")(b => if (b) "high" else "low ")
    println(f"  $label%-26s -> $r")
  }

  println("OpenDrainBus wired-AND OK")
}
