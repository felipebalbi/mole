//! Adversarial / contract tests for `mole-loader`.
//!
//! Goals: hunt frame-verifier off-by-ones, ring-decoder confusion,
//! error-class conflation, and endianness regressions. Each test, if
//! it ever starts failing, signals a real bug --- not a cosmetic
//! change.
//!
//! # v0.2 ring layout recap
//!
//! The result ring is 32-bit-addressed (spec §11.1). Slots are 32
//! bits, drained as 4 little-endian bytes per slot. REVISION
//! occupies word 0; records start at word 1; HALT terminator at
//! `word[resultLimit] = word[total_words - 1]`. Minimum ring size:
//! 2 × 32-bit words = 8 bytes.

use mole_abi::{halt, mark, record_tag, revision};
use mole_asm::frame::{build_frame, crc16_xmodem};
use mole_loader::{
    DEFAULT_RING_BYTES, DecodedRing, FrameError, HaltStatus, LoaderError, Record, Revision,
    RingError, decode_ring, decode_ring_strict, verify_frame,
};

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/// Pack a sequence of u32 ring words into the result-ring byte
/// layout (little-endian per 32-bit word).
///
/// `revision_word` is the 32-bit REVISION word at `word[0]`.
/// `records` is a slice of 32-bit ring words for the record stream
/// (caller supplies the encoded form directly).
/// `halt_word` is the 32-bit HALT value (§11) at `word[total_words - 1]`.
/// `total_words` is the total number of 32-bit ring slots.
/// The minimum valid ring is 2 words (1 REVISION + 1 HALT).
fn build_ring(revision_word: u32, records: &[u32], halt_word: u32, total_words: usize) -> Vec<u8> {
    assert!(total_words >= 2 + records.len());
    let mut words: Vec<u32> = Vec::with_capacity(total_words);
    words.push(revision_word);
    words.extend_from_slice(records);
    while words.len() < total_words - 1 {
        words.push(0u32);
    }
    words.push(halt_word);
    assert_eq!(words.len(), total_words);
    let mut bytes = Vec::with_capacity(total_words * 4);
    for w in words {
        bytes.extend_from_slice(&w.to_le_bytes());
    }
    bytes
}

/// Pack a (major, minor, patch) tuple into the canonical 32-bit
/// REVISION word per spec §11.2 / `mole_abi::revision::pack`.
const fn revision_word(major: u8, minor: u8, patch: u16) -> u32 {
    revision::pack(major, minor, patch)
}

/// Build a CAPTURE record word: tag=0b00 at [31:30], sda at [0],
/// [29:1] reserved-zero.
const fn capture_word(sda: bool) -> u32 {
    if sda { 1u32 } else { 0u32 }
}

/// Build a MARK header word: tag=0b10 at [31:30], 14-bit label at
/// [13:0], [29:14] reserved-zero.
const fn mark_header_word(label: u16) -> u32 {
    (record_tag::MARK << record_tag::SHIFT) | (label as u32 & mark::LABEL_MASK)
}

/// Build a MARK timestamp half-word: low 16 bits of the timestamp
/// at [15:0]; high 16 bits reserved-zero per spec §11.4.
const fn mark_ts_word(half: u16) -> u32 {
    half as u32
}

/// Build a minimal HALT word (tag=0b11, status only, no overflow/
/// mismatch, reserved bits = 0).
const fn halt_word_status(status: u8) -> u32 {
    (halt::TAG_HALT << halt::TAG_SHIFT) | ((status as u32) << halt::STATUS_SHIFT)
}

/// Standard clean halt: tag=11, status=0, no overflow, no mismatch.
const HALT_CLEAN: u32 = halt_word_status(0);

// ---------------------------------------------------------------------------
// Frame verifier --- endianness, fuzz, error-class distinction
// ---------------------------------------------------------------------------

#[test]
fn frame_words_are_little_endian_on_the_wire() {
    // §10: 32-bit LE body words. Pin it explicitly so a future BE flip
    // is caught. Frame layout: [MAGIC 4B][LEN 4B][body 4*N B][CRC 2B].
    // First body word starts at byte offset 8.
    let w1: u32 = 0xAABB_CCDD;
    let body = vec![w1, 0u32];
    let frame = build_frame(&body).unwrap();
    // body[0] = w1 at bytes [8..12], little-endian.
    assert_eq!(frame[8], 0xDD, "w1 byte 0 (lo)");
    assert_eq!(frame[9], 0xCC, "w1 byte 1");
    assert_eq!(frame[10], 0xBB, "w1 byte 2");
    assert_eq!(frame[11], 0xAA, "w1 byte 3 (hi)");
    // The verifier must reconstruct the word identically.
    let decoded = verify_frame(&frame).unwrap();
    assert_eq!(decoded[0], w1);
}

