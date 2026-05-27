//! CLI front-end for the `mole-asm` bytecode compiler.
//!
//! Layout mirrors the Python `mole-asm.py` it replaces:
//!
//! ```text
//! mole-asm [OPTIONS] <INPUT.moleasm>
//!     -o, --output <PATH>   Write packed .molecode here (default: <input>.molecode)
//!         --frame           Also emit <input>.mole.bin (UART-ready frame)
//! ```
//!
//! Rationale for the small-and-boring shape: this is a batch
//! transformer, not a long-running daemon. `clap` + `color-eyre` is
//! enough; we skip `tracing` because there is nothing to trace.

use std::fs;
use std::path::{Path, PathBuf};

use clap::Parser;
use color_eyre::eyre::{Context, Result};

/// Assemble a moleasm source file to packed bytecode and (optionally)
/// the UART-ready frame the on-target loader expects.
#[derive(Debug, Parser)]
#[command(
    name = "mole-asm",
    version,
    about,
    long_about = "Layer-1 bytecode compiler for the Mole bit-cycle engine. \
                  Reads a .moleasm source file and writes a .molecode \
                  (packed little-endian 16-bit words). With --frame, also \
                  writes a .mole.bin (length + words + CRC-16/XMODEM) ready \
                  to ship over UART."
)]
struct Cli {
    /// Source file to assemble.
    input: PathBuf,

    /// Output path for the packed bytecode. Defaults to `INPUT.molecode`.
    #[arg(short = 'o', long = "output", value_name = "PATH")]
    output: Option<PathBuf>,

    /// Also emit a `.mole.bin` UART frame next to (or instead of) the
    /// bytecode. Defaults to placing it next to INPUT.
    #[arg(long = "frame")]
    frame: bool,

    /// Path for the optional frame output. Defaults to `INPUT.mole.bin`.
    /// Implies `--frame`.
    #[arg(long = "frame-output", value_name = "PATH")]
    frame_output: Option<PathBuf>,
}

fn main() -> Result<()> {
    // Parse arguments *before* installing color-eyre so that `--help`
    // and version output stay clean of the eyre banner. Per
    // rubber-duck #6.
    let cli = Cli::parse();
    color_eyre::install()?;

    let source = fs::read_to_string(&cli.input)
        .wrap_err_with(|| format!("failed to read {}", cli.input.display()))?;

    let filename = cli.input.to_string_lossy();
    let words = mole_asm::assemble(&source, &filename)
        .wrap_err_with(|| format!("failed to assemble {}", cli.input.display()))?;

    let bytecode = mole_asm::frame::pack_bytecode(&words);
    let bytecode_path = cli
        .output
        .clone()
        .unwrap_or_else(|| swap_extension(&cli.input, "molecode"));
    fs::write(&bytecode_path, &bytecode)
        .wrap_err_with(|| format!("failed to write {}", bytecode_path.display()))?;
    eprintln!(
        "wrote {} words ({} bytes) -> {}",
        words.len(),
        bytecode.len(),
        bytecode_path.display()
    );

    let want_frame = cli.frame || cli.frame_output.is_some();
    if want_frame {
        let frame = mole_asm::frame::build_frame(&words).wrap_err("failed to build UART frame")?;
        let frame_path = cli
            .frame_output
            .clone()
            .unwrap_or_else(|| swap_extension(&cli.input, "mole.bin"));
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

/// Replace (or append) the file extension on `input`. Always overwrites
/// any existing extension so `foo.moleasm` -> `foo.molecode` works the
/// same as `foo` -> `foo.molecode`.
fn swap_extension(input: &Path, new_ext: &str) -> PathBuf {
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
