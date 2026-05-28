//! Result-ring decoder.
//!
//! After a Mole program halts, the engine drains a fixed-size
//! buffer (`MoleConfig.resultRingByteCount` bytes, Verde default
//! 8192 = 4096 words) back to the host. This module turns that raw
//! byte buffer into typed Rust values.
//!
//! # Ring layout
//!
//! The on-engine layout is documented in the file header of
//! `fpga/Mole/src/hw/BitCycleEngineCore.scala`:
//!
//! ```text
//! word[0]              REVISION lo  (= patch[15:0])
//! word[1]              REVISION hi  (= major[15:8] | minor[7:0])
//! word[2]..word[N-2]   record stream (CAPTURE / MARK) then unused
//!                      slots holding undefined contents
//! word[N-1]            HALT terminator (tag = 0b11)
//! ```
//!
//! Where `N = ring_bytes / 2`. The HALT slot is reserved exclusively
//! for the HALT word --- an overflowing record stream can never
//! overwrite it.
//!
//! ## The "trailing garbage" hazard
//!
//! The engine only writes record-slot words it actually uses. Slots
//! between the last record and the HALT terminator are left in
//! whatever state the SPRAM came up in. On Verilator they are zero;
//! on real silicon they are undefined.
//!
//! That means [`decode_ring`] cannot tell where the record stream
//! genuinely ends. It walks from word offset 2 forward and treats
//! every word as a real record until it hits the HALT terminator. On
//! Verilator the trailing zeros decode as a run of
//! `Capture { sda: false }` records; on real silicon the gap may
//! decode as fake CAPTURE / MARK records or trip [`RingError::ReservedTag`].
//!
//! [`DecodedRing::trailing_garbage_words`] reports how many record
//! slots sat between the last record and the HALT word, so callers
//! can warn the user when the value is non-zero. A future engine
//! revision will either zero the gap at HALT entry or emit an
//! end-of-stream sentinel record, at which point this decoder can
//! become strict.

use crate::error::RingError;

/// REVISION record reported by the engine in the first two ring
/// words. Format per `fpga/Mole/src/hw/Revision.scala`:
///
/// ```text
/// [31:24] = major
/// [23:16] = minor
/// [15: 0] = patch
/// ```
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct Revision {
    /// `[31:24]` --- breaking-change tier.
    pub major: u8,
    /// `[23:16]` --- backward-compatible feature tier.
    pub minor: u8,
    /// `[15: 0]` --- patch / build tier.
    pub patch: u16,
}

impl Revision {
    /// Decode a [`Revision`] from the two-word `word_lo` / `word_hi`
    /// pair the engine writes at the head of every ring.
    ///
    /// `word_lo` is the lower 16 bits of the 32-bit revision word
    /// (i.e. the patch field); `word_hi` is the upper 16 bits
    /// (major and minor packed `[15:8]` / `[7:0]`).
    pub fn from_words(word_lo: u16, word_hi: u16) -> Self {
        Self {
            major: (word_hi >> 8) as u8,
            minor: (word_hi & 0xFF) as u8,
            patch: word_lo,
        }
    }
}

impl std::fmt::Display for Revision {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}.{}.{}", self.major, self.minor, self.patch)
    }
}

/// Parse a `"X.Y.Z"` triplet (decimal) into a [`Revision`].
///
/// Used by the CLI's `--expect-revision` flag. Major and minor are
/// `u8`; patch is `u16`. Three components are mandatory; anything
/// shorter or longer is rejected with a descriptive error.
impl std::str::FromStr for Revision {
    type Err = String;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        let parts: Vec<&str> = s.split('.').collect();
        if parts.len() != 3 {
            return Err(format!(
                "expected three dot-separated components 'major.minor.patch', got {:?}",
                s
            ));
        }
        let major: u8 = parts[0]
            .parse()
            .map_err(|e| format!("invalid major {:?}: {e}", parts[0]))?;
        let minor: u8 = parts[1]
            .parse()
            .map_err(|e| format!("invalid minor {:?}: {e}", parts[1]))?;
        let patch: u16 = parts[2]
            .parse()
            .map_err(|e| format!("invalid patch {:?}: {e}", parts[2]))?;
        Ok(Self {
            major,
            minor,
            patch,
        })
    }
}