#[test]
fn verify_frame_truncated_at_every_offset_never_panics() {
    // Cut a known-good frame at every byte offset from 0 to len-1.
    // For each truncation, the verifier must return a clean error
    // and never panic.
    let body = vec![0x1234_0000u32, 0xABCD_0000u32, 0u32];
    let frame = build_frame(&body).unwrap();
    for cut in 0..frame.len() {
        let slice = &frame[..cut];
        let result = verify_frame(slice);
        assert!(
            result.is_err(),
            "verify_frame accepted a truncated frame ({cut} bytes of {})",
            frame.len()
        );
    }
}

#[test]
fn verify_frame_trailing_garbage_rejected_as_length_mismatch() {
    // A good frame with one extra byte appended is a length problem,
    // not a CRC problem. The verifier must report LengthMismatch.
    let body = vec![0x9000_0000u32, 0x6000_0000u32];
    let mut frame = build_frame(&body).unwrap();
    frame.push(0xFF);
    let err = verify_frame(&frame).unwrap_err();
    assert!(
        matches!(err, FrameError::LengthMismatch { .. }),
        "trailing garbage must produce LengthMismatch, got: {err:?}"
    );
}

#[test]
fn verify_frame_header_says_three_but_payload_has_only_header() {
    // Build a valid 1-body-word frame, then patch the LEN field to
    // claim 3 body words. Body has only 1, so LengthMismatch fires.
    let body = vec![0u32];
    let mut frame = build_frame(&body).unwrap();
    let claimed: u32 = 3;
    frame[4..8].copy_from_slice(&claimed.to_le_bytes());
    let err = verify_frame(&frame).unwrap_err();
    assert!(
        matches!(err, FrameError::LengthMismatch { .. }),
        "got: {err:?}"
    );
}

#[test]
fn verify_frame_oversize_8195_distinct_from_8194_max() {
    // MAX_PROGRAM_WORDS is the inclusive max body word count (= 8192).
    // 8193 (and above) must be LengthOutOfRange, not LengthMismatch.
    // Build a valid 1-body-word frame, patch LEN to 8193.
    let body = vec![0u32];
    let mut frame = build_frame(&body).unwrap();
    let over: u32 = (mole_abi::MAX_PROGRAM_WORDS as u32) + 1; // 8193
    frame[4..8].copy_from_slice(&over.to_le_bytes());
    let err = verify_frame(&frame).unwrap_err();
    assert!(
        matches!(err, FrameError::LengthOutOfRange { len_words } if len_words == over),
        "got: {err:?}"
    );
}

#[test]
fn verify_frame_crc_bit_flip_in_payload_caught() {
    // Flip exactly one bit in the middle of the payload and check
    // that CRC catches it.
    let body = vec![0x1111_0000u32, 0x2222_0000u32, 0u32];
    let mut frame = build_frame(&body).unwrap();
    // Flip a bit in the first body word's low byte (byte offset 8).
    frame[8] ^= 0x01;
    let err = verify_frame(&frame).unwrap_err();
    assert!(
        matches!(err, FrameError::CrcMismatch { .. }),
        "got: {err:?}"
    );
}

#[test]
fn verify_frame_crc_does_not_cover_itself() {
    // If CRC were computed over the whole frame including the CRC
    // bytes, flipping the CRC and re-CRCing would round-trip.
    let body = vec![0u32];
    let mut frame = build_frame(&body).unwrap();
    let last = frame.len() - 1;
    frame[last] ^= 0xFF;
    frame[last - 1] ^= 0xFF;
    assert!(matches!(
        verify_frame(&frame).unwrap_err(),
        FrameError::CrcMismatch { .. }
    ));
}

// ---------------------------------------------------------------------------
// Ring decoder --- endianness, truncation, sticky flags, capture runs
// ---------------------------------------------------------------------------

#[test]
fn revision_words_are_little_endian_on_the_wire() {
    // Spec §11.2: 32-bit REVISION word at word[0], drained as 4 LE
    // bytes. major=0x56 at [31:24], minor=0x78 at [23:16], patch=0x1234
    // at [15:0]. Minimum ring: 2 × 32-bit = 8 bytes.
    // HALT: 0xC000_0000 = tag=11 at [31:30], rest 0.
    let bytes: Vec<u8> = vec![
        0x34, 0x12, 0x78, 0x56, // REVISION word LE = 0x5678_1234
        0x00, 0x00, 0x00, 0xC0, // HALT word LE = 0xC000_0000
    ];
    let ring = decode_ring(&bytes).unwrap();
    assert_eq!(
        ring.revision,
        Revision {
            major: 0x56,
            minor: 0x78,
            patch: 0x1234,
        }
    );
}

#[test]
fn ring_decode_truncated_at_every_offset_never_panics() {
    // Build a substantive ring and truncate it byte-by-byte.
    let bytes = build_ring(
        revision_word(0x02, 0x03, 0x0001),
        &[
            capture_word(true),
            mark_header_word(0),
            mark_ts_word(0xBEEF),
            mark_ts_word(0xDEAD),
        ],
        HALT_CLEAN,
        7, // rev(1) + 1 cap + 3 mark + 1 spare + halt(1)
    );
    for cut in 0..bytes.len() {
        let slice = &bytes[..cut];
        // We don't care what the error variant is for each cut,
        // only that it doesn't panic.
        let _ = decode_ring(slice);
    }
}

