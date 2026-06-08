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

/// Full 32-bit preamble word 0 value, little-endian on wire as bytes
/// `4C 4D 02 00`.
///
/// The low 16 bits (`0x4D4C`) are the format magic (ASCII "ML"
/// little-endian); the high 16 bits (`0x0002`) are the format
/// version. See §10 lines 1419-1424.
pub const MAGIC: u32 = 0x0002_4D4C;

/// Low 16-bit half of [`MAGIC`]: the format magic `0x4D4C`.
///
/// On the wire (little-endian) this appears as bytes `4C 4D`, which
/// reads as ASCII "LM" treated as a byte string. The spec calls it
/// the "ML magic" because the u16 value, read MSB-first, spells
/// "ML". The loader matches on this u16, not on the full u32.
/// See §10 lines 1421-1422.
pub const MAGIC_LO_U16: u16 = 0x4D4C;

/// High 16-bit half of [`MAGIC`]: the format version `0x0002`.
///
/// Identifies this v0.2 specification. The v0 format had no
/// preamble; v0.2 is not backwards-compatible. See §10.
pub const FORMAT_VERSION: u16 = 0x0002;

/// Number of 32-bit preamble words prepended to every program frame.
///
/// Word 0: magic + version ([`MAGIC`]).
/// Word 1: body length in 32-bit instruction words.
///
/// See §10.
pub const PREAMBLE_WORDS: usize = 2;

/// Maximum program body length in 32-bit words (preamble excluded).
///
/// At 32-bit instructions this is 8192 × 4 = 32768 bytes, which fits
/// in UP5K SPRAM (4 × 16-KiB blocks = 64 KiB total). The loader
/// rejects frames whose length field exceeds this limit before
/// writing any data to SPRAM. See §10 line 1431-1432.
pub const MAX_PROGRAM_WORDS: usize = 8192;

/// Default result-ring size in bytes.
/// Mirrors `MoleConfig.resultRingByteCount` (Verde default).
pub const RESULT_RING_BYTE_COUNT: usize = 8192;

/// Default UART baud rate.
///
/// 2 Mbaud at 8× oversampling. When 2 Mbaud is not achievable (e.g.
/// crystal mismatch on a particular host adapter), fall back to
/// [`FALLBACK_BAUD`] at 16× oversampling.
pub const DEFAULT_BAUD: u32 = 2_000_000;

/// Fallback UART baud rate when [`DEFAULT_BAUD`] is not viable.
///
/// 1 Mbaud at 16× oversampling. Use when the host adapter cannot
/// reliably sustain 2 Mbaud.
pub const FALLBACK_BAUD: u32 = 1_000_000;

/// Bit positions in a HALT word (32-bit, §11 lines 1458-1492):
///
/// ```text
/// [31:30] tag        = 0b11  (HALT record)
/// [29]    overflow   (sticky overflow latch)
/// [28]    mismatch   (snapshot of MISMATCH_FLAG at halt entry)
/// [27:23] status     (5 bits, 0x00..0x1F)
/// [22: 0] reserved   (must be 0; loader verifies)
/// ```
pub mod halt {
    /// Shift for the 2-bit record tag at `[31:30]`.
    pub const TAG_SHIFT: u32 = 30;
    /// Mask (after shifting) for the 2-bit tag.
    pub const TAG_MASK: u32 = 0b11;
    /// Tag value identifying a HALT word.
    pub const TAG_HALT: u32 = 0b11;

    /// Bit position of the overflow latch in the HALT word.
    pub const OVERFLOW_BIT: u32 = 29;
    /// Bit position of the sticky `MISMATCH_FLAG` snapshot at
    /// HALT entry.
    pub const MISMATCH_BIT: u32 = 28;

    /// Shift for the 5-bit status field at `[27:23]`.
    pub const STATUS_SHIFT: u32 = 23;
    /// Mask (after shifting) for the 5-bit status field.
    pub const STATUS_MASK: u32 = 0x1F;

    /// Mask covering the reserved low 23 bits `[22:0]`, which must
    /// be zero per INV-WIRE-HALT-RECORD.
    pub const RESERVED_BITS_MASK: u32 = 0x007F_FFFF;

