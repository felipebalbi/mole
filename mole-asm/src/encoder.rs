//! Per-opcode bit-pack encoders. Mirror
//! `fpga/Mole/src/hw/Instruction.scala::encode` byte-for-byte.
//!
//! Each function takes already-validated *raw* operand values
//! (numeric wire codes, not source-level names) and returns the
//! 16-bit word. Range checks live here because the bit-width of each
//! field is a property of the opcode, not of the source syntax;
//! callers add source location to the resulting error.
//!
//! Wire-format invariants (per AGENTS.md §§3.9, 3.10):
//! * Opcode is 5 bits at `[15:11]`.
//! * Flag triple `expect / mask / capture` is at `[2:0]` on every
//!   bearer opcode (EMIT_BIT, EMIT_QUARTER, SAMPLE_BIT_ON_SCL,
//!   DRIVE_BIT_ON_SCL).

/// Opcode field width in bits (5).
pub(crate) const OPCODE_WIDTH: u16 = 5;

/// Low bit of the opcode field within the 16-bit word (11).
pub(crate) const OPCODE_LO: u16 = 16 - OPCODE_WIDTH; // = 11

/// Width of the operand portion below the opcode (11 bits). Currently
/// only used as a doc-anchor for per-opcode range checks; kept around
/// so future ISA tweaks don't have to re-derive it.
#[allow(dead_code)]
pub(crate) const OPERAND_WIDTH: u16 = OPCODE_LO; // = 11

/// Opcode positions (mirror `Opcode.position` in `Instruction.scala`).
pub(crate) const OP_HALT: u16 = 0x00;
pub(crate) const OP_EMIT_BIT: u16 = 0x01;
pub(crate) const OP_EMIT_QUARTER: u16 = 0x02;
pub(crate) const OP_STRETCH_SCL: u16 = 0x03;
pub(crate) const OP_WAIT_ON: u16 = 0x04;
pub(crate) const OP_BRANCH_ON: u16 = 0x05;
pub(crate) const OP_JMP: u16 = 0x06;
pub(crate) const OP_SET_BUS_MODE: u16 = 0x07;
pub(crate) const OP_LOAD_TIMING: u16 = 0x08;
pub(crate) const OP_MARK: u16 = 0x09;
pub(crate) const OP_SAMPLE_BIT: u16 = 0x0A;
pub(crate) const OP_DRIVE_BIT: u16 = 0x0B;
pub(crate) const OP_LOAD_LOOP: u16 = 0x0C;
pub(crate) const OP_DEC_BRANCH: u16 = 0x0D;
// 0x0E (FLAG_CLEAR) and 0x0F (CAPTURE_RUN) are reserved v0.5; reach
// them via `.dw` if you really must.
pub(crate) const OP_SET_ROLE: u16 = 0x10;
// 0x11..=0x1F are reserved v0.5+; reachable via `.dw` only.

/// Pack the `expect / mask / capture` flag triple into bits `[2:0]`.
///
/// Layout: `[2] = expect`, `[1] = mask`, `[0] = capture`. Per
/// `AGENTS.md` §3.10 these positions are fixed on every opcode that
/// carries the triple.
fn flag_triple(expect: bool, mask: bool, capture: bool) -> u16 {
    (u16::from(expect) << 2) | (u16::from(mask) << 1) | u16::from(capture)
}

pub(crate) fn enc_halt(status: i64) -> Result<u16, String> {
    if !(0..(1 << 4)).contains(&status) {
        return Err(format!("HALT status must be 0..15, got {status}"));
    }
    // Layout: [15:11]op [10:7]status [6:0]reserved=0
    Ok((OP_HALT << OPCODE_LO) | ((status as u16) << 7))
}

pub(crate) fn enc_emit_bit(tx: u8, expect: bool, mask: bool, capture: bool) -> Result<u16, String> {
    check_tx("EMIT_BIT", tx)?;
    // Layout: [15:11]op [10:9]tx [8:3]reserved [2:0]flags
    Ok((OP_EMIT_BIT << OPCODE_LO) | (u16::from(tx) << 9) | flag_triple(expect, mask, capture))
}

