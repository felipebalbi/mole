//! CLI front-end for the `mole-asm` bytecode compiler — v0.2.
//!
//! Subcommand layout:
//! ```text
//! mole-asm assemble  [OPTIONS] <INPUT>   compile .moleasm → .molecode
//! mole-asm inspect   [OPTIONS] <INPUT>   decode header/summary of bytecode
//! mole-asm disassemble [OPTIONS] <INPUT> turn bytecode back into moleasm
//! ```
//!
//! The `assemble` subcommand is also the default when the first argument
//! is not a recognized subcommand name (backward compatibility with the
//! v0.1 positional CLI). In that case the argument list is forwarded to
//! `assemble` unchanged.

mod commands;
mod decode;

use std::process;

use clap::{Parser, Subcommand};
use color_eyre::eyre::{Context, Result};

use commands::assemble::AssembleArgs;
use commands::disassemble::DisassembleArgs;
use commands::inspect::InspectArgs;

// -----------------------------------------------------------------------
// Clap top-level
// -----------------------------------------------------------------------

#[derive(Debug, Parser)]
#[command(
    name = "mole-asm",
    version,
    about = "Mole v0.2 bytecode assembler / inspector / disassembler",
    long_about = "\
Layer-1 bytecode compiler and inspector for the Mole bit-cycle engine.

SUBCOMMANDS
  assemble     Compile a .moleasm source file to packed bytecode (.molecode)
               and optionally wrap it in a UART-ready .mole.bin frame.
  inspect      Decode and print the preamble / header of a .molecode or
               .mole.bin file.  Exit 2 on any header or CRC anomaly.
  disassemble  Translate a .molecode or .mole.bin file back into moleasm
               source.  Exit 2 if any unknown word is encountered.

BACKWARD COMPATIBILITY
  When the first argument is not a subcommand name, `assemble` is assumed.
  This preserves the v0.1 usage `mole-asm [OPTIONS] <INPUT>`.
"
)]
struct Cli {
    #[command(subcommand)]
    command: Command,
}

#[derive(Debug, Subcommand)]
enum Command {
    /// Compile a .moleasm source file to packed bytecode.
    Assemble(AssembleArgs),
    /// Decode and summarise the preamble of a bytecode file.
    Inspect(InspectArgs),
    /// Translate a bytecode file back into moleasm source.
    Disassemble(DisassembleArgs),
}

// -----------------------------------------------------------------------
// Entry point
// -----------------------------------------------------------------------

fn main() {
    // Install color-eyre *after* parsing so --help stays clean.
    // We parse raw args first so we can inject "assemble" for compat.
    let raw: Vec<String> = std::env::args().collect();
    let args = inject_default_subcommand(raw);

    color_eyre::install().expect("color-eyre install failed");

    let cli = Cli::parse_from(args);

    let result = dispatch(cli);
    match result {
        Ok(exit_code) => process::exit(exit_code),
        Err(e) => {
            eprintln!("error: {e:?}");
            process::exit(1);
        }
    }
}

/// Dispatch to the appropriate subcommand handler.
/// Returns the desired exit code (0 = success, 1 = assemble error,
/// 2 = inspect/disassemble validation error).
fn dispatch(cli: Cli) -> Result<i32> {
    match cli.command {
        Command::Assemble(args) => {
            commands::assemble::run(&args)?;
            Ok(0)
        }
        Command::Inspect(args) => {
            let ok = commands::inspect::run(&args).wrap_err("inspect failed")?;
            if ok { Ok(0) } else { Ok(2) }
        }
        Command::Disassemble(args) => {
            let ok = commands::disassemble::run(&args).wrap_err("disassemble failed")?;
            if ok { Ok(0) } else { Ok(2) }
        }
    }
}

/// If the first non-binary argument is not a known subcommand keyword,
/// insert "assemble" so clap sees a valid subcommand.
///
/// Known subcommand names: `assemble`, `inspect`, `disassemble`,
/// plus built-in clap flags `--help`, `-h`, `--version`, `-V`.
fn inject_default_subcommand(mut args: Vec<String>) -> Vec<String> {
    const KNOWN_SUBS: &[&str] = &[
        "assemble",
        "inspect",
        "disassemble",
        "--help",
        "-h",
        "--version",
        "-V",
        "help",
    ];
    // args[0] is the binary name; args[1] (if present) is the first arg.
    if let Some(first) = args.get(1) {
        if !KNOWN_SUBS.contains(&first.as_str()) {
            args.insert(1, "assemble".to_string());
        }
    }
    args
}

// -----------------------------------------------------------------------
// Unit tests
// -----------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn inject_inserts_assemble_for_file_arg() {
        let args = vec!["mole-asm".to_string(), "foo.moleasm".to_string()];
        let injected = inject_default_subcommand(args);
        assert_eq!(injected[1], "assemble");
        assert_eq!(injected[2], "foo.moleasm");
    }

    #[test]
    fn inject_leaves_assemble_subcommand_alone() {
        let args = vec![
            "mole-asm".to_string(),
            "assemble".to_string(),
            "foo.moleasm".to_string(),
        ];
        let injected = inject_default_subcommand(args.clone());
        assert_eq!(injected, args);
    }

    #[test]
    fn inject_leaves_inspect_alone() {
        let args = vec![
            "mole-asm".to_string(),
            "inspect".to_string(),
            "foo.molecode".to_string(),
        ];
        let injected = inject_default_subcommand(args.clone());
        assert_eq!(injected, args);
    }

    #[test]
    fn inject_leaves_help_flag_alone() {
        let args = vec!["mole-asm".to_string(), "--help".to_string()];
        let injected = inject_default_subcommand(args.clone());
        assert_eq!(injected, args);
    }

    #[test]
    fn inject_no_op_when_no_args() {
        let args = vec!["mole-asm".to_string()];
        let injected = inject_default_subcommand(args.clone());
        assert_eq!(injected, args);
    }
}