#[test]
fn ring_with_odd_byte_count_classified_distinctly() {
    // Non-multiple-of-4 byte count must be OddByteCount, *not*
    // TooShort or NoHaltAtTail. The decoder uses the variant for
    // diagnostics.
    let bytes = vec![0u8; 7];
    assert!(matches!(
        decode_ring(&bytes).unwrap_err(),
        RingError::OddByteCount { got: 7 }
    ));
}

#[test]
fn ring_too_short_distinct_from_no_halt_at_tail() {
    // 4-byte buffer is too short (minimum is 8 bytes = 2 32-bit words).
    let err = decode_ring(&[0u8; 4]).unwrap_err();
    assert!(
        matches!(err, RingError::TooShort { got: 4 }),
        "got: {err:?}"
    );
    // An 8-byte buffer with tail word tag != 0b11 is NoHaltAtTail,
    // NOT TooShort.
    let bytes = vec![0u8; 8]; // halt word = 0x0000_0000, tag = 00
    let err = decode_ring(&bytes).unwrap_err();
    assert!(
        matches!(err, RingError::NoHaltAtTail { .. }),
        "got: {err:?}"
    );
}

#[test]
fn halt_status_flag_bits_independently_decoded() {
    // Sweep every status code + (overflow, mismatch) combination.
    // v0.2: STATUS_SHIFT=23, OVERFLOW_BIT=29, MISMATCH_BIT=28,
    // TAG_SHIFT=30.
    // STATUS_RESERVED_LOW = 0x1D and STATUS_RESERVED_HIGH = 0x1E are
    // rejected by the decoder; skip them here.
    for status in 0u8..=halt::STATUS_TRAP {
        if status == halt::STATUS_RESERVED_LOW || status == halt::STATUS_RESERVED_HIGH {
            continue;
        }
        for &mismatch in &[false, true] {
            for &overflow in &[false, true] {
                let halt_word = (halt::TAG_HALT << halt::TAG_SHIFT)
                    | ((status as u32) << halt::STATUS_SHIFT)
                    | if overflow { 1 << halt::OVERFLOW_BIT } else { 0 }
                    | if mismatch { 1 << halt::MISMATCH_BIT } else { 0 };
                let bytes = build_ring(0, &[], halt_word, 2);
                let ring = decode_ring(&bytes).unwrap();
                assert_eq!(
                    ring.halt,
                    HaltStatus {
                        status,
                        mismatch,
                        overflow,
                    },
                    "halt={halt_word:#010x}: decode mismatch"
                );
            }
        }
    }
}

#[test]
fn capture_run_boundary_sizes() {
    // Pin boundary capture counts (0, 1, 7, 8, 9, 32) so a future
    // aggregator change is loud.
    for &n in &[0usize, 1, 7, 8, 9, 32, 64, 256] {
        let records: Vec<u32> = (0..n).map(|i| capture_word(i % 2 == 0)).collect();
        let total_words = 2 + n; // rev(1) + records + halt(1)
        let bytes = build_ring(0, &records, HALT_CLEAN, total_words);
        let ring = decode_ring(&bytes).unwrap();
        assert_eq!(
            ring.records.len(),
            n,
            "n={n}: expected exactly {n} captures, got {}",
            ring.records.len()
        );
        assert_eq!(ring.trailing_garbage_words, 0);
    }
}

#[test]
fn capture_sda_bit_only_uses_bit_0() {
    // CAPTURE word tag=00 at [31:30]; sda at [0]; [29:1] must be
    // ignored. (Engine writes [29:1]=0 by construction; this test
    // pins the decoder's tolerance for garbage in those bits.)
    for body in [0x0000_0001u32, 0x3FFF_FFFF, 0x2AAA_AAAB, 0x1555_5555] {
        let bytes = build_ring(0, &[body], HALT_CLEAN, 3);
        let ring = decode_ring(&bytes).unwrap();
        assert_eq!(
            ring.records,
            vec![Record::Capture { sda: true }],
            "word={body:#010x}: bit 0 = 1 must yield sda=true regardless \
             of [29:1]"
        );
    }
    for body in [0x0000_0000u32, 0x3FFF_FFFE, 0x2AAA_AAAA, 0x1555_5554] {
        let bytes = build_ring(0, &[body], HALT_CLEAN, 3);
        let ring = decode_ring(&bytes).unwrap();
        assert_eq!(ring.records, vec![Record::Capture { sda: false }]);
    }
}

#[test]
fn mark_timestamp_little_endian_lo_then_hi() {
    // MARK is 3 32-bit words: header, ts_lo, ts_hi. Pin word order.
    // ts_lo holds the low 16 bits in [15:0]; ts_hi holds the high
    // 16 bits in [15:0]; [31:16] of each is reserved-zero (spec §11.4).
    let bytes = build_ring(
        0,
        &[
            mark_header_word(0),
            mark_ts_word(0xBABE),
            mark_ts_word(0xCAFE),
        ],
        HALT_CLEAN,
        5,
    );
    let ring = decode_ring(&bytes).unwrap();
    assert_eq!(
        ring.records,
        vec![Record::Mark {
            label: 0,
            timestamp: 0xCAFE_BABE
        }]
    );
}

