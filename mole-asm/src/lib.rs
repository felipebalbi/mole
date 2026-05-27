//! Layer-1 bytecode compiler for the Mole bit-cycle engine.
//!
//! `mole-asm` turns moleasm source (the locked assembly grammar
//! documented in `ROADMAP.md` § "moleasm conventions") into the
//! 16-bit-word bytecode the FPGA engine executes. It is the Rust port
//! of the reference Python at `tests/fixtures/mole-asm.py`, which
//! remains the canonical wire-format spec.
//!
//! # Quick start
//!
//! ```
//! use mole_asm::{assemble, assemble_to_frame};
//!
//! // Two equivalent ways to get bytecode out:
//! let words = assemble("HALT status=0\n", "<inline>").unwrap();
//! assert_eq!(words, vec![0x0000]);
//!
//! // Or wrap the bytecode in the host->mole UART frame in one call:
//! let frame = assemble_to_frame("HALT status=0\n", "<inline>").unwrap();
//! assert_eq!(frame.len(), 6); // len(2) + 1 word(2) + crc(2)
//! ```
//!
//! # API stability
//!
//! Pre-Phase-0 the wire format is not stabilized (see
//! `AGENTS.md` §3.17). Once Phase 0 ships, the bytecode produced by
//! [`assemble`] becomes a stable contract between the host compiler
//! and every deployed Mole; from that point on, byte-for-byte
//! reproducibility is enforced by the goldens under
//! `mole-asm/tests/fixtures/`.

#![deny(missing_docs)]
#![warn(clippy::all)]

mod assembler;
mod encoder;
mod error;
pub mod frame;
mod symbols;

pub use error::{AsmError, Kind, Result, SourceLocation};

/// Assemble moleasm source text into a vector of 16-bit bytecode words.
///
/// `filename` is used only for diagnostics --- it never opens a file.
/// Pass a real path when assembling from disk, or a sentinel like
/// `"<inline>"` / `"<stdin>"` for in-memory sources.
///
/// # Errors
///
/// Returns [`AsmError::Syntax`] for any lexer / symbol / range /
/// operand problem the input contains. The error carries a
/// [`SourceLocation`] so callers can render diagnostics in their own
/// preferred format.
pub fn assemble(source: &str, filename: &str) -> Result<Vec<u16>> {
    assembler::assemble(source, filename)
}

/// Assemble moleasm source and wrap the resulting bytecode in the
/// host-to-Mole UART frame format documented in
/// `ROADMAP.md` § "Wire format".
///
/// # Errors
///
/// Returns the same [`AsmError`] variants as [`assemble`], plus
/// [`AsmError::FrameTooLarge`] if the program exceeds the 4096-word
/// program-memory budget.
pub fn assemble_to_frame(source: &str, filename: &str) -> Result<Vec<u8>> {
    let words = assemble(source, filename)?;
    frame::build_frame(&words)
}

/// CRC-16/XMODEM helpers used by the framing layer. Re-exported so
/// downstream tooling (host runtime, on-target sanity checks) can
/// verify frames without depending on the framing module directly.
pub mod crc {
    /// Compute the CRC-16/XMODEM of `bytes` (polynomial `0x1021`,
    /// init `0x0000`, no reflection, no final XOR). Catalog check
    /// value: `crc(b"123456789") == 0x31C3`.
    pub fn xmodem(bytes: &[u8]) -> u16 {
        crate::frame::crc16_xmodem(bytes)
    }
}
