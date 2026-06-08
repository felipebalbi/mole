//! Decoder: 32-bit instruction word → human-readable mnemonic text.
//!
//! Used by both `inspect` (first/last word summary) and `disassemble`
//! (per-instruction output). The decoder mirrors the per-opcode
//! encoding tables in `docs/MOLE-0.2-SPEC.md` §5.
//!
//! The decoder is intentionally lenient: it never panics or returns an
//! error on malformed input. Unknown or reserved encodings produce a
//! `.dw 0x…  ; <reason>` line so the disassembler can continue and set
//! the "had unknowns" flag at the end.

// -----------------------------------------------------------------------
// Group / sub constants (mirrors encoder.rs)
// -----------------------------------------------------------------------

const G_WIRE: u32 = 0b00;
const G_CTRL: u32 = 0b01;
const G_DATA: u32 = 0b10;

// WIRE sub-opcodes
const S_EMIT_BIT_IMM: u32 = 0b0000;
const S_EMIT_BIT_REG: u32 = 0b0001;
const S_EMIT_QUARTER_IMM: u32 = 0b0010;
const S_EMIT_QUARTER_REG: u32 = 0b0011;
const S_EMIT_BYTE: u32 = 0b0100;
const S_SAMPLE_BIT_ON_SCL: u32 = 0b0101;
const S_DRIVE_BIT_ON_SCL: u32 = 0b0110;
const S_STRETCH_SCL_IMM: u32 = 0b0111;
const S_STRETCH_SCL_REG: u32 = 0b1000;

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
// Reverse-lookup helpers
// -----------------------------------------------------------------------

fn tx_name(tx: u32) -> &'static str {
    match tx {
        0b00 => "dominant",
        0b01 => "recessive",
        0b10 => "hiz",
        0b11 => "reserved",
        _ => "?",
    }
}

fn bus_mode_name(mode: u32) -> &'static str {
    match mode {
        0 => "i2c",
        1 => "i3c-OD",
        2 => "i3c-PP",
        3 => "hdr-ddr",
        _ => "reserved",
    }
}

fn cond_name(cond: u32) -> &'static str {
    match cond {
        0 => "ALWAYS",
        1 => "MISMATCH",
        2 => "NOT_MISMATCH",
        3 => "START_SEEN",
        4 => "STOP_SEEN",
        5 => "SDA_LOW",
        6 => "SDA_HIGH",
        7 => "SCL_HIGH",
        8 => "TIMEOUT",
        9 => "NOT_TIMEOUT",
        10 => "REG_ZERO",
        11 => "NOT_REG_ZERO",
        12..=15 => "reserved",
        _ => "?",
    }
}

fn shift_dir_name(arith: bool, dir: bool) -> &'static str {
    match (arith, dir) {
        (false, false) => "left",
        (false, true) => "right",
        (true, true) => "aright",
        (true, false) => "aleft(invalid)",
    }
}

/// Format flag triple as operand suffix.
///
/// Only non-default values are emitted:
/// - `expect` is omitted when `mask=0` (don't-care).
/// - `mask=0` is omitted (default).
/// - `capture=0` is omitted (default).
fn fmt_flags(word: u32) -> String {
    let expect = (word >> 2) & 1;
    let mask = (word >> 1) & 1;
    let capture = word & 1;

    let mut parts = Vec::new();
    if mask != 0 {
        parts.push(format!("expect={expect}"));
        parts.push("mask=1".to_string());
    }
    if capture != 0 {
        parts.push("capture=1".to_string());
    }
    if parts.is_empty() {
        String::new()
    } else {
        format!(" {}", parts.join(" "))
    }
}

// -----------------------------------------------------------------------
// Public API
// -----------------------------------------------------------------------

/// The result of decoding a single 32-bit instruction word.
pub struct Decoded {
    /// Human-readable mnemonic + operands, e.g. `HALT status=0`.
    pub text: String,
    /// True when the word encodes a known opcode cleanly. False for
    /// `.dw` fallback (reserved group, reserved sub-code, or a
    /// reserved-field violation caught by the decoder).
    pub known: bool,
}

