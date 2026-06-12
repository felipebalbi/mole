//! `assemble` subcommand: compile moleasm source → packed bytecode.
//!
//! This is the original single-mode behaviour from the v0.1 CLI, now
//! factored into its own module so `main.rs` stays slim.

use std::fs;
use std::path::{Path, PathBuf};

use color_eyre::eyre::{Context, Result};

/// Options that mirror the old flat CLI plus the new subcommand name.
#[derive(Debug, clap::Args)]
pub struct AssembleArgs {
    /// Source file to assemble.
    pub input: PathBuf,

    /// Output path for the packed bytecode (.molecode).
    /// Defaults to `<INPUT>.molecode`.
    #[arg(short = 'o', long = "output", value_name = "PATH")]
    pub output: Option<PathBuf>,

    /// Also emit a `.mole.bin` UART frame.
    /// Defaults to placing it next to INPUT.
    #[arg(long = "frame")]
    pub frame: bool,

    /// Path for the optional frame output.  Defaults to `<INPUT>.mole.bin`.
    /// Implies `--frame`.
    #[arg(long = "frame-output", value_name = "PATH")]
    pub frame_output: Option<PathBuf>,
}

pub fn run(args: &AssembleArgs) -> Result<()> {
    let source = fs::read_to_string(&args.input)
        .wrap_err_with(|| format!("failed to read {}", args.input.display()))?;

    let filename = args.input.to_string_lossy();
    let words = mole_asm::assemble(&source, &filename)
        .wrap_err_with(|| format!("failed to assemble {}", args.input.display()))?;

    let bytecode = mole_asm::frame::pack_bytecode(&words);
    let bytecode_path = args
        .output
        .clone()
        .unwrap_or_else(|| swap_extension(&args.input, "molecode"));
    fs::write(&bytecode_path, &bytecode)
        .wrap_err_with(|| format!("failed to write {}", bytecode_path.display()))?;
    // body = words minus 2-word preamble
    let body_count = words.len().saturating_sub(2);
    eprintln!(
        "wrote {} body words ({} bytes) -> {}",
        body_count,
        bytecode.len(),
        bytecode_path.display()
    );

    let want_frame = args.frame || args.frame_output.is_some();
    if want_frame {
        // `assemble` returns [MAGIC, body_len, body...]; the wire frame's
        // MAGIC + LEN preamble is built fresh by `build_frame` from the
        // body alone.
        let body = &words[mole_asm::PREAMBLE_WORDS..];
        let frame = mole_asm::frame::build_frame(body).wrap_err("failed to build UART frame")?;
        let frame_path = args
            .frame_output
            .clone()
            .unwrap_or_else(|| swap_extension(&args.input, "mole.bin"));
        fs::write(&frame_path, &frame)
            .wrap_err_with(|| format!("failed to write {}", frame_path.display()))?;
        eprintln!(
            "wrote frame ({} bytes) -> {}",
            frame.len(),
            frame_path.display()
        );
    }

    Ok(())
}

/// Replace (or append) the file extension on `input`.
pub fn swap_extension(input: &Path, new_ext: &str) -> PathBuf {
    let mut out = input.to_path_buf();
    out.set_extension(new_ext);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn swap_extension_replaces_existing() {
        assert_eq!(
            swap_extension(Path::new("foo.moleasm"), "molecode"),
            PathBuf::from("foo.molecode")
        );
    }

    #[test]
    fn swap_extension_appends_when_missing() {
        assert_eq!(
            swap_extension(Path::new("foo"), "molecode"),
            PathBuf::from("foo.molecode")
        );
    }

    #[test]
    fn swap_extension_keeps_directories() {
        assert_eq!(
            swap_extension(Path::new("dir/sub/foo.moleasm"), "mole.bin"),
            PathBuf::from("dir/sub/foo.mole.bin")
        );
    }
}
