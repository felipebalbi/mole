//! Adversarial / contract tests for `mole-loader`.
//!
//! Goals: hunt frame-verifier off-by-ones, ring-decoder confusion,
//! error-class conflation, and endianness regressions. Each test, if
//! it ever starts failing, signals a real bug --- not a cosmetic
//! change.

use mole_asm::frame::{build_frame, crc16_xmodem};
use mole_loader::{
    DecodedRing, FrameError, HaltStatus, LoaderError, Record, Revision, RingError, decode_ring,
    verify_frame,
};

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/// Pack a sequence of u16 words into the result-ring byte layout
/// (little-endian) with a given total word count. The caller supplies
/// the revision (lo, hi), the record-stream words, the HALT word, and
/// the total ring size in words; the middle is zero-padded (Verilator
/// behavior).
fn build_ring(revision: (u16, u16), records: &[u16], halt: u16, total_words: usize) -> Vec<u8> {
    assert!(total_words >= 3 + records.len());
    let mut words = Vec::with_capacity(total_words);
    words.push(revision.0);
    words.push(revision.1);
    words.extend_from_slice(records);
    while words.len() < total_words - 1 {
        words.push(0x0000);
    }
    words.push(halt);
    let mut bytes = Vec::with_capacity(total_words * 2);
    for w in words {
        bytes.push((w & 0xFF) as u8);
        bytes.push((w >> 8) as u8);
    }
    bytes
}

// ---------------------------------------------------------------------------
// Frame verifier --- endianness, fuzz, error-class distinction
// ---------------------------------------------------------------------------

#[test]
fn frame_words_are_little_endian_on_the_wire() {
    // §"Wire contract" in mole-loader/README.md and WIRE_FORMAT.md
    // specify little-endian words. Pin it explicitly so a future
    // BE flip is caught.
    let frame = build_frame(&[0xAABB]).unwrap();
    // Frame: [len_lo=1, len_hi=0, word_lo=0xBB, word_hi=0xAA, crc_lo, crc_hi]
    assert_eq!(frame[0], 0x01, "len_lo");
    assert_eq!(frame[1], 0x00, "len_hi");
    assert_eq!(frame[2], 0xBB, "word_lo");
    assert_eq!(frame[3], 0xAA, "word_hi");
    // And the verifier reconstructs the word identically.
    assert_eq!(verify_frame(&frame).unwrap(), vec![0xAABB]);
}

#[test]
fn verify_frame_truncated_at_every_offset_never_panics() {
    // Cut a known-good frame at every byte offset from 0 to len-1.
    // For each truncation, the verifier must return a clean error
    // and never panic.
    let frame = build_frame(&[0x1234, 0xABCD, 0xC000]).unwrap();
    for cut in 0..frame.len() {
        let slice = &frame[..cut];
        let result = verify_frame(slice);
        // Some short slices may pass FrameError::TooShort; longer
        // ones LengthMismatch or CrcMismatch. The point is: an
        // error, never a panic, never a success on a truncated
        // good frame.
        assert!(
            result.is_err(),
            "verify_frame accepted a truncated frame ({cut} bytes of {})",
            frame.len()
        );
    }
}

#[test]
fn verify_frame_trailing_garbage_rejected_as_length_mismatch() {
    // A good frame with one extra byte appended is *not* a "CRC
    // problem" --- it's a length problem (we know the header). The
    // verifier must report LengthMismatch, not CrcMismatch. This
    // is the conflated-errors hazard called out in the brief.
    let mut frame = build_frame(&[0x9000, 0x6000]).unwrap();
    frame.push(0xFF);
    let err = verify_frame(&frame).unwrap_err();
    assert!(
        matches!(err, FrameError::LengthMismatch { .. }),
        "trailing garbage must produce LengthMismatch, got: {err:?}"
    );
}

#[test]
fn verify_frame_header_says_one_but_payload_is_empty() {
    // Header claims one word; only the header itself is present.
    // Should be LengthMismatch (we got the header but not the body).
    let buf = vec![0x01, 0x00];
    let err = verify_frame(&buf).unwrap_err();
    assert!(
        matches!(err, FrameError::LengthMismatch { .. }),
        "got: {err:?}"
    );
}

#[test]
fn verify_frame_oversize_2049_distinct_from_2048_max() {
    // 2048 is the inclusive max; 2049 must be LengthOutOfRange, not
    // LengthMismatch. Off-by-one regressions on the cap would flip
    // the error class.
    let mut buf = vec![0x01, 0x08]; // 0x0801 = 2049
    buf.extend(std::iter::repeat_n(0u8, 2049 * 2 + 2));
    let err = verify_frame(&buf).unwrap_err();
    assert!(
        matches!(err, FrameError::LengthOutOfRange { len_words: 2049 }),
        "got: {err:?}"
    );
}

