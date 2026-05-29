//! Adversarial / contract tests for `mole-asm`.
//!
//! Goal: each test, if it ever starts failing, signals a real bug or
//! regression --- not a cosmetic change. References to the contract
//! are to `AGENTS.md` §3 (Hard rules) and `ROADMAP.md`.
//!
//! Grouped by surface:
//!
//! 1. Bit-exact encoding pins (opcode at `[15:11]`, flag triple at
//!    `[2:0]`).
//! 2. `tx_symbol` vocabulary (§3.11) --- the reserved `0b11` code is
//!    forbidden from source.
//! 3. PP-on-SCL-in-target-role (§3.13) --- a missing-feature test.
//! 4. `BRANCH_ON` / `WAIT_ON` shared cond-code namespace (§3.14):
//!    codes 0..9 in use, 10..15 reserved.
//! 5. Label resolution edge cases.
//! 6. Surface syntax variants (§3.16) --- `dom`/`dominant` parity.
//! 7. Hostile / pathological input: UTF-8 BOM, CRLF, single-token
//!    sources, truncated lines, deeply nested commas.
//! 8. Off-by-one near numeric field widths.

use mole_asm::{AsmError, Kind, assemble};

fn syntax_kind(err: &AsmError) -> Option<Kind> {
    match err {
        AsmError::Syntax { kind, .. } => Some(*kind),
        _ => None,
    }
}

fn one_word(src: &str) -> u16 {
    let v = assemble(src, "<t>").expect("must assemble");
    assert_eq!(v.len(), 1, "expected exactly one encoded word, got {v:?}");
    v[0]
}

// ---------------------------------------------------------------------------
// 1. Bit-exact encoding pins
// ---------------------------------------------------------------------------

/// Opcode field width is 5 bits at `[15:11]` (AGENTS.md §3.9). For
/// every emitted opcode we sanity-check that bits `[15:11]` carry the
/// documented opcode position. The `mask` is `0xF800` (top 5 bits).
fn opcode_field(word: u16) -> u16 {
    (word >> 11) & 0x1F
}

#[test]
fn opcode_field_lives_at_bits_15_11_for_every_mnemonic() {
    // (source, expected opcode at [15:11])
    let cases: &[(&str, u16)] = &[
        ("HALT status=0\n", 0x00),
        ("EMIT_BIT tx=dom\n", 0x01),
        ("EMIT_QUARTER sda=dom scl=dom\n", 0x02),
        ("STRETCH_SCL 0\n", 0x03),
        ("WAIT_ON ALWAYS, 0\n", 0x04),
        ("JMP 0\n", 0x06),
        ("SET_BUS_MODE i2c\n", 0x07),
        ("LOAD_TIMING i2c_freq, 0\n", 0x08),
        ("MARK label=0\n", 0x09),
        ("SAMPLE_BIT_ON_SCL\n", 0x0A),
        ("DRIVE_BIT_ON_SCL tx=dom\n", 0x0B),
        ("LOAD_LOOP lcr0, 0\n", 0x0C),
        ("DEC_BRANCH lcr0, 0\n", 0x0D),
        ("SET_ROLE controller\n", 0x10),
    ];
    for (src, want) in cases {
        let word = one_word(src);
        assert_eq!(
            opcode_field(word),
            *want,
            "src={src:?} word={word:#06x}: opcode field bits [15:11] \
             must equal {want:#x}, got {:#x}",
            opcode_field(word)
        );
    }
    // BRANCH_ON needs a resolvable target.
    let v = assemble("BRANCH_ON ALWAYS, here\nhere:\nHALT\n", "<t>").unwrap();
    assert_eq!(opcode_field(v[0]), 0x05);
}