/// Decode one 32-bit instruction word into a text representation.
///
/// Never fails: unknown/reserved encodings produce a `.dw 0x…` line
/// with an explanatory comment, and `known=false`.
pub fn decode_word(word: u32) -> Decoded {
    let group = (word >> 30) & 0x3;
    let sub = (word >> 26) & 0xF;

    match group {
        g if g == G_WIRE => decode_wire(word, sub),
        g if g == G_CTRL => decode_ctrl(word, sub),
        g if g == G_DATA => decode_data(word, sub),
        _ => {
            // group=0b11 is the LOOP group — entirely reserved (§4.4).
            Decoded {
                text: format!(".dw 0x{word:08X}  ; reserved LOOP group (group=0b11)"),
                known: false,
            }
        }
    }
}

// -----------------------------------------------------------------------
// WIRE group
// -----------------------------------------------------------------------

fn decode_wire(word: u32, sub: u32) -> Decoded {
    match sub {
        S_EMIT_BIT_IMM => {
            // [4:3] tx, [2:0] flags
            let tx = (word >> 3) & 0x3;
            let flags = fmt_flags(word);
            Decoded {
                text: format!("EMIT_BIT_IMM tx={}{flags}", tx_name(tx)),
                known: true,
            }
        }
        S_EMIT_BIT_REG => {
            // [22:20] src, [2:0] flags
            let src = (word >> 20) & 0x7;
            let flags = fmt_flags(word);
            Decoded {
                text: format!("EMIT_BIT_REG src=R{src}{flags}"),
                known: true,
            }
        }
        S_EMIT_QUARTER_IMM => {
            // [6:5] scl, [4:3] sda, [2:0] flags
            let scl = (word >> 5) & 0x3;
            let sda = (word >> 3) & 0x3;
            let flags = fmt_flags(word);
            Decoded {
                text: format!(
                    "EMIT_QUARTER_IMM sda={} scl={}{}",
                    tx_name(sda),
                    tx_name(scl),
                    flags
                ),
                known: true,
            }
        }
        S_EMIT_QUARTER_REG => {
            // [22:20] src, [2:0] flags
            let src = (word >> 20) & 0x7;
            let flags = fmt_flags(word);
            Decoded {
                text: format!("EMIT_QUARTER_REG src=R{src}{flags}"),
                known: true,
            }
        }
        S_EMIT_BYTE => {
            // [2:0] flags only
            let flags = fmt_flags(word);
            let text = if flags.is_empty() {
                "EMIT_BYTE".to_string()
            } else {
                format!("EMIT_BYTE{flags}")
            };
            Decoded { text, known: true }
        }
        S_SAMPLE_BIT_ON_SCL => {
            // [25:23] dst, [2:0] flags
            let dst = (word >> 23) & 0x7;
            let flags = fmt_flags(word);
            // Canonical: dst=R7; note if unusual.
            let dst_note = if dst == 7 {
                String::new()
            } else {
                format!(" ; dst=R{dst} (non-canonical)")
            };
            Decoded {
                text: format!("SAMPLE_BIT_ON_SCL{flags}{dst_note}"),
                known: true,
            }
        }
        S_DRIVE_BIT_ON_SCL => {
            // [25:23] dst, [4:3] tx, [2:0] flags
            let tx = (word >> 3) & 0x3;
            let flags = fmt_flags(word);
            Decoded {
                text: format!("DRIVE_BIT_ON_SCL tx={}{flags}", tx_name(tx)),
                known: true,
            }
        }
        S_STRETCH_SCL_IMM => {
            // [16:3] n_quarters
            let n = (word >> 3) & 0x3FFF;
            Decoded {
                text: format!("STRETCH_SCL_IMM {n}"),
                known: true,
            }
        }
        S_STRETCH_SCL_REG => {
            // [22:20] src
            let src = (word >> 20) & 0x7;
            Decoded {
                text: format!("STRETCH_SCL_REG src=R{src}"),
                known: true,
            }
        }
        _ => {
            // sub >= 0b1001 in WIRE group: reserved
            Decoded {
                text: format!(".dw 0x{word:08X}  ; reserved WIRE sub={sub:#06b}"),
                known: false,
            }
        }
    }
}

