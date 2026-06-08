//! Result-ring decoder.
//!
//! After a Mole program halts, the engine drains a fixed-size
//! buffer (`MoleConfig.resultRingByteCount` bytes, Verde default
//! 8192 = 4096 16-bit words) back to the host. This module turns
//! that raw byte buffer into typed Rust values.
//!
//! # Ring layout
//!
//! The on-engine layout is documented in the file header of
//! `fpga/Mole/src/hw/BitCycleEngineCore.scala`:
//!
//! ```text
//! word[0]              REVISION lo  (= patch[15:0])
//! word[1]              REVISION hi  (= major[15:8] | minor[7:0])
//! word[2]..word[N-3]   record stream (CAPTURE / MARK) then unused
//!                      slots holding undefined contents
//! word[N-2..N-1]       HALT terminator (32-bit LE, tag = 0b11 at
//!                      bits [31:30]) serialised as two 16-bit LE
//!                      ring words
//! ```
//!
//! Where `N = ring_bytes / 2`. The HALT slot occupies the last two
//! 16-bit ring positions (`word[N-2]` lo-half, `word[N-1]` hi-half).
//!
//! ## 32-bit HALT word (§11)
//!
//! ```text
//! [31:30] tag        = 0b11  (HALT record; identifies this word
//!                             in result ring)
//! [29]    overflow   (sticky overflow latch)
//! [28]    mismatch   (snapshot of MISMATCH_FLAG at halt entry)
//! [27:23] status     (5 bits, 0x00..0x1F)
//! [22: 0] reserved   (must be 0; loader verifies)
//! ```
//!
//! On the wire the 32-bit HALT word is split into two adjacent
//! 16-bit ring slots: the low half at `word[N-2]`, the high half
//! at `word[N-1]`, both little-endian. Combine as
//! `(word_hi as u32) << 16 | (word_lo as u32)`.
//!
//! ## The "trailing garbage" hazard
//!
//! The engine only writes record-slot words it actually uses. Slots
//! between the last record and the HALT terminator are left in
//! whatever state the SPRAM came up in. On Verilator they are zero;
//! on real silicon they are undefined.
//!
//! That means [`decode_ring`] cannot tell *exactly* where the
//! record stream genuinely ends. The decoder uses the simplest
//! heuristic available: it walks from word offset 2 forward and
//! stops at the first word whose tag is not a legal record tag
//! (`0b01` reserved, or `0b11` HALT seen before the tail slot).
//! Both signal "engine stopped writing here, garbage begins"
//! reliably enough for real-silicon use, and the HALT integrity
//! check at the tail confirms we didn't simply lose framing.
//!
//! Limitation: a garbage word that happens to carry tag `0b00`
//! (CAPTURE) is electrically indistinguishable from a real
//! capture --- the decoder will emit it as `Capture { sda: bit0 }`.
//! A garbage word that happens to carry tag `0b10` (MARK) plus
//! two more garbage words after it will look like a real MARK
//! record with arbitrary label and timestamp. The only reliable
//! fix is for the engine to either zero the gap at HALT entry or
//! emit an end-of-stream sentinel record; tracked in the engine
//! roadmap.
//!
//! [`DecodedRing::trailing_garbage_words`] reports how many
//! record slots sat between where the decoder stopped and the
//! HALT word, so callers can warn the user when the value is
//! non-zero.

