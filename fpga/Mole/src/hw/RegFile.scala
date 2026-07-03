// SPDX-FileCopyrightText: 2026 Felipe Balbi
// SPDX-License-Identifier: CERN-OHL-W-2.0

package mole

import spinal.core._

/** 8 × 32-bit general-purpose register file for the Mole v0.2 bit-cycle engine
  * pipeline.
  *
  * Spec authority: `docs/MOLE-0.2-SPEC.md` §8 (register conventions) and §14
  * (pipeline hazards).
  *
  * ==Ports==
  *
  * Two combinational read ports and one synchronous write port. The R stage
  * drives the read addresses; the data emerge the same cycle (combinational
  * read after the W→R bypass mux). The W stage drives the write port; the write
  * commits on the rising clock edge.
  *
  * ==W→R bypass mux==
  *
  * Each read port has an independent bypass mux: a single comparator plus a
  * 2-input mux in the read-port combinational path. When `writeEnable` is
  * asserted and `writeAddr` matches the read address, the mux forwards
  * `writeData` instead of the stored register value. This makes a back-to-back
  * W→R sequence (W stage writes and R stage reads the same register in the same
  * cycle) visible with zero pipeline stall.
  *
  * ==Load-use stall==
  *
  * A load-use hazard occurs when the E-stage instruction will write a register
  * that the current R-stage instruction wants to read, and the E-stage
  * instruction is a stall-inducing producer (LOAD_IMM, MOV, ADD_IMM, DEC,
  * AND_IMM, OR_IMM, XOR_IMM, SHIFT — per spec §14). The W→R bypass cannot cover
  * this case because the E-stage result is not yet available at the W stage
  * when the consumer is in R.
  *
  * The pipeline drives the E-stage signals into the RegFile; the RegFile
  * compares them against the R-stage read addresses and exports `loadUseStall`.
  * When `loadUseStall` is high the pipeline must stall F/D/R for one cycle and
  * bubble E.
  *
  * ==No stall for all other RAW cases==
  *
  * Spec §14 states that only the load-use case above needs a stall. All other
  * RAW hazards in the in-flight W→R window are covered by the bypass network;
  * no additional hazard logic is needed on the read path.
  *
  * ==R7 capture convention==
  *
  * The register file does NOT enforce R7-only-for-capture (spec §8 E-REG-001 is
  * an assembler-level lint rule). Any `writeAddr` is accepted by the write
  * port. The pipeline is responsible for driving `writeAddr = 7` when
  * committing a capture result.
  *
  * ==Reset values==
  *
  * Spec §8 says registers are undefined at power-on. For sim predictability
  * this implementation initialises all storage to 0. A defined value is a
  * strict subset of "may be anything", so this is spec-compatible.
  *
  * @param width
  *   Register width in bits (default 32).
  * @param depth
  *   Number of registers (default 8; must be a power of two).
  */
case class RegFile(width: Int = 32, depth: Int = 8) extends Component {

  /** Register index width derived from depth (= 3 for depth = 8). */
  val indexWidth: Int = log2Up(depth)

  val io = new Bundle {

    // ------------------------------------------------------------------
    // Read ports (combinational, with W→R bypass)
    // ------------------------------------------------------------------

    /** R-stage read address for port 0. */
    val readAddr0 = in UInt (indexWidth bits)

    /** R-stage read address for port 1. */
    val readAddr1 = in UInt (indexWidth bits)

    /** Read data port 0: storage value or bypassed write data. */
    val readData0 = out Bits (width bits)

    /** Read data port 1: storage value or bypassed write data. */
    val readData1 = out Bits (width bits)

    // ------------------------------------------------------------------
    // Write port (synchronous, rising-edge commit)
    // ------------------------------------------------------------------

    /** Write enable from the W stage. */
    val writeEnable = in Bool ()

    /** Write address from the W stage. */
    val writeAddr = in UInt (indexWidth bits)

    /** Write data from the W stage. */
    val writeData = in Bits (width bits)

    // ------------------------------------------------------------------
    // E-stage signals for bypass and load-use detection
    // ------------------------------------------------------------------

    /** High when the E stage holds an instruction that will write a register at
      * its W tick.
      */
    val eValid = in Bool ()

    /** Destination register of the E-stage instruction. */
    val eWriteAddr = in UInt (indexWidth bits)

    /** High when the E-stage instruction is a stall-inducing producer
      * (LOAD_IMM, MOV, ADD_IMM, DEC, AND_IMM, OR_IMM, XOR_IMM, SHIFT per spec
      * §14). The pipeline sets this when the E-stage opcode is one of those;
      * the RegFile uses it to gate `loadUseStall`.
      */
    val eIsLoadUse = in Bool ()

    // ------------------------------------------------------------------
    // Hazard output
    // ------------------------------------------------------------------

    /** High when the current R-stage instruction reads a register that the
      * E-stage instruction is about to write AND the E producer is a load-use
      * case. The pipeline must stall F/D/R and bubble E for one cycle when this
      * is asserted.
      */
    val loadUseStall = out Bool ()
  }

  // ------------------------------------------------------------------
  // Storage: 8 × 32-bit registers, initialised to 0 for sim
  // predictability (spec §8 permits any initial value).
  // ------------------------------------------------------------------
  val storage = Vec.fill(depth)(Reg(Bits(width bits)) init (0))

  // ------------------------------------------------------------------
  // Write port: synchronous, rising-edge commit.
  // ------------------------------------------------------------------
  when(io.writeEnable) {
    storage(io.writeAddr) := io.writeData
  }

  // ------------------------------------------------------------------
  // Read port 0: combinational with W→R bypass mux.
  // Bypass fires when the W stage is writing the same address.
  // ------------------------------------------------------------------
  io.readData0 := Mux(
    sel = io.writeEnable && (io.writeAddr === io.readAddr0),
    whenTrue = io.writeData,
    whenFalse = storage(io.readAddr0)
  )

  // ------------------------------------------------------------------
  // Read port 1: symmetric W→R bypass mux.
  // ------------------------------------------------------------------
  io.readData1 := Mux(
    sel = io.writeEnable && (io.writeAddr === io.readAddr1),
    whenTrue = io.writeData,
    whenFalse = storage(io.readAddr1)
  )

  // ------------------------------------------------------------------
  // Load-use stall: E-stage destination matches either R-stage read
  // address, AND the E producer is a stall-inducing opcode, AND there
  // is actually a live E-stage instruction.
  // ------------------------------------------------------------------
  io.loadUseStall :=
    io.eValid &&
      io.eIsLoadUse &&
      ((io.eWriteAddr === io.readAddr0) || (io.eWriteAddr === io.readAddr1))
}
