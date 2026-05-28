//! Integration test: `verify_frame` must accept every committed
//! `.mole.bin` fixture in `mole-asm/tests/fixtures/`.
//!
//! These artifacts are produced by the assembler and committed as
//! goldens; they are also what the eventual loader CLI will be
//! sending over UART. If the verifier rejects one of them, the
//! verifier is wrong --- the encoder side is authoritative.

use std::fs;
use std::path::{Path, PathBuf};

use mole_loader::verify_frame;

fn fixtures_dir() -> PathBuf {
    // `CARGO_MANIFEST_DIR` is `<workspace>/mole-loader/`; the
    // fixtures live in a sibling crate.
    let mut p = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    p.pop();
    p.push("mole-asm");
    p.push("tests");
    p.push("fixtures");
    p
}

fn verify_fixture(name: &str) {
    let path: PathBuf = fixtures_dir().join(name);
    let bytes = fs::read(&path).unwrap_or_else(|e| panic!("read {}: {e}", path.display()));
    let words = verify_frame(&bytes).unwrap_or_else(|e| {
        panic!(
            "verify_frame({}) rejected a committed fixture: {e}",
            path.display()
        )
    });
    assert!(
        !words.is_empty(),
        "{}: verifier returned zero words --- frame had a valid header but no body?",
        path.display()
    );
    // Cross-check: the header word count must round-trip to the
    // number of words decoded.
    let header_words = u16::from_le_bytes([bytes[0], bytes[1]]) as usize;
    assert_eq!(
        header_words,
        words.len(),
        "{}: header says {header_words} words, verifier yielded {} words",
        path.display(),
        words.len()
    );
}

#[test]
fn first_light_fixture() {
    verify_fixture("first-light.mole.bin");
}

#[test]
fn i2c_write_one_byte_fixture() {
    verify_fixture("i2c-write-one-byte.mole.bin");
}

#[test]
fn loop_counter_demo_fixture() {
    verify_fixture("loop-counter-demo.mole.bin");
}

#[test]
fn tmp108_fixture() {
    verify_fixture("tmp108.mole.bin");
}

#[test]
fn fixtures_dir_exists() {
    // Sanity: if someone moves the fixtures, fail loudly here rather
    // than as four separate "file not found" errors above.
    let dir = fixtures_dir();
    assert!(
        Path::new(&dir).is_dir(),
        "fixtures dir not found at {}",
        dir.display()
    );
}