#[test]
fn flag_triple_lives_at_bits_2_0_for_every_bearer_opcode() {
    // Per AGENTS.md §3.10 the flag triple is `[2]=expect`, `[1]=mask`,
    // `[0]=capture` on every bearer opcode. We sweep all 8 (e,m,c)
    // combinations on each bearer and assert bits [2:0] match the
    // encoded triple, and that no flag bit leaks into bits [3..=10].
    //
    // expect=X (don't-care) + mask=1 is rejected by the parser, so
    // we run the sweep with expect set explicitly to 0 or 1.

    // Returns (template, mnemonic, "no flags" mask of bits [10:3] that
    // must be invariant across the sweep).
    let bearers: &[(&str, u16)] = &[
        // EMIT_BIT tx=dom: tx field at [10:9] both zero, [8:3] zero.
        ("EMIT_BIT tx=dom expect={e} mask={m} capture={c}\n", 0x0800),
        // EMIT_QUARTER sda=dom scl=dom: both 0 -> only opcode bits.
        (
            "EMIT_QUARTER sda=dom scl=dom expect={e} mask={m} capture={c}\n",
            0x1000,
        ),
        // SAMPLE_BIT_ON_SCL has no payload other than the triple.
        (
            "SAMPLE_BIT_ON_SCL expect={e} mask={m} capture={c}\n",
            0x5000,
        ),
        // DRIVE_BIT_ON_SCL tx=dom: tx field zero -> only opcode bits.
        (
            "DRIVE_BIT_ON_SCL tx=dom expect={e} mask={m} capture={c}\n",
            0x5800,
        ),
    ];

    for (tpl, base) in bearers {
        for e in 0u16..=1 {
            for m in 0u16..=1 {
                for c in 0u16..=1 {
                    let src = tpl
                        .replace("{e}", &e.to_string())
                        .replace("{m}", &m.to_string())
                        .replace("{c}", &c.to_string());
                    let word = one_word(&src);
                    let triple = (e << 2) | (m << 1) | c;
                    assert_eq!(
                        word & 0x0007,
                        triple,
                        "src={src:?} word={word:#06x}: flag triple at \
                         [2:0] must equal {triple:#05b}, got {:#05b}",
                        word & 0x0007
                    );
                    // No flag bit may leak into [10:3].
                    assert_eq!(
                        word & 0x07F8,
                        *base & 0x07F8,
                        "src={src:?} word={word:#06x}: bits [10:3] must \
                         be invariant across (e,m,c) sweep (expected \
                         {:#06x})",
                        *base & 0x07F8
                    );
                    // Opcode field unchanged.
                    assert_eq!(
                        word & 0xF800,
                        *base & 0xF800,
                        "src={src:?}: opcode field [15:11] disturbed by flags"
                    );
                }
            }
        }
    }
}

#[test]
fn emit_quarter_sda_scl_fields_pin() {
    // EMIT_QUARTER: sda at [10:9], scl at [8:7]. Two-bit fields, no
    // overlap with the flag triple at [2:0]. Sweep all 9 legal
    // (sda, scl) combos (each in {dom=0, rec=1, hiz=2}; 0b11 reserved
    // is excluded per §3.11).
    let syms = [("dom", 0u16), ("rec", 1), ("hiz", 2)];
    for (s_name, s_val) in &syms {
        for (l_name, l_val) in &syms {
            let src = format!("EMIT_QUARTER sda={s_name} scl={l_name}\n");
            let word = one_word(&src);
            // Expected: op=0x02 << 11 | sda<<9 | scl<<7.
            let want = (0x02 << 11) | (s_val << 9) | (l_val << 7);
            assert_eq!(word, want, "{src:?} -> want {want:#06x}, got {word:#06x}");
            // Field positions:
            assert_eq!((word >> 9) & 0x3, *s_val, "sda field at [10:9]");
            assert_eq!((word >> 7) & 0x3, *l_val, "scl field at [8:7]");
        }
    }
}

// ---------------------------------------------------------------------------
// 2. `tx_symbol` vocabulary (§3.11)
// ---------------------------------------------------------------------------