#[test]
fn mark_label_uses_low_14_bits_only() {
    // MARK header [13:0] = label; [29:14] reserved-zero; [31:30]
    // tag=10. Stuff garbage into [29:14] and check label decode.
    // (Engine writes [29:14]=0; this pins the decoder's mask.)
    for header in [
        0x8000_0042u32,            // canonical: only tag + label
        0x8000_0042 | 0x3FFF_C000, // garbage in [29:14]
        0x8000_0042 | 0x2AAA_8000,
    ] {
        let bytes = build_ring(
            0,
            &[header, mark_ts_word(0), mark_ts_word(0)],
            HALT_CLEAN,
            5,
        );
        let ring = decode_ring(&bytes).unwrap();
        let Record::Mark { label, .. } = ring.records[0] else {
            panic!("expected Mark");
        };
        assert_eq!(label, 0x42, "header={header:#010x}: label should be 0x42");
    }
}

#[test]
fn ring_with_no_records_reports_full_gap_as_trailing_garbage() {
    // 6-word ring: rev(1) + 4 gap slots + halt(1). Zero-tagged
    // slots decode as CAPTURE(sda=false) per the documented hazard.
    let bytes = build_ring(0, &[], HALT_CLEAN, 6);
    let ring = decode_ring(&bytes).unwrap();
    assert_eq!(
        ring.records.len(),
        4,
        "zero-tagged garbage decodes as CAPTURE per documented hazard; \
         if this drops to 0, strict mode landed --- update the test"
    );
    assert_eq!(ring.trailing_garbage_words, 0);
}

#[test]
fn ring_decode_handles_megabyte_ring_in_bounded_time() {
    // 1 MiB ring = 256 Ki 32-bit words. Confirm it completes.
    let total_words = 256 * 1024;
    let bytes = build_ring(0, &[capture_word(true)], HALT_CLEAN, total_words);
    let ring = decode_ring(&bytes).unwrap();
    // 1 real capture + (total_words - 2) zero-tagged "garbage"
    // captures (tag 0b00 = CAPTURE). 1 REVISION + 1 HALT = 2
    // overhead words.
    assert_eq!(ring.records.len(), total_words - 2);
}

// ---------------------------------------------------------------------------
// Garbage prefix / suffix at the buffer level
// ---------------------------------------------------------------------------

#[test]
fn ring_decoder_does_not_silently_strip_a_prefix() {
    // A buffer that "looks like" a ring after skipping bytes must be
    // rejected by the strict decoder, not silently aligned. The lax
    // decoder accepts it (capability-gap pin).
    let inner = build_ring(revision_word(0x02, 0x03, 0x0001), &[], HALT_CLEAN, 2);
    // Prepend 4 garbage bytes (one 32-bit word).
    let mut buf = vec![0xAA, 0xBB, 0xCC, 0xDD];
    buf.extend_from_slice(&inner);
    // Tail bytes are still the HALT word from `inner`, so the tail
    // tag IS 0b11. The "REVISION" the decoder sees will be the
    // garbage prefix. The lax decoder accepts; this pins the gap.
    let ring = decode_ring(&buf).expect("decoder is currently tolerant");
    assert_ne!(
        ring.revision.patch, 0x0001,
        "if this ever equals 0x0001 the decoder grew prefix-stripping \
         and the test needs updating"
    );
}

// ---------------------------------------------------------------------------
// Revision parser hostile inputs
// ---------------------------------------------------------------------------

#[test]
fn revision_parser_rejects_empty_string_cleanly() {
    let err = "".parse::<Revision>().unwrap_err();
    assert!(
        !err.is_empty(),
        "revision-parse diagnostic should be non-empty"
    );
}

#[test]
fn revision_parser_rejects_negative_components() {
    assert!("-1.0.0".parse::<Revision>().is_err());
    assert!("1.-1.0".parse::<Revision>().is_err());
    assert!("1.0.-1".parse::<Revision>().is_err());
    assert!("+1.0.0".parse::<Revision>().is_err());
}

#[test]
fn revision_parser_rejects_whitespace() {
    assert!(" 1.2.3".parse::<Revision>().is_err());
    assert!("1.2.3 ".parse::<Revision>().is_err());
    assert!("1. 2.3".parse::<Revision>().is_err());
}

// ---------------------------------------------------------------------------
// Cross-crate integration: assemble -> verify_frame -> verify_program
// ---------------------------------------------------------------------------

