//! Structured assembler diagnostics.
//!
//! Errors carry a [`SourceLocation`] (filename + 1-based line, plus an
//! optional column once the lexer learns to emit one) and a [`Kind`]
//! that callers can pattern-match without grepping the display text.

use std::fmt;
use thiserror::Error;

/// One-based source position. The lexer always sets [`line`]; [`column`]
/// is reserved for a future per-token column tracker.
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
    /// 1-based column number, when known. Currently always `None`;
    /// reserved for a future per-token column tracker.
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

/// Broad category of a syntax-level error. Callers (notably tests) can
/// match on this without depending on the human-readable `message`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    /// Unknown / reserved / mis-cased mnemonic, malformed label or
    /// directive, unknown directive name.
    Lex,
    /// Symbol-table conflict: duplicate label, duplicate `.equ`,
    /// undefined symbol, label-vs-equate confusion.
    Symbol,
    /// Numeric range overflow: literal too large, branch offset out of
    /// signed-7-bit range, program exceeds 2048 instruction slots, etc.
    Range,
    /// Operand-shape problem: missing required key, unexpected positional
    /// argument, duplicate `key=` operand, contradictory flag
    /// combination (`expect=X` + `mask=1`).
    Operand,
}

/// Anything that can go wrong assembling moleasm source or framing the
/// resulting bytecode for UART transport.
#[derive(Debug, Clone, Error)]
pub enum AsmError {
    /// A source-level error tied to a specific line. The `kind` makes
    /// the error class machine-readable; `message` carries the
    /// human-readable detail, kept stable enough for snapshot tests.
    #[error("{location}: {message}")]
    Syntax {
        /// Broad error category (lex / symbol / range / operand).
        kind: Kind,
        /// Where in the source the error fires.
        location: SourceLocation,
        /// Human-readable detail, stable across releases for tests.
        message: String,
    },

    /// Tried to build a UART frame with zero or more-than-2048 words.
    /// Not tied to a source line because [`crate::frame::build_frame`]
    /// is also a public helper.
    #[error("frame must contain 1..=2048 words, got {word_count}")]
    FrameTooLarge {
        /// Number of words the caller tried to frame. Outside the
        /// `1..=2048` range.
        word_count: usize,
    },
}

impl AsmError {
    pub(crate) fn lex(loc: &SourceLocation, msg: impl Into<String>) -> Self {
        Self::Syntax {
            kind: Kind::Lex,
            location: loc.clone(),
            message: msg.into(),
        }
    }

    pub(crate) fn symbol(loc: &SourceLocation, msg: impl Into<String>) -> Self {
        Self::Syntax {
            kind: Kind::Symbol,
            location: loc.clone(),
            message: msg.into(),
        }
    }

    pub(crate) fn range(loc: &SourceLocation, msg: impl Into<String>) -> Self {
        Self::Syntax {
            kind: Kind::Range,
            location: loc.clone(),
            message: msg.into(),
        }
    }

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
