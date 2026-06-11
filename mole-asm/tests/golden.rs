//! Golden fixture comparison tests for mole-asm v0.2.
//!
//! Each fixture is a triple committed under `tests/fixtures/`:
//!
//!   - `<name>.moleasm`   the human-written source,
//!   - `<name>.molecode`  the raw SPRAM image (preamble + body, packed
//!     little-endian, no UART envelope),
//!   - `<name>.mole.bin`  the UART frame (length prefix + bytecode + CRC).
//!
//! Each fixture is asserted byte-identical at two granularities:
//!
//!   1. [`assemble`] then [`frame::pack_bytecode`] must equal the
//!      committed `.molecode` bytes.
//!   2. [`assemble_to_frame`] must equal the committed `.mole.bin` bytes.
//!
//! The committed bytes are co-authored by the Rust assembler and the
//! Python reference oracle (`tests/fixtures/mole-asm.py`); both must
//! agree byte-for-byte. See `tests/fixtures/README.md` for the
//! regeneration protocol.
//!
//! Fixtures are inlined via [`include_str!`] / [`include_bytes!`]
//! rather than read from disk at test time, so the tests are
//! hermetic, fast, and immune to `cwd` differences across runners.
//! Per AGENTS.md §3.4 no `build.rs` is introduced --- each fixture
//! is an explicit entry in the [`FIXTURES`] table below.

use mole_asm::{assemble, assemble_to_frame, frame::pack_bytecode};

/// One golden fixture. The three byte slices come straight off disk
/// via the `include_*!` macros so the test binary embeds them as
/// `'static` data; nothing here touches the filesystem at runtime.
struct Fixture {
    /// Fixture stem (e.g. `"first-light"`). Used only in failure
    /// messages so an opening hex-dump line identifies which fixture
    /// diverged.
    name: &'static str,
    /// `.moleasm` source text. Fed to [`assemble`] /
    /// [`assemble_to_frame`].
    source: &'static str,
    /// Expected `.molecode` bytes (preamble + body, packed
    /// little-endian).
    expected_molecode: &'static [u8],
    /// Expected `.mole.bin` bytes (UART frame: length prefix +
    /// bytecode + CRC-16/XMODEM trailer).
    expected_mole_bin: &'static [u8],
}

// -------------------------------------------------------------------------
// Fixture table. Adding a new fixture is one entry here + one #[test]
// stanza below. The two are kept in lock-step deliberately: a missing
// per-fixture test surfaces a single named failure rather than a vague
// "table grew but tests didn't" diagnostic.
// -------------------------------------------------------------------------

