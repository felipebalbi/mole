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

/// Malformed `.mole.bin` UART frame, discovered by
/// [`crate::frame::verify_frame`] or [`crate::verify_program`].
///
/// The wire format is
/// `[len_lo, len_hi, words..., crc_lo, crc_hi]` little-endian, per
/// §10. 32-bit instruction words, valid program body length
/// `1..=8192` words (total frame length `3..=8194` including the
/// 2-word preamble). Anything that fails to round-trip back to a
/// valid word vector lands here.
#[derive(Debug, Clone, Error, PartialEq, Eq)]
pub enum FrameError {
    /// Frame is shorter than the minimum: 4B magic, 4B length, 4B for the
    /// first body word, and 2B CRC = 14 bytes total. Anything longer than
    /// 14 but still malformed lands in [`FrameError::LengthMismatch`] or
    /// [`FrameError::LengthOutOfRange`] instead.
    #[error("frame too short: need at least 14 bytes (preamble + 1 body word + CRC), got {got}")]
    TooShort {
        /// Actual byte count of the truncated frame.
        got: usize,
    },

    /// Frame's `len` header asks for a body word count that does not
    /// match the bytes actually present. `expected_bytes` is what the
    /// header implies (`4 + 4 + 4 * len_words + 2`); `got_bytes` is
    /// what the file has.
    #[error(
        "frame length mismatch: header says {len_words} body words \
         (= {expected_bytes} total bytes), got {got_bytes} bytes"
    )]
    LengthMismatch {
        /// Body word count the header claims.
        len_words: u32,
        /// Total byte count the header implies.
        expected_bytes: usize,
        /// Actual byte count of the buffer.
        got_bytes: usize,
    },

    /// Frame's `len` header is outside the engine's accepted range
    /// `1..=MAX_PROGRAM_WORDS` (= `1..=8192`). Symmetric with
    /// [`mole_asm`]'s `AsmError::FrameTooLarge` on the encoder side.
    #[error(
        "frame body word count {len_words} outside engine range \
         1..=8192 (MAX_PROGRAM_WORDS)"
    )]
    LengthOutOfRange {
        /// Body word count the header claims.
        len_words: u32,
    },

    /// CRC-16/XMODEM over `MAGIC + LEN + body` did not match the
    /// trailing two-byte CRC. File is corrupt or truncated past the
    /// length check.
    #[error("frame CRC mismatch: computed {computed:#06x}, header has {got:#06x}")]
    CrcMismatch {
        /// What [`crate::frame::verify_frame`] computed locally.
        computed: u16,
        /// What the trailing two bytes of the frame held.
        got: u16,
    },

    /// Frame magic mismatch (§16.1): the 4 magic bytes at offset 0..4
    /// do not match `mole_abi::MAGIC = 0x0002_4D4C`.
    #[error("frame magic mismatch: got 0x{got:08x}, expected 0x{expected:08x}")]
    MagicMismatch {
        /// The 32-bit value at offset 0..4 of the frame.
        got: u32,
        /// The expected value (`mole_abi::MAGIC`).
        expected: u32,
    },

    /// A HALT instruction in the program body has a reserved status
    /// code (§16.4).
    ///
    /// Status codes `0x1D`–`0x1E` are reserved for future engine
    /// traps and must not appear in user programs. The loader rejects
    /// such programs before any data is written to SPRAM. See spec
    /// §16.4.
    #[error(
        "program contains HALT with reserved status 0x{status:02x} at PC {pc} \
         (loader rejects; STATUS_RESERVED range 0x1D..=0x1E is \
         engine-trap-only)"
    )]
    ReservedHaltStatusInBody {
        /// 0-indexed instruction offset in the program body where the
        /// HALT with reserved status was found.
        pc: usize,
        /// The reserved status value found (`0x1D` or `0x1E`).
        status: u8,
    },
}

/// Malformed result-ring buffer, discovered by
/// [`crate::ring::decode_ring`].
///
/// The result ring's wire format is documented in
/// `fpga/Mole/src/hw/EnginePipeline.scala` (ring write paths) and in
/// the host-facing `docs/MOLE-0.2-SPEC.md` §11. Tags are
/// `00=CAPTURE`, `01=reserved`, `10=MARK`, `11=HALT`. Ring slots are
/// 32 bits wide (the result ring is 32-bit-addressed, drained as 4
/// little-endian bytes per slot).
#[derive(Debug, Clone, Error, PartialEq, Eq)]
pub enum RingError {
    /// Buffer is shorter than the minimum legal ring (REVISION +
    /// HALT = 2 × 32-bit words = 8 bytes).
    #[error("ring too short: need at least 8 bytes (revision + halt), got {got}")]
    TooShort {
        /// Actual byte count of the truncated ring.
        got: usize,
    },

