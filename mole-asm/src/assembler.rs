//! Lexer + symbol table + two-pass assembler.
//!
//! Mirrors the reference Python at `tests/fixtures/mole-asm.py`. Each
//! source line becomes a [`Statement`], pass 1 assigns PCs and builds
//! the symbol table, pass 2 encodes each statement into a 16-bit word.
//!
//! Reading order matches the Python: lexer first, then pass 1, then
//! the per-opcode parsers in pass 2.

use std::collections::HashMap;

use crate::encoder::{self};
use crate::error::{AsmError, Result, SourceLocation};
use crate::symbols::{
    self, BUS_MODES, COND_CODES, MNEMONICS, RESERVED_V05_MNEMONICS, TIMING_REG_ALIASES, TX_SYMBOLS,
};

// ---------------------------------------------------------------------------
// Statement
// ---------------------------------------------------------------------------

#[derive(Debug, Clone)]
pub(crate) struct Statement {
    pub loc: SourceLocation,
    pub label: Option<String>,
    pub mnemonic: Option<String>,
    pub directive: Option<String>,
    pub operands: Vec<String>,
}

// ---------------------------------------------------------------------------
// Symbol table
// ---------------------------------------------------------------------------

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
                format!("invalid {} name: '{name}'", sym.kind.name()),
            ));
        }
        if symbols::is_reserved_name(name) {
            return Err(AsmError::symbol(
                loc,
                format!(
                    "{} name '{name}' collides with a reserved mnemonic / \
                     symbol / alias",
                    sym.kind.name()
                ),
            ));
        }
        if let Some(prior) = self.map.get(name) {
            return Err(AsmError::symbol(
                loc,
                format!(
                    "{} '{name}' re-defined (prior {} on line {})",
                    sym.kind.name(),
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

// ---------------------------------------------------------------------------
// Identifier helpers (hand-rolled to avoid pulling in the regex crate)
// ---------------------------------------------------------------------------

/// `[A-Za-z_][A-Za-z0-9_-]*` --- the Python `_IDENT_RE`. Used by the
/// symbol-table binder so user names allow embedded dashes (matches
/// the named bus modes like `i3c-od`).
fn is_valid_ident(s: &str) -> bool {
    let mut chars = s.chars();
    let Some(first) = chars.next() else {
        return false;
    };
    if !(first.is_ascii_alphabetic() || first == '_') {
        return false;
    }
    chars.all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
}

/// Cheap "this token looks like an identifier reference" test used by
/// the literal-or-equate resolvers. Matches the (deliberate) Python
/// behaviour: any token starting with an ASCII letter or underscore is
/// resolved through the symbol table; anything else falls through to
/// numeric parsing.
fn looks_like_identifier(tok: &str) -> bool {
    matches!(tok.chars().next(), Some(c) if c.is_ascii_alphabetic() || c == '_')
}

/// Try to split `body` as `LABEL:rest`. Label pattern is the stricter
/// `[A-Za-z_][A-Za-z0-9_]*` --- no dashes --- matching the Python
/// `_LABEL_DEF_RE`. Whitespace is allowed around the `:`.
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

// ---------------------------------------------------------------------------
// Numeric literal parser
// ---------------------------------------------------------------------------

fn parse_int(tok: &str, loc: &SourceLocation) -> Result<i64> {
    if tok.is_empty() {
        return Err(AsmError::range(loc, "empty numeric literal"));
    }
    let (negative, body) = match tok.strip_prefix('-') {
        Some(rest) => (true, rest),
        None => (false, tok),
    };
    let parsed = if let Some(hex) = body.strip_prefix("0x").or_else(|| body.strip_prefix("0X")) {
        i64::from_str_radix(hex, 16)
    } else if let Some(bin) = body.strip_prefix("0b").or_else(|| body.strip_prefix("0B")) {
        i64::from_str_radix(bin, 2)
    } else {
        body.parse::<i64>()
    };
    let val = parsed
        .map_err(|_| AsmError::range(loc, format!("not a valid integer literal: '{tok}'")))?;
    Ok(if negative { -val } else { val })
}

fn resolve_literal_or_equate(tok: &str, syms: &SymbolTable, loc: &SourceLocation) -> Result<i64> {
    if looks_like_identifier(tok) {
        let sym = syms
            .get(tok)
            .ok_or_else(|| AsmError::symbol(loc, format!("undefined symbol: '{tok}'")))?;
        if sym.kind != SymbolKind::Equate {
            return Err(AsmError::symbol(
                loc,
                format!(
                    "'{tok}' is a label (PC address), not a constant; \
                     expected an .equ value here"
                ),
            ));
        }
        return Ok(sym.value);
    }
    parse_int(tok, loc)
}

// ---------------------------------------------------------------------------
// Lexer
// ---------------------------------------------------------------------------

fn strip_comment(line: &str) -> &str {
    match line.find(';') {
        Some(idx) => &line[..idx],
        None => line,
    }
}

/// Split the operand region on commas, then split each comma-piece on
/// whitespace. Yields a flat list of tokens with empties filtered.
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

/// Split `body` on the first run of ASCII whitespace into `(head, rest)`.
/// `rest` is trimmed; `head` is the longest non-whitespace prefix.
fn split_head(body: &str) -> (&str, &str) {
    let head_end = body
        .find(|c: char| c.is_ascii_whitespace())
        .unwrap_or(body.len());
    let (head, rest) = body.split_at(head_end);
    (head, rest.trim())
}

const ALLOWED_DIRECTIVES: &[&str] = &[".equ", ".dw"];

pub(crate) fn lex(source: &str, filename: &str) -> Result<Vec<Statement>> {
    let mut statements = Vec::new();
    for (idx, raw) in source.lines().enumerate() {
        let line_no = idx + 1;
        let loc = SourceLocation::new(filename, line_no);

        let body = strip_comment(raw).trim();
        if body.is_empty() {
            continue;
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
                        "unknown directive '{directive}' \
                         (allowed: .equ, .dw)"
                    ),
                ));
            }
            statements.push(Statement {
                loc,
                label,
                mnemonic: None,
                directive: Some(directive),
                operands: tokenize_operands(rest),
            });
            continue;
        }

        if head != head.to_ascii_uppercase() {
            return Err(AsmError::lex(
                &loc,
                format!("mnemonic must be UPPER CASE: got '{head}'"),
            ));
        }
        if RESERVED_V05_MNEMONICS.contains(&head) {
            return Err(AsmError::lex(
                &loc,
                format!(
                    "{head} is a reserved-v0.5 opcode; use `.dw` to inject \
                     the raw word if you really mean it"
                ),
            ));
        }
        if !MNEMONICS.contains(&head) {
            return Err(AsmError::lex(&loc, format!("unknown mnemonic: '{head}'")));
        }

        statements.push(Statement {
            loc,
            label,
            mnemonic: Some(head.to_string()),
            directive: None,
            operands: tokenize_operands(rest),
        });
    }
    Ok(statements)
}