// -----------------------------------------------------------------------
// CTRL group
// -----------------------------------------------------------------------

fn decode_ctrl(word: u32, sub: u32) -> Decoded {
    match sub {
        S_HALT => {
            // [7:3] status
            let status = (word >> 3) & 0x1F;
            Decoded {
                text: format!("HALT status={status}"),
                known: true,
            }
        }
        S_BRANCH_ON => {
            // [16:13] cond, [12:3] offset (signed 10-bit)
            let cond = (word >> 13) & 0xF;
            let raw_off = (word >> 3) & 0x3FF;
            // Sign-extend 10-bit value
            let offset = sign_extend_10(raw_off);
            let cname = cond_name(cond);
            Decoded {
                text: format!("BRANCH_ON {cname}, {offset}"),
                known: true,
            }
        }
        S_WAIT_ON => {
            // [16:13] cond, [12:3] timeout (unsigned 10-bit)
            let cond = (word >> 13) & 0xF;
            let timeout = (word >> 3) & 0x3FF;
            let cname = cond_name(cond);
            Decoded {
                text: format!("WAIT_ON {cname}, {timeout}"),
                known: true,
            }
        }
        S_SET_BUS_MODE => {
            // [6:3] mode
            let mode = (word >> 3) & 0xF;
            Decoded {
                text: format!("SET_BUS_MODE {}", bus_mode_name(mode)),
                known: true,
            }
        }
        S_SET_ROLE => {
            // [3] role
            let role = (word >> 3) & 0x1;
            let rname = if role == 0 { "controller" } else { "target" };
            Decoded {
                text: format!("SET_ROLE {rname}"),
                known: true,
            }
        }
        S_FLAG_CLEAR => {
            // [7:3] mask
            let mask = (word >> 3) & 0x1F;
            Decoded {
                text: format!("FLAG_CLEAR 0b{mask:05b}"),
                known: true,
            }
        }
        S_MARK => {
            // [16:3] label
            let label = (word >> 3) & 0x3FFF;
            Decoded {
                text: format!("MARK label={label}"),
                known: true,
            }
        }
        S_LOAD_TIMING => {
            // [19:17] reg, [16:3] divider
            let reg = (word >> 17) & 0x7;
            let divider = (word >> 3) & 0x3FFF;
            Decoded {
                text: format!("LOAD_TIMING reg={reg}, divider={divider}"),
                known: true,
            }
        }
        _ => {
            // sub >= 0b1000 in CTRL group: reserved
            Decoded {
                text: format!(".dw 0x{word:08X}  ; reserved CTRL sub={sub:#06b}"),
                known: false,
            }
        }
    }
}

// -----------------------------------------------------------------------
// DATA group
// -----------------------------------------------------------------------