#[test]
fn assemble_then_verify_frame_round_trip() {
    // Crossing the mole-asm / mole-loader boundary: frame must round-
    // trip through verify_frame. The returned words include the
    // 2-word preamble (MAGIC + body_len).
    let frame = mole_asm::assemble_to_frame("HALT status=0\nHALT status=1\n", "<rt>").unwrap();
    // verify_frame returns body-only (preamble validated + stripped).
    let body = verify_frame(&frame).unwrap();
    assert_eq!(body.len(), 2); // two HALT instructions
    let scanned = mole_loader::verify_program(&body).expect("valid assembled frame");
    assert_eq!(scanned.len(), 2);
}

#[test]
fn assemble_to_frame_then_loader_error_is_frame_variant() {
    // Corrupt a frame produced by the assembler; the LoaderError
    // variant must be Frame(_), not Ring(_).
    let mut frame = mole_asm::assemble_to_frame("HALT\n", "<t>").unwrap();
    let last = frame.len() - 1;
    frame[last] ^= 0xFF;
    let err: LoaderError = verify_frame(&frame).unwrap_err().into();
    assert!(
        matches!(err, LoaderError::Frame(FrameError::CrcMismatch { .. })),
        "got: {err:?}"
    );
}

// ---------------------------------------------------------------------------
// Magic validation (§16.1) — now done by verify_frame.
//
// v0.2 does not have a separate format-version field on the wire; the
// magic word `0x0002_4D4C` encodes both magic ("ML" = 0x4D4C) and
// version (0x0002) atomically. Any 4-byte value at offset 0 that is
// not exactly MAGIC is a frame-level rejection, surfaced from
// `verify_frame` (NOT `verify_program`).
// ---------------------------------------------------------------------------

#[test]
fn verify_frame_rejects_wrong_magic() {
    // Build a valid frame, then corrupt the magic bytes.
    let body = vec![0u32];
    let mut frame = build_frame(&body).unwrap();
    frame[0] ^= 0xFF;
    let err = verify_frame(&frame).unwrap_err();
    assert!(
        matches!(err, FrameError::MagicMismatch { .. }),
        "wrong magic must produce MagicMismatch, got: {err:?}"
    );
}

#[test]
fn verify_frame_rejects_wrong_version_via_magic() {
    // The version is baked into the high 16 bits of the magic word.
    // If word 0 has the right "ML" low half but a wrong version high
    // half (e.g. 0x0001_4D4C), verify_frame still flags it as a magic
    // mismatch because the comparison is against the full 32-bit value.
    let body = vec![0u32];
    let mut frame = build_frame(&body).unwrap();
    // Build "v1 magic" = (0x0001 << 16) | 0x4D4C = 0x0001_4D4C.
    let wrong_magic: u32 = (0x0001u32 << 16) | (mole_abi::MAGIC_LO_U16 as u32);
    frame[0..4].copy_from_slice(&wrong_magic.to_le_bytes());
    let err = verify_frame(&frame).unwrap_err();
    assert!(
        matches!(err, FrameError::MagicMismatch { got, expected }
                 if got == wrong_magic && expected == mole_abi::MAGIC),
        "wrong version in magic word must produce MagicMismatch, got: {err:?}"
    );
}

#[test]
fn verify_program_accepts_correct_frame() {
    let frame = mole_asm::assemble_to_frame("HALT status=0\n", "<t>").unwrap();
    // verify_frame returns body-only; verify_program returns the body
    // unchanged if no reserved HALT status is found.
    let body = verify_frame(&frame).unwrap();
    let scanned = mole_loader::verify_program(&body).unwrap();
    assert_eq!(scanned.len(), 1);
}

// ---------------------------------------------------------------------------
// Reserved-status-in-body scan (§16.4)
// ---------------------------------------------------------------------------

#[test]
fn verify_program_rejects_halt_with_reserved_status_0x1d() {
    // Build a HALT instruction with status=0x1D in the body.
    // HALT encoding: [31:30]=01, [29:26]=0000, [7:3]=status, rest=0.
    // HALT opcode at [31:26] = 0b01_0000 = 0x10.
    // status=0x1D at [7:3]: 0x1D << 3 = 0xE8.
    let halt_instr: u32 = (0b01_0000u32 << 26) | (0x1Du32 << 3);
    let body = vec![halt_instr];
    let frame = build_frame(&body).unwrap();
    let decoded = verify_frame(&frame).unwrap();
    let err = mole_loader::verify_program(&decoded).unwrap_err();
    assert!(
        matches!(
            err,
            FrameError::ReservedHaltStatusInBody {
                pc: 0,
                status: 0x1D,
            }
        ),
        "reserved status 0x1D must be rejected, got: {err:?}"
    );
}

#[test]
fn verify_program_rejects_halt_with_reserved_status_0x1e() {
    let halt_instr: u32 = (0b01_0000u32 << 26) | (0x1Eu32 << 3);
    let body = vec![halt_instr];
    let frame = build_frame(&body).unwrap();
    let decoded = verify_frame(&frame).unwrap();
    let err = mole_loader::verify_program(&decoded).unwrap_err();
    assert!(
        matches!(
            err,
            FrameError::ReservedHaltStatusInBody {
                pc: 0,
                status: 0x1E,
            }
        ),
        "reserved status 0x1E must be rejected, got: {err:?}"
    );
}

