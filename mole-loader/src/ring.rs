//! Result-ring decoder.
//!
//! After a Mole program halts, the engine drains a fixed-size byte
//! buffer (`MoleConfig.resultRingByteCount` bytes, Verde default
//! 8192 = **2048 32-bit words**) back to the host. This module turns
//! that raw byte buffer into typed Rust values.
//!
//! # Ring layout (spec §11.1)
//!
//! ```text
//! word[0]                              REVISION word
//! word[1] .. word[resultLimit-1]       record stream (CAPTURE / MARK)
//!                                      followed by uninitialised SPRAM
//!                                      ("trailing garbage", see below)
//! word[resultLimit] (= last word)      HALT terminator
//! ```
//!
//! Where `resultLimit = (resultRingByteCount / 4) - 1` (the index of
//! the last 32-bit ring word). Every record is a whole number of
//! 32-bit words, drained as 4 little-endian bytes per word (b0 = LSB
//! first). The result ring is **not** half-word grained; the only
//! 16-bit-wide quantity in the host-link protocol is the CRC-16/XMODEM
//! frame checksum.
//!
//! ## 32-bit record words
//!
//! | Tag at `[31:30]` | Record   | Width  | Body                                       |
//! |------------------|----------|--------|--------------------------------------------|
//! | `0b00`           | CAPTURE  | 1 word | sda at `[0]`, `[29:1]` reserved-zero       |
//! | `0b01`           | reserved | -      | never emitted by a current engine          |
//! | `0b10`           | MARK     | 3 word | header (label) + ts_lo + ts_hi             |
//! | `0b11`           | HALT     | 1 word | overflow/mismatch/status, at `resultLimit` |
//!
//! HALT word layout (spec §11.5):
//!
//! ```text
//! [31:30] tag        = 0b11
//! [29]    overflow   (sticky overflow latch)
//! [28]    mismatch   (snapshot of MISMATCH_FLAG at halt entry)
//! [27:23] status     (5 bits, 0x00..0x1F)
//! [22: 0] reserved   (must be 0; loader verifies)
//! ```
//!
//! ## The "trailing garbage" hazard
//!
//! The engine writes only the record-stream slots it actually uses.
//! Slots between the last real record and `word[resultLimit]` hold
//! whatever the SPRAM came up in. On Verilator they are zero; on real
//! silicon they are undefined.
//!
//! That means [`decode_ring`] cannot tell *exactly* where the record
//! stream genuinely ends. The decoder walks from word offset 1
//! (post-REVISION) forward and stops at the first word whose tag is
//! not a legal record tag (`0b01` reserved, or `0b11` HALT seen before
//! the tail slot). Both signal "engine stopped writing here, garbage
//! begins". The HALT integrity check at the tail confirms framing.
//!
//! Limitation: a garbage word that happens to carry tag `0b00`
//! (CAPTURE) is indistinguishable from a real capture --- the decoder
//! will emit it as `Capture { sda: bit0 }`. A garbage word that
//! happens to carry tag `0b10` (MARK) plus two more garbage words
//! after it will look like a real MARK record with arbitrary label
//! and timestamp. The only reliable fix is for the engine to either
//! zero the gap at HALT entry or emit an end-of-stream sentinel
//! record; tracked in the engine roadmap.
//!
//! [`DecodedRing::trailing_garbage_words`] reports how many record
//! slots sat between where the decoder stopped and the HALT word, so
//! callers can warn the user when the value is non-zero.

use crate::error::RingError;
use mole_abi::{capture, halt, mark, record_tag, record_width_words, result_ring};

/// REVISION record reported by the engine in `word[0]` of every ring.
/// Format per `fpga/Mole/src/hw/Revision.scala` and spec §11.2:
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
    /// Decode a [`Revision`] from the 32-bit REVISION word at
    /// `word[0]` of every result ring.
    pub fn from_word(word: u32) -> Self {
        let (major, minor, patch) = mole_abi::revision::unpack(word);
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
    /// CAPTURE record (1 word, tag `0b00`, spec §11.3). Carries the
    /// SDA bit the engine sampled at the request point.
    Capture {
        /// The captured SDA value.
        sda: bool,
    },
    /// MARK record (3 words, tag `0b10`, spec §11.4). Carries the
    /// 14-bit caller label and the 32-bit fabric-cycle timestamp
    /// latched at decode.
    Mark {
        /// `[13:0]` of word 0 --- caller-supplied 14-bit label.
        label: u16,
        /// Words 1 and 2: low 16 bits of each carry the timestamp
        /// halves; high 16 bits of each are reserved-zero. Combined
        /// as `(ts_hi << 16) | ts_lo` to give a 32-bit free-running
        /// counter (resets on every `io.start`, wraps at ~179 s @
        /// 24 MHz).
        timestamp: u32,
    },
}