pub(crate) fn enc_emit_quarter(
    sda: u8,
    scl: u8,
    expect: bool,
    mask: bool,
    capture: bool,
) -> Result<u16, String> {
    check_tx("EMIT_QUARTER sda", sda)?;
    check_tx("EMIT_QUARTER scl", scl)?;
    // Layout: [15:11]op [10:9]sda [8:7]scl [6:3]reserved [2:0]flags
    Ok((OP_EMIT_QUARTER << OPCODE_LO)
        | (u16::from(sda) << 9)
        | (u16::from(scl) << 7)
        | flag_triple(expect, mask, capture))
}

pub(crate) fn enc_stretch_scl(n_quarters: i64) -> Result<u16, String> {
    if !(0..(1 << 11)).contains(&n_quarters) {
        return Err(format!(
            "STRETCH_SCL n_quarters must be 0..2047, got {n_quarters}"
        ));
    }
    // Layout: [15:11]op [10:0]n_quarters
    Ok((OP_STRETCH_SCL << OPCODE_LO) | (n_quarters as u16))
}

pub(crate) fn enc_wait_on(cond: u8, timeout_quarters: i64) -> Result<u16, String> {
    if !(0..(1 << 7)).contains(&timeout_quarters) {
        return Err(format!(
            "WAIT_ON timeout must be 0..127 quarters, got {timeout_quarters}"
        ));
    }
    // Layout: [15:11]op [10:7]cond [6:0]timeout
    Ok((OP_WAIT_ON << OPCODE_LO) | (u16::from(cond) << 7) | (timeout_quarters as u16 & 0x7F))
}

pub(crate) fn enc_branch_on(cond: u8, pc_rel_offset: i64) -> Result<u16, String> {
    if !(-64..=63).contains(&pc_rel_offset) {
        return Err(format!(
            "BRANCH_ON pc-rel offset must be -64..63, got {pc_rel_offset}"
        ));
    }
    let byte = (pc_rel_offset as i16 as u16) & 0x7F;
    // Layout: [15:11]op [10:7]cond [6:0]offset_signed
    Ok((OP_BRANCH_ON << OPCODE_LO) | (u16::from(cond) << 7) | byte)
}

pub(crate) fn enc_jmp(addr: i64) -> Result<u16, String> {
    if !(0..(1 << 11)).contains(&addr) {
        return Err(format!(
            "JMP addr must fit in 11 bits (0..2047), got {addr}"
        ));
    }
    // Layout: [15:11]op [10:0]addr
    Ok((OP_JMP << OPCODE_LO) | (addr as u16))
}

pub(crate) fn enc_set_bus_mode(mode_wire: u8) -> Result<u16, String> {
    if !matches!(mode_wire, 0 | 1 | 6 | 7) {
        return Err(format!(
            "SET_BUS_MODE wire value must be 0|1|6|7, got {mode_wire}"
        ));
    }
    // Layout: [15:11]op [10:8]mode_wire [7:0]reserved=0
    Ok((OP_SET_BUS_MODE << OPCODE_LO) | (u16::from(mode_wire) << 8))
}

pub(crate) fn enc_load_timing(reg: i64, divider_word: i64) -> Result<u16, String> {
    if !(0..4).contains(&reg) {
        return Err(format!("LOAD_TIMING reg must be 0..3, got {reg}"));
    }
    if !(0..(1 << 9)).contains(&divider_word) {
        return Err(format!(
            "LOAD_TIMING divider_word must be 0..511, got {divider_word}"
        ));
    }
    // Layout: [15:11]op [10:9]reg [8:0]divider_word
    Ok((OP_LOAD_TIMING << OPCODE_LO) | ((reg as u16) << 9) | (divider_word as u16))
}

pub(crate) fn enc_mark(label: i64) -> Result<u16, String> {
    if !(0..(1 << 8)).contains(&label) {
        return Err(format!("MARK label must be 0..255, got {label}"));
    }
    // Layout: [15:11]op [10:3]label [2:0]reserved=0
    Ok((OP_MARK << OPCODE_LO) | ((label as u16) << 3))
}