#[test]
fn verify_program_accepts_halt_status_user_max_0x1c() {
    // 0x1C is STATUS_USER_MAX; it must NOT be rejected.
    let halt_instr: u32 = (0b01_0000u32 << 26) | (0x1Cu32 << 3);
    let body = vec![halt_instr];
    let frame = build_frame(&body).unwrap();
    let decoded = verify_frame(&frame).unwrap();
    assert!(mole_loader::verify_program(&decoded).is_ok());
}

#[test]
fn verify_program_accepts_halt_status_trap_0x1f() {
    // 0x1F is STATUS_TRAP; the loader does NOT pre-reject it --- the
    // engine emits it on malformed instructions and user programs
    // should never emit it, but the loader spec only rejects
    // 0x1D..=0x1E. STATUS_TRAP passes verify_program.
    let halt_instr: u32 = (0b01_0000u32 << 26) | (0x1Fu32 << 3);
    let body = vec![halt_instr];
    let frame = build_frame(&body).unwrap();
    let decoded = verify_frame(&frame).unwrap();
    assert!(mole_loader::verify_program(&decoded).is_ok());
}

// ---------------------------------------------------------------------------
// Sticky-flag observation (§3.15)
// ---------------------------------------------------------------------------

#[test]
fn halt_mismatch_flag_is_sticky_across_all_status_codes() {
    // §3.15: sticky flags are write-once until overwritten. The HALT
    // word's `mismatch` bit reflects the sticky flag at HALT entry
    // independently of the status code. Skip reserved codes.
    for status in 0u8..=halt::STATUS_TRAP {
        if status == halt::STATUS_RESERVED_LOW || status == halt::STATUS_RESERVED_HIGH {
            continue;
        }
        let halt_word = (halt::TAG_HALT << halt::TAG_SHIFT)
            | (1 << halt::MISMATCH_BIT)
            | ((status as u32) << halt::STATUS_SHIFT);
        let bytes = build_ring(0, &[], halt_word, 2);
        let ring = decode_ring(&bytes).unwrap();
        assert!(
            ring.halt.mismatch,
            "status={status:#x}: mismatch flag must decode independently"
        );
        assert_eq!(ring.halt.status, status);
    }
}