#[test]
fn verify_frame_crc_bit_flip_in_payload_caught() {
    // Flip exactly one bit in the middle of the payload and check
    // that CRC catches it (the CRC-16/XMODEM polynomial has decent
    // single-bit detection by construction; this regression-pins
    // the CRC covers the right byte range).
    let mut frame = build_frame(&[0x1111, 0x2222, 0x3333]).unwrap();
    // Flip a bit in the second word's low byte.
    frame[4] ^= 0x01;
    let err = verify_frame(&frame).unwrap_err();
    assert!(
        matches!(err, FrameError::CrcMismatch { .. }),
        "got: {err:?}"
    );
}

#[test]
fn verify_frame_crc_does_not_cover_itself() {
    // If CRC were computed over the *whole* frame including the CRC
    // bytes, flipping the CRC and re-CRCing would round-trip. As
    // implemented it doesn't, so a wrong CRC stays wrong.
    let mut frame = build_frame(&[0x0000]).unwrap();
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
    // patch=0x1234 in word_lo, major=0x56 / minor=0x78 in word_hi.
    // word_lo bytes: [0x34, 0x12]; word_hi bytes: [0x78, 0x56].
    let bytes: Vec<u8> = vec![
        0x34, 0x12, // word_lo = 0x1234 -> patch
        0x78, 0x56, // word_hi = 0x5678 -> major=0x56, minor=0x78
        0x00, 0xC0, // halt: tag=11 only
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
    // Build a substantive ring and truncate it byte-by-byte. Every
    // slice must produce either Ok or a structured RingError, never
    // panic.
    let bytes = build_ring(
        (0x0001, 0x0203),
        &[0x0001, 0x8000, 0xBEEF, 0xDEAD],
        0xC000,
        8,
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
    // Odd byte count must be OddByteCount, *not* TooShort or
    // NoHaltAtTail. The decoder uses the variant for diagnostics.
    let bytes = vec![0u8; 7];
    assert!(matches!(
        decode_ring(&bytes).unwrap_err(),
        RingError::OddByteCount { got: 7 }
    ));
}

#[test]
fn ring_too_short_distinct_from_no_halt_at_tail() {
    // 4-byte buffer is too short to even hold the minimum ring.
    let err = decode_ring(&[0u8; 4]).unwrap_err();
    assert!(
        matches!(err, RingError::TooShort { got: 4 }),
        "got: {err:?}"
    );
    // A 6-byte buffer with tail tag != 0b11 is NoHaltAtTail, NOT
    // TooShort.
    let bytes = vec![0u8; 6]; // tail = 0x0000, tag = 00
    let err = decode_ring(&bytes).unwrap_err();
    assert!(
        matches!(err, RingError::NoHaltAtTail { .. }),
        "got: {err:?}"
    );
}

#[test]
fn halt_status_flag_bits_independently_decoded() {
    // Sweep every (overflow, mismatch, status) combination of the
    // HALT word and verify decode is bit-for-bit. Catches a
    // bit-shift regression in `decode_ring`'s tail parser.
    for status in 0u8..=0xF {
        for &mismatch in &[false, true] {
            for &overflow in &[false, true] {
                let mut halt: u16 = 0xC000; // tag=11
                if overflow {
                    halt |= 1 << 13;
                }
                if mismatch {
                    halt |= 1 << 12;
                }
                halt |= (status as u16) << 8;
                let bytes = build_ring((0, 0), &[], halt, 3);
                let ring = decode_ring(&bytes).unwrap();
                assert_eq!(
                    ring.halt,
                    HaltStatus {
                        status,
                        mismatch,
                        overflow,
                    },
                    "halt={halt:#06x}: decode mismatch"
                );
            }
        }
    }
}

#[test]
fn capture_run_boundary_sizes() {
    // Capture aggregation isn't a thing in the current API --- the
    // decoder just emits one `Record::Capture` per word --- but the
    // brief asks us to pin boundary capture counts (0, 1, 7, 8, 9
    // and a long run) so a future aggregator can be retrofitted
    // without silently changing reported counts.
    for &n in &[0usize, 1, 7, 8, 9, 32, 64, 256, 1024] {
        let records: Vec<u16> = (0..n).map(|i| (i as u16) & 1).collect();
        let total_words = 3 + n; // rev(2) + records + halt(1)
        let bytes = build_ring((0, 0), &records, 0xC000, total_words);
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
    // CAPTURE word tag=00; SDA is bit 0. Bits [13:1] must be
    // ignored by the decoder --- any other bit influencing the
    // decoded sda value is a bug.
    // Try a few words that all have bit 0 = 1 but garbage elsewhere.
    for body in [0x0001u16, 0x3FFF, 0x2AAB, 0x1555] {
        let bytes = build_ring((0, 0), &[body], 0xC000, 4);
        let ring = decode_ring(&bytes).unwrap();
        assert_eq!(
            ring.records,
            vec![Record::Capture { sda: true }],
            "word={body:#06x}: bit 0 = 1 must yield sda=true regardless \
             of [13:1]"
        );
    }
    // And bit 0 = 0:
    for body in [0x0000u16, 0x3FFE, 0x2AAA, 0x1554] {
        let bytes = build_ring((0, 0), &[body], 0xC000, 4);
        let ring = decode_ring(&bytes).unwrap();
        assert_eq!(ring.records, vec![Record::Capture { sda: false }]);
    }
}

#[test]
fn mark_timestamp_little_endian_lo_then_hi() {
    // MARK is 3 words: header, ts_lo, ts_hi. The full 32-bit
    // timestamp is `(ts_hi << 16) | ts_lo`. Pin the byte order to
    // catch a future word-swap regression. We use a value whose
    // halves are clearly distinct (0xCAFEBABE).
    let bytes = build_ring((0, 0), &[0x8000, 0xBABE, 0xCAFE], 0xC000, 6);
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
fn mark_label_uses_low_byte_only() {
    // MARK header low 8 bits = label; high 6 bits (above tag) are
    // reserved. Stuff garbage into [13:8] and check label decode
    // is still just the low byte.
    for header in [0x8042u16, 0xBF42, 0xA042] {
        let bytes = build_ring((0, 0), &[header, 0, 0], 0xC000, 6);
        let ring = decode_ring(&bytes).unwrap();
        let Record::Mark { label, .. } = ring.records[0] else {
            panic!("expected Mark");
        };
        assert_eq!(label, 0x42, "header={header:#06x}: label should be 0x42");
    }
}

#[test]
fn ring_with_no_records_reports_full_gap_as_trailing_garbage() {
    // 8-word ring: rev(2) + 5 garbage slots + halt(1). Our `build_ring`
    // fills the gap with zeros; tag(0x0000) = 00 = CAPTURE, so the
    // decoder will decode them all as `Capture { sda: false }`.
    // This is the documented "trailing garbage" hazard --- pin it so
    // a future strict-mode flip is loud.
    let bytes = build_ring((0, 0), &[], 0xC000, 8);
    let ring = decode_ring(&bytes).unwrap();
    assert_eq!(
        ring.records.len(),
        5,
        "zero-tagged garbage decodes as CAPTURE per documented hazard \
         (mole-loader/README.md §Trailing-garbage hazard); if this \
         drops to 0, strict mode landed --- update the test and the \
         README in the same PR"
    );
    assert_eq!(ring.trailing_garbage_words, 0);
}

#[test]
fn ring_decode_handles_megabyte_ring_in_bounded_time() {
    // 1 MiB ring = 512 Ki words. The decoder must walk it linearly
    // and not allocate quadratically. We don't time it; we just
    // confirm it completes and reports the right structure.
    let total_words = 512 * 1024;
    let bytes = build_ring((0, 0), &[0x0001], 0xC000, total_words);
    let ring = decode_ring(&bytes).unwrap();
    // 1 real capture, then (total_words - 4) zero-tagged "garbage"
    // captures, then HALT. Our walker decodes zeros as captures
    // because tag(0x0000) = 00.
    assert_eq!(ring.records.len(), total_words - 3);
}

// ---------------------------------------------------------------------------
// Garbage prefix / suffix at the buffer level
// ---------------------------------------------------------------------------

#[test]
fn ring_decoder_does_not_silently_strip_a_prefix() {
    // A buffer that "looks like" a ring after skipping one word
    // (i.e. someone concatenated a stray word) must be rejected,
    // not silently aligned. We confirm by prepending two bytes
    // that would re-frame the rest as a valid ring --- the
    // decoder must use the new tail (offset == len-2) for HALT
    // checking and reject when tag mismatches.
    let inner = build_ring((0x0001, 0x0203), &[], 0xC000, 3);
    let mut buf = vec![0xAA, 0xBB];
    buf.extend_from_slice(&inner);
    // Now tail bytes are still the HALT word from `inner`, so the
    // tail tag IS 0b11. The "REVISION" the decoder sees will be
    // [0xAA, 0xBB, 0x01, 0x00] -> word_lo=0xBBAA, word_hi=0x0001.
    // The decoder will accept this as a valid ring (no integrity
    // beyond the tail tag). This is a *capability gap*: prefix
    // tolerance.
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
    // unsigned components: negatives must be rejected. The parser
    // also rejects a leading `+` (libstd's `u8::from_str` would
    // otherwise accept it), so "+1.0.0" is errored too.
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
// Cross-crate integration: assemble -> verify_frame -> decode words
// ---------------------------------------------------------------------------

#[test]
fn assemble_then_verify_frame_round_trip() {
    // The one place round-tripping is genuinely useful: it crosses
    // the mole-asm / mole-loader boundary. If this ever breaks, the
    // two crates have drifted on frame format.
    let frame = mole_asm::assemble_to_frame("HALT status=0\nHALT status=1\n", "<rt>").unwrap();
    let words = verify_frame(&frame).unwrap();
    assert_eq!(words, vec![0x0000, 0x0080]);
}

#[test]
fn assemble_to_frame_then_loader_error_is_frame_variant() {
    // Corrupt a frame produced by the assembler; the LoaderError
    // variant must be Frame(_), not Ring(_) (which is the most
    // common conflation hazard).
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
// Format version mismatch (capability gap)
// ---------------------------------------------------------------------------

#[test]
#[ignore = "blocked: adding a frame-format version field requires a \
            coordinated change to the FPGA engine's loader FSM in \
            fpga/Mole/ (it expects the existing [len, words, crc] \
            wire layout; a host-only version prepend would break \
            the hardware loop). Tracked as architect S1; needs to \
            land together with the engine-side change. AGENTS.md \
            §3.17 commits to bytecode-format stability once Phase 0 \
            ships; pre-Phase-0 the format may break cleanly when \
            both sides change in lockstep."]
fn frame_format_version_mismatch_rejected() {
    // Placeholder. Once the frame grows a 1-byte / 1-word format-
    // version field, feeding the verifier a wrong version must
    // produce a dedicated FrameError variant (not a CrcMismatch).
}

// ---------------------------------------------------------------------------
// Sticky-flag observation (§3.15)
// ---------------------------------------------------------------------------

#[test]
fn halt_mismatch_flag_is_sticky_across_all_status_codes() {
    // §3.15: sticky flags (MISMATCH_FLAG etc.) are write-once until
    // overwritten. The HALT word's `mismatch` bit reflects the
    // sticky flag at HALT entry --- it must decode independently of
    // the status code value. Loop over every status code with
    // mismatch=1 and confirm the decoded HaltStatus carries it.
    for status in 0u8..=0xF {
        let halt: u16 = 0xC000 | (1 << 12) | ((status as u16) << 8);
        let bytes = build_ring((0, 0), &[], halt, 3);
        let ring = decode_ring(&bytes).unwrap();
        assert!(
            ring.halt.mismatch,
            "status={status:#x}: mismatch flag must decode independently"
        );
        assert_eq!(ring.halt.status, status);
    }
}

#[test]
#[ignore = "blocked on engine-side work in fpga/Mole/: the HALT word \
            currently surfaces only MISMATCH_FLAG + overflow. \
            AGENTS.md §3.15 names four sticky flags (MISMATCH, \
            TIMEOUT, START, STOP). Wiring the remaining three into \
            the ring (HALT-word bit allocation or a dedicated \
            sentinel record) is FPGA work, not a host change; the \
            mole-loader decoder will gain the matching fields once \
            the engine emits them."]
fn all_four_sticky_flags_observable_in_decoded_ring() {
    // Placeholder. When the engine grows additional sticky-flag
    // bits in the HALT word (or a separate flag record), this test
    // exercises the full set --- START, STOP, TIMEOUT, MISMATCH.
}

// ---------------------------------------------------------------------------
// Decoded ring equality / Display sanity
// ---------------------------------------------------------------------------

#[test]
fn decoded_ring_equality_is_field_wise() {
    // Two identical builds must compare equal; perturbing any
    // single field must compare unequal. Catches a future PartialEq
    // derivation regression.
    let a: DecodedRing = decode_ring(&build_ring((0, 0), &[], 0xC000, 3)).unwrap();
    let b = decode_ring(&build_ring((0, 0), &[], 0xC000, 3)).unwrap();
    assert_eq!(a, b);
    let c = decode_ring(&build_ring((1, 0), &[], 0xC000, 3)).unwrap();
    assert_ne!(a, c);
}

#[test]
fn crc_helper_re_export_matches_mole_asm_implementation() {
    // The loader uses `mole_asm::frame::crc16_xmodem` directly;
    // pin the published catalog value here too so a regression
    // anywhere in the CRC path lights up *both* crates' suites.
    assert_eq!(crc16_xmodem(b"123456789"), 0x31C3);
    assert_eq!(crc16_xmodem(b""), 0x0000);
}
