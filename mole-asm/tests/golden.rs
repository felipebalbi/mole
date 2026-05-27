//! Byte-for-byte golden tests for the three bring-up fixtures plus a
//! handful of negative cases that are clearer at the integration
//! layer than as unit tests.
//!
//! The goldens are the wire-format contract: any change that bumps
//! the assembled bytes for an existing program is a deliberate spec
//! change and should be reviewed alongside the corresponding
//! `Instruction.scala` / `ROADMAP.md` updates.

use std::fs;
use std::path::{Path, PathBuf};

use mole_asm::{AsmError, Kind, assemble, assemble_to_frame};

fn fixtures_dir() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("tests")
        .join("fixtures")
}

fn read_text(path: &Path) -> String {
    let raw = fs::read_to_string(path).unwrap_or_else(|e| panic!("read {path:?}: {e}"));
    // CRLF in goldens is benign (line-comment until EOL anyway), but
    // strip them to keep diagnostics tidy if a stray one slips in.
    raw.replace("\r\n", "\n")
}

fn read_bytes(path: &Path) -> Vec<u8> {
    fs::read(path).unwrap_or_else(|e| panic!("read {path:?}: {e}"))
}

fn check_fixture(name: &str) {
    let dir = fixtures_dir();
    let src_path = dir.join(format!("{name}.moleasm"));
    let bytecode_path = dir.join(format!("{name}.molecode"));
    let frame_path = dir.join(format!("{name}.mole.bin"));

    let source = read_text(&src_path);
    let expected_bytecode = read_bytes(&bytecode_path);
    let expected_frame = read_bytes(&frame_path);

    let words = assemble(&source, src_path.to_str().unwrap())
        .unwrap_or_else(|e| panic!("{name}.moleasm failed to assemble: {e}"));
    let actual_bytecode = mole_asm::frame::pack_bytecode(&words);
    assert_eq!(
        actual_bytecode,
        expected_bytecode,
        "{name}: assembled .molecode mismatch (got {actual:?}, want {want:?})",
        actual = actual_bytecode,
        want = expected_bytecode,
    );

    let actual_frame = assemble_to_frame(&source, src_path.to_str().unwrap())
        .unwrap_or_else(|e| panic!("{name}.moleasm failed to frame: {e}"));
    assert_eq!(
        actual_frame, expected_frame,
        "{name}: assembled .mole.bin mismatch",
    );
}

#[test]
fn first_light_golden() {
    check_fixture("first-light");
}

#[test]
fn tmp108_golden() {
    check_fixture("tmp108");
}

#[test]
fn i2c_write_one_byte_golden() {
    check_fixture("i2c-write-one-byte");
}

// ---------------------------------------------------------------------------
// Range boundary tests at the assembler level (encoder unit tests cover
// the raw bit-pack; these confirm the assembler's PC math gets the
// same boundaries through correctly).
// ---------------------------------------------------------------------------

fn syntax_kind(err: &AsmError) -> Option<Kind> {
    match err {
        AsmError::Syntax { kind, .. } => Some(*kind),
        _ => None,
    }
}

#[test]
fn branch_minus_128_accepted() {
    // Place the target 128 PC slots before the branch: 128 HALTs, label,
    // 128 HALTs again, then branch back to the label so offset = -128.
    // Layout:
    //   PC 0..127:   HALT * 128
    //   PC 128:      tgt:
    //   PC 128..255: HALT * 128   (128 more HALTs *after* the label)
    //   PC 256:      BRANCH_ON ALWAYS, tgt
    // offset = tgt(128) - branch_pc(256) - 1 = -129. Off by one.
    // Re-pick: want offset exactly -128, so target_pc - branch_pc = -127.
    //   PC 0..126:   HALT * 127  (127 instrs)
    //   PC 127:      tgt:
    //   PC 127..253: HALT * 127  (127 instrs)
    //   PC 254:      BRANCH_ON ALWAYS, tgt
    // offset = 127 - 254 - 1 = -128.  ✓
    let mut src = String::new();
    for _ in 0..127 {
        src.push_str("HALT\n");
    }
    src.push_str("tgt:\n");
    for _ in 0..127 {
        src.push_str("HALT\n");
    }
    src.push_str("BRANCH_ON ALWAYS, tgt\n");
    let words = assemble(&src, "<offset=-128>").expect("offset=-128 must encode");
    let branch_word = *words.last().unwrap();
    // BRANCH_ON cond=0 offset=-128 -> 0x5080.
    assert_eq!(
        branch_word, 0x5080,
        "expected 0x5080, got {branch_word:#06x}"
    );
}