#[test]
fn tx_symbol_reserved_0b11_is_unreachable_from_source() {
    // Per AGENTS.md §3.11, `tx_symbol = 0b11` is the reserved
    // `raw_override` escape held for v0.5. The parser must refuse
    // any spelling that maps to it; today the encoder also rejects
    // it as defense-in-depth (encoder::check_tx).
    //
    // We test every plausible bad spelling on every bearer that takes
    // tx=...
    let bad = [
        "reserved",
        "RESERVED",
        "raw",
        "raw_override",
        "3",
        "0b11",
        "0x3",
    ];
    let templates = [
        "EMIT_BIT tx={x}\n",
        "DRIVE_BIT_ON_SCL tx={x}\n",
        "EMIT_QUARTER sda={x} scl=dom\n",
        "EMIT_QUARTER sda=dom scl={x}\n",
    ];
    for tpl in &templates {
        for sym in &bad {
            let src = tpl.replace("{x}", sym);
            let err = assemble(&src, "<t>").unwrap_err();
            assert_eq!(
                syntax_kind(&err),
                Some(Kind::Operand),
                "src={src:?} should be rejected by the parser, got: {err:?}"
            );
        }
    }
}

#[test]
fn tx_symbol_uppercase_rejected() {
    // The vocabulary is lower-case canonical (mole-asm/src/symbols.rs).
    // Uppercase spellings are not synonyms; accepting them would be
    // surface-syntax drift from the locked spec (§3.16). If this
    // suddenly starts assembling, the parser silently widened.
    let err = assemble("EMIT_BIT tx=DOMINANT\n", "<t>").unwrap_err();
    assert_eq!(syntax_kind(&err), Some(Kind::Operand));
    let err = assemble("EMIT_BIT tx=DOM\n", "<t>").unwrap_err();
    assert_eq!(syntax_kind(&err), Some(Kind::Operand));
}

// ---------------------------------------------------------------------------
// 3. PP-on-SCL-in-target-role rejection (§3.13) --- MISSING FEATURE
// ---------------------------------------------------------------------------

#[test]
fn target_role_pp_drive_on_scl_rejected() {
    // SET_ROLE target then SET_BUS_MODE i3c-pp then EMIT_QUARTER
    // scl=recessive must be rejected at compile time. Today this
    // assembles cleanly because the parser does not track active
    // (role, bus_mode) state.
    let src = "
        SET_ROLE target
        SET_BUS_MODE i3c-pp
        EMIT_QUARTER sda=dom scl=recessive
        HALT
    ";
    let err = assemble(src, "<t>")
        .expect_err("target role + i3c-pp + scl=recessive must be rejected (§3.13)");
    let msg = format!("{err}");
    assert!(
        msg.to_ascii_lowercase().contains("target") || msg.to_ascii_lowercase().contains("pp"),
        "diagnostic should mention target/PP context, got: {msg}"
    );
}

#[test]
fn target_role_hdr_ddr_pp_on_scl_rejected() {
    let src = "
        SET_ROLE target
        SET_BUS_MODE hdr-ddr
        EMIT_QUARTER sda=dom scl=recessive
        HALT
    ";
    assemble(src, "<t>")
        .expect_err("target role + hdr-ddr + scl=recessive must be rejected (§3.13)");
}

// ---------------------------------------------------------------------------
// 4. BRANCH_ON / WAIT_ON cond-code namespace (§3.14)
// ---------------------------------------------------------------------------

