//! Host-side `.mole.bin` frame verification.
//!
//! The encoder side --- [`mole_asm::frame::build_frame`] --- is
//! authoritative; this module is the inverse, used by the loader to
//! sanity-check an artifact *before* it is shipped over UART.
//!
//! ==v0.2 frame wire format== (see WIRE_FORMAT.md / spec §10):
//!
//! ```text
//! [MAGIC: 4 bytes LE = 0x0002_4D4C]
//! [LEN:   4 bytes LE = N (body word count)]
//! [body:  4 * N bytes (N × 32-bit instructions, each LE)]
//! [CRC:   2 bytes LE = CRC-16/XMODEM over all preceding bytes]
//!
//! Total wire = 10 + 4*N bytes
//! ```
//!
//! - MAGIC and LEN are the 8-byte preamble consumed by the loader (no
//!   v0 back-compat).
//! - Each body word is a u32 little-endian (4 bytes).
//! - The trailing 2 bytes are CRC-16/XMODEM over MAGIC + LEN + body
//!   bytes (everything before the CRC), polynomial `0x1021`, init
//!   `0x0000`, no reflection, no XOR-out --- identical to
//!   [`mole_asm::frame::crc16_xmodem`].

use mole_abi::{MAGIC, MAX_PROGRAM_WORDS};
use mole_asm::frame::crc16_xmodem;

use crate::error::FrameError;

/// Verify a host-built `.mole.bin` frame and return its body word
/// vector.
///
/// On success the returned `Vec<u32>` is the **body** word sequence
/// (preamble already validated and stripped).
///
/// The function does **not** attempt any partial recovery: a single
/// CRC bit-flip or magic mismatch rejects the whole frame.
///
/// # Errors
///
/// - [`FrameError::TooShort`]: `bytes.len() < 10` (no preamble + CRC).
/// - [`FrameError::MagicMismatch`]: first 4 bytes are not `MAGIC`.
/// - [`FrameError::LengthOutOfRange`]: body word count is 0 or
///   exceeds `MAX_PROGRAM_WORDS`.
/// - [`FrameError::LengthMismatch`]: declared length disagrees with
///   the file's actual byte count.
/// - [`FrameError::CrcMismatch`]: trailing CRC does not match.
pub fn verify_frame(bytes: &[u8]) -> Result<Vec<u32>, FrameError> {
    // Minimum valid frame is 4B magic + 4B len + 4B body[0] + 2B CRC
    // = 14 bytes (one body word). Anything shorter is too short to
    // even read the preamble.
    const MIN_FRAME_BYTES: usize = 4 + 4 + 4 + 2;
    if bytes.len() < MIN_FRAME_BYTES {
        return Err(FrameError::TooShort { got: bytes.len() });
    }

    // Validate MAGIC (4 bytes LE).
    let got_magic = u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]);
    if got_magic != MAGIC {
        return Err(FrameError::MagicMismatch {
            got: got_magic,
            expected: MAGIC,
        });
    }

    // Read LEN (4 bytes LE).
    let len_words = u32::from_le_bytes([bytes[4], bytes[5], bytes[6], bytes[7]]);
    if len_words == 0 || len_words as usize > MAX_PROGRAM_WORDS {
        return Err(FrameError::LengthOutOfRange { len_words });
    }

    // Each body word is 4 bytes; the frame carries 4B magic + 4B len +
    // 4*N body bytes + 2B CRC.
    let expected_bytes = 4 + 4 + 4 * (len_words as usize) + 2;
    if bytes.len() != expected_bytes {
        return Err(FrameError::LengthMismatch {
            len_words,
            expected_bytes,
            got_bytes: bytes.len(),
        });
    }

    // CRC covers MAGIC + LEN + body --- everything except the trailing
    // two CRC bytes themselves.
    let crc_payload_end = expected_bytes - 2;
    let computed = crc16_xmodem(&bytes[..crc_payload_end]);
    let got = u16::from_le_bytes([bytes[crc_payload_end], bytes[crc_payload_end + 1]]);
    if computed != got {
        return Err(FrameError::CrcMismatch { computed, got });
    }

    // Decode body words: skip 8 preamble bytes; read 4 bytes per word LE.
    let body_bytes = &bytes[8..crc_payload_end];
    let mut body = Vec::with_capacity(len_words as usize);
    for chunk in body_bytes.chunks_exact(4) {
        body.push(u32::from_le_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]));
    }
    Ok(body)
}

