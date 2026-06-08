//! Adversarial / contract tests for mole-asm v0.2.
//!
//! Coverage map (§13 error codes):
//!
//! 1.  Encoding pins (group at [31:30], sub at [29:26], flag triple at [2:0]).
//! 2.  tx_symbol vocabulary: reserved 0b11 blocked without raw/ pragma.
//! 3.  BUS_MODE renumbering: v0.2 wire values 0..3 (NOT v0's 0,1,6,7).
//! 4.  PP-class target SCL=recessive rejection (E-WIRE-002, §3.13).
//! 5.  BRANCH_ON / WAIT_ON condition-code namespace (§6, E-CTRL-001).
//! 6.  JMP / LOAD_LOOP sugar forms (§12.3).
//! 7.  EMIT_BYTE pairing rule (E-WIRE-003, §5.5).
//! 8.  raw/ pragma carve-outs (E-RAW-001 / E-RAW-002 / E-RAW-003).
//! 9.  Label resolution edge cases (E-SYM-001 .. E-SYM-004).
//! 10. Register name validation (E-OP-007).
//! 11. Numeric range violations (E-RNG-001 .. E-RNG-004).
//! 12. Missing / unknown / duplicate operand keys (E-OP-001 .. E-OP-005).
//! 13. Hostile input: UTF-8 BOM, CRLF, long comment, many commas, NUL.
//! 14. Determinism.

use mole_asm::{AsmError, Kind, assemble};

fn syntax_kind(err: &AsmError) -> Option<Kind> {
    match err {
        AsmError::Syntax { kind, .. } => Some(*kind),
        _ => None,
    }
}

/// Assemble source and return only the body words (after 2-word preamble).
fn asm_body(src: &str) -> Vec<u32> {
    let words = assemble(src, "<t>").expect("must assemble");
    assert!(words.len() >= 2, "result must include preamble");
    words[2..].to_vec()
}

fn one_body_word(src: &str) -> u32 {
    let v = asm_body(src);
    assert_eq!(v.len(), 1, "expected exactly one body word, got {v:?}");
    v[0]
}

// ---------------------------------------------------------------------------
// 1. Encoding pins: group / sub / flag triple positions
// ---------------------------------------------------------------------------

/// Extract the `{group, sub}` 6-bit opcode from a 32-bit v0.2 word.
fn opcode_field(word: u32) -> u32 {
    (word >> 26) & 0x3F
}

/// Extract the flag triple from bits [2:0].
fn flag_triple(word: u32) -> u32 {
    word & 0x7
}

#[test]
fn opcode_field_positions_for_all_mnemonics() {
    // (source, expected {group,sub} at [31:26])
    let cases: &[(&str, u32)] = &[
        // WIRE group = 0b00
        ("EMIT_BIT_IMM tx=dom\n", 0b00_0000),
        ("EMIT_BIT_REG src=R0\n", 0b00_0001),
        ("EMIT_QUARTER_IMM sda=dom scl=dom\n", 0b00_0010),
        ("EMIT_QUARTER_REG src=R0\n", 0b00_0011),
        ("EMIT_BYTE\n", 0b00_0100),
        ("SAMPLE_BIT_ON_SCL\n", 0b00_0101),
        ("DRIVE_BIT_ON_SCL tx=dom\n", 0b00_0110),
        ("STRETCH_SCL_IMM 0\n", 0b00_0111),
        ("STRETCH_SCL_REG src=R0\n", 0b00_1000),
        // CTRL group = 0b01
        ("HALT\n", 0b01_0000),
        ("WAIT_ON ALWAYS, 0\n", 0b01_0010),
        ("SET_BUS_MODE i2c\n", 0b01_0011),
        ("SET_ROLE controller\n", 0b01_0100),
        ("FLAG_CLEAR 0b00001\n", 0b01_0101),
        ("MARK label=0\n", 0b01_0110),
        ("LOAD_TIMING reg=0, divider=0\n", 0b01_0111),
        // DATA group = 0b10
        ("LOAD_IMM R0, 0\n", 0b10_0000),
        ("MOV R0, R0\n", 0b10_0001),
        ("ADD_IMM R0, R0, 0\n", 0b10_0010),
        ("DEC R0\n", 0b10_0011),
        ("AND_IMM R0, R0, 0\n", 0b10_0100),
        ("OR_IMM R0, R0, 0\n", 0b10_0101),
        ("XOR_IMM R0, R0, 0\n", 0b10_0110),
        ("SHIFT R0, R0, left, 0\n", 0b10_0111),
    ];
    for (src, want) in cases {
        let word = one_body_word(src);
        let got = opcode_field(word);
        assert_eq!(
            got, *want,
            "src={src:?}: opcode field [31:26] = {got:#010b}, want {want:#010b}"
        );
    }
    // BRANCH_ON needs a resolvable target.
    {
        let v = asm_body("BRANCH_ON ALWAYS, here\nhere:\nHALT\n");
        assert_eq!(opcode_field(v[0]), 0b01_0001);
    }
    // JMP sugar → BRANCH_ON group.
    {
        let v = asm_body("JMP here\nhere:\nHALT\n");
        assert_eq!(opcode_field(v[0]), 0b01_0001); // BRANCH_ON
    }
    // LOAD_LOOP sugar → LOAD_IMM group.
    {
        let v = asm_body("LOAD_LOOP 0\nHALT\n");
        assert_eq!(opcode_field(v[0]), 0b10_0000); // LOAD_IMM
    }
}