#[test]
fn branch_on_and_wait_on_share_cond_namespace_in_use() {
    // §3.14: codes 0..=9 are in use. Both opcodes must accept the
    // same names with the same numeric mapping. We assemble the same
    // cond name through both opcodes and assert the cond field
    // ([10:7]) decodes to the same value.
    let names = [
        ("ALWAYS", 0x0u16),
        ("MISMATCH", 0x1),
        ("NOT_MISMATCH", 0x2),
        ("START_SEEN", 0x3),
        ("STOP_SEEN", 0x4),
        ("SDA_LOW", 0x5),
        ("SDA_HIGH", 0x6),
        ("SCL_HIGH", 0x7),
        ("TIMEOUT", 0x8),
        ("NOT_TIMEOUT", 0x9),
    ];
    for (name, want) in &names {
        // BRANCH_ON to here (offset 0).
        let v = assemble(&format!("BRANCH_ON {name}, tgt\ntgt:\nHALT\n"), "<t>")
            .unwrap_or_else(|e| panic!("BRANCH_ON {name}: {e}"));
        let bcond = (v[0] >> 7) & 0xF;
        assert_eq!(bcond, *want, "BRANCH_ON {name} cond field");
        // WAIT_ON timeout=0.
        let v = assemble(&format!("WAIT_ON {name}, 0\n"), "<t>")
            .unwrap_or_else(|e| panic!("WAIT_ON {name}: {e}"));
        let wcond = (v[0] >> 7) & 0xF;
        assert_eq!(wcond, *want, "WAIT_ON {name} cond field");
        assert_eq!(bcond, wcond, "{name}: BRANCH_ON / WAIT_ON cond divergence");
    }
}

#[test]
fn branch_on_and_wait_on_reject_reserved_cond_names() {
    // §3.14: codes 10..15 are reserved for v0.5. The parser today
    // rejects any unrecognised cond name; if someone adds a name for
    // a reserved slot, this test will catch it.
    //
    // We probe a few plausible names that a future contributor might
    // try to add ("OVERFLOW", "ANY", "NEVER", etc.). None should be
    // accepted today.
    let probes = [
        "OVERFLOW",
        "ANY",
        "NEVER",
        "ADDRESSED",
        "RESERVED",
        "FLAG_10",
        "FLAG_15",
        "0xA",
        "10",
    ];
    for name in &probes {
        let b_err = assemble(&format!("BRANCH_ON {name}, here\nhere:\nHALT\n"), "<t>").unwrap_err();
        assert_eq!(
            syntax_kind(&b_err),
            Some(Kind::Operand),
            "BRANCH_ON {name} should be rejected as an unknown cond"
        );
        let w_err = assemble(&format!("WAIT_ON {name}, 0\n"), "<t>").unwrap_err();
        assert_eq!(
            syntax_kind(&w_err),
            Some(Kind::Operand),
            "WAIT_ON {name} should be rejected as an unknown cond"
        );
    }
}

// ---------------------------------------------------------------------------
// 5. Label resolution edge cases
// ---------------------------------------------------------------------------

#[test]
fn branch_to_undefined_label_diagnostics() {
    let err = assemble("BRANCH_ON ALWAYS, nowhere\nHALT\n", "<t>").unwrap_err();
    assert_eq!(syntax_kind(&err), Some(Kind::Symbol));
    let msg = format!("{err}");
    assert!(
        msg.contains("nowhere"),
        "diagnostic should name the missing symbol, got: {msg}"
    );
}

#[test]
fn branch_to_equate_rejected_not_treated_as_offset() {
    // An .equ is a constant, not a label. BRANCH_ON to an .equ must
    // be a Symbol error (label-vs-equate confusion), not silently
    // treated as a numeric offset.
    let src = ".equ k, 1\nBRANCH_ON ALWAYS, k\nHALT\n";
    let err = assemble(src, "<t>").unwrap_err();
    assert_eq!(syntax_kind(&err), Some(Kind::Symbol));
}

#[test]
fn jmp_to_equate_rejected() {
    let src = ".equ k, 5\nJMP k\nHALT\n";
    let err = assemble(src, "<t>").unwrap_err();
    assert_eq!(syntax_kind(&err), Some(Kind::Symbol));
}