pub(crate) fn enc_sample_bit(expect: bool, mask: bool, capture: bool) -> u16 {
    // Layout: [15:11]op [10:3]reserved [2:0]flags
    (OP_SAMPLE_BIT << OPCODE_LO) | flag_triple(expect, mask, capture)
}

pub(crate) fn enc_drive_bit(
    tx: u8,
    expect: bool,
    mask: bool,
    capture: bool,
) -> Result<u16, String> {
    check_tx("DRIVE_BIT_ON_SCL", tx)?;
    // Layout: [15:11]op [10:9]tx [8:3]reserved [2:0]flags
    Ok((OP_DRIVE_BIT << OPCODE_LO) | (u16::from(tx) << 9) | flag_triple(expect, mask, capture))
}

/// `LOAD_LOOP reg, imm8` --- prime `LCR[reg]` with an 8-bit unsigned
/// immediate. Wire layout: `[15:11]op [10]reg [9:8]reserved=0 [7:0]imm8`.
///
/// The reg id is one bit on the wire (`lcr0` = 0, `lcr1` = 1); the
/// two pad bits stay reserved so a future 4-LCR widening can claim
/// them without breaking the wire format. See `Instruction.scala`'s
/// `LoadLoop` case-class doc for the full design note.
pub(crate) fn enc_load_loop(reg: i64, imm: i64) -> Result<u16, String> {
    if !(0..2).contains(&reg) {
        return Err(format!("LOAD_LOOP reg must be 0 or 1, got {reg}"));
    }
    if !(0..(1 << 8)).contains(&imm) {
        return Err(format!("LOAD_LOOP imm must be 0..255, got {imm}"));
    }
    Ok((OP_LOAD_LOOP << OPCODE_LO) | ((reg as u16) << 10) | (imm as u16 & 0xFF))
}

/// `DEC_BRANCH reg, offset` --- decrement `LCR[reg]` then back-edge by
/// the signed 8-bit PC-relative offset iff the post-decrement value is
/// non-zero (8-bit wrap on the decrement: 0 -> 0xFF). Wire layout:
/// `[15:11]op [10]reg [9:8]reserved=0 [7:0]offset_signed`.
pub(crate) fn enc_dec_branch(reg: i64, pc_rel_offset: i64) -> Result<u16, String> {
    if !(0..2).contains(&reg) {
        return Err(format!("DEC_BRANCH reg must be 0 or 1, got {reg}"));
    }
    if !(-128..=127).contains(&pc_rel_offset) {
        return Err(format!(
            "DEC_BRANCH pc-rel offset must be -128..127, got {pc_rel_offset}"
        ));
    }
    let byte = (pc_rel_offset as i16 as u16) & 0xFF;
    Ok((OP_DEC_BRANCH << OPCODE_LO) | ((reg as u16) << 10) | byte)
}

/// `SET_ROLE role` --- switch the engine between Controller (0) and
/// Target (1) roles at runtime. Wire layout: `[15:11]op [10]role
/// [9:0]reserved=0`.
///
/// Lives in opcode slot 0x10 (the first slot exposed by the 5-bit
/// opcode widening). All v0 opcodes are also valid in either role; this
/// opcode just flips which role-specific arms the engine will dispatch
/// to (e.g. `EMIT_BIT` becomes a no-op in target role, and
/// `SAMPLE_BIT_ON_SCL` / `DRIVE_BIT_ON_SCL` only fire in target role).
pub(crate) fn enc_set_role(role: bool) -> u16 {
    // Layout: [15:11]op [10]role [9:0]reserved=0
    (OP_SET_ROLE << OPCODE_LO) | (u16::from(role) << 10)
}

