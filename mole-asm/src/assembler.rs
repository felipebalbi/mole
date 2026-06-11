//! Lexer + symbol table + two-pass assembler for Mole v0.2 moleasm.
//!
//! Source files may begin with the raw-primitives pragma:
//! `(use-raw-primitives)` — enables reserved tx symbols, capture-to-
//! non-R7, and suppresses the EMIT_BYTE pairing check.
//!
//! ## Two-pass structure
//!
//! - **Lex:** tokenise each line into a [`Statement`].
//! - **Pass 1:** walk statements in order, assign PCs, build symbol
//!   table (labels + `.equ` names).
//! - **Pass 2:** encode each statement; resolve labels as PC-relative
//!   offsets; check EMIT_BYTE pairing; build preamble.

use std::collections::HashMap;

use crate::encoder::{self, SHIFT_ARIGHT, SHIFT_LEFT, SHIFT_RIGHT};
use crate::error::{AsmError, Result, SourceLocation};
use crate::symbols::{
    self, BUS_MODES, COND_CODES, MNEMONICS, RETIRED_MNEMONICS, ROLE_NAMES, SHIFT_DIRS, TX_SYMBOLS,
};

// -----------------------------------------------------------------------
// Wire-format preamble constants (mole_abi)
// -----------------------------------------------------------------------

/// Preamble magic + version word: bytes 4C 4D 02 00 (little-endian).
/// Mirror of `mole_abi::MAGIC`. The magic u16 is 0x4D4C;
/// version u16 is 0x0002. See §10.
const PREAMBLE_MAGIC: u32 = mole_abi::MAGIC;

/// Maximum body length in 32-bit words. Mirror of
/// `mole_abi::MAX_PROGRAM_WORDS`. See §10.
const MAX_PROGRAM_WORDS: usize = mole_abi::MAX_PROGRAM_WORDS;

// -----------------------------------------------------------------------
// Statement
// -----------------------------------------------------------------------

#[derive(Debug, Clone)]
pub(crate) struct Statement {
    pub loc: SourceLocation,
    pub label: Option<String>,
    pub mnemonic: Option<String>,
    pub directive: Option<String>,
    pub operands: Vec<String>,
}

// -----------------------------------------------------------------------
// Symbol table
// -----------------------------------------------------------------------

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum SymbolKind {
    Label,
    Equate,
}

impl SymbolKind {
    fn name(self) -> &'static str {
        match self {
            SymbolKind::Label => "label",
            SymbolKind::Equate => "equate",
        }
    }
}

#[derive(Debug, Clone)]
pub(crate) struct Symbol {
    pub kind: SymbolKind,
    pub value: i64,
    pub line_no: usize,
}

#[derive(Debug, Default)]
pub(crate) struct SymbolTable {
    map: HashMap<String, Symbol>,
}

impl SymbolTable {
    fn bind(&mut self, loc: &SourceLocation, name: &str, sym: Symbol) -> Result<()> {
        if !is_valid_ident(name) {
            return Err(AsmError::symbol(
                loc,
                format!("E-SYM-001: invalid {} name: '{name}'", sym.kind.name()),
            ));
        }
        if symbols::is_reserved_name(name) {
            return Err(AsmError::symbol(
                loc,
                format!(
                    "E-SYM-005: {} name '{name}' collides with a reserved \
                     mnemonic / symbol",
                    sym.kind.name()
                ),
            ));
        }
        if let Some(prior) = self.map.get(name) {
            return Err(AsmError::symbol(
                loc,
                format!(
                    "{}: '{name}' re-defined (prior {} on line {})",
                    if sym.kind == SymbolKind::Label {
                        "E-SYM-001"
                    } else {
                        "E-SYM-002"
                    },
                    prior.kind.name(),
                    prior.line_no
                ),
            ));
        }
        self.map.insert(name.to_string(), sym);
        Ok(())
    }

    fn get(&self, name: &str) -> Option<&Symbol> {
        self.map.get(name)
    }
}

// -----------------------------------------------------------------------
// Identifier helpers
// -----------------------------------------------------------------------

/// Label pattern: `[A-Za-z_][A-Za-z0-9_]*` (no embedded dashes per §12.1).
fn is_valid_ident(s: &str) -> bool {
    let mut chars = s.chars();
    let Some(first) = chars.next() else {
        return false;
    };
    if !(first.is_ascii_alphabetic() || first == '_') {
        return false;
    }
    chars.all(|c| c.is_ascii_alphanumeric() || c == '_')
}

/// Bus-mode identifiers allow hyphens: `[A-Za-z_][A-Za-z0-9_-]*`.
fn is_valid_bus_mode_ident(s: &str) -> bool {
    let mut chars = s.chars();
    let Some(first) = chars.next() else {
        return false;
    };
    if !(first.is_ascii_alphabetic() || first == '_') {
        return false;
    }
    chars.all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
}

fn looks_like_identifier(tok: &str) -> bool {
    matches!(tok.chars().next(), Some(c) if c.is_ascii_alphabetic() || c == '_')
}

/// Try to split `body` as `LABEL:rest`. Label uses the stricter
/// no-dashes pattern.
fn try_split_label(body: &str) -> Option<(&str, &str)> {
    let colon_pos = body.find(':')?;
    let label = body[..colon_pos].trim_end();
    let rest = body[colon_pos + 1..].trim_start();
    if label.is_empty() {
        return None;
    }
    let mut chars = label.chars();
    let first = chars.next()?;
    if !(first.is_ascii_alphabetic() || first == '_') {
        return None;
    }
    if !chars.all(|c| c.is_ascii_alphanumeric() || c == '_') {
        return None;
    }
    Some((label, rest))
}

// -----------------------------------------------------------------------
// Numeric literal parser
// -----------------------------------------------------------------------

fn parse_int(tok: &str, loc: &SourceLocation) -> Result<i64> {
    if tok.is_empty() {
        return Err(AsmError::range(loc, "E-RNG-001: empty numeric literal"));
    }
    let (negative, body) = match tok.strip_prefix('-') {
        Some(rest) => (true, rest),
        None => (false, tok),
    };
    // Strip visual separators (underscores) from numeric literals so
    // `0xDEAD_BEEF` and `1_000_000` are accepted.
    let clean: String = body.chars().filter(|&c| c != '_').collect();
    let clean_body = clean.as_str();
    let parsed = if let Some(hex) = clean_body
        .strip_prefix("0x")
        .or_else(|| clean_body.strip_prefix("0X"))
    {
        i64::from_str_radix(hex, 16)
    } else if let Some(bin) = clean_body
        .strip_prefix("0b")
        .or_else(|| clean_body.strip_prefix("0B"))
    {
        i64::from_str_radix(bin, 2)
    } else {
        clean_body.parse::<i64>()
    };
    let val = parsed.map_err(|_| {
        AsmError::range(
            loc,
            format!("E-RNG-001: not a valid integer literal: '{tok}'"),
        )
    })?;
    Ok(if negative { -val } else { val })
}

fn resolve_literal_or_equate(tok: &str, syms: &SymbolTable, loc: &SourceLocation) -> Result<i64> {
    if looks_like_identifier(tok) {
        let sym = syms.get(tok).ok_or_else(|| {
            AsmError::symbol(loc, format!("E-SYM-004: undefined symbol: '{tok}'"))
        })?;
        if sym.kind != SymbolKind::Equate {
            return Err(AsmError::symbol(
                loc,
                format!(
                    "E-SYM-004: '{tok}' is a label (PC address), not a \
                     constant; expected an .equ value here"
                ),
            ));
        }
        return Ok(sym.value);
    }
    parse_int(tok, loc)
}

// -----------------------------------------------------------------------
// Lexer
// -----------------------------------------------------------------------

fn strip_comment(line: &str) -> &str {
    match line.find(';') {
        Some(idx) => &line[..idx],
        None => line,
    }
}

fn tokenize_operands(operand_region: &str) -> Vec<String> {
    let mut out = Vec::new();
    for piece in operand_region.split(',') {
        for tok in piece.split_whitespace() {
            if !tok.is_empty() {
                out.push(tok.to_string());
            }
        }
    }
    out
}