/// 11 fixtures: 6 v0-era programs regenerated against the v0.2
/// encoder in Phase B4, plus 5 adversarial probes that lock specific
/// spec invariants.
const FIXTURES: &[Fixture] = &[
    // -- 6 regenerated v0 programs --
    Fixture {
        name: "first-light",
        source: include_str!("fixtures/first-light.moleasm"),
        expected_molecode: include_bytes!("fixtures/first-light.molecode"),
        expected_mole_bin: include_bytes!("fixtures/first-light.mole.bin"),
    },
    Fixture {
        name: "i2c-write-one-byte",
        source: include_str!("fixtures/i2c-write-one-byte.moleasm"),
        expected_molecode: include_bytes!("fixtures/i2c-write-one-byte.molecode"),
        expected_mole_bin: include_bytes!("fixtures/i2c-write-one-byte.mole.bin"),
    },
    Fixture {
        name: "i2c-soak",
        source: include_str!("fixtures/i2c-soak.moleasm"),
        expected_molecode: include_bytes!("fixtures/i2c-soak.molecode"),
        expected_mole_bin: include_bytes!("fixtures/i2c-soak.mole.bin"),
    },
    Fixture {
        name: "i3c-write-byte",
        source: include_str!("fixtures/i3c-write-byte.moleasm"),
        expected_molecode: include_bytes!("fixtures/i3c-write-byte.molecode"),
        expected_mole_bin: include_bytes!("fixtures/i3c-write-byte.mole.bin"),
    },
    Fixture {
        name: "loop-counter-demo",
        source: include_str!("fixtures/loop-counter-demo.moleasm"),
        expected_molecode: include_bytes!("fixtures/loop-counter-demo.molecode"),
        expected_mole_bin: include_bytes!("fixtures/loop-counter-demo.mole.bin"),
    },
    Fixture {
        name: "tmp108",
        source: include_str!("fixtures/tmp108.moleasm"),
        expected_molecode: include_bytes!("fixtures/tmp108.molecode"),
        expected_mole_bin: include_bytes!("fixtures/tmp108.mole.bin"),
    },
    // -- 5 adversarial probes (Phase B4) --
    Fixture {
        name: "pipeline-hazard-emit-dec",
        source: include_str!("fixtures/pipeline-hazard-emit-dec.moleasm"),
        expected_molecode: include_bytes!("fixtures/pipeline-hazard-emit-dec.molecode"),
        expected_mole_bin: include_bytes!("fixtures/pipeline-hazard-emit-dec.mole.bin"),
    },
    Fixture {
        name: "emit-byte-throughput-100",
        source: include_str!("fixtures/emit-byte-throughput-100.moleasm"),
        expected_molecode: include_bytes!("fixtures/emit-byte-throughput-100.molecode"),
        expected_mole_bin: include_bytes!("fixtures/emit-byte-throughput-100.mole.bin"),
    },
    Fixture {
        name: "duty-cycle-warp",
        source: include_str!("fixtures/duty-cycle-warp.moleasm"),
        expected_molecode: include_bytes!("fixtures/duty-cycle-warp.molecode"),
        expected_mole_bin: include_bytes!("fixtures/duty-cycle-warp.mole.bin"),
    },
    Fixture {
        name: "watchdog-trip",
        source: include_str!("fixtures/watchdog-trip.moleasm"),
        expected_molecode: include_bytes!("fixtures/watchdog-trip.molecode"),
        expected_mole_bin: include_bytes!("fixtures/watchdog-trip.mole.bin"),
    },
    Fixture {
        name: "dec-branch-loop-deep",
        source: include_str!("fixtures/dec-branch-loop-deep.moleasm"),
        expected_molecode: include_bytes!("fixtures/dec-branch-loop-deep.molecode"),
        expected_mole_bin: include_bytes!("fixtures/dec-branch-loop-deep.mole.bin"),
    },
];

// -------------------------------------------------------------------------
// Test driver.
// -------------------------------------------------------------------------

