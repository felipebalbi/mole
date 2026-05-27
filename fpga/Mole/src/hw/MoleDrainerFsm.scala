package mole

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

/** Post-halt result-ring sweeper.
  *
  * Streams the engine's `resultWordCount`-word result ring out of SPRAM
  * onto a byte `Stream` (low byte first), one full ring per
  * `triggerDrain` pulse from the top-level phase FSM.
  *
  * == Layering ==
  *
  *   - The drainer does NOT watch `engine.done` directly. The top-level
  *     phase FSM owns the `Running -> Draining` transition and pulses
  *     `triggerDrain` exactly once per real halt. This eliminates the
  *     edge/level race that the plan calls out --- crucially, on
  *     first-power-up `engine.done` is high but no program has ever
  *     run, and the phase FSM (in `AcceptLoad`) never trips the drainer.
  *
  *   - The drainer owns the SPRAM read port (`readCmd` / `readResp`)
  *     only while it is active. While idle, the engine has the read
  *     port to itself.
  *
  * == SPRAM read timing ==
  *
  * The SPRAM controller services `readCmd` at priority 1 (always
  * `ready`) and the corresponding `readResp.valid` arrives EXACTLY one
  * cycle after `readCmd.fire`. The FSM exploits this: `issueRead`
  * drives `valid` until fire, `waitResp` lands one cycle later and the
  * response is guaranteed to be on the wire.
  *
  * == UART back-pressure ==
  *
  * `UartTx.data.ready` drops while a byte is on the wire (~10 fabric
  * cycles per UART bit at the default 48 MHz / 2 Mbaud). The FSM holds
  * `txData.valid` and `txData.payload` stable in `sendLo` / `sendHi`
  * until the handshake fires --- never advances state or address on
  * `valid && !ready`. The `MoleDrainerFsmSim` random-throttle and
  * long-stall cases verify byte order survives arbitrary back-pressure.
  *
  * == drainComplete pulse contract ==
  *
  * `drainComplete` pulses for exactly one cycle the SAME cycle the
  * final byte (high byte of the last ring word) fires on `txData`.
  * The phase FSM transitions `Draining -> AcceptLoad` on that pulse.
  * Earlier drafts of this file used a separate `done` state and
  * pulsed two cycles late, which violated the spec and made the
  * phase-FSM handoff window unnecessarily lossy.
  *
  * == State list ==
  *
  *   - `idleState`: waits for `triggerDrain`; resets `addrReg` to
  *     `resultBase` on trigger; transitions to `issueRead`.
  *   - `issueReadState`: drives `readCmd.valid := True` until fire.
  *   - `waitRespState`: waits for `readResp.valid` (1 cycle); latches
  *     into `wordReg`.
  *   - `sendLoState` / `sendHiState`: byte stream out, with proper
  *     back-pressure. The final fire in `sendHi` pulses
  *     `drainComplete` and returns to `idleState` in one step.
  *
  * Five states total --- the plan called out seven (`Advance` / `Done`
  * as separate stages); we collapsed the increment and completion
  * into `sendHi`'s fire branch to honour the "pulse on fire" spec.
  *
  * @param resultBase
  *   SPRAM word address of the first ring word (= `programWordCount`
  *   in the standard layout).
  * @param resultWordCount
  *   number of 16-bit words in the ring.
  * @param addrWidth
  *   SPRAM address width (matches `SpramController.addrWidth`).
  */
