package mole

import spinal.core._
import spinal.lib._

/** Write command into the [[SpramController]].
  *
  * Bundled into a Stream so each producer (UART loader, engine result-ring
  * write port) can offer one write per cycle and be back-pressured by the
  * controller's arbitration when it loses.
  */
case class SpramWriteCmd(addrWidth: Int) extends Bundle {
  val addr = UInt(addrWidth bits)
  val data = Bits(16 bits)
}

/** Single-port SPRAM controller wrapping (one to four) iCE40 UP5K
  * `SB_SPRAM256KA` tiles into one logical, word-addressed, stream-shaped store.
  *
  * Memory model the engine sees:
  *   - one read port (`readCmd` → `readResp`), word-grained, with fixed
  *     one-cycle synchronous read latency. Matches `SB_SPRAM256KA` semantics:
  *     address registered on cycle N, data valid on cycle N+1.
  *   - two write ports (`loaderWrite`, `resultWrite`), both word-grained, both
  *     back-pressured by the arbiter.
  *   - one flat address space: program words at low addresses, result-ring
  *     words above (the *split* is enforced by the engine and the host SDK —
  *     `SpramController` itself is unaware).
  *
  * Why two named write ports rather than one shared `write` port: the engine
  * result-ring producer and the UART loader have different lifetimes (loader is
  * only active at boot, before the engine is started; result-ring is active
  * during every run), and different priorities. Keeping them as distinct ports
  * preserves producer identity in code review, sim waveforms, and back-pressure
  * analysis. Read-time the engine never overlaps with loader-time, so the
  * arbiter never *actually* picks between `loaderWrite` and `resultWrite` in
  * production — that case only shows up in the sim's stress test.
  *
  * Arbitration: priority 1: `readCmd` — engine fetch path; critical. priority
  * 2: `resultWrite` — engine result-ring producer. priority 3: `loaderWrite` —
  * boot-time UART loader.
  *
  * Read wins over both writes; if both writes want the same cycle,
  * `resultWrite` wins. The losing write back-pressures via Stream `ready` and
  * retries next cycle. Read-vs-write to the *same* address in the same cycle is
  * structurally impossible under this arbiter: read wins, write loses
  * arbitration, write is offered the bus next cycle. The `SB_SPRAM256KA` "what
  * happens on simultaneous read+write to the same address" question therefore
  * never arises in Mole.
  *
  * @param cfg
  *   Mole configuration record; consulted for `programWordCount` and
  *   `resultRingByteCount` to size the logical address space.
  * @param useBlackBox
  *   When `true` (default; the synthesis path), instantiate an `SB_SPRAM256KA`
  *   primitive. When `false` (the sim path), back the memory with a plain
  *   Spinal `Mem` whose semantics match the primitive's bit-for-bit at the
  *   address-mapping wrapper level. Sim tests pass `false` so they can run
  *   without an external Verilog model of the iCE40 primitive.
  */