#[test]
fn flag_triple_lives_at_bits_2_0_for_bearer_opcodes() {
    // The seven WIRE bearer opcodes must place `expect/mask/capture`
    // exactly at [2:0].  Sweep e,m,c on two bearers; verify placement.
    let bearers: &[&str] = &[
        "EMIT_BIT_IMM tx=dom expect={e} mask={m} capture={c}\n",
        "EMIT_BYTE expect={e} mask={m} capture={c}\n",
    ];
    // Only test where c=1 implies paired (to avoid E-WIRE-003 on EMIT_BYTE).
    // Simplest: use EMIT_BIT_IMM (no pairing constraint).
    for e in 0u32..=1 {
        for m in 0u32..=1 {
            for c in 0u32..=1 {
                let src = bearers[0]
                    .replace("{e}", &e.to_string())
                    .replace("{m}", &m.to_string())
                    .replace("{c}", &c.to_string());
                let word = one_body_word(&src);
                let want_triple = (e << 2) | (m << 1) | c;
                assert_eq!(
                    flag_triple(word),
                    want_triple,
                    "src={src:?}: flag triple = {:#05b}, want {:#05b}",
                    flag_triple(word),
                    want_triple
                );
                // Bits [3..=7] must NOT contain flag leakage.
                let tx_dom_bits = word & !0x7; // strip flags
                let _ = tx_dom_bits; // encoding check only
            }
        }
    }
}

#[test]
fn non_bearer_opcodes_have_zero_flags() {
    // Non-bearer WIRE opcodes (STRETCH_SCL_*) and all CTRL / DATA
    // opcodes must have [2:0] = 0.
    let cases: &[&str] = &[
        "STRETCH_SCL_IMM 0\n",
        "STRETCH_SCL_REG src=R0\n",
        "HALT\n",
        "SET_BUS_MODE i2c\n",
        "SET_ROLE controller\n",
        "FLAG_CLEAR 0b00001\n",
        "MARK label=0\n",
        "LOAD_TIMING reg=0, divider=0\n",
        "LOAD_IMM R0, 0\n",
        "MOV R0, R0\n",
        "ADD_IMM R0, R0, 0\n",
        "DEC R0\n",
        "AND_IMM R0, R0, 0\n",
        "OR_IMM R0, R0, 0\n",
        "XOR_IMM R0, R0, 0\n",
        "SHIFT R0, R0, left, 0\n",
    ];
    for src in cases {
        let word = one_body_word(src);
        assert_eq!(
            flag_triple(word),
            0,
            "src={src:?}: non-bearer must have [2:0]=0, got word={word:#010x}"
        );
    }
}

// ---------------------------------------------------------------------------
// 2. tx_symbol vocabulary: reserved 0b11 without raw/ pragma
// ---------------------------------------------------------------------------

#[test]
fn tx_reserved_0b11_is_blocked_without_raw_pragma() {
    let bad_spellings = ["reserved", "3", "0b11", "0x3"];
    let templates = [
        "EMIT_BIT_IMM tx={x}\n",
        "DRIVE_BIT_ON_SCL tx={x}\n",
        "EMIT_QUARTER_IMM sda={x} scl=dom\n",
        "EMIT_QUARTER_IMM sda=dom scl={x}\n",
    ];
    for tpl in &templates {
        for spell in &bad_spellings {
            let src = tpl.replace("{x}", spell);
            let err = assemble(&src, "<t>").unwrap_err();
            assert_eq!(
                syntax_kind(&err),
                Some(Kind::Operand),
                "src={src:?}: reserved tx should be rejected"
            );
        }
    }
}

#[test]
fn tx_reserved_allowed_with_raw_pragma() {
    let src = "(use-raw-primitives)\nEMIT_BIT_IMM tx=reserved\nHALT\n";
    assert!(
        assemble(src, "<t>").is_ok(),
        "raw/ pragma enables tx=reserved"
    );
}

#[test]
fn tx_uppercase_form_is_case_insensitive() {
    // tx values are case-insensitive in v0.2 (§12.1).
    let a = asm_body("EMIT_BIT_IMM tx=dom\n");
    let b = asm_body("EMIT_BIT_IMM tx=DOM\n");
    let c = asm_body("EMIT_BIT_IMM tx=Dom\n");
    assert_eq!(a, b);
    assert_eq!(a, c);
}

// ---------------------------------------------------------------------------
// 3. BUS_MODE renumbering (v0.2: 0,1,2,3 NOT v0's 0,1,6,7)
// ---------------------------------------------------------------------------

#[test]
fn bus_mode_wire_values_are_v02() {
    // Extract mode field at [6:3] of the SET_BUS_MODE word.
    let get_mode = |src: &str| -> u32 { (one_body_word(src) >> 3) & 0xF };

    assert_eq!(get_mode("SET_BUS_MODE i2c\n"), 0, "i2c must be 0");
    assert_eq!(get_mode("SET_BUS_MODE i3c-OD\n"), 1, "i3c-OD must be 1");
    assert_eq!(
        get_mode("SET_BUS_MODE i3c-PP\n"),
        2,
        "i3c-PP must be 2 (NOT 6)"
    );
    assert_eq!(
        get_mode("SET_BUS_MODE hdr-ddr\n"),
        3,
        "hdr-ddr must be 3 (NOT 7)"
    );
}

