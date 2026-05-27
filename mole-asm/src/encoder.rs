//! Per-opcode bit-pack encoders. Mirror
//! `fpga/Mole/src/hw/Instruction.scala::encode` byte-for-byte.
//!
//! Each function takes already-validated *raw* operand values
//! (numeric wire codes, not source-level names) and returns the
//! 16-bit word. Range checks live here because the bit-width of each
//! field is a property of the opcode, not of the source syntax;
//! callers add source location to the resulting error.

/// Opcode positions (mirror `Opcode.position` in `Instruction.scala`).
pub(crate) const OP_HALT: u16 = 0x0;
pub(crate) const OP_EMIT_BIT: u16 = 0x1;
pub(crate) const OP_EMIT_QUARTER: u16 = 0x2;
pub(crate) const OP_STRETCH_SCL: u16 = 0x3;
pub(crate) const OP_WAIT_ON: u16 = 0x4;
pub(crate) const OP_BRANCH_ON: u16 = 0x5;
pub(crate) const OP_JMP: u16 = 0x6;
pub(crate) const OP_SET_BUS_MODE: u16 = 0x7;
pub(crate) const OP_LOAD_TIMING: u16 = 0x8;
pub(crate) const OP_MARK: u16 = 0x9;
pub(crate) const OP_SAMPLE_BIT: u16 = 0xA;
pub(crate) const OP_DRIVE_BIT: u16 = 0xB;
// 0xC..=0xF are reserved-v0.5; reach them via `.dw` if you really must.

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
    Ok((OP_HALT << 12) | ((status as u16) << 8))
}

pub(crate) fn enc_emit_bit(tx: u8, expect: bool, mask: bool, capture: bool) -> Result<u16, String> {
    check_tx("EMIT_BIT", tx)?;
    Ok((OP_EMIT_BIT << 12) | (u16::from(tx) << 10) | flag_triple(expect, mask, capture))
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
    Ok((OP_EMIT_QUARTER << 12)
        | (u16::from(sda) << 10)
        | (u16::from(scl) << 8)
        | flag_triple(expect, mask, capture))
}

pub(crate) fn enc_stretch_scl(n_quarters: i64) -> Result<u16, String> {
    if !(0..(1 << 12)).contains(&n_quarters) {
        return Err(format!(
            "STRETCH_SCL n_quarters must be 0..4095, got {n_quarters}"
        ));
    }
    Ok((OP_STRETCH_SCL << 12) | (n_quarters as u16))
}

pub(crate) fn enc_wait_on(cond: u8, timeout_quarters: i64) -> Result<u16, String> {
    if !(0..(1 << 8)).contains(&timeout_quarters) {
        return Err(format!(
            "WAIT_ON timeout must be 0..255 quarters, got {timeout_quarters}"
        ));
    }
    Ok((OP_WAIT_ON << 12) | (u16::from(cond) << 8) | (timeout_quarters as u16 & 0xFF))
}

pub(crate) fn enc_branch_on(cond: u8, pc_rel_offset: i64) -> Result<u16, String> {
    if !(-128..=127).contains(&pc_rel_offset) {
        return Err(format!(
            "BRANCH_ON pc-rel offset must be -128..127, got {pc_rel_offset}"
        ));
    }
    let byte = (pc_rel_offset as i16 as u16) & 0xFF;
    Ok((OP_BRANCH_ON << 12) | (u16::from(cond) << 8) | byte)
}

pub(crate) fn enc_jmp(addr: i64) -> Result<u16, String> {
    if !(0..(1 << 12)).contains(&addr) {
        return Err(format!(
            "JMP addr must fit in 12 bits (0..4095), got {addr}"
        ));
    }
    Ok((OP_JMP << 12) | (addr as u16))
}

pub(crate) fn enc_set_bus_mode(mode_wire: u8) -> Result<u16, String> {
    if !matches!(mode_wire, 0 | 1 | 6 | 7) {
        return Err(format!(
            "SET_BUS_MODE wire value must be 0|1|6|7, got {mode_wire}"
        ));
    }
    Ok((OP_SET_BUS_MODE << 12) | (u16::from(mode_wire) << 9))
}

pub(crate) fn enc_load_timing(reg: i64, divider_word: i64) -> Result<u16, String> {
    if !(0..4).contains(&reg) {
        return Err(format!("LOAD_TIMING reg must be 0..3, got {reg}"));
    }
    if !(0..(1 << 10)).contains(&divider_word) {
        return Err(format!(
            "LOAD_TIMING divider_word must be 0..1023, got {divider_word}"
        ));
    }
    Ok((OP_LOAD_TIMING << 12) | ((reg as u16) << 10) | (divider_word as u16))
}

pub(crate) fn enc_mark(label: i64) -> Result<u16, String> {
    if !(0..(1 << 8)).contains(&label) {
        return Err(format!("MARK label must be 0..255, got {label}"));
    }
    Ok((OP_MARK << 12) | ((label as u16) << 4))
}

