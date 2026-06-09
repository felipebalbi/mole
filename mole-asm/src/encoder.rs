//! Per-opcode bit-pack encoders for the Mole v0.2 ISA.
//!
//! Each function accepts already-validated *raw* operand values
//! (numeric wire codes, not source-level names) and returns a
//! 32-bit instruction word. Range checks live here because the
//! bit-width of each field is a property of the opcode encoding,
//! not of the source syntax.
//!
//! ## v0.2 instruction-word anatomy (§3)
//!
//! ```text
//! [31:30] group   (2 bits: WIRE=0b00, CTRL=0b01, DATA=0b10, LOOP=0b11)
//! [29:26] sub     (4 bits: sub-opcode within group)
//! [25:23] dst     (3 bits: destination register R0–R7)
//! [22:20] src     (3 bits: source register R0–R7)
//! [19:17] aux     (3 bits: auxiliary register or other use)
//! [16: 3] payload (14 bits: immediate, offset, or opcode-specific)
//! [ 2: 0] flags   (3 bits: flag triple on bearer opcodes; else 0)
//! ```
//!
//! `opcode` = `{group[31:30], sub[29:26]}`.  All 26 live opcodes:
//! 10 WIRE, 8 CTRL, 8 DATA.

// -----------------------------------------------------------------------
// Group / sub constants
// -----------------------------------------------------------------------

const G_WIRE: u32 = 0b00;
const G_CTRL: u32 = 0b01;
const G_DATA: u32 = 0b10;

// WIRE sub-opcodes
const S_EMIT_BIT_IMM: u32 = 0b0000;
const S_EMIT_BIT_REG: u32 = 0b0001;
const S_EMIT_QUARTER_IMM: u32 = 0b0010;
const S_EMIT_QUARTER_REG: u32 = 0b0011;
const S_EMIT_BYTE_REG: u32 = 0b0100;
const S_SAMPLE_BIT_ON_SCL: u32 = 0b0101;
const S_DRIVE_BIT_ON_SCL: u32 = 0b0110;
const S_STRETCH_SCL_IMM: u32 = 0b0111;
const S_STRETCH_SCL_REG: u32 = 0b1000;
const S_EMIT_BYTE_IMM: u32 = 0b1001;

// CTRL sub-opcodes
const S_HALT: u32 = 0b0000;
const S_BRANCH_ON: u32 = 0b0001;
const S_WAIT_ON: u32 = 0b0010;
const S_SET_BUS_MODE: u32 = 0b0011;
const S_SET_ROLE: u32 = 0b0100;
const S_FLAG_CLEAR: u32 = 0b0101;
const S_MARK: u32 = 0b0110;
const S_LOAD_TIMING: u32 = 0b0111;

// DATA sub-opcodes
const S_LOAD_IMM: u32 = 0b0000;
const S_MOV: u32 = 0b0001;
const S_ADD_IMM: u32 = 0b0010;
const S_DEC: u32 = 0b0011;
const S_AND_IMM: u32 = 0b0100;
const S_OR_IMM: u32 = 0b0101;
const S_XOR_IMM: u32 = 0b0110;
const S_SHIFT: u32 = 0b0111;

// -----------------------------------------------------------------------
// Low-level helpers
// -----------------------------------------------------------------------

/// Build the 32-bit opcode prefix: `group << 30 | sub << 26`.
#[inline]
fn opcode(group: u32, sub: u32) -> u32 {
    (group << 30) | (sub << 26)
}

/// Pack the `expect / mask / capture` flag triple into bits `[2:0]`.
///
/// Layout: `[2] = expect`, `[1] = mask`, `[0] = capture`. Per §3 these
/// positions are fixed on every bearer opcode.
#[inline]
fn flag_triple(expect: bool, mask: bool, capture: bool) -> u32 {
    (u32::from(expect) << 2) | (u32::from(mask) << 1) | u32::from(capture)
}

/// Validate a `tx_symbol` value. Returns an error if the value is the
/// reserved encoding `0b11` and `raw_mode` is false.
fn check_tx(name: &str, tx: u8, raw_mode: bool) -> Result<(), String> {
    if tx > 3 {
        return Err(format!(
            "E-RNG-001: {name}: tx symbol must be 0..3, got {tx}"
        ));
    }
    if tx == 0b11 && !raw_mode {
        return Err(format!(
            "E-WIRE-001: tx=reserved (0b11) requires \
             '(use-raw-primitives)' pragma; use .dw or declare raw/"
        ));
    }
    Ok(())
}

/// Validate a 3-bit register index (R0–R7).
fn check_reg(name: &str, reg: u8) -> Result<(), String> {
    if reg > 7 {
        return Err(format!(
            "E-OP-007: {name}: register R{reg} is not valid; use R0..R7"
        ));
    }
    Ok(())
}

// -----------------------------------------------------------------------
// WIRE group encoders
// -----------------------------------------------------------------------

/// WIRE.EMIT_BIT_IMM (§5.1)
///
/// ```text
/// [31:30] group  = 0b00
/// [29:26] sub    = 0b0000
/// [25: 5] reserved = 0
/// [ 4: 3] tx     (2 bits)
/// [ 2: 0] flags
/// ```
pub(crate) fn enc_emit_bit_imm(
    tx: u8,
    expect: bool,
    mask: bool,
    capture: bool,
    raw_mode: bool,
) -> Result<u32, String> {
    check_tx("EMIT_BIT_IMM", tx, raw_mode)?;
    Ok(opcode(G_WIRE, S_EMIT_BIT_IMM) | (u32::from(tx) << 3) | flag_triple(expect, mask, capture))
}

