//! Integration tests for the `mole-loader` CLI binary.
//!
//! Tests exercise the `--validate-only` path (no serial port needed)
//! and verify the exit-code contract from §16:
//!
//! | Code | Meaning                                     |
//! |------|---------------------------------------------|
//! | 0    | Program valid; safe to load.                |
//! | 2    | Frame structure error.                      |
//! | 3    | Version mismatch (§16.2).                   |
//! | 4    | Magic mismatch (§16.1).                     |
//! | 5    | Reserved HALT status in body (§16.4).       |

use std::process::Command;

use mole_asm::frame::build_frame;
use mole_loader::verify_frame;

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/// Path to the compiled `mole-loader` binary.
fn mole_loader_bin() -> std::path::PathBuf {
    // `CARGO_BIN_EXE_mole-loader` is set by Cargo for integration
    // tests in crates that declare a [[bin]].
    std::path::PathBuf::from(env!("CARGO_BIN_EXE_mole-loader"))
}

/// Write `bytes` to a temp file and return its path.
fn write_temp(name: &str, bytes: &[u8]) -> std::path::PathBuf {
    let dir = std::path::PathBuf::from(env!("CARGO_TARGET_TMPDIR"));
    let path = dir.join(name);
    std::fs::write(&path, bytes).unwrap_or_else(|e| panic!("write {}: {e}", path.display()));
    path
}

/// Run `mole-loader --validate-only <path>` and return the exit code.
fn validate_only(frame_path: &std::path::Path) -> i32 {
    let status = Command::new(mole_loader_bin())
        .arg("--validate-only")
        .arg(frame_path)
        .status()
        .expect("failed to execute mole-loader");
    status.code().expect("process terminated by signal")
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

#[test]
fn exit_0_for_valid_program() {
    // Build a well-formed frame with a single HALT status=0.
    let frame = mole_asm::assemble_to_frame("HALT status=0\n", "<t>").unwrap();
    let path = write_temp("valid.mole.bin", &frame);
    assert_eq!(validate_only(&path), 0, "valid program must exit 0");
}

#[test]
fn exit_4_for_bad_magic() {
    // Build a valid frame, then corrupt the magic bytes.
    let body = vec![0u32];
    let mut frame = build_frame(&body).unwrap();
    frame[0] ^= 0xFF;
    let path = write_temp("bad-magic.mole.bin", &frame);
    assert_eq!(validate_only(&path), 4, "bad magic must exit 4");
}

#[test]
fn exit_4_for_wrong_version_in_magic() {
    // The version is encoded in the high 16 bits of the magic word.
    // Building a frame with a v1 magic (= (0x0001 << 16) | 0x4D4C =
    // 0x0001_4D4C) must also exit 4, since the magic comparison is
    // against the full 32-bit value.
    let body = vec![0u32];
    let mut frame = build_frame(&body).unwrap();
    let wrong_magic: u32 = (0x0001u32 << 16) | (mole_abi::MAGIC_LO_U16 as u32);
    frame[0..4].copy_from_slice(&wrong_magic.to_le_bytes());
    let path = write_temp("wrong-version.mole.bin", &frame);
    assert_eq!(
        validate_only(&path),
        4,
        "wrong version in magic word must exit 4 (no separate version code)"
    );
}

#[test]
fn exit_5_for_reserved_halt_status_in_body() {
    // Build a frame containing a HALT instruction with status=0x1D.
    // HALT instruction encoding per §5.10:
    //   [31:30]=01 (CTRL group), [29:26]=0000 (HALT sub),
    //   [7:3]=status, rest=0.
    let halt_instr: u32 = (0b01_0000u32 << 26) | (0x1Du32 << 3);
    let body = vec![halt_instr];
    let frame = build_frame(&body).unwrap();
    let path = write_temp("reserved-halt.mole.bin", &frame);
    assert_eq!(validate_only(&path), 5, "reserved HALT status must exit 5");
}

#[test]
fn exit_2_for_corrupt_crc() {
    // Build a valid frame then corrupt its CRC byte.
    let mut frame = mole_asm::assemble_to_frame("HALT\n", "<t>").unwrap();
    let last = frame.len() - 1;
    frame[last] ^= 0xFF;
    let path = write_temp("bad-crc.mole.bin", &frame);
    assert_eq!(validate_only(&path), 2, "bad CRC must exit 2");
}

#[test]
fn exit_2_for_preamble_only_empty_program() {
    // A frame claiming LEN=0 (no body words) must be rejected as an
    // empty program (§16.5) with exit code 2.
    let body = vec![0u32];
    let mut frame = build_frame(&body).unwrap();
    // Patch LEN to 0.
    frame[4..8].copy_from_slice(&0u32.to_le_bytes());
    // CRC will be wrong but LengthOutOfRange fires before CRC check.
    let path = write_temp("empty-program.mole.bin", &frame);
    assert_eq!(validate_only(&path), 2, "empty program must exit 2");
}

#[test]
fn verify_frame_and_verify_program_round_trip_all_user_statuses() {
    // Sweep every user-defined status code 0x00..=0x1C and confirm
    // that verify_program accepts all of them.
    for status in 0u8..=0x1C {
        let src = format!("HALT status={status}\n");
        let frame = mole_asm::assemble_to_frame(&src, "<t>").unwrap();
        let body = verify_frame(&frame).unwrap();
        let result = mole_loader::verify_program(&body);
        assert!(
            result.is_ok(),
            "status 0x{status:02X} must be accepted by verify_program, \
             got {result:?}"
        );
    }
}