#[test]
fn branch_minus_129_rejected() {
    // One more HALT between label and branch -> offset = -129.
    let mut src = String::new();
    for _ in 0..127 {
        src.push_str("HALT\n");
    }
    src.push_str("tgt:\n");
    for _ in 0..128 {
        src.push_str("HALT\n");
    }
    src.push_str("BRANCH_ON ALWAYS, tgt\n");
    let err = assemble(&src, "<offset=-129>").unwrap_err();
    assert_eq!(syntax_kind(&err), Some(Kind::Range));
}

#[test]
fn branch_plus_127_accepted() {
    // BRANCH at PC 0, target at PC 128 -> offset = 128 - 0 - 1 = 127.
    let mut src = String::from("BRANCH_ON ALWAYS, tgt\n");
    for _ in 0..127 {
        src.push_str("HALT\n");
    }
    src.push_str("tgt:\n  HALT\n");
    let words = assemble(&src, "<offset=+127>").expect("offset=+127 must encode");
    // BRANCH_ON cond=0 offset=127 -> 0x507F.
    assert_eq!(words[0], 0x507F, "expected 0x507F, got {:#06x}", words[0]);
}

#[test]
fn branch_plus_128_rejected() {
    let mut src = String::from("BRANCH_ON ALWAYS, tgt\n");
    for _ in 0..128 {
        src.push_str("HALT\n");
    }
    src.push_str("tgt:\n  HALT\n");
    let err = assemble(&src, "<offset=+128>").unwrap_err();
    assert_eq!(syntax_kind(&err), Some(Kind::Range));
}

#[test]
fn program_length_4096_accepted() {
    // 4096 HALTs is exactly the program-memory budget. assemble() must
    // succeed; assemble_to_frame() then succeeds too (frame size check
    // is 1..=4096 inclusive).
    let mut src = String::with_capacity(4096 * 6);
    for _ in 0..4096 {
        src.push_str("HALT\n");
    }
    let words = assemble(&src, "<max-prog>").expect("4096 words must assemble");
    assert_eq!(words.len(), 4096);
    let frame = assemble_to_frame(&src, "<max-prog>").expect("must frame");
    assert_eq!(frame.len(), 2 + 4096 * 2 + 2);
}

#[test]
fn program_length_4097_rejected() {
    let mut src = String::with_capacity(4097 * 6);
    for _ in 0..4097 {
        src.push_str("HALT\n");
    }
    let err = assemble(&src, "<over-prog>").unwrap_err();
    // The assembler enforces 4096-word PC budget itself; we never get
    // far enough to hit the frame builder's check.
    assert_eq!(syntax_kind(&err), Some(Kind::Range));
}

// ---------------------------------------------------------------------------
// Python parity (oracle). Off by default; opt in with
// `MOLEASM_PYTHON_PARITY=1` and a `python3` on PATH.
// ---------------------------------------------------------------------------

#[test]
#[ignore = "needs MOLEASM_PYTHON_PARITY=1 and python3 on PATH"]
fn python_parity_against_reference() {
    if std::env::var("MOLEASM_PYTHON_PARITY").as_deref() != Ok("1") {
        eprintln!("MOLEASM_PYTHON_PARITY != 1; skipping");
        return;
    }
    let dir = fixtures_dir();
    let py = dir.join("mole-asm.py");
    assert!(py.exists(), "reference python at {py:?} not found");

    for name in ["first-light", "tmp108", "i2c-write-one-byte"] {
        let src = dir.join(format!("{name}.moleasm"));
        let tmp_dir = std::env::temp_dir().join("mole-asm-parity");
        let _ = std::fs::create_dir_all(&tmp_dir);
        let py_bytecode = tmp_dir.join(format!("{name}.molecode"));
        let py_frame = tmp_dir.join(format!("{name}.mole.bin"));

        let status = std::process::Command::new("python3")
            .arg(&py)
            .arg(&src)
            .arg("-o")
            .arg(&py_bytecode)
            .arg("--frame")
            .arg(&py_frame)
            .status()
            .expect("failed to spawn python3");
        assert!(status.success(), "python assembler failed for {name}");

        let source = read_text(&src);
        let rust_words = assemble(&source, src.to_str().unwrap()).expect("rust assemble");
        let rust_bytecode = mole_asm::frame::pack_bytecode(&rust_words);
        let rust_frame = assemble_to_frame(&source, src.to_str().unwrap()).expect("rust frame");

        let py_bc = read_bytes(&py_bytecode);
        let py_fr = read_bytes(&py_frame);
        assert_eq!(rust_bytecode, py_bc, "{name} bytecode parity");
        assert_eq!(rust_frame, py_fr, "{name} frame parity");
    }
}