// ---------------------------------------------------------------------------
// Pass 1: build symbol table + assign PC slots
// ---------------------------------------------------------------------------

fn pc_advance_for(stmt: &Statement) -> u16 {
    match stmt.directive.as_deref() {
        Some(".equ") => 0,
        Some(".dw") => stmt.operands.len() as u16,
        Some(_) => 0, // ALLOWED_DIRECTIVES gate keeps us here only for .equ/.dw
        None => {
            if stmt.mnemonic.is_some() {
                1
            } else {
                0 // label-only line
            }
        }
    }
}

pub(crate) struct Pass1Output {
    pub symbols: SymbolTable,
    pub pc_stmts: Vec<(u16, Statement)>,
}

pub(crate) fn pass1(statements: Vec<Statement>, _filename: &str) -> Result<Pass1Output> {
    let mut symbols = SymbolTable::default();
    let mut pc_stmts: Vec<(u16, Statement)> = Vec::new();
    let mut pc: u16 = 0;

    for stmt in statements {
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
                    ".equ takes exactly two operands: NAME, VALUE",
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
            continue; // .equ consumes no PC slots
        }

        let advance = pc_advance_for(&stmt);
        let stmt_loc = stmt.loc.clone();
        if advance > 0 {
            pc_stmts.push((pc, stmt));
        }
        pc = pc
            .checked_add(advance)
            .ok_or_else(|| AsmError::range(&stmt_loc, "PC overflow"))?;
        if pc > (1 << 12) {
            return Err(AsmError::range(
                &stmt_loc,
                "program exceeds 4096 instruction slots (PC overflow)",
            ));
        }
    }

    Ok(Pass1Output { symbols, pc_stmts })
}

