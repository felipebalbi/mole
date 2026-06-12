//! Host-side loader and result-ring decoder for the Mole bit-cycle
//! engine.
//!
//! This crate is the runtime counterpart to the
//! [`mole-asm`](../mole_asm/index.html) assembler:
//!
//! - **Frame verification** ([`frame`]): sanity-check a `.mole.bin`
//!   artifact (magic, length, CRC-16/XMODEM) before sending it to the
//!   engine. The 8-byte preamble (`MAGIC` + body LEN) is validated
//!   and stripped; the returned slice is body-only.
//! - **Program validation** ([`verify_program`]): scan the body for
//!   reserved HALT status values (§16.4) before writing any data to
//!   SPRAM.
//! - **Result-ring decoder** ([`ring`]): parse the engine's
//!   `REVISION` / `CAPTURE` / `MARK` / `HALT` records out of the
//!   raw byte stream the drainer returns.
//! - **Serial transport** ([`transport`]): ships the frame over
//!   UART (2 Mbaud, 8N1, hardware RTS/CTS) and waits for the
//!   engine's drained ring.
//!
//! The wire contract is documented in `fpga/Mole/WIRE_FORMAT.md`.
//! Frames are little-endian
//! `[MAGIC(4B), LEN(4B), body(4*N B), crc(2B)]` with 32-bit LE
//! body words over UART at 1-2 Mbaud 8N1 with mandatory hardware
//! RTS/CTS. The engine drains exactly `resultRingByteCount` bytes
//! back per `HALT`.
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
//! let body = verify_frame(&frame).unwrap();
//! let _scanned = verify_program(&body).unwrap();
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

use mole_abi::halt;

/// Scan a v0.2 program body for reserved HALT status values (§16.4).
///
/// Run AFTER [`verify_frame`] succeeds (magic + length + CRC already
/// validated). The body slice is the program instructions only --- the
/// 8-byte MAGIC + LEN preamble has been stripped by `verify_frame`.
///
/// # Errors
///
/// - [`FrameError::ReservedHaltStatusInBody`] if any HALT instruction
///   in the body carries status `0x1D..=0x1E` (§16.4).
pub fn verify_program(body: &[u32]) -> Result<&[u32], FrameError> {
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

    Ok(body)
}
