//! Wire-format helpers: CRC-16/XMODEM, raw bytecode packing, and the
//! length-prefixed + CRC-signed UART frame builder.
//!
//! v0.2 bytecode uses 32-bit instruction words (little-endian). The
//! preamble (2 words) is already embedded in the `words` slice
//! passed to [`build_frame`]; the frame format is otherwise unchanged
//! from v0.

use mole_abi::{MAX_PROGRAM_WORDS, PREAMBLE_WORDS};

use crate::error::AsmError;

/// Maximum total program length in 32-bit words (preamble + body).
/// Derived from `mole_abi::MAX_PROGRAM_WORDS + mole_abi::PREAMBLE_WORDS`
/// per §10. The frame builder enforces this limit.
const MAX_TOTAL_WORDS: usize = MAX_PROGRAM_WORDS + PREAMBLE_WORDS;

/// CRC-16/XMODEM (poly `0x1021`, init `0x0000`, no reflection, no
/// XOR-out).
///
/// Catalog check value: `crc16_xmodem(b"123456789") == 0x31C3`.
pub fn crc16_xmodem(bytes: &[u8]) -> u16 {
    let mut crc: u16 = 0x0000;
    for &b in bytes {
        crc ^= (b as u16) << 8;
        for _ in 0..8 {
            crc = if crc & 0x8000 != 0 {
                (crc << 1) ^ 0x1021
            } else {
                crc << 1
            };
        }
    }
    crc
}

/// Pack 32-bit instruction words little-endian into bytes.
///
/// This is the raw `.molecode` payload: no frame, no CRC, no
/// preamble stripping --- just the bytes that would land in SPRAM
/// at runtime.
pub fn pack_bytecode(words: &[u32]) -> Vec<u8> {
    let mut out = Vec::with_capacity(words.len() * 4);
    for &w in words {
        out.push((w & 0xFF) as u8);
        out.push(((w >> 8) & 0xFF) as u8);
        out.push(((w >> 16) & 0xFF) as u8);
        out.push((w >> 24) as u8);
    }
    out
}

/// Build a complete UART frame from a program word vector.
///
/// The `words` slice must include the 2-word preamble (as produced
/// by [`crate::assemble`]). The frame layout is:
///
/// ```text
/// [len_lo, len_hi]           u16 LE: total word count (preamble+body)
/// [w0_b0, w0_b1, w0_b2, w0_b3, ...]  32-bit words, each LE
/// [crc_lo, crc_hi]           CRC-16/XMODEM over all preceding bytes
/// ```
///
/// Rejects programs with `words.len() < PREAMBLE_WORDS + 1` (no body)
/// or `words.len() > MAX_TOTAL_WORDS` (exceeds SPRAM budget).
///
/// # Errors
///
/// Returns [`AsmError::FrameTooLarge`] when the length is outside the
/// valid range.
pub fn build_frame(words: &[u32]) -> Result<Vec<u8>, AsmError> {
    if words.len() < PREAMBLE_WORDS + 1 || words.len() > MAX_TOTAL_WORDS {
        return Err(AsmError::FrameTooLarge {
            word_count: words.len(),
        });
    }
    let n = words.len() as u16;
    let mut payload = Vec::with_capacity(2 + words.len() * 4 + 2);
    payload.push((n & 0xFF) as u8);
    payload.push((n >> 8) as u8);
    for &w in words {
        payload.push((w & 0xFF) as u8);
        payload.push(((w >> 8) & 0xFF) as u8);
        payload.push(((w >> 16) & 0xFF) as u8);
        payload.push((w >> 24) as u8);
    }
    let crc = crc16_xmodem(&payload);
    payload.push((crc & 0xFF) as u8);
    payload.push((crc >> 8) as u8);
    Ok(payload)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn crc_catalog_check() {
        assert_eq!(crc16_xmodem(b"123456789"), 0x31C3);
    }

    #[test]
    fn crc_empty_is_zero() {
        assert_eq!(crc16_xmodem(b""), 0x0000);
    }

    #[test]
    fn pack_bytecode_is_little_endian() {
        // 0x12345678 → bytes [78, 56, 34, 12]
        assert_eq!(pack_bytecode(&[0x1234_5678]), vec![0x78, 0x56, 0x34, 0x12]);
    }

    #[test]
    fn pack_bytecode_empty() {
        assert!(pack_bytecode(&[]).is_empty());
    }

    #[test]
    fn build_frame_rejects_preamble_only() {
        // Exactly PREAMBLE_WORDS words, no body → FrameTooLarge.
        let preamble_only = vec![0u32; PREAMBLE_WORDS];
        assert!(matches!(
            build_frame(&preamble_only),
            Err(AsmError::FrameTooLarge { .. })
        ));
    }

    #[test]
    fn build_frame_rejects_empty() {
        assert!(matches!(
            build_frame(&[]),
            Err(AsmError::FrameTooLarge { word_count: 0 })
        ));
    }

    #[test]
    fn build_frame_accepts_minimum() {
        // Preamble + 1 body word = 3 words total.
        let words = vec![0u32; PREAMBLE_WORDS + 1];
        let frame = build_frame(&words).unwrap();
        // len(2) + 3*4 bytes + crc(2) = 16 bytes.
        assert_eq!(frame.len(), 2 + 3 * 4 + 2);
    }

    #[test]
    fn build_frame_accepts_max() {
        // MAX_TOTAL_WORDS = 8194 words.
        let max = vec![0u32; MAX_TOTAL_WORDS];
        let frame = build_frame(&max).unwrap();
        assert_eq!(frame.len(), 2 + MAX_TOTAL_WORDS * 4 + 2);
    }

    #[test]
    fn build_frame_rejects_over_max() {
        let big = vec![0u32; MAX_TOTAL_WORDS + 1];
        assert!(matches!(
            build_frame(&big),
            Err(AsmError::FrameTooLarge { .. })
        ));
    }

    #[test]
    fn length_field_is_total_word_count() {
        // The length field (first 2 bytes, LE u16) must equal the total
        // word count (preamble + body).
        let words: Vec<u32> = vec![0xDEAD_BEEF, 0x0000_0003, 0x1234_5678]; // 3 words
        let frame = build_frame(&words).unwrap();
        let len_field = u16::from_le_bytes([frame[0], frame[1]]);
        assert_eq!(len_field, 3);
    }
}