/// WIRE.EMIT_BIT_REG (§5.2)
///
/// ```text
/// [31:30] group  = 0b00
/// [29:26] sub    = 0b0001
/// [25:23] reserved = 0
/// [22:20] src    (3 bits: register R0–R7)
/// [19: 3] reserved = 0
/// [ 2: 0] flags
/// ```
pub(crate) fn enc_emit_bit_reg(
    src: u8,
    expect: bool,
    mask: bool,
    capture: bool,
) -> Result<u32, String> {
    check_reg("EMIT_BIT_REG src", src)?;
    Ok(
        opcode(G_WIRE, S_EMIT_BIT_REG)
            | (u32::from(src) << 20)
            | flag_triple(expect, mask, capture),
    )
}

/// WIRE.EMIT_QUARTER_IMM (§5.3)
///
/// ```text
/// [31:30] group  = 0b00
/// [29:26] sub    = 0b0010
/// [25: 7] reserved = 0
/// [ 6: 5] scl    (2 bits)
/// [ 4: 3] sda    (2 bits)
/// [ 2: 0] flags
/// ```
pub(crate) fn enc_emit_quarter_imm(
    sda: u8,
    scl: u8,
    expect: bool,
    mask: bool,
    capture: bool,
    raw_mode: bool,
) -> Result<u32, String> {
    check_tx("EMIT_QUARTER_IMM sda", sda, raw_mode)?;
    check_tx("EMIT_QUARTER_IMM scl", scl, raw_mode)?;
    Ok(opcode(G_WIRE, S_EMIT_QUARTER_IMM)
        | (u32::from(scl) << 5)
        | (u32::from(sda) << 3)
        | flag_triple(expect, mask, capture))
}

/// WIRE.EMIT_QUARTER_REG (§5.4)
///
/// ```text
/// [31:30] group  = 0b00
/// [29:26] sub    = 0b0011
/// [25:23] reserved = 0
/// [22:20] src    (3 bits: register R0–R7)
/// [19: 3] reserved = 0
/// [ 2: 0] flags
/// ```
pub(crate) fn enc_emit_quarter_reg(
    src: u8,
    expect: bool,
    mask: bool,
    capture: bool,
) -> Result<u32, String> {
    check_reg("EMIT_QUARTER_REG src", src)?;
    Ok(opcode(G_WIRE, S_EMIT_QUARTER_REG)
        | (u32::from(src) << 20)
        | flag_triple(expect, mask, capture))
}

/// WIRE.EMIT_BYTE_REG (§5.5)
///
/// ```text
/// [31:30] group  = 0b00
/// [29:26] sub    = 0b0100
/// [25: 3] reserved = 0
/// [ 2: 0] flags
/// ```
///
/// Reads payload byte from R7[7:0] at E stage.
pub(crate) fn enc_emit_byte_reg(expect: bool, mask: bool, capture: bool) -> u32 {
    opcode(G_WIRE, S_EMIT_BYTE_REG) | flag_triple(expect, mask, capture)
}

/// WIRE.EMIT_BYTE_IMM (§5.5b)
///
/// ```text
/// [31:30] group  = 0b00
/// [29:26] sub    = 0b1001
/// [25:11] reserved = 0
/// [10: 3] imm    = payload byte (MSB on SDA first)
/// [ 2: 0] flags
/// ```
///
/// The 8-bit payload occupies `[10:3]`; the MSB (`[10]`) is shifted
/// out first onto SDA. Unlike EMIT_BYTE_REG, this opcode does not
/// read R7 — the payload is encoded in the instruction word, so a
/// preceding `LOAD_IMM R7, ...` introduces no load-use hazard. The
/// range check on `imm` is trivially satisfied by the `u8` argument
/// type (caller is responsible for E-RNG-001 reporting on overflow
/// from a wider source-level literal).
pub(crate) fn enc_emit_byte_imm(imm: u8, expect: bool, mask: bool, capture: bool) -> u32 {
    opcode(G_WIRE, S_EMIT_BYTE_IMM) | (u32::from(imm) << 3) | flag_triple(expect, mask, capture)
}

/// WIRE.SAMPLE_BIT_ON_SCL (§5.6)
///
/// ```text
/// [31:30] group  = 0b00
/// [29:26] sub    = 0b0101
/// [25:23] dst    (3 bits: must be R7=0b111 unless raw/)
/// [22: 3] reserved = 0
/// [ 2: 0] flags
/// ```
///
/// Under `raw_mode`, the caller may specify any `dst` register.
/// Without `raw_mode`, `dst` is forced to 7 (R7).
pub(crate) fn enc_sample_bit_on_scl(
    dst: u8,
    expect: bool,
    mask: bool,
    capture: bool,
    raw_mode: bool,
) -> Result<u32, String> {
    check_reg("SAMPLE_BIT_ON_SCL dst", dst)?;
    if dst != 7 && !raw_mode {
        return Err("E-REG-001: capture must write to R7; use raw/ pragma to override".to_string());
    }
    Ok(opcode(G_WIRE, S_SAMPLE_BIT_ON_SCL)
        | (u32::from(dst) << 23)
        | flag_triple(expect, mask, capture))
}

/// WIRE.DRIVE_BIT_ON_SCL (§5.7)
///
/// ```text
/// [31:30] group  = 0b00
/// [29:26] sub    = 0b0110
/// [25:23] dst    (3 bits: must be R7 when capture=1; else 0b000)
/// [22: 5] reserved = 0
/// [ 4: 3] tx     (2 bits)
/// [ 2: 0] flags
/// ```
///
/// When `capture=0` the encoder emits `dst=0b000` as canonical form.
/// Under `raw_mode`, any `dst` register is accepted when `capture=1`.
pub(crate) fn enc_drive_bit_on_scl(
    tx: u8,
    expect: bool,
    mask: bool,
    capture: bool,
    raw_mode: bool,
) -> Result<u32, String> {
    check_tx("DRIVE_BIT_ON_SCL", tx, raw_mode)?;
    // Canonical: when capture=0, dst=0b000. When capture=1, dst=R7=7.
    // Raw mode allows dst override via dst field; the assembler layer
    // passes dst=7 (or the overridden value) explicitly.
    let dst: u32 = if capture { 7 } else { 0 };
    Ok(opcode(G_WIRE, S_DRIVE_BIT_ON_SCL)
        | (dst << 23)
        | (u32::from(tx) << 3)
        | flag_triple(expect, mask, capture))
}

