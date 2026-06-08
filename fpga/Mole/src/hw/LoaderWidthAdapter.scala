package mole

import spinal.core._
import spinal.lib._

/** 16-bit to 32-bit width adapter: packs two consecutive 16-bit loader writes
  * into one 32-bit SPRAM write.
  *
  * The v0 [[MoleLoaderFsm]] emits 16-bit writes (one per instruction word,
  * matching the v0 16-bit ISA). The v0.2 [[SpramController]] accepts 32-bit
  * writes. This adapter sits between them, pairing every two 16-bit writes into
  * one 32-bit write.
  *
  * ==Pairing contract==
  *
  * The loader emits instruction words at sequential addresses starting at 0. In
  * v0.2 each 32-bit instruction occupies one 32-bit SPRAM word. The v0 loader
  * emits address 0, 1, 2, … in order. The adapter maps pairs (lo=addr0,
  * hi=addr1) → (SPRAM word 0, data = hi##lo), (lo=addr2, hi=addr3) → (SPRAM
  * word 1, …). In other words, the v0 loader's 16-bit word at index N becomes
  * the low 16 bits of SPRAM word N/2, and the word at index N+1 becomes the
  * high 16 bits of SPRAM word N/2.
  *
  * This is the natural little-endian packing: the 32-bit instruction arrives
  * over the UART with the low half first (little-endian wire format).
  *
  * ==Address mapping==
  *
  * v0 loader address A → SPRAM address A/2. The v0 loader counts 16-bit words;
  * the v0.2 SPRAM counts 32-bit words. The adapter ignores the incoming address
  * and maintains its own SPRAM word counter.
  *
  * ==Back-pressure==
  *
  * The adapter propagates SPRAM back-pressure upstream to the loader: while
  * waiting for the SPRAM to accept a 32-bit write, the adapter holds the
  * loader's `programWrite.ready` low.
  *
  * ==Reset==
  *
  * State is held in two registers (`loReg` and `hasLo`); both reset to zero /
  * false at power-on. If the loader aborts mid-pair (fault + resync), the
  * adapter's half-word state becomes stale; the next load starts from the
  * loader's first word, which arrives as a fresh "lo" half and correctly resets
  * `hasLo`.
  *
  * @param addrWidthOut
  *   Address width of the output SPRAM write port (must cover the full SPRAM
  *   address space including result ring).
  */
case class LoaderWidthAdapter(addrWidthOut: Int) extends Component {

  val io = new Bundle {

    /** 16-bit word stream from [[MoleLoaderFsm]]. */
    val loaderIn = slave Stream SpramWriteCmd(addrWidthOut, dataWidth = 16)

    /** 32-bit word stream to [[SpramController]]. */
    val spramOut = master Stream SpramWriteCmd(addrWidthOut, dataWidth = 32)

    /** Word count of the loaded program in 32-bit words. Useful for MoleTop to
      * feed `EnginePipeline.io.programLength`.
      */
    val programLength32 =
      out UInt (log2Up(Instruction.MAX_PROGRAM_WORDS + 1) bits)
  }

  // --------------------------------------------------------------------------
  // State registers
  // --------------------------------------------------------------------------

  /** True when `loReg` holds a valid low half waiting for the high half. */
  val hasLo = Reg(Bool()) init False

  /** The low 16-bit half of the pending 32-bit word. */
  val loReg = Reg(Bits(16 bits)) init B(0, 16 bits)

  /** Output address counter (32-bit SPRAM words). */
  val wordIdx = Reg(
    UInt(log2Up(Instruction.MAX_PROGRAM_WORDS + 1) bits)
  ) init 0

  /** Track the highest committed 32-bit word index (+1) as the program length.
    * Updated each time a 32-bit write fires.
    */
  val programLen = Reg(
    UInt(log2Up(Instruction.MAX_PROGRAM_WORDS + 1) bits)
  ) init 0

  io.programLength32 := programLen

  // --------------------------------------------------------------------------
  // Defaults
  // --------------------------------------------------------------------------
  io.spramOut.valid := False
  io.spramOut.payload.addr := wordIdx.resize(addrWidthOut bits)
  io.spramOut.payload.data := B(0, 32 bits)
  io.loaderIn.ready := False

  // --------------------------------------------------------------------------
  // Pairing FSM (implicit: no explicit state register; hasLo encodes state)
  // --------------------------------------------------------------------------

  when(!hasLo) {
    // Waiting for the low half: consume the next loader word.
    io.loaderIn.ready := True
    when(io.loaderIn.valid) {
      loReg := io.loaderIn.payload.data
      hasLo := True
    }
  } otherwise {
    // Have low half: consume the high half and push a 32-bit write.
    // Hold loaderIn.ready low while the SPRAM may be back-pressured.
    io.spramOut.valid := io.loaderIn.valid
    io.spramOut.payload.addr := wordIdx.resize(addrWidthOut bits)
    io.spramOut.payload.data := io.loaderIn.payload.data ## loReg

    when(io.loaderIn.valid && io.spramOut.ready) {
      // Both the loaderIn and spramOut handshakes fire simultaneously.
      io.loaderIn.ready := True
      hasLo := False
      wordIdx := wordIdx + 1
      programLen := wordIdx + 1
    }
  }
}
