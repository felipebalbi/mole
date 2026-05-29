//! Shared wire-protocol constants for the Mole bytecode engine.
//!
//! These values are mirrored from the canonical engine source at
//! `fpga/Mole/src/hw/`:
//!
//! - `RESULT_RING_BYTE_COUNT` --- `MoleConfig.scala`
//! - `MAX_PROGRAM_WORDS` --- engine SPRAM budget; encoder enforces
//!   via `frame.rs` length cap.
//! - Record tags + HALT bit layout --- `BitCycleEngineCore.scala`
//!   (ring write paths).
//! - REVISION packing --- `Revision.scala` (see also the Makefile
//!   vars per AGENTS §"REVISION word convention").
//!
//! A future CI golden test (deferred per AGENTS §3.4) should parse
//! the engine source and assert it agrees with these constants.
//!
//! No internal logic --- pure declarations.

#![no_std]
#![deny(missing_docs)]
#![warn(clippy::all)]

/// Default result-ring size in bytes.
/// Mirrors `MoleConfig.resultRingByteCount` (Verde default).
pub const RESULT_RING_BYTE_COUNT: usize = 8192;

/// Maximum program length in 16-bit words.
/// Mirrors the JMP-operand-derived SPRAM budget; encoder rejects
/// frames with more.
pub const MAX_PROGRAM_WORDS: usize = 2048;

/// Default UART baud rate.
/// Mirrors `MoleConfig.uartBaud` (Verde default).
pub const DEFAULT_BAUD: u32 = 1_000_000;

/// Bit positions in a HALT word:
///
/// ```text
/// [15:14] tag  (must be 0b11 for HALT)
/// [13]    overflow
/// [12]    mismatch-at-halt
/// [11:8]  status (4 bits)
/// [7:0]   reserved (must be 0 per recent F-HOST-001 fix)
/// ```
pub mod halt {
    /// Shift for the 2-bit record tag at `[15:14]`.
    pub const TAG_SHIFT: u32 = 14;
    /// Mask (after shifting) for the 2-bit tag.
    pub const TAG_MASK: u16 = 0b11;
    /// Tag value identifying a HALT word.
    pub const TAG_HALT: u16 = 0b11;

    /// Bit position of the overflow latch in the HALT word.
    pub const OVERFLOW_BIT: u32 = 13;
    /// Bit position of the sticky `MISMATCH_FLAG` snapshot at
    /// HALT entry.
    pub const MISMATCH_BIT: u32 = 12;

    /// Shift for the 4-bit status field at `[11:8]`.
    pub const STATUS_SHIFT: u32 = 8;
    /// Mask (after shifting) for the 4-bit status field.
    pub const STATUS_MASK: u16 = 0xF;

    /// Mask covering the reserved low byte `[7:0]`, which must be
    /// zero per INV-WIRE-HALT-RECORD.
    pub const RESERVED_BITS_MASK: u16 = 0x00FF;

    /// Status codes:
    /// - `0x0..=0xC`: caller-defined user halts.
    /// - `0xD..=0xE`: reserved for future engine traps (loader
    ///   rejects these --- see F-HOST-001).
    /// - `0xF`: in-use engine trap status.
    pub const STATUS_USER_MAX: u8 = 0xC;
    /// Lower bound of the reserved trap range.
    pub const STATUS_RESERVED_LOW: u8 = 0xD;
    /// Upper bound of the reserved trap range.
    pub const STATUS_RESERVED_HIGH: u8 = 0xE;
    /// In-use engine-trap status code (malformed instruction).
    pub const STATUS_TRAP: u8 = 0xF;
}

/// Bit positions in a record word's `[15:14]` tag:
///
/// ```text
/// 0b00 = CAPTURE  (1 word)
/// 0b01 = reserved
/// 0b10 = MARK     (3 words: header + ts_lo + ts_hi)
/// 0b11 = HALT     (1 word, at resultLimit)
/// ```
pub mod record_tag {
    /// Shift for the 2-bit record tag at `[15:14]`.
    pub const SHIFT: u32 = 14;
    /// Mask (after shifting) for the 2-bit tag.
    pub const MASK: u16 = 0b11;

    /// CAPTURE tag (single-word record carrying an SDA sample).
    pub const CAPTURE: u16 = 0b00;
    /// Reserved tag; never emitted by a current engine.
    pub const RESERVED: u16 = 0b01;
    /// MARK tag (three-word record: label + timestamp lo/hi).
    pub const MARK: u16 = 0b10;
    /// HALT tag (terminator at `resultLimit`).
    pub const HALT: u16 = 0b11;
}

/// Width in 16-bit words of each variable-size record.
pub mod record_width_words {
    /// CAPTURE is a single word.
    pub const CAPTURE: usize = 1;
    /// MARK is header + timestamp-lo + timestamp-hi.
    pub const MARK: usize = 3;
    /// HALT is a single word.
    pub const HALT: usize = 1;
}

/// REVISION word packing:
///
/// ```text
/// [31:24] major
/// [23:16] minor
/// [15: 0] patch
/// ```
///
/// On the wire the REVISION word occupies the first 4 bytes of the
/// result ring as two 16-bit half-words, little-endian per half.
/// The lo half carries `patch`; the hi half carries
/// `major << 8 | minor`.
pub mod revision {
    /// Pack a (major, minor, patch) tuple into the 32-bit REVISION
    /// word.
    pub const fn pack(major: u8, minor: u8, patch: u16) -> u32 {
        ((major as u32) << 24) | ((minor as u32) << 16) | (patch as u32)
    }

    /// Unpack a 32-bit REVISION word.
    pub const fn unpack(word: u32) -> (u8, u8, u16) {
        let major = ((word >> 24) & 0xFF) as u8;
        let minor = ((word >> 16) & 0xFF) as u8;
        let patch = (word & 0xFFFF) as u16;
        (major, minor, patch)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn revision_pack_unpack_roundtrip() {
        let (m, n, p) = (1, 2, 0x1234);
        assert_eq!(revision::unpack(revision::pack(m, n, p)), (m, n, p));
    }

    #[test]
    fn revision_packs_into_expected_bit_positions() {
        let word = revision::pack(0xAB, 0xCD, 0xEF01);
        assert_eq!(word, 0xABCD_EF01);
    }

    #[test]
    fn halt_status_range_constants_are_consistent() {
        // const blocks evaluate at compile time; if the
        // ordering ever breaks, the test fails to build
        // rather than just at runtime. Also satisfies
        // clippy::assertions_on_constants under
        // `--all-targets`.
        const _: () = assert!(halt::STATUS_USER_MAX < halt::STATUS_RESERVED_LOW);
        const _: () = assert!(halt::STATUS_RESERVED_LOW < halt::STATUS_RESERVED_HIGH);
        const _: () = assert!(halt::STATUS_RESERVED_HIGH < halt::STATUS_TRAP);
    }
}