/// WIRE.DRIVE_BIT_ON_SCL with explicit dst (raw/ override, §5.7).
pub(crate) fn enc_drive_bit_on_scl_raw(
    dst: u8,
    tx: u8,
    expect: bool,
    mask: bool,
    capture: bool,
) -> Result<u32, String> {
    check_reg("DRIVE_BIT_ON_SCL dst", dst)?;
    check_tx("DRIVE_BIT_ON_SCL", tx, true)?;
    Ok(opcode(G_WIRE, S_DRIVE_BIT_ON_SCL)
        | (u32::from(dst) << 23)
        | (u32::from(tx) << 3)
        | flag_triple(expect, mask, capture))
}

/// WIRE.STRETCH_SCL_IMM (§5.8)
///
/// ```text
/// [31:30] group  = 0b00
/// [29:26] sub    = 0b0111
/// [25:17] reserved = 0
/// [16: 3] n_quarters (14 bits, 0..16383)
/// [ 2: 0] reserved = 0
/// ```
pub(crate) fn enc_stretch_scl_imm(n_quarters: i64) -> Result<u32, String> {
    if !(0..(1 << 14)).contains(&n_quarters) {
        return Err(format!(
            "E-RNG-001: STRETCH_SCL_IMM n_quarters must be 0..16383, \
             got {n_quarters}"
        ));
    }
    Ok(opcode(G_WIRE, S_STRETCH_SCL_IMM) | ((n_quarters as u32) << 3))
}

/// WIRE.STRETCH_SCL_REG (§5.9)
///
/// ```text
/// [31:30] group  = 0b00
/// [29:26] sub    = 0b1000
/// [25:23] reserved = 0
/// [22:20] src    (3 bits: register R0–R7)
/// [19: 3] reserved = 0
/// [ 2: 0] reserved = 0
/// ```
pub(crate) fn enc_stretch_scl_reg(src: u8) -> Result<u32, String> {
    check_reg("STRETCH_SCL_REG src", src)?;
    Ok(opcode(G_WIRE, S_STRETCH_SCL_REG) | (u32::from(src) << 20))
}

// -----------------------------------------------------------------------
// CTRL group encoders
// -----------------------------------------------------------------------

/// CTRL.HALT (§5.10)
///
/// ```text
/// [31:30] group    = 0b01
/// [29:26] sub      = 0b0000
/// [25: 8] reserved = 0
/// [ 7: 3] status   (5 bits, 0x00..0x1C user; 0x1F trap)
/// [ 2: 0] reserved = 0
/// ```
pub(crate) fn enc_halt(status: i64) -> Result<u32, String> {
    // Spec §11: 0x1D–0x1E are reserved; loader rejects them.
    // The assembler only rejects out-of-range here; the loader
    // does the 0x1D/0x1E check.
    if !(0..32).contains(&status) {
        return Err(format!(
            "E-RNG-001: HALT status must be 0..31, got {status}"
        ));
    }
    Ok(opcode(G_CTRL, S_HALT) | ((status as u32) << 3))
}

/// CTRL.BRANCH_ON (§5.11)
///
/// ```text
/// [31:30] group    = 0b01
/// [29:26] sub      = 0b0001
/// [25:17] reserved = 0
/// [16:13] cond     (4 bits: condition code 0..15)
/// [12: 3] offset   (10 bits, signed two's-complement, -512..511)
/// [ 2: 0] reserved = 0
/// ```
///
/// Branch target: `next_pc = branch_pc + 1 + offset`
/// Offset: `label_pc - branch_pc - 1`
pub(crate) fn enc_branch_on(cond: u8, pc_rel_offset: i64) -> Result<u32, String> {
    if cond > 15 {
        return Err(format!(
            "E-CTRL-001: cond code {cond} is out of range 0..15"
        ));
    }
    if !(-512..=511).contains(&pc_rel_offset) {
        return Err(format!(
            "E-RNG-003: BRANCH_ON offset {pc_rel_offset} out of signed \
             10-bit range (-512..511)"
        ));
    }
    let offset_bits = (pc_rel_offset as i32 as u32) & 0x3FF;
    Ok(opcode(G_CTRL, S_BRANCH_ON) | (u32::from(cond) << 13) | (offset_bits << 3))
}

/// CTRL.WAIT_ON (§5.12)
///
/// ```text
/// [31:30] group    = 0b01
/// [29:26] sub      = 0b0010
/// [25:17] reserved = 0
/// [16:13] cond     (4 bits)
/// [12: 3] timeout  (10 bits, unsigned, 0..1023; 0 = infinite)
/// [ 2: 0] reserved = 0
/// ```
pub(crate) fn enc_wait_on(cond: u8, timeout: i64) -> Result<u32, String> {
    if cond > 15 {
        return Err(format!(
            "E-CTRL-001: cond code {cond} is out of range 0..15"
        ));
    }
    if !(0..1024).contains(&timeout) {
        return Err(format!(
            "E-RNG-004: WAIT_ON timeout must be 0..1023 quarters, got {timeout}"
        ));
    }
    Ok(opcode(G_CTRL, S_WAIT_ON) | (u32::from(cond) << 13) | ((timeout as u32) << 3))
}