fn split_head(body: &str) -> (&str, &str) {
    let head_end = body
        .find(|c: char| c.is_ascii_whitespace())
        .unwrap_or(body.len());
    let (head, rest) = body.split_at(head_end);
    (head, rest.trim())
}

const ALLOWED_DIRECTIVES: &[&str] = &[".equ", ".dw"];

/// Raw-primitives pragma token (§12.1).
const RAW_PRAGMA: &str = "(use-raw-primitives)";

pub(crate) fn lex(source: &str, filename: &str) -> Result<(Vec<Statement>, bool)> {
    let mut statements = Vec::new();
    let mut raw_mode = false;
    let mut first_instruction_seen = false;

    for (idx, raw_line) in source.lines().enumerate() {
        let line_no = idx + 1;
        let loc = SourceLocation::new(filename, line_no);

        // Strip CRLF: `.lines()` strips `\n` but leaves `\r`.
        let trimmed_line = raw_line.trim_end_matches('\r');
        let body = strip_comment(trimmed_line).trim();

        if body.is_empty() {
            continue;
        }

        // Check for the raw-primitives pragma (§12.1).
        if body == RAW_PRAGMA {
            if first_instruction_seen {
                return Err(AsmError::operand(
                    &loc,
                    "E-RAW-002: raw pragma must be the first non-comment line",
                ));
            }
            raw_mode = true;
            continue;
        }
        // Detect a malformed pragma attempt (starts with `(` but isn't the
        // exact token).
        if body.starts_with('(') {
            return Err(AsmError::operand(
                &loc,
                format!(
                    "E-RAW-003: pragma must read exactly '{RAW_PRAGMA}'; \
                     got '{body}'"
                ),
            ));
        }

        let (label, body) = match try_split_label(body) {
            Some((lbl, rest)) => (Some(lbl.to_string()), rest),
            None => (None, body),
        };

        if body.is_empty() {
            // Label-only line.
            statements.push(Statement {
                loc,
                label,
                mnemonic: None,
                directive: None,
                operands: Vec::new(),
            });
            continue;
        }

        let (head, rest) = split_head(body);

        if head.starts_with('.') {
            let directive = head.to_ascii_lowercase();
            if !ALLOWED_DIRECTIVES.contains(&directive.as_str()) {
                return Err(AsmError::lex(
                    &loc,
                    format!(
                        "E-LEX-005: unknown directive '{directive}' \
                         (allowed: .equ, .dw)"
                    ),
                ));
            }
            first_instruction_seen = true;
            statements.push(Statement {
                loc,
                label,
                mnemonic: None,
                directive: Some(directive),
                operands: tokenize_operands(rest),
            });
            continue;
        }

        // Mnemonic: v0.2 is case-insensitive; normalise to UPPER-CASE.
        let upper = head.to_ascii_uppercase();

        // LOOP-group check (§4.4, E-LEX-003): no live opcodes in group=0b11.
        // We surface this by checking for a heuristic prefix that would
        // land in the LOOP group. Since we have no LOOP mnemonics, any
        // unrecognised mnemonic just falls through to E-LEX-001.

        // Retired mnemonic check (E-LEX-006): catches bare `EMIT_BYTE`
        // and similar sugar that existed in earlier v0.2 drafts. Runs
        // BEFORE the general MNEMONICS.contains check so the diagnostic
        // can point at the canonical replacement instead of the generic
        // "unknown mnemonic" message.
        if let Some((_, hint)) = RETIRED_MNEMONICS.iter().find(|(n, _)| *n == upper.as_str()) {
            return Err(AsmError::lex(
                &loc,
                format!(
                    "E-LEX-006: '{upper}' was retired in v0.2; \
                     use {hint}"
                ),
            ));
        }

        if !MNEMONICS.contains(&upper.as_str()) {
            return Err(AsmError::lex(
                &loc,
                format!(
                    "E-LEX-001: unknown mnemonic: '{upper}' \
                     (note: in v0.2 mnemonics are case-insensitive)"
                ),
            ));
        }

        first_instruction_seen = true;
        statements.push(Statement {
            loc,
            label,
            mnemonic: Some(upper),
            directive: None,
            operands: tokenize_operands(rest),
        });
    }

    Ok((statements, raw_mode))
}

// -----------------------------------------------------------------------
// Pass 1: build symbol table + assign PC slots
// -----------------------------------------------------------------------

fn pc_advance_for(stmt: &Statement) -> Result<usize> {
    match stmt.directive.as_deref() {
        Some(".equ") => Ok(0),
        Some(".dw") => {
            if stmt.operands.is_empty() {
                return Err(AsmError::operand(
                    &stmt.loc,
                    "E-FRM-003: .dw directive with no operands \
                     (would emit zero body words; supply at \
                     least one literal or equate)",
                ));
            }
            if stmt.operands.len() > MAX_PROGRAM_WORDS {
                return Err(AsmError::range(
                    &stmt.loc,
                    format!(
                        "E-FRM-001: .dw operand count {} exceeds \
                         program-memory budget of {} words",
                        stmt.operands.len(),
                        MAX_PROGRAM_WORDS,
                    ),
                ));
            }
            Ok(stmt.operands.len())
        }
        Some(_) => Ok(0),
        None => Ok(if stmt.mnemonic.is_some() { 1 } else { 0 }),
    }
}

pub(crate) struct Pass1Output {
    pub symbols: SymbolTable,
    pub pc_stmts: Vec<(usize, Statement)>,
}

pub(crate) fn pass1(statements: Vec<Statement>, filename: &str) -> Result<Pass1Output> {
    let mut symbols = SymbolTable::default();
    let mut pc_stmts: Vec<(usize, Statement)> = Vec::new();
    let mut pc: usize = 0;
    // Track the last statement's location for the empty-body diagnostic.
    let mut last_loc: Option<SourceLocation> = None;

    for stmt in statements {
        last_loc = Some(stmt.loc.clone());

        if let Some(label) = &stmt.label {
            let sym = Symbol {
                kind: SymbolKind::Label,
                value: pc as i64,
                line_no: stmt.loc.line,
            };
            symbols.bind(&stmt.loc, label, sym)?;
        }

        if stmt.directive.as_deref() == Some(".equ") {
            if stmt.operands.len() != 2 {
                return Err(AsmError::operand(
                    &stmt.loc,
                    "E-OP-001: .equ takes exactly two operands: NAME, VALUE",
                ));
            }
            let name = stmt.operands[0].clone();
            let value = resolve_literal_or_equate(&stmt.operands[1], &symbols, &stmt.loc)?;
            let sym = Symbol {
                kind: SymbolKind::Equate,
                value,
                line_no: stmt.loc.line,
            };
            symbols.bind(&stmt.loc, &name, sym)?;
            continue;
        }

        let advance = pc_advance_for(&stmt)?;
        let stmt_loc = stmt.loc.clone();
        if advance > 0 {
            pc_stmts.push((pc, stmt));
        }
        pc = pc
            .checked_add(advance)
            .ok_or_else(|| AsmError::range(&stmt_loc, "E-RNG-002: PC overflow"))?;
        if pc > MAX_PROGRAM_WORDS {
            return Err(AsmError::range(
                &stmt_loc,
                format!(
                    "E-RNG-002: program exceeds {MAX_PROGRAM_WORDS} \
                     instruction slots (PC overflow)"
                ),
            ));
        }
    }

    // Reject programs with no body words.  The loader (§16.4) already
    // requires at least one HALT; catching it here gives a precise
    // diagnostic at the assembler level rather than a framer rejection.
    if pc == 0 {
        let loc = last_loc.unwrap_or_else(|| SourceLocation::new(filename, 1));
        return Err(AsmError::operand(
            &loc,
            "E-FRM-003: program contains no instructions \
             (body would be zero words; the canonical \
             minimum is a single HALT)",
        ));
    }

    Ok(Pass1Output { symbols, pc_stmts })
}

