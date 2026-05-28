//! Structured loader / decoder errors.
//!
//! `mole_loader` returns `Result<T, LoaderError>` from every fallible
//! function. The variants partition the failure space the loader can
//! actually see:
//!
//! - [`LoaderError::Frame`] --- malformed `.mole.bin` (bad length,
//!   bad CRC) discovered when verifying the artifact before sending
//!   it to the engine.
//! - [`LoaderError::Ring`] --- malformed result-ring buffer (wrong
//!   length, reserved tag, truncated record) discovered when
//!   decoding what the engine wrote back.
//! - [`LoaderError::RevisionMismatch`] --- the `--expect-revision`
//!   check failed.
//!
//! These are all data-shape failures of bytes already in hand. I/O
//! and serial-port errors land in Commit 3 alongside the transport
//! module and live in their own variants.

use thiserror::Error;

use crate::ring::Revision;

/// Anything that can go wrong inside the host-side loader.
#[derive(Debug, Clone, Error, PartialEq, Eq)]
pub enum LoaderError {
    /// The `.mole.bin` artifact failed structural / CRC validation
    /// before it was even handed to the transport layer.
    #[error(transparent)]
    Frame(#[from] FrameError),

    /// The result-ring buffer drained from the engine did not parse
    /// as a well-formed (REVISION, records*, HALT) sequence.
    #[error(transparent)]
    Ring(#[from] RingError),

    /// The engine reported a different REVISION than the caller
    /// asserted via `--expect-revision`. Carries both sides so the
    /// CLI can render `expected X.Y.Z, got A.B.C`.
    #[error("REVISION mismatch: expected {expected}, got {got}")]
    RevisionMismatch {
        /// What `--expect-revision` (or the programmatic caller)
        /// asked for.
        expected: Revision,
        /// What the engine actually reported in the first two words
        /// of the result ring.
        got: Revision,
    },
}

/// Malformed `.mole.bin` UART frame, discovered by [`crate::frame::verify_frame`].
///
/// The wire format is `[len_lo, len_hi, words..., crc_lo, crc_hi]`
/// little-endian, per `fpga/Mole/WIRE_FORMAT.md`. Anything that
/// fails to round-trip back to a valid word vector lands here.
#[derive(Debug, Clone, Error, PartialEq, Eq)]
pub enum FrameError {
    /// Frame is shorter than the 2-byte length header, so we cannot
    /// even tell what the file thinks it is. Anything longer than 2
    /// bytes but still malformed lands in [`FrameError::LengthMismatch`]
    /// or [`FrameError::LengthOutOfRange`] instead.
    #[error("frame too short: need at least 2 bytes (length header), got {got}")]
    TooShort {
        /// Actual byte count of the truncated frame.
        got: usize,
    },

    /// Frame's `len` header asks for a word count that does not match
    /// the bytes actually present. `expected_bytes` is what the header
    /// implies (`2 + 2 * len_words + 2`); `got_bytes` is what the file
    /// has.
    #[error(
        "frame length mismatch: header says {len_words} words \
         (= {expected_bytes} total bytes), got {got_bytes} bytes"
    )]
    LengthMismatch {
        /// Word count the header claims.
        len_words: u16,
        /// Total byte count the header implies.
        expected_bytes: usize,
        /// Actual byte count of the buffer.
        got_bytes: usize,
    },

    /// Frame's `len` header is outside the engine's accepted range
    /// `1..=2048`. Symmetric with [`mole_asm`]'s
    /// `AsmError::FrameTooLarge` on the encoder side.
    #[error("frame word count {len_words} outside engine range 1..=2048")]
    LengthOutOfRange {
        /// Word count the header claims.
        len_words: u16,
    },

    /// CRC-16/XMODEM over `len + words` did not match the trailing
    /// two-byte CRC. File is corrupt or truncated past the length
    /// check.
    #[error("frame CRC mismatch: computed {computed:#06x}, header has {got:#06x}")]
    CrcMismatch {
        /// What [`crate::frame::verify_frame`] computed locally.
        computed: u16,
        /// What the trailing two bytes of the frame held.
        got: u16,
    },
}

/// Malformed result-ring buffer, discovered by [`crate::ring::decode_ring`].
///
/// The result ring's wire format is documented in
/// `fpga/Mole/src/hw/BitCycleEngineCore.scala` (file header). Tags
/// are `00=CAPTURE`, `01=reserved`, `10=MARK`, `11=HALT`.
#[derive(Debug, Clone, Error, PartialEq, Eq)]
pub enum RingError {
    /// Buffer is shorter than the minimum legal ring (REVISION lo +
    /// REVISION hi + HALT = 3 words = 6 bytes).
    #[error("ring too short: need at least 6 bytes (revision + halt), got {got}")]
    TooShort {
        /// Actual byte count of the truncated ring.
        got: usize,
    },

    /// Ring byte count is odd; rings are sequences of 16-bit words.
    #[error("ring byte count {got} is odd; rings must be a whole number of 16-bit words")]
    OddByteCount {
        /// The offending odd byte count.
        got: usize,
    },

    /// The terminator word at the tail of the ring did not carry the
    /// expected `tag = 0b11` HALT bits.
    ///
    /// On a real engine this is the loudest sign that the drainer
    /// returned the wrong byte count, the serial link dropped bytes,
    /// or the engine never reached `Halt` --- the tail slot is
    /// reserved exclusively for the HALT word.
    #[error("ring tail word {word:#06x} does not have HALT tag (expected high 2 bits = 0b11)")]
    NoHaltAtTail {
        /// The word that was found in the tail slot.
        word: u16,
    },

    /// While walking the record stream we saw a word with tag `0b01`,
    /// which is reserved (never produced by a conforming engine).
    /// Almost certainly trailing-slot garbage --- see the file header
    /// docstring on [`crate::ring`] for why the gap exists.
    #[error(
        "ring record at word offset {offset_words}: reserved tag 0b01 \
         (word {word:#06x})"
    )]
    ReservedTag {
        /// 0-based word offset (from start of ring) where the bad
        /// word lives.
        offset_words: usize,
        /// The full 16-bit word.
        word: u16,
    },

    /// A MARK record header (tag `0b10`) appeared without the two
    /// timestamp words that must follow it before the HALT word.
    #[error(
        "ring record at word offset {offset_words}: MARK header without two trailing \
         timestamp words (only {remaining_words} word(s) left before HALT)"
    )]
    TruncatedMark {
        /// Word offset (from start of ring) of the MARK header.
        offset_words: usize,
        /// How many words were left between the MARK header and the
        /// HALT terminator.
        remaining_words: usize,
    },

    /// We hit a HALT-tagged word at an offset other than the tail.
    /// The engine never writes HALT mid-ring; this is either a
    /// drainer / framing bug or garbage that happened to decode as
    /// HALT.
    #[error(
        "ring record at word offset {offset_words}: unexpected mid-ring HALT \
         (word {word:#06x}); HALT only lives at the tail slot"
    )]
    UnexpectedMidRingHalt {
        /// Word offset of the spurious HALT.
        offset_words: usize,
        /// The full 16-bit word.
        word: u16,
    },
}