fn decode_data(word: u32, sub: u32) -> Decoded {
    match sub {
        S_LOAD_IMM => {
            // [25:23] dst, [16:3] imm14
            let dst = (word >> 23) & 0x7;
            let imm = (word >> 3) & 0x3FFF;
            Decoded {
                text: format!("LOAD_IMM R{dst}, {imm}"),
                known: true,
            }
        }
        S_MOV => {
            // [25:23] dst, [22:20] src
            let dst = (word >> 23) & 0x7;
            let src = (word >> 20) & 0x7;
            Decoded {
                text: format!("MOV R{dst}, R{src}"),
                known: true,
            }
        }
        S_ADD_IMM => {
            // [25:23] dst, [22:20] src, [16:3] imm14 signed
            let dst = (word >> 23) & 0x7;
            let src = (word >> 20) & 0x7;
            let raw_imm = (word >> 3) & 0x3FFF;
            let imm = sign_extend_14(raw_imm);
            Decoded {
                text: format!("ADD_IMM R{dst}, R{src}, {imm}"),
                known: true,
            }
        }
        S_DEC => {
            // [25:23] dst (= src)
            let dst = (word >> 23) & 0x7;
            Decoded {
                text: format!("DEC R{dst}"),
                known: true,
            }
        }
        S_AND_IMM => {
            let dst = (word >> 23) & 0x7;
            let src = (word >> 20) & 0x7;
            let imm = (word >> 3) & 0x3FFF;
            Decoded {
                text: format!("AND_IMM R{dst}, R{src}, {imm}"),
                known: true,
            }
        }
        S_OR_IMM => {
            let dst = (word >> 23) & 0x7;
            let src = (word >> 20) & 0x7;
            let imm = (word >> 3) & 0x3FFF;
            Decoded {
                text: format!("OR_IMM R{dst}, R{src}, {imm}"),
                known: true,
            }
        }
        S_XOR_IMM => {
            let dst = (word >> 23) & 0x7;
            let src = (word >> 20) & 0x7;
            let imm = (word >> 3) & 0x3FFF;
            Decoded {
                text: format!("XOR_IMM R{dst}, R{src}, {imm}"),
                known: true,
            }
        }
        S_SHIFT => {
            // [25:23] dst, [22:20] src, [19] arith, [18] dir, [7:3] shamt
            let dst = (word >> 23) & 0x7;
            let src = (word >> 20) & 0x7;
            let arith = (word >> 19) & 0x1 != 0;
            let dir = (word >> 18) & 0x1 != 0;
            let shamt = (word >> 3) & 0x1F;
            let dname = shift_dir_name(arith, dir);
            Decoded {
                text: format!("SHIFT R{dst}, R{src}, {dname}, {shamt}"),
                known: true,
            }
        }
        _ => {
            // sub >= 0b1000 in DATA group: reserved
            Decoded {
                text: format!(".dw 0x{word:08X}  ; reserved DATA sub={sub:#06b}"),
                known: false,
            }
        }
    }
}

// -----------------------------------------------------------------------
// Arithmetic helpers
// -----------------------------------------------------------------------

/// Sign-extend a 10-bit value to i32.
fn sign_extend_10(v: u32) -> i32 {
    let v = v & 0x3FF;
    if v & 0x200 != 0 {
        // Negative: fill upper bits
        (v | 0xFFFF_FC00) as i32
    } else {
        v as i32
    }
}

/// Sign-extend a 14-bit value to i32.
fn sign_extend_14(v: u32) -> i32 {
    let v = v & 0x3FFF;
    if v & 0x2000 != 0 {
        (v | 0xFFFF_C000) as i32
    } else {
        v as i32
    }
}