// -----------------------------------------------------------------------
// Pass 2 helpers — operand parsing
// -----------------------------------------------------------------------

fn parse_kv_operands(stmt: &Statement, allowed: &[&str]) -> Result<HashMap<String, String>> {
    let mut out: HashMap<String, String> = HashMap::new();
    for tok in &stmt.operands {
        let Some((key, val)) = tok.split_once('=') else {
            return Err(AsmError::operand(
                &stmt.loc,
                format!(
                    "E-OP-004: expected key=value operand, got positional \
                     token '{tok}'"
                ),
            ));
        };
        if !allowed.contains(&key) {
            let mut sorted: Vec<&str> = allowed.to_vec();
            sorted.sort();
            return Err(AsmError::operand(
                &stmt.loc,
                format!(
                    "E-OP-002: unknown operand key '{key}' \
                     (allowed: {sorted:?})"
                ),
            ));
        }
        if out.contains_key(key) {
            return Err(AsmError::operand(
                &stmt.loc,
                format!("E-OP-003: duplicate operand key '{key}'"),
            ));
        }
        out.insert(key.to_string(), val.to_string());
    }
    Ok(out)
}

fn resolve_flag_triple(
    kv: &HashMap<String, String>,
    loc: &SourceLocation,
) -> Result<(bool, bool, bool)> {
    let expect_str = kv
        .get("expect")
        .map(|s| s.to_ascii_uppercase())
        .unwrap_or_else(|| "X".to_string());
    let mask_str = kv.get("mask").cloned().unwrap_or_else(|| "0".to_string());
    let capture_str = kv
        .get("capture")
        .cloned()
        .unwrap_or_else(|| "0".to_string());

    if !matches!(expect_str.as_str(), "0" | "1" | "X") {
        return Err(AsmError::operand(
            loc,
            format!("E-OP-005: expect must be 0|1|X, got '{expect_str}'"),
        ));
    }
    if !matches!(mask_str.as_str(), "0" | "1") {
        return Err(AsmError::operand(
            loc,
            format!("E-OP-002: mask must be 0|1, got '{mask_str}'"),
        ));
    }
    if !matches!(capture_str.as_str(), "0" | "1") {
        return Err(AsmError::operand(
            loc,
            format!("E-OP-002: capture must be 0|1, got '{capture_str}'"),
        ));
    }

    let mask = mask_str == "1";
    let capture = capture_str == "1";

    let expect = if expect_str == "X" {
        if mask {
            return Err(AsmError::operand(
                loc,
                "E-OP-005: expect=X is don't-care and cannot be combined \
                 with mask=1; set an explicit expect=0|1",
            ));
        }
        false
    } else {
        expect_str == "1"
    };

    Ok((expect, mask, capture))
}

/// Resolve a `tx=<sym>` key from a kv map.
/// Accepts case-insensitive names; short forms `dom`/`rec` accepted.
/// `raw_mode` enables `tx=reserved` (0b11).
fn resolve_tx_key(
    kv: &HashMap<String, String>,
    key: &str,
    loc: &SourceLocation,
    raw_mode: bool,
) -> Result<u8> {
    let Some(sym) = kv.get(key) else {
        return Err(AsmError::operand(
            loc,
            format!("E-OP-001: missing required operand: {key}=<tx-symbol>"),
        ));
    };
    let lower = sym.to_ascii_lowercase();
    // Try standard table first.
    if let Some(v) = symbols::lookup(TX_SYMBOLS, &lower) {
        return Ok(v);
    }
    // raw/ mode: accept "reserved" → 0b11.
    if raw_mode && (lower == "reserved" || lower == "0b11" || lower == "3") {
        return Ok(0b11);
    }
    // Numeric literals 0..3 also accepted (round-trips from disassembler).
    if let Ok(n) = sym.parse::<u8>() {
        if n <= 2 {
            return Ok(n);
        }
        if n == 3 {
            if raw_mode {
                return Ok(3);
            }
            return Err(AsmError::operand(
                loc,
                format!(
                    "E-WIRE-001: tx=reserved (0b11) requires \
                     '(use-raw-primitives)' pragma"
                ),
            ));
        }
    }
    Err(AsmError::operand(
        loc,
        format!(
            "E-OP-002: {key}='{sym}' is not a named tx symbol \
             (allowed: {})",
            symbols::sorted_names_csv(TX_SYMBOLS)
        ),
    ))
}

fn resolve_cond(tok: &str, loc: &SourceLocation) -> Result<u8> {
    let upper = tok.to_ascii_uppercase();
    symbols::lookup(COND_CODES, &upper).ok_or_else(|| {
        AsmError::operand(
            loc,
            format!(
                "E-LEX-004: cond code '{tok}' is not named \
                 (allowed: {})",
                symbols::sorted_names_csv(COND_CODES)
            ),
        )
    })
}

fn resolve_register(tok: &str, loc: &SourceLocation) -> Result<u8> {
    symbols::parse_reg(tok).ok_or_else(|| {
        AsmError::operand(
            loc,
            format!("E-OP-007: register '{tok}' is not valid; use R0..R7"),
        )
    })
}

fn resolve_branch_target(
    tok: &str,
    branch_pc: usize,
    syms: &SymbolTable,
    loc: &SourceLocation,
) -> Result<i64> {
    let offset = if looks_like_identifier(tok) {
        let sym = syms.get(tok).ok_or_else(|| {
            AsmError::symbol(loc, format!("E-SYM-003: undefined branch target: '{tok}'"))
        })?;
        if sym.kind != SymbolKind::Label {
            return Err(AsmError::symbol(
                loc,
                format!(
                    "E-SYM-003: BRANCH_ON target '{tok}' is an .equ \
                     constant, not a label"
                ),
            ));
        }
        sym.value - (branch_pc as i64) - 1
    } else {
        parse_int(tok, loc)?
    };
    if !(-512..=511).contains(&offset) {
        return Err(AsmError::range(
            loc,
            format!(
                "E-RNG-003: BRANCH_ON offset {offset} out of signed \
                 10-bit range (branch_pc={branch_pc})"
            ),
        ));
    }
    Ok(offset)
}

/// True iff `bus_mode_wire` is a push-pull class (§9, AGENTS §3.13).
/// PP classes are `i3c-pp` (wire=2) and `hdr-ddr` (wire=3).
fn bus_mode_is_pp(wire: u8) -> bool {
    matches!(wire, 2 | 3)
}

// -----------------------------------------------------------------------
// EMIT_BYTE pairing check (§5.5, E-WIRE-003)
// -----------------------------------------------------------------------

/// Opaque marker for what can pair with EMIT_BYTE mask=1.
#[derive(Debug, Clone, Copy, PartialEq)]
enum PairKind {
    BranchOnMismatch,
    FlagClearBit0,
}

/// Scan `pc_stmts` starting at index `start` for a pairing opcode,
/// skipping `.dw` directives. Returns `Some(PairKind)` if a
/// valid pairing is found before any non-directive-non-pairing instruction,
/// `None` otherwise.
fn find_emit_byte_pair(
    pc_stmts: &[(usize, Statement)],
    start: usize,
    syms: &SymbolTable,
) -> Option<PairKind> {
    for (_, stmt) in pc_stmts.iter().skip(start) {
        let Some(mne) = stmt.mnemonic.as_deref() else {
            // .dw directive: skip.
            continue;
        };
        // BRANCH_ON MISMATCH, <target> — case-insensitive per spec §12.
        if mne == "BRANCH_ON"
            && stmt
                .operands
                .first()
                .map(|s| s.eq_ignore_ascii_case("MISMATCH"))
                == Some(true)
        {
            return Some(PairKind::BranchOnMismatch);
        }
        // FLAG_CLEAR with bit 0 set in the mask operand.
        // Resolve equate names against the symbol table (M4 fix).
        if mne == "FLAG_CLEAR" {
            if let Some(mask_tok) = stmt.operands.first() {
                let resolved = if looks_like_identifier(mask_tok) {
                    syms.get(mask_tok)
                        .filter(|s| s.kind == SymbolKind::Equate)
                        .map(|s| s.value)
                } else {
                    parse_int_raw(mask_tok).ok().map(|v| v as i64)
                };
                if let Some(m) = resolved {
                    if m & 1 != 0 {
                        return Some(PairKind::FlagClearBit0);
                    }
                }
            }
        }
        // Any other instruction: unpaired.
        return None;
    }
    None
}