fn check_tx(name: &str, tx: u8) -> Result<(), String> {
    if tx >= 4 {
        return Err(format!("{name} tx symbol must be 0..3, got {tx}"));
    }
    if tx == 0b11 {
        return Err(format!("{name} tx=reserved (0b11) is v0.5; use .dw"));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn halt_zero() {
        assert_eq!(enc_halt(0).unwrap(), 0x0000);
    }

    #[test]
    fn halt_max_status() {
        // HALT status=15 -> 0x0F << 7 = 0x0780.
        assert_eq!(enc_halt(15).unwrap(), 0x0780);
    }

    #[test]
    fn halt_overflow_rejected() {
        assert!(enc_halt(16).is_err());
        assert!(enc_halt(-1).is_err());
    }

    #[test]
    fn emit_bit_dominant() {
        // EMIT_BIT tx=dominant -> op<<11 = 0x0800.
        assert_eq!(enc_emit_bit(0b00, false, false, false).unwrap(), 0x0800);
    }

    #[test]
    fn emit_bit_recessive_no_flags() {
        // EMIT_BIT tx=recessive -> 0x0800 | (1<<9) = 0x0A00.
        assert_eq!(enc_emit_bit(0b01, false, false, false).unwrap(), 0x0A00);
    }

    #[test]
    fn emit_bit_ack_slot() {
        // EMIT_BIT tx=hiz expect=0 mask=1 capture=1
        // -> 0x0800 | (2<<9) | 0b011 = 0x0C03.
        assert_eq!(enc_emit_bit(0b10, false, true, true).unwrap(), 0x0C03);
    }

    #[test]
    fn emit_bit_reserved_tx_rejected() {
        assert!(enc_emit_bit(0b11, false, false, false).is_err());
    }

    #[test]
    fn emit_quarter_sda_dom_scl_dom() {
        // EMIT_QUARTER sda=dom scl=dom -> 0x1000.
        assert_eq!(
            enc_emit_quarter(0b00, 0b00, false, false, false).unwrap(),
            0x1000
        );
    }

    #[test]
    fn emit_quarter_sda_rec_scl_rec() {
        // EMIT_QUARTER sda=rec scl=rec -> 0x1000 | (1<<9) | (1<<7) = 0x1280.
        assert_eq!(
            enc_emit_quarter(0b01, 0b01, false, false, false).unwrap(),
            0x1280
        );
    }

    #[test]
    fn branch_on_mismatch_offset_15() {
        // BRANCH_ON MISMATCH (cond=1), offset=15
        // -> 0x2800 | (1<<7) | 15 = 0x288F.
        assert_eq!(enc_branch_on(1, 15).unwrap(), 0x288F);
    }

    #[test]
    fn branch_on_negative_offset() {
        // BRANCH_ON ALWAYS (cond=0), offset=-1
        // -> 0x2800 | 0x7F = 0x287F.
        assert_eq!(enc_branch_on(0, -1).unwrap(), 0x287F);
    }

    #[test]
    fn branch_on_extremes() {
        // -64 and 63 must encode cleanly; -65 and 64 must fail.
        assert!(enc_branch_on(0, -64).is_ok());
        assert!(enc_branch_on(0, 63).is_ok());
        assert!(enc_branch_on(0, -65).is_err());
        assert!(enc_branch_on(0, 64).is_err());
    }

    #[test]
    fn jmp_max_addr() {
        // JMP 2047 -> 0x37FF; JMP 2048 / -1 rejected.
        assert_eq!(enc_jmp(2047).unwrap(), 0x37FF);
        assert!(enc_jmp(2048).is_err());
        assert!(enc_jmp(-1).is_err());
    }

    #[test]
    fn set_bus_mode_i2c() {
        // wire 0 -> op<<11 = 0x3800.
        assert_eq!(enc_set_bus_mode(0).unwrap(), 0x3800);
    }

    #[test]
    fn set_bus_mode_i3c_pp() {
        // wire 6 -> 0x3800 | (6<<8) = 0x3E00.
        assert_eq!(enc_set_bus_mode(6).unwrap(), 0x3E00);
    }

    #[test]
    fn set_bus_mode_rejects_non_canonical() {
        for bad in [2, 3, 4, 5] {
            assert!(enc_set_bus_mode(bad).is_err(), "wire={bad}");
        }
    }

    #[test]
    fn load_timing_i2c_freq_60() {
        // LOAD_TIMING reg=0, divider=60 -> 0x4000 | 60 = 0x403C.
        assert_eq!(enc_load_timing(0, 60).unwrap(), 0x403C);
    }

    #[test]
    fn load_timing_max_divider() {
        // 9-bit divider field caps at 511; 512 must fail.
        assert!(enc_load_timing(0, 511).is_ok());
        assert!(enc_load_timing(0, 512).is_err());
    }

    #[test]
    fn mark_label_1() {
        // MARK label=1 -> 0x4800 | (1<<3) = 0x4808.
        assert_eq!(enc_mark(1).unwrap(), 0x4808);
    }

    #[test]
    fn stretch_scl_max() {
        // STRETCH_SCL 2047 -> 0x1FFF; 2048 must fail.
        assert_eq!(enc_stretch_scl(2047).unwrap(), 0x1FFF);
        assert!(enc_stretch_scl(2048).is_err());
    }

    #[test]
    fn wait_on_always_zero_timeout() {
        // WAIT_ON always (cond=0), timeout=0 -> 0x2000.
        assert_eq!(enc_wait_on(0, 0).unwrap(), 0x2000);
    }

    #[test]
    fn wait_on_max_timeout() {
        // 7-bit timeout caps at 127; 128 must fail.
        assert!(enc_wait_on(0, 127).is_ok());
        assert!(enc_wait_on(0, 128).is_err());
    }

    #[test]
    fn drive_bit_reserved_rejected() {
        assert!(enc_drive_bit(0b11, false, false, false).is_err());
    }

    #[test]
    fn load_loop_lcr0_zero() {
        // LOAD_LOOP lcr0, 0 -> 0x6000.
        assert_eq!(enc_load_loop(0, 0).unwrap(), 0x6000);
    }

    #[test]
    fn load_loop_lcr1_imm_ff() {
        // LOAD_LOOP lcr1, 0xff -> 0x6000 | (1<<10) | 0xFF = 0x64FF.
        assert_eq!(enc_load_loop(1, 0xff).unwrap(), 0x64FF);
    }

    #[test]
    fn load_loop_range_rejects() {
        assert!(enc_load_loop(2, 0).is_err());
        assert!(enc_load_loop(-1, 0).is_err());
        assert!(enc_load_loop(0, 256).is_err());
        assert!(enc_load_loop(0, -1).is_err());
    }

    #[test]
    fn dec_branch_lcr0_zero() {
        // DEC_BRANCH lcr0, 0 -> 0x6800.
        assert_eq!(enc_dec_branch(0, 0).unwrap(), 0x6800);
    }

    #[test]
    fn dec_branch_lcr0_minus_one() {
        // DEC_BRANCH lcr0, -1 -> 0x68FF (two's-complement low byte).
        assert_eq!(enc_dec_branch(0, -1).unwrap(), 0x68FF);
    }

    #[test]
    fn dec_branch_lcr1_plus_127() {
        // DEC_BRANCH lcr1, 127 -> 0x6800 | (1<<10) | 0x7F = 0x6C7F.
        assert_eq!(enc_dec_branch(1, 127).unwrap(), 0x6C7F);
    }

    #[test]
    fn dec_branch_range_rejects() {
        assert!(enc_dec_branch(2, 0).is_err());
        assert!(enc_dec_branch(0, 128).is_err());
        assert!(enc_dec_branch(0, -129).is_err());
    }

    #[test]
    fn set_role_controller() {
        // SET_ROLE controller (0) -> 0x10 << 11 = 0x8000.
        assert_eq!(enc_set_role(false), 0x8000);
    }

    #[test]
    fn set_role_target() {
        // SET_ROLE target (1) -> 0x8000 | (1<<10) = 0x8400.
        assert_eq!(enc_set_role(true), 0x8400);
    }
}