/// CTRL.SET_BUS_MODE (§5.13)
///
/// ```text
/// [31:30] group  = 0b01
/// [29:26] sub    = 0b0011
/// [25: 7] reserved = 0
/// [ 6: 3] mode   (4 bits: 0b0000..0b0011 live)
/// [ 2: 0] reserved = 0
/// ```
///
/// v0.2 BUS_MODE wire values (RENUMBERED from v0):
///   i2c=0, i3c-OD=1, i3c-PP=2, hdr-ddr=3
pub(crate) fn enc_set_bus_mode(mode_wire: u8) -> Result<u32, String> {
    if mode_wire > 15 {
        return Err(format!(
            "E-OP-006: SET_BUS_MODE wire value must be 0..15, got {mode_wire}"
        ));
    }
    Ok(opcode(G_CTRL, S_SET_BUS_MODE) | (u32::from(mode_wire) << 3))
}

/// CTRL.SET_ROLE (§5.14)
///
/// ```text
/// [31:30] group    = 0b01
/// [29:26] sub      = 0b0100
/// [25: 4] reserved = 0
/// [ 3]    role     (1 bit: 0=controller, 1=target)
/// [ 2: 0] reserved = 0
/// ```
pub(crate) fn enc_set_role(role: bool) -> u32 {
    opcode(G_CTRL, S_SET_ROLE) | (u32::from(role) << 3)
}

/// CTRL.FLAG_CLEAR (§5.15)
///
/// ```text
/// [31:30] group  = 0b01
/// [29:26] sub    = 0b0101
/// [25: 8] reserved = 0
/// [ 7: 3] mask   (5 bits; bit N=1 clears flag N)
/// [ 2: 0] reserved = 0
/// ```
pub(crate) fn enc_flag_clear(mask: u8) -> Result<u32, String> {
    if mask > 31 {
        return Err(format!(
            "E-OP-009: FLAG_CLEAR mask must be a 5-bit value (0..31), got {mask}"
        ));
    }
    Ok(opcode(G_CTRL, S_FLAG_CLEAR) | (u32::from(mask) << 3))
}

/// CTRL.MARK (§5.16)
///
/// ```text
/// [31:30] group    = 0b01
/// [29:26] sub      = 0b0110
/// [25:17] reserved = 0
/// [16: 3] label    (14 bits, unsigned, 0..16383)
/// [ 2: 0] reserved = 0
/// ```
pub(crate) fn enc_mark(label: i64) -> Result<u32, String> {
    if !(0..(1 << 14)).contains(&label) {
        return Err(format!(
            "E-RNG-001: MARK label must be 0..16383, got {label}"
        ));
    }
    Ok(opcode(G_CTRL, S_MARK) | ((label as u32) << 3))
}

/// CTRL.LOAD_TIMING (§5.17)
///
/// ```text
/// [31:30] group    = 0b01
/// [29:26] sub      = 0b0111
/// [25:20] reserved = 0
/// [19:17] reg      (3 bits: timing-register selector 0..7)
/// [16: 3] divider  (14 bits, 0..16383)
/// [ 2: 0] reserved = 0
/// ```
pub(crate) fn enc_load_timing(reg: i64, divider: i64) -> Result<u32, String> {
    if !(0..8).contains(&reg) {
        return Err(format!(
            "E-RNG-001: LOAD_TIMING reg must be 0..7, got {reg}"
        ));
    }
    if !(0..(1 << 14)).contains(&divider) {
        return Err(format!(
            "E-RNG-001: LOAD_TIMING divider must be 0..16383, got {divider}"
        ));
    }
    Ok(opcode(G_CTRL, S_LOAD_TIMING) | ((reg as u32) << 17) | ((divider as u32) << 3))
}

// -----------------------------------------------------------------------
// DATA group encoders
// -----------------------------------------------------------------------

/// DATA.LOAD_IMM (§5.18)
///
/// ```text
/// [31:30] group   = 0b10
/// [29:26] sub     = 0b0000
/// [25:23] dst     (3 bits)
/// [22:17] reserved = 0
/// [16: 3] imm14   (14 bits, unsigned, 0..16383)
/// [ 2: 0] reserved = 0
/// ```
pub(crate) fn enc_load_imm(dst: u8, imm14: i64) -> Result<u32, String> {
    check_reg("LOAD_IMM dst", dst)?;
    if !(0..(1 << 14)).contains(&imm14) {
        return Err(format!(
            "E-RNG-001: LOAD_IMM imm14 must be 0..16383, got {imm14}"
        ));
    }
    Ok(opcode(G_DATA, S_LOAD_IMM) | (u32::from(dst) << 23) | ((imm14 as u32) << 3))
}

/// DATA.MOV (§5.19)
///
/// ```text
/// [31:30] group   = 0b10
/// [29:26] sub     = 0b0001
/// [25:23] dst     (3 bits)
/// [22:20] src     (3 bits)
/// [19: 3] reserved = 0
/// [ 2: 0] reserved = 0
/// ```
pub(crate) fn enc_mov(dst: u8, src: u8) -> Result<u32, String> {
    check_reg("MOV dst", dst)?;
    check_reg("MOV src", src)?;
    Ok(opcode(G_DATA, S_MOV) | (u32::from(dst) << 23) | (u32::from(src) << 20))
}

/// DATA.ADD_IMM (§5.20)
///
/// ```text
/// [31:30] group   = 0b10
/// [29:26] sub     = 0b0010
/// [25:23] dst     (3 bits)
/// [22:20] src     (3 bits)
/// [19:17] reserved = 0
/// [16: 3] imm14   (14 bits, signed two's-complement, -8192..8191)
/// [ 2: 0] reserved = 0
/// ```
pub(crate) fn enc_add_imm(dst: u8, src: u8, imm14: i64) -> Result<u32, String> {
    check_reg("ADD_IMM dst", dst)?;
    check_reg("ADD_IMM src", src)?;
    if !(-8192..=8191).contains(&imm14) {
        return Err(format!(
            "E-RNG-001: ADD_IMM imm14 must be -8192..8191, got {imm14}"
        ));
    }
    let bits = (imm14 as i32 as u32) & 0x3FFF;
    Ok(opcode(G_DATA, S_ADD_IMM) | (u32::from(dst) << 23) | (u32::from(src) << 20) | (bits << 3))
}

