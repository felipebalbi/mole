//! Host-side `.mole.bin` frame verification.
//!
//! The encoder side --- [`mole_asm::frame::build_frame`] --- is
//! authoritative; this module is the inverse, used by the loader
//! to sanity-check an artifact *before* it is shipped over UART.
//!
//! v0.2 frame wire format (see §10):
//!
//! ```text
//! [len_lo][len_hi]                      u16 LE total word count
//!                                       (preamble + body)
//! [w0_b0][w0_b1][w0_b2][w0_b3] ...     N × 32-bit LE words
//! [crc_lo][crc_hi]                      CRC-16/XMODEM over all
//!                                       preceding bytes
//! ```
//!
//! - `len` is a u16 little-endian *total* word count (preamble +
//!   body), valid range
//!   `(PREAMBLE_WORDS + 1)..=(MAX_PROGRAM_WORDS + PREAMBLE_WORDS)`
//!   = `3..=8194`. Length 2 (preamble only, zero body words) is
//!   rejected per §16.5 minimum-length rule.
//! - Each program word is a u32 little-endian (4 bytes).
//! - The trailing two bytes are CRC-16/XMODEM over everything before
//!   the CRC (`len_bytes + word_bytes`), polynomial `0x1021`, init
//!   `0x0000`, no reflection, no XOR-out --- identical to
//!   [`mole_asm::frame::crc16_xmodem`].

use mole_abi::{MAX_PROGRAM_WORDS, PREAMBLE_WORDS};
use mole_asm::frame::crc16_xmodem;

use crate::error::FrameError;

/// Verify a host-built `.mole.bin` frame and return its word vector.
///
/// On success the returned `Vec<u32>` is the same word sequence the
/// encoder originally framed (preamble + body) --- callers should
/// pass it to [`crate::verify_program`] for the magic / version /
/// reserved-status checks (§16.1, §16.2, §16.4).
///
/// The function does **not** attempt any partial recovery: a single
/// CRC bit-flip rejects the whole frame.
///
/// # Errors
///
/// - [`FrameError::TooShort`]: `bytes.len() < 2` (no length header).
/// - [`FrameError::LengthOutOfRange`]: header word count is outside
///   `(PREAMBLE_WORDS + 1)..=(MAX_PROGRAM_WORDS + PREAMBLE_WORDS)`
///   i.e. `3..=8194`; includes the §16.5 empty-program rejection
///   (length 0 and length 2 both fall below the lower bound).
/// - [`FrameError::LengthMismatch`]: header length disagrees with
///   the file's actual byte count.
/// - [`FrameError::CrcMismatch`]: trailing CRC does not match.
pub fn verify_frame(bytes: &[u8]) -> Result<Vec<u32>, FrameError> {
    // We need at least the 2-byte length header to do anything
    // useful. Anything shorter is unambiguously "too short" --- we
    // cannot even tell what the file thought it was.
    if bytes.len() < 2 {
        return Err(FrameError::TooShort { got: bytes.len() });
    }

    // Validate the header value *before* checking the byte count.
    // The valid range is [PREAMBLE_WORDS+1 .. MAX_PROGRAM_WORDS +
    // PREAMBLE_WORDS] = [3..=8194]. This also covers the §16.5
    // empty-program rejection: a length-field value of PREAMBLE_WORDS
    // (= 2) means zero body words and is below the lower bound.
    let len_words = u16::from_le_bytes([bytes[0], bytes[1]]);
    let lo = (PREAMBLE_WORDS + 1) as u16;
    let hi = (MAX_PROGRAM_WORDS + PREAMBLE_WORDS) as u16;
    if !(lo..=hi).contains(&len_words) {
        return Err(FrameError::LengthOutOfRange { len_words });
    }

    // Each word is 4 bytes; the frame also carries the 2-byte length
    // header and the 2-byte trailing CRC.
    let expected_bytes = 2 + 4 * (len_words as usize) + 2;
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

    // Decode words: skip the 2-byte length header; read 4 bytes per
    // word, little-endian.
    let mut words = Vec::with_capacity(len_words as usize);
    let words_bytes = &bytes[2..crc_payload_end];
    for chunk in words_bytes.chunks_exact(4) {
        words.push(u32::from_le_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]));
    }
    Ok(words)
}

#[cfg(test)]
mod tests {
    use super::*;
    use mole_asm::frame::build_frame;

    #[test]
    fn round_trip_minimum_program() {
        // Preamble (2 words) + 1 body word = 3 words total.
        let magic_word = mole_abi::MAGIC;
        let body_len: u32 = 1;
        let halt_word: u32 = 0x0400_0000; // HALT status=0
        let words = vec![magic_word, body_len, halt_word];
        let frame = build_frame(&words).unwrap();
        let decoded = verify_frame(&frame).unwrap();
        assert_eq!(decoded, words);
    }