use crate::error::RingError;
use mole_abi::{halt, record_tag, record_width_words, revision};

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
        let word = ((word_hi as u32) << 16) | (word_lo as u32);
        let (major, minor, patch) = revision::unpack(word);
        Self {
            major,
            minor,
            patch,
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
                "expected three dot-separated components 'major.minor.patch', \
                 got {:?}",
                s
            ));
        }
        // libstd's `u8::from_str` / `u16::from_str` accept a leading
        // `+` (e.g. "+1" parses as 1). For a version triple that is
        // surprising --- "+1.0.0" should not silently become "1.0.0"
        // --- so reject any component whose first byte is not an
        // ASCII digit. This keeps the contract "decimal digits only,
        // no sign, no whitespace, no underscores".
        fn check_digits_only(field: &str, tok: &str) -> Result<(), String> {
            match tok.as_bytes().first() {
                None => Err(format!("invalid {field}: empty component")),
                Some(b) if !b.is_ascii_digit() => Err(format!(
                    "invalid {field} {tok:?}: must start with an ASCII \
                     digit (no leading sign or whitespace)"
                )),
                Some(_) => Ok(()),
            }
        }
        check_digits_only("major", parts[0])?;
        check_digits_only("minor", parts[1])?;
        check_digits_only("patch", parts[2])?;
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
/// The HALT word's v0.2 bit layout (§11 lines 1458-1492):
///
/// ```text
/// [31:30] = 0b11     (tag = HALT)
/// [29]    = overflow latch
/// [28]    = mismatchAtHalt (sticky MISMATCH_FLAG at HALT entry)
/// [27:23] = caller status (5 bits; 0x1F for engine trap)
/// [22: 0] = reserved (= 0)
/// ```
///
/// The strict decoder validates `[22:0] == 0` and `status ∉
/// {0x1D, 0x1E}` per INV-WIRE-HALT-RECORD +
/// INV-NUM-STATUS-RESERVED. See
/// [`RingError::HaltReservedBitsSet`] and
/// [`RingError::HaltStatusReserved`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct HaltStatus {
    /// 5-bit status code from the HALT opcode (`0x00..=0x1C`
    /// caller-defined; `0x1F` reserved for engine traps).
    pub status: u8,
    /// `true` if `MISMATCH_FLAG` was still set at HALT entry.
    pub mismatch: bool,
    /// `true` if the record stream overflowed the ring before HALT.
    pub overflow: bool,
}

