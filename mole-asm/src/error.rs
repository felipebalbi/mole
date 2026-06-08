//! Structured assembler diagnostics for mole-asm v0.2.
//!
//! Each error carries a [`SourceLocation`] (filename + 1-based line)
//! and a [`Kind`] that callers can pattern-match without grepping
//! the human-readable message. Error codes from §13 are embedded in
//! the message text as `E-<CATEGORY>-<NNN>:` prefixes so the CLI can
//! render them; the `code()` method returns the code string alone.

use std::fmt;
use thiserror::Error;

/// One-based source position.
///
/// The lexer always sets [`line`]; [`column`] is reserved for a
/// future per-token column tracker.
///
/// [`line`]: SourceLocation::line
/// [`column`]: SourceLocation::column
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SourceLocation {
    /// Source file name as supplied by the caller. May be a sentinel
    /// like `"<inline>"` or `"<stdin>"` for in-memory sources.
    pub filename: String,
    /// 1-based source line number.
    pub line: usize,
    /// 1-based column number, when known. Currently always `None`.
    pub column: Option<usize>,
}

impl SourceLocation {
    pub(crate) fn new(filename: impl Into<String>, line: usize) -> Self {
        Self {
            filename: filename.into(),
            line,
            column: None,
        }
    }
}

impl fmt::Display for SourceLocation {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self.column {
            Some(col) => write!(f, "{}:{}:{}", self.filename, self.line, col),
            None => write!(f, "{}:{}", self.filename, self.line),
        }
    }
}

/// Broad category of a source-level error. Callers (notably tests) can
/// match on this without depending on the human-readable `message`.
///
/// Each variant maps to one or more §13 error codes.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    /// E-LEX-* — unknown / reserved mnemonic, malformed label,
    /// unknown directive, LOOP-group mnemonic.
    Lex,
    /// E-SYM-* — duplicate label, duplicate `.equ`, undefined
    /// symbol, label-vs-equate confusion.
    Symbol,
    /// E-RNG-* — numeric range overflow, branch offset out of range,
    /// program exceeds MAX_PROGRAM_WORDS.
    Range,
    /// E-OP-* / E-REG-* / E-CTRL-* / E-WIRE-* / E-RAW-* /
    /// E-FRM-* — missing required operand, unexpected positional
    /// token, duplicate key, contradictory flag combination, bad
    /// register, EMIT_BYTE pairing violation, raw-pragma violation.
    Operand,
}

/// Anything that can go wrong assembling moleasm source or framing the
/// resulting bytecode for UART transport.
#[derive(Debug, Clone, Error)]
pub enum AsmError {
    /// A source-level error tied to a specific line. `kind` is
    /// machine-readable; `message` is human-readable and includes the
    /// §13 error code as a `E-*-*: ` prefix.
    #[error("{location}: {message}")]
    Syntax {
        /// Broad error category.
        kind: Kind,
        /// Source position.
        location: SourceLocation,
        /// Human-readable detail including the §13 code prefix.
        message: String,
    },

    /// Frame contains zero words, or more than
    /// `mole_abi::MAX_PROGRAM_WORDS + mole_abi::PREAMBLE_WORDS` words
    /// (i.e. 8194 total: 8192 body + 2 preamble).
    #[error(
        "E-FRM-001: frame must contain 1..=8194 total words \
         (8192 body + 2 preamble), got {word_count}"
    )]
    FrameTooLarge {
        /// Total word count (preamble + body) that was rejected.
        word_count: usize,
    },
}

impl AsmError {
    /// Construct a Lex-category error (E-LEX-*).
    pub(crate) fn lex(loc: &SourceLocation, msg: impl Into<String>) -> Self {
        Self::Syntax {
            kind: Kind::Lex,
            location: loc.clone(),
            message: msg.into(),
        }
    }

    /// Construct a Symbol-category error (E-SYM-*).
    pub(crate) fn symbol(loc: &SourceLocation, msg: impl Into<String>) -> Self {
        Self::Syntax {
            kind: Kind::Symbol,
            location: loc.clone(),
            message: msg.into(),
        }
    }

    /// Construct a Range-category error (E-RNG-*).
    pub(crate) fn range(loc: &SourceLocation, msg: impl Into<String>) -> Self {
        Self::Syntax {
            kind: Kind::Range,
            location: loc.clone(),
            message: msg.into(),
        }
    }

    /// Construct an Operand-category error (E-OP-* / E-REG-* /
    /// E-CTRL-* / E-WIRE-* / E-RAW-* / E-FRM-*).
    pub(crate) fn operand(loc: &SourceLocation, msg: impl Into<String>) -> Self {
        Self::Syntax {
            kind: Kind::Operand,
            location: loc.clone(),
            message: msg.into(),
        }
    }
}

/// Crate-internal `Result` alias.
pub type Result<T> = std::result::Result<T, AsmError>;
