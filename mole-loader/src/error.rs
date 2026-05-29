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
//!   length, truncated MARK, missing HALT tail) discovered when
//!   decoding what the engine wrote back.
//! - [`LoaderError::RevisionMismatch`] --- the `--expect-revision`
//!   check failed.
//! - [`LoaderError::Transport`] --- the serial port layer raised an
//!   error (open / configure / read / write / timeout).

use std::io;

use thiserror::Error;

use crate::ring::Revision;

/// Anything that can go wrong inside the host-side loader.
#[derive(Debug, Error)]
pub enum LoaderError {
    /// The `.mole.bin` artifact failed structural / CRC validation
    /// before it was even handed to the transport layer.
    #[error(transparent)]
    Frame(#[from] FrameError),

    /// The result-ring buffer drained from the engine did not parse
    /// as a well-formed (REVISION, records*, HALT) sequence.
    #[error(transparent)]
    Ring(#[from] RingError),

    /// Serial-port transport raised an error. Wraps the rich
    /// [`TransportError`] which itself distinguishes open / configure
    /// / I/O / timeout failures.
    #[error(transparent)]
    Transport(#[from] TransportError),

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

    /// The buffer size handed to
    /// [`crate::ring::decode_ring_strict`] does not match the
    /// configured ring size. Catches the silent-prefix /
    /// silent-suffix hazard `decode_ring` cannot detect on its own
    /// (it only checks "even byte count, ≥ 6, HALT tag at tail").
    #[error(
        "ring byte count {got} does not match configured ring size {expected} \
         (engine drains exactly resultRingByteCount per HALT; \
         mis-sized buffers usually mean the host asked for the wrong \
         --ring-bytes or the serial link dropped framing)"
    )]
    UnexpectedByteCount {
        /// Actual byte count of the buffer.
        got: usize,
        /// Configured ring size (e.g.
        /// [`crate::transport::DEFAULT_RING_BYTES`]).
        expected: usize,
    },
}

/// Serial-port transport failures.
///
/// Wraps the [`serialport`] crate errors and `std::io::Error` so the
/// CLI can surface "couldn't open the port" differently from "wrote
/// fine but read timed out". Hardware RTS/CTS flow control is
/// mandatory on the Mole side; failure to configure it lands in
/// [`TransportError::ConfigureFlowControl`].
#[derive(Debug, Error)]
pub enum TransportError {
    /// `serialport::new(...).open()` failed --- usually means the
    /// path does not exist, the port is held by another process, or
    /// the OS refused permission.
    #[error("failed to open serial port {path:?}: {source}")]
    OpenPort {
        /// Path the caller tried to open (e.g. `/dev/ttyUSB0`).
        path: String,
        /// Underlying `serialport` error.
        source: serialport::Error,
    },

    /// Setting baud, parity, stop bits, or data bits failed after
    /// `open` succeeded. Vanishingly rare in practice.
    #[error("failed to configure serial port {path:?}: {source}")]
    Configure {
        /// Path of the port being configured.
        path: String,
        /// Underlying `serialport` error.
        source: serialport::Error,
    },

    /// Setting hardware RTS/CTS flow control failed. Mole *requires*
    /// hardware flow control --- if the platform's serial driver
    /// cannot enable it, the loader cannot safely proceed.
    #[error(
        "failed to enable hardware RTS/CTS flow control on {path:?}: {source}.\n\
         The Mole engine requires hardware flow control to safely transfer the \
         program frame and drain the result ring; soft- or no-flow-control \
         operation is not supported."
    )]
    ConfigureFlowControl {
        /// Path of the port whose flow control could not be enabled.
        path: String,
        /// Underlying `serialport` error.
        source: serialport::Error,
    },

    /// A blocking read or write returned an `io::ErrorKind::TimedOut`
    /// before the requested byte count completed. Usually means the
    /// engine never reached `HALT`, the wrong baud is set on one side,
    /// or hardware flow control is wired the wrong way around.
    #[error("serial port {phase:?} timed out after {after_bytes} of {expected_bytes} bytes")]
    Timeout {
        /// Which phase of the transaction timed out.
        phase: TransportPhase,
        /// How many bytes had completed before the timeout fired.
        after_bytes: usize,
        /// How many bytes the operation was waiting for in total.
        expected_bytes: usize,
    },

    /// Any other `io::Error` raised by the serial port during read
    /// or write.
    #[error("serial port {phase:?} I/O error: {source}")]
    Io {
        /// Which phase of the transaction errored.
        phase: TransportPhase,
        /// Underlying I/O error.
        source: io::Error,
    },

    /// Generic [`serialport`] error not captured by the more specific
    /// variants above.
    #[error("serial port error: {0}")]
    Serial(#[from] serialport::Error),
}

/// Which phase of a `send_and_drain` transaction was active when the
/// transport error fired. Lets the CLI render a more helpful diagnostic
/// ("the frame upload timed out" vs "the result ring drain timed out").
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TransportPhase {
    /// Writing the `.mole.bin` frame to the engine.
    Load,
    /// Reading the result ring back from the engine.
    Drain,
}

impl std::fmt::Display for TransportPhase {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Load => f.write_str("load"),
            Self::Drain => f.write_str("drain"),
        }
    }
}
