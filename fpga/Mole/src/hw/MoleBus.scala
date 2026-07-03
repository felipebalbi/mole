// SPDX-FileCopyrightText: 2026 Felipe Balbi
// SPDX-License-Identifier: CERN-OHL-W-2.0

package mole

import spinal.core._
import spinal.lib._

/** One wire of Mole's open-drain / push-pull-capable bus.
  *
  * Three signals per line — not the two-signal
  * `spinal.lib.io.ReadableOpenDrain` that the sibling I2c project uses. The
  * reason is laid out in `AGENTS.md` §"Open-drain primitive: custom `MoleBus`,
  * not `ReadableOpenDrain`": Mole has to speak both I2C / I3C-OD (open-drain)
  * *and* I3C-PP / HDR-DDR (push-pull). `ReadableOpenDrain` can only express
  * "drive low" or "release"; it cannot drive an active high. The 3-signal
  * bundle splits the two so the symbol decoder (a pure combinational function
  * of `tx_symbol` + active `BUS_MODE`) can pick the right pin without inferring
  * a hidden per-pad mode register.
  *
  * `IMasterSlave` direction convention (`master` view): outputs `driveLow` and
  * `driveHigh`; input `read`. This mirrors `I2cIo` exactly — controller-side
  * blocks declare `master(MoleBus())` and peripheral-side blocks declare
  * `slave(MoleBus())`; Spinal flips every leaf direction at the connection
  * site, so a one-line `controller.io.bus <> target.io.bus` is type-correct.
  *
  * Polarity rules (from `AGENTS.md`):
  *   - `driveLow  := True` → NMOS on → pin at GND → bus low.
  *   - `driveHigh := True` → PMOS on → pin at VIO → bus high (PP).
  *   - both False → output-enable off → external pull-up wins → bus high
  *     (open-drain release).
  *   - both True → **bus contention** (illegal; the symbol decoder must never
  *     produce it, and `OpenDrainBusSim` asserts it out).
  *
  * No "release" helper. The repo-level AGENTS rejects helpers like
  * `releaseAll()` from the sibling `I2cIo` because Mole's bus-shaped FSMs
  * always *set* each driver on every transition (last-assignment-wins clobber
  * risk otherwise). Wide-fanout "release" comes through the symbol decoder
  * selecting `tx_symbol = hiz`, which decodes to `(driveLow=0, driveHigh=0)`.
  */
case class MoleBusLine() extends Bundle with IMasterSlave {

  /** NMOS pull-down enable. `True` → pin at GND → bus low. */
  val driveLow = Bool()

  /** PMOS push-pull pull-up enable. `True` → pin at VIO → bus high. Only legal
    * in PP-class `BUS_MODE`s (`i3c-PP`, `hdr-ddr`); the symbol decoder is
    * responsible for never asserting this in OD-class modes (`i2c`, `i3c-OD`).
    * The pad will physically drive it; gating the *decoder* is what makes the
    * OD mode electrically faithful.
    */
  val driveHigh = Bool()

  /** Live post-pad value of the wire. Sampled value includes contributions from
    * every device on the wired-AND segment (peers, external pull-ups). Used by
    * the engine both for `capture=1` slots and for mismatch detection in
    * `expect=1` slots (ROADMAP §"Per-bit flag triple").
    */
  val read = Bool()

  override def asMaster(): Unit = {
    out(driveLow, driveHigh)
    in(read)
  }
}

/** The full Mole bus: two `MoleBusLine`s named `scl` and `sda`.
  *
  * Direction note: `asMaster` uses `master(scl); master(sda)` — same pattern as
  * `I2cIo` in the sibling project. Spinal recurses into each nested
  * `IMasterSlave`, so `controller.io.bus <> target.io.bus` connects all six
  * leaf signals in one line with the correct directions flipped.
  *
  * Naming: kept `scl` / `sda` even though the engine is bus-agnostic. The pad
  * layer maps these onto whatever the active `BUS_MODE` calls them; for I2C /
  * I3C they happen to be the spec-canonical names. ROADMAP §"Bus mode register"
  * calls out that future bus modes (SMBus, PMBus, LIN, 1-Wire, CAN) would reuse
  * the same two physical wires and just pick a different
  * `tx_symbol`-to-electrical mapping.
  */
case class MoleBus() extends Bundle with IMasterSlave {

  val scl = MoleBusLine()
  val sda = MoleBusLine()

  override def asMaster(): Unit = {
    master(scl)
    master(sda)
  }
}