// ---------------------------------------------------------------------------
// Pass 2 helpers --- per-opcode operand parsing
// ---------------------------------------------------------------------------

fn parse_kv_operands(stmt: &Statement, allowed: &[&str]) -> Result<HashMap<String, String>> {
    let mut out: HashMap<String, String> = HashMap::new();
    for tok in &stmt.operands {
        let Some((key, val)) = tok.split_once('=') else {
            return Err(AsmError::operand(
                &stmt.loc,
                format!("expected key=value operand, got positional token '{tok}'"),
            ));
        };
        if !allowed.contains(&key) {
            let mut sorted: Vec<&str> = allowed.to_vec();
            sorted.sort();
            return Err(AsmError::operand(
                &stmt.loc,
                format!("unknown operand key '{key}' (allowed: {sorted:?})"),
            ));
        }
        if out.contains_key(key) {
            return Err(AsmError::operand(
                &stmt.loc,
                format!("duplicate operand key '{key}'"),
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
            format!("expect must be 0|1|X, got '{expect_str}'"),
        ));
    }
    if !matches!(mask_str.as_str(), "0" | "1") {
        return Err(AsmError::operand(
            loc,
            format!("mask must be 0|1, got '{mask_str}'"),
        ));
    }
    if !matches!(capture_str.as_str(), "0" | "1") {
        return Err(AsmError::operand(
            loc,
            format!("capture must be 0|1, got '{capture_str}'"),
        ));
    }

    let mask = mask_str == "1";
    let capture = capture_str == "1";

    let expect = if expect_str == "X" {
        if mask {
            return Err(AsmError::operand(
                loc,
                "expect=X is don't-care and cannot be combined with mask=1; \
                 set an explicit expect=0|1 if you want to compare",
            ));
        }
        false
    } else {
        expect_str == "1"
    };

    Ok((expect, mask, capture))
}

fn resolve_tx_key(kv: &HashMap<String, String>, key: &str, loc: &SourceLocation) -> Result<u8> {
    let Some(sym) = kv.get(key) else {
        return Err(AsmError::operand(
            loc,
            format!("missing required operand: {key}=<symbol>"),
        ));
    };
    symbols::lookup(TX_SYMBOLS, sym).ok_or_else(|| {
        AsmError::operand(
            loc,
            format!(
                "{key}='{sym}' is not a named tx symbol (allowed: {:?})",
                symbols::sorted_names(TX_SYMBOLS)
            ),
        )
    })
}

fn resolve_cond(tok: &str, loc: &SourceLocation) -> Result<u8> {
    symbols::lookup(COND_CODES, tok).ok_or_else(|| {
        AsmError::operand(
            loc,
            format!(
                "cond code '{tok}' is not named (allowed: {:?})",
                symbols::sorted_names(COND_CODES)
            ),
        )
    })
}

fn resolve_branch_target(
    tok: &str,
    branch_pc: u16,
    syms: &SymbolTable,
    loc: &SourceLocation,
) -> Result<i64> {
    let offset = if looks_like_identifier(tok) {
        let sym = syms
            .get(tok)
            .ok_or_else(|| AsmError::symbol(loc, format!("undefined branch target: '{tok}'")))?;
        if sym.kind != SymbolKind::Label {
            return Err(AsmError::symbol(
                loc,
                format!("BRANCH_ON target '{tok}' is an .equ constant, not a label"),
            ));
        }
        sym.value - (branch_pc as i64) - 1
    } else {
        parse_int(tok, loc)?
    };
    if !(-128..=127).contains(&offset) {
        return Err(AsmError::range(
            loc,
            format!(
                "BRANCH_ON offset {offset} out of signed 8-bit range \
                 (branch_pc={branch_pc})"
            ),
        ));
    }
    Ok(offset)
}

fn resolve_jmp_target(tok: &str, syms: &SymbolTable, loc: &SourceLocation) -> Result<i64> {
    if looks_like_identifier(tok) {
        let sym = syms
            .get(tok)
            .ok_or_else(|| AsmError::symbol(loc, format!("undefined jump target: '{tok}'")))?;
        if sym.kind != SymbolKind::Label {
            return Err(AsmError::symbol(
                loc,
                format!(
                    "JMP target '{tok}' is an .equ constant, not a label \
                     (use a numeric literal if you really want an .equ as addr)"
                ),
            ));
        }
        return Ok(sym.value);
    }
    parse_int(tok, loc)
}

// ---------------------------------------------------------------------------
// Pass 2: encode each (pc, statement) into a 16-bit word
// ---------------------------------------------------------------------------

pub(crate) fn pass2(syms: &SymbolTable, pc_stmts: &[(u16, Statement)]) -> Result<Vec<u16>> {
    let mut out = Vec::with_capacity(pc_stmts.len());
    for (pc, stmt) in pc_stmts {
        if stmt.directive.as_deref() == Some(".dw") {
            for tok in &stmt.operands {
                let val = resolve_literal_or_equate(tok, syms, &stmt.loc)?;
                if !(0..=0xFFFF).contains(&val) {
                    return Err(AsmError::range(
                        &stmt.loc,
                        format!(".dw value {val:#x} out of 16-bit range"),
                    ));
                }
                out.push((val & 0xFFFF) as u16);
            }
            continue;
        }
        let mnemonic = stmt
            .mnemonic
            .as_deref()
            .expect("pc_stmts only contains directives or mnemonic-bearing lines");
        let word = encode_mnemonic(mnemonic, stmt, *pc, syms)?;
        out.push(word);
    }
    Ok(out)
}

fn encode_mnemonic(m: &str, stmt: &Statement, pc: u16, syms: &SymbolTable) -> Result<u16> {
    let loc = &stmt.loc;
    let rangify = |s: String| AsmError::range(loc, s);
    match m {
        "HALT" => {
            let kv = parse_kv_operands(stmt, &["status"])?;
            let status_tok = kv.get("status").cloned().unwrap_or_else(|| "0".into());
            let status = resolve_literal_or_equate(&status_tok, syms, loc)?;
            encoder::enc_halt(status).map_err(rangify)
        }
        "EMIT_BIT" => {
            let kv = parse_kv_operands(stmt, &["tx", "expect", "mask", "capture"])?;
            let tx = resolve_tx_key(&kv, "tx", loc)?;
            let (e, mk, c) = resolve_flag_triple(&kv, loc)?;
            encoder::enc_emit_bit(tx, e, mk, c).map_err(rangify)
        }
        "EMIT_QUARTER" => {
            let kv = parse_kv_operands(stmt, &["sda", "scl", "expect", "mask", "capture"])?;
            let sda = resolve_tx_key(&kv, "sda", loc)?;
            let scl = resolve_tx_key(&kv, "scl", loc)?;
            let (e, mk, c) = resolve_flag_triple(&kv, loc)?;
            encoder::enc_emit_quarter(sda, scl, e, mk, c).map_err(rangify)
        }
        "SAMPLE_BIT_ON_SCL" => {
            let kv = parse_kv_operands(stmt, &["expect", "mask", "capture"])?;
            let (e, mk, c) = resolve_flag_triple(&kv, loc)?;
            Ok(encoder::enc_sample_bit(e, mk, c))
        }
        "DRIVE_BIT_ON_SCL" => {
            let kv = parse_kv_operands(stmt, &["tx", "expect", "mask", "capture"])?;
            let tx = resolve_tx_key(&kv, "tx", loc)?;
            let (e, mk, c) = resolve_flag_triple(&kv, loc)?;
            encoder::enc_drive_bit(tx, e, mk, c).map_err(rangify)
        }
        "MARK" => {
            let kv = parse_kv_operands(stmt, &["label"])?;
            let Some(label_tok) = kv.get("label") else {
                return Err(AsmError::operand(loc, "MARK requires label=<0..255>"));
            };
            let label_val = resolve_literal_or_equate(label_tok, syms, loc)?;
            encoder::enc_mark(label_val).map_err(rangify)
        }
        "STRETCH_SCL" => {
            if stmt.operands.len() != 1 {
                return Err(AsmError::operand(
                    loc,
                    "STRETCH_SCL takes one positional operand: n_quarters",
                ));
            }
            let n = resolve_literal_or_equate(&stmt.operands[0], syms, loc)?;
            encoder::enc_stretch_scl(n).map_err(rangify)
        }
        "SET_BUS_MODE" => {
            if stmt.operands.len() != 1 {
                return Err(AsmError::operand(
                    loc,
                    "SET_BUS_MODE takes one positional operand: <bus-mode>",
                ));
            }
            let raw = &stmt.operands[0];
            let lower = raw.to_ascii_lowercase();
            let mode_wire = symbols::lookup(BUS_MODES, &lower).ok_or_else(|| {
                AsmError::operand(
                    loc,
                    format!(
                        "bus mode '{raw}' is not named (allowed: {:?})",
                        symbols::sorted_names(BUS_MODES)
                    ),
                )
            })?;
            encoder::enc_set_bus_mode(mode_wire).map_err(rangify)
        }
        "LOAD_TIMING" => {
            if stmt.operands.len() != 2 {
                return Err(AsmError::operand(
                    loc,
                    "LOAD_TIMING takes two positional operands: reg, divider_word",
                ));
            }
            let reg_tok = &stmt.operands[0];
            let word_tok = &stmt.operands[1];
            let reg = if let Some(v) = symbols::lookup(TIMING_REG_ALIASES, reg_tok) {
                v as i64
            } else {
                resolve_literal_or_equate(reg_tok, syms, loc)?
            };
            let word_val = resolve_literal_or_equate(word_tok, syms, loc)?;
            encoder::enc_load_timing(reg, word_val).map_err(rangify)
        }
        "WAIT_ON" => {
            if stmt.operands.len() != 2 {
                return Err(AsmError::operand(
                    loc,
                    "WAIT_ON takes two positional operands: cond, timeout",
                ));
            }
            let cond = resolve_cond(&stmt.operands[0], loc)?;
            let timeout = resolve_literal_or_equate(&stmt.operands[1], syms, loc)?;
            encoder::enc_wait_on(cond, timeout).map_err(rangify)
        }
        "BRANCH_ON" => {
            if stmt.operands.len() != 2 {
                return Err(AsmError::operand(
                    loc,
                    "BRANCH_ON takes two positional operands: cond, target",
                ));
            }
            let cond = resolve_cond(&stmt.operands[0], loc)?;
            let offset = resolve_branch_target(&stmt.operands[1], pc, syms, loc)?;
            encoder::enc_branch_on(cond, offset).map_err(rangify)
        }
        "JMP" => {
            if stmt.operands.len() != 1 {
                return Err(AsmError::operand(
                    loc,
                    "JMP takes one positional operand: <addr-or-label>",
                ));
            }
            let addr = resolve_jmp_target(&stmt.operands[0], syms, loc)?;
            encoder::enc_jmp(addr).map_err(rangify)
        }
        other => Err(AsmError::lex(
            loc,
            format!("unhandled mnemonic in encoder: '{other}'"),
        )),
    }
}

// ---------------------------------------------------------------------------
// Top-level entry point used by lib.rs
// ---------------------------------------------------------------------------

pub(crate) fn assemble(source: &str, filename: &str) -> Result<Vec<u16>> {
    let statements = lex(source, filename)?;
    let Pass1Output { symbols, pc_stmts } = pass1(statements, filename)?;
    pass2(&symbols, &pc_stmts)
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

    #[test]
    fn empty_source_assembles_to_empty() {
        assert_eq!(assemble("", "<test>").unwrap(), Vec::<u16>::new());
    }

    #[test]
    fn whitespace_only_source_assembles_to_empty() {
        let src = "\n\n   \n\t\n;just a comment\n";
        assert_eq!(assemble(src, "<test>").unwrap(), Vec::<u16>::new());
    }

    #[test]
    fn halt_zero() {
        assert_eq!(assemble("HALT status=0\n", "<t>").unwrap(), vec![0x0000]);
    }

    #[test]
    fn lowercase_mnemonic_rejected() {
        let err = assemble("halt status=0\n", "<t>").unwrap_err();
        assert_eq!(err_kind(&err), Some(Kind::Lex));
    }

    #[test]
    fn unknown_mnemonic_rejected() {
        let err = assemble("FROBNICATE\n", "<t>").unwrap_err();
        assert_eq!(err_kind(&err), Some(Kind::Lex));
    }

    #[test]
    fn reserved_v05_mnemonic_rejected() {
        for m in [
            "WAIT_ADDRESSED",
            "MISMATCH_CLEAR",
            "FLAG_CLEAR",
            "CAPTURE_RUN",
        ] {
            let err = assemble(&format!("{m}\n"), "<t>").unwrap_err();
            assert_eq!(err_kind(&err), Some(Kind::Lex), "mnemonic {m}");
        }
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
    fn dw_rejects_label_operand() {
        let src = "tgt:\n  .dw tgt\n";
        let err = assemble(src, "<t>").unwrap_err();
        assert_eq!(err_kind(&err), Some(Kind::Symbol));
    }

    #[test]
    fn dw_accepts_equate_operand() {
        let src = ".equ x, 0x1234\n  .dw x, 0xC000\n";
        assert_eq!(assemble(src, "<t>").unwrap(), vec![0x1234, 0xC000]);
    }

    #[test]
    fn reserved_name_for_equate_rejected() {
        let src = ".equ HALT, 1\n";
        let err = assemble(src, "<t>").unwrap_err();
        assert_eq!(err_kind(&err), Some(Kind::Symbol));
    }

    #[test]
    fn branch_out_of_range_rejected() {
        // Force a forward branch past +127 by stuffing 129 HALTs between
        // the branch and its target.
        let mut src = String::from("BRANCH_ON ALWAYS, tgt\n");
        for _ in 0..129 {
            src.push_str("HALT\n");
        }
        src.push_str("tgt:\n  HALT\n");
        let err = assemble(&src, "<t>").unwrap_err();
        assert_eq!(err_kind(&err), Some(Kind::Range));
    }

    #[test]
    fn expect_x_with_mask_one_rejected() {
        let src = "EMIT_BIT tx=hiz expect=X mask=1\n";
        let err = assemble(src, "<t>").unwrap_err();
        assert_eq!(err_kind(&err), Some(Kind::Operand));
    }

    #[test]
    fn whitespace_tolerance() {
        // Regression: lexer used to call `body.partition(" ")` which only
        // accepted a single space character. Tabs / multi-space runs /
        // mixed-whitespace separators must all work.
        let src = ".equ\tslow_div,\t59\n\
                   start:\n\
                   \tLOAD_TIMING\t\t i2c_freq,  slow_div\n\
                   \tSET_BUS_MODE   \ti2c\n\
                   \tHALT\tstatus=0\n";
        assert_eq!(assemble(src, "<t>").unwrap(), vec![0x803B, 0x7000, 0x0000]);
    }

    #[test]
    fn roadmap_i2c_write_one_byte_example() {
        // ROADMAP §"Example: I2C write-one-byte in moleasm" --- 32 words.
        // Mirrors the Python `_selfcheck_roadmap_example` test.
        let src = "
        LOAD_TIMING   i2c_freq, 250
        SET_BUS_MODE  i2c
        EMIT_QUARTER  sda=recessive scl=recessive
        EMIT_QUARTER  sda=dominant  scl=recessive
        EMIT_QUARTER  sda=dominant  scl=dominant
        EMIT_BIT      tx=recessive
        EMIT_BIT      tx=dominant
        EMIT_BIT      tx=recessive
        EMIT_BIT      tx=dominant
        EMIT_BIT      tx=dominant
        EMIT_BIT      tx=dominant
        EMIT_BIT      tx=dominant
        EMIT_BIT      tx=dominant
        EMIT_BIT      tx=hiz expect=0 mask=1 capture=1
        BRANCH_ON     MISMATCH, nak
        EMIT_BIT      tx=recessive
        EMIT_BIT      tx=dominant
        EMIT_BIT      tx=recessive
        EMIT_BIT      tx=dominant
        EMIT_BIT      tx=recessive
        EMIT_BIT      tx=dominant
        EMIT_BIT      tx=recessive
        EMIT_BIT      tx=recessive
        EMIT_BIT      tx=hiz expect=0 mask=1 capture=1
        BRANCH_ON     MISMATCH, nak
        EMIT_QUARTER  sda=dominant  scl=dominant
        EMIT_QUARTER  sda=dominant  scl=recessive
        EMIT_QUARTER  sda=recessive scl=recessive
        MARK          label=1
        HALT          status=0
nak:
        MARK          label=2
        HALT          status=1
";
        let expected = vec![
            0x80FA, 0x7000, 0x2500, 0x2100, 0x2000, 0x1400, 0x1000, 0x1400, 0x1000, 0x1000, 0x1000,
            0x1000, 0x1000, 0x1803, 0x510F, 0x1400, 0x1000, 0x1400, 0x1000, 0x1400, 0x1000, 0x1400,
            0x1400, 0x1803, 0x5105, 0x2000, 0x2100, 0x2500, 0x9010, 0x0000, 0x9020, 0x0100,
        ];
        assert_eq!(assemble(src, "roadmap-example").unwrap(), expected);
    }
}
