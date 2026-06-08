//! Proptest-based panic-hunting fuzzing for the mole-asm
//! public API.
//!
//! These tests assert STATISTICAL absence of panics across
//! the input space, complementing the targeted tests in
//! `tests/adversarial.rs`. If a property fails, proptest
//! shrinks to a minimal repro; copy it into `adversarial.rs`
//! as a named regression test before fixing the underlying
//! issue.
//!
//! Run only these:    cargo test -p mole-asm --test fuzz
//! Skip these:        cargo test -p mole-asm -- --skip fuzz
//! Heavier run:       PROPTEST_CASES=10000 cargo test -p mole-asm --test fuzz

use proptest::prelude::*;

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/// A strategy that produces one line of moleasm-looking source text.
///
/// The mix (via `prop_oneof!`) covers:
/// - A real mnemonic followed by random operand bytes.
/// - A label declaration (`ident:`).
/// - A comment (`;` + random text).
/// - An empty line.
///
/// This pushes proptest inputs deeper into the parser than purely
/// random UTF-8 does, stressing paths after lexer acceptance.
fn structured_line_strategy() -> impl Strategy<Value = String> {
    let mnemonic = prop_oneof![
        Just("HALT"),
        Just("EMIT_BIT"),
        Just("EMIT_QUARTER"),
        Just("EMIT_BYTE"),
        Just("LOAD_IMM"),
        Just("LOAD_LOOP"),
        Just("DEC_BRANCH"),
        Just("BRANCH_ON"),
        Just("WAIT_ON"),
        Just("SET_BUS_MODE"),
        Just("SET_ROLE"),
        Just("SAMPLE_BIT_ON_SCL"),
        Just("DRIVE_BIT_ON_SCL"),
        Just("NOP"),
        Just("JMP"),
        Just(".dw"),
        Just(".equ"),
    ];

    // Random ASCII-printable operand tail (may be empty).
    let operand_tail = prop::collection::vec(prop::char::ranges(vec![' '..='~'].into()), 0..64)
        .prop_map(|chars| chars.into_iter().collect::<String>());

    // ASCII identifier for labels.  Build fresh each arm so we don't
    // need Clone on RegexGeneratorStrategy.
    let mk_ident =
        || prop::string::string_regex("[a-zA-Z_][a-zA-Z0-9_]{0,15}").expect("valid regex");

    // Random comment text (any printable ASCII).
    let comment_text = prop::collection::vec(prop::char::ranges(vec![' '..='~'].into()), 0..80)
        .prop_map(|chars| chars.into_iter().collect::<String>());

    prop_oneof![
        // weight 4 – mnemonic line: most interesting for the parser
        4 => (mnemonic, operand_tail)
            .prop_map(|(mn, tail)| format!("{mn} {tail}")),
        // weight 2 – label declaration
        2 => mk_ident().prop_map(|id| format!("{id}:")),
        // weight 1 – comment
        1 => comment_text.prop_map(|t| format!("; {t}")),
        // weight 1 – empty line
        1 => Just(String::new()),
    ]
}

// ---------------------------------------------------------------------------
// Property 1: `assemble` never panics on arbitrary UTF-8
// ---------------------------------------------------------------------------

proptest! {
    #![proptest_config(ProptestConfig {
        cases: 1024,
        max_global_rejects: 65536,
        .. ProptestConfig::default()
    })]

    /// `mole_asm::assemble` must return `Result` for any UTF-8 input.
    /// It must NEVER panic, infinite-loop, or abort.
    #[test]
    fn assemble_never_panics_on_arbitrary_utf8(src in ".*") {
        let _ = std::panic::catch_unwind(|| {
            let _ = mole_asm::assemble(&src, "<proptest>");
        }).expect("assemble panicked on arbitrary UTF-8 input");
    }
}

// ---------------------------------------------------------------------------
// Property 2: `assemble` never panics on arbitrary BYTES (lossy UTF-8)
// ---------------------------------------------------------------------------

proptest! {
    #![proptest_config(ProptestConfig {
        cases: 512,
        .. ProptestConfig::default()
    })]

    /// Bytes that survive `from_utf8_lossy` MUST also be safe to
    /// assemble. Lossy conversion produces replacement characters
    /// \u{FFFD} which is a valid edge case the lexer must handle.
    #[test]
    fn assemble_never_panics_on_lossy_bytes(
        bytes in prop::collection::vec(any::<u8>(), 0..4096)
    ) {
        let src = String::from_utf8_lossy(&bytes).into_owned();
        let _ = std::panic::catch_unwind(|| {
            let _ = mole_asm::assemble(&src, "<proptest>");
        }).expect("assemble panicked on lossy-UTF-8 input");
    }
}

