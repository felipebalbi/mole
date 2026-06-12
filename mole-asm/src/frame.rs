//! Wire-format helpers: CRC-16/XMODEM, raw bytecode packing, and the
//! magic + length + CRC UART frame builder.
//!
//! v0.2 wire format (no v0 back-compat):
//!
//! ```text
//! [MAGIC: 4 bytes LE = 0x0002_4D4C]
//! [LEN:   4 bytes LE = N (body word count)]
//! [body:  4 * N bytes (N × 32-bit instructions, each LE)]
//! [CRC:   2 bytes LE = CRC-16/XMODEM over all preceding bytes]
//!
//! Total wire = 10 + 4 * N bytes
//! ```
//!
//! MAGIC and LEN together are the preamble (8 bytes). They are NOT
//! 32-bit instructions in the body --- they are the host-link header
//! consumed by the MoleLoaderFsm to validate the frame and learn the
//! body size before writing the body into SPRAM. The body that follows
//! is just program instructions; the engine fetches them from SPRAM
//! starting at PC=0.

use mole_abi::{MAGIC, MAX_PROGRAM_WORDS};

use crate::error::AsmError;

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
/// preamble --- just the bytes that would land in SPRAM at runtime.
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

/// Build a complete UART frame from a body-only word vector.
///
/// `body` is the program instruction stream WITHOUT any preamble ---
/// just the words that will be loaded into SPRAM starting at PC=0.
/// This function prepends the 4-byte MAGIC + 4-byte LEN preamble and
/// appends the 2-byte CRC trailer.
///
/// # Errors
///
/// Returns [`AsmError::FrameTooLarge`] when the body length is 0 or
/// exceeds `MAX_PROGRAM_WORDS`.
pub fn build_frame(body: &[u32]) -> Result<Vec<u8>, AsmError> {
    if body.is_empty() || body.len() > MAX_PROGRAM_WORDS {
        return Err(AsmError::FrameTooLarge {
            word_count: body.len(),
        });
    }
    let n = body.len() as u32;
    let mut payload = Vec::with_capacity(4 + 4 + body.len() * 4 + 2);
    // MAGIC (4 bytes LE).
    payload.push((MAGIC & 0xFF) as u8);
    payload.push(((MAGIC >> 8) & 0xFF) as u8);
    payload.push(((MAGIC >> 16) & 0xFF) as u8);
    payload.push((MAGIC >> 24) as u8);
    // LEN (4 bytes LE).
    payload.push((n & 0xFF) as u8);
    payload.push(((n >> 8) & 0xFF) as u8);
    payload.push(((n >> 16) & 0xFF) as u8);
    payload.push((n >> 24) as u8);
    // Body (4*N bytes LE).
    for &w in body {
        payload.push((w & 0xFF) as u8);
        payload.push(((w >> 8) & 0xFF) as u8);
        payload.push(((w >> 16) & 0xFF) as u8);
        payload.push((w >> 24) as u8);
    }
    // CRC (2 bytes LE) over all preceding bytes.
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
    fn build_frame_rejects_empty_body() {
        // Zero body words → FrameTooLarge (rejected).
        assert!(matches!(
            build_frame(&[]),
            Err(AsmError::FrameTooLarge { word_count: 0 })
        ));
    }

    #[test]
    fn build_frame_accepts_minimum() {
        // 1 body word is the minimum.
        let body = vec![0u32; 1];
        let frame = build_frame(&body).unwrap();
        // Wire: 4B magic + 4B len + 1*4B body + 2B CRC = 14 bytes.
        assert_eq!(frame.len(), 4 + 4 + 4 + 2);
    }

    #[test]
    fn build_frame_accepts_max() {
        // MAX_PROGRAM_WORDS body words.
        let max = vec![0u32; mole_abi::MAX_PROGRAM_WORDS];
        let frame = build_frame(&max).unwrap();
        // Wire: 4B magic + 4B len + N*4B body + 2B CRC.
        assert_eq!(frame.len(), 4 + 4 + mole_abi::MAX_PROGRAM_WORDS * 4 + 2);
    }

    #[test]
    fn build_frame_rejects_over_max() {
        let big = vec![0u32; mole_abi::MAX_PROGRAM_WORDS + 1];
        assert!(matches!(
            build_frame(&big),
            Err(AsmError::FrameTooLarge { .. })
        ));
    }

    #[test]
    fn build_frame_magic_word_first() {
        // First 4 bytes must be MAGIC = 0x0002_4D4C, little-endian
        // (= bytes 4C 4D 02 00 on the wire).
        let body = vec![0xDEAD_BEEFu32];
        let frame = build_frame(&body).unwrap();
        let got_magic = u32::from_le_bytes([frame[0], frame[1], frame[2], frame[3]]);
        assert_eq!(got_magic, mole_abi::MAGIC);
    }

    #[test]
    fn build_frame_len_field_is_body_word_count() {
        // The LEN field at bytes [4..8] must equal the body word count
        // (NOT including the 2-word MAGIC + LEN preamble).
        let body = vec![0xDEAD_BEEFu32, 0x1234_5678u32, 0xCAFE_BABEu32];
        let frame = build_frame(&body).unwrap();
        let len_field = u32::from_le_bytes([frame[4], frame[5], frame[6], frame[7]]);
        assert_eq!(len_field, body.len() as u32);
    }
}
