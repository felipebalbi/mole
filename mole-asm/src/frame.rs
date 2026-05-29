//! Wire-format helpers: CRC-16/XMODEM, raw bytecode packing, and the
//! length + words + CRC frame builder consumed by the iCE40 UART
//! ingest path.
//!
//! Mirrors `tests/fixtures/mole-asm.py::{crc16_xmodem, pack_bytecode,
//! build_frame}` byte-for-byte.

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

/// Pack 16-bit instruction words little-endian. This is the raw
/// `.molecode` payload: no frame, no CRC --- just the bytes that would
/// land in SPRAM at runtime.
pub fn pack_bytecode(words: &[u16]) -> Vec<u8> {
    let mut out = Vec::with_capacity(words.len() * 2);
    for &w in words {
        out.push((w & 0xFF) as u8);
        out.push((w >> 8) as u8);
    }
    out
}

/// Build a complete UART frame: `len_lo`, `len_hi`, words (each LE),
/// `crc_lo`, `crc_hi`. CRC covers everything except itself.
///
/// Per `WIRE_FORMAT.md` the engine accepts 1..=2048 words per frame
/// (matching the 11-bit JMP / branch addr field after the opcode
/// widening); anything outside that range returns
/// [`AsmError::FrameTooLarge`].
pub fn build_frame(words: &[u16]) -> Result<Vec<u8>, AsmError> {
    if !(1..=mole_abi::MAX_PROGRAM_WORDS).contains(&words.len()) {
        return Err(AsmError::FrameTooLarge {
            word_count: words.len(),
        });
    }
    let n = words.len() as u16;
    let mut payload = Vec::with_capacity(2 + words.len() * 2 + 2);
    payload.push((n & 0xFF) as u8);
    payload.push((n >> 8) as u8);
    for &w in words {
        payload.push((w & 0xFF) as u8);
        payload.push((w >> 8) as u8);
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
        // Standard CRC-16/XMODEM check value.
        assert_eq!(crc16_xmodem(b"123456789"), 0x31C3);
    }

    #[test]
    fn crc_empty_is_zero() {
        // CRC-16/XMODEM over the empty string is the init value (0).
        assert_eq!(crc16_xmodem(b""), 0x0000);
    }

    #[test]
    fn pack_bytecode_is_little_endian() {
        assert_eq!(
            pack_bytecode(&[0x1234, 0xABCD]),
            vec![0x34, 0x12, 0xCD, 0xAB]
        );
    }

    #[test]
    fn pack_bytecode_empty() {
        assert!(pack_bytecode(&[]).is_empty());
    }

    #[test]
    fn build_frame_wire_format_example() {
        // WIRE_FORMAT.md §6 worked example: two words [0x9000, 0x6000]
        // -> "02 00 00 90 00 60 DF 9F".
        let frame = build_frame(&[0x9000, 0x6000]).unwrap();
        assert_eq!(frame, vec![0x02, 0x00, 0x00, 0x90, 0x00, 0x60, 0xDF, 0x9F]);
    }

    #[test]
    fn build_frame_rejects_empty() {
        assert!(matches!(
            build_frame(&[]),
            Err(AsmError::FrameTooLarge { word_count: 0 })
        ));
    }

    #[test]
    fn build_frame_rejects_oversize() {
        let big = vec![0u16; 2049];
        assert!(matches!(
            build_frame(&big),
            Err(AsmError::FrameTooLarge { word_count: 2049 })
        ));
    }

    #[test]
    fn build_frame_accepts_max() {
        let max = vec![0u16; 2048];
        // Just verify it does not error and yields the expected length:
        // 2 (len) + 2048 * 2 (words) + 2 (crc) = 4100 bytes.
        let frame = build_frame(&max).unwrap();
        assert_eq!(frame.len(), 2 + 2048 * 2 + 2);
    }
}
