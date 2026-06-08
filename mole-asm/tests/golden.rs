//! Golden fixture comparison tests for mole-asm v0.2.
//!
//! The v0 fixture files (`.moleasm`, `.molecode`, `.mole.bin`) are not
//! compatible with the v0.2 encoder: the wire format is 32-bit per
//! instruction instead of 16-bit, and the BUS_MODE wire values changed.
//! Regeneration of the fixtures is tracked under Phase B4.
//!
//! This stub keeps the test file alive so the build remains green.
//! Real golden tests will be added in Phase B4 alongside fresh v0.2
//! fixture files.

/// Placeholder: golden fixtures are regenerated in Phase B4.
///
/// See `docs/MOLE-0.2-SPEC.md` §12.4 for the worked examples that
/// drive the encoder unit tests in `mole-asm/src/encoder.rs`; those
/// are the authoritative v0.2 encoding checks until B4 lands.
#[test]
#[ignore = "v0.2 fixture regeneration tracked in Phase B4; \
            see docs/MOLE-0.2-SPEC.md §12.4 for encoding goldens"]
fn golden_fixtures_regenerated_in_phase_b4() {
    // Nothing to do here until Phase B4 adds v0.2 fixture files.
}