    #[test]
    fn round_trip_via_build_frame() {
        // Round-trip through the encoder's build_frame to confirm
        // byte-for-byte compatibility.
        let magic = mole_abi::MAGIC;
        let body_len: u32 = 2;
        let w1: u32 = 0xDEAD_BEEF;
        let w2: u32 = 0x1234_5678;
        let words = vec![magic, body_len, w1, w2];
        let frame = build_frame(&words).unwrap();
        assert_eq!(verify_frame(&frame).unwrap(), words);
    }

    #[test]
    fn rejects_short_buffer() {
        assert_eq!(verify_frame(&[]), Err(FrameError::TooShort { got: 0 }));
        assert_eq!(verify_frame(&[0]), Err(FrameError::TooShort { got: 1 }));
    }

    #[test]
    fn rejects_zero_len_header() {
        // len=0: below the lower bound (3).
        let mut buf = vec![0x00u8, 0x00u8];
        let crc = crc16_xmodem(&buf);
        buf.push((crc & 0xFF) as u8);
        buf.push((crc >> 8) as u8);
        assert_eq!(
            verify_frame(&buf),
            Err(FrameError::LengthOutOfRange { len_words: 0 })
        );
    }

    #[test]
    fn rejects_preamble_only_len_2() {
        // len=2 means zero body words (preamble only): §16.5 empty
        // program rejection.
        let header = 2u16;
        let mut buf = header.to_le_bytes().to_vec();
        // Body: 2 × 4 = 8 bytes of zeros.
        buf.extend_from_slice(&[0u8; 8]);
        let crc = crc16_xmodem(&buf);
        buf.push((crc & 0xFF) as u8);
        buf.push((crc >> 8) as u8);
        assert_eq!(
            verify_frame(&buf),
            Err(FrameError::LengthOutOfRange { len_words: 2 })
        );
    }

    #[test]
    fn rejects_oversize_header() {
        // len = MAX_PROGRAM_WORDS + PREAMBLE_WORDS + 1 = 8195:
        // just past the encoder cap. Range check fires first.
        let over: u16 = (MAX_PROGRAM_WORDS + PREAMBLE_WORDS + 1) as u16;
        let mut buf = over.to_le_bytes().to_vec();
        // Body sized to match the claimed length, so the byte-count
        // check doesn't fire before the range check.
        buf.extend(std::iter::repeat_n(0u8, (over as usize) * 4 + 2));
        assert_eq!(
            verify_frame(&buf),
            Err(FrameError::LengthOutOfRange { len_words: over })
        );
    }

    #[test]
    fn rejects_length_mismatch() {
        // Build a valid 3-word frame (len=3, 12 body bytes + CRC),
        // then patch the header to lie (claim len=7).
        let words = vec![mole_abi::MAGIC, 1u32, 0u32];
        let mut frame = build_frame(&words).unwrap();
        frame[0] = 7;
        frame[1] = 0;
        let err = verify_frame(&frame).unwrap_err();
        assert!(matches!(
            err,
            FrameError::LengthMismatch {
                len_words: 7,
                expected_bytes: 32, // 2 + 7*4 + 2
                ..
            }
        ));
    }

    #[test]
    fn rejects_crc_corruption() {
        let words = vec![mole_abi::MAGIC, 1u32, 0xDEAD_BEEFu32];
        let mut frame = build_frame(&words).unwrap();
        let crc_lo_index = frame.len() - 2;
        frame[crc_lo_index] ^= 0xFF;
        let err = verify_frame(&frame).unwrap_err();
        assert!(matches!(err, FrameError::CrcMismatch { .. }));
    }

    #[test]
    fn round_trip_max_program_words() {
        // 8192 body words + 2 preamble = 8194 total; verify no
        // off-by-one near the cap.
        let mut words = vec![0u32; MAX_PROGRAM_WORDS + PREAMBLE_WORDS];
        words[0] = mole_abi::MAGIC;
        words[1] = MAX_PROGRAM_WORDS as u32;
        let frame = build_frame(&words).unwrap();
        assert_eq!(verify_frame(&frame).unwrap(), words);
    }

    #[test]
    fn length_field_is_total_word_count() {
        // The length field (first 2 bytes, LE u16) must equal the
        // total word count (preamble + body).
        let words = vec![mole_abi::MAGIC, 1u32, 0xCAFE_BABEu32]; // 3 words
        let frame = build_frame(&words).unwrap();
        let len_field = u16::from_le_bytes([frame[0], frame[1]]);
        assert_eq!(len_field, 3);
    }
}
