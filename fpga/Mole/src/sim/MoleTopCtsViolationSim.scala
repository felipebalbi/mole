package mole

import spinal.core._
import spinal.core.sim._
import spinal.lib._

// **C.11.b FINDING:** This sim currently fails because the engine
// fetches the v0.2 frame preamble (MAGIC + body_len) at PC=0 as
// instructions and traps. See `MoleTopSim.scala` header comment for
// the full diagnosis and the proposed fix shape. Not in aggregate
// `make sim`; run via `make sim-top-cts-violation` to reproduce.

/** F-FPGA-007 regression coverage: CTS#-violation sticky observable (v0.2).
  *
  * Reuses [[MoleTopSimDut]] (same fabric clock, same UART config, same sim-side
  * TX/RX). The sim port `ctsViolationObserved` taps
  * `MoleTop.ctsViolationObservedReg`, which latches True on the first UART RX
  * byte that arrives while the phase FSM is NOT in `acceptLoadState` (i.e. CTS#
  * is deasserted and the host is supposed to be silent per the CTS contract).
  *
  * Single case: load a tiny program, wait for the engine to leave `acceptLoad`,
  * then push a stray UART byte from the sim TX. The sticky must assert within a
  * bounded number of cycles and must stay asserted for the rest of the run. The
  * fault LED must also light (the sticky is OR'd into `io_ledFault`; see
  * `icebreaker.pcf` for the function → pin → physical-LED mapping).
  */
object MoleTopCtsViolationSim extends App {
  import MoleTopSimSupport._

  val cfg = testCfg

  // A program with a long-running WAIT_ON keeps the engine in
  // `runningState` (CTS# deasserted) long enough for one UART byte to
  // traverse the sim TX, hit MoleTop's RxFsm, and surface as
  // `uartRx.payload.valid`. WAIT_ON ALWAYS would short-circuit
  // immediately --- we want a cond that NEVER fires so the engine
  // genuinely waits out the timeout. STOP_SEEN against an idle bus
  // (no Stop edge) waits for the full 7-bit (v0) / 10-bit (v0.2)
  // timeout.
  //
  // The timeout is in quarter-bit times. With
  // `quarterPeriodCyclesReset = 6` and timeout = 1023 (v0.2's 10-bit
  // max), the wait lasts roughly 1023 * 6 = 6138 fabric cycles ---
  // well over a single UART byte (~120 cycles at 2 Mbaud / 24 MHz).
  // The WAIT_ON then times out (TIMEOUT_FLAG sticky), the HALT runs,
  // and the engine re-enters idle.
  val longProgram = Seq(
    Instruction.encode(Instruction.SetBusMode(BusMode.i2c)),
    Instruction.encode(Instruction.WaitOn(CondCode.stopSeen, 1023)),
    Instruction.encode(Instruction.Halt(0))
  )
  val longFrame = buildFrame(longProgram)

  println(
    "--- MoleTopCtsViolationSim: rx_during_running_sets_cts_violation_sticky ---"
  )
  simConfig
    .compile(MoleTopSimDut(cfg))
    .doSim("rx-during-running-sets-cts-violation-sticky") { dut =>
      dut.clockDomain.forkStimulus(period = 10)
      doReset(dut)

      // Sanity: out of reset, the sticky must be False.
      assert(
        !dut.io.ctsViolationObserved.toBoolean,
        "post-reset: ctsViolationObserved should start False"
      )

      // Load the program. Loader RX consumes the frame while CTS# is
      // asserted --- those bytes are NOT violations.
      sendFrame(dut, longFrame)

      // Wait until the phase FSM moves out of acceptLoad
      // (CTS# deasserts == io_uCts goes high).
      var waited = 0
      while (!dut.io.ctsOut.toBoolean && waited < 200_000) {
        dut.clockDomain.waitSampling()
        waited += 1
      }
      assert(
        dut.io.ctsOut.toBoolean,
        "engine never entered running/draining (CTS# never deasserted)"
      )

      // Sticky must still be False --- we haven't sent a stray byte
      // yet. (CTS deasserts BEFORE the violation occurs.)
      assert(
        !dut.io.ctsViolationObserved.toBoolean,
        "ctsViolationObserved asserted before any stray byte was sent"
      )

      // Push a stray byte. The sim TX serialises it over ~240 fabric
      // cycles; MoleTop's RxFsm raises `uartRx.payload.valid` shortly
      // after the stop bit. The sticky must latch True somewhere in
      // the ~500-cycle window after the byte is queued. Give it
      // generous headroom (5000 cycles).
      sendByte(dut, 0xa5)

      var observed = false
      var c = 0
      while (!observed && c < 5_000) {
        if (dut.io.ctsViolationObserved.toBoolean) observed = true
        dut.clockDomain.waitSampling()
        c += 1
      }
      assert(
        observed,
        "ctsViolationObserved never asserted after stray UART byte during running phase"
      )

      // Fault LED must reflect the sticky (OR'd into io_ledFault).
      // LED polarity: io_ledFault is active-low (drive LOW = pin LOW =
      // LED ON; drive HIGH = pin HIGH = LED OFF). When the sticky
      // ctsViolation latches, MoleTop's
      // `io_ledFault := !((counter != 0) || ctsViolationObservedReg)`
      // drives False, so "LED is lit" reads as
      // `!dut.io.ledFault.toBoolean`.
      assert(
        !dut.io.ledFault.toBoolean,
        "io_ledFault did not assert (pin not driven low) when " +
          "ctsViolationObserved latched"
      )

      // Stickiness: sample for another 2000 cycles --- must stay True
      // regardless of what the phase FSM does next (engine eventually
      // times out the WAIT_ON, halts, drains, returns to acceptLoad;
      // the sticky must survive all of that).
      for (_ <- 0 until 2_000) {
        assert(
          dut.io.ctsViolationObserved.toBoolean,
          "ctsViolationObserved cleared --- sticky violated"
        )
        dut.clockDomain.waitSampling()
      }

      println("   ok: sticky latched, LED asserted, survived phase transitions")
    }

  println("--- MoleTopCtsViolationSim: all cases passed ---")
}
