package mole

import spinal.core._
import spinal.lib._

/** Write command into the [[SpramController]].
  *
  * Bundled into a Stream so each producer (UART loader, engine result-ring
  * write port) can offer one write per cycle and be back-pressured by the
  * controller's arbitration when it loses.
  *
  * @param addrWidth
  *   Width of the address field, in bits.
  * @param dataWidth
  *   Width of the data field, in bits. Default 32 (v0.2 grain). The UART loader
  *   still assembles 16-bit words internally and passes `dataWidth = 16`; the
  *   top-level packing adapter (in `MoleTop`, Phase C.6) widens them to 32
  *   before connecting to the SPRAM controller.
  */
case class SpramWriteCmd(addrWidth: Int, dataWidth: Int = 32) extends Bundle {
  val addr = UInt(addrWidth bits)
  val data = Bits(dataWidth bits)
}

/** Dual-tile SPRAM controller wrapping two iCE40 UP5K `SB_SPRAM256KA` tiles in
  * parallel to deliver a 32-bit-wide, word-addressed, stream-shaped store.
  *
  * ==Tile layout==
  *
  * Each `SB_SPRAM256KA` is 16K × 16-bit. Two tiles share the address bus and
  * operate in lockstep:
  *
  *   - `tileLo` contributes bits `[15:0]`.
  *   - `tileHi` contributes bits `[31:16]`.
  *
  * One 14-bit address now indexes one 32-bit word (= 64 KB total raw capacity
  * from the tile pair), up from 16-bit (= 32 KB) in v0. The UP5K has four tiles
  * total; this controller consumes two, leaving two for future use (result-ring
  * expansion, a second program bank, ...).
  *
  * ==Memory model the engine sees==
  *
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
  * ==Arbitration==
  *
  * Priority 1: `readCmd` — engine fetch path; critical. Priority 2:
  * `resultWrite` — engine result-ring producer. Priority 3: `loaderWrite` —
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
  *   When `true` (default; the synthesis path), instantiate two `SB_SPRAM256KA`
  *   primitives in parallel. When `false` (the sim path), back the memory with
  *   a plain Spinal `Mem` whose semantics match the primitive pair's
  *   bit-for-bit at the address-mapping wrapper level. Sim tests pass `false`
  *   so they can run without an external Verilog model of the iCE40 primitive.
  */
case class SpramController(cfg: MoleConfig, useBlackBox: Boolean = true)
    extends Component {

  // Word counts. The result ring is byte-sized in `MoleConfig` (the
  // user-visible unit; matches the engine's byte-grained result stream
  // into the UART). v0.2 grain is 32-bit (4 bytes per word): round up
  // to a 32-bit word count.
  val resultWordCount: Int = (cfg.resultRingByteCount + 3) / 4
  val totalWords: Int = cfg.programWordCount + resultWordCount

  require(
    totalWords >= 1,
    s"SPRAM total word count must be >= 1, got $totalWords"
  )
  // v0.2 uses two SB_SPRAM256KA tiles in parallel (tileLo + tileHi),
  // yielding 16K × 32-bit = 64 KB of addressable space. Each tile is
  // 16K × 16-bit; sharing the address bus makes a 16K × 32-bit pair.
  // The UP5K has four tiles total (128 KB raw); this controller
  // consumes two, leaving two for future expansion.
  require(
    totalWords <= 16384,
    s"SPRAM total ($totalWords words) exceeds one tile-pair (16384 × 32-bit words). " +
      "Bump programWordCount or resultRingByteCount down, or add a second tile-pair."
  )

  val addrWidth: Int = log2Up(totalWords)

  val io = new Bundle {

    /** Boot-time loader-write port. Active only while the engine is stopped;
      * the SDK programs the engine's instruction store through this port before
      * issuing `START`.
      */
    val loaderWrite = slave Stream SpramWriteCmd(addrWidth)

    /** Run-time result-ring write port. The engine fans 32-bit records into the
      * ring here at up to one word per fabric cycle.
      */
    val resultWrite = slave Stream SpramWriteCmd(addrWidth)

    /** Engine fetch read command. */
    val readCmd = slave Stream (UInt(addrWidth bits))

    /** Engine fetch response. `Flow`, not `Stream` — the SPRAM primitive
      * returns data one cycle after the address is presented and there is no
      * way to stall the read once it is issued. `Flow.valid` is true exactly
      * one cycle after the cycle on which the corresponding `readCmd.fire`d.
      */
    val readResp = master Flow (Bits(32 bits))
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
  val wrData = Bits(32 bits)
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
    // Hardware path: two SB_SPRAM256KA tiles in parallel.
    //
    //   tileLo — bits [15: 0] of the 32-bit word.
    //   tileHi — bits [31:16] of the 32-bit word.
    //
    // Both tiles share ADDRESS, MASKWREN, CHIPSELECT, WREN, STANDBY,
    // SLEEP, and POWEROFF. DATAIN is split; DATAOUT is concatenated.
    //
    // POWEROFF is ACTIVE LOW — must be tied HIGH for the tiles to be
    // operational. Common gotcha (every iCE40 SPRAM bring-up story
    // mentions it). STANDBY and SLEEP are active-high and stay
    // deasserted for v0.2.
    //
    // MASKWREN is 4 bits, one per nibble of the 16-bit tile data bus.
    // All-ones = whole-word write (no sub-word masking needed in Mole).
    val tileLo = new SB_SPRAM256KA
    val tileHi = new SB_SPRAM256KA

    // ---- shared control signals ----
    for (tile <- Seq(tileLo, tileHi)) {
      tile.io.ADDRESS := addr.resize(14 bits)
      tile.io.MASKWREN := B"1111"
      tile.io.WREN := doWrite
      tile.io.CHIPSELECT := True
      tile.io.STANDBY := False
      tile.io.SLEEP := False
      tile.io.POWEROFF := True
    }

    // ---- data split / merge ----
    tileLo.io.DATAIN := wrData(15 downto 0)
    tileHi.io.DATAIN := wrData(31 downto 16)

    io.readResp.payload := tileHi.io.DATAOUT ## tileLo.io.DATAOUT
  } else {
    // Sim path: plain Mem modelling the 32-bit tile-pair as a single
    // 32-bit-wide memory. Same single-port semantics (read-or-write per
    // cycle), same one-cycle synchronous read latency. The
    // wrapper-level arbitration (address mux, ready back-pressure) is
    // exercised end-to-end by the sim despite the primitive being
    // substituted. The BlackBox tile-pair wiring (tileLo/tileHi data
    // split and DATAOUT concatenation) is validated separately by the
    // sim's dual-tile parallelism and alternating-bit-pattern cases.
    val mem = Mem(Bits(32 bits), 1 << addrWidth)
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
  *   - `CHIPSELECT` active high; tie HIGH for v0.2.
  *   - `STANDBY` active high; tie LOW (always-on).
  *   - `SLEEP` active high; tie LOW (always-on).
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
