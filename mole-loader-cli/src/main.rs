//! CLI front-end for `mole-loader`.
//!
//! Round-trip a `.mole.bin` artifact against a real Mole engine:
//! verify the frame locally, ship it over UART, drain the result
//! ring, decode it, print it.
//!
//! ```text
//! mole-loader [OPTIONS] <FRAME>
//!     -p, --port <PATH>                Serial port (e.g. /dev/ttyUSB0)
//!     -b, --baud <RATE>                Baud rate [default: 2000000]
//!         --ring-bytes <BYTES>         Result-ring size [default: 8192]
//!         --expect-revision <X.Y.Z>    Assert engine revision
//!         --timeout <SECS>             Per-op I/O timeout [default: 30]
//!         --no-verify-frame            Skip CRC pre-flight
//!         --dry-run                    Verify frame + program, exit 0
//!         --validate-only              Validate frame + program, print
//!                                      result, exit without transport
//!         --dump-ring <PATH>           Save raw ring bytes
//! ```
//!
//! ## Exit codes
//!
//! | Code | Meaning                                                    |
//! |------|------------------------------------------------------------|
//! | 0    | Success (program loaded and ran, or validation passed).    |
//! | 1    | I/O or argument error; engine reported a non-clean halt.   |
//! | 2    | Frame structure error (bad length, CRC, or empty program). |
//! | 3    | Frame version mismatch (§16.2 dedicated exit code).        |
//! | 4    | Frame magic mismatch (§16.1).                              |
//! | 5    | Reserved HALT status in program body (§16.4).              |
//!
//! Pass `-` as `<FRAME>` to read from stdin.

use std::fs;
use std::io::{self, Read, Write};
use std::path::{Path, PathBuf};
use std::process::ExitCode;
use std::time::Duration;

use clap::Parser;
use color_eyre::eyre::{Context, Result, eyre};
use indicatif::{ProgressBar, ProgressStyle};

use mole_loader::{
    DEFAULT_BAUD, DEFAULT_RING_BYTES, DecodedRing, FrameError, HaltStatus, Progress, Record,
    Revision, Transport, decode_ring_strict, verify_frame,
};

/// Load a Mole program over UART and decode the engine's result ring.
#[derive(Debug, Parser)]
#[command(
    name = "mole-loader",
    version,
    about,
    long_about = "Ship a .mole.bin frame to a Mole bit-cycle engine over UART (2 Mbaud \
                  default; use --baud 1000000 for the 16x fallback), 8N1, hardware \
                  RTS/CTS), wait for HALT, drain the result ring, and decode its \
                  REVISION / CAPTURE / MARK / HALT records. Pass '-' as FRAME to \
                  read from stdin.\n\n\
                  Exit codes:\n  \
                  0  success (or --validate-only: program is valid)\n  \
                  1  I/O / argument error; engine non-clean halt\n  \
                  2  frame structure error (length, CRC, empty program)\n  \
                  3  frame version mismatch (§16.2)\n  \
                  4  frame magic mismatch (§16.1)\n  \
                  5  reserved HALT status in program body (§16.4)"
)]
struct Cli {
    /// Path to the `.mole.bin` frame, or `-` to read from stdin.
    frame: PathBuf,

    /// Serial port path (e.g. `/dev/ttyUSB0`, `COM3`).
    /// Required unless `--dry-run` or `--validate-only` is set.
    #[arg(short = 'p', long = "port", value_name = "PATH")]
    port: Option<String>,

    /// Baud rate. Default: 2 Mbaud (per WIRE_FORMAT.md).
    /// Use 1000000 for the 16x oversampling fallback when 2 Mbaud
    /// is not achievable on a particular host adapter.
    #[arg(short = 'b', long = "baud", default_value_t = DEFAULT_BAUD)]
    baud: u32,

    /// Result-ring size in bytes the engine will drain back. Default
    /// matches `MoleConfig.resultRingByteCount` on the Verde build
    /// (4096 words = 8192 bytes), mirrored host-side as
    /// `mole_abi::RESULT_RING_BYTE_COUNT`. Must match the engine
    /// bitstream.
    #[arg(long = "ring-bytes", default_value_t = DEFAULT_RING_BYTES)]
    ring_bytes: usize,

