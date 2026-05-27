package mole

import spinal.core._
import spinal.core.sim._

/** CRC-16/XMODEM audit for [[Crc16Xmodem]] and the wire-format CRC contract.
  *
  * Two layers of coverage:
  *
  *   1. **Pure-Scala** (`Crc16Xmodem.updateAll`): verify the catalogue check
  *      vector and every Mole-specific vector listed in `../../WIRE_FORMAT.md`.
  *      Catches a reference-algorithm regression in `Crc16Xmodem.updateAll`,
  *      which sims and the eventual host-side builder both rely on as a golden
  *      source.
  *   2. **SpinalSim DUT** ([[Crc16Xmodem]] Component): drive the same byte
  *      sequences through the registered accumulator and assert `io.value`
  *      matches `updateAll`. Catches a hardware-vs-software divergence in
  *      [[Crc16Xmodem.update]] (the combinational byte-step the loader will use
  *      cycle-by-cycle), and exercises the `init` / `update` priority contract.
  *
  * The two layers are deliberately redundant: the catalogue check value is what
  * an independent host-side implementation will use to validate its own CRC;
  * the DUT sim then proves the hardware agrees byte-for-byte with that same
  * software reference. Either layer alone leaves a wire-format gap.
  *
  * Run: `sbt "runMain mole.Crc16XmodemSim"`
  */
object Crc16XmodemSim extends App {

  // --------------------------------------------------------------
  // Test vectors --- single source of truth, lifted from
  // ../../WIRE_FORMAT.md section 3.2. Any change to that table must
  // change this list in lockstep.
  // --------------------------------------------------------------

  private case class Vec(label: String, bytes: Seq[Int], crc: Int)

  // Pre-built example frame from WIRE_FORMAT.md section 6:
  //   len=2, w0=0x9000, w1=0x6000  ->  payload bytes 02 00 00 90 00 60
  private val exampleFramePayload: Seq[Int] =
    Seq(0x02, 0x00, 0x00, 0x90, 0x00, 0x60)

  private val vectors: Seq[Vec] = Seq(
    Vec("empty", Seq.empty, 0x0000),
    Vec("single 0x00", Seq(0x00), 0x0000),
    Vec("single 0xFF", Seq(0xff), 0x1ef0),
    Vec(
      "ASCII 123456789",
      "123456789".getBytes("US-ASCII").map(_ & 0xff).toSeq,
      0x31c3
    ),
    Vec("two 0x00", Seq(0x00, 0x00), 0x0000),
    Vec("0xAA 0x55", Seq(0xaa, 0x55), 0xf8e5),
    Vec("example frame payload", exampleFramePayload, 0x9fdf)
  )

  // --------------------------------------------------------------
  // Layer 1: pure-Scala reference vs vectors
  // --------------------------------------------------------------

  println("--- Crc16XmodemSim: pure-Scala reference ---")
  for (v <- vectors) {
    val got = Crc16Xmodem.updateAll(Crc16Xmodem.INIT, v.bytes)
    assert(
      got == v.crc,
      f"updateAll/${v.label}: expected 0x${v.crc}%04X, got 0x${got}%04X"
    )
    println(f"  ${v.label}%-25s 0x${v.crc}%04X  OK")
  }

  // --------------------------------------------------------------
  // Layer 2: hardware DUT vs the same vectors
  // --------------------------------------------------------------

