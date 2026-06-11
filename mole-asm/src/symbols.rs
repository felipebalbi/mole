//! Named symbol tables for the Mole v0.2 assembler.
//!
//! Every public-facing string the assembler accepts as a *named*
//! constant (tx symbol, bus mode, condition code, timing-register
//! alias, role, shift direction) is registered here. Mnemonic tables
//! live here too so the lexer's reserved-name check has a single
//! source of truth.
//!
//! All lookups are case-insensitive (the caller is expected to
//! lowercase the input before calling `lookup` / `lookup_ci`).
//! Short forms `dom` and `rec` are accepted as synonyms for
//! `dominant` and `recessive`.

/// `tx_symbol` vocabulary (§2, §3.11).
///
/// Encoding: `dominant=0b00`, `recessive=0b01`, `hiz=0b10`,
/// `reserved=0b11` (requires raw/ pragma).
/// Short forms `dom` and `rec` are accepted.
///
/// Stored in lower-case canonical form. Callers must lower-case
/// their input before calling `lookup`.
pub(crate) const TX_SYMBOLS: &[(&str, u8)] = &[
    ("dominant", 0b00),
    ("dom", 0b00),
    ("recessive", 0b01),
    ("rec", 0b01),
    ("hiz", 0b10),
    // NOTE: "reserved" is intentionally absent; raw/ mode adds 0b11
    // via the raw-symbol path, not via this table.
];

/// `BUS_MODE` wire values (§9, RENUMBERED from v0):
///   i2c=0, i3c-OD=1, i3c-PP=2, hdr-ddr=3
///
/// Stored in lower-case canonical form. Bus-mode names allow hyphens.
pub(crate) const BUS_MODES: &[(&str, u8)] =
    &[("i2c", 0), ("i3c-od", 1), ("i3c-pp", 2), ("hdr-ddr", 3)];