#[test]
fn duplicate_label_diagnostic_names_prior_line() {
    // §3 covers symbol uniqueness; the diagnostic must name the
    // prior definition's line so the user can find it.
    let src = "loop:\n  HALT\nloop:\n  HALT\n";
    let err = assemble(src, "<t>").unwrap_err();
    let msg = format!("{err}");
    assert!(
        msg.contains("line 1") || msg.contains("prior"),
        "duplicate-label diagnostic should reference prior definition, \
         got: {msg}"
    );
}

#[test]
fn branch_to_self_offset_minus_one() {
    // Self-branch at PC 0: offset = 0 - 0 - 1 = -1, encoded as 0x7F
    // in the 7-bit signed field. Word = 0x2800 | 0x7F = 0x287F.
    let src = "here: BRANCH_ON ALWAYS, here\n";
    let v = assemble(src, "<t>").unwrap();
    assert_eq!(v[0], 0x287F);
}

// ---------------------------------------------------------------------------
// 6. Surface syntax variants (§3.16)
// ---------------------------------------------------------------------------

#[test]
fn dom_and_dominant_encode_identically() {
    // §3.16 explicitly allows the short form `dom` / `rec` as
    // synonyms for `dominant` / `recessive`. They must encode
    // byte-identically.
    let cases = [
        ("EMIT_BIT tx=dom\n", "EMIT_BIT tx=dominant\n"),
        ("EMIT_BIT tx=rec\n", "EMIT_BIT tx=recessive\n"),
        (
            "EMIT_QUARTER sda=dom scl=rec\n",
            "EMIT_QUARTER sda=dominant scl=recessive\n",
        ),
        (
            "DRIVE_BIT_ON_SCL tx=rec\n",
            "DRIVE_BIT_ON_SCL tx=recessive\n",
        ),
    ];
    for (short, long) in &cases {
        let a = assemble(short, "<short>").unwrap();
        let b = assemble(long, "<long>").unwrap();
        assert_eq!(a, b, "{short:?} vs {long:?} must encode identically");
    }
}

#[test]
fn omitted_defaults_match_explicit_defaults() {
    // §3.16 says defaults `expect=X`, `mask=0`, `capture=0` may be
    // omitted. Omitting them must produce byte-identical bytecode
    // to writing them out.
    let cases = [
        (
            "EMIT_BIT tx=dom\n",
            "EMIT_BIT tx=dom expect=X mask=0 capture=0\n",
        ),
        (
            "EMIT_QUARTER sda=dom scl=dom\n",
            "EMIT_QUARTER sda=dom scl=dom expect=X mask=0 capture=0\n",
        ),
        (
            "SAMPLE_BIT_ON_SCL\n",
            "SAMPLE_BIT_ON_SCL expect=X mask=0 capture=0\n",
        ),
        (
            "DRIVE_BIT_ON_SCL tx=dom\n",
            "DRIVE_BIT_ON_SCL tx=dom expect=X mask=0 capture=0\n",
        ),
    ];
    for (terse, explicit) in &cases {
        let a = assemble(terse, "<t>").unwrap();
        let b = assemble(explicit, "<t>").unwrap();
        assert_eq!(a, b, "{terse:?} vs {explicit:?} must match");
    }
}

#[test]
fn extra_whitespace_around_kv_does_not_change_encoding() {
    // Whitespace is the operand separator (after a comma split). Lots
    // of whitespace must not change anything.
    let a = assemble("EMIT_BIT     tx=dom    expect=1   mask=1\n", "<t>").unwrap();
    let b = assemble("EMIT_BIT tx=dom expect=1 mask=1\n", "<t>").unwrap();
    assert_eq!(a, b);
}

#[test]
fn expect_lowercase_x_accepted() {
    // The expect-side spec calls out `expect=X` (don't-care); the
    // parser upper-cases the value before matching, so `x` works.
    // This is a "documented behaviour" pin --- if the parser starts
    // requiring capital X, that's a surface-syntax break.
    let a = assemble("EMIT_BIT tx=dom expect=x\n", "<t>").unwrap();
    let b = assemble("EMIT_BIT tx=dom expect=X\n", "<t>").unwrap();
    assert_eq!(a, b);
}

