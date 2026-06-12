//! Layer-1 bytecode compiler for the Mole v0.2 bit-cycle engine.
//!
//! `mole-asm` turns moleasm source (grammar documented in
//! `docs/MOLE-0.2-SPEC.md` §12) into 32-bit-word bytecode the FPGA
//! engine executes.  The returned program vector still includes a
//! 2-word in-memory preamble (magic + body length) for backward
//! compatibility with consumers that handle the preamble themselves;
//! [`assemble_to_frame`] strips that preamble and builds the v0.2
//! UART frame (4-byte MAGIC + 4-byte LEN + body + 2-byte CRC).
//!
//! # Quick start
//!
//! ```
//! use mole_asm::{assemble, assemble_to_frame};
//!
//! // Assemble a minimal program.  The returned vector includes the
//! // 2-word in-memory preamble plus the single HALT instruction.
//! let words = assemble("HALT status=0\n", "<inline>").unwrap();
//! assert_eq!(words.len(), 3); // preamble(2) + body(1)
//! assert_eq!(words[0], 0x0002_4D4C); // magic + version
//! assert_eq!(words[1], 1);            // body length = 1 word
//! assert_eq!(words[2], 0x4000_0000); // HALT status=0
//!
//! // Or wrap the bytecode in the host->mole UART frame in one call.
//! // v0.2 frame = MAGIC(4) + LEN(4) + body(4) + crc(2) = 14 bytes.
//! let frame = assemble_to_frame("HALT status=0\n", "<inline>").unwrap();
//! assert_eq!(frame.len(), 14);
//! ```
//!
//! # API stability
//!
//! Pre-Phase-0 the wire format is not stabilised (see `AGENTS.md`
//! §3.17 and `docs/MOLE-0.2-SPEC.md` §1).  Once Phase 0 ships, the
//! bytecode produced by [`assemble`] becomes a stable contract between
//! the host compiler and every deployed Mole.

#![deny(missing_docs)]
#![warn(clippy::all)]

mod assembler;
mod encoder;
mod error;
pub mod frame;
mod symbols;

pub use error::{AsmError, Kind, Result, SourceLocation};

/// Re-export of `mole_abi::PREAMBLE_WORDS` so downstream callers
/// (mole-asm-cli) can slice `assemble()`'s output without depending
/// on `mole-abi` directly.
pub use mole_abi::PREAMBLE_WORDS;

/// Assemble moleasm source text into a vector of 32-bit bytecode words.
///
/// The returned vector includes the 2-word preamble (word 0 = magic
/// `0x0002_4D4C`, word 1 = body length in 32-bit words) followed by
/// the encoded instruction stream.
///
/// `filename` is used only for diagnostics — it never opens a file.
/// Pass a real path when assembling from disk, or a sentinel like
/// `"<inline>"` / `"<stdin>"` for in-memory sources.
///
/// # Errors
///
/// Returns [`AsmError::Syntax`] for any lexer / symbol / range /
/// operand problem the input contains. The error carries a
/// [`SourceLocation`] so callers can render diagnostics in their own
/// preferred format.
pub fn assemble(source: &str, filename: &str) -> Result<Vec<u32>> {
    assembler::assemble(source, filename)
}

/// Assemble moleasm source and wrap the resulting bytecode in the
/// host-to-Mole UART frame format.
///
/// The frame layout is:
/// ```text
/// len_lo, len_hi       u16 LE: total word count (preamble + body)
/// word_0 .. word_N     32-bit words, each LE (preamble first)
/// crc_lo, crc_hi       CRC-16/XMODEM over all preceding bytes
/// ```
///
/// # Errors
///
/// Returns the same [`AsmError`] variants as [`assemble`], plus
/// [`AsmError::FrameTooLarge`] if the body length is 0 or exceeds
/// `MAX_PROGRAM_WORDS` (= 8192).
pub fn assemble_to_frame(source: &str, filename: &str) -> Result<Vec<u8>> {
    let words = assemble(source, filename)?;
    // `assemble` returns `[MAGIC, body_len, body[0], ..., body[N-1]]`.
    // The wire frame's MAGIC + LEN preamble is built fresh by
    // `build_frame` from the body alone, so slice off the first 2
    // words before handing the body across.
    let body = &words[mole_abi::PREAMBLE_WORDS..];
    frame::build_frame(body)
}

/// CRC-16/XMODEM helpers. Re-exported so downstream tooling can
/// verify frames without depending on the framing module directly.
pub mod crc {
    /// Compute the CRC-16/XMODEM of `bytes` (polynomial `0x1021`,
    /// init `0x0000`, no reflection, no final XOR).
    ///
    /// Catalog check value: `crc(b"123456789") == 0x31C3`.
    pub fn xmodem(bytes: &[u8]) -> u16 {
        crate::frame::crc16_xmodem(bytes)
    }
}