/// Parse an integer without a SourceLocation (used for pairing check).
fn parse_int_raw(tok: &str) -> std::result::Result<i64, ()> {
    let (negative, body) = match tok.strip_prefix('-') {
        Some(rest) => (true, rest),
        None => (false, tok),
    };
    let clean: String = body.chars().filter(|&c| c != '_').collect();
    let s = clean.as_str();
    let val = if let Some(hex) = s.strip_prefix("0x").or_else(|| s.strip_prefix("0X")) {
        i64::from_str_radix(hex, 16).map_err(|_| ())?
    } else if let Some(bin) = s.strip_prefix("0b").or_else(|| s.strip_prefix("0B")) {
        i64::from_str_radix(bin, 2).map_err(|_| ())?
    } else {
        s.parse::<i64>().map_err(|_| ())?
    };
    Ok(if negative { -val } else { val })
}

// -----------------------------------------------------------------------
// Pass 2: encode each (pc, statement) into a 32-bit word
// -----------------------------------------------------------------------

pub(crate) fn pass2(
    syms: &SymbolTable,
    pc_stmts: &[(usize, Statement)],
    raw_mode: bool,
) -> Result<Vec<u32>> {
    let mut out: Vec<u32> = Vec::with_capacity(pc_stmts.len());

    // Linear (role, bus_mode) tracker for §3.13 PP-SCL target check.
    let mut active_role: Option<bool> = None; // false=ctrl, true=tgt
    let mut active_bus_mode: Option<u8> = None;

    for (i, (pc, stmt)) in pc_stmts.iter().enumerate() {
        // --- .dw directive ---
        if stmt.directive.as_deref() == Some(".dw") {
            for tok in &stmt.operands {
                let val = resolve_literal_or_equate(tok, syms, &stmt.loc)?;
                if !(0..=0xFFFF_FFFF_u64 as i64).contains(&val) {
                    return Err(AsmError::range(
                        &stmt.loc,
                        format!("E-FRM-002: .dw value {val:#x} out of 32-bit range"),
                    ));
                }
                out.push(val as u32);
            }
            continue;
        }

        let mnemonic = stmt
            .mnemonic
            .as_deref()
            .expect("pc_stmts only contains directives or mnemonic lines");

        let word = encode_mnemonic(
            mnemonic,
            stmt,
            *pc,
            syms,
            active_role,
            active_bus_mode,
            raw_mode,
            pc_stmts,
            i,
        )?;

        // Update linear tracker.
        match mnemonic {
            "SET_ROLE" => {
                // Role bit at [3] of the encoded word.
                active_role = Some((word & (1 << 3)) != 0);
            }
            "SET_BUS_MODE" => {
                // Mode wire at [6:3]: extract 4-bit field.
                active_bus_mode = Some(((word >> 3) & 0xF) as u8);
            }
            _ => {}
        }

        out.push(word);
    }
    Ok(out)
}

