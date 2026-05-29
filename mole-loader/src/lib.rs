//! Host-side loader and result-ring decoder for the Mole bit-cycle
//! engine.
//!
//! This crate is the runtime counterpart to the [`mole-asm`](../mole_asm/index.html)
//! assembler:
//!
//! - **Frame verification** ([`frame`]): sanity-check a `.mole.bin`
//!   artifact (length, CRC-16/XMODEM) before sending it to the engine.
//! - **Result-ring decoder** ([`ring`]): parse the engine's
//!   `REVISION` / `CAPTURE` / `MARK` / `HALT` records out of the raw
//!   byte stream the drainer returns.
//! - **Serial transport** (Commit 3): ships the frame over UART
//!   (1 Mbaud, 8N1, hardware RTS/CTS) and waits for the engine's
//!   drained ring.
//!
//! The wire contract is documented in
//! `fpga/Mole/WIRE_FORMAT.md`. Frames are little-endian
//! `[len_lo, len_hi, words..., crc_lo, crc_hi]` over UART at
//! 1 Mbaud 8N1 with mandatory hardware RTS/CTS. The engine drains
//! exactly `resultRingByteCount` bytes back per `HALT`.
//!
//! # Quick start
//!
//! ```
//! use mole_loader::{verify_frame, decode_ring, Revision};
//!
//! // Pre-flight check: did our .mole.bin survive disk / network?
//! let frame = mole_asm::assemble_to_frame(
//!     "HALT status=0\n",
//!     "<inline>",
//! )
//! .unwrap();
//! let words = verify_frame(&frame).unwrap();
//! assert_eq!(words, vec![0x0000]);
//!
//! // Post-run: decode a hand-built 3-word "REVISION + HALT" ring.
//! let ring = &[
//!     0x34, 0x12, // REVISION lo = 0x1234 (= patch)
//!     0x00, 0x01, // REVISION hi = 0x0100 (major=1, minor=0)
//!     0x00, 0xC0, // HALT (tag=11, status=0)
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
pub use transport::{DEFAULT_BAUD, DEFAULT_RING_BYTES, DEFAULT_TIMEOUT, Progress, Transport};