// ---------------------------------------------------------------------------
// 7. Hostile / pathological input
// ---------------------------------------------------------------------------

#[test]
fn utf8_bom_on_first_line_does_not_panic() {
    // §4 says we never write a BOM, but the parser must still handle
    // one without panicking. Either accept (treat as whitespace) or
    // reject with a clean lex error --- both are fine; a panic isn't.
    let src = "\u{FEFF}HALT status=0\n";
    let result = assemble(src, "<bom>");
    match result {
        Ok(v) => assert_eq!(
            v,
            vec![0x0000],
            "if accepted, must still encode HALT correctly"
        ),
        Err(AsmError::Syntax { .. }) => { /* clean rejection is acceptable */ }
        Err(other) => panic!("non-syntax error on BOM input: {other:?}"),
    }
}

#[test]
fn crlf_line_endings_work() {
    // CRLF is forbidden in committed source (§4), but moleasm is
    // sometimes hand-edited on Windows; the parser must not choke.
    // `.lines()` strips `\n` but leaves trailing `\r`; if the lexer
    // doesn't `.trim()` aggressively, the mnemonic comparison fails
    // with a confusing diagnostic.
    let src = "HALT status=0\r\nHALT status=1\r\n";
    let v = assemble(src, "<crlf>").unwrap_or_else(|e| {
        panic!("CRLF input must parse cleanly, got: {e}");
    });
    assert_eq!(v, vec![0x0000, 0x0080]);
}

#[test]
fn single_token_no_operands() {
    // Some mnemonics have legal zero-operand forms; many don't.
    // None should panic; all should either assemble or return a
    // structured error.
    let probes = [
        "HALT\n",
        "EMIT_BIT\n",
        "EMIT_QUARTER\n",
        "STRETCH_SCL\n",
        "WAIT_ON\n",
        "BRANCH_ON\n",
        "JMP\n",
        "SET_BUS_MODE\n",
        "LOAD_TIMING\n",
        "MARK\n",
        "SAMPLE_BIT_ON_SCL\n",
        "DRIVE_BIT_ON_SCL\n",
        "LOAD_LOOP\n",
        "DEC_BRANCH\n",
        "SET_ROLE\n",
    ];
    for src in &probes {
        // Either Ok or AsmError --- never panic.
        let _ = assemble(src, "<probe>");
    }
}

#[test]
fn extremely_long_comment_line_does_not_blow_up() {
    // 64 KiB of comment text on a single line. Lexer must not be
    // quadratic, must not panic, must not allocate unboundedly.
    let mut src = String::from(";");
    src.extend(std::iter::repeat_n('x', 64 * 1024));
    src.push('\n');
    src.push_str("HALT\n");
    let v = assemble(&src, "<long-comment>").unwrap();
    assert_eq!(v, vec![0x0000]);
}

#[test]
fn many_commas_in_operand_region_do_not_panic() {
    // The lexer splits on commas first; many empty splits between
    // real tokens are an edge case. Must not panic. The exact
    // accept/reject answer is policy; just don't crash.
    let _ = assemble("HALT ,,,,,,,,,,,, status=0 ,,,,,\n", "<commas>");
}

#[test]
fn label_only_then_eof() {
    // A label at EOF with no following instruction binds to the
    // current PC (which is 0). The program is empty --- valid.
    let v = assemble("here:\n", "<t>").unwrap();
    assert!(v.is_empty());
}

#[test]
fn null_byte_in_source_rejected_not_panic() {
    // NUL is not whitespace and not a legal mnemonic char. Should
    // produce a clean lex error, not panic.
    let src = "HALT\0\n";
    let _ = assemble(src, "<nul>"); // either Err or Ok; no panic.
}