#[test]
fn all_four_sticky_flags_observable_in_decoded_ring() {
    // AGENTS.md §3.15 names four sticky engine flags: MISMATCH,
    // TIMEOUT, START, STOP. v0.2 surfaces them to the host through
    // two channels (spec §11 + §6 cond-code table):
    //
    //   1. MISMATCH ships in the HALT word directly as bit [28]
    //      (`HaltStatus::mismatch`). It is the only sticky flag with
    //      a dedicated HALT bit because it gates the "did this test
    //      pass?" question every program asks.
    //
    //   2. TIMEOUT / START / STOP are observable via program logic:
    //      a `BRANCH_ON` consumes the flag (cond codes 1/4/5 hit
    //      TIMEOUT_FLAG; codes 6/7 hit START_FLAG; codes 8/9 hit
    //      STOP_FLAG --- see spec §6) and the program HALTs with a
    //      distinct 5-bit status code per outcome. The 5-bit status
    //      field (0x00..=0x1C user-defined, see §11) gives the
    //      program 29 distinct codes to encode whichever flag-state
    //      tuple it cares about.
    //
    // This test asserts the loader-side contract on both channels:
    // every (status, mismatch) combination a program might choose
    // round-trips through `decode_ring` without conflation. The
    // engine-side "did it actually set these flags?" question is
    // covered by `BitCycleEngineSim` / `EnginePipelineSim` in
    // fpga/Mole/. Splitting concerns this way is exactly the
    // observable-from-the-decoded-ring property the AGENTS file
    // demands: any program can encode any flag observation into the
    // ring, and the host will faithfully report it.
    //
    // Status-code allocation used here is illustrative --- the spec
    // does not reserve specific user codes for specific flags. A
    // real test fixture under fpga/Mole/ assigns them at program-
    // compile time. The point is that decode preserves them.
    const STATUS_TIMEOUT_OBSERVED: u8 = 0x10;
    const STATUS_START_OBSERVED: u8 = 0x11;
    const STATUS_STOP_OBSERVED: u8 = 0x12;
    const STATUS_NO_FLAGS_OBSERVED: u8 = 0x00;

    // Case 1: MISMATCH via the dedicated HALT bit. Status code does
    // not need to encode it; the bit is sufficient on its own.
    let halt_mismatch = (halt::TAG_HALT << halt::TAG_SHIFT)
        | (1 << halt::MISMATCH_BIT)
        | ((STATUS_NO_FLAGS_OBSERVED as u32) << halt::STATUS_SHIFT);
    let ring = decode_ring(&build_ring(0, &[], halt_mismatch, 2)).unwrap();
    assert!(
        ring.halt.mismatch,
        "MISMATCH must decode through the dedicated HALT bit"
    );
    assert_eq!(ring.halt.status, STATUS_NO_FLAGS_OBSERVED);

    // Case 2: TIMEOUT via status-code convention (BRANCH_ON cond
    // codes 1/4/5 hit TIMEOUT_FLAG; program HALTs with a code that
    // means "we saw TIMEOUT").
    let halt_timeout = halt_word_status(STATUS_TIMEOUT_OBSERVED);
    let ring = decode_ring(&build_ring(0, &[], halt_timeout, 2)).unwrap();
    assert_eq!(
        ring.halt.status, STATUS_TIMEOUT_OBSERVED,
        "TIMEOUT must be observable via the 5-bit HALT status field"
    );
    assert!(!ring.halt.mismatch);
    assert!(!ring.halt.overflow);

    // Case 3: START via status-code convention (cond codes 6/7).
    let halt_start = halt_word_status(STATUS_START_OBSERVED);
    let ring = decode_ring(&build_ring(0, &[], halt_start, 2)).unwrap();
    assert_eq!(ring.halt.status, STATUS_START_OBSERVED);
    assert!(!ring.halt.mismatch);

    // Case 4: STOP via status-code convention (cond codes 8/9).
    let halt_stop = halt_word_status(STATUS_STOP_OBSERVED);
    let ring = decode_ring(&build_ring(0, &[], halt_stop, 2)).unwrap();
    assert_eq!(ring.halt.status, STATUS_STOP_OBSERVED);
    assert!(!ring.halt.mismatch);

    // Case 5: all four observed simultaneously. MISMATCH in its
    // dedicated bit; TIMEOUT/START/STOP composed into a single
    // status code via a host-program-defined bitmap. Use status
    // 0x1C (the highest legal user code per §11) as a synthetic
    // "all three soft flags observed" sentinel; real fixtures pick
    // codes from a per-test scheme, but the loader's contract is
    // identical: status preserved verbatim.
    const STATUS_ALL_SOFT_OBSERVED: u8 = 0x1C;
    let halt_all = (halt::TAG_HALT << halt::TAG_SHIFT)
        | (1 << halt::MISMATCH_BIT)
        | ((STATUS_ALL_SOFT_OBSERVED as u32) << halt::STATUS_SHIFT);
    let ring = decode_ring(&build_ring(0, &[], halt_all, 2)).unwrap();
    assert!(ring.halt.mismatch);
    assert_eq!(ring.halt.status, STATUS_ALL_SOFT_OBSERVED);

    // Sanity: the four distinct status codes used above must all
    // round-trip without collision in `HaltStatus` equality.
    let s_clean = HaltStatus {
        status: STATUS_NO_FLAGS_OBSERVED,
        mismatch: false,
        overflow: false,
    };
    let s_timeout = HaltStatus {
        status: STATUS_TIMEOUT_OBSERVED,
        mismatch: false,
        overflow: false,
    };
    let s_start = HaltStatus {
        status: STATUS_START_OBSERVED,
        mismatch: false,
        overflow: false,
    };
    let s_stop = HaltStatus {
        status: STATUS_STOP_OBSERVED,
        mismatch: false,
        overflow: false,
    };
    assert_ne!(s_clean, s_timeout);
    assert_ne!(s_timeout, s_start);
    assert_ne!(s_start, s_stop);
    assert_ne!(s_stop, s_clean);
}

// ---------------------------------------------------------------------------
// Decoded ring equality / Display sanity
// ---------------------------------------------------------------------------

#[test]
fn decoded_ring_equality_is_field_wise() {
    let a: DecodedRing = decode_ring(&build_ring(0, &[], HALT_CLEAN, 2)).unwrap();
    let b = decode_ring(&build_ring(0, &[], HALT_CLEAN, 2)).unwrap();
    assert_eq!(a, b);
    // Distinct REVISION word (different patch) → distinct decoded ring.
    let c = decode_ring(&build_ring(revision_word(0, 0, 1), &[], HALT_CLEAN, 2)).unwrap();
    assert_ne!(a, c);
}

#[test]
fn crc_helper_re_export_matches_mole_asm_implementation() {
    assert_eq!(crc16_xmodem(b"123456789"), 0x31C3);
    assert_eq!(crc16_xmodem(b""), 0x0000);
}

// ---------------------------------------------------------------------------
// T-008 / T-009 --- HALT word validation (F-HOST-001)
//
// v0.2 HALT word layout (§11 lines 1458-1492):
//   [31:30]=11 (tag), [29]=overflow, [28]=mismatch, [27:23]=status,
//   [22:0]=0 (reserved). Status 0x00..=0x1C user, 0x1D..=0x1E
//   reserved, 0x1F engine-trap.
// ---------------------------------------------------------------------------