    /// Ring byte count is not a multiple of 4; rings are sequences
    /// of 32-bit words.
    #[error(
        "ring byte count {got} is not a multiple of 4; rings must be a whole \
         number of 32-bit words"
    )]
    OddByteCount {
        /// The offending byte count.
        got: usize,
    },

    /// The terminator word at the tail of the ring did not carry the
    /// expected `tag = 0b11` HALT bits.
    ///
    /// On a real engine this is the loudest sign that the drainer
    /// returned the wrong byte count, the serial link dropped bytes,
    /// or the engine never reached `Halt` --- the tail slot is
    /// reserved exclusively for the HALT word.
    #[error(
        "ring tail word {word:#010x} does not have HALT tag \
         (expected [31:30] = 0b11)"
    )]
    NoHaltAtTail {
        /// The 32-bit word that was found in the tail slot.
        word: u32,
    },

    /// A MARK record header (tag `0b10`) appeared without the two
    /// timestamp words that must follow it before the HALT word.
    #[error(
        "ring record at word offset {offset_words}: MARK header without two \
         trailing timestamp words (only {remaining_words} 32-bit word(s) left \
         before HALT)"
    )]
    TruncatedMark {
        /// 32-bit-word offset (from start of ring) of the MARK header.
        offset_words: usize,
        /// How many 32-bit words were left between the MARK header
        /// and the HALT terminator.
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

    /// HALT word's reserved low-bit field (`[22:0]`) is non-zero.
    ///
    /// Per INV-WIRE-HALT-RECORD the v0.2 HALT word layout is (§11
    /// lines 1458-1492): `[31:30]=11`, `[29]=overflow`,
    /// `[28]=mismatchAtHalt`, `[27:23]=status` (5 bits),
    /// `[22:0]=0` (23 reserved bits). Any non-zero bit in `[22:0]`
    /// is either an engine-loader version skew or a corrupted drain.
    /// The decoder fails fast rather than silently discarding the
    /// information.
    #[error(
        "HALT word {halt_word:#010x} has non-zero reserved low 23 bits \
         (reserved & 0x007FFFFF = {reserved:#010x}); \
         INV-WIRE-HALT-RECORD requires [22:0] == 0. Likely cause: \
         engine-loader version skew, or a corrupted drain"
    )]
    HaltReservedBitsSet {
        /// The full 32-bit HALT word for forensic inspection.
        halt_word: u32,
        /// The offending reserved-bits value
        /// (= `halt_word & 0x007F_FFFF`).
        reserved: u32,
    },

    /// HALT word's status field is in `0x1D..=0x1E` --- reserved for
    /// future engine traps and not currently emitted by any shipped
    /// engine (§11, INV-NUM-STATUS-RESERVED).
    ///
    /// Per INV-NUM-STATUS-RESERVED, the 5-bit status field
    /// `[27:23]` encodes: `0x00..=0x1C` caller-defined user halts,
    /// `0x1D..=0x1E` reserved for future engine traps, `0x1F`
    /// currently in use as the engine-trap code. This decoder rejects
    /// `0x1D`/`0x1E` so callers are not silently misled when a future
    /// engine ships a new trap class. Likely cause: engine-loader
    /// version skew, or a corrupted drain.
    #[error(
        "HALT word {halt_word:#010x} has reserved status code {status:#x}; \
         INV-NUM-STATUS-RESERVED reserves 0x1D and 0x1E for future engine \
         traps (today only 0x1F is defined). Likely cause: \
         engine-loader version skew, or a corrupted drain"
    )]
    HaltStatusReserved {
        /// The reserved status value (`0x1D` or `0x1E`).
        status: u8,
        /// The full 32-bit HALT word for forensic inspection.
        halt_word: u32,
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
    /// engine never reached `HALT`, the wrong baud is set on one
    /// side, or hardware flow control is wired the wrong way around.
    #[error(
        "serial port {phase:?} timed out after {after_bytes} \
         of {expected_bytes} bytes"
    )]
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
