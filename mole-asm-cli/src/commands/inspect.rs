//! `inspect` subcommand: decode the header of a `.molecode` or
//! `.mole.bin` file and print a human-readable summary.
//!
//! Exit code: 0 = clean, 2 = header / CRC / length mismatch.

use std::path::PathBuf;

use color_eyre::eyre::{Context, Result};

use crate::decode::decode_word;

/// Constants from §10.
const MAGIC: u32 = 0x0002_4D4C;
const FORMAT_VERSION: u16 = 0x0002;
const PREAMBLE_WORDS: usize = 2;

#[derive(Debug, clap::Args)]
pub struct InspectArgs {
    /// File to inspect (.molecode or .mole.bin).
    pub input: PathBuf,

    /// Treat input as a raw packed bytecode file (`.molecode`).
    /// Mutually exclusive with `--frame`.
    #[arg(long = "raw", conflicts_with = "frame")]
    pub raw: bool,

    /// Treat input as a framed file (`.mole.bin` with len+CRC envelope).
    /// Mutually exclusive with `--raw`.
    #[arg(long = "frame", conflicts_with = "raw")]
    pub frame: bool,
}

/// Run the inspect subcommand.  Returns Ok(()) for a clean file,
/// or Ok(()) after printing errors (exit code is handled in main).
/// Returns Err only for I/O failures.
pub fn run(args: &InspectArgs) -> Result<bool> {
    let path = &args.input;
    let raw_bytes =
        std::fs::read(path).wrap_err_with(|| format!("failed to read {}", path.display()))?;

    let file_size = raw_bytes.len();
    println!("File:           {} ({file_size} bytes)", path.display());

    // Determine framed/raw mode.
    let (is_frame, body_bytes) = detect_mode(&raw_bytes, args);
    let mut ok = true;

    if is_frame {
        let result = inspect_frame(&raw_bytes);
        match result {
            Ok(stripped) => {
                println!("Frame mode:     yes  CRC OK");
                inspect_molecode(&stripped, &mut ok);
            }
            Err(msg) => {
                println!("Frame mode:     yes  {msg}");
                ok = false;
            }
        }
    } else {
        println!("Frame mode:     no  (raw)");
        inspect_molecode(body_bytes, &mut ok);
    }

    Ok(ok)
}

/// Try to strip the UART envelope and return the bytecode body.
/// Returns Err with a diagnostic string on any failure.
fn inspect_frame(raw: &[u8]) -> std::result::Result<Vec<u8>, String> {
    if raw.len() < 4 {
        return Err("error: file too short to contain frame header".to_string());
    }
    // Frame layout: len(2) | body(4*N) | crc(2)
    let len_field = u16::from_le_bytes([raw[0], raw[1]]) as usize;
    let body_byte_count = len_field * 4;
    let expected_total = 2 + body_byte_count + 2;
    if raw.len() != expected_total {
        return Err(format!(
            "error: length field={len_field} words implies {expected_total} bytes \
             but file is {} bytes",
            raw.len()
        ));
    }
    // Verify CRC over everything except the trailing 2 CRC bytes.
    let data = &raw[..raw.len() - 2];
    let computed = mole_asm::crc::xmodem(data);
    let stored = u16::from_le_bytes([raw[raw.len() - 2], raw[raw.len() - 1]]);
    if computed != stored {
        return Err(format!(
            "error: CRC mismatch (stored=0x{stored:04X} computed=0x{computed:04X})"
        ));
    }
    // Extract body bytes (skip 2-byte len prefix, skip trailing CRC).
    Ok(raw[2..raw.len() - 2].to_vec())
}

/// Detect whether the file looks like a frame or raw bytecode.
/// Explicit flags take priority; fallback auto-detects.
/// Returns `(is_frame, body_slice)`.
fn detect_mode<'a>(raw: &'a [u8], args: &InspectArgs) -> (bool, &'a [u8]) {
    if args.frame {
        return (true, raw);
    }
    if args.raw {
        return (false, raw);
    }
    // Auto-detect: does the len field + 4 match file size?
    if raw.len() >= 4 {
        let len_field = u16::from_le_bytes([raw[0], raw[1]]) as usize;
        let body_byte_count = len_field * 4;
        let expected_total = 2 + body_byte_count + 2;
        if raw.len() == expected_total && raw.len() >= 4 {
            return (true, raw);
        }
    }
    (false, raw)
}

/// Inspect a raw bytecode blob (no UART framing): print preamble
/// fields and first/last instruction summary. Sets `ok=false` on
/// any inconsistency.
fn inspect_molecode(bytes: &[u8], ok: &mut bool) {
    if bytes.len() % 4 != 0 {
        eprintln!(
            "error: bytecode length {} is not a multiple of 4",
            bytes.len()
        );
        *ok = false;
        return;
    }
    if bytes.len() < PREAMBLE_WORDS * 4 {
        eprintln!(
            "error: file too short to contain preamble \
             ({} bytes, need at least {})",
            bytes.len(),
            PREAMBLE_WORDS * 4
        );
        *ok = false;
        return;
    }

    let words: Vec<u32> = bytes
        .chunks_exact(4)
        .map(|c| u32::from_le_bytes([c[0], c[1], c[2], c[3]]))
        .collect();

    // --- Preamble word 0: magic (low 16) + version (high 16) ---
    let word0 = words[0];
    let magic_field = word0 & 0x0000_FFFF;
    let version_field = ((word0 >> 16) & 0xFFFF) as u16;

    let magic_status = if magic_field == (MAGIC & 0xFFFF) {
        "OK"
    } else {
        *ok = false;
        "MISMATCH"
    };
    let version_status = if version_field == FORMAT_VERSION {
        "OK"
    } else {
        *ok = false;
        "UNKNOWN"
    };

    // --- Preamble word 1: body length in 32-bit words ---
    let declared_body = words[1] as usize;
    let actual_body = words.len() - PREAMBLE_WORDS;

    let body_status = if declared_body == actual_body {
        "OK".to_string()
    } else {
        *ok = false;
        format!("MISMATCH(actual: {actual_body})")
    };

    println!("Preamble:");
    println!("  Magic:        0x{magic_field:04X}   {magic_status}");
    println!("  Version:      0x{version_field:04X}   {version_status}");
    println!("  Body length:  {declared_body} words");

    println!();
    println!("Body:");
    println!("  Total:        {actual_body} instructions  {body_status}");

    if actual_body == 0 {
        println!("  (empty body)");
        return;
    }

    let first_word = words[PREAMBLE_WORDS];
    let last_word = words[words.len() - 1];

    let first_dec = decode_word(first_word);
    let last_dec = decode_word(last_word);

    println!("  First:        0x{first_word:08X}   {}", first_dec.text);
    println!("  Last:         0x{last_word:08X}   {}", last_dec.text);
}