    /// Assert that the engine's REVISION word matches `X.Y.Z`. The
    /// loader exits nonzero with a clear diagnostic if it does not.
    #[arg(long = "expect-revision", value_name = "X.Y.Z")]
    expect_revision: Option<Revision>,

    /// Per-operation I/O timeout, seconds. Applies to both the load
    /// phase (host writes) and the drain phase (host reads).
    #[arg(long = "timeout", default_value_t = 30u64, value_name = "SECS")]
    timeout: u64,

    /// Skip the pre-flight CRC / length check on the input frame.
    /// Intended for debugging deliberately malformed frames.
    #[arg(long = "no-verify-frame")]
    no_verify_frame: bool,

    /// Verify the frame and run all §16 validation checks locally,
    /// then exit without opening a serial port. Useful for CI /
    /// smoke-testing artifacts in environments without hardware.
    /// Exit codes: 0=valid, 2=frame-error, 3=version-mismatch,
    /// 4=magic-mismatch, 5=reserved-status.
    #[arg(long = "dry-run")]
    dry_run: bool,

    /// Validate the frame and program (§16 checks) without any
    /// transport interaction. Distinct from `--dry-run` in that it
    /// always prints a structured result and uses the full exit-code
    /// table (codes 2/3/4/5). Exits 0 when the program is safe to
    /// load.
    #[arg(long = "validate-only")]
    validate_only: bool,

    /// Also write the raw drained ring bytes to this path
    /// (before decoding). Useful for archiving the raw evidence
    /// alongside the decoded view.
    #[arg(long = "dump-ring", value_name = "PATH")]
    dump_ring: Option<PathBuf>,
}

fn main() -> ExitCode {
    // Parse first so `--help` / `--version` stay free of the eyre
    // banner (same convention as `mole-asm-cli`).
    let cli = Cli::parse();
    if let Err(e) = color_eyre::install() {
        eprintln!("failed to install color-eyre: {e}");
        return ExitCode::from(1);
    }

    match run(cli) {
        Ok(code) => code,
        Err(e) => {
            eprintln!("mole-loader: {e:?}");
            ExitCode::from(1)
        }
    }
}

/// Map a [`FrameError`] to one of the structured exit codes 2/3/4/5.
///
/// | Code | Error kind                               |
/// |------|------------------------------------------|
/// | 2    | structural (TooShort, LengthMismatch,    |
/// |      | LengthOutOfRange, CrcMismatch)           |
/// | 3    | VersionMismatch (§16.2)                  |
/// | 4    | MagicMismatch   (§16.1)                  |
/// | 5    | ReservedHaltStatusInBody (§16.4)         |
fn frame_error_exit_code(err: &FrameError) -> u8 {
    match err {
        FrameError::VersionMismatch { .. } => 3,
        FrameError::MagicMismatch { .. } => 4,
        FrameError::ReservedHaltStatusInBody { .. } => 5,
        _ => 2,
    }
}