/// One decoded record from the ring's record stream.
///
/// HALT is *not* a [`Record`] --- it terminates the stream and is
/// surfaced separately as [`DecodedRing::halt`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Record {
    /// CAPTURE record (1 word, tag `0b00`). Carries the SDA bit the
    /// engine sampled at the request point.
    Capture {
        /// The captured SDA value.
        sda: bool,
    },
    /// MARK record (3 words, tag `0b10`). Carries the 8-bit caller
    /// label and the 32-bit fabric-cycle timestamp latched at decode.
    Mark {
        /// `[7:0]` of word 0 --- caller-supplied label.
        label: u8,
        /// Words 1 and 2 packed `(hi << 16) | lo` --- free-running
        /// 32-bit counter, reset to 0 on every `io.start`. Wraps at
        /// ~179 s @ 24 MHz.
        timestamp: u32,
    },
}

/// Decoded HALT terminator.
///
/// The HALT word's bit layout (`tag = word[15:14] = 0b11`):
///
/// ```text
/// [13]    = overflow latch (ring filled before HALT)
/// [12]    = mismatchAtHalt (sticky MISMATCH_FLAG at HALT entry)
/// [11:8]  = caller status (or 0xF for engine trap)
/// [7:0]   = reserved (= 0)
/// ```
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct HaltStatus {
    /// 4-bit status code from the HALT opcode (`0x0..=0xC`
    /// caller-defined; `0xF` reserved for engine traps).
    pub status: u8,
    /// `true` if `MISMATCH_FLAG` was still set at HALT entry.
    pub mismatch: bool,
    /// `true` if the record stream overflowed the ring before HALT.
    pub overflow: bool,
}

impl HaltStatus {
    /// `true` if the status code matches an engine-internal trap
    /// (currently only `0xF` --- malformed instruction).
    pub fn is_engine_trap(&self) -> bool {
        self.status == 0xF
    }
}

/// Fully decoded ring buffer.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DecodedRing {
    /// REVISION word reported by the engine.
    pub revision: Revision,
    /// Record stream (CAPTURE / MARK), in the order written.
    pub records: Vec<Record>,
    /// Terminator at the tail slot.
    pub halt: HaltStatus,
    /// Number of unused 16-bit slots between the last record we
    /// decoded and the HALT terminator. Always 0 on a perfectly
    /// utilised ring; positive when the engine emitted fewer
    /// records than the ring can hold. See the module-level
    /// "trailing garbage hazard" note.
    pub trailing_garbage_words: usize,
}

