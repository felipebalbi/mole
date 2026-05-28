//! Host-side loader and result-ring decoder for the Mole bit-cycle engine.
//!
//! This crate is the runtime counterpart to the [`mole-asm`](../mole_asm/index.html)
//! assembler:
//!
//! - **Frame I/O**: ship a `.mole.bin` artifact to a Mole engine over a
//!   serial port and read back the result ring.
//! - **Decoder**: parse the result ring's `REVISION` / `MARK` / `CAPTURE`
//!   / `HALT` records out of the raw byte stream.
//!
//! The wire contract is documented in
//! `fpga/Mole/WIRE_FORMAT.md`. Frames are little-endian
//! `[len_lo, len_hi, words..., crc_lo, crc_hi]` over UART
//! 1 Mbaud 8N1 with hardware RTS/CTS. The engine drains exactly
//! `resultRingByteCount` bytes back per `HALT`.
//!
//! This top-level scaffold has no functional content yet; the
//! decoder lands in Commit 2 and serial-port transport in Commit 3.