// ---------------------------------------------------------------------------
// Property 3: `assemble` never panics on structured-looking input
// ---------------------------------------------------------------------------

proptest! {
    #![proptest_config(ProptestConfig {
        cases: 512,
        .. ProptestConfig::default()
    })]

    /// Inputs that look like real moleasm statements --- mnemonic-shaped
    /// identifiers, key=value operands, labels --- exercise the parser
    /// past its first rejection. Must not panic regardless of validity.
    #[test]
    fn assemble_never_panics_on_structured_input(
        lines in prop::collection::vec(structured_line_strategy(), 0..64)
    ) {
        let src = lines.join("\n");
        let _ = std::panic::catch_unwind(|| {
            let _ = mole_asm::assemble(&src, "<proptest>");
        }).expect("assemble panicked on structured input");
    }
}

// ---------------------------------------------------------------------------
// Property 4: `build_frame` never panics on arbitrary u32 slices
// ---------------------------------------------------------------------------

proptest! {
    #![proptest_config(ProptestConfig {
        cases: 256,
        .. ProptestConfig::default()
    })]

    /// `build_frame` must reject oversize / undersize slices with
    /// `FrameTooLarge`, not panic.
    #[test]
    fn build_frame_never_panics(
        words in prop::collection::vec(any::<u32>(), 0..10_000)
    ) {
        let _ = std::panic::catch_unwind(|| {
            let _ = mole_asm::frame::build_frame(&words);
        }).expect("build_frame panicked on arbitrary u32 slice");
    }
}

// ---------------------------------------------------------------------------
// Property 5: `crc16_xmodem` never panics on arbitrary bytes
// ---------------------------------------------------------------------------

proptest! {
    #![proptest_config(ProptestConfig {
        cases: 256,
        .. ProptestConfig::default()
    })]

    /// CRC computation is pure and bounded. Must not panic on any byte
    /// slice, including empty and huge slices.
    #[test]
    fn crc16_never_panics(
        bytes in prop::collection::vec(any::<u8>(), 0..100_000)
    ) {
        let _ = std::panic::catch_unwind(|| {
            let _ = mole_asm::frame::crc16_xmodem(&bytes);
        }).expect("crc16_xmodem panicked on arbitrary bytes");
    }
}

// ---------------------------------------------------------------------------
// Property 6: `pack_bytecode` never panics
// ---------------------------------------------------------------------------

proptest! {
    #![proptest_config(ProptestConfig {
        cases: 256,
        .. ProptestConfig::default()
    })]

    /// `pack_bytecode` is a pure little-endian pack; length is
    /// `4 * words.len()`. Must not panic.
    #[test]
    fn pack_bytecode_never_panics(
        words in prop::collection::vec(any::<u32>(), 0..10_000)
    ) {
        let _ = std::panic::catch_unwind(|| {
            let bytes = mole_asm::frame::pack_bytecode(&words);
            // Length invariant: 4 bytes per word.
            assert_eq!(bytes.len(), words.len() * 4);
        }).expect("pack_bytecode panicked");
    }
}

// ---------------------------------------------------------------------------
// Property 7: round-trip safety for inputs that DO assemble
// ---------------------------------------------------------------------------

proptest! {
    #![proptest_config(ProptestConfig {
        cases: 512,
        .. ProptestConfig::default()
    })]

    /// If `assemble` succeeds, `build_frame` on its output must also
    /// succeed --- the assembler must not produce output the framer
    /// rejects.
    ///
    /// Previously failed with `assemble("")` returning `Ok` with a
    /// 2-word preamble-only vector that `build_frame` then rejected.
    /// Fixed: the assembler now raises E-FRM-003 for empty programs,
    /// so any successful assemble is guaranteed to carry ≥1 body word.
    #[test]
    fn successful_assemble_always_frames(
        lines in prop::collection::vec(structured_line_strategy(), 0..32)
    ) {
        let src = lines.join("\n");
        if let Ok(words) = mole_asm::assemble(&src, "<proptest>") {
            let frame_result = std::panic::catch_unwind(|| {
                mole_asm::frame::build_frame(&words)
            });
            let frame = frame_result
                .expect("build_frame panicked on assembler output");
            assert!(
                frame.is_ok(),
                "assembler produced output that build_frame rejected: \
                 src={src:?} words.len()={}",
                words.len()
            );
        }
    }
}