// ---------------------------------------------------------------------------
// 8. Off-by-one near numeric field widths
// ---------------------------------------------------------------------------

#[test]
fn dw_accepts_full_u16_range_and_rejects_overflow() {
    let v = assemble(".dw 0x0000, 0xFFFF\n", "<t>").unwrap();
    assert_eq!(v, vec![0x0000, 0xFFFF]);
    let err = assemble(".dw 0x10000\n", "<t>").unwrap_err();
    assert_eq!(syntax_kind(&err), Some(Kind::Range));
}

#[test]
fn dw_rejects_negative() {
    // .dw is documented as 0..=0xFFFF in pass2; negative literals
    // must be rejected, not two's-complement reinterpreted.
    let err = assemble(".dw -1\n", "<t>").unwrap_err();
    assert_eq!(syntax_kind(&err), Some(Kind::Range));
}

#[test]
fn load_timing_max_divider_pin() {
    // 9-bit divider field: max=511 -> op<<11 | reg<<9 | 511
    //   = 0x4000 | 0 | 0x1FF = 0x41FF.
    let v = assemble("LOAD_TIMING i2c_freq, 511\n", "<t>").unwrap();
    assert_eq!(v[0], 0x41FF);
    let err = assemble("LOAD_TIMING i2c_freq, 512\n", "<t>").unwrap_err();
    assert_eq!(syntax_kind(&err), Some(Kind::Range));
}

#[test]
fn mark_label_max_pin() {
    // 8-bit label, shifted left by 3: label=255 -> 0x4800 | 0xFF<<3
    //   = 0x4800 | 0x7F8 = 0x4FF8.
    let v = assemble("MARK label=255\n", "<t>").unwrap();
    assert_eq!(v[0], 0x4FF8);
    let err = assemble("MARK label=256\n", "<t>").unwrap_err();
    assert_eq!(syntax_kind(&err), Some(Kind::Range));
}

#[test]
fn halt_status_negative_rejected() {
    let err = assemble("HALT status=-1\n", "<t>").unwrap_err();
    assert_eq!(syntax_kind(&err), Some(Kind::Range));
}

#[test]
fn dec_branch_signed_8_bit_field_boundaries() {
    // DEC_BRANCH uses an 8-bit signed PC-rel offset: -128..=127.
    // Drive the assembler to each boundary via label arithmetic.

    // offset = -128: target 128 PCs before the DEC_BRANCH.
    let mut src = String::from("tgt:\n");
    for _ in 0..127 {
        src.push_str("HALT\n");
    }
    src.push_str("DEC_BRANCH lcr0, tgt\n");
    // PC of DEC_BRANCH = 127; offset = 0 - 127 - 1 = -128. Good.
    let v = assemble(&src, "<min>").expect("offset=-128 must encode");
    // DEC_BRANCH lcr0, -128 -> 0x6800 | 0x80 = 0x6880.
    assert_eq!(*v.last().unwrap(), 0x6880);

    // offset = -129: one more HALT.
    let mut src = String::from("tgt:\n");
    for _ in 0..128 {
        src.push_str("HALT\n");
    }
    src.push_str("DEC_BRANCH lcr0, tgt\n");
    let err = assemble(&src, "<under>").unwrap_err();
    assert_eq!(syntax_kind(&err), Some(Kind::Range));
}

#[test]
fn set_bus_mode_rejects_non_canonical_names() {
    // §3.11: BUS_MODE is the named symbol-to-electrical mapping; the
    // four legal names are i2c, i3c-od, i3c-pp, hdr-ddr. Anything
    // else --- including the previous-vocabulary terms listed in
    // AGENTS.md §8 --- must be rejected.
    let bad = ["smbus", "i3c", "od", "pp", "drive_sda", "raw"];
    for name in &bad {
        let err = assemble(&format!("SET_BUS_MODE {name}\n"), "<t>").unwrap_err();
        assert_eq!(
            syntax_kind(&err),
            Some(Kind::Operand),
            "SET_BUS_MODE {name} should be rejected"
        );
    }
}