impl HaltStatus {
    /// `true` if the status code matches an engine-internal trap
    /// (currently only `0x1F` --- malformed instruction).
    pub fn is_engine_trap(&self) -> bool {
        self.status == halt::STATUS_TRAP
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
/// **This function does *not* enforce that `bytes.len()` equals the
/// configured ring size.** It happily decodes any buffer whose length
/// is a non-zero even number ≥ 8 and whose last two words form a
/// valid HALT word. For production reads --- where you know exactly
/// how many bytes you asked the engine to drain --- use
/// [`decode_ring_strict`] instead, which rejects any
/// length-vs-expected mismatch before parsing. The lax form is kept
/// for tests and tooling that synthesise short rings on purpose.
///
/// # Ring minimum size
///
/// Minimum: 8 bytes = 4 × 16-bit words:
/// - word[0]: REVISION lo
/// - word[1]: REVISION hi
/// - word[2]: HALT lo (low 16 bits of 32-bit HALT word)
/// - word[3]: HALT hi (high 16 bits of 32-bit HALT word)
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
    // Minimum ring: 2 REVISION words + 2 HALT words = 4 × 16-bit = 8 bytes.
    if bytes.len() < 8 {
        return Err(RingError::TooShort { got: bytes.len() });
    }

    let words: Vec<u16> = bytes
        .chunks_exact(2)
        .map(|c| u16::from_le_bytes([c[0], c[1]]))
        .collect();
    let total_words = words.len();

    // REVISION lives in words 0..=1.
    let revision = Revision::from_words(words[0], words[1]);

    // HALT occupies the last two 16-bit ring positions. Combine
    // little-endian: low half at [N-2], high half at [N-1].
    let halt_lo = words[total_words - 2] as u32;
    let halt_hi = words[total_words - 1] as u32;
    let halt_word: u32 = (halt_hi << 16) | halt_lo;

    if (halt_word >> halt::TAG_SHIFT) & halt::TAG_MASK != halt::TAG_HALT {
        // Report the 16-bit hi-half as the "word" for compatibility
        // with the error type (NoHaltAtTail stores the tail word).
        return Err(RingError::NoHaltAtTail {
            word: words[total_words - 1],
        });
    }
    // Reject structurally invalid HALT words first (reserved low 23
    // bits must be zero per INV-WIRE-HALT-RECORD); then reject
    // semantically reserved status codes (0x1D, 0x1E per
    // INV-NUM-STATUS-RESERVED). Both carry the original `halt_word`
    // for forensic value. 0x1F is the documented engine-trap code
    // and passes through to `HaltStatus` where `is_engine_trap()`
    // surfaces it.
    let reserved = halt_word & halt::RESERVED_BITS_MASK;
    if reserved != 0 {
        return Err(RingError::HaltReservedBitsSet {
            halt_word,
            reserved,
        });
    }
    let status = ((halt_word >> halt::STATUS_SHIFT) & halt::STATUS_MASK) as u8;
    if status == halt::STATUS_RESERVED_LOW || status == halt::STATUS_RESERVED_HIGH {
        return Err(RingError::HaltStatusReserved { status, halt_word });
    }
    let halt = HaltStatus {
        overflow: (halt_word & (1 << halt::OVERFLOW_BIT)) != 0,
        mismatch: (halt_word & (1 << halt::MISMATCH_BIT)) != 0,
        status,
    };

    // Records walk from word offset 2 up to (but not including) the
    // first HALT word (word[N-2]). The engine writes a contiguous
    // record stream starting at offset 2 and stops at whatever
    // `resultWp` reached when the program halted. Slots from
    // `resultWp` up to `resultLimit - 3` hold *uninitialised SPRAM*
    // on real silicon (zero only under Verilator) --- see the
    // module-level "trailing-garbage hazard" callout.
    //
    // We therefore stop walking at the first word whose tag is not
    // a legal record tag (`0b01` reserved, or `0b11` HALT mid-
    // stream): both signal "engine stopped writing here, garbage
    // begins". The remainder is reported via
    // `trailing_garbage_words` so callers can warn the user. The
    // HALT integrity check at the tail (above) guarantees we
    // didn't simply lose framing.
    //
    // Record-stream walk ends two words before the tail (the two
    // 16-bit HALT slots are not record data).
    let record_end_word = total_words - 2; // index of halt_lo slot
    let mut offset = 2usize;
    let mut records = Vec::new();
    while offset < record_end_word {
        let word = words[offset];
        // Tags are the top 2 bits of the 16-bit ring word; the
        // record_tag constants use bits [31:30] of the ABI's 32-bit
        // representation, so shift down by 14 to get the top 2 bits
        // of the 16-bit ring word into the low 2 bits for matching.
        let tag = ((word >> 14) as u32) & record_tag::MASK;
        match tag {
            record_tag::CAPTURE => {
                records.push(Record::Capture {
                    sda: (word & 1) != 0,
                });
                offset += record_width_words::CAPTURE;
            }
            record_tag::MARK => {
                let remaining = record_end_word - offset;
                if remaining < record_width_words::MARK {
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
                offset += record_width_words::MARK;
            }
            record_tag::RESERVED | record_tag::HALT => {
                // Reserved tag mid-stream OR HALT-tagged word
                // before the tail slot. Either way the engine
                // stopped writing real records at `offset`; the
                // remainder is uninitialised SPRAM. Stop walking
                // and bookkeep the gap.
                break;
            }
            _ => unreachable!("tag is 2 bits, only 4 possible values"),
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

/// Strict wrapper around [`decode_ring`] that first checks
/// `bytes.len() == expected_bytes`.
///
/// Use this in production read paths --- the host always knows the
/// configured `resultRingByteCount` (see
/// [`crate::transport::DEFAULT_RING_BYTES`]) and a length mismatch
/// is the single loudest signal that the drain went sideways
/// (wrong `--ring-bytes`, serial link dropped framing, host's
/// kernel buffer kept a stale prefix from the previous run, etc.).
/// The lax [`decode_ring`] cannot detect a prepended-bytes prefix
/// or a truncated suffix as long as the resulting tail still
/// happens to carry a HALT-tagged word; this wrapper closes that
/// hole at the cost of one extra `usize` argument.
///
/// # Errors
///
/// [`RingError::UnexpectedByteCount`] if the input is the wrong
/// size; otherwise whatever [`decode_ring`] would return.
pub fn decode_ring_strict(bytes: &[u8], expected_bytes: usize) -> Result<DecodedRing, RingError> {
    if bytes.len() != expected_bytes {
        return Err(RingError::UnexpectedByteCount {
            got: bytes.len(),
            expected: expected_bytes,
        });
    }
    decode_ring(bytes)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Helper: assemble a ring from typed parts.
    ///
    /// `halt_word` is the 32-bit HALT value; it is split into two
    /// 16-bit ring slots (lo at `[N-2]`, hi at `[N-1]`).
    /// `total_words` is the total number of 16-bit ring slots.
    fn build_ring(
        revision: (u16, u16),
        records: &[u16],
        halt_word: u32,
        total_words: usize,
    ) -> Vec<u8> {
        // Need room for: 2 REVISION + records + (optional gap) + 2 HALT slots.
        assert!(total_words >= 4 + records.len());
        let halt_lo = (halt_word & 0xFFFF) as u16;
        let halt_hi = (halt_word >> 16) as u16;
        let mut words = Vec::with_capacity(total_words);
        words.push(revision.0);
        words.push(revision.1);
        words.extend_from_slice(records);
        // Fill the gap with zeros (mimics Verilator).
        while words.len() < total_words - 2 {
            words.push(0x0000);
        }
        words.push(halt_lo);
        words.push(halt_hi);
        assert_eq!(words.len(), total_words);
        let mut bytes = Vec::with_capacity(total_words * 2);
        for w in words {
            bytes.push((w & 0xFF) as u8);
            bytes.push((w >> 8) as u8);
        }
        bytes
    }

    /// Build a minimal HALT word with the given status (tag=0b11,
    /// no overflow, no mismatch, reserved=0).
    const fn halt_word_status(status: u8) -> u32 {
        (halt::TAG_HALT << halt::TAG_SHIFT) | ((status as u32) << halt::STATUS_SHIFT)
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
    fn revision_from_str_rejects_leading_plus() {
        // Regression: libstd's `u8::from_str` / `u16::from_str`
        // accept a leading `+`, so without an explicit check
        // "+1.0.0" parses to 1.0.0. We require strict decimal-digit
        // components --- no leading sign --- so callers cannot
        // confuse themselves on the CLI's `--expect-revision` flag.
        assert!("+1.0.0".parse::<Revision>().is_err());
        assert!("1.+0.0".parse::<Revision>().is_err());
        assert!("1.0.+0".parse::<Revision>().is_err());
        // The negative-rejection cases already covered upstream
        // (libstd refuses `-` for unsigned types) are re-pinned
        // here to keep the rejection contract visible in one place.
        assert!("-1.0.0".parse::<Revision>().is_err());
    }

    #[test]
    fn halt_status_is_engine_trap() {
        assert!(
            HaltStatus {
                status: halt::STATUS_TRAP,
                mismatch: false,
                overflow: false,
            }
            .is_engine_trap()
        );
        // STATUS_TRAP = 0x1F; all others below it are not traps.
        for s in 0x0..halt::STATUS_TRAP {
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
        // Exact 4-word ring: REVISION lo + REVISION hi + HALT lo +
        // HALT hi; no records.
        // halt status=0, no overflow, no mismatch:
        // 0xC000_0000 = tag(11) at [31:30], rest 0.
        let halt = halt_word_status(0);
        let bytes = build_ring((0x0001, 0x0203), &[], halt, 4);
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
        // Two CAPTUREs: sda=1 then sda=0.
        // CAPTURE tag = 0b00, so any word with top 2 bits = 00 is a
        // CAPTURE. sda is bit 0.
        let halt = halt_word_status(0);
        let bytes = build_ring(
            (0x0000, 0x0000),
            &[0x0001, 0x0000],
            halt,
            6, // rev(2) + 2 records + halt(2)
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
        // MARK tag = 0b10 in top 2 bits of a 16-bit word:
        // top2=10 → 0x80xx format. word0: 0x80A5
        // word1: timestamp_lo = 0xBEEF
        // word2: timestamp_hi = 0xDEAD
        let halt = halt_word_status(0);
        let bytes = build_ring((0x0000, 0x0000), &[0x80A5, 0xBEEF, 0xDEAD], halt, 7);
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
        // Using ABI constants: STATUS_SHIFT=23, OVERFLOW_BIT=29,
        // MISMATCH_BIT=28, TAG_SHIFT=30.
        let halt = (halt::TAG_HALT << halt::TAG_SHIFT)
            | (1 << halt::OVERFLOW_BIT)
            | (1 << halt::MISMATCH_BIT)
            | (0xAu32 << halt::STATUS_SHIFT);
        let bytes = build_ring((0, 0), &[], halt, 4);
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
        // status=0x1F (STATUS_TRAP; malformed instruction)
        let halt = halt_word_status(halt::STATUS_TRAP);
        let bytes = build_ring((0, 0), &[], halt, 4);
        let ring = decode_ring(&bytes).unwrap();
        assert!(ring.halt.is_engine_trap());
    }

    #[test]
    fn halt_user_max_status_passes() {
        // STATUS_USER_MAX = 0x1C is the highest user-defined status.
        let halt = halt_word_status(halt::STATUS_USER_MAX);
        let bytes = build_ring((0, 0), &[], halt, 4);
        let ring = decode_ring(&bytes).unwrap();
        assert_eq!(ring.halt.status, halt::STATUS_USER_MAX);
        assert!(!ring.halt.is_engine_trap());
    }

    #[test]
    fn halt_reserved_status_low_rejected() {
        // STATUS_RESERVED_LOW = 0x1D should be rejected.
        let halt = halt_word_status(halt::STATUS_RESERVED_LOW);
        let bytes = build_ring((0, 0), &[], halt, 4);
        let err = decode_ring(&bytes).unwrap_err();
        assert!(matches!(
            err,
            RingError::HaltStatusReserved {
                status: s,
                ..
            } if s == halt::STATUS_RESERVED_LOW
        ));
    }

    #[test]
    fn halt_reserved_status_high_rejected() {
        // STATUS_RESERVED_HIGH = 0x1E should be rejected.
        let halt = halt_word_status(halt::STATUS_RESERVED_HIGH);
        let bytes = build_ring((0, 0), &[], halt, 4);
        let err = decode_ring(&bytes).unwrap_err();
        assert!(matches!(
            err,
            RingError::HaltStatusReserved {
                status: s,
                ..
            } if s == halt::STATUS_RESERVED_HIGH
        ));
    }

    #[test]
    fn halt_reserved_bits_set_rejected() {
        // Set bit 0 in the reserved [22:0] range.
        let halt = halt_word_status(0) | 0x0000_0001u32;
        let bytes = build_ring((0, 0), &[], halt, 4);
        let err = decode_ring(&bytes).unwrap_err();
        assert!(matches!(err, RingError::HaltReservedBitsSet { .. }));
    }

    #[test]
    fn rejects_odd_byte_count() {
        let bytes = vec![0u8; 7];
        assert_eq!(decode_ring(&bytes), Err(RingError::OddByteCount { got: 7 }));
    }

    #[test]
    fn rejects_short_buffer() {
        assert_eq!(decode_ring(&[]), Err(RingError::TooShort { got: 0 }));
        assert_eq!(decode_ring(&[0u8; 6]), Err(RingError::TooShort { got: 6 }));
    }

    #[test]
    fn rejects_missing_halt_tail() {
        // Tail word has hi-half = 0x0000 (tag 00 at [31:30]), not 11.
        let halt = halt_word_status(0);
        let mut bytes = build_ring((0, 0), &[], halt, 4);
        // Patch the HALT hi-half to 0x0000 (removes tag).
        let hi_half_byte = bytes.len() - 2;
        bytes[hi_half_byte] = 0x00;
        bytes[hi_half_byte + 1] = 0x00;
        // The tail 16-bit word (hi-half) is 0x0000.
        assert_eq!(
            decode_ring(&bytes),
            Err(RingError::NoHaltAtTail { word: 0x0000 })
        );
    }

    #[test]
    fn reserved_tag_01_terminates_record_stream_as_trailing_garbage() {
        // word at offset 2 with tag 01 (= 0x4001 in 16-bit ring word,
        // top 2 bits = 01): this is the real-silicon failure mode the
        // tmp108 fixture hit. The decoder must treat that as "garbage
        // begins here" rather than fail, but still honour the
        // HALT-at-tail integrity check.
        let halt = halt_word_status(0);
        let bytes = build_ring((0, 0), &[0x4001], halt, 5);
        let ring = decode_ring(&bytes).unwrap();
        assert!(ring.records.is_empty());
        assert_eq!(ring.trailing_garbage_words, 1);
        assert_eq!(ring.halt.status, 0);
    }

    #[test]
    fn rejects_truncated_mark_at_tail() {
        // MARK at offset 2; only 1 word left before the HALT slots.
        // total_words=5 => record window is [2..3) which is 1 word.
        let halt = halt_word_status(0);
        let bytes = build_ring((0, 0), &[0x8000], halt, 5);
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
        // MARK at offset 2; only 2 words left before the HALT slots.
        // total_words=6 => record window is [2..4) which is 2 words.
        let halt = halt_word_status(0);
        let bytes = build_ring((0, 0), &[0x8000, 0x0000], halt, 6);
        assert_eq!(
            decode_ring(&bytes),
            Err(RingError::TruncatedMark {
                offset_words: 2,
                remaining_words: 2,
            })
        );
    }

    #[test]
    fn mid_ring_halt_tag_terminates_record_stream_as_trailing_garbage() {
        // A 16-bit word with top 2 bits = 11 at offset 2 (before the
        // actual HALT slots). The record walker treats tag 0b11
        // mid-stream as "garbage begins here" and stops walking.
        // 0xC000 in 16-bit = tag 11 in top 2 bits.
        let halt = halt_word_status(0);
        let bytes = build_ring((0, 0), &[0xC000], halt, 5);
        let ring = decode_ring(&bytes).unwrap();
        assert!(ring.records.is_empty());
        assert_eq!(ring.trailing_garbage_words, 1);
    }

    #[test]
    fn tmp108_shaped_real_silicon_ring_decodes_to_19_captures() {
        // Regression test for the tmp108 hardware bring-up failure.
        // The program emits 19 `EMIT_BIT ... capture=1` (3 slave
        // ACKs + 8 MSB + 8 LSB), then HALT. On real silicon slots
        // 21..(resultLimit - 3) hold uninitialised SPRAM; the
        // decoder must stop at the first garbage word and not trip
        // on its tag bits.
        let halt = halt_word_status(0);
        let captures: Vec<u16> = (0..19).map(|i| (i as u16) & 1).collect();
        // 4096 total ring words (8192 bytes ring).
        let mut bytes = build_ring((0, 0), &captures, halt, 4096);
        // Drop the garbage word at offset 21 (the first slot past
        // the last real capture) to the same value real silicon
        // produced on the user's run.
        let garbage_byte_offset = 21 * 2;
        bytes[garbage_byte_offset] = 0x0e;
        bytes[garbage_byte_offset + 1] = 0x63;
        let ring = decode_ring(&bytes).unwrap();
        assert_eq!(ring.records.len(), 19);
        assert_eq!(ring.halt.status, 0);
        // 4092 record slots total (4096 - 2 REVISION - 2 HALT), 19
        // consumed; the rest is trailing garbage.
        assert_eq!(ring.trailing_garbage_words, 4092 - 19);
    }

    #[test]
    fn trailing_zeros_decode_as_capture_false_run() {
        // Verilator-style ring: 8 words total, REV(2) + CAPTURE(1)
        // + 3 zero slots + HALT(2). The 3 zero slots decode as
        // CAPTURE { sda: false } per the documented hazard.
        let halt = halt_word_status(0);
        let bytes = build_ring((0, 0), &[0x0001], halt, 8);
        let ring = decode_ring(&bytes).unwrap();
        assert_eq!(
            ring.records,
            vec![
                Record::Capture { sda: true },
                Record::Capture { sda: false },
                Record::Capture { sda: false },
                Record::Capture { sda: false },
            ]
        );
        assert_eq!(ring.trailing_garbage_words, 0);
    }

    #[test]
    fn decode_ring_strict_rejects_size_mismatch() {
        // A perfectly-valid 4-word ring (REVISION + HALT) handed to
        // `decode_ring_strict` with a 4096-word expectation must be
        // rejected.
        let halt = halt_word_status(0);
        let bytes = build_ring((0, 0), &[], halt, 4);
        let err = decode_ring_strict(&bytes, 8192).unwrap_err();
        assert_eq!(
            err,
            RingError::UnexpectedByteCount {
                got: 8,
                expected: 8192,
            }
        );
        // Exact match passes through and decodes identically to the
        // lax form.
        assert_eq!(
            decode_ring_strict(&bytes, bytes.len()).unwrap(),
            decode_ring(&bytes).unwrap(),
        );
    }

    #[test]
    fn decode_ring_strict_catches_prefix_garbage() {
        // The capability-gap test in tests/adversarial.rs documents
        // that `decode_ring` happily accepts a prepended-prefix
        // buffer if the tail still tags as HALT. The strict wrapper
        // is exactly the fix for production reads.
        let halt = halt_word_status(0);
        let inner = build_ring((0x0001, 0x0203), &[], halt, 4);
        let mut buf = vec![0xAA, 0xBB];
        buf.extend_from_slice(&inner);
        // Lax accepts (documented hazard).
        assert!(decode_ring(&buf).is_ok());
        // Strict refuses because buf.len() != expected ring size.
        let err = decode_ring_strict(&buf, inner.len()).unwrap_err();
        assert!(matches!(err, RingError::UnexpectedByteCount { .. }));
    }

    #[test]
    fn full_default_ring_size_round_trip() {
        // Verde default ring = 8192 bytes = 4096 words. Smoke-test
        // that the decoder is happy at the full size.
        let halt = halt_word_status(0);
        let bytes = build_ring((0x1234, 0x5678), &[], halt, 4096);
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
        // Record window = 4096 - 2 (REV) - 2 (HALT) = 4092 words.
        assert_eq!(ring.records.len(), 4096 - 4);
        assert!(
            ring.records
                .iter()
                .all(|r| *r == Record::Capture { sda: false })
        );
    }
}