fn run(cli: Cli) -> Result<ExitCode> {
    // ----------------------------------------------------- 1. Read frame
    let frame_bytes = read_frame_source(&cli.frame)
        .wrap_err_with(|| format!("failed to read frame from {}", display_source(&cli.frame)))?;
    eprintln!(
        "loaded {} bytes from {}",
        frame_bytes.len(),
        display_source(&cli.frame)
    );

    // ----------------------------------------------------- 2. Verify frame
    let words_opt = if !cli.no_verify_frame {
        match verify_frame(&frame_bytes) {
            Ok(words) => {
                eprintln!(
                    "verified frame: {} total words (preamble + body), CRC matches",
                    words.len()
                );
                Some(words)
            }
            Err(e) => {
                eprintln!("mole-loader: frame pre-flight failed: {e}");
                return Ok(ExitCode::from(frame_error_exit_code(&e)));
            }
        }
    } else {
        eprintln!("warning: --no-verify-frame set; sending unverified bytes");
        None
    };

    // ----------------------------------------------------- 3. §16 program check
    // Run verify_program when we have words AND either --validate-only
    // / --dry-run is set, or the frame was verified normally.
    if let Some(ref words) = words_opt {
        match mole_loader::verify_program(words) {
            Ok((ver, body)) => {
                eprintln!(
                    "program validated: format version 0x{ver:04x}, \
                     {} body word(s)",
                    body.len()
                );
            }
            Err(e) => {
                eprintln!("mole-loader: program validation failed: {e}");
                return Ok(ExitCode::from(frame_error_exit_code(&e)));
            }
        }
    }

    // ----------------------------------------------------- 4. Validate-only / dry-run exit
    if cli.validate_only || cli.dry_run {
        eprintln!("validate-only: program ok, not opening serial port");
        return Ok(ExitCode::SUCCESS);
    }

    // ----------------------------------------------------- 5. Open port
    let port_path = cli.port.as_deref().ok_or_else(|| {
        eyre!(
            "--port <PATH> is required when --dry-run / --validate-only \
             are not set (e.g. --port /dev/ttyUSB0)"
        )
    })?;
    eprintln!(
        "opening {port_path} at {} baud (8N1, hardware RTS/CTS)",
        cli.baud
    );
    let mut transport =
        Transport::open(port_path, cli.baud).wrap_err("failed to open serial transport")?;
    transport
        .with_timeout(Duration::from_secs(cli.timeout))
        .wrap_err("failed to configure I/O timeout")?;

    // ----------------------------------------------------- 6. Send frame
    let load_bar = make_progress_bar(frame_bytes.len() as u64, "load ");
    {
        let bar = load_bar.clone();
        let mut progress = Progress::new(move |n| bar.set_position(n as u64));
        transport
            .send_frame(&frame_bytes, &mut progress)
            .wrap_err("failed to send program frame")?;
    }
    load_bar.finish_with_message("done");

    // ----------------------------------------------------- 7. Drain
    let drain_bar = make_progress_bar(cli.ring_bytes as u64, "drain");
    let ring_bytes = {
        let bar = drain_bar.clone();
        let mut progress = Progress::new(move |n| bar.set_position(n as u64));
        transport
            .drain_ring(cli.ring_bytes, &mut progress)
            .wrap_err("failed to drain result ring")?
    };
    drain_bar.finish_with_message("done");

    // ----------------------------------------------------- 8. Dump?
    if let Some(path) = &cli.dump_ring {
        fs::write(path, &ring_bytes)
            .wrap_err_with(|| format!("failed to write --dump-ring {}", path.display()))?;
        eprintln!(
            "wrote raw ring ({} bytes) -> {}",
            ring_bytes.len(),
            path.display()
        );
    }

    // ----------------------------------------------------- 9. Decode
    let decoded =
        decode_ring_strict(&ring_bytes, cli.ring_bytes).wrap_err("failed to decode result ring")?;

    // ---------------------------------------------------- 10. Optional REVISION check
    if let Some(expected) = cli.expect_revision {
        if decoded.revision != expected {
            return Err(eyre!(
                "REVISION mismatch: expected {expected}, got {}",
                decoded.revision
            ));
        }
    }

    // ---------------------------------------------------- 11. Render
    render_decoded(&decoded);

    // ---------------------------------------------------- 12. Exit code
    if halt_indicates_failure(&decoded.halt) {
        Ok(ExitCode::from(1))
    } else {
        Ok(ExitCode::SUCCESS)
    }
}

fn read_frame_source(path: &Path) -> io::Result<Vec<u8>> {
    if path.as_os_str() == "-" {
        let mut buf = Vec::new();
        io::stdin().lock().read_to_end(&mut buf)?;
        Ok(buf)
    } else {
        fs::read(path)
    }
}

fn display_source(path: &Path) -> String {
    if path.as_os_str() == "-" {
        "<stdin>".to_string()
    } else {
        path.display().to_string()
    }
}

fn make_progress_bar(total: u64, prefix: &'static str) -> ProgressBar {
    // Hide the bar when stderr is not a TTY so log capture stays
    // clean (CI, redirected output, etc.).
    let bar = if io::IsTerminal::is_terminal(&io::stderr()) {
        ProgressBar::new(total)
    } else {
        ProgressBar::hidden()
    };
    bar.set_style(
        ProgressStyle::with_template(
            "{prefix:>6} [{bar:32.cyan/blue}] {bytes:>9}/{total_bytes:<9} \
             {bytes_per_sec:>11} eta {eta:>4} {msg}",
        )
        .expect("indicatif template is well-formed")
        .progress_chars("=>-"),
    );
    bar.set_prefix(prefix);
    bar
}