/// Condition-code namespace shared by BRANCH_ON and WAIT_ON (§6).
///
/// Codes 0–11 live; 12–15 reserved. Stored in UPPER_CASE to match
/// the canonical moleasm form; callers must upper-case their input
/// before calling `lookup`.
pub(crate) const COND_CODES: &[(&str, u8)] = &[
    ("ALWAYS", 0),
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

/// `SET_ROLE` named operands (§5.14).
pub(crate) const ROLE_NAMES: &[(&str, u8)] = &[("controller", 0), ("target", 1)];

/// SHIFT direction keywords (§5.25).
///
/// `left` → arith=0, dir=0 (encoder::SHIFT_LEFT=0)
/// `right` → arith=0, dir=1 (encoder::SHIFT_RIGHT=1)
/// `aright` → arith=1, dir=1 (encoder::SHIFT_ARIGHT=2)
/// `aleft` is NOT a valid form (arithmetic left = logical left; reject it).
pub(crate) const SHIFT_DIRS: &[(&str, u8)] = &[("left", 0), ("right", 1), ("aright", 2)];

/// Register file: R0–R7 (§8). Case-insensitive (callers lower-case).
pub(crate) fn parse_reg(tok: &str) -> Option<u8> {
    let lower = tok.to_ascii_lowercase();
    let body = lower.strip_prefix('r')?;
    // Exactly one digit: rejects R07, R007, etc. (spec §8: R0..R7 only).
    if body.len() != 1 {
        return None;
    }
    let n: u8 = body.parse().ok()?;
    if n <= 7 { Some(n) } else { None }
}

/// All live mnemonic names (UPPER-CASE canonical). Used by the lexer.
/// Includes sugar mnemonics that expand to other instructions.
pub(crate) const MNEMONICS: &[&str] = &[
    // WIRE group
    "EMIT_BIT_IMM",
    "EMIT_BIT_REG",
    "EMIT_QUARTER_IMM",
    "EMIT_QUARTER_REG",
    "EMIT_BYTE_IMM",
    "EMIT_BYTE_REG",
    // NOTE: bare "EMIT_BYTE" was sugar for EMIT_BYTE_REG in early
    // v0.2 sources. It is retired (E-LEX-006) --- the lexer
    // detects it explicitly and emits a "use _IMM or _REG" hint
    // before reaching the general "unknown mnemonic" path.
    "SAMPLE_BIT_ON_SCL",
    "DRIVE_BIT_ON_SCL",
    "STRETCH_SCL_IMM",
    "STRETCH_SCL_REG",
    // CTRL group
    "HALT",
    "BRANCH_ON",
    "WAIT_ON",
    "SET_BUS_MODE",
    "SET_ROLE",
    "FLAG_CLEAR",
    "MARK",
    "LOAD_TIMING",
    // DATA group
    "LOAD_IMM",
    "MOV",
    "ADD_IMM",
    "DEC",
    "AND_IMM",
    "OR_IMM",
    "XOR_IMM",
    "SHIFT",
    // Sugar forms (expand at parse time)
    "JMP",
    "LOAD_LOOP",
];

/// Reserved LOOP-group mnemonic trigger (§4.4, E-LEX-003).
///
/// No live opcodes exist in group=0b11. Any mnemonic in this set would
/// trigger E-LEX-003 instead of E-LEX-001. Currently empty because we
/// have no LOOP-group mnemonic names to reserve; kept as a hook for
/// future use.
#[allow(dead_code)]
pub(crate) const LOOP_GROUP_MNEMONICS: &[&str] = &[];

/// Mnemonics that existed as sugar in earlier v0.2 drafts and have
/// been retired. The lexer special-cases these *before* the general
/// `MNEMONICS.contains` check so the diagnostic can point at the
/// canonical replacement (E-LEX-006) instead of the generic
/// "unknown mnemonic" message (E-LEX-001).
///
/// Tuple is `(retired_name, replacement_hint)`. The replacement
/// hint is interpolated into the error message verbatim --- it
/// should be a short noun phrase listing the replacement(s).
pub(crate) const RETIRED_MNEMONICS: &[(&str, &str)] = &[(
    "EMIT_BYTE",
    "EMIT_BYTE_IMM (compile-time-known byte) or EMIT_BYTE_REG (R7-sourced byte)",
)];

/// True iff `name` is a reserved built-in that user-defined labels
/// and `.equ` names must not shadow.
pub(crate) fn is_reserved_name(name: &str) -> bool {
    // Pre-fold once for each case family rather than folding per table entry.
    let upper = name.to_ascii_uppercase();
    let lower = name.to_ascii_lowercase();
    // MNEMONICS and COND_CODES are stored UPPER-CASE.
    MNEMONICS.contains(&upper.as_str())
        || COND_CODES.iter().any(|(n, _)| *n == upper.as_str())
        // TX_SYMBOLS, BUS_MODES, ROLE_NAMES, SHIFT_DIRS are stored lower-case.
        || TX_SYMBOLS.iter().any(|(n, _)| *n == lower.as_str())
        || BUS_MODES.iter().any(|(n, _)| *n == lower.as_str())
        || ROLE_NAMES.iter().any(|(n, _)| *n == lower.as_str())
        || SHIFT_DIRS.iter().any(|(n, _)| *n == lower.as_str())
}

/// Case-insensitive lookup in a `(name, value)` table.
///
/// The `name` parameter must already have been lowercased by the
/// caller for tables that are stored in lowercase canonical form
/// (TX_SYMBOLS, BUS_MODES, ROLE_NAMES, SHIFT_DIRS). For tables
/// stored in UPPER-CASE (COND_CODES), callers should uppercase.
pub(crate) fn lookup(table: &[(&str, u8)], name: &str) -> Option<u8> {
    table.iter().find_map(|(n, v)| (*n == name).then_some(*v))
}

/// Collect sorted names from a table for use in error messages.
pub(crate) fn sorted_names<'a>(table: &'a [(&'a str, u8)]) -> Vec<&'a str> {
    let mut names: Vec<&'a str> = table.iter().map(|(n, _)| *n).collect();
    names.sort();
    names
}

/// Format `sorted_names(table)` as a comma-separated list for use
/// inside `(allowed: ...)` error fragments. Matches the canonical
/// hand-written form used at other E-OP-006 / E-LEX-004 / E-OP-002
/// sites (e.g. `i2c, i3c-od, i3c-pp, hdr-ddr`), so all three error
/// codes render the same way regardless of which call path produced
/// them.
pub(crate) fn sorted_names_csv(table: &[(&str, u8)]) -> String {
    sorted_names(table).join(", ")
}