case class SpramController(cfg: MoleConfig, useBlackBox: Boolean = true)
    extends Component {

  // Word counts. The result ring is byte-sized in `MoleConfig` (the
  // user-visible unit; matches the engine's byte-grained result
  // stream into the UART). Round up to a word count for SPRAM
  // sizing.
  val resultWordCount: Int = (cfg.resultRingByteCount + 1) / 2
  val totalWords: Int = cfg.programWordCount + resultWordCount

  require(
    totalWords >= 1,
    s"SPRAM total word count must be >= 1, got $totalWords"
  )
  // Phase 0 simplifies to a single 16k-word tile. The UP5K has
  // four; the multi-tile arbiter lands in a Step 8+ follow-up
  // when the engine actually needs more memory. With the v0
  // defaults (2048 program + 2048 result words = 4096) we use a
  // quarter of one tile; users can push the result ring up to
  // ~28 KiB before hitting this cap.
  require(
    totalWords <= 16384,
    s"SPRAM total ($totalWords words) exceeds one tile (16384 words). Multi-tile support is Step 8+; bump programWordCount or resultRingByteCount down for now."
  )

  val addrWidth: Int = log2Up(totalWords)

  val io = new Bundle {

    /** Boot-time loader-write port. Active only while the engine is stopped;
      * the SDK programs the engine's instruction store through this port before
      * issuing `START`.
      */
    val loaderWrite = slave Stream SpramWriteCmd(addrWidth)

    /** Run-time result-ring write port. The engine fans bytes into the ring
      * here at up to one 16-bit word per fabric cycle.
      */
    val resultWrite = slave Stream SpramWriteCmd(addrWidth)

    /** Engine fetch read command. */
    val readCmd = slave Stream (UInt(addrWidth bits))

    /** Engine fetch response. `Flow`, not `Stream` — the SPRAM primitive
      * returns data one cycle after the address is presented and there is no
      * way to stall the read once it is issued. `Flow.valid` is true exactly
      * one cycle after the cycle on which the corresponding `readCmd.fire`d.
      */
    val readResp = master Flow (Bits(16 bits))
  }

  // Arbitration:
  //   - readCmd can always fire (the engine's critical path).
  //   - resultWrite fires when readCmd is not firing.
  //   - loaderWrite fires only when neither read nor result-write
  //     is firing.
  io.readCmd.ready := True
  io.resultWrite.ready := !io.readCmd.valid
  io.loaderWrite.ready := !io.readCmd.valid && !io.resultWrite.valid

  val doRead = io.readCmd.fire
  val doResultWrite = io.resultWrite.fire
  val doLoaderWrite = io.loaderWrite.fire
  val doWrite = doResultWrite || doLoaderWrite

  // Address selection. Read wins; among writes, result wins.
  val addr = UInt(addrWidth bits)
  val wrData = Bits(16 bits)
  when(doRead) {
    addr := io.readCmd.payload
  } elsewhen (doResultWrite) {
    addr := io.resultWrite.payload.addr
  } otherwise {
    addr := io.loaderWrite.payload.addr
  }
  when(doResultWrite) {
    wrData := io.resultWrite.payload.data
  } otherwise {
    wrData := io.loaderWrite.payload.data
  }

  if (useBlackBox) {
    // Hardware path: one SB_SPRAM256KA tile. POWEROFF on the
    // primitive is ACTIVE LOW — must be tied HIGH for the tile to
    // be operational. Common gotcha (every iCE40 SPRAM bring-up
    // story I have read mentions it). STANDBY and SLEEP are
    // active-high and stay deasserted for v0.
    val tile = new SB_SPRAM256KA
    tile.io.DATAIN := wrData
    tile.io.ADDRESS := addr.resize(14 bits)
    tile.io.MASKWREN := B"1111" // whole-word writes; Mole's grain is the 16-bit instruction
    tile.io.WREN := doWrite
    tile.io.CHIPSELECT := True
    tile.io.STANDBY := False
    tile.io.SLEEP := False
    tile.io.POWEROFF := True
    io.readResp.payload := tile.io.DATAOUT
  } else {
    // Sim path: plain Mem. Same single-port semantics
    // (read-or-write per cycle), same one-cycle synchronous read
    // latency. The wrapper-level arbitration (address mux, ready
    // back-pressure) is exercised end-to-end by the sim despite
    // the primitive being substituted.
    val mem = Mem(Bits(16 bits), 1 << addrWidth)
    when(doWrite) {
      mem.write(addr, wrData)
    }
    io.readResp.payload := mem.readSync(addr, enable = doRead)
  }

  // Read response valid one cycle after the read fired.
  io.readResp.valid := RegNext(doRead) init (False)
}

/** `SB_SPRAM256KA` primitive — iCE40 UP5K single-port 16k×16-bit SPRAM tile.
  * Each UP5K has four of these.
  *
  * Port list cross-checked against the icestorm `cells_sim.v` reference model.
  * The `mapClockDomain(clock = io.CLOCK)` call threads the implicit clock onto
  * the primitive's clock pin so the tile shares the same clock domain as the
  * surrounding logic.
  *
  * Pin polarity (the gotchas):
  *   - `WREN` active high.
  *   - `MASKWREN` bit-per-nibble mask, active high per nibble.
  *   - `CHIPSELECT` active high; tie HIGH for v0.
  *   - `STANDBY` active high; tie LOW (we always-on).
  *   - `SLEEP` active high; tie LOW (we always-on).
  *   - `POWEROFF` **active LOW** — tie HIGH for the tile to work.
  */
class SB_SPRAM256KA extends BlackBox {
  val io = new Bundle {
    val DATAIN = in Bits (16 bits)
    val ADDRESS = in UInt (14 bits)
    val MASKWREN = in Bits (4 bits)
    val WREN = in Bool ()
    val CHIPSELECT = in Bool ()
    val CLOCK = in Bool ()
    val STANDBY = in Bool ()
    val SLEEP = in Bool ()
    val POWEROFF = in Bool ()
    val DATAOUT = out Bits (16 bits)
  }
  noIoPrefix()
  mapClockDomain(clock = io.CLOCK)
}
