package mole

import spinal.core._
import spinal.core.sim._

/** Companion sim for [[RegFile]].
  *
  * Exercises all 14 specified scenarios covering storage correctness, W→R
  * bypass, load-use stall detection, and the interaction between bypass and
  * stall logic.
  *
  * All cases operate on a `RegFile(width = 32, depth = 8)` instance — the same
  * configuration the v0.2 pipeline will use.
  *
  * Run: `sbt "runMain mole.RegFileSim"`
  */
object RegFileSim {

  private def compileDut() =
    SimConfig.withWave.compile(RegFile(width = 32, depth = 8))

  // ------------------------------------------------------------------
  // Helpers: one tick of clock with named inputs applied beforehand.
  // ------------------------------------------------------------------

  /** Drive all inputs to quiescent (no write, no E-stage, read addr 0). */
  private def quiet(dut: RegFile): Unit = {
    dut.io.readAddr0 #= 0
    dut.io.readAddr1 #= 0
    dut.io.writeEnable #= false
    dut.io.writeAddr #= 0
    dut.io.writeData #= 0
    dut.io.eValid #= false
    dut.io.eWriteAddr #= 0
    dut.io.eIsLoadUse #= false
  }

  /** Drive a synchronous write. Does NOT advance the clock. */
  private def driveWrite(
      dut: RegFile,
      addr: Int,
      data: Long,
      enable: Boolean = true
  ): Unit = {
    dut.io.writeEnable #= enable
    dut.io.writeAddr #= addr
    dut.io.writeData #= data
  }

  // ------------------------------------------------------------------
  // Case a: basic write / read
  // ------------------------------------------------------------------

