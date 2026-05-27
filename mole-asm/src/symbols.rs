//! Named symbol tables --- lower-case canonical, mirrors
//! `fpga/Mole/src/hw/Instruction.scala` byte-for-byte.
//!
//! Every public-facing string the assembler accepts as a *named*
//! constant (tx symbol, bus mode, condition code, timing-register
//! alias) is registered here. Mnemonic and reserved-v0.5 sets live
//! here too so the lexer's reserved-name check has a single source of
//! truth.

/// `TxSymbol.position` --- per-line drive operand for `EMIT_BIT`,
/// `EMIT_QUARTER`, `DRIVE_BIT_ON_SCL`. `reserved` (`0b11`) is held for
/// the v0.5 `raw_override` escape; the assembler refuses to emit it
/// directly. Use `.dw` if you really need the reserved code.
pub(crate) const TX_SYMBOLS: &[(&str, u8)] = &[
    ("dominant", 0b00),
    ("dom", 0b00),
    ("recessive", 0b01),
    ("rec", 0b01),
    ("hiz", 0b10),
];

/// `BusMode` wire values (default `busModeWire` encoding in
/// `Instruction.scala`). Non-sequential by design --- see
/// `ROADMAP.md` §"Bus mode register".
pub(crate) const BUS_MODES: &[(&str, u8)] =
    &[("i2c", 0), ("i3c-od", 1), ("i3c-pp", 6), ("hdr-ddr", 7)];

/// `CondCode.position` --- shared namespace for `BRANCH_ON` and
/// `WAIT_ON`. See `ROADMAP.md` §"Unified condition codes" (the Scala
/// implementation, not the ROADMAP table, is the source of truth; the
/// ROADMAP doc has been brought in line).
pub(crate) const COND_CODES: &[(&str, u8)] = &[
    ("ALWAYS", 0x0),
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

/// `LOAD_TIMING` register aliases. `timingRegs(busModeReg[1:0])` is
/// the active register, so each BUS_MODE maps to one of the four:
///
/// ```text
/// i2c     wire 0b000 -> mode[1:0]=00 -> reg 0  (i2c_freq)
/// i3c-OD  wire 0b001 -> mode[1:0]=01 -> reg 1  (i3c_od_freq)
/// i3c-PP  wire 0b110 -> mode[1:0]=10 -> reg 2  (i3c_pp_freq)
/// hdr-DDR wire 0b111 -> mode[1:0]=11 -> reg 3  (hdr_ddr_freq)
/// ```
pub(crate) const TIMING_REG_ALIASES: &[(&str, u8)] = &[
    ("i2c_freq", 0),
    ("i3c_od_freq", 1),
    ("i3c_pp_freq", 2),
    ("hdr_ddr_freq", 3),
];

/// Recognised mnemonics (UPPER CASE). The 14 v0 opcodes; everything
/// else either belongs in [`RESERVED_V05_MNEMONICS`] or is rejected
/// outright.
pub(crate) const MNEMONICS: &[&str] = &[
    "HALT",
    "EMIT_BIT",
    "EMIT_QUARTER",
    "STRETCH_SCL",
    "WAIT_ON",
    "BRANCH_ON",
    "JMP",
    "SET_BUS_MODE",
    "LOAD_TIMING",
    "MARK",
    "SAMPLE_BIT_ON_SCL",
    "DRIVE_BIT_ON_SCL",
    "LOAD_LOOP",
    "DEC_BRANCH",
];

/// Reserved-v0.5 mnemonics; the assembler rejects them and points the
/// user at `.dw` for raw-word injection. Names mirror Opcode case
/// names in `Instruction.scala`. Slots 0xC and 0xD (formerly
/// `WAIT_ADDRESSED` and `MISMATCH_CLEAR`) graduated to v0 as
/// `LOAD_LOOP` and `DEC_BRANCH`; only the two remaining reservations
/// are listed here.
pub(crate) const RESERVED_V05_MNEMONICS: &[&str] = &["FLAG_CLEAR", "CAPTURE_RUN"];

/// Loop-counter register aliases for [`Instruction::LoadLoop`] /
/// [`Instruction::DecBranch`]. One bit on the wire: `lcr0` -> 0,
/// `lcr1` -> 1. The `[10:8]` pad above the reg bit stays reserved so
/// a future 16-LCR widening reuses those bits with no wire break.
pub(crate) const LOOP_REG_ALIASES: &[(&str, u8)] = &[("lcr0", 0), ("lcr1", 1)];

/// Lookup helpers. Linear scans are fine: every table has at most a
/// dozen entries and gets hit a handful of times per source line.
pub(crate) fn lookup(table: &[(&str, u8)], name: &str) -> Option<u8> {
    table.iter().find_map(|(n, v)| (*n == name).then_some(*v))
}

/// Set membership check for the mnemonic / reserved tables.
pub(crate) fn contains(table: &[&str], name: &str) -> bool {
    table.contains(&name)
}

/// True iff `name` is reserved (a mnemonic, a v0.5 reserved mnemonic,
/// or any named tx / bus-mode / cond / timing-reg / loop-reg symbol).
/// Used by the symbol table to refuse user-defined names that would
/// shadow built-ins.
pub(crate) fn is_reserved_name(name: &str) -> bool {
    contains(MNEMONICS, name)
        || contains(RESERVED_V05_MNEMONICS, name)
        || TX_SYMBOLS.iter().any(|(n, _)| *n == name)
        || BUS_MODES.iter().any(|(n, _)| *n == name)
        || COND_CODES.iter().any(|(n, _)| *n == name)
        || TIMING_REG_ALIASES.iter().any(|(n, _)| *n == name)
        || LOOP_REG_ALIASES.iter().any(|(n, _)| *n == name)
}

/// Sorted list of accepted names from a `(name, value)` table, used in
/// error messages when an operand fails to match.
pub(crate) fn sorted_names<'a>(table: &'a [(&'a str, u8)]) -> Vec<&'a str> {
    let mut names: Vec<&'a str> = table.iter().map(|(n, _)| *n).collect();
    names.sort();
    names
}