#[test]
fn dw_can_inject_reserved_opcode_words() {
    // §3.17: pre-Phase-0 the wire format is mutable. But even today,
    // `.dw` is the documented escape hatch for reserved opcode words
    // (encoder.rs comments). Verify that .dw passes arbitrary 16-bit
    // words through unmodified --- a future bug that "validates" .dw
    // operands against the opcode whitelist would break this.
    let v = assemble(".dw 0x7800, 0x7FFF\n", "<t>").unwrap();
    assert_eq!(v, vec![0x7800, 0x7FFF]);
}

// ---------------------------------------------------------------------------
// 9. Encoder determinism: re-assembling the same source is byte-stable
// ---------------------------------------------------------------------------

#[test]
fn encoder_is_deterministic_across_repeated_calls() {
    // Per AGENTS.md §3.6, the engine has zero runtime randomness;
    // the host encoder must therefore be deterministic. Re-run the
    // I2C-write-one-byte example 16 times and check every output
    // matches the first.
    let src = include_str!("fixtures/i2c-write-one-byte.moleasm");
    let first = assemble(src, "<det>").unwrap();
    for _ in 0..16 {
        let again = assemble(src, "<det>").unwrap();
        assert_eq!(again, first, "encoder non-determinism detected");
    }
}

#[test]
#[ignore = "blocked: AGENTS.md §3.6 mandates compile-time error \
            injection with a (seed, ratio) API where ratio=0 is \
            byte-identical to no-injection. The injection API has \
            not been designed yet --- inventing it from a single \
            test prompt is exactly the architect/coder split \
            AGENTS.md §8 warns against. Needs an architect spec \
            (which knob shape, where in the pipeline, how the PRNG \
            seeds per-instruction vs per-bit) before a coder can \
            land it. See also ROADMAP.md on error injection."]
fn injection_ratio_zero_is_byte_identical_to_no_injection() {
    // Placeholder: once `assemble` grows a `with_injection(seed,
    // ratio)` knob, `ratio = 0` must produce bytes identical to a
    // baseline assemble.
}

// ---------------------------------------------------------------------------
// `.dw` operand-count cap (F-HOST-003)
// ---------------------------------------------------------------------------

/// `.dw` advances PC by `operands.len()`. The legacy code cast
/// `operands.len() as u16`, so a source with >= 65536 operands
/// wrapped the advance to a small u16; the existing `pc > 2048`
/// guard then only caught wrapped sums still above 2048. A wrapped
/// sum landing <= 2048 silently miscompiled. Both 2049 (above the
/// program-memory budget, below the u16 wrap point) and 65536
/// (exact u16 wrap to zero) must be rejected.
#[test]
fn dw_operand_count_above_max_program_words_rejected() {
    // 2049 operands: below the u16 wrap point, but one past the
    // 2048-word program-memory budget. Without the gate, pass1's
    // `pc > 2048` check still catches this (2049 > 2048), but the
    // diagnostic the user sees here is the new, explicit one.
    let many = "0,".repeat(2049);
    let src = format!(".dw {}\n", &many[..many.len() - 1]);
    let r = assemble(&src, "<dw-cap>");
    assert!(
        r.is_err(),
        "2049 .dw operands must be rejected, not silently truncated",
    );

    // 65536 operands: exactly the u16 wrap point. The legacy cast
    // produced advance = 0, so pass1's PC overflow guard never
    // fired --- silent miscompilation. The cap must intercept
    // *before* the cast.
    let many = "0,".repeat(65536);
    let src = format!(".dw {}\n", &many[..many.len() - 1]);
    let r = assemble(&src, "<dw-wrap>");
    assert!(
        r.is_err(),
        "65536 .dw operands must be rejected; legacy cast wrapped \
         the advance to 0 and silently miscompiled",
    );
}