case class MoleDrainerFsm(
    resultBase: Int,
    resultWordCount: Int,
    addrWidth: Int
) extends Component {

  require(resultBase >= 0, s"resultBase=$resultBase must be >= 0")
  require(resultWordCount >= 1, s"resultWordCount=$resultWordCount must be >= 1")
  require(addrWidth >= 1, s"addrWidth=$addrWidth must be >= 1")
  require(
    resultBase + resultWordCount <= (1 << addrWidth),
    s"resultBase ($resultBase) + resultWordCount ($resultWordCount) overflows ${1 << addrWidth}-word address space"
  )

  val io = new Bundle {

    /** One-cycle pulse from the top-level phase FSM on `Running ->
      * Draining`. The drainer latches the trigger as a goto from
      * `idleState` to `issueReadState`; a pulse while busy is ignored.
      */
    val triggerDrain = in Bool ()

    /** SPRAM read command port (priority 1 in the controller; always
      * ready). `valid` is asserted in `issueReadState` until the
      * handshake fires.
      */
    val readCmd = master Stream UInt(addrWidth bits)

    /** SPRAM read response port. `valid` arrives exactly one cycle
      * after `readCmd.fire`; `payload` is the 16-bit word at the
      * requested address.
      */
    val readResp = slave Flow Bits(16 bits)

    /** Byte stream sink. Drives the controller's `UartTx.data` port.
      * Low byte of each word goes first, then high byte.
      */
    val txData = master Stream Bits(8 bits)

    /** Single-cycle pulse the SAME cycle the final byte (high byte of
      * the last ring word) fires on `txData`.
      */
    val drainComplete = out Bool ()

    /** Status: high whenever the drainer is not in `idleState`. The
      * top-level phase FSM uses this to gate ownership of the SPRAM
      * read port.
      */
    val active = out Bool ()
  }

  // --------------------------------------------------------------------
  // Address counter. Sized to addrWidth so it can address the whole
  // SPRAM map (the upper bits address the ring; the lower bits hold
  // the base + offset).
  // --------------------------------------------------------------------
  val addrReg = Reg(UInt(addrWidth bits)) init U(0, addrWidth bits)
  val resultLimit: UInt = U(resultBase + resultWordCount - 1, addrWidth bits)

  // Latched word from SPRAM. Captured in waitRespState; consumed in
  // sendLoState / sendHiState.
  val wordReg = Reg(Bits(16 bits)) init B(0, 16 bits)

  // --------------------------------------------------------------------
  // Defaults --- overridden inside the FSM as needed.
  // --------------------------------------------------------------------
  io.readCmd.valid := False
  io.readCmd.payload := addrReg

  io.txData.valid := False
  io.txData.payload := wordReg(7 downto 0)

  io.drainComplete := False

  // --------------------------------------------------------------------
  // FSM.
  // --------------------------------------------------------------------
  val fsm = new StateMachine {

    // idle: wait for the phase FSM's trigger.
    val idleState: State = new State with EntryPoint {
      whenIsActive {
        when(io.triggerDrain) {
          addrReg := U(resultBase, addrWidth bits)
          goto(issueReadState)
        }
      }
    }

    // issueRead: drive readCmd.valid until the SPRAM accepts it. In
    // the default top-level wiring the SPRAM is always ready so this
    // is a one-cycle pass-through; the loop guards against a future
    // arbiter that might back-pressure us.
    val issueReadState: State = new State {
      whenIsActive {
        io.readCmd.valid := True
        when(io.readCmd.ready) {
          goto(waitRespState)
        }
      }
    }

    // waitResp: readResp.valid is guaranteed by the SPRAM contract to
    // arrive on the very next cycle after readCmd.fire, so we'll
    // always catch it on the first cycle we're active. The `when` is
    // defence-in-depth in case the SPRAM contract ever loosens.
    val waitRespState: State = new State {
      whenIsActive {
        when(io.readResp.valid) {
          wordReg := io.readResp.payload
          goto(sendLoState)
        }
      }
    }

    // sendLo: present the low byte of wordReg. Hold valid until the
    // UART takes it (txData.ready high). MUST NOT advance state on
    // valid && !ready -- the UART is busy serialising the previous
    // byte and our byte would be silently dropped.
    val sendLoState: State = new State {
      whenIsActive {
        io.txData.valid := True
        io.txData.payload := wordReg(7 downto 0)
        when(io.txData.ready) {
          goto(sendHiState)
        }
      }
    }

    // sendHi: present the high byte of wordReg. Same handshake
    // discipline as sendLo. On the final-word fire, pulse
    // drainComplete and return to idle in one edge -- the phase FSM
    // sees completion on the same cycle as the byte hitting the UART.
    // On non-final fire, increment addrReg and go back to issueRead.
    val sendHiState: State = new State {
      whenIsActive {
        io.txData.valid := True
        io.txData.payload := wordReg(15 downto 8)
        when(io.txData.ready) {
          when(addrReg === resultLimit) {
            io.drainComplete := True
            goto(idleState)
          } otherwise {
            addrReg := addrReg + 1
            goto(issueReadState)
          }
        }
      }
    }

    // Status output: anything that is not idle counts as active.
    io.active := !isActive(idleState)
  }
}
