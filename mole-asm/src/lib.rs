//! `mole-asm` --- the Layer-1 bytecode compiler for the Mole bit-cycle
//! engine.
//!
//! The user-facing tutorial lives in `book/`; this crate-level doc only
//! lists the public surface.
//!
//! # Public surface (v1)
//!
//! - [`assemble`] / [`assemble_to_frame`] --- the two top-level entry
//!   points
//! - [`AsmError`] / [`SourceLocation`] --- structured diagnostics
//! - [`frame`] --- low-level wire-format helpers (`pack_bytecode`,
//!   `build_frame`)
//! - [`crc`] --- CRC-16/XMODEM, exposed for callers that want to verify
//!   a frame they did not build through us
//!
//! Everything else is internal and not subject to semver.