/// Locate the fixture entry by name, or panic with a list of known
/// fixtures (failure mode helpful when the per-fixture `#[test]`
/// stanzas and the [`FIXTURES`] table fall out of lock-step).
fn fixture(name: &'static str) -> &'static Fixture {
    FIXTURES.iter().find(|f| f.name == name).unwrap_or_else(|| {
        let known: Vec<&str> = FIXTURES.iter().map(|f| f.name).collect();
        panic!("golden fixture {name:?} not in FIXTURES table; known: {known:?}",);
    })
}

/// Run both byte-equality checks for one fixture.
///
/// On mismatch, the first divergent 16-byte window is hex-dumped to
/// the panic message so a CI failure carries enough context to
/// reproduce locally without rerunning under a debugger.
fn check_fixture(f: &Fixture) {
    // 1. .molecode (raw SPRAM image).
    let words = assemble(f.source, f.name)
        .unwrap_or_else(|e| panic!("[{}] assemble() failed: {e}", f.name));
    let molecode = pack_bytecode(&words);
    assert_bytes_eq(f.name, ".molecode", &molecode, f.expected_molecode);

    // 2. .mole.bin (framed for the wire).
    let mole_bin = assemble_to_frame(f.source, f.name)
        .unwrap_or_else(|e| panic!("[{}] assemble_to_frame() failed: {e}", f.name));
    assert_bytes_eq(f.name, ".mole.bin", &mole_bin, f.expected_mole_bin);
}

/// Byte-equality with first-divergence reporting.
///
/// On mismatch panics with:
///
///   - fixture name + artifact (`.molecode` / `.mole.bin`),
///   - actual vs expected lengths,
///   - first divergent byte offset (or "length differs" if one slice
///     ran out before the first byte mismatch),
///   - hex-dump of the 16-byte window starting at the divergent
///     offset (or as much as remains), for both actual and expected.
///
/// The hex window is aligned to a 16-byte boundary so multiple
/// failures across fixtures line up legibly in CI output.
fn assert_bytes_eq(fixture_name: &str, artifact: &str, actual: &[u8], expected: &[u8]) {
    if actual == expected {
        return;
    }

    let first_diff = actual
        .iter()
        .zip(expected.iter())
        .position(|(a, e)| a != e)
        .unwrap_or(actual.len().min(expected.len()));

    let window_start = (first_diff / 16) * 16;
    let actual_window = hex_window(actual, window_start);
    let expected_window = hex_window(expected, window_start);

    panic!(
        "[{fixture_name}] {artifact} mismatch:\n  \
         actual len   = {alen} bytes\n  \
         expected len = {elen} bytes\n  \
         first diff   = offset 0x{first_diff:04x} ({first_diff})\n  \
         actual   [0x{window_start:04x}]: {actual_window}\n  \
         expected [0x{window_start:04x}]: {expected_window}",
        alen = actual.len(),
        elen = expected.len(),
    );
}

/// Format up to 16 bytes starting at `start` as two-digit
/// space-separated hex (or "<EOF>" if `start` is past the end).
fn hex_window(buf: &[u8], start: usize) -> String {
    if start >= buf.len() {
        return "<EOF>".to_string();
    }
    let end = buf.len().min(start + 16);
    let mut out = String::with_capacity(3 * 16);
    for (i, b) in buf[start..end].iter().enumerate() {
        if i > 0 {
            out.push(' ');
        }
        out.push_str(&format!("{b:02x}"));
    }
    out
}

// -------------------------------------------------------------------------
// One #[test] per fixture (11 total).
//
// Named with the fixture stem so cargo's per-test diagnostics surface
// the exact failing program. Each is a one-liner that delegates to
// check_fixture; the body lives in the helpers above.
// -------------------------------------------------------------------------

#[test]
fn first_light_matches_committed_bytes() {
    check_fixture(fixture("first-light"));
}

#[test]
fn i2c_write_one_byte_matches_committed_bytes() {
    check_fixture(fixture("i2c-write-one-byte"));
}

#[test]
fn i2c_soak_matches_committed_bytes() {
    check_fixture(fixture("i2c-soak"));
}

#[test]
fn i3c_write_byte_matches_committed_bytes() {
    check_fixture(fixture("i3c-write-byte"));
}

#[test]
fn loop_counter_demo_matches_committed_bytes() {
    check_fixture(fixture("loop-counter-demo"));
}

#[test]
fn tmp108_matches_committed_bytes() {
    check_fixture(fixture("tmp108"));
}

#[test]
fn pipeline_hazard_emit_dec_matches_committed_bytes() {
    check_fixture(fixture("pipeline-hazard-emit-dec"));
}

#[test]
fn emit_byte_throughput_100_matches_committed_bytes() {
    check_fixture(fixture("emit-byte-throughput-100"));
}

#[test]
fn duty_cycle_warp_matches_committed_bytes() {
    check_fixture(fixture("duty-cycle-warp"));
}

#[test]
fn watchdog_trip_matches_committed_bytes() {
    check_fixture(fixture("watchdog-trip"));
}

#[test]
fn dec_branch_loop_deep_matches_committed_bytes() {
    check_fixture(fixture("dec-branch-loop-deep"));
}

// -------------------------------------------------------------------------
// Meta-test: the per-fixture #[test] stanzas and the FIXTURES table
// must stay in lock-step. If an entry is added to FIXTURES but no
// corresponding #[test] is added, this catches it.
// -------------------------------------------------------------------------

/// Names referenced by the per-fixture `#[test]` stanzas above.
/// Manually mirrors the test list; intentionally redundant with
/// [`FIXTURES`] so a drift surfaces as a failed assertion rather
/// than as a silent untested fixture.
const TESTED_FIXTURE_NAMES: &[&str] = &[
    "first-light",
    "i2c-write-one-byte",
    "i2c-soak",
    "i3c-write-byte",
    "loop-counter-demo",
    "tmp108",
    "pipeline-hazard-emit-dec",
    "emit-byte-throughput-100",
    "duty-cycle-warp",
    "watchdog-trip",
    "dec-branch-loop-deep",
];

#[test]
fn every_fixture_has_a_dedicated_test() {
    let table: Vec<&str> = FIXTURES.iter().map(|f| f.name).collect();
    assert_eq!(
        table, TESTED_FIXTURE_NAMES,
        "FIXTURES table and TESTED_FIXTURE_NAMES drifted; \
         add or remove the matching #[test] stanza"
    );
}