    /// Status codes (5-bit, §11):
    /// - `0x00..=0x1C`: caller-defined user halts.
    /// - `0x1D..=0x1E`: reserved for future engine traps (loader
    ///   rejects these --- see F-HOST-001).
    /// - `0x1F`: in-use engine trap status.
    pub const STATUS_USER_MAX: u8 = 0x1C;
    /// Lower bound of the reserved trap range.
    pub const STATUS_RESERVED_LOW: u8 = 0x1D;
    /// Upper bound of the reserved trap range.
    pub const STATUS_RESERVED_HIGH: u8 = 0x1E;
    /// In-use engine-trap status code (malformed instruction).
    pub const STATUS_TRAP: u8 = 0x1F;
}

/// Bit positions in a record word's `[31:30]` tag:
///
/// ```text
/// 0b00 = CAPTURE  (1 word)
/// 0b01 = reserved
/// 0b10 = MARK     (3 words: header + ts_lo + ts_hi)
/// 0b11 = HALT     (1 word, at resultLimit)
/// ```
///
/// Note: result-ring word widths are 16 bits (the engine's result
/// ring is 16-bit-addressed), independent of the 32-bit program-
/// memory instruction width. Constants in [`record_width_words`]
/// count in 16-bit words accordingly. See `BitCycleEngineCore.scala`
/// (ring write paths) as the authoritative source.
pub mod record_tag {
    /// Shift for the 2-bit record tag at `[31:30]`.
    pub const SHIFT: u32 = 30;
    /// Mask (after shifting) for the 2-bit tag.
    pub const MASK: u32 = 0b11;

    /// CAPTURE tag (single-word record carrying an SDA sample).
    pub const CAPTURE: u32 = 0b00;
    /// Reserved tag; never emitted by a current engine.
    pub const RESERVED: u32 = 0b01;
    /// MARK tag (three-word record: label + timestamp lo/hi).
    pub const MARK: u32 = 0b10;
    /// HALT tag (terminator at `resultLimit`).
    pub const HALT: u32 = 0b11;
}

/// Width in 16-bit words of each variable-size record.
///
/// Result-ring words are 16 bits wide (the engine's result ring is
/// 16-bit-addressed), independent of the 32-bit program-memory
/// instruction width. These constants count in 16-bit units.
/// See `BitCycleEngineCore.scala` (ring write paths).
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
        // const blocks evaluate at compile time; if the ordering ever
        // breaks, the test fails to build rather than just at runtime.
        // Also satisfies clippy::assertions_on_constants under
        // `--all-targets`.
        const _: () = assert!(halt::STATUS_USER_MAX < halt::STATUS_RESERVED_LOW);
        const _: () = assert!(halt::STATUS_RESERVED_LOW < halt::STATUS_RESERVED_HIGH);
        const _: () = assert!(halt::STATUS_RESERVED_HIGH < halt::STATUS_TRAP);
    }

    #[test]
    fn magic_word_bytes_on_wire_are_4c_4d_02_00() {
        assert_eq!(MAGIC.to_le_bytes(), [0x4C, 0x4D, 0x02, 0x00]);
    }

    #[test]
    fn magic_lo_u16_extracts_ml_ascii() {
        // The low u16 of MAGIC equals MAGIC_LO_U16.
        assert_eq!(MAGIC as u16, MAGIC_LO_U16);
        // On the wire (little-endian) the magic bytes are 4C 4D.
        assert_eq!(MAGIC_LO_U16.to_le_bytes(), [0x4C, 0x4D]);
    }

    #[test]
    fn format_version_extracts_from_magic() {
        assert_eq!((MAGIC >> 16) as u16, FORMAT_VERSION);
    }

    #[test]
    fn max_program_words_fits_up5k_spram() {
        // 8192 × 4 bytes = 32768 bytes total program memory.
        assert_eq!(MAX_PROGRAM_WORDS * 4, 32768);
        // UP5K SPRAM is 4 × 16-KiB = 65536 bytes; program fits.
        assert!(32768 <= 65536);
    }
}
