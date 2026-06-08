//! Host-side loader and result-ring decoder for the Mole bit-cycle
//! engine.
//!
//! This crate is the runtime counterpart to the
//! [`mole-asm`](../mole_asm/index.html) assembler:
//!
//! - **Frame verification** ([`frame`]): sanity-check a `.mole.bin`
//!   artifact (length, CRC-16/XMODEM) before sending it to the
//!   engine.
//! - **Program validation** ([`verify_program`]): check magic,
//!   version, and reserved-status fields (§16.1, §16.2, §16.4)
//!   before writing any data to SPRAM.
//! - **Result-ring decoder** ([`ring`]): parse the engine's
//!   `REVISION` / `CAPTURE` / `MARK` / `HALT` records out of the
//!   raw byte stream the drainer returns.
//! - **Serial transport** ([`transport`]): ships the frame over
//!   UART (2 Mbaud, 8N1, hardware RTS/CTS) and waits for the
//!   engine's drained ring.
//!
//! The wire contract is documented in
//! `fpga/Mole/WIRE_FORMAT.md`. Frames are little-endian
//! `[len_lo, len_hi, words..., crc_lo, crc_hi]` with 32-bit LE
//! instruction words over UART at 2 Mbaud 8N1 with mandatory
//! hardware RTS/CTS. The engine drains exactly
//! `resultRingByteCount` bytes back per `HALT`.
//!
//! # Quick start
//!
//! ```
//! use mole_loader::{verify_frame, verify_program, decode_ring, Revision};
//!
//! // Pre-flight check: did our .mole.bin survive disk / network?
//! let frame = mole_asm::assemble_to_frame(
//!     "HALT status=0\n",
//!     "<inline>",
//! )
//! .unwrap();
//! let words = verify_frame(&frame).unwrap();
//! let (_version, body) = verify_program(&words).unwrap();
//! assert_eq!(body.len(), 1); // one body word (HALT)
//!
//! // Post-run: decode a hand-built 4-word "REVISION + HALT" ring.
//! // The HALT word is 32-bit LE split across two 16-bit ring slots.
//! // halt_word = 0xC000_0000 (tag=11 at [31:30], status=0, rest 0).
//! let ring = &[
//!     0x34, 0x12, // REVISION lo = 0x1234 (= patch)
//!     0x00, 0x01, // REVISION hi = 0x0100 (major=1, minor=0)
//!     0x00, 0x00, // HALT lo half = 0x0000
//!     0x00, 0xC0, // HALT hi half = 0xC000 (tag=11 at bits [15:14])
//! ];
//! let decoded = decode_ring(ring).unwrap();
//! assert_eq!(
//!     decoded.revision,
//!     Revision { major: 1, minor: 0, patch: 0x1234 }
//! );
//! assert!(decoded.records.is_empty());
//! assert_eq!(decoded.halt.status, 0);
//! ```

#![deny(missing_docs)]
#![warn(clippy::all)]

pub mod error;
pub mod frame;
pub mod ring;
pub mod transport;

pub use error::{FrameError, LoaderError, RingError, TransportError, TransportPhase};
pub use frame::verify_frame;
pub use ring::{DecodedRing, HaltStatus, Record, Revision, decode_ring, decode_ring_strict};
pub use transport::{
    DEFAULT_BAUD, DEFAULT_RING_BYTES, DEFAULT_TIMEOUT, FALLBACK_BAUD, Progress, Transport,
};

use mole_abi::{FORMAT_VERSION, MAGIC, MAGIC_LO_U16, PREAMBLE_WORDS, halt};

/// Validate a v0.2 program word slice per spec §16.
///
/// Run AFTER [`verify_frame`] succeeds. Performs the magic check
/// (§16.1), version check (§16.2), body-length consistency check
/// (§16.3), and the reserved-HALT-status scan (§16.4). The
/// minimum-length check (§16.5) is enforced by [`verify_frame`]'s
/// lower bound on the length field.
///
/// On success, returns `(version, body)` where `version` is the
/// format version extracted from preamble word 0 and `body` is the
/// program body slice (preamble stripped). On failure, returns the
/// first error encountered without scanning further.
///
/// # Errors
///
/// - [`FrameError::MagicMismatch`] if word 0 low 16 bits ≠
///   `mole_abi::MAGIC_LO_U16` (§16.1).
/// - [`FrameError::VersionMismatch`] if word 0 high 16 bits ≠
///   `mole_abi::FORMAT_VERSION` (§16.2).
/// - [`FrameError::ReservedHaltStatusInBody`] if any HALT
///   instruction in the body carries status `0x1D..=0x1E` (§16.4).
pub fn verify_program(words: &[u32]) -> Result<(u16, &[u32]), FrameError> {
    // §16.1 — Magic check: low 16 bits of word 0 must be MAGIC_LO_U16.
    let found_magic_lo = (words[0] & 0xFFFF) as u16;
    if found_magic_lo != MAGIC_LO_U16 {
        return Err(FrameError::MagicMismatch {
            found: words[0],
            expected: MAGIC,
        });
    }

    // §16.2 — Version check: high 16 bits of word 0 must be
    // FORMAT_VERSION.
    let found_version = (words[0] >> 16) as u16;
    if found_version != FORMAT_VERSION {
        return Err(FrameError::VersionMismatch {
            found: found_version,
            expected: FORMAT_VERSION,
        });
    }

    // §16.3 — Body length consistency: word 1 declares the body
    // length; the actual body slice length must agree.
    let declared_body_len = words[1] as usize;
    let body = &words[PREAMBLE_WORDS..];
    debug_assert_eq!(
        declared_body_len,
        body.len(),
        "verify_frame should have guaranteed consistency between \
         the frame length field and the returned word slice"
    );

    // §16.4 — Reserved-status scan: walk every body word and reject
    // any HALT instruction whose status field is in 0x1D..=0x1E.
    //
    // HALT instruction encoding per §5.10:
    //   [31:30] group  = 0b01  (CTRL group)
    //   [29:26] sub    = 0b0000
    //   [25: 8] reserved = 0
    //   [ 7: 3] status  (5 bits)
    //   [ 2: 0] reserved = 0
    //
    // The 6-bit opcode {group, sub} lives at [31:26]:
    //   group(0b01) << 4 | sub(0b0000) = 0b01_0000 = 0x10.
    //
    // Status is at [7:3] of the instruction word (NOT the same
    // position as in the result-ring HALT word, which places status
    // at [27:23]).
    const HALT_OPCODE: u32 = 0b01_0000; // {group=01, sub=0000}
    const HALT_STATUS_SHIFT: u32 = 3; // §5.10, §4.2 table row 0
    const HALT_STATUS_MASK: u32 = 0x1F; // 5-bit field

    for (pc, &word) in body.iter().enumerate() {
        let opcode = (word >> 26) & 0b11_1111;
        if opcode == HALT_OPCODE {
            let status = ((word >> HALT_STATUS_SHIFT) & HALT_STATUS_MASK) as u8;
            if (halt::STATUS_RESERVED_LOW..=halt::STATUS_RESERVED_HIGH).contains(&status) {
                return Err(FrameError::ReservedHaltStatusInBody { pc, status });
            }
        }
    }

    Ok((found_version, body))
}