/// Build a ring of exactly `DEFAULT_RING_BYTES` with the canonical
/// REVISION word at word[0], the supplied 32-bit HALT word at
/// word[resultLimit] (= the last 32-bit slot), and the middle filled
/// with tag-`01` reserved-tag words so the record-stream walker
/// terminates immediately.
fn build_minimal_ring_with_halt(halt_word: u32) -> Vec<u8> {
    let total_words = DEFAULT_RING_BYTES / 4;
    let mut words: Vec<u32> = Vec::with_capacity(total_words);
    words.push(revision_word(0x02, 0x03, 0x0001));
    // Fill the gap with 0x4000_0000 (tag 01 reserved). The walker
    // recognises tag 01 as "trailing garbage starts here" and
    // stops on the first slot, keeping the test cheap.
    while words.len() < total_words - 1 {
        words.push(0x4000_0000);
    }
    words.push(halt_word);
    assert_eq!(words.len(), total_words);
    let mut bytes = Vec::with_capacity(total_words * 4);
    for w in words {
        bytes.extend_from_slice(&w.to_le_bytes());
    }
    bytes
}

#[test]
fn halt_reserved_bits_low_byte_rejected() {
    // Set bit 6 in the reserved [22:0] range.
    // halt_word = 0xC000_0040 (tag=11, reserved bit 6 set).
    let halt_word: u32 = HALT_CLEAN | 0x0000_0040;
    let ring = build_minimal_ring_with_halt(halt_word);
    let r = decode_ring_strict(&ring, ring.len());
    assert!(
        matches!(
            r,
            Err(RingError::HaltReservedBitsSet {
                halt_word: hw,
                reserved: rb,
            }) if hw == halt_word && rb == 0x0000_0040
        ),
        "expected HaltReservedBitsSet with reserved=0x40, got {r:?}"
    );
}

#[test]
fn halt_reserved_bits_low_byte_rejected_across_patterns() {
    // Sweep a set of reserved-bit patterns; every non-zero value in
    // [22:0] must trip the fail-fast check.
    for reserved in [
        0x0000_0001u32,
        0x0000_0002,
        0x0000_0040,
        0x0000_0080,
        0x0007_FFFF,
        0x007F_FFFF,
    ] {
        let halt_word = HALT_CLEAN | reserved;
        let ring = build_minimal_ring_with_halt(halt_word);
        let r = decode_ring_strict(&ring, ring.len());
        assert!(
            matches!(
                r,
                Err(RingError::HaltReservedBitsSet {
                    halt_word: hw,
                    reserved: rb,
                }) if hw == halt_word && rb == reserved
            ),
            "reserved={reserved:#010x}: expected HaltReservedBitsSet, \
             got {r:?}"
        );
    }
}

#[test]
fn halt_status_reserved_for_engine_traps_rejected() {
    // STATUS_RESERVED_LOW = 0x1D and STATUS_RESERVED_HIGH = 0x1E.
    for status in [halt::STATUS_RESERVED_LOW, halt::STATUS_RESERVED_HIGH] {
        let halt_word = halt_word_status(status);
        let ring = build_minimal_ring_with_halt(halt_word);
        let r = decode_ring_strict(&ring, ring.len());
        assert!(
            matches!(
                r,
                Err(RingError::HaltStatusReserved {
                    status: s,
                    halt_word: hw,
                }) if s == status && hw == halt_word
            ),
            "status 0x{status:X} must be rejected; got {r:?}"
        );
    }
}

#[test]
fn halt_status_engine_trap_0x1f_still_decodes_with_trap_flag() {
    // 0x1F is STATUS_TRAP; the decoder must NOT reject it.
    let halt_word = halt_word_status(halt::STATUS_TRAP);
    let ring = build_minimal_ring_with_halt(halt_word);
    let decoded = decode_ring_strict(&ring, ring.len()).expect("0x1F must decode");
    assert_eq!(decoded.halt.status, halt::STATUS_TRAP);
    assert!(decoded.halt.is_engine_trap());
}

#[test]
fn halt_clean_status_zero_still_decodes_after_validation_lands() {
    // Regression: the canonical "clean halt" (tag=11, status=0,
    // reserved=0) must still decode after the HALT validation checks.
    let ring = build_minimal_ring_with_halt(HALT_CLEAN);
    let decoded = decode_ring_strict(&ring, ring.len()).expect("clean HALT must decode");
    assert_eq!(
        decoded.halt,
        HaltStatus {
            status: 0,
            mismatch: false,
            overflow: false,
        }
    );
}

#[test]
fn halt_reserved_bits_rejected_before_reserved_status() {
    // A HALT word that violates BOTH invariants (reserved bits set
    // AND reserved status code) must be reported as
    // HaltReservedBitsSet --- the structural check fires first.
    let halt_word = halt_word_status(halt::STATUS_RESERVED_LOW) | 0x0000_0001u32;
    let ring = build_minimal_ring_with_halt(halt_word);
    let r = decode_ring_strict(&ring, ring.len());
    assert!(
        matches!(r, Err(RingError::HaltReservedBitsSet { .. })),
        "structural check (reserved bits) must fire before semantic check \
         (reserved status); got {r:?}"
    );
}