/// Decoded HALT terminator (spec §11.5).
///
/// The HALT word's v0.2 bit layout:
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
    /// REVISION word reported by the engine at `word[0]`.
    pub revision: Revision,
    /// Record stream (CAPTURE / MARK), in the order written.
    pub records: Vec<Record>,
    /// Terminator at the tail slot.
    pub halt: HaltStatus,
    /// Number of unused 32-bit slots between the last record we
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
/// is a non-zero multiple of 4 and ≥ 8 bytes and whose last word forms
/// a valid HALT word. For production reads --- where you know exactly
/// how many bytes you asked the engine to drain --- use
/// [`decode_ring_strict`] instead, which rejects any
/// length-vs-expected mismatch before parsing. The lax form is kept
/// for tests and tooling that synthesise short rings on purpose.
///
/// # Ring minimum size
///
/// Minimum: 8 bytes = 2 × 32-bit words:
/// - word[0]: REVISION
/// - word[1]: HALT (= `word[resultLimit]` when there is no record
///   stream space, i.e. `resultLimit = 1`)
///
/// # Errors
///
/// See [`RingError`] for the full catalogue. The most common
/// failure on a real link is [`RingError::NoHaltAtTail`] --- the
/// drainer returned the wrong byte count or the serial link dropped
/// bytes.
pub fn decode_ring(bytes: &[u8]) -> Result<DecodedRing, RingError> {
    if bytes.len() % 4 != 0 {
        return Err(RingError::OddByteCount { got: bytes.len() });
    }
    // Minimum ring: 1 REVISION word + 1 HALT word = 2 × 32-bit = 8 bytes.
    if bytes.len() < 8 {
        return Err(RingError::TooShort { got: bytes.len() });
    }

    let words: Vec<u32> = bytes
        .chunks_exact(4)
        .map(|c| u32::from_le_bytes([c[0], c[1], c[2], c[3]]))
        .collect();
    let total_words = words.len();

    // REVISION lives at word[0].
    let revision = Revision::from_word(words[result_ring::REVISION_OFFSET_WORDS]);

    // HALT occupies the last 32-bit ring slot.
    let halt_word = words[total_words - 1];

    if (halt_word >> halt::TAG_SHIFT) & halt::TAG_MASK != halt::TAG_HALT {
        return Err(RingError::NoHaltAtTail { word: halt_word });
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
    let halt_decoded = HaltStatus {
        overflow: (halt_word & (1 << halt::OVERFLOW_BIT)) != 0,
        mismatch: (halt_word & (1 << halt::MISMATCH_BIT)) != 0,
        status,
    };

    // Records walk from word offset 1 (post-REVISION) up to (but
    // not including) the HALT word at `word[total_words - 1]`. The
    // engine writes a contiguous record stream starting at offset 1
    // and stops at whatever `resultWp` reached when the program
    // halted. Slots from `resultWp` up to `resultLimit - 1` hold
    // either:
    //   - the SENTINEL word (`mole_abi::result_ring::SENTINEL` =
    //     `0x4000_0000`, reserved record tag `0b01`) — the normal
    //     case on every program after the first, because the engine
    //     sentinel-fills the entire result region on every
    //     `programStart` (spec §11.7); or
    //   - uninitialised SPRAM (zero under Verilator, undefined on
    //     real silicon) — only on the very first program after
    //     FPGA reset, where the engine's `sentinelFillActive`
    //     register initialised False to avoid back-pressuring the
    //     loader phase (spec §11.7 cold-boot exception).
    //
    // Either way, we stop walking at the first word whose tag is
    // not a legal record tag (`0b01` reserved, includes SENTINEL,
    // or `0b11` HALT mid-stream): both signal "engine stopped
    // writing here, garbage begins". The remainder is reported via
    // `trailing_garbage_words` so callers can warn the user. The
    // HALT integrity check at the tail (above) guarantees we
    // didn't simply lose framing.
    let record_end_word = total_words - 1; // index of HALT slot
    let mut offset = result_ring::RECORD_STREAM_OFFSET_WORDS;
    let mut records = Vec::new();
    while offset < record_end_word {
        let word = words[offset];
        let tag = (word >> record_tag::SHIFT) & record_tag::MASK;
        match tag {
            record_tag::CAPTURE => {
                records.push(Record::Capture {
                    sda: ((word >> capture::SDA_BIT) & 1) != 0,
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
                let label = ((word >> mark::LABEL_SHIFT) & mark::LABEL_MASK) as u16;
                // ts_lo and ts_hi each carry their useful bits in
                // the low 16 of the 32-bit ring word; the high 16
                // are reserved-zero per spec §11.4. Combine the low
                // halves into a 32-bit timestamp.
                let ts_lo = words[offset + 1] & 0xFFFF;
                let ts_hi = words[offset + 2] & 0xFFFF;
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
        halt: halt_decoded,
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

    /// Helper: assemble a ring from typed parts. v0.2 32-bit grain.
    ///
    /// `revision` is the full 32-bit REVISION word at `word[0]`.
    /// `records` is a slice of 32-bit ring words for the record
    /// stream (caller supplies the encoding directly).
    /// `halt_word` is the 32-bit HALT word at `word[total_words - 1]`.
    /// `total_words` is the total number of 32-bit ring slots.
    fn build_ring(revision: u32, records: &[u32], halt_word: u32, total_words: usize) -> Vec<u8> {
        // Need room for: 1 REVISION + records + (optional gap) + 1 HALT slot.
        assert!(total_words >= 2 + records.len());
        let mut words: Vec<u32> = Vec::with_capacity(total_words);
        words.push(revision);
        words.extend_from_slice(records);
        // Fill the gap with zeros (mimics Verilator).
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

    /// Build a minimal HALT word with the given status (tag=0b11,
    /// no overflow, no mismatch, reserved=0).
    const fn halt_word_status(status: u8) -> u32 {
        (halt::TAG_HALT << halt::TAG_SHIFT) | ((status as u32) << halt::STATUS_SHIFT)
    }

    /// Build a CAPTURE record word: tag=0b00 at [31:30], sda at [0],
    /// [29:1] reserved-zero. Equivalent to engine code at
    /// `EnginePipeline.scala` (the WIRE mini-FSM's `xWireWriteData`).
    const fn capture_word(sda: bool) -> u32 {
        if sda { 1u32 } else { 0u32 }
    }

    /// Build a MARK header word: tag=0b10 at [31:30], 14-bit label
    /// at [13:0], [29:14] reserved-zero.
    const fn mark_header_word(label: u16) -> u32 {
        (record_tag::MARK << record_tag::SHIFT) | (label as u32 & mark::LABEL_MASK)
    }

    /// Build a MARK timestamp half-word: low 16 bits of the timestamp
    /// at [15:0], [31:16] reserved-zero. The engine's MARK ts_lo /
    /// ts_hi format (spec §11.4).
    const fn mark_ts_word(half: u16) -> u32 {
        half as u32
    }

    #[test]
    fn revision_from_word_field_layout() {
        // major=0x12, minor=0x34, patch=0xABCD
        let word: u32 = (0x12u32 << 24) | (0x34u32 << 16) | 0xABCD;
        let rev = Revision::from_word(word);
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
        // Exact 2-word ring (8 bytes): REVISION + HALT; no records.
        // halt status=0, no overflow, no mismatch:
        // 0xC000_0000 = tag(11) at [31:30], rest 0.
        let halt = halt_word_status(0);
        let bytes = build_ring(mole_abi::revision::pack(0x02, 0x03, 0x0001), &[], halt, 2);
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
        // CAPTURE tag = 0b00 at [31:30]; sda is bit 0.
        let halt = halt_word_status(0);
        let bytes = build_ring(
            0,
            &[capture_word(true), capture_word(false)],
            halt,
            4, // rev(1) + 2 records + halt(1)
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
        // MARK with label=0xA5, timestamp=0xDEAD_BEEF.
        // Header: tag=0b10 at [31:30], label at [13:0]: 0x8000_00A5.
        // ts_lo: low 16 of 0xDEAD_BEEF = 0xBEEF; word = 0x0000_BEEF.
        // ts_hi: high 16 of 0xDEAD_BEEF = 0xDEAD; word = 0x0000_DEAD.
        let halt = halt_word_status(0);
        let bytes = build_ring(
            0,
            &[
                mark_header_word(0xA5),
                mark_ts_word(0xBEEF),
                mark_ts_word(0xDEAD),
            ],
            halt,
            5, // rev(1) + mark(3) + halt(1)
        );
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
    fn mark_record_decode_14_bit_label() {
        // MARK with full 14-bit label (0x3FFF). Verifies the
        // host doesn't truncate to 8 bits (regression: pre-Phase-A
        // host had `label: u8` and `& 0xFF`).
        let halt = halt_word_status(0);
        let bytes = build_ring(
            0,
            &[
                mark_header_word(0x3FFF),
                mark_ts_word(0x0000),
                mark_ts_word(0x0000),
            ],
            halt,
            5,
        );
        let ring = decode_ring(&bytes).unwrap();
        assert_eq!(
            ring.records,
            vec![Record::Mark {
                label: 0x3FFF,
                timestamp: 0,
            }]
        );
    }

    #[test]
    fn halt_decodes_overflow_mismatch_status() {
        // tag=11, overflow=1, mismatch=1, status=0xA
        let halt = (halt::TAG_HALT << halt::TAG_SHIFT)
            | (1 << halt::OVERFLOW_BIT)
            | (1 << halt::MISMATCH_BIT)
            | (0xAu32 << halt::STATUS_SHIFT);
        let bytes = build_ring(0, &[], halt, 2);
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
        let bytes = build_ring(0, &[], halt, 2);
        let ring = decode_ring(&bytes).unwrap();
        assert!(ring.halt.is_engine_trap());
    }

    #[test]
    fn halt_user_max_status_passes() {
        // STATUS_USER_MAX = 0x1C is the highest user-defined status.
        let halt = halt_word_status(halt::STATUS_USER_MAX);
        let bytes = build_ring(0, &[], halt, 2);
        let ring = decode_ring(&bytes).unwrap();
        assert_eq!(ring.halt.status, halt::STATUS_USER_MAX);
        assert!(!ring.halt.is_engine_trap());
    }

    #[test]
    fn halt_reserved_status_low_rejected() {
        // STATUS_RESERVED_LOW = 0x1D should be rejected.
        let halt = halt_word_status(halt::STATUS_RESERVED_LOW);
        let bytes = build_ring(0, &[], halt, 2);
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
        let bytes = build_ring(0, &[], halt, 2);
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
        let bytes = build_ring(0, &[], halt, 2);
        let err = decode_ring(&bytes).unwrap_err();
        assert!(matches!(err, RingError::HaltReservedBitsSet { .. }));
    }

    #[test]
    fn rejects_non_multiple_of_4_byte_count() {
        // 6 bytes: legal under v0 16-bit grain (3 words) but v0.2
        // ring is 32-bit-grained.
        let bytes = vec![0u8; 6];
        assert_eq!(decode_ring(&bytes), Err(RingError::OddByteCount { got: 6 }));
        // 7 bytes: not a multiple of 4 either way.
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
        // Tail word has tag != 11 (set to 0 = CAPTURE tag).
        let halt = halt_word_status(0);
        let mut bytes = build_ring(0, &[], halt, 2);
        // Patch the HALT word to 0x0000_0000 (removes tag).
        let halt_off = bytes.len() - 4;
        for b in &mut bytes[halt_off..halt_off + 4] {
            *b = 0;
        }
        assert_eq!(
            decode_ring(&bytes),
            Err(RingError::NoHaltAtTail { word: 0 })
        );
    }

    #[test]
    fn reserved_tag_01_terminates_record_stream_as_trailing_garbage() {
        // word at offset 1 with tag 01 (= 0x4000_0000 in 32-bit ring
        // word). This is the real-silicon failure mode where SPRAM
        // garbage starts after the last real record. The decoder
        // must treat that as "garbage begins here" rather than fail,
        // but still honour the HALT-at-tail integrity check.
        let halt = halt_word_status(0);
        let reserved_tag_word = 0x4000_0000u32;
        let bytes = build_ring(0, &[reserved_tag_word], halt, 3);
        let ring = decode_ring(&bytes).unwrap();
        assert!(ring.records.is_empty());
        assert_eq!(ring.trailing_garbage_words, 1);
        assert_eq!(ring.halt.status, 0);
    }

    #[test]
    fn sentinel_fill_terminates_after_real_records() {
        // Real-silicon post-fix layout: a small number of CAPTURE
        // records, then the SENTINEL run that the engine pre-filled
        // before this program started writing, then the HALT
        // terminator at the tail.
        //
        // This is the engine's defence against cross-program SPRAM
        // leakage: previously, a short program following a long one
        // would see the long program's CAPTURE / MARK records leak
        // into its decoded output, because the walker keeps reading
        // legal record tags forward until it hits a non-record tag.
        // The SENTINEL (tag 0b01 = reserved record) is that
        // non-record tag.
        let halt = halt_word_status(0);
        let captures = [
            capture_word(false),
            capture_word(true),
            capture_word(false),
        ];
        let sentinel = mole_abi::result_ring::SENTINEL;
        // Layout: REVISION + 3 CAPTUREs + 100 SENTINELs + HALT = 105 words.
        let mut records: Vec<u32> = Vec::with_capacity(103);
        records.extend_from_slice(&captures);
        records.extend(std::iter::repeat_n(sentinel, 100));
        let bytes = build_ring(0, &records, halt, 105);
        let ring = decode_ring(&bytes).unwrap();
        assert_eq!(ring.records.len(), 3, "expected exactly 3 captures");
        assert_eq!(ring.records[0], Record::Capture { sda: false });
        assert_eq!(ring.records[1], Record::Capture { sda: true });
        assert_eq!(ring.records[2], Record::Capture { sda: false });
        assert_eq!(ring.trailing_garbage_words, 100);
        assert_eq!(ring.halt.status, 0);
        assert!(!ring.halt.overflow);
    }

    #[test]
    fn rejects_truncated_mark_at_tail() {
        // MARK at offset 1; only 1 word left before the HALT slot.
        // total_words=3 → record window is [1..2) which is 1 word.
        let halt = halt_word_status(0);
        let bytes = build_ring(0, &[mark_header_word(0)], halt, 3);
        assert_eq!(
            decode_ring(&bytes),
            Err(RingError::TruncatedMark {
                offset_words: 1,
                remaining_words: 1,
            })
        );
    }

    #[test]
    fn rejects_truncated_mark_two_words_short() {
        // MARK at offset 1; only 2 words left before the HALT slot.
        // total_words=4 → record window is [1..3) which is 2 words.
        let halt = halt_word_status(0);
        let bytes = build_ring(0, &[mark_header_word(0), mark_ts_word(0)], halt, 4);
        assert_eq!(
            decode_ring(&bytes),
            Err(RingError::TruncatedMark {
                offset_words: 1,
                remaining_words: 2,
            })
        );
    }

    #[test]
    fn mid_ring_halt_tag_terminates_record_stream_as_trailing_garbage() {
        // A word with top 2 bits = 11 at offset 1 (before the actual
        // HALT slot at offset 2). The record walker treats tag 0b11
        // mid-stream as "garbage begins here" and stops walking.
        let halt = halt_word_status(0);
        let mid_halt_tag_word = 0xC000_0000u32;
        let bytes = build_ring(0, &[mid_halt_tag_word], halt, 3);
        let ring = decode_ring(&bytes).unwrap();
        assert!(ring.records.is_empty());
        assert_eq!(ring.trailing_garbage_words, 1);
    }

    #[test]
    fn tmp108_shaped_real_silicon_ring_decodes_to_19_captures() {
        // Regression test for the tmp108 hardware bring-up failure.
        // The program emits 19 capture-bearing WIRE opcodes (3 EMIT_BYTE
        // ACKs + 8 MSB EMIT_BIT + 8 LSB EMIT_BIT), then HALT. The
        // engine writes 19 CAPTURE records starting at ring word 1
        // (post-REVISION), then HALT at the tail. Slots between the
        // last real capture and HALT hold uninitialised SPRAM; the
        // decoder must stop at the first garbage word and not trip
        // on its tag bits.
        let halt = halt_word_status(0);
        let captures: Vec<u32> = (0..19).map(|i| capture_word(i % 2 == 0)).collect();
        // 2048 total ring words (8192 bytes ring), matches Verde default.
        let mut bytes = build_ring(mole_abi::revision::pack(0, 1, 0), &captures, halt, 2048);
        // Drop a single garbage word into the slot at offset 20
        // (just past the 19 captures) so the decoder hits a
        // reserved/halt tag and stops walking. Choose a tag-01
        // (reserved) pattern.
        let garbage_word_offset = 20;
        let garbage_byte_offset = garbage_word_offset * 4;
        let garbage: u32 = 0x4000_1234; // tag=01, arbitrary low bits
        bytes[garbage_byte_offset..garbage_byte_offset + 4].copy_from_slice(&garbage.to_le_bytes());
        let ring = decode_ring(&bytes).unwrap();
        assert_eq!(ring.records.len(), 19);
        assert_eq!(ring.halt.status, 0);
        // 2046 record slots total (2048 - 1 REVISION - 1 HALT), 19
        // consumed; the rest is trailing garbage.
        assert_eq!(ring.trailing_garbage_words, 2046 - 19);
    }

    #[test]
    fn trailing_zeros_decode_as_capture_false_run() {
        // Verilator-style ring: 5 words total, REV(1) + CAPTURE(1)
        // + 2 zero slots + HALT(1). The 2 zero slots decode as
        // CAPTURE { sda: false } per the documented hazard.
        let halt = halt_word_status(0);
        let bytes = build_ring(0, &[capture_word(true)], halt, 5);
        let ring = decode_ring(&bytes).unwrap();
        assert_eq!(
            ring.records,
            vec![
                Record::Capture { sda: true },
                Record::Capture { sda: false },
                Record::Capture { sda: false },
            ]
        );
        assert_eq!(ring.trailing_garbage_words, 0);
    }

    #[test]
    fn decode_ring_strict_rejects_size_mismatch() {
        // A perfectly-valid 2-word ring (REVISION + HALT) handed to
        // `decode_ring_strict` with an 8192-byte expectation must be
        // rejected.
        let halt = halt_word_status(0);
        let bytes = build_ring(0, &[], halt, 2);
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
        let inner = build_ring(mole_abi::revision::pack(0x02, 0x03, 0x0001), &[], halt, 2);
        // Prepend 4 garbage bytes (one 32-bit word).
        let mut buf = vec![0xAA, 0xBB, 0xCC, 0xDD];
        buf.extend_from_slice(&inner);
        // Lax accepts (documented hazard).
        assert!(decode_ring(&buf).is_ok());
        // Strict refuses because buf.len() != expected ring size.
        let err = decode_ring_strict(&buf, inner.len()).unwrap_err();
        assert!(matches!(err, RingError::UnexpectedByteCount { .. }));
    }

    #[test]
    fn full_default_ring_size_round_trip() {
        // Verde default ring = 8192 bytes = 2048 32-bit words. Smoke-
        // test that the decoder is happy at the full size.
        let halt = halt_word_status(0);
        let bytes = build_ring(
            mole_abi::revision::pack(0x12, 0x34, 0x5678),
            &[],
            halt,
            2048,
        );
        let ring = decode_ring(&bytes).unwrap();
        assert_eq!(
            ring.revision,
            Revision {
                major: 0x12,
                minor: 0x34,
                patch: 0x5678,
            }
        );
        // All inter-rev / pre-halt slots are zero in our builder,
        // so they decode as CAPTURE { sda: false }.
        // Record window = 2048 - 1 (REV) - 1 (HALT) = 2046 words.
        assert_eq!(ring.records.len(), 2048 - 2);
        assert!(
            ring.records
                .iter()
                .all(|r| *r == Record::Capture { sda: false })
        );
    }
}