  /** Write each of the 8 registers in sequence; read each one back. */
  def caseBasicWriteRead(): Unit = {
    compileDut().doSim("basic-write-read") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.clockDomain.waitSampling(4)

      // Write a distinct pattern to each register.
      for (r <- 0 until 8) {
        val data = (0x12340000L | (r.toLong << 4) | r.toLong) & 0xffffffffL
        driveWrite(dut, r, data)
        dut.clockDomain.waitSampling()
      }
      dut.io.writeEnable #= false
      dut.clockDomain.waitSampling(2)

      // Read each register back via port 0 (no concurrent write).
      for (r <- 0 until 8) {
        val expected = (0x12340000L | (r.toLong << 4) | r.toLong) & 0xffffffffL
        dut.io.readAddr0 #= r
        dut.clockDomain.waitSampling()
        val got = dut.io.readData0.toLong & 0xffffffffL
        assert(
          got == expected,
          s"[basic-write-read] R$r: expected 0x${expected.toHexString} got 0x${got.toHexString}"
        )
      }

      println("[caseBasicWriteRead] PASS")
    }
  }

  // ------------------------------------------------------------------
  // Case b: read after write same cycle (bypass)
  // ------------------------------------------------------------------

  /** Drive write to R3 and read R3 on port 0 in the same cycle. The bypass must
    * forward the write data, not the stored value.
    */
  def caseBypassSameCycle(): Unit = {
    compileDut().doSim("bypass-same-cycle") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.clockDomain.waitSampling(4)

      val wData = 0xdeadbeefL
      driveWrite(dut, 3, wData)
      dut.io.readAddr0 #= 3

      // Sample combinational outputs before the edge commits the write.
      dut.clockDomain.waitSampling()
      val got = dut.io.readData0.toLong & 0xffffffffL
      assert(
        got == wData,
        s"[bypass-same-cycle] expected 0x${wData.toHexString} got 0x${got.toHexString}"
      )

      println("[caseBypassSameCycle] PASS")
    }
  }

  // ------------------------------------------------------------------
  // Case c: read after write next cycle
  // ------------------------------------------------------------------

  /** Write R5 on cycle N, read R5 on cycle N+1 (no concurrent write). */
  def caseReadNextCycle(): Unit = {
    compileDut().doSim("read-next-cycle") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.clockDomain.waitSampling(4)

      val wData = 0xcafebabeL
      driveWrite(dut, 5, wData)
      dut.clockDomain.waitSampling() // write commits on this edge

      dut.io.writeEnable #= false
      dut.io.readAddr0 #= 5
      dut.clockDomain.waitSampling()
      val got = dut.io.readData0.toLong & 0xffffffffL
      assert(
        got == wData,
        s"[read-next-cycle] expected 0x${wData.toHexString} got 0x${got.toHexString}"
      )

      println("[caseReadNextCycle] PASS")
    }
  }

  // ------------------------------------------------------------------
  // Case d: bypass priority — write overrides stored value
  // ------------------------------------------------------------------

  /** Initialise R2 = 0xAAAA_AAAA, then simultaneously write 0xBBBB_BBBB and
    * read R2 on port 0. The bypass must return 0xBBBB_BBBB.
    */
  def caseBypassPriority(): Unit = {
    compileDut().doSim("bypass-priority") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.clockDomain.waitSampling(4)

      // Seed R2 with the old value.
      driveWrite(dut, 2, 0xaaaaaaaaL)
      dut.clockDomain.waitSampling()

      // Now do the contending write+read in the same cycle.
      val newData = 0xbbbbbbbbL
      driveWrite(dut, 2, newData)
      dut.io.readAddr0 #= 2
      dut.clockDomain.waitSampling()
      val got = dut.io.readData0.toLong & 0xffffffffL
      assert(
        got == newData,
        s"[bypass-priority] expected 0x${newData.toHexString} got 0x${got.toHexString}"
      )

      println("[caseBypassPriority] PASS")
    }
  }

  // ------------------------------------------------------------------
  // Case e: bypass per-port independence
  // ------------------------------------------------------------------

  /** Write to R4 while port 0 reads R4 (must get new value) and port 1 reads R7
    * (must get stored value).
    */
  def caseBypassPortIndependence(): Unit = {
    compileDut().doSim("bypass-port-independence") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.clockDomain.waitSampling(4)

      // Seed R4 and R7 with distinct patterns.
      driveWrite(dut, 4, 0x44444444L)
      dut.clockDomain.waitSampling()
      driveWrite(dut, 7, 0x77777777L)
      dut.clockDomain.waitSampling()

      // Write new value to R4; read R4 on port 0, R7 on port 1.
      val newR4 = 0x40404040L
      driveWrite(dut, 4, newR4)
      dut.io.readAddr0 #= 4
      dut.io.readAddr1 #= 7
      dut.clockDomain.waitSampling()

      val got0 = dut.io.readData0.toLong & 0xffffffffL
      val got1 = dut.io.readData1.toLong & 0xffffffffL
      assert(
        got0 == newR4,
        s"[bypass-port-independence] port0: expected 0x${newR4.toHexString} got 0x${got0.toHexString}"
      )
      assert(
        got1 == 0x77777777L,
        s"[bypass-port-independence] port1: expected 0x77777777 got 0x${got1.toHexString}"
      )

      println("[caseBypassPortIndependence] PASS")
    }
  }

  // ------------------------------------------------------------------
  // Case f: two-read-port simultaneous distinct addresses
  // ------------------------------------------------------------------

  /** Pre-init R0 = 1, R7 = 7. Simultaneously read both. */
  def caseTwoPortDistinct(): Unit = {
    compileDut().doSim("two-port-distinct") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.clockDomain.waitSampling(4)

      driveWrite(dut, 0, 1L)
      dut.clockDomain.waitSampling()
      driveWrite(dut, 7, 7L)
      dut.clockDomain.waitSampling()

      dut.io.writeEnable #= false
      dut.io.readAddr0 #= 0
      dut.io.readAddr1 #= 7
      dut.clockDomain.waitSampling()

      val got0 = dut.io.readData0.toLong & 0xffffffffL
      val got1 = dut.io.readData1.toLong & 0xffffffffL
      assert(
        got0 == 1L,
        s"[two-port-distinct] port0: expected 1 got $got0"
      )
      assert(
        got1 == 7L,
        s"[two-port-distinct] port1: expected 7 got $got1"
      )

      println("[caseTwoPortDistinct] PASS")
    }
  }

  // ------------------------------------------------------------------
  // Case g: two-read-port simultaneous same address
  // ------------------------------------------------------------------

  /** Both ports address R4; both must return the same stored value. */
  def caseTwoPortSameAddr(): Unit = {
    compileDut().doSim("two-port-same-addr") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.clockDomain.waitSampling(4)

      driveWrite(dut, 4, 0xfeedc0deL)
      dut.clockDomain.waitSampling()

      dut.io.writeEnable #= false
      dut.io.readAddr0 #= 4
      dut.io.readAddr1 #= 4
      dut.clockDomain.waitSampling()

      val got0 = dut.io.readData0.toLong & 0xffffffffL
      val got1 = dut.io.readData1.toLong & 0xffffffffL
      assert(
        got0 == got1,
        s"[two-port-same-addr] ports disagree: port0=0x${got0.toHexString} port1=0x${got1.toHexString}"
      )
      assert(
        got0 == 0xfeedc0deL,
        s"[two-port-same-addr] expected 0xfeedc0de got 0x${got0.toHexString}"
      )

      println("[caseTwoPortSameAddr] PASS")
    }
  }

  // ------------------------------------------------------------------
  // Case h: load-use stall — reader matches eWriteAddr on port 0
  // ------------------------------------------------------------------

  /** eValid=1, eIsLoadUse=1, eWriteAddr=2, readAddr0=2 → stall=1. */
  def caseStallPort0Match(): Unit = {
    compileDut().doSim("stall-port0-match") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.clockDomain.waitSampling(4)

      dut.io.eValid #= true
      dut.io.eIsLoadUse #= true
      dut.io.eWriteAddr #= 2
      dut.io.readAddr0 #= 2
      dut.io.readAddr1 #= 0
      dut.clockDomain.waitSampling()

      assert(
        dut.io.loadUseStall.toBoolean,
        "[stall-port0-match] expected loadUseStall=1"
      )

      println("[caseStallPort0Match] PASS")
    }
  }

  // ------------------------------------------------------------------
  // Case i: load-use stall — reader matches eWriteAddr on port 1
  // ------------------------------------------------------------------

  /** eValid=1, eIsLoadUse=1, eWriteAddr=5, readAddr1=5 → stall=1. */
  def caseStallPort1Match(): Unit = {
    compileDut().doSim("stall-port1-match") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.clockDomain.waitSampling(4)

      dut.io.eValid #= true
      dut.io.eIsLoadUse #= true
      dut.io.eWriteAddr #= 5
      dut.io.readAddr0 #= 0
      dut.io.readAddr1 #= 5
      dut.clockDomain.waitSampling()

      assert(
        dut.io.loadUseStall.toBoolean,
        "[stall-port1-match] expected loadUseStall=1"
      )

      println("[caseStallPort1Match] PASS")
    }
  }

  // ------------------------------------------------------------------
  // Case j: load-use stall — neither port matches
  // ------------------------------------------------------------------

  /** eValid=1, eIsLoadUse=1, eWriteAddr=3, readAddr0=0, readAddr1=1 → stall=0.
    */
  def caseStallNoMatch(): Unit = {
    compileDut().doSim("stall-no-match") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.clockDomain.waitSampling(4)

      dut.io.eValid #= true
      dut.io.eIsLoadUse #= true
      dut.io.eWriteAddr #= 3
      dut.io.readAddr0 #= 0
      dut.io.readAddr1 #= 1
      dut.clockDomain.waitSampling()

      assert(
        !dut.io.loadUseStall.toBoolean,
        "[stall-no-match] expected loadUseStall=0"
      )

      println("[caseStallNoMatch] PASS")
    }
  }

  // ------------------------------------------------------------------
  // Case k: eIsLoadUse=0 inhibits stall despite address match
  // ------------------------------------------------------------------

  /** eValid=1, eIsLoadUse=0, eWriteAddr=2, readAddr0=2 → stall=0. */
  def caseStallInhibitedByIsLoadUse(): Unit = {
    compileDut().doSim("stall-inhibited-by-isloaduse") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.clockDomain.waitSampling(4)

      dut.io.eValid #= true
      dut.io.eIsLoadUse #= false
      dut.io.eWriteAddr #= 2
      dut.io.readAddr0 #= 2
      dut.clockDomain.waitSampling()

      assert(
        !dut.io.loadUseStall.toBoolean,
        "[stall-inhibited-by-isloaduse] expected loadUseStall=0"
      )

      println("[caseStallInhibitedByIsLoadUse] PASS")
    }
  }

  // ------------------------------------------------------------------
  // Case l: eValid=0 inhibits stall despite address match
  // ------------------------------------------------------------------

  /** eValid=0, eIsLoadUse=1, eWriteAddr=2, readAddr0=2 → stall=0. */
  def caseStallInhibitedByEValid(): Unit = {
    compileDut().doSim("stall-inhibited-by-evalid") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.clockDomain.waitSampling(4)

      dut.io.eValid #= false
      dut.io.eIsLoadUse #= true
      dut.io.eWriteAddr #= 2
      dut.io.readAddr0 #= 2
      dut.clockDomain.waitSampling()

      assert(
        !dut.io.loadUseStall.toBoolean,
        "[stall-inhibited-by-evalid] expected loadUseStall=0"
      )

      println("[caseStallInhibitedByEValid] PASS")
    }
  }

  // ------------------------------------------------------------------
  // Case m: bypass + load-use interaction (orthogonality check)
  // ------------------------------------------------------------------

  /** W-stage writes R3, E-stage has a load-use hazard on R4. Port 0 reads R3
    * (must get W bypass), port 1 reads R4 (triggers stall). Both effects must
    * be simultaneous and independent.
    */
  def caseBypassAndStallOrthogonal(): Unit = {
    compileDut().doSim("bypass-and-stall-orthogonal") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.clockDomain.waitSampling(4)

      // Seed R3 with an old value so we can confirm bypass wins.
      driveWrite(dut, 3, 0x11111111L)
      dut.clockDomain.waitSampling()
      dut.io.writeEnable #= false
      dut.clockDomain.waitSampling(2)

      // Now drive all three simultaneously:
      //   W-stage: write R3 = 0xcafef00d
      //   E-stage: eValid=1, eIsLoadUse=1, eWriteAddr=4
      //   R-stage: readAddr0=3, readAddr1=4
      val wData = 0xcafef00dL
      driveWrite(dut, 3, wData)
      dut.io.readAddr0 #= 3
      dut.io.readAddr1 #= 4
      dut.io.eValid #= true
      dut.io.eIsLoadUse #= true
      dut.io.eWriteAddr #= 4
      dut.clockDomain.waitSampling()

      val got0 = dut.io.readData0.toLong & 0xffffffffL
      val stall = dut.io.loadUseStall.toBoolean
      assert(
        got0 == wData,
        s"[bypass-and-stall-orthogonal] port0 bypass: expected 0x${wData.toHexString} got 0x${got0.toHexString}"
      )
      assert(
        stall,
        "[bypass-and-stall-orthogonal] expected loadUseStall=1 (E-stage hazard on R4)"
      )

      println("[caseBypassAndStallOrthogonal] PASS")
    }
  }

  // ------------------------------------------------------------------
  // Case n: reset / power-on — all registers read 0
  // ------------------------------------------------------------------

  /** After elaboration with no writes, all registers read 0. This is a
    * sim-friendly stronger guarantee than spec §8 requires; it makes test
    * ordering deterministic.
    */
  def caseResetAllZero(): Unit = {
    compileDut().doSim("reset-all-zero") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      quiet(dut)
      dut.clockDomain.waitSampling(4)

      for (r <- 0 until 8) {
        dut.io.readAddr0 #= r
        dut.clockDomain.waitSampling()
        val got = dut.io.readData0.toLong & 0xffffffffL
        assert(
          got == 0L,
          s"[reset-all-zero] R$r: expected 0 got 0x${got.toHexString}"
        )
      }

      println("[caseResetAllZero] PASS")
    }
  }

  // ------------------------------------------------------------------
  // Entry point
  // ------------------------------------------------------------------
  def main(args: Array[String]): Unit = {
    caseBasicWriteRead()
    caseBypassSameCycle()
    caseReadNextCycle()
    caseBypassPriority()
    caseBypassPortIndependence()
    caseTwoPortDistinct()
    caseTwoPortSameAddr()
    caseStallPort0Match()
    caseStallPort1Match()
    caseStallNoMatch()
    caseStallInhibitedByIsLoadUse()
    caseStallInhibitedByEValid()
    caseBypassAndStallOrthogonal()
    caseResetAllZero()
    println("RegFileSim: all 14 cases passed")
  }
}