pub(crate) fn enc_sample_bit(expect: bool, mask: bool, capture: bool) -> u16 {
    (OP_SAMPLE_BIT << 12) | flag_triple(expect, mask, capture)
}

pub(crate) fn enc_drive_bit(
    tx: u8,
    expect: bool,
    mask: bool,
    capture: bool,
) -> Result<u16, String> {
    check_tx("DRIVE_BIT_ON_SCL", tx)?;
    Ok((OP_DRIVE_BIT << 12) | (u16::from(tx) << 10) | flag_triple(expect, mask, capture))
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
        assert_eq!(enc_halt(15).unwrap(), 0x0F00);
    }

    #[test]
    fn halt_overflow_rejected() {
        assert!(enc_halt(16).is_err());
        assert!(enc_halt(-1).is_err());
    }

    #[test]
    fn emit_bit_dominant() {
        // EMIT_BIT tx=dominant => 0x1000.
        assert_eq!(enc_emit_bit(0b00, false, false, false).unwrap(), 0x1000);
    }

    #[test]
    fn emit_bit_recessive_no_flags() {
        // EMIT_BIT tx=recessive => 0x1400.
        assert_eq!(enc_emit_bit(0b01, false, false, false).unwrap(), 0x1400);
    }

    #[test]
    fn emit_bit_ack_slot() {
        // EMIT_BIT tx=hiz expect=0 mask=1 capture=1 => 0x1803.
        // (tx=hiz=0b10 -> bits[11:10]=10 -> 0x1800; flags 011 -> 0x03)
        assert_eq!(enc_emit_bit(0b10, false, true, true).unwrap(), 0x1803);
    }

    #[test]
    fn emit_bit_reserved_tx_rejected() {
        assert!(enc_emit_bit(0b11, false, false, false).is_err());
    }

    #[test]
    fn emit_quarter_sda_rec_scl_rec() {
        // EMIT_QUARTER sda=rec scl=rec => 0x2500.
        assert_eq!(
            enc_emit_quarter(0b01, 0b01, false, false, false).unwrap(),
            0x2500
        );
    }

    #[test]
    fn emit_quarter_sda_dom_scl_dom() {
        // EMIT_QUARTER sda=dom scl=dom => 0x2000.
        assert_eq!(
            enc_emit_quarter(0b00, 0b00, false, false, false).unwrap(),
            0x2000
        );
    }

    #[test]
    fn branch_on_mismatch_offset_15() {
        // BRANCH_ON MISMATCH (cond=1), offset=15 -> 0x510F.
        assert_eq!(enc_branch_on(1, 15).unwrap(), 0x510F);
    }

    #[test]
    fn branch_on_negative_offset() {
        // BRANCH_ON ALWAYS (cond=0), offset=-1 -> 0x50FF.
        assert_eq!(enc_branch_on(0, -1).unwrap(), 0x50FF);
    }

    #[test]
    fn branch_on_extremes() {
        // -128 and 127 must encode cleanly.
        assert!(enc_branch_on(0, -128).is_ok());
        assert!(enc_branch_on(0, 127).is_ok());
        assert!(enc_branch_on(0, -129).is_err());
        assert!(enc_branch_on(0, 128).is_err());
    }

    #[test]
    fn jmp_max_addr() {
        assert_eq!(enc_jmp(4095).unwrap(), 0x6FFF);
        assert!(enc_jmp(4096).is_err());
        assert!(enc_jmp(-1).is_err());
    }

    #[test]
    fn set_bus_mode_i2c() {
        // wire 0 -> mode[2:0]=000 -> bits[11:9]=000 -> 0x7000.
        assert_eq!(enc_set_bus_mode(0).unwrap(), 0x7000);
    }

    #[test]
    fn set_bus_mode_i3c_pp() {
        // wire 6 -> bits[11:9]=110 -> 0x7C00.
        assert_eq!(enc_set_bus_mode(6).unwrap(), 0x7C00);
    }

    #[test]
    fn set_bus_mode_rejects_non_canonical() {
        for bad in [2, 3, 4, 5] {
            assert!(enc_set_bus_mode(bad).is_err(), "wire={bad}");
        }
    }

    #[test]
    fn load_timing_i2c_freq_250() {
        // LOAD_TIMING i2c_freq=0, 250 -> 0x80FA.
        assert_eq!(enc_load_timing(0, 250).unwrap(), 0x80FA);
    }

    #[test]
    fn mark_label_1() {
        // MARK label=1 -> 0x9010.
        assert_eq!(enc_mark(1).unwrap(), 0x9010);
    }

    #[test]
    fn stretch_scl_max() {
        assert_eq!(enc_stretch_scl(4095).unwrap(), 0x3FFF);
        assert!(enc_stretch_scl(4096).is_err());
    }

    #[test]
    fn wait_on_always_zero_timeout() {
        assert_eq!(enc_wait_on(0, 0).unwrap(), 0x4000);
    }

    #[test]
    fn drive_bit_reserved_rejected() {
        assert!(enc_drive_bit(0b11, false, false, false).is_err());
    }
}