/// DATA.DEC (§5.21)
///
/// ```text
/// [31:30] group   = 0b10
/// [29:26] sub     = 0b0011
/// [25:23] dst     (3 bits; same as src)
/// [22:20] src     (3 bits; same as dst)
/// [19: 3] reserved = 0
/// [ 2: 0] reserved = 0
/// ```
pub(crate) fn enc_dec(reg: u8) -> Result<u32, String> {
    check_reg("DEC", reg)?;
    Ok(opcode(G_DATA, S_DEC) | (u32::from(reg) << 23) | (u32::from(reg) << 20))
}

/// DATA.AND_IMM (§5.22)
///
/// ```text
/// [31:30] group   = 0b10
/// [29:26] sub     = 0b0100
/// [25:23] dst     (3 bits)
/// [22:20] src     (3 bits)
/// [19:17] reserved = 0
/// [16: 3] imm14   (14 bits, zero-extended)
/// [ 2: 0] reserved = 0
/// ```
pub(crate) fn enc_and_imm(dst: u8, src: u8, imm14: i64) -> Result<u32, String> {
    check_reg("AND_IMM dst", dst)?;
    check_reg("AND_IMM src", src)?;
    if !(0..(1 << 14)).contains(&imm14) {
        return Err(format!(
            "E-RNG-001: AND_IMM imm14 must be 0..16383, got {imm14}"
        ));
    }
    Ok(opcode(G_DATA, S_AND_IMM)
        | (u32::from(dst) << 23)
        | (u32::from(src) << 20)
        | ((imm14 as u32) << 3))
}

/// DATA.OR_IMM (§5.23)
///
/// Identical layout to AND_IMM with `sub = 0b0101`.
pub(crate) fn enc_or_imm(dst: u8, src: u8, imm14: i64) -> Result<u32, String> {
    check_reg("OR_IMM dst", dst)?;
    check_reg("OR_IMM src", src)?;
    if !(0..(1 << 14)).contains(&imm14) {
        return Err(format!(
            "E-RNG-001: OR_IMM imm14 must be 0..16383, got {imm14}"
        ));
    }
    Ok(opcode(G_DATA, S_OR_IMM)
        | (u32::from(dst) << 23)
        | (u32::from(src) << 20)
        | ((imm14 as u32) << 3))
}

/// DATA.XOR_IMM (§5.24)
///
/// Identical layout to AND_IMM with `sub = 0b0110`.
pub(crate) fn enc_xor_imm(dst: u8, src: u8, imm14: i64) -> Result<u32, String> {
    check_reg("XOR_IMM dst", dst)?;
    check_reg("XOR_IMM src", src)?;
    if !(0..(1 << 14)).contains(&imm14) {
        return Err(format!(
            "E-RNG-001: XOR_IMM imm14 must be 0..16383, got {imm14}"
        ));
    }
    Ok(opcode(G_DATA, S_XOR_IMM)
        | (u32::from(dst) << 23)
        | (u32::from(src) << 20)
        | ((imm14 as u32) << 3))
}

/// SHIFT direction codes.
pub(crate) const SHIFT_LEFT: u8 = 0; // arith=0, dir=0
pub(crate) const SHIFT_RIGHT: u8 = 1; // arith=0, dir=1
pub(crate) const SHIFT_ARIGHT: u8 = 2; // arith=1, dir=1

/// DATA.SHIFT (§5.25)
///
/// ```text
/// [31:30] group   = 0b10
/// [29:26] sub     = 0b0111
/// [25:23] dst     (3 bits)
/// [22:20] src     (3 bits)
/// [19]    arith   (1 bit: 0=logical, 1=arithmetic)
/// [18]    dir     (1 bit: 0=left, 1=right)
/// [17]    reserved = 0
/// [16: 8] reserved = 0
/// [ 7: 3] shamt   (5 bits: shift amount 0..31)
/// [ 2: 0] reserved = 0
/// ```
///
/// `shift_kind`: `SHIFT_LEFT=0`, `SHIFT_RIGHT=1`, `SHIFT_ARIGHT=2`.
pub(crate) fn enc_shift(dst: u8, src: u8, shift_kind: u8, shamt: i64) -> Result<u32, String> {
    check_reg("SHIFT dst", dst)?;
    check_reg("SHIFT src", src)?;
    if !(0..32).contains(&shamt) {
        return Err(format!("E-RNG-001: SHIFT shamt must be 0..31, got {shamt}"));
    }
    let (arith, dir) = match shift_kind {
        SHIFT_LEFT => (0u32, 0u32),
        SHIFT_RIGHT => (0u32, 1u32),
        SHIFT_ARIGHT => (1u32, 1u32),
        _ => {
            return Err(format!(
                "E-OP-002: unknown shift direction {shift_kind}; \
                 use left, right, or aright"
            ));
        }
    };
    Ok(opcode(G_DATA, S_SHIFT)
        | (u32::from(dst) << 23)
        | (u32::from(src) << 20)
        | (arith << 19)
        | (dir << 18)
        | ((shamt as u32) << 3))
}