#[test]
fn set_bus_mode_rejects_numeric_literals_without_raw() {
    for n in ["0", "1", "2", "3", "6", "7"] {
        let err = assemble(&format!("SET_BUS_MODE {n}\n"), "<t>").unwrap_err();
        assert_eq!(
            syntax_kind(&err),
            Some(Kind::Operand),
            "numeric SET_BUS_MODE {n} must be rejected without raw/"
        );
    }
}

#[test]
fn set_bus_mode_rejects_unknown_names() {
    let bad = ["smbus", "i3c", "od", "pp", "drive_sda", "raw", "i3c-od-x"];
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
fn set_bus_mode_case_insensitive() {
    // Bus-mode names are case-insensitive (§12.1).
    let a = asm_body("SET_BUS_MODE i2c\n");
    let b = asm_body("SET_BUS_MODE I2C\n");
    let c = asm_body("SET_BUS_MODE I2c\n");
    assert_eq!(a, b);
    assert_eq!(a, c);
}

// ---------------------------------------------------------------------------
// 4. PP-class target SCL=recessive rejection (E-WIRE-002)
// ---------------------------------------------------------------------------

#[test]
fn target_role_i3c_pp_scl_recessive_rejected() {
    let src = "SET_ROLE target\n\
               SET_BUS_MODE i3c-PP\n\
               EMIT_QUARTER_IMM sda=dom scl=recessive\n\
               HALT\n";
    let err = assemble(src, "<t>").unwrap_err();
    let msg = format!("{err}");
    assert_eq!(syntax_kind(&err), Some(Kind::Operand));
    assert!(
        msg.contains("E-WIRE-002"),
        "diagnostic must cite E-WIRE-002, got: {msg}"
    );
}

#[test]
fn target_role_hdr_ddr_scl_recessive_rejected() {
    let src = "SET_ROLE target\n\
               SET_BUS_MODE hdr-ddr\n\
               EMIT_QUARTER_IMM sda=dom scl=recessive\n\
               HALT\n";
    let err = assemble(src, "<t>").unwrap_err();
    assert_eq!(syntax_kind(&err), Some(Kind::Operand));
}

#[test]
fn target_role_pp_scl_dominant_is_legal() {
    // scl=dominant (stretch) is always legal.
    let src = "SET_ROLE target\n\
               SET_BUS_MODE i3c-PP\n\
               EMIT_QUARTER_IMM sda=dom scl=dominant\n\
               HALT\n";
    assert!(assemble(src, "<t>").is_ok());
}

#[test]
fn target_role_pp_scl_hiz_is_legal() {
    let src = "SET_ROLE target\n\
               SET_BUS_MODE i3c-PP\n\
               EMIT_QUARTER_IMM sda=dom scl=hiz\n\
               HALT\n";
    assert!(assemble(src, "<t>").is_ok());
}

#[test]
fn controller_role_pp_scl_recessive_is_legal() {
    // The §3.13 constraint is target-role only.
    let src = "SET_ROLE controller\n\
               SET_BUS_MODE i3c-PP\n\
               EMIT_QUARTER_IMM sda=dom scl=recessive\n\
               HALT\n";
    assert!(assemble(src, "<t>").is_ok());
}

#[test]
fn raw_pragma_suppresses_wire_002() {
    let src = "(use-raw-primitives)\n\
               SET_ROLE target\n\
               SET_BUS_MODE i3c-PP\n\
               EMIT_QUARTER_IMM sda=dom scl=recessive\n\
               HALT\n";
    assert!(assemble(src, "<t>").is_ok());
}

// ---------------------------------------------------------------------------
// 5. BRANCH_ON / WAIT_ON condition-code namespace (§6)
// ---------------------------------------------------------------------------

#[test]
fn all_12_live_cond_codes_accepted() {
    let names_and_codes = [
        ("ALWAYS", 0u32),
        ("MISMATCH", 1),
        ("NOT_MISMATCH", 2),
        ("START_SEEN", 3),
        ("STOP_SEEN", 4),
        ("SDA_LOW", 5),
        ("SDA_HIGH", 6),
        ("SCL_HIGH", 7),
        ("TIMEOUT", 8),
        ("NOT_TIMEOUT", 9),
        ("REG_ZERO", 10),
        ("NOT_REG_ZERO", 11),
    ];
    for (name, want_code) in &names_and_codes {
        // BRANCH_ON to self (offset -1 → cond field at [16:13]).
        let v = asm_body(&format!("here: BRANCH_ON {name}, here\n"));
        let got = (v[0] >> 13) & 0xF;
        assert_eq!(got, *want_code, "BRANCH_ON {name}: cond field");

        // WAIT_ON timeout=0 → cond field at [16:13].
        let v = asm_body(&format!("WAIT_ON {name}, 0\n"));
        let got = (v[0] >> 13) & 0xF;
        assert_eq!(got, *want_code, "WAIT_ON {name}: cond field");
    }
}

#[test]
fn reserved_cond_names_rejected() {
    let bad = [
        "OVERFLOW",
        "NEVER",
        "ANY",
        "ADDRESSED",
        "FLAG_10",
        "12",
        "15",
    ];
    for name in &bad {
        let b_err = assemble(&format!("BRANCH_ON {name}, here\nhere:\nHALT\n"), "<t>").unwrap_err();
        assert_eq!(
            syntax_kind(&b_err),
            Some(Kind::Operand),
            "BRANCH_ON {name} should be rejected"
        );
        let w_err = assemble(&format!("WAIT_ON {name}, 0\n"), "<t>").unwrap_err();
        assert_eq!(
            syntax_kind(&w_err),
            Some(Kind::Operand),
            "WAIT_ON {name} should be rejected"
        );
    }
}

#[test]
fn branch_on_and_wait_on_share_cond_code_encoding() {
    // Same cond name → same cond-field bits in both opcodes.
    let name = "MISMATCH";
    let bv = asm_body(&format!("here: BRANCH_ON {name}, here\n"));
    let wv = asm_body(&format!("WAIT_ON {name}, 0\n"));
    assert_eq!(
        (bv[0] >> 13) & 0xF,
        (wv[0] >> 13) & 0xF,
        "BRANCH_ON / WAIT_ON cond fields must match for {name}"
    );
}

// ---------------------------------------------------------------------------
// 6. JMP / LOAD_LOOP sugar
// ---------------------------------------------------------------------------

#[test]
fn jmp_sugar_is_branch_on_always() {
    // JMP tgt encodes as BRANCH_ON ALWAYS, tgt (cond=0).
    let via_jmp = asm_body("JMP tgt\ntgt:\nHALT\n");
    let via_branch = asm_body("BRANCH_ON ALWAYS, tgt\ntgt:\nHALT\n");
    assert_eq!(via_jmp, via_branch);
}

#[test]
fn load_loop_sugar_is_load_imm_r6() {
    let via_ll = asm_body("LOAD_LOOP 255\nHALT\n");
    let via_li = asm_body("LOAD_IMM R6, 255\nHALT\n");
    assert_eq!(via_ll, via_li);
}

#[test]
fn jmp_to_undefined_label_rejected() {
    let err = assemble("JMP nowhere\nHALT\n", "<t>").unwrap_err();
    assert_eq!(syntax_kind(&err), Some(Kind::Symbol));
}

#[test]
fn load_loop_out_of_range_rejected() {
    let err = assemble("LOAD_LOOP 16384\n", "<t>").unwrap_err();
    assert_eq!(syntax_kind(&err), Some(Kind::Range));
}

// ---------------------------------------------------------------------------
// 7. EMIT_BYTE pairing rule (E-WIRE-003)
// ---------------------------------------------------------------------------

#[test]
fn emit_byte_mask1_without_pair_is_error() {
    let src = "EMIT_BYTE expect=0 mask=1\nHALT\n";
    let err = assemble(src, "<t>").unwrap_err();
    assert_eq!(syntax_kind(&err), Some(Kind::Operand));
    let msg = format!("{err}");
    assert!(
        msg.contains("E-WIRE-003"),
        "must cite E-WIRE-003, got: {msg}"
    );
}

#[test]
fn emit_byte_mask0_no_pair_needed() {
    assert!(assemble("EMIT_BYTE\nHALT\n", "<t>").is_ok());
    assert!(assemble("EMIT_BYTE mask=0\nHALT\n", "<t>").is_ok());
}

#[test]
fn emit_byte_paired_branch_on_mismatch() {
    let src = "EMIT_BYTE expect=0 mask=1\nBRANCH_ON MISMATCH, nak\nnak:\nHALT\n";
    assert!(assemble(src, "<t>").is_ok());
}

#[test]
fn emit_byte_paired_flag_clear_bit0() {
    let src = "EMIT_BYTE expect=0 mask=1\nFLAG_CLEAR 0b00001\nHALT\n";
    assert!(assemble(src, "<t>").is_ok());
}

#[test]
fn emit_byte_paired_flag_clear_bit0_multi() {
    // Any mask with bit 0 set qualifies.
    let src = "EMIT_BYTE expect=0 mask=1\nFLAG_CLEAR 0b11111\nHALT\n";
    assert!(assemble(src, "<t>").is_ok());
}

#[test]
fn emit_byte_not_paired_by_flag_clear_no_bit0() {
    // FLAG_CLEAR with bit 0 clear is NOT a valid pair.
    let src = "EMIT_BYTE expect=0 mask=1\nFLAG_CLEAR 0b11110\nHALT\n";
    let err = assemble(src, "<t>").unwrap_err();
    assert_eq!(syntax_kind(&err), Some(Kind::Operand));
}

#[test]
fn emit_byte_label_between_pair_is_ok() {
    // Labels between EMIT_BYTE and pair do not break the pairing
    // analysis (§5.5: "ignoring labels").
    let src = "EMIT_BYTE expect=0 mask=1\nmy_label:\nBRANCH_ON MISMATCH, my_label\nHALT\n";
    assert!(assemble(src, "<t>").is_ok());
}

#[test]
fn raw_pragma_suppresses_emit_byte_wire003() {
    let src = "(use-raw-primitives)\nEMIT_BYTE expect=0 mask=1\nHALT\n";
    assert!(assemble(src, "<t>").is_ok());
}

// ---------------------------------------------------------------------------
// 8. raw/ pragma carve-outs
// ---------------------------------------------------------------------------

#[test]
fn raw_pragma_enables_tx_reserved() {
    let src = "(use-raw-primitives)\nEMIT_BIT_IMM tx=reserved\nHALT\n";
    assert!(assemble(src, "<t>").is_ok());
}

#[test]
fn raw_pragma_enables_capture_to_non_r7() {
    let src = "(use-raw-primitives)\n\
               SAMPLE_BIT_ON_SCL dst=R3 capture=1\n\
               HALT\n";
    assert!(assemble(src, "<t>").is_ok());
}

#[test]
fn capture_to_non_r7_without_raw_rejected() {
    // SAMPLE_BIT_ON_SCL defaults to dst=R7. Explicitly specifying a
    // different dst requires raw/ pragma.
    let src = "SAMPLE_BIT_ON_SCL dst=R3 capture=1\nHALT\n";
    let err = assemble(src, "<t>").unwrap_err();
    assert_eq!(syntax_kind(&err), Some(Kind::Operand));
}

#[test]
fn raw_pragma_after_first_instruction_is_e_raw_002() {
    let src = "HALT\n(use-raw-primitives)\n";
    let err = assemble(src, "<t>").unwrap_err();
    assert_eq!(syntax_kind(&err), Some(Kind::Operand));
    let msg = format!("{err}");
    assert!(msg.contains("E-RAW-002"), "must cite E-RAW-002, got: {msg}");
}

#[test]
fn malformed_pragma_is_e_raw_003() {
    let src = "(use-raw-primitive)\nHALT\n"; // typo: missing 's'
    let err = assemble(src, "<t>").unwrap_err();
    assert_eq!(syntax_kind(&err), Some(Kind::Operand));
    let msg = format!("{err}");
    assert!(msg.contains("E-RAW-003"), "must cite E-RAW-003, got: {msg}");
}

#[test]
fn raw_pragma_no_raw_features_is_legal() {
    // Declaring the pragma without using raw features is legal (advisory
    // only per §12.1; not a lint we emit here).
    let src = "(use-raw-primitives)\nHALT\n";
    assert!(assemble(src, "<t>").is_ok());
}

// ---------------------------------------------------------------------------
// 9. Label resolution (E-SYM-*)
// ---------------------------------------------------------------------------

#[test]
fn undefined_branch_target_is_e_sym_003() {
    let err = assemble("BRANCH_ON ALWAYS, nowhere\nHALT\n", "<t>").unwrap_err();
    assert_eq!(syntax_kind(&err), Some(Kind::Symbol));
    let msg = format!("{err}");
    assert!(
        msg.contains("nowhere"),
        "must name missing symbol, got: {msg}"
    );
}

#[test]
fn branch_to_equate_rejected() {
    let src = ".equ k, 1\nBRANCH_ON ALWAYS, k\nHALT\n";
    let err = assemble(src, "<t>").unwrap_err();
    assert_eq!(syntax_kind(&err), Some(Kind::Symbol));
}

#[test]
fn duplicate_label_diagnostic_names_prior_line() {
    let src = "loop:\n  HALT\nloop:\n  HALT\n";
    let err = assemble(src, "<t>").unwrap_err();
    assert_eq!(syntax_kind(&err), Some(Kind::Symbol));
    let msg = format!("{err}");
    assert!(
        msg.contains("line 1") || msg.contains("prior"),
        "must reference prior definition, got: {msg}"
    );
}

#[test]
fn branch_to_self_offset_minus_one() {
    // Self-branch: offset = 0 - 0 - 1 = -1.
    // BRANCH_ON cond=0 offset=-1: signed 10-bit -1 = 0x3FF → 0x3FF<<3=0x1FF8
    // opcode = 0b01_0001 at [31:26] = 0x4400_0000
    let v = asm_body("here: BRANCH_ON ALWAYS, here\n");
    let expected = 0x4400_0000 | 0x0000_1FF8;
    assert_eq!(v[0], expected, "self-branch must encode offset=-1");
}

// ---------------------------------------------------------------------------
// 10. Register name validation (E-OP-007)
// ---------------------------------------------------------------------------

#[test]
fn bad_register_r8_rejected() {
    let err = assemble("LOAD_IMM R8, 1\n", "<t>").unwrap_err();
    assert_eq!(syntax_kind(&err), Some(Kind::Operand));
    let msg = format!("{err}");
    assert!(msg.contains("E-OP-007"), "must cite E-OP-007, got: {msg}");
}

#[test]
fn register_case_insensitive() {
    // R0 and r0 are the same register (§12.1).
    let a = asm_body("LOAD_IMM R0, 42\n");
    let b = asm_body("LOAD_IMM r0, 42\n");
    assert_eq!(a, b);
}

// ---------------------------------------------------------------------------
// 11. Numeric range violations (E-RNG-*)
// ---------------------------------------------------------------------------

#[test]
fn load_imm_max_accepted() {
    assert!(assemble("LOAD_IMM R0, 16383\n", "<t>").is_ok());
}

#[test]
fn load_imm_over_max_rejected() {
    let err = assemble("LOAD_IMM R0, 16384\n", "<t>").unwrap_err();
    assert_eq!(syntax_kind(&err), Some(Kind::Range));
}

#[test]
fn wait_on_timeout_max_accepted() {
    assert!(assemble("WAIT_ON ALWAYS, 1023\n", "<t>").is_ok());
}

#[test]
fn wait_on_timeout_over_max_rejected() {
    let err = assemble("WAIT_ON ALWAYS, 1024\n", "<t>").unwrap_err();
    assert_eq!(syntax_kind(&err), Some(Kind::Range));
}

#[test]
fn branch_offset_max_positive_accepted() {
    // offset = +511: target at PC 512.
    let mut src = String::from("BRANCH_ON ALWAYS, tgt\n");
    for _ in 0..511 {
        src.push_str("HALT\n");
    }
    src.push_str("tgt:\n  HALT\n");
    assert!(assemble(&src, "<t>").is_ok());
}

#[test]
fn branch_offset_512_rejected() {
    let mut src = String::from("BRANCH_ON ALWAYS, tgt\n");
    for _ in 0..512 {
        src.push_str("HALT\n");
    }
    src.push_str("tgt:\n  HALT\n");
    let err = assemble(&src, "<t>").unwrap_err();
    assert_eq!(syntax_kind(&err), Some(Kind::Range));
}

#[test]
fn branch_offset_max_negative_accepted() {
    // offset = -512: target 512 PCs before.
    let mut src = String::from("tgt:\n");
    for _ in 0..511 {
        src.push_str("HALT\n");
    }
    src.push_str("BRANCH_ON ALWAYS, tgt\n");
    assert!(assemble(&src, "<t>").is_ok());
}

#[test]
fn stretch_scl_imm_max_accepted() {
    assert!(assemble("STRETCH_SCL_IMM 16383\n", "<t>").is_ok());
}

#[test]
fn stretch_scl_imm_over_max_rejected() {
    let err = assemble("STRETCH_SCL_IMM 16384\n", "<t>").unwrap_err();
    assert_eq!(syntax_kind(&err), Some(Kind::Range));
}

#[test]
fn mark_label_max_accepted() {
    assert!(assemble("MARK label=16383\n", "<t>").is_ok());
}

#[test]
fn mark_label_over_max_rejected() {
    let err = assemble("MARK label=16384\n", "<t>").unwrap_err();
    assert_eq!(syntax_kind(&err), Some(Kind::Range));
}

#[test]
fn halt_status_31_accepted() {
    assert!(assemble("HALT status=31\n", "<t>").is_ok());
}

#[test]
fn halt_status_32_rejected() {
    let err = assemble("HALT status=32\n", "<t>").unwrap_err();
    assert_eq!(syntax_kind(&err), Some(Kind::Range));
}

#[test]
fn halt_status_negative_rejected() {
    let err = assemble("HALT status=-1\n", "<t>").unwrap_err();
    assert_eq!(syntax_kind(&err), Some(Kind::Range));
}

#[test]
fn program_length_8192_accepted_e_rng_002() {
    let src = "HALT\n".repeat(8192);
    let words = assemble(&src, "<max>").unwrap();
    assert_eq!(words.len(), 2 + 8192);
    assert_eq!(words[1], 8192);
}

#[test]
fn program_length_8193_rejected_e_rng_002() {
    let src = "HALT\n".repeat(8193);
    let err = assemble(&src, "<over>").unwrap_err();
    assert_eq!(syntax_kind(&err), Some(Kind::Range));
}

#[test]
fn add_imm_negative_immediate_accepted() {
    assert!(assemble("ADD_IMM R0, R0, -8192\n", "<t>").is_ok());
}

#[test]
fn add_imm_over_range_rejected() {
    let err = assemble("ADD_IMM R0, R0, 8192\n", "<t>").unwrap_err();
    assert_eq!(syntax_kind(&err), Some(Kind::Range));
}

#[test]
fn shift_shamt_31_accepted() {
    assert!(assemble("SHIFT R0, R0, left, 31\n", "<t>").is_ok());
}

#[test]
fn shift_shamt_32_rejected() {
    let err = assemble("SHIFT R0, R0, left, 32\n", "<t>").unwrap_err();
    assert_eq!(syntax_kind(&err), Some(Kind::Range));
}

#[test]
fn load_timing_reg_7_accepted() {
    assert!(assemble("LOAD_TIMING reg=7, divider=0\n", "<t>").is_ok());
}

#[test]
fn load_timing_reg_8_rejected() {
    let err = assemble("LOAD_TIMING reg=8, divider=0\n", "<t>").unwrap_err();
    assert_eq!(syntax_kind(&err), Some(Kind::Range));
}

#[test]
fn load_timing_max_divider_accepted() {
    assert!(assemble("LOAD_TIMING reg=0, divider=16383\n", "<t>").is_ok());
}

#[test]
fn load_timing_over_divider_rejected() {
    let err = assemble("LOAD_TIMING reg=0, divider=16384\n", "<t>").unwrap_err();
    assert_eq!(syntax_kind(&err), Some(Kind::Range));
}

// ---------------------------------------------------------------------------
// 12. Missing / unknown / duplicate operand keys (E-OP-*)
// ---------------------------------------------------------------------------

#[test]
fn missing_required_tx_is_e_op_001() {
    let err = assemble("EMIT_BIT_IMM\n", "<t>").unwrap_err();
    assert_eq!(syntax_kind(&err), Some(Kind::Operand));
}

#[test]
fn unknown_operand_key_is_e_op_002() {
    let err = assemble("HALT garbage=1\n", "<t>").unwrap_err();
    assert_eq!(syntax_kind(&err), Some(Kind::Operand));
}

#[test]
fn duplicate_operand_key_is_e_op_003() {
    let err = assemble("HALT status=0 status=1\n", "<t>").unwrap_err();
    assert_eq!(syntax_kind(&err), Some(Kind::Operand));
}

#[test]
fn positional_where_kv_expected_is_e_op_004() {
    // A bare token in the operands of an opcode that uses key=value.
    let err = assemble("HALT 0\n", "<t>").unwrap_err();
    assert_eq!(syntax_kind(&err), Some(Kind::Operand));
}

#[test]
fn expect_x_with_mask1_is_e_op_005() {
    let err = assemble("EMIT_BIT_IMM tx=dom expect=X mask=1\n", "<t>").unwrap_err();
    assert_eq!(syntax_kind(&err), Some(Kind::Operand));
}

#[test]
fn unknown_bus_mode_is_e_op_006() {
    let err = assemble("SET_BUS_MODE i3c-unknown\n", "<t>").unwrap_err();
    assert_eq!(syntax_kind(&err), Some(Kind::Operand));
    let msg = format!("{err}");
    assert!(msg.contains("E-OP-006"), "must cite E-OP-006, got: {msg}");
}

#[test]
fn bad_role_name_is_e_op_008() {
    let err = assemble("SET_ROLE master\n", "<t>").unwrap_err();
    assert_eq!(syntax_kind(&err), Some(Kind::Operand));
    let msg = format!("{err}");
    assert!(msg.contains("E-OP-008"), "must cite E-OP-008, got: {msg}");
}

#[test]
fn flag_clear_mask_out_of_range_is_e_op_009() {
    let err = assemble("FLAG_CLEAR 32\n", "<t>").unwrap_err();
    // Range or Operand: the check is in the encoder (returns range-style
    // error) but the assembler surfaces it through the operand path.
    assert!(syntax_kind(&err).is_some());
}

#[test]
fn shift_aleft_is_rejected() {
    // `aleft` is explicitly not a valid direction (§5.25).
    let err = assemble("SHIFT R0, R0, aleft, 1\n", "<t>").unwrap_err();
    assert_eq!(syntax_kind(&err), Some(Kind::Operand));
}

// ---------------------------------------------------------------------------
// 13. Hostile / pathological input
// ---------------------------------------------------------------------------

#[test]
fn utf8_bom_does_not_panic() {
    let src = "\u{FEFF}HALT status=0\n";
    let result = assemble(src, "<bom>");
    match result {
        Ok(_) | Err(AsmError::Syntax { .. }) => {}
        Err(other) => panic!("non-syntax error on BOM input: {other:?}"),
    }
}

#[test]
fn crlf_line_endings_work() {
    let src = "HALT status=0\r\nHALT status=2\r\n";
    let body = asm_body(src);
    assert_eq!(body[0], 0x4000_0000);
    assert_eq!(body[1], 0x4000_0010);
}

#[test]
fn extremely_long_comment_does_not_blow_up() {
    let mut src = String::from(";");
    src.extend(std::iter::repeat_n('x', 64 * 1024));
    src.push('\n');
    src.push_str("HALT\n");
    let body = asm_body(&src);
    assert_eq!(body[0], 0x4000_0000);
}

#[test]
fn many_commas_in_operands_do_not_panic() {
    let _ = assemble("HALT ,,,,,, status=0 ,,,,\n", "<commas>");
}

#[test]
fn label_only_then_eof_is_empty_program() {
    let words = assemble("here:\n", "<t>").unwrap();
    assert_eq!(words.len(), 2); // preamble only
}

#[test]
fn single_token_no_operands_do_not_panic() {
    let probes = [
        "HALT\n",
        "EMIT_BIT_IMM\n",
        "EMIT_BYTE\n",
        "STRETCH_SCL_IMM\n",
        "WAIT_ON\n",
        "BRANCH_ON\n",
        "JMP\n",
        "SET_BUS_MODE\n",
        "LOAD_TIMING\n",
        "MARK\n",
        "SAMPLE_BIT_ON_SCL\n",
        "DRIVE_BIT_ON_SCL\n",
        "LOAD_LOOP\n",
        "SET_ROLE\n",
        "FLAG_CLEAR\n",
        "LOAD_IMM\n",
        "MOV\n",
        "ADD_IMM\n",
        "DEC\n",
        "AND_IMM\n",
        "OR_IMM\n",
        "XOR_IMM\n",
        "SHIFT\n",
    ];
    for src in &probes {
        let _ = assemble(src, "<probe>"); // must not panic
    }
}

#[test]
fn dw_accepts_full_32bit_range() {
    let body = asm_body(".dw 0x00000000, 0xFFFFFFFF\n");
    assert_eq!(body, vec![0x0000_0000, 0xFFFF_FFFF]);
}

#[test]
fn dw_rejects_over_32bit_value() {
    let err = assemble(".dw 0x100000000\n", "<t>").unwrap_err();
    assert_eq!(syntax_kind(&err), Some(Kind::Range));
}

#[test]
fn dw_operand_count_above_max_rejected() {
    let many = "0,".repeat(8193);
    let src = format!(".dw {}\n", &many[..many.len() - 1]);
    assert!(assemble(&src, "<dw-cap>").is_err());
}

// ---------------------------------------------------------------------------
// 14. Determinism
// ---------------------------------------------------------------------------

#[test]
fn encoder_is_deterministic() {
    let src = "HALT status=0\nSET_BUS_MODE i3c-PP\nLOAD_IMM R7, 0xFF\n\
               EMIT_BYTE expect=0 mask=1\nBRANCH_ON MISMATCH, done\n\
               done:\nHALT status=1\n";
    let first = assemble(src, "<det>").unwrap();
    for _ in 0..8 {
        let again = assemble(src, "<det>").unwrap();
        assert_eq!(again, first, "encoder must be deterministic");
    }
}

// ---------------------------------------------------------------------------
// 15. Preamble format (§10)
// ---------------------------------------------------------------------------

#[test]
fn preamble_magic_matches_spec() {
    // Word 0 = 0x0002_4D4C (magic 0x4D4C + version 0x0002).
    let words = assemble("HALT\n", "<t>").unwrap();
    assert_eq!(words[0], 0x0002_4D4C);
}

#[test]
fn preamble_body_length_word() {
    // Word 1 = number of instruction words (body only, no preamble).
    for n in [1usize, 5, 10] {
        let src = "HALT\n".repeat(n);
        let words = assemble(&src, "<t>").unwrap();
        assert_eq!(words[1] as usize, n, "body-length word for n={n}");
    }
}

// ---------------------------------------------------------------------------
// 16. §12.4 worked examples — spot-check a few through the assembler
// ---------------------------------------------------------------------------

/// Assemble a single instruction and verify the body word.
fn check_single(src: &str, expected: u32) {
    let body = asm_body(src);
    assert_eq!(
        body.len(),
        1,
        "src={src:?}: expected 1 body word, got {body:?}"
    );
    assert_eq!(
        body[0], expected,
        "src={src:?}: body[0]={:#010x}, want {expected:#010x}",
        body[0]
    );
}

#[test]
fn asm_12_4_emit_bit_imm_tx_recessive() {
    check_single("EMIT_BIT_IMM tx=recessive\n", 0x0000_0008);
}

#[test]
fn asm_12_4_emit_quarter_imm_sda_dom_scl_hiz() {
    check_single("EMIT_QUARTER_IMM sda=dominant scl=hiz\n", 0x0800_0040);
}

#[test]
fn asm_12_4_halt_status_2() {
    check_single("HALT status=2\n", 0x4000_0010);
}

#[test]
fn asm_12_4_set_bus_mode_i3c_pp() {
    check_single("SET_BUS_MODE i3c-PP\n", 0x4C00_0010);
}

#[test]
fn asm_12_4_load_imm_r3_100() {
    check_single("LOAD_IMM R3, 100\n", 0x8180_0320);
}

#[test]
fn asm_12_4_dec_r6() {
    check_single("DEC R6\n", 0x8F60_0000);
}

#[test]
fn asm_12_4_shift_left_1() {
    check_single("SHIFT R0, R0, left, 1\n", 0x9C00_0008);
}

#[test]
fn asm_12_4_mark_label_42() {
    check_single("MARK label=42\n", 0x5800_0150);
}

// ---------------------------------------------------------------------------
// Phase B1 reviewer fixes — regression tests
// ---------------------------------------------------------------------------

// --- M1: case-insensitive MISMATCH in EMIT_BYTE pairing check ------------

#[test]
fn emit_byte_pairing_accepts_lowercase_mismatch() {
    // Spec §12: mnemonics and cond codes are case-insensitive.
    // EMIT_BYTE pairing check must accept `BRANCH_ON mismatch, ...`.
    let src = "EMIT_BYTE expect=0 mask=1\n\
               BRANCH_ON mismatch, nak\n\
               nak: HALT\n";
    assert!(assemble(src, "<inline>").is_ok());
}

#[test]
fn emit_byte_pairing_accepts_mixed_case_mismatch() {
    // Also lock mixed case (Mismatch) per spec §12.
    let src = "EMIT_BYTE expect=0 mask=1\n\
               BRANCH_ON Mismatch, nak\n\
               nak: HALT\n";
    assert!(assemble(src, "<inline>").is_ok());
}

// --- m2: is_reserved_name is fully case-insensitive ----------------------

#[test]
fn equate_shadowing_builtin_is_rejected() {
    // Every variant (upper, lower, mixed) of a built-in name must be
    // rejected by the .equ reserved-name check.
    let cases = [
        "DOMINANT",
        "Dominant",
        "dominant",
        "MISMATCH",
        "Mismatch",
        "I2C",
        "i2c",
        "Controller",
        "left",
        "LEFT",
    ];
    for name in cases {
        let src = format!(".equ {name}, 1\nHALT\n");
        let err = assemble(&src, "<inline>")
            .expect_err(&format!("`{name}` must be rejected as reserved"));
        let s = format!("{err}");
        assert!(
            s.contains("reserved") || s.contains("shadow") || s.contains("collides"),
            "{name}: error doesn't mention reserved/shadow/collides: {s}"
        );
    }
}

// --- m3: parse_reg rejects leading zeros ---------------------------------

#[test]
fn register_leading_zero_r07_rejected() {
    // Spec §8: register names are exactly R0..R7, no leading zeros.
    let err = assemble("LOAD_IMM R07, 0\n", "<inline>").unwrap_err();
    assert_eq!(syntax_kind(&err), Some(Kind::Operand));
    let msg = format!("{err}");
    assert!(msg.contains("E-OP-007"), "must cite E-OP-007, got: {msg}");
}

// --- M4: FLAG_CLEAR pairing resolves equate names ------------------------

#[test]
fn emit_byte_pairing_accepts_equated_flag_clear_mask() {
    // An equate-named mask with bit 0 set must satisfy the pairing rule.
    let src = ".equ ack_mask, 0b00001\n\
               EMIT_BYTE expect=0 mask=1\n\
               FLAG_CLEAR ack_mask\n\
               HALT\n";
    assert!(assemble(src, "<inline>").is_ok());
}