#[cfg(test)]
mod tests {
    use super::*;
    use mole_asm::frame::build_frame;

    #[test]
    fn round_trip_minimum_program() {
        // 1 body word (HALT status=0) is the minimum.
        let halt_word: u32 = 0x4000_0000; // HALT status=0
        let body = vec![halt_word];
        let frame = build_frame(&body).unwrap();
        let decoded = verify_frame(&frame).unwrap();
        assert_eq!(decoded, body);
    }

    #[test]
    fn round_trip_two_words() {
        let body = vec![0xDEAD_BEEFu32, 0x1234_5678u32];
        let frame = build_frame(&body).unwrap();
        assert_eq!(verify_frame(&frame).unwrap(), body);
    }

    #[test]
    fn rejects_short_buffer() {
        assert!(matches!(
            verify_frame(&[]),
            Err(FrameError::TooShort { got: 0 })
        ));
        assert!(matches!(
            verify_frame(&[0; 9]),
            Err(FrameError::TooShort { got: 9 })
        ));
    }

    #[test]
    fn rejects_bad_magic() {
        // Build a valid frame, then corrupt the magic bytes.
        let body = vec![0u32];
        let mut frame = build_frame(&body).unwrap();
        frame[0] ^= 0xFF;
        assert!(matches!(
            verify_frame(&frame),
            Err(FrameError::MagicMismatch { .. })
        ));
    }

    #[test]
    fn rejects_zero_len() {
        // Build a valid 1-body-word frame, then patch LEN to 0.
        let body = vec![0u32];
        let mut frame = build_frame(&body).unwrap();
        frame[4] = 0;
        frame[5] = 0;
        frame[6] = 0;
        frame[7] = 0;
        assert!(matches!(
            verify_frame(&frame),
            Err(FrameError::LengthOutOfRange { len_words: 0 })
        ));
    }

    #[test]
    fn rejects_oversize_len() {
        // Build a valid 1-body-word frame, then patch LEN above max.
        let body = vec![0u32];
        let mut frame = build_frame(&body).unwrap();
        let over = (MAX_PROGRAM_WORDS as u32) + 1;
        frame[4..8].copy_from_slice(&over.to_le_bytes());
        assert!(matches!(
            verify_frame(&frame),
            Err(FrameError::LengthOutOfRange { .. })
        ));
    }

    #[test]
    fn rejects_length_mismatch() {
        // Build a 1-body-word frame, then patch LEN to 7 (claims 7
        // body words but file has only 1).
        let body = vec![0u32];
        let mut frame = build_frame(&body).unwrap();
        let claimed: u32 = 7;
        frame[4..8].copy_from_slice(&claimed.to_le_bytes());
        let err = verify_frame(&frame).unwrap_err();
        assert!(matches!(
            err,
            FrameError::LengthMismatch {
                len_words: 7,
                expected_bytes: 38, // 4 + 4 + 7*4 + 2
                ..
            }
        ));
    }

    #[test]
    fn rejects_crc_corruption() {
        let body = vec![0xDEAD_BEEFu32];
        let mut frame = build_frame(&body).unwrap();
        let crc_lo_index = frame.len() - 2;
        frame[crc_lo_index] ^= 0xFF;
        let err = verify_frame(&frame).unwrap_err();
        assert!(matches!(err, FrameError::CrcMismatch { .. }));
    }

    #[test]
    fn round_trip_max_body_words() {
        let body = vec![0u32; MAX_PROGRAM_WORDS];
        let frame = build_frame(&body).unwrap();
        assert_eq!(verify_frame(&frame).unwrap().len(), MAX_PROGRAM_WORDS);
    }
}
