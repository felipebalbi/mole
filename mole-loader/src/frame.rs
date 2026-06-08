//! Host-side `.mole.bin` frame verification.
//!
// FIXME(B6): mole-abi v0.2 rewrite changed MAX_PROGRAM_WORDS from 2048 to
//            8192 and the frame format from 16-bit to 32-bit words with a
//            2-word preamble (MAGIC + length). The doc comments and the
//            verify_frame logic below still describe the v0 16-bit layout.
//            B6 rewrites this module for the v0.2 32-bit frame format.
//!
//! The encoder side --- [`mole_asm::frame::build_frame`] --- is
//! authoritative; this module is the inverse, used by the loader
//! to sanity-check an artifact *before* it is shipped over UART.
//!
//! The frame wire format (`fpga/Mole/WIRE_FORMAT.md`):
//!
//! ```text
//! [len_lo][len_hi][word_0_lo][word_0_hi]...[word_{n-1}_lo][word_{n-1}_hi][crc_lo][crc_hi]
//! ```
//!
//! - `len` is a u16 little-endian word count (`1..=2048`).
//! - Each program word is a u16 little-endian.
//! - The trailing two bytes are CRC-16/XMODEM over everything before
//!   the CRC (`len + words`), polynomial `0x1021`, init `0x0000`, no
//!   reflection, no XOR-out --- identical to
//!   [`mole_asm::frame::crc16_xmodem`].

use mole_asm::frame::crc16_xmodem;

use crate::error::FrameError;

/// Verify a host-built `.mole.bin` frame and return its word vector.
///
/// On success the returned `Vec<u16>` is the same word sequence the
/// encoder originally framed --- callers can re-use it for sanity
/// checks (e.g. expected program length, expected first opcode).
///
/// The function does **not** attempt any partial recovery: a single
/// CRC bit-flip rejects the whole frame. The engine's loader on the
/// FPGA side does the same thing on receive (the host has nothing
/// to lose by being symmetric).
///
/// # Errors
///
/// Returns [`FrameError::TooShort`] if `bytes.len() < 2` (not even
/// a length header), [`FrameError::LengthOutOfRange`] if the header
/// word count is outside `1..=2048`, [`FrameError::LengthMismatch`]
/// if the header length disagrees with the file size, and
/// [`FrameError::CrcMismatch`] if the trailing CRC does not match
/// our local computation.
pub fn verify_frame(bytes: &[u8]) -> Result<Vec<u16>, FrameError> {
    // We need at least the 2-byte length header to do anything
    // useful. Anything shorter is unambiguously "too short" --- we
    // cannot even tell what the file thought it was.
    if bytes.len() < 2 {
        return Err(FrameError::TooShort { got: bytes.len() });
    }

    // Validate the header value *before* checking the byte count.
    // The two failure modes are independent: a zero / oversize len
    // header is wrong whatever the byte count is, and surfacing it
    // as `LengthOutOfRange` is more informative than `TooShort`
    // (which the caller might fix by appending data).
    let len_words = u16::from_le_bytes([bytes[0], bytes[1]]);
    if !(1..=mole_abi::MAX_PROGRAM_WORDS as u16).contains(&len_words) {
        return Err(FrameError::LengthOutOfRange { len_words });
    }

    let expected_bytes = 2 + 2 * (len_words as usize) + 2;
    if bytes.len() != expected_bytes {
        return Err(FrameError::LengthMismatch {
            len_words,
            expected_bytes,
            got_bytes: bytes.len(),
        });
    }

    // CRC covers `len + words` --- everything except the trailing
    // two CRC bytes themselves.
    let crc_payload_end = expected_bytes - 2;
    let computed = crc16_xmodem(&bytes[..crc_payload_end]);
    let got = u16::from_le_bytes([bytes[crc_payload_end], bytes[crc_payload_end + 1]]);
    if computed != got {
        return Err(FrameError::CrcMismatch { computed, got });
    }

    // Decode words: skip the 2-byte length header.
    let mut words = Vec::with_capacity(len_words as usize);
    let words_bytes = &bytes[2..crc_payload_end];
    for chunk in words_bytes.chunks_exact(2) {
        words.push(u16::from_le_bytes([chunk[0], chunk[1]]));
    }
    Ok(words)
}

#[cfg(test)]
mod tests {
    use super::*;
    use mole_asm::frame::build_frame;

    #[test]
    fn round_trip_single_word() {
        // HALT status=0 -> [0x0000].
        let frame = build_frame(&[0x0000]).unwrap();
        assert_eq!(verify_frame(&frame).unwrap(), vec![0x0000]);
    }

    #[test]
    fn round_trip_two_words() {
        // WIRE_FORMAT.md §6 worked example: [0x9000, 0x6000].
        let frame = build_frame(&[0x9000, 0x6000]).unwrap();
        assert_eq!(verify_frame(&frame).unwrap(), vec![0x9000, 0x6000]);
    }

    #[test]
    fn rejects_short_buffer() {
        assert_eq!(verify_frame(&[]), Err(FrameError::TooShort { got: 0 }));
        assert_eq!(verify_frame(&[0]), Err(FrameError::TooShort { got: 1 }));
    }

    #[test]
    fn rejects_zero_len_header() {
        // Hand-built: len=0, no words, valid CRC over [0, 0].
        let mut buf = vec![0x00, 0x00];
        let crc = crc16_xmodem(&buf);
        buf.push((crc & 0xFF) as u8);
        buf.push((crc >> 8) as u8);
        assert_eq!(
            verify_frame(&buf),
            Err(FrameError::LengthOutOfRange { len_words: 0 })
        );
    }

    #[test]
    fn rejects_oversize_header() {
        // Hand-built: len=2049 (just past the encoder cap) but the
        // file is sized as if the header were honest. CRC arithmetic
        // doesn't matter --- the range check fires first.
        let header = 2049u16;
        let mut buf = vec![(header & 0xFF) as u8, (header >> 8) as u8];
        buf.extend(std::iter::repeat_n(0u8, 2049 * 2 + 2));
        assert_eq!(
            verify_frame(&buf),
            Err(FrameError::LengthOutOfRange { len_words: 2049 })
        );
    }

    #[test]
    fn rejects_length_mismatch() {
        // Header claims 5 words (= 14 expected bytes); file holds
        // bytes for 2 words (= 8 actual bytes).
        let mut frame = build_frame(&[0x1234, 0x5678]).unwrap();
        // Patch header to lie about length.
        frame[0] = 5;
        frame[1] = 0;
        let err = verify_frame(&frame).unwrap_err();
        assert!(matches!(
            err,
            FrameError::LengthMismatch {
                len_words: 5,
                expected_bytes: 14,
                got_bytes: 8,
            }
        ));
    }

    #[test]
    fn rejects_crc_corruption() {
        let mut frame = build_frame(&[0x9000, 0x6000]).unwrap();
        let crc_lo_index = frame.len() - 2;
        frame[crc_lo_index] ^= 0xFF;
        let err = verify_frame(&frame).unwrap_err();
        assert!(matches!(err, FrameError::CrcMismatch { .. }));
    }

    #[test]
    fn round_trip_2048_words_max_program() {
        // 2048 words is the engine's program-memory budget; verify we
        // accept it without an off-by-one near the cap.
        let words = vec![0xC000u16; 2048];
        let frame = build_frame(&words).unwrap();
        assert_eq!(verify_frame(&frame).unwrap(), words);
    }
}