// -----------------------------------------------------------------------
// Unit tests — one per live opcode (§12.4 worked examples)
// -----------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    /// Helper: assemble a single-instruction program and return the body word.
    fn asm_body(src: &str) -> u32 {
        let words = mole_asm::assemble(src, "<test>").unwrap();
        // words[0] = magic, words[1] = length, words[2] = instruction
        assert_eq!(words.len(), 3, "expected single body word for: {src}");
        words[2]
    }

    // §5.1 EMIT_BIT_IMM
    #[test]
    fn decode_emit_bit_imm_recessive() {
        let w = asm_body("EMIT_BIT_IMM tx=recessive\n");
        assert_eq!(w, 0x0000_0008);
        let d = decode_word(w);
        assert!(d.known);
        assert!(d.text.contains("EMIT_BIT_IMM"), "{}", d.text);
        assert!(d.text.contains("recessive"), "{}", d.text);
    }

    // §5.1 with flags
    #[test]
    fn decode_emit_bit_imm_with_flags() {
        let w = asm_body("EMIT_BIT_IMM tx=dominant expect=0 mask=1 capture=1\n");
        let d = decode_word(w);
        assert!(d.known);
        assert!(d.text.contains("dominant"), "{}", d.text);
        assert!(d.text.contains("mask=1"), "{}", d.text);
        assert!(d.text.contains("capture=1"), "{}", d.text);
    }

    // §5.2 EMIT_BIT_REG
    #[test]
    fn decode_emit_bit_reg() {
        let w = asm_body("EMIT_BIT_REG src=R2 capture=1\n");
        assert_eq!(w, 0x0420_0001);
        let d = decode_word(w);
        assert!(d.known);
        assert!(d.text.contains("EMIT_BIT_REG"), "{}", d.text);
        assert!(d.text.contains("R2"), "{}", d.text);
        assert!(d.text.contains("capture=1"), "{}", d.text);
    }

    // §5.3 EMIT_QUARTER_IMM
    #[test]
    fn decode_emit_quarter_imm() {
        let w = asm_body("EMIT_QUARTER_IMM sda=dominant scl=hiz\n");
        assert_eq!(w, 0x0800_0040);
        let d = decode_word(w);
        assert!(d.known);
        assert!(d.text.contains("EMIT_QUARTER_IMM"), "{}", d.text);
        assert!(d.text.contains("dominant"), "{}", d.text);
        assert!(d.text.contains("hiz"), "{}", d.text);
    }

    // §5.4 EMIT_QUARTER_REG
    #[test]
    fn decode_emit_quarter_reg() {
        let w = asm_body("EMIT_QUARTER_REG src=R1\n");
        assert_eq!(w, 0x0C10_0000);
        let d = decode_word(w);
        assert!(d.known);
        assert!(d.text.contains("EMIT_QUARTER_REG"), "{}", d.text);
        assert!(d.text.contains("R1"), "{}", d.text);
    }

    // §5.5 EMIT_BYTE with flags — the assembler requires a paired
    // BRANCH_ON MISMATCH when mask=1, so we supply one.  We verify
    // the body word at index 2 (the EMIT_BYTE itself).
    #[test]
    fn decode_emit_byte() {
        // Pair EMIT_BYTE mask=1 with BRANCH_ON MISMATCH per E-WIRE-003.
        let src = "EMIT_BYTE expect=0 mask=1 capture=1\nBRANCH_ON MISMATCH, 0\n";
        let words = mole_asm::assemble(src, "<test>").unwrap();
        // words[0]=magic, [1]=length, [2]=EMIT_BYTE, [3]=BRANCH_ON
        let w = words[2];
        assert_eq!(w, 0x1000_0003);
        let d = decode_word(w);
        assert!(d.known);
        assert!(d.text.contains("EMIT_BYTE"), "{}", d.text);
        assert!(d.text.contains("mask=1"), "{}", d.text);
    }

    // §5.5 EMIT_BYTE no flags
    #[test]
    fn decode_emit_byte_no_flags() {
        let w = asm_body("EMIT_BYTE\n");
        assert_eq!(w, 0x1000_0000);
        let d = decode_word(w);
        assert!(d.known);
        assert_eq!(d.text.trim(), "EMIT_BYTE");
    }

    // §5.6 SAMPLE_BIT_ON_SCL
    #[test]
    fn decode_sample_bit_on_scl() {
        let w = asm_body("SAMPLE_BIT_ON_SCL capture=1\n");
        assert_eq!(w, 0x1780_0001);
        let d = decode_word(w);
        assert!(d.known);
        assert!(d.text.contains("SAMPLE_BIT_ON_SCL"), "{}", d.text);
        assert!(d.text.contains("capture=1"), "{}", d.text);
    }

    // §5.7 DRIVE_BIT_ON_SCL
    #[test]
    fn decode_drive_bit_on_scl() {
        let w = asm_body("DRIVE_BIT_ON_SCL tx=dominant\n");
        assert_eq!(w, 0x1800_0000);
        let d = decode_word(w);
        assert!(d.known);
        assert!(d.text.contains("DRIVE_BIT_ON_SCL"), "{}", d.text);
        assert!(d.text.contains("dominant"), "{}", d.text);
    }

    // §5.8 STRETCH_SCL_IMM
    #[test]
    fn decode_stretch_scl_imm() {
        let w = asm_body("STRETCH_SCL_IMM 400\n");
        assert_eq!(w, 0x1C00_0C80);
        let d = decode_word(w);
        assert!(d.known);
        assert!(d.text.contains("STRETCH_SCL_IMM"), "{}", d.text);
        assert!(d.text.contains("400"), "{}", d.text);
    }

    // §5.9 STRETCH_SCL_REG
    #[test]
    fn decode_stretch_scl_reg() {
        let w = asm_body("STRETCH_SCL_REG src=R0\n");
        assert_eq!(w, 0x2000_0000);
        let d = decode_word(w);
        assert!(d.known);
        assert!(d.text.contains("STRETCH_SCL_REG"), "{}", d.text);
        assert!(d.text.contains("R0"), "{}", d.text);
    }

    // §5.10 HALT
    #[test]
    fn decode_halt() {
        let w = asm_body("HALT status=2\n");
        assert_eq!(w, 0x4000_0010);
        let d = decode_word(w);
        assert!(d.known);
        assert!(d.text.contains("HALT"), "{}", d.text);
        assert!(d.text.contains("2"), "{}", d.text);
    }

    #[test]
    fn decode_halt_status_zero() {
        let w = asm_body("HALT status=0\n");
        assert_eq!(w, 0x4000_0000);
        let d = decode_word(w);
        assert!(d.known);
        assert_eq!(d.text, "HALT status=0");
    }

    // §5.11 BRANCH_ON
    #[test]
    fn decode_branch_on_mismatch_neg4() {
        // BRANCH_ON MISMATCH, -4  (raw word from encoder test)
        let w = 0x4400_3FE0_u32;
        let d = decode_word(w);
        assert!(d.known);
        assert!(d.text.contains("BRANCH_ON"), "{}", d.text);
        assert!(d.text.contains("MISMATCH"), "{}", d.text);
        assert!(d.text.contains("-4"), "{}", d.text);
    }

    // §5.12 WAIT_ON
    #[test]
    fn decode_wait_on_sda_low_64() {
        let w = 0x4800_A200_u32;
        let d = decode_word(w);
        assert!(d.known);
        assert!(d.text.contains("WAIT_ON"), "{}", d.text);
        assert!(d.text.contains("SDA_LOW"), "{}", d.text);
        assert!(d.text.contains("64"), "{}", d.text);
    }

    // §5.13 SET_BUS_MODE
    #[test]
    fn decode_set_bus_mode_i3c_pp() {
        let w = asm_body("SET_BUS_MODE i3c-PP\n");
        assert_eq!(w, 0x4C00_0010);
        let d = decode_word(w);
        assert!(d.known);
        assert!(d.text.contains("SET_BUS_MODE"), "{}", d.text);
        assert!(d.text.contains("i3c-PP"), "{}", d.text);
    }

    // §5.14 SET_ROLE
    #[test]
    fn decode_set_role_target() {
        let w = asm_body("SET_ROLE target\n");
        assert_eq!(w, 0x5000_0008);
        let d = decode_word(w);
        assert!(d.known);
        assert!(d.text.contains("SET_ROLE"), "{}", d.text);
        assert!(d.text.contains("target"), "{}", d.text);
    }

    // §5.15 FLAG_CLEAR
    #[test]
    fn decode_flag_clear() {
        let w = asm_body("FLAG_CLEAR 0b00001\n");
        assert_eq!(w, 0x5400_0008);
        let d = decode_word(w);
        assert!(d.known);
        assert!(d.text.contains("FLAG_CLEAR"), "{}", d.text);
    }

    // §5.16 MARK
    #[test]
    fn decode_mark_label_42() {
        let w = asm_body("MARK label=42\n");
        assert_eq!(w, 0x5800_0150);
        let d = decode_word(w);
        assert!(d.known);
        assert!(d.text.contains("MARK"), "{}", d.text);
        assert!(d.text.contains("42"), "{}", d.text);
    }

    // §5.17 LOAD_TIMING
    #[test]
    fn decode_load_timing() {
        let w = asm_body("LOAD_TIMING reg=0, divider=480\n");
        assert_eq!(w, 0x5C00_0F00);
        let d = decode_word(w);
        assert!(d.known);
        assert!(d.text.contains("LOAD_TIMING"), "{}", d.text);
        assert!(d.text.contains("480"), "{}", d.text);
    }

    // §5.18 LOAD_IMM
    #[test]
    fn decode_load_imm() {
        let w = asm_body("LOAD_IMM R3, 100\n");
        assert_eq!(w, 0x8180_0320);
        let d = decode_word(w);
        assert!(d.known);
        assert!(d.text.contains("LOAD_IMM"), "{}", d.text);
        assert!(d.text.contains("R3"), "{}", d.text);
        assert!(d.text.contains("100"), "{}", d.text);
    }

    // §5.19 MOV
    #[test]
    fn decode_mov() {
        let w = asm_body("MOV R1, R0\n");
        assert_eq!(w, 0x8480_0000);
        let d = decode_word(w);
        assert!(d.known);
        assert!(d.text.contains("MOV"), "{}", d.text);
        assert!(d.text.contains("R1"), "{}", d.text);
        assert!(d.text.contains("R0"), "{}", d.text);
    }

    // §5.20 ADD_IMM
    #[test]
    fn decode_add_imm() {
        let w = asm_body("ADD_IMM R0, R0, 4\n");
        assert_eq!(w, 0x8800_0020);
        let d = decode_word(w);
        assert!(d.known);
        assert!(d.text.contains("ADD_IMM"), "{}", d.text);
        assert!(d.text.contains("4"), "{}", d.text);
    }

    // §5.20 ADD_IMM negative immediate
    #[test]
    fn decode_add_imm_negative() {
        let w = asm_body("ADD_IMM R1, R2, -8\n");
        let d = decode_word(w);
        assert!(d.known);
        assert!(d.text.contains("-8"), "{}", d.text);
    }

    // §5.21 DEC
    #[test]
    fn decode_dec() {
        let w = asm_body("DEC R6\n");
        assert_eq!(w, 0x8F60_0000);
        let d = decode_word(w);
        assert!(d.known);
        assert!(d.text.contains("DEC"), "{}", d.text);
        assert!(d.text.contains("R6"), "{}", d.text);
    }

    // §5.22 AND_IMM
    #[test]
    fn decode_and_imm() {
        let w = asm_body("AND_IMM R0, R0, 0xFF\n");
        assert_eq!(w, 0x9000_07F8);
        let d = decode_word(w);
        assert!(d.known);
        assert!(d.text.contains("AND_IMM"), "{}", d.text);
        assert!(d.text.contains("255"), "{}", d.text);
    }

    // §5.23 OR_IMM
    #[test]
    fn decode_or_imm() {
        let w = asm_body("OR_IMM R0, R0, 0x01\n");
        assert_eq!(w, 0x9400_0008);
        let d = decode_word(w);
        assert!(d.known);
        assert!(d.text.contains("OR_IMM"), "{}", d.text);
    }

    // §5.24 XOR_IMM
    #[test]
    fn decode_xor_imm() {
        let w = asm_body("XOR_IMM R0, R0, 0xFF\n");
        assert_eq!(w, 0x9800_07F8);
        let d = decode_word(w);
        assert!(d.known);
        assert!(d.text.contains("XOR_IMM"), "{}", d.text);
    }

    // §5.25 SHIFT
    #[test]
    fn decode_shift_left() {
        let w = asm_body("SHIFT R0, R0, left, 1\n");
        assert_eq!(w, 0x9C00_0008);
        let d = decode_word(w);
        assert!(d.known);
        assert!(d.text.contains("SHIFT"), "{}", d.text);
        assert!(d.text.contains("left"), "{}", d.text);
        assert!(d.text.contains("1"), "{}", d.text);
    }

    #[test]
    fn decode_shift_right() {
        let w = asm_body("SHIFT R1, R2, right, 4\n");
        let d = decode_word(w);
        assert!(d.known);
        assert!(d.text.contains("right"), "{}", d.text);
        assert!(d.text.contains("4"), "{}", d.text);
    }

    #[test]
    fn decode_shift_aright() {
        let w = asm_body("SHIFT R3, R3, aright, 8\n");
        let d = decode_word(w);
        assert!(d.known);
        assert!(d.text.contains("aright"), "{}", d.text);
        assert!(d.text.contains("8"), "{}", d.text);
    }

    // Reserved group → .dw
    #[test]
    fn decode_reserved_loop_group() {
        let w = 0xC000_0000_u32; // group=0b11
        let d = decode_word(w);
        assert!(!d.known);
        assert!(d.text.starts_with(".dw"), "{}", d.text);
        assert!(d.text.contains("LOOP"), "{}", d.text);
    }

    // Reserved WIRE sub → .dw
    #[test]
    fn decode_reserved_wire_sub() {
        // group=00 sub=0b1111 (=15, reserved)
        let w = 0x3C00_0000_u32;
        let d = decode_word(w);
        assert!(!d.known);
        assert!(d.text.starts_with(".dw"), "{}", d.text);
    }

    // Sign-extend helpers
    #[test]
    fn sign_extend_10_positive() {
        assert_eq!(sign_extend_10(0x1FF), 511); // max positive
        assert_eq!(sign_extend_10(0), 0);
    }

    #[test]
    fn sign_extend_10_negative() {
        assert_eq!(sign_extend_10(0x3FC), -4); // -4 as 10-bit
        assert_eq!(sign_extend_10(0x200), -512); // min negative
    }

    #[test]
    fn sign_extend_14_positive() {
        assert_eq!(sign_extend_14(0x1FFF), 8191);
    }

    #[test]
    fn sign_extend_14_negative() {
        // -8 as 14-bit two's-complement
        let neg8: u32 = ((-8_i16) as u16 as u32) & 0x3FFF;
        assert_eq!(sign_extend_14(neg8), -8);
    }

    // Round-trip test: assemble → decode → verify mnemonic name preserved
    #[test]
    fn roundtrip_all_25_opcodes() {
        let programs = [
            ("EMIT_BIT_IMM", "EMIT_BIT_IMM tx=dominant\n"),
            ("EMIT_BIT_REG", "EMIT_BIT_REG src=R0\n"),
            (
                "EMIT_QUARTER_IMM",
                "EMIT_QUARTER_IMM sda=recessive scl=recessive\n",
            ),
            ("EMIT_QUARTER_REG", "EMIT_QUARTER_REG src=R1\n"),
            ("EMIT_BYTE", "EMIT_BYTE\n"),
            ("SAMPLE_BIT_ON_SCL", "SAMPLE_BIT_ON_SCL capture=1\n"),
            ("DRIVE_BIT_ON_SCL", "DRIVE_BIT_ON_SCL tx=hiz\n"),
            ("STRETCH_SCL_IMM", "STRETCH_SCL_IMM 4\n"),
            ("STRETCH_SCL_REG", "STRETCH_SCL_REG src=R2\n"),
            ("HALT", "HALT status=0\n"),
            ("BRANCH_ON", "BRANCH_ON ALWAYS, 0\n"),
            ("WAIT_ON", "WAIT_ON SCL_HIGH, 0\n"),
            ("SET_BUS_MODE", "SET_BUS_MODE i2c\n"),
            ("SET_ROLE", "SET_ROLE controller\n"),
            ("FLAG_CLEAR", "FLAG_CLEAR 0b11111\n"),
            ("MARK", "MARK label=0\n"),
            ("LOAD_TIMING", "LOAD_TIMING reg=0, divider=0\n"),
            ("LOAD_IMM", "LOAD_IMM R0, 0\n"),
            ("MOV", "MOV R0, R0\n"),
            ("ADD_IMM", "ADD_IMM R0, R0, 0\n"),
            ("DEC", "DEC R0\n"),
            ("AND_IMM", "AND_IMM R0, R0, 0\n"),
            ("OR_IMM", "OR_IMM R0, R0, 0\n"),
            ("XOR_IMM", "XOR_IMM R0, R0, 0\n"),
            ("SHIFT", "SHIFT R0, R0, left, 0\n"),
        ];
        for (mne, src) in &programs {
            let w = asm_body(src);
            let d = decode_word(w);
            assert!(
                d.known,
                "opcode {mne} decoded as unknown: word=0x{w:08X} text={}",
                d.text
            );
            assert!(
                d.text.contains(mne),
                "opcode {mne}: mnemonic not in decoded text '{}' (word=0x{w:08X})",
                d.text
            );
        }
    }
}
