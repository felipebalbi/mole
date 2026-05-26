package mole

// Imported from felipebalbi/icebreaker-spinalhdl-examples@98c06a8cf39f6c212d3b46d98868aca398a82186 Uart/src/sim/TxShiftRegSim.scala.
// Modified for Mole only as documented inline (search for 'Mole').

import spinal.core._
import spinal.core.sim._

/** Standalone sim for [[TxShiftReg]].
  *
  * What we verify
  *   1. After loading a byte, the bit sequence on `io.bit` over the next 8
  *      shift cycles matches the byte's LSB-first bit order. 2. Multiple test
  *      patterns: all-zeros, all-ones, alternating bits, single-bit walks, and
  *      a generic mixed pattern. Each catches a different class of bug:
  *      - `0x00` / `0xFF`: catches "init value bleeds through" bugs (e.g.
  *        forgetting to actually load).
  *      - `0xAA` / `0x55`: catches off-by-one shift bugs.
  *      - `0x80` / `0x01`: catches LSB/MSB swap bugs that symmetric patterns
  *        (`0xAA`) would mask.
  *      - `0xAD`: a generic mix, no special structure. 3. Load-priority test:
  *        when `load=1` and `shift=1` fire on the same cycle, the freshly
  *        loaded LSB must appear next cycle — not the shifted-then-loaded
  *        value, not the loaded-then-shifted value, just the load. 4. Hold
  *        test: with both `load` and `shift` low, the register content (and
  *        `io.bit`) must not change.
  *
  * Timing model `io.bit` is combinational off a register. After we drive
  * `load=1` and call `waitSampling()`, the rising edge captures `data` into
  * `shiftReg`; on the *next* sim observation `io.bit` reflects `data(0)`. The
  * shift loop therefore: sample, drive shift=1, waitSampling (this is the cycle
  * the shift commits), drop shift. One cycle per shift, no wasted cycles.
  *
  * Run: `sbt "runMain mole.TxShiftRegSim"`
  */
object TxShiftRegSim {
  def main(args: Array[String]): Unit = {

    val cfg = UartConfig(dataBits = 8)

    SimConfig.withWave
      .compile(TxShiftReg(cfg))
      .doSim { dut =>
        dut.clockDomain.forkStimulus(10)

        // ---------- helpers ----------------------------------------------

        /** Pulse load=1 for one cycle with `value` on data, then wait one extra
          * cycle so that the combinational `io.bit := shiftReg(0)` has fully
          * settled to the newly-loaded LSB. Necessary because `io.bit` is
          * combinational off the register; reads issued in the same delta as
          * the register update can race.
          */
        def loadByte(value: Int): Unit = {
          dut.io.data #= value
          dut.io.load #= true
          dut.io.shift #= false
          dut.clockDomain.waitSampling()
          dut.io.load #= false
          dut.io.data #= 0
          dut.clockDomain.waitSampling()
        }

        /** Read 8 bits LSB-first by repeatedly shifting, asserting each matches
          * the expected bit of `value`. After load, the LSB is already visible
          * on `io.bit` — we sample, then shift to expose the next bit. The
          * trailing `waitSampling()` after dropping `shift` gives the
          * combinational `io.bit` a settled cycle before the next iteration's
          * read.
          */
        def expectByte(value: Int, label: String): Unit = {
          for (i <- 0 until cfg.dataBits) {
            val expected = ((value >> i) & 1) == 1
            val got = dut.io.bit.toBoolean
            assert(
              got == expected,
              f"[$label, byte=0x$value%02X] bit $i: expected $expected, got $got"
            )
            dut.io.shift #= true
            dut.clockDomain.waitSampling()
            dut.io.shift #= false
            dut.clockDomain.waitSampling()
          }
        }

        // ---------- init -------------------------------------------------
        dut.io.load #= false
        dut.io.shift #= false
        dut.io.data #= 0
        // forkStimulus holds reset asserted for ~10 cycles by default.
        // Wait well past that so the register's `init(0xff)` value is
        // actually present and subsequent loads are not suppressed.
        dut.clockDomain.waitSampling(20)

        // ---------- (1) + (2) per-pattern shift-out tests ----------------
        val patterns = Seq(0x00, 0xff, 0xaa, 0x55, 0x80, 0x01, 0xad)
        for (p <- patterns) {
          loadByte(p)
          expectByte(p, label = "shift-out")
          // Drain and let things settle between patterns.
          dut.clockDomain.waitSampling(2)
        }

        // ---------- (3) load-priority test -------------------------------
        // First load a known sentinel so we can tell load-wins from
        // shift-wins from no-op.
        loadByte(0x00)
        // Sanity: bit should be 0 (LSB of 0x00).
        assert(!dut.io.bit.toBoolean, "sentinel load 0x00 did not take effect")

        // Now drive load=1 AND shift=1 in the same cycle with a new byte
        // whose LSB is 1.  Expected behaviour:
        //   - load wins -> next cycle io.bit = (newByte)(0) = 1
        //   - shift won  -> next cycle io.bit = (0x00 >> 1)(0) = 0
        //   - both ran   -> ambiguous, but load-wins gives bit=1
        // So observing 1 next cycle proves load-wins.
        val newByte = 0xa5 // LSB = 1
        dut.io.data #= newByte
        dut.io.load #= true
        dut.io.shift #= true
        dut.clockDomain.waitSampling()
        dut.io.load #= false
        dut.io.shift #= false
        dut.io.data #= 0
        dut.clockDomain.waitSampling() // settle combinational io.bit
        assert(
          dut.io.bit.toBoolean,
          f"load-priority test: expected bit=1 (LSB of 0x$newByte%02X), got 0 — shift won over load"
        )
        // While we're here, drain it out and verify the *whole* loaded
        // byte is correct, not just the LSB.
        expectByte(newByte, label = "load-priority post-drain")

        // ---------- (4) hold test ----------------------------------------
        loadByte(0xc3) // 11000011, LSB=1
        val before = dut.io.bit.toBoolean
        assert(before, "hold test: post-load LSB of 0xC3 should be 1")
        // Park: both inputs low for a bunch of cycles.
        dut.io.load #= false
        dut.io.shift #= false
        dut.clockDomain.waitSampling(20)
        val after = dut.io.bit.toBoolean
        assert(
          before == after,
          s"hold test: io.bit changed without load or shift ($before -> $after)"
        )

        println("OK: TxShiftReg behaves correctly")
      }
  }
}
