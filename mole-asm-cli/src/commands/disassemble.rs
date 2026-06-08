//! `disassemble` subcommand: decode a `.molecode` / `.mole.bin` file
//! back into moleasm source text.
//!
//! Exit code: 0 = all instructions decoded cleanly; 2 = at least one
//! unknown/reserved word was encountered (`.dw` fallback used).

use std::io::Write;
use std::path::PathBuf;

use color_eyre::eyre::{Context, Result};

use crate::decode::decode_word;

/// Frame-detection and strip logic is inlined here (same heuristic as
/// `inspect`).  The two subcommands share the same detection rules but
/// are kept separate to avoid coupling their Args structs.

const PREAMBLE_WORDS: usize = 2;

#[derive(Debug, clap::Args)]
pub struct DisassembleArgs {
    /// File to disassemble (.molecode or .mole.bin).
    pub input: PathBuf,

    /// Output path for the moleasm source.
    /// Defaults to stdout when omitted.
    #[arg(short = 'o', long = "output", value_name = "PATH")]
    pub output: Option<PathBuf>,

    /// Treat input as a raw packed bytecode file (`.molecode`).
    /// Mutually exclusive with `--frame`.
    #[arg(long = "raw", conflicts_with = "frame")]
    pub raw: bool,

    /// Treat input as a framed file (`.mole.bin` with len+CRC envelope).
    /// Mutually exclusive with `--raw`.
    #[arg(long = "frame", conflicts_with = "raw")]
    pub frame: bool,
}

/// Run the disassemble subcommand.
/// Returns `true` if all instructions decoded cleanly (exit 0),
/// `false` if any `.dw` fallbacks were needed (exit 2).
pub fn run(args: &DisassembleArgs) -> Result<bool> {
    let raw_bytes = std::fs::read(&args.input)
        .wrap_err_with(|| format!("failed to read {}", args.input.display()))?;

    // Reuse the same frame-detection logic as inspect.
    let body_bytes = extract_body(&raw_bytes, args)?;

    if body_bytes.len() % 4 != 0 {
        color_eyre::eyre::bail!(
            "bytecode length {} is not a multiple of 4",
            body_bytes.len()
        );
    }
    if body_bytes.len() < PREAMBLE_WORDS * 4 {
        color_eyre::eyre::bail!(
            "file too short to contain preamble ({} bytes, need {})",
            body_bytes.len(),
            PREAMBLE_WORDS * 4
        );
    }

    let words: Vec<u32> = body_bytes
        .chunks_exact(4)
        .map(|c| u32::from_le_bytes([c[0], c[1], c[2], c[3]]))
        .collect();

    // Skip the 2-word preamble.
    let body = &words[PREAMBLE_WORDS..];

    let mut had_unknowns = false;
    let mut lines: Vec<String> = Vec::with_capacity(body.len());

    for (idx, &word) in body.iter().enumerate() {
        let pc = idx; // 0-based PC within the body
        let decoded = decode_word(word);
        if !decoded.known {
            had_unknowns = true;
        }

        // For BRANCH_ON, annotate the resolved target PC.
        let extra = branch_target_comment(&decoded.text, pc, body.len());
        let line = format!(
            "  {:<40}  ; word=0x{word:08X}  PC={pc}{}",
            decoded.text, extra
        );
        lines.push(line);
    }

    // Write output.
    if let Some(out_path) = &args.output {
        let mut f = std::fs::File::create(out_path)
            .wrap_err_with(|| format!("failed to create {}", out_path.display()))?;
        for line in &lines {
            writeln!(f, "{line}")?;
        }
    } else {
        for line in &lines {
            println!("{line}");
        }
    }

    Ok(!had_unknowns)
}

/// If `text` is a BRANCH_ON instruction, compute and return a
/// `  -> PC=N` comment showing the resolved target.  Otherwise "".
fn branch_target_comment(text: &str, pc: usize, body_len: usize) -> String {
    if !text.starts_with("BRANCH_ON") {
        return String::new();
    }
    // Parse the offset: last token after the comma.
    let offset_str = match text.rsplit_once(", ") {
        Some((_, o)) => o.trim(),
        None => return String::new(),
    };
    let offset: i64 = match offset_str.parse() {
        Ok(v) => v,
        Err(_) => return String::new(),
    };
    // target_pc = branch_pc + 1 + offset
    let target = pc as i64 + 1 + offset;
    if target < 0 || target >= body_len as i64 {
        format!("  -> PC={target} (out of range)")
    } else {
        format!("  -> PC={target}")
    }
}

/// Extract the bytecode body bytes (including preamble), stripping
/// the UART frame envelope when present.
fn extract_body<'a>(raw: &'a [u8], args: &DisassembleArgs) -> Result<&'a [u8]> {
    if args.frame {
        return strip_frame(raw);
    }
    if args.raw {
        return Ok(raw);
    }
    // Auto-detect: same heuristic as inspect.
    if raw.len() >= 4 {
        let len_field = u16::from_le_bytes([raw[0], raw[1]]) as usize;
        let body_byte_count = len_field * 4;
        let expected_total = 2 + body_byte_count + 2;
        if raw.len() == expected_total {
            return strip_frame(raw);
        }
    }
    Ok(raw)
}

/// Strip the 2-byte len prefix and 2-byte CRC suffix from a framed
/// bytecode file, verifying the CRC in the process.
fn strip_frame(raw: &[u8]) -> Result<&[u8]> {
    if raw.len() < 4 {
        color_eyre::eyre::bail!("file too short to contain frame header");
    }
    let len_field = u16::from_le_bytes([raw[0], raw[1]]) as usize;
    let expected_total = 2 + len_field * 4 + 2;
    if raw.len() != expected_total {
        color_eyre::eyre::bail!(
            "frame length field={len_field} implies {expected_total} bytes, file is {}",
            raw.len()
        );
    }
    let data = &raw[..raw.len() - 2];
    let computed = mole_asm::crc::xmodem(data);
    let stored = u16::from_le_bytes([raw[raw.len() - 2], raw[raw.len() - 1]]);
    if computed != stored {
        color_eyre::eyre::bail!("CRC mismatch: stored=0x{stored:04X} computed=0x{computed:04X}");
    }
    // Return body without the 2-byte len prefix and 2-byte CRC suffix.
    Ok(&raw[2..raw.len() - 2])
}