#[allow(clippy::too_many_arguments)]
fn encode_mnemonic(
    m: &str,
    stmt: &Statement,
    pc: usize,
    syms: &SymbolTable,
    active_role: Option<bool>,
    active_bus_mode: Option<u8>,
    raw_mode: bool,
    pc_stmts: &[(usize, Statement)],
    stmt_idx: usize,
) -> Result<u32> {
    let loc = &stmt.loc;
    let rangify = |s: String| AsmError::range(loc, s);
    let operify = |s: String| AsmError::operand(loc, s);

    match m {
        // -----------------------------------------------------------------
        "HALT" => {
            let kv = parse_kv_operands(stmt, &["status"])?;
            let status_tok = kv.get("status").cloned().unwrap_or_else(|| "0".into());
            let status = resolve_literal_or_equate(&status_tok, syms, loc)?;
            encoder::enc_halt(status).map_err(rangify)
        }

        // -----------------------------------------------------------------
        "EMIT_BIT_IMM" => {
            let kv = parse_kv_operands(stmt, &["tx", "expect", "mask", "capture"])?;
            let tx = resolve_tx_key(&kv, "tx", loc, raw_mode)?;
            let (e, mk, c) = resolve_flag_triple(&kv, loc)?;
            encoder::enc_emit_bit_imm(tx, e, mk, c, raw_mode).map_err(operify)
        }

        // -----------------------------------------------------------------
        "EMIT_BIT_REG" => {
            let kv = parse_kv_operands(stmt, &["src", "expect", "mask", "capture"])?;
            let src_tok = kv.get("src").ok_or_else(|| {
                AsmError::operand(loc, "E-OP-001: EMIT_BIT_REG requires src=<reg>")
            })?;
            let src = resolve_register(src_tok, loc)?;
            let (e, mk, c) = resolve_flag_triple(&kv, loc)?;
            encoder::enc_emit_bit_reg(src, e, mk, c).map_err(operify)
        }

        // -----------------------------------------------------------------
        "EMIT_QUARTER_IMM" => {
            let kv = parse_kv_operands(stmt, &["sda", "scl", "expect", "mask", "capture"])?;
            let sda = resolve_tx_key(&kv, "sda", loc, raw_mode)?;
            let scl = resolve_tx_key(&kv, "scl", loc, raw_mode)?;
            let (e, mk, c) = resolve_flag_triple(&kv, loc)?;

            // §3.13 / §5.3: in target role under PP-class, scl=recessive
            // is illegal (would active-PP-drive SCL from target).
            if !raw_mode
                && active_role == Some(true)
                && active_bus_mode.is_some_and(bus_mode_is_pp)
                && scl == 0b01
            // recessive
            {
                let mode_name = match active_bus_mode {
                    Some(2) => "i3c-PP",
                    Some(3) => "hdr-ddr",
                    _ => "<pp>",
                };
                return Err(AsmError::operand(
                    loc,
                    format!(
                        "E-WIRE-002: EMIT_QUARTER scl=recessive is illegal \
                         in target role under PP-class BUS_MODE ({mode_name})"
                    ),
                ));
            }
            encoder::enc_emit_quarter_imm(sda, scl, e, mk, c, raw_mode).map_err(operify)
        }

        // -----------------------------------------------------------------
        "EMIT_QUARTER_REG" => {
            let kv = parse_kv_operands(stmt, &["src", "expect", "mask", "capture"])?;
            let src_tok = kv.get("src").ok_or_else(|| {
                AsmError::operand(loc, "E-OP-001: EMIT_QUARTER_REG requires src=<reg>")
            })?;
            let src = resolve_register(src_tok, loc)?;
            let (e, mk, c) = resolve_flag_triple(&kv, loc)?;
            encoder::enc_emit_quarter_reg(src, e, mk, c).map_err(operify)
        }

        // -----------------------------------------------------------------
        // EMIT_BYTE_REG (canonical). Bare `EMIT_BYTE` was sugar for
        // EMIT_BYTE_REG in early v0.2 drafts; it is retired in this
        // revision (E-LEX-006). The lexer catches the bare form
        // before this dispatch; this arm only handles the explicit
        // _REG form.
        "EMIT_BYTE_REG" => {
            let kv = parse_kv_operands(stmt, &["expect", "mask", "capture"])?;
            let (e, mk, c) = resolve_flag_triple(&kv, loc)?;

            // §5.5 E-WIRE-003: every EMIT_BYTE_REG with mask=1 must
            // be followed by BRANCH_ON MISMATCH or FLAG_CLEAR
            // bit-0-set. The same rule applies to EMIT_BYTE_IMM
            // (handled by its own arm below).
            if mk && !raw_mode {
                let paired = find_emit_byte_pair(pc_stmts, stmt_idx + 1, syms);
                if paired.is_none() {
                    return Err(AsmError::operand(
                        loc,
                        format!(
                            "E-WIRE-003: EMIT_BYTE_REG mask=1 at line {} \
                             must be followed by BRANCH_ON MISMATCH or \
                             FLAG_CLEAR with bit 0 set; declare \
                             '(use-raw-primitives)' to suppress",
                            loc.line
                        ),
                    ));
                }
            }
            Ok(encoder::enc_emit_byte_reg(e, mk, c))
        }

        // -----------------------------------------------------------------
        // EMIT_BYTE_IMM imm=<byte> [expect=... mask=... capture=...]
        //
        // Single-instruction byte emit with the payload encoded in
        // the instruction word (§5.5b). Does not read R7, so no
        // load-use hazard against a prior LOAD_IMM R7. The same
        // E-WIRE-003 ACK-pairing rule as EMIT_BYTE_REG applies.
        "EMIT_BYTE_IMM" => {
            let kv = parse_kv_operands(stmt, &["imm", "expect", "mask", "capture"])?;
            let imm_tok = kv.get("imm").ok_or_else(|| {
                AsmError::operand(loc, "E-OP-001: EMIT_BYTE_IMM requires imm=<byte>")
            })?;
            let imm_val = parse_int(imm_tok, loc)?;
            if !(0..=255).contains(&imm_val) {
                return Err(AsmError::range(
                    loc,
                    format!("E-RNG-001: EMIT_BYTE_IMM imm must be 0..255, got {imm_val}"),
                ));
            }
            let (e, mk, c) = resolve_flag_triple(&kv, loc)?;

            // §5.5b inherits the §5.5 E-WIRE-003 pairing rule.
            if mk && !raw_mode {
                let paired = find_emit_byte_pair(pc_stmts, stmt_idx + 1, syms);
                if paired.is_none() {
                    return Err(AsmError::operand(
                        loc,
                        format!(
                            "E-WIRE-003: EMIT_BYTE_IMM mask=1 at line {} \
                             must be followed by BRANCH_ON MISMATCH or \
                             FLAG_CLEAR with bit 0 set; declare \
                             '(use-raw-primitives)' to suppress",
                            loc.line
                        ),
                    ));
                }
            }
            Ok(encoder::enc_emit_byte_imm(imm_val as u8, e, mk, c))
        }

        // -----------------------------------------------------------------
        "SAMPLE_BIT_ON_SCL" => {
            // Allowed keys: expect, mask, capture.
            // Under raw/, also dst=Rx.
            let allowed: &[&str] = if raw_mode {
                &["dst", "expect", "mask", "capture"]
            } else {
                &["expect", "mask", "capture"]
            };
            let kv = parse_kv_operands(stmt, allowed)?;
            let (e, mk, c) = resolve_flag_triple(&kv, loc)?;
            let dst: u8 = if let Some(dst_tok) = kv.get("dst") {
                let d = resolve_register(dst_tok, loc)?;
                if d != 7 && !raw_mode {
                    return Err(AsmError::operand(
                        loc,
                        "E-REG-001: capture must write to R7; use raw/ \
                         pragma to override",
                    ));
                }
                d
            } else {
                7 // canonical: R7
            };
            encoder::enc_sample_bit_on_scl(dst, e, mk, c, raw_mode).map_err(operify)
        }

        // -----------------------------------------------------------------
        "DRIVE_BIT_ON_SCL" => {
            let allowed: &[&str] = if raw_mode {
                &["tx", "dst", "expect", "mask", "capture"]
            } else {
                &["tx", "expect", "mask", "capture"]
            };
            let kv = parse_kv_operands(stmt, allowed)?;
            let tx = resolve_tx_key(&kv, "tx", loc, raw_mode)?;
            let (e, mk, c) = resolve_flag_triple(&kv, loc)?;
            if let Some(dst_tok) = kv.get("dst") {
                if !raw_mode {
                    return Err(AsmError::operand(
                        loc,
                        "E-RAW-001: dst override on DRIVE_BIT_ON_SCL \
                         requires '(use-raw-primitives)'",
                    ));
                }
                let dst = resolve_register(dst_tok, loc)?;
                encoder::enc_drive_bit_on_scl_raw(dst, tx, e, mk, c).map_err(operify)
            } else {
                encoder::enc_drive_bit_on_scl(tx, e, mk, c, raw_mode).map_err(operify)
            }
        }

        // -----------------------------------------------------------------
        "STRETCH_SCL_IMM" => {
            if stmt.operands.len() != 1 {
                return Err(AsmError::operand(
                    loc,
                    "E-OP-001: STRETCH_SCL_IMM takes one positional \
                     operand: n_quarters",
                ));
            }
            let n = resolve_literal_or_equate(&stmt.operands[0], syms, loc)?;
            encoder::enc_stretch_scl_imm(n).map_err(rangify)
        }

        // -----------------------------------------------------------------
        "STRETCH_SCL_REG" => {
            let kv = parse_kv_operands(stmt, &["src"])?;
            let src_tok = kv.get("src").ok_or_else(|| {
                AsmError::operand(loc, "E-OP-001: STRETCH_SCL_REG requires src=<reg>")
            })?;
            let src = resolve_register(src_tok, loc)?;
            encoder::enc_stretch_scl_reg(src).map_err(operify)
        }

        // -----------------------------------------------------------------
        "BRANCH_ON" => {
            if stmt.operands.len() != 2 {
                return Err(AsmError::operand(
                    loc,
                    "E-OP-001: BRANCH_ON takes two operands: cond, target",
                ));
            }
            let cond = resolve_cond(&stmt.operands[0], loc)?;
            // Reserved cond codes 12..15 (§6, E-CTRL-001).
            if cond >= 12 && !raw_mode {
                return Err(AsmError::operand(
                    loc,
                    format!(
                        "E-CTRL-001: cond code {cond} is reserved; \
                         values 12..15 require raw/ pragma"
                    ),
                ));
            }
            let offset = resolve_branch_target(&stmt.operands[1], pc, syms, loc)?;
            encoder::enc_branch_on(cond, offset).map_err(rangify)
        }

        // -----------------------------------------------------------------
        "WAIT_ON" => {
            if stmt.operands.len() != 2 {
                return Err(AsmError::operand(
                    loc,
                    "E-OP-001: WAIT_ON takes two operands: cond, timeout",
                ));
            }
            let cond = resolve_cond(&stmt.operands[0], loc)?;
            if cond >= 12 && !raw_mode {
                return Err(AsmError::operand(
                    loc,
                    format!(
                        "E-CTRL-001: cond code {cond} is reserved; \
                         values 12..15 require raw/ pragma"
                    ),
                ));
            }
            let timeout = resolve_literal_or_equate(&stmt.operands[1], syms, loc)?;
            encoder::enc_wait_on(cond, timeout).map_err(rangify)
        }

        // -----------------------------------------------------------------
        "SET_BUS_MODE" => {
            if stmt.operands.len() != 1 {
                return Err(AsmError::operand(
                    loc,
                    "E-OP-001: SET_BUS_MODE takes one operand: <bus-mode>",
                ));
            }
            let raw_tok = &stmt.operands[0];
            let lower = raw_tok.to_ascii_lowercase();

            // Non-raw mode: only named identifiers allowed.
            if !raw_mode {
                // Reject numeric literals.
                if raw_tok
                    .chars()
                    .next()
                    .map(|c| c.is_ascii_digit())
                    .unwrap_or(false)
                {
                    return Err(AsmError::operand(
                        loc,
                        format!(
                            "E-OP-006: bus mode '{raw_tok}' is not named; \
                             use i2c, i3c-OD, i3c-PP, or hdr-ddr"
                        ),
                    ));
                }
                if !is_valid_bus_mode_ident(&lower) {
                    return Err(AsmError::operand(
                        loc,
                        format!(
                            "E-OP-006: bus mode '{raw_tok}' is not named \
                             (allowed: i2c, i3c-OD, i3c-PP, hdr-ddr)"
                        ),
                    ));
                }
                let mode_wire = symbols::lookup(BUS_MODES, &lower).ok_or_else(|| {
                    AsmError::operand(
                        loc,
                        format!(
                            "E-OP-006: bus mode '{raw_tok}' is not named \
                             (allowed: {})",
                            symbols::sorted_names_csv(BUS_MODES)
                        ),
                    )
                })?;
                encoder::enc_set_bus_mode(mode_wire).map_err(rangify)
            } else {
                // raw/ mode: accept named OR numeric literal.
                if let Some(mode_wire) = symbols::lookup(BUS_MODES, &lower) {
                    encoder::enc_set_bus_mode(mode_wire).map_err(rangify)
                } else {
                    let n = resolve_literal_or_equate(raw_tok, syms, loc)?;
                    if !(0..16).contains(&n) {
                        return Err(AsmError::range(
                            loc,
                            format!("SET_BUS_MODE mode must be 0..15, got {n}"),
                        ));
                    }
                    encoder::enc_set_bus_mode(n as u8).map_err(rangify)
                }
            }
        }

        // -----------------------------------------------------------------
        "SET_ROLE" => {
            if stmt.operands.len() != 1 {
                return Err(AsmError::operand(
                    loc,
                    "E-OP-001: SET_ROLE takes one operand: controller|target",
                ));
            }
            let tok = &stmt.operands[0];
            let lower = tok.to_ascii_lowercase();
            let role = symbols::lookup(ROLE_NAMES, &lower).ok_or_else(|| {
                AsmError::operand(
                    loc,
                    format!(
                        "E-OP-008: SET_ROLE operand must be 'controller' \
                         or 'target', got '{tok}'"
                    ),
                )
            })?;
            Ok(encoder::enc_set_role(role != 0))
        }

        // -----------------------------------------------------------------
        "FLAG_CLEAR" => {
            if stmt.operands.len() != 1 {
                return Err(AsmError::operand(
                    loc,
                    "E-OP-001: FLAG_CLEAR takes one operand: <5-bit mask>",
                ));
            }
            let mask_val = resolve_literal_or_equate(&stmt.operands[0], syms, loc)?;
            if !(0..32).contains(&mask_val) {
                return Err(AsmError::operand(
                    loc,
                    format!(
                        "E-OP-009: FLAG_CLEAR mask must be a 5-bit value \
                         (0..31), got {mask_val}"
                    ),
                ));
            }
            encoder::enc_flag_clear(mask_val as u8).map_err(operify)
        }

        // -----------------------------------------------------------------
        "MARK" => {
            let kv = parse_kv_operands(stmt, &["label"])?;
            let label_tok = kv.get("label").ok_or_else(|| {
                AsmError::operand(loc, "E-OP-001: MARK requires label=<0..16383>")
            })?;
            let label_val = resolve_literal_or_equate(label_tok, syms, loc)?;
            encoder::enc_mark(label_val).map_err(rangify)
        }

        // -----------------------------------------------------------------
        "LOAD_TIMING" => {
            // Form: LOAD_TIMING reg=N, divider=N
            // Also accepted: LOAD_TIMING N, N (positional)
            let kv = try_parse_kv_or_positional(stmt, &["reg", "divider"], 2)?;
            let reg = resolve_literal_or_equate(
                kv.get("reg").ok_or_else(|| {
                    AsmError::operand(loc, "E-OP-001: LOAD_TIMING requires reg=<0..7>")
                })?,
                syms,
                loc,
            )?;
            let divider = resolve_literal_or_equate(
                kv.get("divider").ok_or_else(|| {
                    AsmError::operand(loc, "E-OP-001: LOAD_TIMING requires divider=<0..16383>")
                })?,
                syms,
                loc,
            )?;
            encoder::enc_load_timing(reg, divider).map_err(rangify)
        }

        // -----------------------------------------------------------------
        "LOAD_IMM" => {
            // Form: LOAD_IMM Rn, imm
            if stmt.operands.len() != 2 {
                return Err(AsmError::operand(
                    loc,
                    "E-OP-001: LOAD_IMM takes two operands: Rn, imm",
                ));
            }
            let dst = resolve_register(&stmt.operands[0], loc)?;
            let imm = resolve_literal_or_equate(&stmt.operands[1], syms, loc)?;
            encoder::enc_load_imm(dst, imm).map_err(rangify)
        }

        // -----------------------------------------------------------------
        "MOV" => {
            if stmt.operands.len() != 2 {
                return Err(AsmError::operand(
                    loc,
                    "E-OP-001: MOV takes two operands: Rdst, Rsrc",
                ));
            }
            let dst = resolve_register(&stmt.operands[0], loc)?;
            let src = resolve_register(&stmt.operands[1], loc)?;
            encoder::enc_mov(dst, src).map_err(operify)
        }

        // -----------------------------------------------------------------
        "ADD_IMM" => {
            if stmt.operands.len() != 3 {
                return Err(AsmError::operand(
                    loc,
                    "E-OP-001: ADD_IMM takes three operands: Rdst, Rsrc, imm",
                ));
            }
            let dst = resolve_register(&stmt.operands[0], loc)?;
            let src = resolve_register(&stmt.operands[1], loc)?;
            let imm = resolve_literal_or_equate(&stmt.operands[2], syms, loc)?;
            encoder::enc_add_imm(dst, src, imm).map_err(rangify)
        }

        // -----------------------------------------------------------------
        "DEC" => {
            if stmt.operands.len() != 1 {
                return Err(AsmError::operand(
                    loc,
                    "E-OP-001: DEC takes one operand: Rn",
                ));
            }
            let reg = resolve_register(&stmt.operands[0], loc)?;
            encoder::enc_dec(reg).map_err(operify)
        }

        // -----------------------------------------------------------------
        "AND_IMM" => {
            if stmt.operands.len() != 3 {
                return Err(AsmError::operand(
                    loc,
                    "E-OP-001: AND_IMM takes three operands: Rdst, Rsrc, imm",
                ));
            }
            let dst = resolve_register(&stmt.operands[0], loc)?;
            let src = resolve_register(&stmt.operands[1], loc)?;
            let imm = resolve_literal_or_equate(&stmt.operands[2], syms, loc)?;
            encoder::enc_and_imm(dst, src, imm).map_err(rangify)
        }

        // -----------------------------------------------------------------
        "OR_IMM" => {
            if stmt.operands.len() != 3 {
                return Err(AsmError::operand(
                    loc,
                    "E-OP-001: OR_IMM takes three operands: Rdst, Rsrc, imm",
                ));
            }
            let dst = resolve_register(&stmt.operands[0], loc)?;
            let src = resolve_register(&stmt.operands[1], loc)?;
            let imm = resolve_literal_or_equate(&stmt.operands[2], syms, loc)?;
            encoder::enc_or_imm(dst, src, imm).map_err(rangify)
        }

        // -----------------------------------------------------------------
        "XOR_IMM" => {
            if stmt.operands.len() != 3 {
                return Err(AsmError::operand(
                    loc,
                    "E-OP-001: XOR_IMM takes three operands: Rdst, Rsrc, imm",
                ));
            }
            let dst = resolve_register(&stmt.operands[0], loc)?;
            let src = resolve_register(&stmt.operands[1], loc)?;
            let imm = resolve_literal_or_equate(&stmt.operands[2], syms, loc)?;
            encoder::enc_xor_imm(dst, src, imm).map_err(rangify)
        }

        // -----------------------------------------------------------------
        "SHIFT" => {
            // Form: SHIFT Rdst, Rsrc, direction, shamt
            if stmt.operands.len() != 4 {
                return Err(AsmError::operand(
                    loc,
                    "E-OP-001: SHIFT takes four operands: Rdst, Rsrc, \
                     direction, shamt (direction: left|right|aright)",
                ));
            }
            let dst = resolve_register(&stmt.operands[0], loc)?;
            let src = resolve_register(&stmt.operands[1], loc)?;
            let dir_tok = stmt.operands[2].to_ascii_lowercase();
            let dir_val = symbols::lookup(SHIFT_DIRS, &dir_tok).ok_or_else(|| {
                AsmError::operand(
                    loc,
                    format!(
                        "E-OP-002: unknown shift direction '{}'; \
                         use left, right, or aright (not aleft)",
                        stmt.operands[2]
                    ),
                )
            })?;
            let shamt = resolve_literal_or_equate(&stmt.operands[3], syms, loc)?;
            let shift_kind = match dir_val {
                0 => SHIFT_LEFT,
                1 => SHIFT_RIGHT,
                2 => SHIFT_ARIGHT,
                _ => unreachable!(),
            };
            encoder::enc_shift(dst, src, shift_kind, shamt).map_err(rangify)
        }

        // -----------------------------------------------------------------
        // Sugar forms
        // -----------------------------------------------------------------

        // JMP <label> sugar → BRANCH_ON ALWAYS, <label>
        "JMP" => {
            if stmt.operands.len() != 1 {
                return Err(AsmError::operand(
                    loc,
                    "E-OP-001: JMP takes one operand: <label>",
                ));
            }
            let offset = resolve_branch_target(&stmt.operands[0], pc, syms, loc)?;
            encoder::enc_branch_on(0 /* ALWAYS */, offset).map_err(rangify)
        }

        // LOAD_LOOP n sugar → LOAD_IMM R6, n
        "LOAD_LOOP" => {
            if stmt.operands.len() != 1 {
                return Err(AsmError::operand(
                    loc,
                    "E-OP-001: LOAD_LOOP takes one operand: n \
                     (sugar for LOAD_IMM R6, n)",
                ));
            }
            let imm = resolve_literal_or_equate(&stmt.operands[0], syms, loc)?;
            // Always R6 per §12.3 / §5.18.
            encoder::enc_load_imm(6, imm).map_err(rangify)
        }

        other => Err(AsmError::lex(
            loc,
            format!("E-LEX-001: unhandled mnemonic: '{other}'"),
        )),
    }
}