fn render_decoded(ring: &DecodedRing) {
    println!("revision: {}", ring.revision);

    let n_captures = ring
        .records
        .iter()
        .filter(|r| matches!(r, Record::Capture { .. }))
        .count();
    let n_marks = ring.records.len() - n_captures;
    println!(
        "records:  {} total ({} CAPTURE, {} MARK)",
        ring.records.len(),
        n_captures,
        n_marks
    );

    for (i, rec) in ring.records.iter().enumerate() {
        match rec {
            Record::Capture { sda } => {
                println!("  [{i:>4}] CAPTURE sda={}", u8::from(*sda));
            }
            Record::Mark { label, timestamp } => {
                println!("  [{i:>4}] MARK    label=0x{label:02X} ts={timestamp} cycles");
            }
        }
    }

    if ring.trailing_garbage_words > 0 {
        println!(
            "(note: {} unused record slots between last record and HALT --- \
             see crate docs on the trailing-garbage hazard)",
            ring.trailing_garbage_words
        );
    }

    println!(
        "halt:     status=0x{:X} mismatch={} overflow={}{}",
        ring.halt.status,
        ring.halt.mismatch,
        ring.halt.overflow,
        if ring.halt.is_engine_trap() {
            " [ENGINE TRAP: malformed instruction]"
        } else {
            ""
        }
    );

    // Flush stdout before main() returns so the OS buffer does not
    // eat the last line under capture redirection.
    let _ = io::stdout().flush();
}

/// Decide whether the engine reported a clean run.
///
/// A "failure" is any of: non-zero caller status, sticky MISMATCH at
/// HALT entry, ring overflow, or the engine-internal `0x1F` trap.
fn halt_indicates_failure(halt: &HaltStatus) -> bool {
    halt.status != 0 || halt.mismatch || halt.overflow || halt.is_engine_trap()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn halt_indicates_failure_default_is_clean() {
        let h = HaltStatus {
            status: 0,
            mismatch: false,
            overflow: false,
        };
        assert!(!halt_indicates_failure(&h));
    }

    #[test]
    fn halt_indicates_failure_on_nonzero_status() {
        let h = HaltStatus {
            status: 1,
            mismatch: false,
            overflow: false,
        };
        assert!(halt_indicates_failure(&h));
    }

    #[test]
    fn halt_indicates_failure_on_mismatch() {
        let h = HaltStatus {
            status: 0,
            mismatch: true,
            overflow: false,
        };
        assert!(halt_indicates_failure(&h));
    }

    #[test]
    fn halt_indicates_failure_on_overflow() {
        let h = HaltStatus {
            status: 0,
            mismatch: false,
            overflow: true,
        };
        assert!(halt_indicates_failure(&h));
    }

    #[test]
    fn halt_indicates_failure_on_engine_trap() {
        let h = HaltStatus {
            status: 0x1F,
            mismatch: false,
            overflow: false,
        };
        assert!(halt_indicates_failure(&h));
    }

    #[test]
    fn display_source_stdin_sentinel() {
        assert_eq!(display_source(Path::new("-")), "<stdin>");
        assert_eq!(display_source(Path::new("foo.mole.bin")), "foo.mole.bin");
    }

    #[test]
    fn frame_error_exit_codes_are_correct() {
        assert_eq!(
            frame_error_exit_code(&FrameError::VersionMismatch {
                found: 1,
                expected: 2
            }),
            3
        );
        assert_eq!(
            frame_error_exit_code(&FrameError::MagicMismatch {
                found: 0,
                expected: mole_abi::MAGIC
            }),
            4
        );
        assert_eq!(
            frame_error_exit_code(&FrameError::ReservedHaltStatusInBody {
                pc: 0,
                status: 0x1D
            }),
            5
        );
        assert_eq!(
            frame_error_exit_code(&FrameError::CrcMismatch {
                computed: 0,
                got: 1
            }),
            2
        );
        assert_eq!(frame_error_exit_code(&FrameError::TooShort { got: 0 }), 2);
        assert_eq!(
            frame_error_exit_code(&FrameError::LengthOutOfRange { len_words: 0 }),
            2
        );
    }
}
