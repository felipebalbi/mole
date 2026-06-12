//! Shared wire-protocol constants for the Mole bytecode engine.
//!
//! These values are mirrored from the canonical engine source at
//! `fpga/Mole/src/hw/`:
//!
//! - `RESULT_RING_BYTE_COUNT` --- `MoleConfig.scala`
//! - `MAX_PROGRAM_WORDS` --- engine SPRAM budget; encoder enforces
//!   via `frame.rs` length cap.
//! - Record tags + HALT bit layout --- `EnginePipeline.scala`
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
/// Result-ring words are **32 bits** wide --- the SPRAM grain matches
/// the v0.2 program-memory instruction width. The drainer streams
/// each 32-bit word as 4 LE bytes (b0 first). Per-record widths in
/// [`record_width_words`] count in 32-bit units. See `MOLE-0.2-SPEC.md`
/// §11 and `fpga/Mole/src/hw/EnginePipeline.scala` ring-write paths.
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

/// Width in **32-bit** words of each variable-size record.
///
/// Result-ring slots are 32-bit-grained (one SPRAM word per slot,
/// drained as 4 LE bytes). See `MOLE-0.2-SPEC.md` §11 and
/// `fpga/Mole/src/hw/EnginePipeline.scala` (ring-write paths).
pub mod record_width_words {
    /// CAPTURE is a single 32-bit word: `[31:30] tag=0b00`,
    /// `[29:1] reserved=0`, `[0] sda`. Spec §11.3.
    pub const CAPTURE: usize = 1;
    /// MARK is three 32-bit words: header + timestamp-lo + timestamp-hi.
    /// Spec §11.4.
    pub const MARK: usize = 3;
    /// HALT is a single 32-bit word at the ring's last slot. Spec §11.5.
    pub const HALT: usize = 1;
}

/// CAPTURE record bit layout (spec §11.3, 1 word, tag `0b00`).
pub mod capture {
    /// Bit position of the captured SDA sample.
    pub const SDA_BIT: u32 = 0;
    /// Mask covering the reserved bits `[29:1]` (must be zero).
    pub const RESERVED_BITS_MASK: u32 = 0x3FFF_FFFE;
}

/// MARK record bit layout (spec §11.4, 3 words, tag `0b10`).
pub mod mark {
    /// Shift for the 14-bit label in the MARK header word at `[13:0]`.
    pub const LABEL_SHIFT: u32 = 0;
    /// Mask (after shifting) for the 14-bit label.
    pub const LABEL_MASK: u32 = 0x3FFF;
    /// Mask covering the reserved bits `[29:14]` of the header (must
    /// be zero).
    pub const HEADER_RESERVED_BITS_MASK: u32 = 0x3FFF_C000;
}

/// Result-ring layout offsets (spec §11.1) in **32-bit words**.
pub mod result_ring {
    /// Offset of the REVISION word at the head of every ring.
    pub const REVISION_OFFSET_WORDS: usize = 0;
    /// Offset of the first record-stream slot (just past REVISION).
    pub const RECORD_STREAM_OFFSET_WORDS: usize = 1;
    /// Words reserved by REVISION (1 word) + the HALT terminator slot
    /// (1 word) that bound the record stream.
    pub const OVERHEAD_WORDS: usize = 2;
}

/// REVISION word packing (spec §11.2):
///
/// ```text
/// [31:24] major
/// [23:16] minor
/// [15: 0] patch
/// ```
///
/// The REVISION word occupies `word[0]` of the result ring (the first
/// 4 bytes of the drained byte stream), little-endian per word.
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
        // assert!(32768 <= 65536);
    }

    #[test]
    fn record_widths_are_in_32_bit_words() {
        // Spec §11.3: CAPTURE is 1 32-bit word.
        assert_eq!(record_width_words::CAPTURE, 1);
        // Spec §11.4: MARK is 3 32-bit words (header + ts_lo + ts_hi).
        assert_eq!(record_width_words::MARK, 3);
        // Spec §11.5: HALT is 1 32-bit word at the ring's last slot.
        assert_eq!(record_width_words::HALT, 1);
    }

    #[test]
    fn capture_reserved_mask_covers_bits_29_to_1() {
        // [29:1] reserved; bit [0] sda; bits [31:30] tag=00.
        assert_eq!(capture::RESERVED_BITS_MASK, 0x3FFF_FFFE);
        // A valid CAPTURE word with sda=1 has no reserved bits set.
        let w: u32 = 1 << capture::SDA_BIT;
        assert_eq!(w & capture::RESERVED_BITS_MASK, 0);
        // A garbage CAPTURE-tagged word with junk in [29:1] is detectable.
        let g: u32 = 0x1234_5678;
        assert_ne!(g & capture::RESERVED_BITS_MASK, 0);
    }

    #[test]
    fn mark_header_label_and_reserved_layout() {
        // Header: [31:30]=10 tag, [29:14] reserved, [13:0] label.
        let label = 0x12A5_u32;
        let header = (record_tag::MARK << record_tag::SHIFT) | label;
        // Tag round-trips.
        assert_eq!(
            (header >> record_tag::SHIFT) & record_tag::MASK,
            record_tag::MARK
        );
        // Label round-trips (14 bits, 0..0x3FFF).
        assert_eq!((header >> mark::LABEL_SHIFT) & mark::LABEL_MASK, label);
        // Reserved bits [29:14] are zero in this canonical encoding.
        assert_eq!(header & mark::HEADER_RESERVED_BITS_MASK, 0);
    }

    #[test]
    fn result_ring_overhead_words_is_revision_plus_halt() {
        // REVISION (1) + HALT terminator (1) = 2 words of overhead.
        assert_eq!(result_ring::OVERHEAD_WORDS, 2);
        assert_eq!(result_ring::REVISION_OFFSET_WORDS, 0);
        assert_eq!(result_ring::RECORD_STREAM_OFFSET_WORDS, 1);
    }
}