/// Try to parse key=value pairs from `stmt.operands`, falling back to
/// positional style where operands are mapped to `keys` in order.
///
/// Used by LOAD_TIMING which accepts both `reg=N, divider=N`
/// (keyword style) and `N, N` (positional).
fn try_parse_kv_or_positional(
    stmt: &Statement,
    keys: &[&str],
    expected_positional_count: usize,
) -> Result<HashMap<String, String>> {
    // If any operand contains '=' it's key=value style.
    let any_kv = stmt.operands.iter().any(|o| o.contains('='));
    if any_kv {
        parse_kv_operands(stmt, keys)
    } else {
        // Positional.
        if stmt.operands.len() != expected_positional_count {
            return Err(AsmError::operand(
                &stmt.loc,
                format!(
                    "E-OP-001: expected {} positional operands, got {}",
                    expected_positional_count,
                    stmt.operands.len()
                ),
            ));
        }
        let mut map = HashMap::new();
        for (k, v) in keys.iter().zip(stmt.operands.iter()) {
            map.insert(k.to_string(), v.clone());
        }
        Ok(map)
    }
}

// -----------------------------------------------------------------------
// Top-level entry point
// -----------------------------------------------------------------------

/// Assemble moleasm source into a vector of 32-bit instruction words.
///
/// The returned vector begins with the 2-word preamble (magic + length
/// word), followed by the encoded instruction stream. The body length
/// field is computed from the actual program size.
///
/// `filename` is used only for diagnostics.
pub(crate) fn assemble(source: &str, filename: &str) -> Result<Vec<u32>> {
    let (statements, raw_mode) = lex(source, filename)?;
    let Pass1Output { symbols, pc_stmts } = pass1(statements, filename)?;
    let body = pass2(&symbols, &pc_stmts, raw_mode)?;

    // Build preamble.
    // Word 0: magic 0x4D4C + version 0x0002 → 0x0002_4D4C
    // Word 1: body length in 32-bit words (body-only, preamble excluded)
    let mut program = Vec::with_capacity(2 + body.len());
    program.push(PREAMBLE_MAGIC);
    program.push(body.len() as u32);
    program.extend_from_slice(&body);
    Ok(program)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::error::Kind;

    fn err_kind(e: &AsmError) -> Option<Kind> {
        match e {
            AsmError::Syntax { kind, .. } => Some(*kind),
            _ => None,
        }
    }

    /// Assemble source and return only the body words (skip preamble).
    fn asm_body(src: &str) -> crate::error::Result<Vec<u32>> {
        let words = assemble(src, "<t>")?;
        Ok(words[2..].to_vec())
    }

    #[test]
    fn empty_source_rejected_with_e_frm_003() {
        let err = assemble("", "<t>").unwrap_err();
        assert_eq!(err_kind(&err), Some(Kind::Operand));
        assert!(
            err.to_string().contains("E-FRM-003"),
            "expected E-FRM-003 in error, got: {err}"
        );
    }

    #[test]
    fn preamble_magic_and_version() {
        let words = assemble("HALT\n", "<t>").unwrap();
        assert_eq!(words[0], 0x0002_4D4C);
        // body = 1 word
        assert_eq!(words[1], 1);
    }

    #[test]
    fn halt_zero() {
        let body = asm_body("HALT status=0\n").unwrap();
        assert_eq!(body, vec![0x4000_0000]);
    }

    #[test]
    fn halt_default_status_zero() {
        let a = asm_body("HALT\n").unwrap();
        let b = asm_body("HALT status=0\n").unwrap();
        assert_eq!(a, b);
    }

    #[test]
    fn case_insensitive_mnemonic() {
        let a = asm_body("HALT status=0\n").unwrap();
        let b = asm_body("halt status=0\n").unwrap();
        let c = asm_body("Halt status=0\n").unwrap();
        assert_eq!(a, b);
        assert_eq!(a, c);
    }

    #[test]
    fn unknown_mnemonic_rejected() {
        let err = assemble("FROBNICATE\n", "<t>").unwrap_err();
        assert_eq!(err_kind(&err), Some(Kind::Lex));
    }

    #[test]
    fn unknown_directive_rejected() {
        let err = assemble(".include foo\n", "<t>").unwrap_err();
        assert_eq!(err_kind(&err), Some(Kind::Lex));
    }

    #[test]
    fn duplicate_label_rejected() {
        let src = "loop:\n  HALT\nloop:\n  HALT\n";
        let err = assemble(src, "<t>").unwrap_err();
        assert_eq!(err_kind(&err), Some(Kind::Symbol));
    }

    #[test]
    fn duplicate_equate_rejected() {
        let src = ".equ x, 1\n.equ x, 2\n";
        let err = assemble(src, "<t>").unwrap_err();
        assert_eq!(err_kind(&err), Some(Kind::Symbol));
    }

    #[test]
    fn equate_cannot_forward_reference() {
        let src = ".equ x, y\n.equ y, 1\n";
        let err = assemble(src, "<t>").unwrap_err();
        assert_eq!(err_kind(&err), Some(Kind::Symbol));
    }

    #[test]
    fn dw_accepts_32bit_values() {
        let body = asm_body(".dw 0xDEAD_BEEF\n").unwrap();
        assert_eq!(body, vec![0xDEAD_BEEF]);
    }

    #[test]
    fn dw_rejects_label_operand() {
        let src = "tgt:\n  .dw tgt\n";
        let err = assemble(src, "<t>").unwrap_err();
        assert_eq!(err_kind(&err), Some(Kind::Symbol));
    }

    #[test]
    fn dw_accepts_equate_operand() {
        let src = ".equ x, 0x12345678\n  .dw x\n";
        let body = asm_body(src).unwrap();
        assert_eq!(body, vec![0x1234_5678]);
    }

    #[test]
    fn reserved_name_for_equate_rejected() {
        let src = ".equ HALT, 1\n";
        let err = assemble(src, "<t>").unwrap_err();
        assert_eq!(err_kind(&err), Some(Kind::Symbol));
    }

    #[test]
    fn branch_offset_510_accepted() {
        // BRANCH_ON at PC 0, target at PC 511 → offset = 510 (< 511 max).
        let mut src = String::from("BRANCH_ON ALWAYS, tgt\n");
        for _ in 0..510 {
            src.push_str("HALT\n");
        }
        src.push_str("tgt:\n  HALT\n");
        assert!(assemble(&src, "<t>").is_ok());
    }

    #[test]
    fn branch_offset_512_rejected() {
        // BRANCH_ON at PC 0, target at PC 513 → offset = 512 (exceeds 511).
        let mut src = String::from("BRANCH_ON ALWAYS, tgt\n");
        for _ in 0..512 {
            src.push_str("HALT\n");
        }
        src.push_str("tgt:\n  HALT\n");
        let err = assemble(&src, "<t>").unwrap_err();
        assert_eq!(err_kind(&err), Some(Kind::Range));
    }

    #[test]
    fn expect_x_with_mask_one_rejected() {
        let src = "EMIT_BIT_IMM tx=hiz expect=X mask=1\n";
        let err = assemble(src, "<t>").unwrap_err();
        assert_eq!(err_kind(&err), Some(Kind::Operand));
    }

    #[test]
    fn jmp_sugar_encodes_as_branch_on_always() {
        // JMP tgt → BRANCH_ON ALWAYS, tgt. cond=0 (ALWAYS).
        let src = "JMP tgt\ntgt:\nHALT\n";
        let body = asm_body(src).unwrap();
        // cond=ALWAYS=0, offset=0 → 0x4400_0000 | (0<<13) | (0<<3) = 0x4400_0000
        // Wait: BRANCH_ON is group=01 sub=0001 → 0x4400_0000
        // offset=0: 0x4400_0000 | 0 = 0x4400_0000
        assert_eq!(body[0], 0x4400_0000);
    }

    #[test]
    fn load_loop_sugar_encodes_as_load_imm_r6() {
        let a = asm_body("LOAD_LOOP 10\n").unwrap();
        let b = asm_body("LOAD_IMM R6, 10\n").unwrap();
        assert_eq!(a, b);
    }

    #[test]
    fn dom_and_dominant_encode_identically() {
        let a = asm_body("EMIT_BIT_IMM tx=dom\n").unwrap();
        let b = asm_body("EMIT_BIT_IMM tx=dominant\n").unwrap();
        assert_eq!(a, b);
    }

    #[test]
    fn rec_and_recessive_encode_identically() {
        let a = asm_body("EMIT_BIT_IMM tx=rec\n").unwrap();
        let b = asm_body("EMIT_BIT_IMM tx=recessive\n").unwrap();
        assert_eq!(a, b);
    }

    #[test]
    fn set_bus_mode_renumbering() {
        // v0.2: i2c=0, i3c-OD=1, i3c-PP=2, hdr-ddr=3 (NOT v0's 0,1,6,7)
        let i2c = asm_body("SET_BUS_MODE i2c\n").unwrap();
        let i3c_od = asm_body("SET_BUS_MODE i3c-OD\n").unwrap();
        let i3c_pp = asm_body("SET_BUS_MODE i3c-PP\n").unwrap();
        let hdr_ddr = asm_body("SET_BUS_MODE hdr-ddr\n").unwrap();
        // mode at [6:3]: i2c=0, i3c-OD=1<<3=8, i3c-PP=2<<3=0x10, hdr-ddr=3<<3=0x18
        assert_eq!(i2c[0] & 0x78, 0x00); // mode=0
        assert_eq!(i3c_od[0] & 0x78, 0x08); // mode=1
        assert_eq!(i3c_pp[0] & 0x78, 0x10); // mode=2
        assert_eq!(hdr_ddr[0] & 0x78, 0x18); // mode=3
    }

    #[test]
    fn emit_byte_pairing_required_with_mask_1() {
        // EMIT_BYTE_REG mask=1 without BRANCH_ON MISMATCH or FLAG_CLEAR → E-WIRE-003
        let src = "EMIT_BYTE_REG expect=0 mask=1\nHALT\n";
        let err = assemble(src, "<t>").unwrap_err();
        assert_eq!(err_kind(&err), Some(Kind::Operand));
    }

    #[test]
    fn emit_byte_mask_0_no_pairing_required() {
        // EMIT_BYTE_REG mask=0 is exempt.
        let src = "EMIT_BYTE_REG\nHALT\n";
        assert!(assemble(src, "<t>").is_ok());
    }

    #[test]
    fn emit_byte_paired_with_branch_on_mismatch() {
        let src = "EMIT_BYTE_REG expect=0 mask=1\nBRANCH_ON MISMATCH, nak\nnak:\nHALT\n";
        assert!(assemble(src, "<t>").is_ok());
    }

    #[test]
    fn emit_byte_paired_with_flag_clear() {
        let src = "EMIT_BYTE_REG expect=0 mask=1\nFLAG_CLEAR 0b00001\nHALT\n";
        assert!(assemble(src, "<t>").is_ok());
    }

    #[test]
    fn raw_pragma_suppresses_emit_byte_pairing_check() {
        let src = "(use-raw-primitives)\nEMIT_BYTE_REG expect=0 mask=1\nHALT\n";
        assert!(assemble(src, "<t>").is_ok());
    }

    #[test]
    fn bare_emit_byte_is_retired_in_v0_2() {
        // E-LEX-006: bare `EMIT_BYTE` was sugar for EMIT_BYTE_REG in
        // early v0.2 drafts; this revision retires it. The diagnostic
        // must point at the canonical replacement (both _IMM and
        // _REG) rather than the generic "unknown mnemonic" message.
        let src = "EMIT_BYTE\nHALT\n";
        let err = assemble(src, "<t>").unwrap_err();
        assert_eq!(err_kind(&err), Some(Kind::Lex));
        let msg = format!("{err}");
        assert!(
            msg.contains("E-LEX-006"),
            "expected E-LEX-006 in diagnostic, got: {msg}"
        );
        assert!(
            msg.contains("EMIT_BYTE_IMM") && msg.contains("EMIT_BYTE_REG"),
            "expected both canonical replacements in diagnostic, got: {msg}"
        );
    }

    #[test]
    fn raw_pragma_after_instruction_rejected() {
        let src = "HALT\n(use-raw-primitives)\n";
        let err = assemble(src, "<t>").unwrap_err();
        assert_eq!(err_kind(&err), Some(Kind::Operand));
    }

    #[test]
    fn target_role_pp_scl_recessive_rejected() {
        let src = "SET_ROLE target\nSET_BUS_MODE i3c-PP\n\
                   EMIT_QUARTER_IMM sda=dom scl=recessive\nHALT\n";
        let err = assemble(src, "<t>").unwrap_err();
        assert_eq!(err_kind(&err), Some(Kind::Operand));
    }

    #[test]
    fn whitespace_only_source_rejected_with_e_frm_003() {
        let src = "\n\n   \n\t\n;just a comment\n";
        let err = assemble(src, "<t>").unwrap_err();
        assert_eq!(err_kind(&err), Some(Kind::Operand));
        assert!(
            err.to_string().contains("E-FRM-003"),
            "expected E-FRM-003 in error, got: {err}"
        );
    }

    #[test]
    fn program_length_8192_accepted() {
        let mut src = String::with_capacity(8192 * 6);
        for _ in 0..8192 {
            src.push_str("HALT\n");
        }
        let words = assemble(&src, "<max-prog>").unwrap();
        // 2 preamble + 8192 body
        assert_eq!(words.len(), 2 + 8192);
        assert_eq!(words[1], 8192); // body length word
    }

    #[test]
    fn program_length_8193_rejected() {
        let mut src = String::with_capacity(8193 * 6);
        for _ in 0..8193 {
            src.push_str("HALT\n");
        }
        let err = assemble(&src, "<over-prog>").unwrap_err();
        assert_eq!(err_kind(&err), Some(Kind::Range));
    }

    #[test]
    fn crlf_line_endings_work() {
        let src = "HALT status=0\r\nHALT status=2\r\n";
        let body = asm_body(src).unwrap();
        assert_eq!(body[0], 0x4000_0000);
        assert_eq!(body[1], 0x4000_0010);
    }
}