  println("--- Crc16XmodemSim: hardware DUT ---")
  SimConfig.compile(Crc16Xmodem()).doSim("dut-vs-vectors") { dut =>
    dut.clockDomain.forkStimulus(period = 10)

    // Idle defaults.
    dut.io.init #= false
    dut.io.update.valid #= false
    dut.io.update.payload #= 0
    dut.clockDomain.waitSampling(2)

    // Both helpers follow the canonical "drive input, wait edge, drop
    // input, wait one extra edge to settle" pattern used by
    // TxShiftRegSim.loadByte. The trailing waitSampling() is not
    // optional: `io.value := crc` is combinational off the register,
    // and a read issued in the same delta as the edge that updated
    // the register can race the combinational propagation and return
    // the pre-edge value. Burn one quiet cycle (when/elsewhen are
    // both false, so the register holds) to let io.value settle to
    // the new register value before the caller samples it.

    def reset(): Unit = {
      dut.io.init #= true
      dut.io.update.valid #= false
      dut.clockDomain.waitSampling() // edge with init=true -> crc := 0
      dut.io.init #= false
      dut.clockDomain.waitSampling() // settle io.value combinational
      assert(
        dut.io.value.toLong == Crc16Xmodem.INIT,
        f"reset: value should be 0x${Crc16Xmodem.INIT}%04X, got 0x${dut.io.value.toLong}%04X"
      )
    }

    def feed(bytes: Seq[Int]): Unit = {
      for (b <- bytes) {
        dut.io.update.valid #= true
        dut.io.update.payload #= (b & 0xff).toLong
        dut.clockDomain.waitSampling() // edge consumes byte
      }
      dut.io.update.valid #= false
      dut.clockDomain.waitSampling() // settle io.value combinational
    }

    for (v <- vectors) {
      reset()
      feed(v.bytes)
      val got = dut.io.value.toLong & 0xffff
      assert(
        got == v.crc,
        f"dut/${v.label}: expected 0x${v.crc}%04X, got 0x${got}%04X"
      )
      println(f"  ${v.label}%-25s 0x${v.crc}%04X  OK")
    }

    // --------------------------------------------------------------
    // Contract: `init` wins over `update` in the same cycle.
    // --------------------------------------------------------------
    reset()
    feed(Seq(0xaa, 0x55)) // running CRC = 0xF8E5
    assert((dut.io.value.toLong & 0xffff) == 0xf8e5, "pre-collision setup")

    dut.io.init #= true
    dut.io.update.valid #= true
    dut.io.update.payload #= 0xff
    dut.clockDomain.waitSampling() // edge: init=true wins -> crc := 0
    dut.io.init #= false
    dut.io.update.valid #= false
    dut.clockDomain.waitSampling() // settle io.value combinational
    assert(
      (dut.io.value.toLong & 0xffff) == Crc16Xmodem.INIT,
      f"init+update collision: init must win; got 0x${dut.io.value.toLong}%04X"
    )
    println("  init wins over update collision  OK")

    // --------------------------------------------------------------
    // Contract: when `update.valid` is low, the register holds.
    // --------------------------------------------------------------
    reset()
    feed(Seq(0x12, 0x34))
    val held = dut.io.value.toLong & 0xffff
    dut.io.update.valid #= false
    dut.clockDomain.waitSampling(10)
    assert(
      (dut.io.value.toLong & 0xffff) == held,
      "value must hold while update.valid is low"
    )
    println("  holds while update.valid low     OK")

    // --------------------------------------------------------------
    // Contract: the loader-pattern fits.
    //
    // The loader fires `update` for every payload byte then stops
    // firing during the CRC trailer (so the running CRC reflects the
    // pre-trailer payload). It then compares `io.value` against the
    // 16-bit CRC the host placed in the trailer. Simulate that exact
    // pattern over the example frame and assert the compare succeeds.
    //
    // Note we do NOT use the "feed payload + trailer, expect 0x0000"
    // residue trick: CRC-16/XMODEM has residue 0x0000 only when the
    // appended CRC is big-endian (MSB-first); Mole's wire format
    // serialises the CRC trailer little-endian, so the residue is not
    // zero. Compare-against-trailer is the correct loader pattern.
    // --------------------------------------------------------------
    reset()
    feed(exampleFramePayload)
    val computed = dut.io.value.toLong & 0xffff
    val trailerLo = 0xdfL // crc_lo of 0x9FDF
    val trailerHi = 0x9fL // crc_hi of 0x9FDF
    val trailerWord = (trailerHi << 8) | trailerLo
    assert(
      computed == trailerWord,
      f"loader pattern: computed 0x${computed}%04X vs trailer 0x${trailerWord}%04X"
    )
    println("  loader-pattern compare           OK")
  }

  println("Crc16XmodemSim: all cases passed")
}