// -----------------------------------------------------------------------
// Unit tests: one per §12.4 worked example + field-width range checks
// -----------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    // -------------------------------------------------------------------
    // §12.4 worked examples — each asserts the EXACT hex from the spec
    // -------------------------------------------------------------------

    #[test]
    fn spec_5_1_emit_bit_imm_tx_recessive() {
        // §5.1  EMIT_BIT_IMM  tx=recessive
        //   group=00 sub=0000, tx=0b01 at [4:3] → 0x08
        // word = 0x0000_0008
        assert_eq!(
            enc_emit_bit_imm(0b01, false, false, false, false).unwrap(),
            0x0000_0008
        );
    }

    #[test]
    fn spec_5_2_emit_bit_reg_src_r2_capture_1() {
        // §5.2  EMIT_BIT_REG  src=R2 capture=1
        //   group=00 sub=0001, src=0b010 at [22:20] → 0x0020_0000; flags=0b001
        // word = 0x0420_0001
        assert_eq!(
            enc_emit_bit_reg(2, false, false, true).unwrap(),
            0x0420_0001
        );
    }

    #[test]
    fn spec_5_3_emit_quarter_imm_sda_dominant_scl_hiz() {
        // §5.3  EMIT_QUARTER_IMM  sda=dominant scl=hiz
        //   group=00 sub=0010, scl=0b10(hiz) at [6:5]=0x40,
        //   sda=0b00(dom) at [4:3]=0
        // word = 0x0800_0040
        assert_eq!(
            enc_emit_quarter_imm(0b00, 0b10, false, false, false, false).unwrap(),
            0x0800_0040
        );
    }

    #[test]
    fn spec_5_4_emit_quarter_reg_src_r1() {
        // §5.4  EMIT_QUARTER_REG  src=R1
        //   group=00 sub=0011, src=0b001 at [22:20] → 0x0010_0000
        // word = 0x0C10_0000
        assert_eq!(
            enc_emit_quarter_reg(1, false, false, false).unwrap(),
            0x0C10_0000
        );
    }

    #[test]
    fn spec_5_5_emit_byte_reg_expect_0_mask_1_capture_1() {
        // §5.5  EMIT_BYTE_REG  expect=0 mask=1 capture=1
        //   group=00 sub=0100, flags=0b011=3
        // word = 0x1000_0003
        assert_eq!(enc_emit_byte_reg(false, true, true), 0x1000_0003);
    }

    #[test]
    fn spec_5_5b_emit_byte_imm_0x48_no_flags() {
        // §5.5b EMIT_BYTE_IMM  imm=0x48 (no flags)
        //   group=00 sub=1001 → top 6 bits = 001001 → 0x2400_0000
        //   imm=0x48 at [10:3] → 0x48<<3 = 0x240
        // word = 0x2400_0240
        assert_eq!(enc_emit_byte_imm(0x48, false, false, false), 0x2400_0240);
    }

    #[test]
    fn spec_5_5b_emit_byte_imm_with_flags() {
        // EMIT_BYTE_IMM imm=0xAB expect=1 mask=1 capture=0
        //   group=00 sub=1001 → 0x2400_0000
        //   imm=0xAB at [10:3] → 0xAB<<3 = 0x558
        //   flags = 0b110 = 6
        // word = 0x2400_055E
        assert_eq!(enc_emit_byte_imm(0xAB, true, true, false), 0x2400_055E);
    }

    #[test]
    fn spec_5_6_sample_bit_on_scl_capture_1() {
        // §5.6  SAMPLE_BIT_ON_SCL  capture=1
        //   group=00 sub=0101, dst=R7 at [25:23]=0b111 → 7<<23=0x0380_0000;
        //   flags=0b001
        // word = 0x1780_0001
        assert_eq!(
            enc_sample_bit_on_scl(7, false, false, true, false).unwrap(),
            0x1780_0001
        );
    }

    #[test]
    fn spec_5_7_drive_bit_on_scl_tx_dominant() {
        // §5.7  DRIVE_BIT_ON_SCL  tx=dominant
        //   group=00 sub=0110, tx=0b00(dom) at [4:3]=0
        // word = 0x1800_0000
        assert_eq!(
            enc_drive_bit_on_scl(0b00, false, false, false, false).unwrap(),
            0x1800_0000
        );
    }

    #[test]
    fn spec_5_8_stretch_scl_imm_400() {
        // §5.8  STRETCH_SCL_IMM  400
        //   group=00 sub=0111, n=400 at [16:3] → 400<<3=3200=0x0C80
        // word = 0x1C00_0C80
        assert_eq!(enc_stretch_scl_imm(400).unwrap(), 0x1C00_0C80);
    }

    #[test]
    fn spec_5_9_stretch_scl_reg_src_r0() {
        // §5.9  STRETCH_SCL_REG  src=R0
        //   group=00 sub=1000, src=0b000 at [22:20]=0
        // word = 0x2000_0000
        assert_eq!(enc_stretch_scl_reg(0).unwrap(), 0x2000_0000);
    }

    #[test]
    fn spec_5_10_halt_status_2() {
        // §5.10 HALT  status=2
        //   group=01 sub=0000, status=2 at [7:3] → 2<<3=0x10
        // word = 0x4000_0010
        assert_eq!(enc_halt(2).unwrap(), 0x4000_0010);
    }

    #[test]
    fn spec_5_11_branch_on_mismatch_loop_top_minus_4() {
        // §5.11 BRANCH_ON  MISMATCH, loop_top
        //   (assume loop_top is 4 words back: offset=-4)
        //   group=01 sub=0001
        //   cond=MISMATCH=1 at [16:13] → 1<<13 = 0x0000_2000
        //   offset=-4 at [12:3]: signed 10-bit of -4 = 0x3FC → 0x3FC<<3
        //                       = 0x1FE0
        //   word = 0x4400_0000 | 0x0000_2000 | 0x0000_1FE0 = 0x4400_3FE0
        assert_eq!(enc_branch_on(1, -4).unwrap(), 0x4400_3FE0);
    }

    #[test]
    fn spec_5_12_wait_on_sda_low_64() {
        // §5.12 WAIT_ON  SDA_LOW, 64
        //   group=01 sub=0010
        //   cond=SDA_LOW=5 at [16:13] → 5<<13 = 0x0000_A000
        //   timeout=64 at [12:3] → 64<<3 = 0x0000_0200
        //   word = 0x4800_0000 | 0x0000_A000 | 0x0000_0200 = 0x4800_A200
        assert_eq!(enc_wait_on(5, 64).unwrap(), 0x4800_A200);
    }

    #[test]
    fn spec_5_13_set_bus_mode_i3c_pp() {
        // §5.13 SET_BUS_MODE  i3c-PP
        //   group=01 sub=0011, mode=0b0010 at [6:3] → 2<<3=0x10
        // word = 0x4C00_0010
        // v0.2: i3c-PP wire value = 2 (NOT 6 as in v0!)
        assert_eq!(enc_set_bus_mode(2).unwrap(), 0x4C00_0010);
    }

    #[test]
    fn spec_5_14_set_role_target() {
        // §5.14 SET_ROLE  target
        //   group=01 sub=0100, role=1 at [3] → 1<<3=0x08
        // word = 0x5000_0008
        assert_eq!(enc_set_role(true), 0x5000_0008);
    }

    #[test]
    fn spec_5_15_flag_clear_0b00001() {
        // §5.15 FLAG_CLEAR  0b00001
        //   group=01 sub=0101, mask=0b00001 at [7:3] → 1<<3=0x08
        // word = 0x5400_0008
        assert_eq!(enc_flag_clear(0b00001).unwrap(), 0x5400_0008);
    }

    #[test]
    fn spec_5_16_mark_label_42() {
        // §5.16 MARK  label=42
        //   group=01 sub=0110, label=42 at [16:3] → 42<<3=336=0x0150
        // word = 0x5800_0150
        assert_eq!(enc_mark(42).unwrap(), 0x5800_0150);
    }

    #[test]
    fn spec_5_17_load_timing_reg_0_divider_480() {
        // §5.17 LOAD_TIMING  reg=0, divider=480
        //   group=01 sub=0111, reg=0 at [19:17]=0;
        //   divider=480 at [16:3] → 480<<3=3840=0x0F00
        // word = 0x5C00_0F00
        assert_eq!(enc_load_timing(0, 480).unwrap(), 0x5C00_0F00);
    }

    #[test]
    fn spec_5_18_load_imm_r3_100() {
        // §5.18 LOAD_IMM  R3, 100
        //   group=10 sub=0000, dst=3 at [25:23] → 3<<23=0x0180_0000
        //   imm=100 at [16:3] → 100<<3=0x320
        // word = 0x8180_0320
        assert_eq!(enc_load_imm(3, 100).unwrap(), 0x8180_0320);
    }

    #[test]
    fn spec_5_19_mov_r1_r0() {
        // §5.19 MOV  R1, R0
        //   group=10 sub=0001, dst=1 at [25:23] → 1<<23=0x0080_0000
        //   src=0 at [22:20]
        // word = 0x8480_0000
        assert_eq!(enc_mov(1, 0).unwrap(), 0x8480_0000);
    }

    #[test]
    fn spec_5_20_add_imm_r0_r0_4() {
        // §5.20 ADD_IMM  R0, R0, 4
        //   group=10 sub=0010, dst=0 src=0, imm=4 at [16:3] → 4<<3=0x20
        // word = 0x8800_0020
        assert_eq!(enc_add_imm(0, 0, 4).unwrap(), 0x8800_0020);
    }

    #[test]
    fn spec_5_21_dec_r6() {
        // §5.21 DEC  R6
        //   group=10 sub=0011, dst=6 at [25:23] → 6<<23=0x0300_0000
        //   src=6 at [22:20] → 6<<20=0x0060_0000
        // word = 0x8F60_0000
        assert_eq!(enc_dec(6).unwrap(), 0x8F60_0000);
    }

    #[test]
    fn spec_5_22_and_imm_r0_r0_0xff() {
        // §5.22 AND_IMM  R0, R0, 0xFF
        //   group=10 sub=0100, imm=255 at [16:3] → 255<<3=0x7F8
        // word = 0x9000_07F8
        assert_eq!(enc_and_imm(0, 0, 0xFF).unwrap(), 0x9000_07F8);
    }

    #[test]
    fn spec_5_23_or_imm_r0_r0_0x01() {
        // §5.23 OR_IMM  R0, R0, 0x01
        //   group=10 sub=0101, imm=1 at [16:3] → 1<<3=0x08
        // word = 0x9400_0008
        assert_eq!(enc_or_imm(0, 0, 0x01).unwrap(), 0x9400_0008);
    }

    #[test]
    fn spec_5_24_xor_imm_r0_r0_0xff() {
        // §5.24 XOR_IMM  R0, R0, 0xFF
        //   group=10 sub=0110, imm=255<<3=0x7F8
        // word = 0x9800_07F8
        assert_eq!(enc_xor_imm(0, 0, 0xFF).unwrap(), 0x9800_07F8);
    }

    #[test]
    fn spec_5_25_shift_r0_r0_left_1() {
        // §5.25 SHIFT  R0, R0, left, 1
        //   group=10 sub=0111, dst=0 src=0
        //   arith=0 at [19]=0; dir=0(left) at [18]=0; [17] reserved=0
        //   shamt=1 at [7:3] → 1<<3=0x08; [2:0] reserved=0
        // word = 0x9C00_0008
        assert_eq!(enc_shift(0, 0, SHIFT_LEFT, 1).unwrap(), 0x9C00_0008);
    }

    // -------------------------------------------------------------------
    // Additional coverage: field-width boundaries and error cases
    // -------------------------------------------------------------------

    #[test]
    fn halt_zero() {
        assert_eq!(enc_halt(0).unwrap(), 0x4000_0000);
    }

    #[test]
    fn halt_max_status_31() {
        // status=31 (0x1F) is the STATUS_TRAP value; still encodable.
        // 31 << 3 = 0xF8
        assert_eq!(enc_halt(31).unwrap(), 0x4000_00F8);
    }

    #[test]
    fn halt_overflow_rejected() {
        assert!(enc_halt(32).is_err());
        assert!(enc_halt(-1).is_err());
    }

    #[test]
    fn emit_bit_imm_reserved_rejected_without_raw() {
        assert!(enc_emit_bit_imm(0b11, false, false, false, false).is_err());
    }

    #[test]
    fn emit_bit_imm_reserved_allowed_with_raw() {
        assert!(enc_emit_bit_imm(0b11, false, false, false, true).is_ok());
    }

    #[test]
    fn branch_on_max_positive_offset() {
        // offset=+511 (max positive 10-bit signed)
        assert!(enc_branch_on(0, 511).is_ok());
        assert!(enc_branch_on(0, 512).is_err());
    }

    #[test]
    fn branch_on_max_negative_offset() {
        assert!(enc_branch_on(0, -512).is_ok());
        assert!(enc_branch_on(0, -513).is_err());
    }

    #[test]
    fn wait_on_max_timeout() {
        assert!(enc_wait_on(0, 1023).is_ok());
        assert!(enc_wait_on(0, 1024).is_err());
    }

    #[test]
    fn stretch_scl_imm_max() {
        assert!(enc_stretch_scl_imm(16383).is_ok());
        assert!(enc_stretch_scl_imm(16384).is_err());
    }

    #[test]
    fn load_timing_max_divider() {
        assert!(enc_load_timing(7, 16383).is_ok());
        assert!(enc_load_timing(0, 16384).is_err());
        assert!(enc_load_timing(8, 0).is_err());
    }

    #[test]
    fn load_imm_max_imm14() {
        assert!(enc_load_imm(0, 16383).is_ok());
        assert!(enc_load_imm(0, 16384).is_err());
        assert!(enc_load_imm(0, -1).is_err());
    }

    #[test]
    fn add_imm_signed_boundaries() {
        assert!(enc_add_imm(0, 0, 8191).is_ok());
        assert!(enc_add_imm(0, 0, 8192).is_err());
        assert!(enc_add_imm(0, 0, -8192).is_ok());
        assert!(enc_add_imm(0, 0, -8193).is_err());
    }

    #[test]
    fn shift_max_shamt() {
        assert!(enc_shift(0, 0, SHIFT_LEFT, 31).is_ok());
        assert!(enc_shift(0, 0, SHIFT_LEFT, 32).is_err());
    }

    #[test]
    fn sample_bit_on_scl_non_r7_rejected_without_raw() {
        assert!(enc_sample_bit_on_scl(3, false, false, true, false).is_err());
    }

    #[test]
    fn sample_bit_on_scl_non_r7_allowed_with_raw() {
        assert!(enc_sample_bit_on_scl(3, false, false, true, true).is_ok());
    }

    #[test]
    fn bus_mode_renumbering_v02() {
        // i2c=0, i3c-OD=1, i3c-PP=2, hdr-ddr=3 (NOT v0's 0,1,6,7)
        // i2c=0
        assert_eq!(enc_set_bus_mode(0).unwrap(), 0x4C00_0000);
        // i3c-OD=1: 1<<3 = 0x08
        assert_eq!(enc_set_bus_mode(1).unwrap(), 0x4C00_0008);
        // i3c-PP=2: 2<<3 = 0x10
        assert_eq!(enc_set_bus_mode(2).unwrap(), 0x4C00_0010);
        // hdr-ddr=3: 3<<3 = 0x18
        assert_eq!(enc_set_bus_mode(3).unwrap(), 0x4C00_0018);
    }

    #[test]
    fn set_role_controller() {
        // role=0 (controller): bit 3 = 0
        assert_eq!(enc_set_role(false), 0x5000_0000);
    }

    #[test]
    fn set_role_target() {
        // role=1 (target): 1<<3 = 0x08
        assert_eq!(enc_set_role(true), 0x5000_0008);
    }

    #[test]
    fn flag_clear_all_bits() {
        // mask=0b11111 (31): 31<<3 = 0xF8
        assert_eq!(enc_flag_clear(31).unwrap(), 0x5400_00F8);
        assert!(enc_flag_clear(32).is_err());
    }

    #[test]
    fn mark_max_label() {
        assert!(enc_mark(16383).is_ok());
        assert!(enc_mark(16384).is_err());
    }

    #[test]
    fn dec_r0_field_check() {
        // DEC R0: dst=0, src=0 → group=10 sub=0011, both zero
        // opcode = 0b10_0011 at [31:26] = 0x8C00_0000
        assert_eq!(enc_dec(0).unwrap(), 0x8C00_0000);
    }

    #[test]
    fn emit_byte_reg_no_flags() {
        // EMIT_BYTE_REG (no flags): group=00 sub=0100 → 0x1000_0000
        assert_eq!(enc_emit_byte_reg(false, false, false), 0x1000_0000);
    }

    #[test]
    fn emit_byte_imm_zero_payload_no_flags() {
        // EMIT_BYTE_IMM imm=0 (no flags): group=00 sub=1001 → 0x2400_0000
        assert_eq!(enc_emit_byte_imm(0, false, false, false), 0x2400_0000);
    }

    #[test]
    fn emit_byte_imm_full_payload_all_flags() {
        // EMIT_BYTE_IMM imm=0xFF expect=1 mask=1 capture=1
        //   group=00 sub=1001 → 0x2400_0000
        //   imm=0xFF<<3 = 0x7F8
        //   flags = 0b111 = 7
        // word = 0x2400_07FF
        assert_eq!(enc_emit_byte_imm(0xFF, true, true, true), 0x2400_07FF);
    }
}