/// Decode the engine's result-ring byte buffer.
///
/// `bytes` is expected to be exactly `MoleConfig.resultRingByteCount`
/// bytes --- the full ring the drainer always streams back. Shorter
/// buffers are rejected because the decoder cannot tell whether a
/// suffix is missing or just trimmed.
///
/// # Errors
///
/// See [`RingError`] for the full catalogue. The most common
/// failure on a real link is [`RingError::NoHaltAtTail`] --- the
/// drainer returned the wrong byte count or the serial link dropped
/// bytes.
pub fn decode_ring(bytes: &[u8]) -> Result<DecodedRing, RingError> {
    if bytes.len() % 2 != 0 {
        return Err(RingError::OddByteCount { got: bytes.len() });
    }
    if bytes.len() < 6 {
        return Err(RingError::TooShort { got: bytes.len() });
    }

    let words: Vec<u16> = bytes
        .chunks_exact(2)
        .map(|c| u16::from_le_bytes([c[0], c[1]]))
        .collect();
    let total_words = words.len();

    // REVISION lives in words 0..=1.
    let revision = Revision::from_words(words[0], words[1]);

    // HALT lives in the very last word (`resultLimit` slot).
    let halt_word = words[total_words - 1];
    if halt_word >> 14 != 0b11 {
        return Err(RingError::NoHaltAtTail { word: halt_word });
    }
    let halt = HaltStatus {
        overflow: (halt_word & (1 << 13)) != 0,
        mismatch: (halt_word & (1 << 12)) != 0,
        status: ((halt_word >> 8) & 0xF) as u8,
    };

    // Records walk from word offset 2 up to (but not including) the
    // HALT word. Stop on first HALT-tagged word (which would be the
    // tail itself in a clean ring) OR on the explicit tail index.
    let record_end_word = total_words - 1;
    let mut offset = 2usize;
    let mut records = Vec::new();
    while offset < record_end_word {
        let word = words[offset];
        let tag = word >> 14;
        match tag {
            0b00 => {
                records.push(Record::Capture {
                    sda: (word & 1) != 0,
                });
                offset += 1;
            }
            0b01 => {
                return Err(RingError::ReservedTag {
                    offset_words: offset,
                    word,
                });
            }
            0b10 => {
                let remaining = record_end_word - offset;
                if remaining < 3 {
                    return Err(RingError::TruncatedMark {
                        offset_words: offset,
                        remaining_words: remaining,
                    });
                }
                let label = (word & 0xFF) as u8;
                let ts_lo = words[offset + 1] as u32;
                let ts_hi = words[offset + 2] as u32;
                let timestamp = (ts_hi << 16) | ts_lo;
                records.push(Record::Mark { label, timestamp });
                offset += 3;
            }
            0b11 => {
                // Mid-ring HALT is never produced by the engine ---
                // the tail slot is the only legal HALT site. Treat
                // as malformed.
                return Err(RingError::UnexpectedMidRingHalt {
                    offset_words: offset,
                    word,
                });
            }
            _ => unreachable!("tag is >> 14 of a u16, only 4 possible values"),
        }
    }

    // After a perfectly-utilised ring this is 0; on a real run the
    // record stream usually stops far short of `recordLimit` and
    // the gap holds undefined contents. We have no way to detect
    // that gap from the data alone --- callers that care must rely
    // on the engine zeroing the ring (future work) or on a record-
    // count contract.
    let trailing_garbage_words = record_end_word.saturating_sub(offset);

    Ok(DecodedRing {
        revision,
        records,
        halt,
        trailing_garbage_words,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Helper: assemble a ring from typed parts.
    fn build_ring(revision: (u16, u16), records: &[u16], halt: u16, total_words: usize) -> Vec<u8> {
        assert!(total_words >= 3 + records.len());
        let mut words = Vec::with_capacity(total_words);
        words.push(revision.0);
        words.push(revision.1);
        words.extend_from_slice(records);
        // Fill the gap with zeros (mimics Verilator).
        while words.len() < total_words - 1 {
            words.push(0x0000);
        }
        words.push(halt);
        assert_eq!(words.len(), total_words);
        let mut bytes = Vec::with_capacity(total_words * 2);
        for w in words {
            bytes.push((w & 0xFF) as u8);
            bytes.push((w >> 8) as u8);
        }
        bytes
    }

    #[test]
    fn revision_from_words_field_layout() {
        // major=0x12, minor=0x34, patch=0xABCD
        // word_hi = (0x12 << 8) | 0x34 = 0x1234
        // word_lo = 0xABCD
        let rev = Revision::from_words(0xABCD, 0x1234);
        assert_eq!(
            rev,
            Revision {
                major: 0x12,
                minor: 0x34,
                patch: 0xABCD,
            }
        );
    }

    #[test]
    fn revision_display() {
        let rev = Revision {
            major: 1,
            minor: 2,
            patch: 3,
        };
        assert_eq!(rev.to_string(), "1.2.3");
    }

    #[test]
    fn revision_from_str_round_trip() {
        let rev = Revision {
            major: 9,
            minor: 12,
            patch: 0x1234,
        };
        let parsed: Revision = rev.to_string().parse().unwrap();
        assert_eq!(rev, parsed);
    }

    #[test]
    fn revision_from_str_rejects_wrong_arity() {
        assert!("1.2".parse::<Revision>().is_err());
        assert!("1.2.3.4".parse::<Revision>().is_err());
        assert!("not a version".parse::<Revision>().is_err());
    }

    #[test]
    fn revision_from_str_rejects_out_of_range() {
        // major / minor are u8; 256 overflows.
        assert!("256.0.0".parse::<Revision>().is_err());
        // patch is u16; 65536 overflows.
        assert!("0.0.65536".parse::<Revision>().is_err());
    }

    #[test]
    fn halt_status_is_engine_trap() {
        assert!(
            HaltStatus {
                status: 0xF,
                mismatch: false,
                overflow: false,
            }
            .is_engine_trap()
        );
        for s in 0x0..=0xE {
            assert!(
                !HaltStatus {
                    status: s,
                    mismatch: false,
                    overflow: false,
                }
                .is_engine_trap()
            );
        }
    }

    #[test]
    fn minimum_ring_revision_then_halt_only() {
        // Exact 3-word ring: REVISION + HALT, no records.
        // halt = tag(11) only, status 0, no overflow, no mismatch
        let bytes = build_ring((0x0001, 0x0203), &[], 0xC000, 3);
        let ring = decode_ring(&bytes).unwrap();
        assert_eq!(
            ring.revision,
            Revision {
                major: 0x02,
                minor: 0x03,
                patch: 0x0001
            }
        );
        assert!(ring.records.is_empty());
        assert_eq!(
            ring.halt,
            HaltStatus {
                status: 0,
                mismatch: false,
                overflow: false
            }
        );
        assert_eq!(ring.trailing_garbage_words, 0);
    }

    #[test]
    fn capture_records_decode_low_bit() {
        // Two CAPTUREs: sda=1 then sda=0. tag=00, body bit 0 = sda.
        let bytes = build_ring(
            (0x0000, 0x0000),
            &[0x0001, 0x0000],
            0xC000,
            5, // rev(2) + 2 records + halt(1)
        );
        let ring = decode_ring(&bytes).unwrap();
        assert_eq!(
            ring.records,
            vec![
                Record::Capture { sda: true },
                Record::Capture { sda: false }
            ]
        );
        assert_eq!(ring.trailing_garbage_words, 0);
    }

    #[test]
    fn mark_record_decode_three_words() {
        // MARK with label=0xA5, timestamp=0xDEAD_BEEF
        // word0: tag(10)|label = 0x80 | 0xA5 = 0x80A5 -> upper 2 bits
        //        are tag(10) so high byte = 0x80 ... full word 0x80A5
        // word1: timestamp_lo = 0xBEEF
        // word2: timestamp_hi = 0xDEAD
        let bytes = build_ring((0x0000, 0x0000), &[0x80A5, 0xBEEF, 0xDEAD], 0xC000, 6);
        let ring = decode_ring(&bytes).unwrap();
        assert_eq!(
            ring.records,
            vec![Record::Mark {
                label: 0xA5,
                timestamp: 0xDEAD_BEEF,
            }]
        );
    }

    #[test]
    fn halt_decodes_overflow_mismatch_status() {
        // tag=11, overflow=1, mismatch=1, status=0xA
        // 0xC000 | (1<<13) | (1<<12) | (0xA<<8) = 0xFA00
        let bytes = build_ring((0, 0), &[], 0xFA00, 3);
        let ring = decode_ring(&bytes).unwrap();
        assert_eq!(
            ring.halt,
            HaltStatus {
                status: 0xA,
                mismatch: true,
                overflow: true,
            }
        );
    }

    #[test]
    fn halt_engine_trap_status_recognised() {
        // status=0xF (malformed instruction trap)
        // 0xC000 | (0xF << 8) = 0xCF00
        let bytes = build_ring((0, 0), &[], 0xCF00, 3);
        let ring = decode_ring(&bytes).unwrap();
        assert!(ring.halt.is_engine_trap());
    }

    #[test]
    fn rejects_odd_byte_count() {
        let bytes = vec![0u8; 7];
        assert_eq!(decode_ring(&bytes), Err(RingError::OddByteCount { got: 7 }));
    }

    #[test]
    fn rejects_short_buffer() {
        assert_eq!(decode_ring(&[]), Err(RingError::TooShort { got: 0 }));
        assert_eq!(decode_ring(&[0u8; 4]), Err(RingError::TooShort { got: 4 }));
    }

    #[test]
    fn rejects_missing_halt_tail() {
        // Tail word has tag 00 (CAPTURE), not 11.
        let mut bytes = build_ring((0, 0), &[], 0xC000, 3);
        // Patch tail to 0x0000 (tag 00).
        let tail = bytes.len() - 2;
        bytes[tail] = 0x00;
        bytes[tail + 1] = 0x00;
        assert_eq!(
            decode_ring(&bytes),
            Err(RingError::NoHaltAtTail { word: 0x0000 })
        );
    }

    #[test]
    fn rejects_reserved_tag_01_in_record_stream() {
        // word at offset 2 with tag 01: 0x4000.
        let bytes = build_ring((0, 0), &[0x4001], 0xC000, 4);
        assert_eq!(
            decode_ring(&bytes),
            Err(RingError::ReservedTag {
                offset_words: 2,
                word: 0x4001,
            })
        );
    }

    #[test]
    fn rejects_truncated_mark_at_tail() {
        // MARK at offset 2; only 1 word left before the HALT slot.
        // total_words=4 => record window is [2..3) which is 1 word.
        let bytes = build_ring((0, 0), &[0x8000], 0xC000, 4);
        assert_eq!(
            decode_ring(&bytes),
            Err(RingError::TruncatedMark {
                offset_words: 2,
                remaining_words: 1,
            })
        );
    }

    #[test]
    fn rejects_truncated_mark_two_words_short() {
        // MARK at offset 2; only 2 words left before the HALT slot.
        // total_words=5 => record window is [2..4) which is 2 words.
        let bytes = build_ring((0, 0), &[0x8000, 0x0000], 0xC000, 5);
        assert_eq!(
            decode_ring(&bytes),
            Err(RingError::TruncatedMark {
                offset_words: 2,
                remaining_words: 2,
            })
        );
    }

    #[test]
    fn rejects_unexpected_mid_ring_halt() {
        // tag 11 at offset 2 (not the tail).
        let bytes = build_ring((0, 0), &[0xC000], 0xC000, 4);
        assert_eq!(
            decode_ring(&bytes),
            Err(RingError::UnexpectedMidRingHalt {
                offset_words: 2,
                word: 0xC000,
            })
        );
    }

    #[test]
    fn trailing_zeros_decode_as_capture_false_run() {
        // Verilator-style ring: 8 words, REV + REV + CAPTURE(1)
        // + 4 zero slots + HALT. The 4 zero slots decode as
        // CAPTURE { sda: false } per the documented hazard.
        let bytes = build_ring((0, 0), &[0x0001], 0xC000, 8);
        let ring = decode_ring(&bytes).unwrap();
        assert_eq!(
            ring.records,
            vec![
                Record::Capture { sda: true },
                Record::Capture { sda: false },
                Record::Capture { sda: false },
                Record::Capture { sda: false },
                Record::Capture { sda: false },
            ]
        );
        assert_eq!(ring.trailing_garbage_words, 0);
    }

    #[test]
    fn full_default_ring_size_round_trip() {
        // Verde default ring = 8192 bytes = 4096 words. Smoke-test
        // that the decoder is happy at the full size.
        let bytes = build_ring((0x1234, 0x5678), &[], 0xC000, 4096);
        let ring = decode_ring(&bytes).unwrap();
        assert_eq!(
            ring.revision,
            Revision {
                major: 0x56,
                minor: 0x78,
                patch: 0x1234,
            }
        );
        // All inter-rev / pre-halt slots are zero in our builder,
        // so they decode as CAPTURE { sda: false }.
        assert_eq!(ring.records.len(), 4096 - 3);
        assert!(
            ring.records
                .iter()
                .all(|r| *r == Record::Capture { sda: false })
        );
    }
}
