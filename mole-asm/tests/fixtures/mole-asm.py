#!/usr/bin/env python3
"""mole-asm --- golden moleasm assembler (Mole v0.2 ISA).

Reads a ``.moleasm`` source file and emits raw 32-bit little-endian
bytecode into a sibling ``.molecode`` file.  With ``--frame`` it also
writes a framed ``.mole.bin`` (len-in-words u16 + program bytes +
CRC-16/XMODEM u16) ready to drop onto the Mole UART.

This is the *reference oracle* that the Rust ``mole-asm`` crate
(at ``../../../mole-asm/``, CLI at ``../../../mole-asm-cli/``) is
diffed against byte-for-byte.  The encoding in this file follows
``docs/MOLE-0.2-SPEC.md`` (25 live opcodes, 32-bit fixed-width
instructions, 6-bit opcode space = {group[31:30], sub[29:26]}).

Reference:
  docs/MOLE-0.2-SPEC.md   -- normative ISA and wire-format spec
  mole-asm/src/encoder.rs  -- Rust encoder (matches this file)
  mole-asm/src/assembler.rs-- Rust assembler (grammar reference)
  mole-asm/src/symbols.rs  -- symbol tables (same constants)

CLI::

    python mole-asm.py [-h] [--frame] [-o OUT] INPUT.moleasm
    python mole-asm.py           # no args: runs self-check suite

Running with no args regenerates fixture bytecode files in this
directory and runs the full self-check suite; exits 0 on success.

Encoding note: wire-format preamble constants are defined in
mole-abi/src/lib.rs (mole_abi::MAGIC, mole_abi::MAX_PROGRAM_WORDS).
The Python literals below are mirrors of those Rust constants; keep
them in sync when mole-abi changes.
"""

from __future__ import annotations

import argparse
import struct
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Tuple

# ---------------------------------------------------------------------------
# Wire-format preamble constants  (§10)
# Wire-format preamble constants. Source of truth: mole-abi/src/lib.rs.
# Keep in sync.
# ---------------------------------------------------------------------------

# Mirror of mole_abi::MAGIC
PREAMBLE_MAGIC: int = 0x0002_4D4C   # bytes on wire: 4C 4D 02 00
# Mirror of mole_abi::MAX_PROGRAM_WORDS
MAX_PROGRAM_WORDS: int = 8192        # §10


# ---------------------------------------------------------------------------
# Group / sub-opcode constants  (§4)
# ---------------------------------------------------------------------------

GROUP_WIRE: int = 0b00
GROUP_CTRL: int = 0b01
GROUP_DATA: int = 0b10
GROUP_LOOP: int = 0b11   # entirely reserved in v0.2

# WIRE sub-opcodes
S_EMIT_BIT_IMM:     int = 0b0000
S_EMIT_BIT_REG:     int = 0b0001
S_EMIT_QUARTER_IMM: int = 0b0010
S_EMIT_QUARTER_REG: int = 0b0011
S_EMIT_BYTE_REG:    int = 0b0100
S_SAMPLE_BIT_ON_SCL: int = 0b0101
S_DRIVE_BIT_ON_SCL: int = 0b0110
S_STRETCH_SCL_IMM:  int = 0b0111
S_STRETCH_SCL_REG:  int = 0b1000
S_EMIT_BYTE_IMM:    int = 0b1001

# CTRL sub-opcodes
S_HALT:         int = 0b0000
S_BRANCH_ON:    int = 0b0001
S_WAIT_ON:      int = 0b0010
S_SET_BUS_MODE: int = 0b0011
S_SET_ROLE:     int = 0b0100
S_FLAG_CLEAR:   int = 0b0101
S_MARK:         int = 0b0110
S_LOAD_TIMING:  int = 0b0111

# DATA sub-opcodes
S_LOAD_IMM: int = 0b0000
S_MOV:      int = 0b0001
S_ADD_IMM:  int = 0b0010
S_DEC:      int = 0b0011
S_AND_IMM:  int = 0b0100
S_OR_IMM:   int = 0b0101
S_XOR_IMM:  int = 0b0110
S_SHIFT:    int = 0b0111

# SHIFT direction constants
SHIFT_LEFT:  int = 0  # arith=0, dir=0
SHIFT_RIGHT: int = 1  # arith=0, dir=1
SHIFT_ARIGHT: int = 2  # arith=1, dir=1


# ---------------------------------------------------------------------------
# Named symbol tables  (mirrors mole-asm/src/symbols.rs)
# ---------------------------------------------------------------------------

# tx_symbol vocabulary (§2, §3).
# dominant=0b00, recessive=0b01, hiz=0b10, reserved=0b11 (raw-only).
# Short forms dom/rec accepted.  Stored lowercase; callers lowercase input.
TX_SYMBOLS: Dict[str, int] = {
    "dominant":  0b00,
    "dom":       0b00,
    "recessive": 0b01,
    "rec":       0b01,
    "hiz":       0b10,
    # NOTE: "reserved" absent here; raw/ mode adds 0b11 explicitly.
}

# BUS_MODE wire values (§9).  RENUMBERED from v0:
#   i2c=0, i3c-OD=1, i3c-PP=2, hdr-ddr=3
# Stored lowercase with hyphens; callers lowercase input.
BUS_MODES: Dict[str, int] = {
    "i2c":     0,
    "i3c-od":  1,
    "i3c-pp":  2,
    "hdr-ddr": 3,
}

# Condition-code namespace shared by BRANCH_ON and WAIT_ON (§6).
# Codes 0-11 live; 12-15 reserved.
# Stored UPPERCASE; callers uppercase input.
COND_CODES: Dict[str, int] = {
    "ALWAYS":       0,
    "MISMATCH":     1,
    "NOT_MISMATCH": 2,
    "START_SEEN":   3,
    "STOP_SEEN":    4,
    "SDA_LOW":      5,
    "SDA_HIGH":     6,
    "SCL_HIGH":     7,
    "TIMEOUT":      8,
    "NOT_TIMEOUT":  9,
    "REG_ZERO":     10,
    "NOT_REG_ZERO": 11,
}

# SET_ROLE named operands (§5.14).
ROLE_NAMES: Dict[str, int] = {
    "controller": 0,
    "target":     1,
}

# SHIFT direction keywords (§5.25).
SHIFT_DIRS: Dict[str, int] = {
    "left":   SHIFT_LEFT,
    "right":  SHIFT_RIGHT,
    "aright": SHIFT_ARIGHT,
}

# All live mnemonic names (uppercase canonical).  Sugar forms included.
MNEMONICS = frozenset([
    # WIRE group
    "EMIT_BIT_IMM", "EMIT_BIT_REG",
    "EMIT_QUARTER_IMM", "EMIT_QUARTER_REG",
    "EMIT_BYTE_IMM", "EMIT_BYTE_REG",
    # NOTE: bare "EMIT_BYTE" was sugar for EMIT_BYTE_REG in early
    # v0.2 drafts. It is retired (E-LEX-006); see RETIRED_MNEMONICS
    # below for the special-case diagnostic.
    "SAMPLE_BIT_ON_SCL", "DRIVE_BIT_ON_SCL",
    "STRETCH_SCL_IMM", "STRETCH_SCL_REG",
    # CTRL group
    "HALT", "BRANCH_ON", "WAIT_ON",
    "SET_BUS_MODE", "SET_ROLE",
    "FLAG_CLEAR", "MARK", "LOAD_TIMING",
    # DATA group
    "LOAD_IMM", "MOV",
    "ADD_IMM", "DEC",
    "AND_IMM", "OR_IMM", "XOR_IMM",
    "SHIFT",
    # Sugar forms
    "JMP", "LOAD_LOOP",
])


# Mnemonics that existed as sugar in earlier v0.2 drafts and have
# been retired. The lexer special-cases these BEFORE the general
# MNEMONICS check so the diagnostic can point at the canonical
# replacement (E-LEX-006) instead of the generic "unknown mnemonic"
# (E-LEX-001).
#
# Mirrors `mole-asm/src/symbols.rs::RETIRED_MNEMONICS`.
RETIRED_MNEMONICS = {
    "EMIT_BYTE":
        "EMIT_BYTE_IMM (compile-time-known byte) "
        "or EMIT_BYTE_REG (R7-sourced byte)",
}


def is_reserved_name(name: str) -> bool:
    """True iff ``name`` collides with a built-in mnemonic/symbol.

    Mirrors ``mole-asm/src/symbols.rs::is_reserved_name``.
    """
    upper = name.upper()
    lower = name.lower()
    if upper in MNEMONICS:
        return True
    if upper in COND_CODES:
        return True
    if lower in TX_SYMBOLS:
        return True
    if lower in BUS_MODES:
        return True
    if lower in ROLE_NAMES:
        return True
    if lower in SHIFT_DIRS:
        return True
    return False


# ---------------------------------------------------------------------------
# Error class
# ---------------------------------------------------------------------------

class AsmError(Exception):
    """Assembler error carrying a short error code and source location."""

    def __init__(self, code: str, message: str,
                 line: int = 0, filename: str = "<unknown>",
                 col: int = 0) -> None:
        self.code = code
        self.message = message
        self.line = line
        self.col = col
        self.filename = filename
        super().__init__(self._format())

    def _format(self) -> str:
        loc = f"{self.filename}:{self.line}" if self.line else self.filename
        return f"{loc}: {self.code}: {self.message}"


# ---------------------------------------------------------------------------
# Low-level encoder helpers
# ---------------------------------------------------------------------------

def _opcode(group: int, sub: int) -> int:
    """Build the 6-bit opcode prefix: ``group << 30 | sub << 26``."""
    return (group << 30) | (sub << 26)


def _flag_triple(expect: bool, mask: bool, capture: bool) -> int:
    """Pack expect/mask/capture into bits [2:0].

    Layout per §3: [2]=expect, [1]=mask, [0]=capture.
    """
    return (int(expect) << 2) | (int(mask) << 1) | int(capture)


def _check_tx(name: str, tx: int, raw_mode: bool,
              line: int = 0, filename: str = "<unknown>") -> None:
    if tx > 3:
        raise AsmError("E-WIRE-001",
                       f"{name}: tx symbol must be 0..3, got {tx}",
                       line=line, filename=filename)
    if tx == 0b11 and not raw_mode:
        raise AsmError("E-WIRE-001",
                       "tx=reserved (0b11) requires '(use-raw-primitives)' "
                       "pragma; use .dw or declare raw/",
                       line=line, filename=filename)


def _check_reg(name: str, reg: int,
               line: int = 0, filename: str = "<unknown>") -> None:
    if reg > 7:
        raise AsmError("E-OP-007",
                       f"{name}: register R{reg} is not valid; use R0..R7",
                       line=line, filename=filename)


# ---------------------------------------------------------------------------
# WIRE group encoders  (§5.1 – §5.9)
# ---------------------------------------------------------------------------

def enc_emit_bit_imm(tx: int, expect: bool, mask: bool, capture: bool,
                     raw_mode: bool,
                     line: int = 0, filename: str = "<unknown>") -> int:
    """WIRE.EMIT_BIT_IMM (§5.1).

    [31:30]=00 [29:26]=0000 [25:5]=0 [4:3]=tx [2:0]=flags
    """
    _check_tx("EMIT_BIT_IMM", tx, raw_mode, line, filename)
    return (_opcode(GROUP_WIRE, S_EMIT_BIT_IMM)
            | (tx << 3)
            | _flag_triple(expect, mask, capture))


def enc_emit_bit_reg(src: int, expect: bool, mask: bool, capture: bool,
                     line: int = 0, filename: str = "<unknown>") -> int:
    """WIRE.EMIT_BIT_REG (§5.2).

    [31:30]=00 [29:26]=0001 [25:23]=0 [22:20]=src [19:3]=0 [2:0]=flags
    """
    _check_reg("EMIT_BIT_REG src", src, line, filename)
    return (_opcode(GROUP_WIRE, S_EMIT_BIT_REG)
            | (src << 20)
            | _flag_triple(expect, mask, capture))


def enc_emit_quarter_imm(sda: int, scl: int, expect: bool, mask: bool,
                         capture: bool, raw_mode: bool,
                         line: int = 0, filename: str = "<unknown>") -> int:
    """WIRE.EMIT_QUARTER_IMM (§5.3).

    [31:30]=00 [29:26]=0010 [25:7]=0 [6:5]=scl [4:3]=sda [2:0]=flags
    """
    _check_tx("EMIT_QUARTER_IMM sda", sda, raw_mode, line, filename)
    _check_tx("EMIT_QUARTER_IMM scl", scl, raw_mode, line, filename)
    return (_opcode(GROUP_WIRE, S_EMIT_QUARTER_IMM)
            | (scl << 5)
            | (sda << 3)
            | _flag_triple(expect, mask, capture))


def enc_emit_quarter_reg(src: int, expect: bool, mask: bool, capture: bool,
                         line: int = 0, filename: str = "<unknown>") -> int:
    """WIRE.EMIT_QUARTER_REG (§5.4).

    [31:30]=00 [29:26]=0011 [25:23]=0 [22:20]=src [19:3]=0 [2:0]=flags
    """
    _check_reg("EMIT_QUARTER_REG src", src, line, filename)
    return (_opcode(GROUP_WIRE, S_EMIT_QUARTER_REG)
            | (src << 20)
            | _flag_triple(expect, mask, capture))


def enc_emit_byte_reg(expect: bool, mask: bool, capture: bool) -> int:
    """WIRE.EMIT_BYTE_REG (§5.5).

    [31:30]=00 [29:26]=0100 [25:3]=0 [2:0]=flags
    Payload comes from R7[7:0] at runtime.
    """
    return (_opcode(GROUP_WIRE, S_EMIT_BYTE_REG)
            | _flag_triple(expect, mask, capture))


def enc_emit_byte_imm(imm: int, expect: bool, mask: bool, capture: bool,
                       line: int = 0, filename: str = "<unknown>") -> int:
    """WIRE.EMIT_BYTE_IMM (§5.5b).

    [31:30]=00 [29:26]=1001 [25:11]=0 [10:3]=imm [2:0]=flags

    The 8-bit payload is encoded in the instruction word; the engine
    does not read R7. Caller is responsible for range-checking `imm`
    to 0..255 (E-RNG-001 site lives in the dispatcher).
    """
    if not (0 <= imm <= 255):
        raise AsmError("E-RNG-001",
                       f"EMIT_BYTE_IMM imm must be 0..255, got {imm}",
                       line, filename)
    return (_opcode(GROUP_WIRE, S_EMIT_BYTE_IMM)
            | ((imm & 0xFF) << 3)
            | _flag_triple(expect, mask, capture))


def enc_sample_bit_on_scl(dst: int, expect: bool, mask: bool, capture: bool,
                           raw_mode: bool,
                           line: int = 0, filename: str = "<unknown>") -> int:
    """WIRE.SAMPLE_BIT_ON_SCL (§5.6).

    [31:30]=00 [29:26]=0101 [25:23]=dst [22:3]=0 [2:0]=flags
    dst must be R7 unless raw/ pragma.
    """
    _check_reg("SAMPLE_BIT_ON_SCL dst", dst, line, filename)
    if dst != 7 and not raw_mode:
        raise AsmError("E-REG-001",
                       "capture must write to R7; use raw/ pragma to override",
                       line=line, filename=filename)
    return (_opcode(GROUP_WIRE, S_SAMPLE_BIT_ON_SCL)
            | (dst << 23)
            | _flag_triple(expect, mask, capture))


def enc_drive_bit_on_scl(tx: int, expect: bool, mask: bool, capture: bool,
                          raw_mode: bool,
                          line: int = 0, filename: str = "<unknown>") -> int:
    """WIRE.DRIVE_BIT_ON_SCL (§5.7).

    [31:30]=00 [29:26]=0110 [25:23]=dst [22:5]=0 [4:3]=tx [2:0]=flags
    Canonical: dst=0b000 when capture=0; dst=R7=7 when capture=1.
    """
    _check_tx("DRIVE_BIT_ON_SCL", tx, raw_mode, line, filename)
    dst = 7 if capture else 0
    return (_opcode(GROUP_WIRE, S_DRIVE_BIT_ON_SCL)
            | (dst << 23)
            | (tx << 3)
            | _flag_triple(expect, mask, capture))


def enc_drive_bit_on_scl_raw(dst: int, tx: int, expect: bool, mask: bool,
                              capture: bool,
                              line: int = 0,
                              filename: str = "<unknown>") -> int:
    """WIRE.DRIVE_BIT_ON_SCL with explicit dst (raw/ override, §5.7)."""
    _check_reg("DRIVE_BIT_ON_SCL dst", dst, line, filename)
    _check_tx("DRIVE_BIT_ON_SCL", tx, True, line, filename)
    return (_opcode(GROUP_WIRE, S_DRIVE_BIT_ON_SCL)
            | (dst << 23)
            | (tx << 3)
            | _flag_triple(expect, mask, capture))


def enc_stretch_scl_imm(n_quarters: int,
                         line: int = 0,
                         filename: str = "<unknown>") -> int:
    """WIRE.STRETCH_SCL_IMM (§5.8).

    [31:30]=00 [29:26]=0111 [25:17]=0 [16:3]=n [2:0]=0
    """
    if not (0 <= n_quarters < (1 << 14)):
        raise AsmError("E-RNG-001",
                       f"STRETCH_SCL_IMM n_quarters must be 0..16383, "
                       f"got {n_quarters}",
                       line=line, filename=filename)
    return _opcode(GROUP_WIRE, S_STRETCH_SCL_IMM) | (n_quarters << 3)


def enc_stretch_scl_reg(src: int,
                         line: int = 0,
                         filename: str = "<unknown>") -> int:
    """WIRE.STRETCH_SCL_REG (§5.9).

    [31:30]=00 [29:26]=1000 [25:23]=0 [22:20]=src [19:3]=0 [2:0]=0
    """
    _check_reg("STRETCH_SCL_REG src", src, line, filename)
    return _opcode(GROUP_WIRE, S_STRETCH_SCL_REG) | (src << 20)


# ---------------------------------------------------------------------------
# CTRL group encoders  (§5.10 – §5.17)
# ---------------------------------------------------------------------------

def enc_halt(status: int,
             line: int = 0, filename: str = "<unknown>") -> int:
    """CTRL.HALT (§5.10).

    [31:30]=01 [29:26]=0000 [25:8]=0 [7:3]=status [2:0]=0
    """
    if not (0 <= status < 32):
        raise AsmError("E-RNG-001",
                       f"HALT status must be 0..31, got {status}",
                       line=line, filename=filename)
    return _opcode(GROUP_CTRL, S_HALT) | (status << 3)


def enc_branch_on(cond: int, pc_rel_offset: int,
                  line: int = 0, filename: str = "<unknown>") -> int:
    """CTRL.BRANCH_ON (§5.11).

    [31:30]=01 [29:26]=0001 [25:17]=0 [16:13]=cond [12:3]=offset [2:0]=0
    offset: signed 10-bit, -512..511.
    """
    if cond > 15:
        raise AsmError("E-CTRL-001",
                       f"cond code {cond} is out of range 0..15",
                       line=line, filename=filename)
    if not (-512 <= pc_rel_offset <= 511):
        raise AsmError("E-RNG-003",
                       f"BRANCH_ON offset {pc_rel_offset} out of signed "
                       f"10-bit range (-512..511)",
                       line=line, filename=filename)
    offset_bits = pc_rel_offset & 0x3FF
    return (_opcode(GROUP_CTRL, S_BRANCH_ON)
            | (cond << 13)
            | (offset_bits << 3))


def enc_wait_on(cond: int, timeout: int,
                line: int = 0, filename: str = "<unknown>") -> int:
    """CTRL.WAIT_ON (§5.12).

    [31:30]=01 [29:26]=0010 [25:17]=0 [16:13]=cond [12:3]=timeout [2:0]=0
    timeout: unsigned 10-bit, 0..1023.
    """
    if cond > 15:
        raise AsmError("E-CTRL-001",
                       f"cond code {cond} is out of range 0..15",
                       line=line, filename=filename)
    if not (0 <= timeout < 1024):
        raise AsmError("E-RNG-004",
                       f"WAIT_ON timeout must be 0..1023 quarters, "
                       f"got {timeout}",
                       line=line, filename=filename)
    return (_opcode(GROUP_CTRL, S_WAIT_ON)
            | (cond << 13)
            | (timeout << 3))


def enc_set_bus_mode(mode_wire: int,
                     line: int = 0, filename: str = "<unknown>") -> int:
    """CTRL.SET_BUS_MODE (§5.13).

    [31:30]=01 [29:26]=0011 [25:7]=0 [6:3]=mode [2:0]=0
    v0.2 wire values: i2c=0, i3c-OD=1, i3c-PP=2, hdr-ddr=3 (RENUMBERED).
    """
    if mode_wire > 15:
        raise AsmError("E-OP-006",
                       f"SET_BUS_MODE wire value must be 0..15, "
                       f"got {mode_wire}",
                       line=line, filename=filename)
    return _opcode(GROUP_CTRL, S_SET_BUS_MODE) | (mode_wire << 3)


def enc_set_role(role: bool) -> int:
    """CTRL.SET_ROLE (§5.14).

    [31:30]=01 [29:26]=0100 [25:4]=0 [3]=role [2:0]=0
    """
    return _opcode(GROUP_CTRL, S_SET_ROLE) | (int(role) << 3)


def enc_flag_clear(mask: int,
                   line: int = 0, filename: str = "<unknown>") -> int:
    """CTRL.FLAG_CLEAR (§5.15).

    [31:30]=01 [29:26]=0101 [25:8]=0 [7:3]=mask [2:0]=0
    """
    if not (0 <= mask <= 31):
        raise AsmError("E-OP-009",
                       f"FLAG_CLEAR mask must be a 5-bit value (0..31), "
                       f"got {mask}",
                       line=line, filename=filename)
    return _opcode(GROUP_CTRL, S_FLAG_CLEAR) | (mask << 3)


def enc_mark(label: int,
             line: int = 0, filename: str = "<unknown>") -> int:
    """CTRL.MARK (§5.16).

    [31:30]=01 [29:26]=0110 [25:17]=0 [16:3]=label [2:0]=0
    """
    if not (0 <= label < (1 << 14)):
        raise AsmError("E-RNG-001",
                       f"MARK label must be 0..16383, got {label}",
                       line=line, filename=filename)
    return _opcode(GROUP_CTRL, S_MARK) | (label << 3)


def enc_load_timing(reg: int, divider: int,
                    line: int = 0, filename: str = "<unknown>") -> int:
    """CTRL.LOAD_TIMING (§5.17).

    [31:30]=01 [29:26]=0111 [25:20]=0 [19:17]=reg [16:3]=divider [2:0]=0
    """
    if not (0 <= reg < 8):
        raise AsmError("E-RNG-001",
                       f"LOAD_TIMING reg must be 0..7, got {reg}",
                       line=line, filename=filename)
    if not (0 <= divider < (1 << 14)):
        raise AsmError("E-RNG-001",
                       f"LOAD_TIMING divider must be 0..16383, "
                       f"got {divider}",
                       line=line, filename=filename)
    return (_opcode(GROUP_CTRL, S_LOAD_TIMING)
            | (reg << 17)
            | (divider << 3))


# ---------------------------------------------------------------------------
# DATA group encoders  (§5.18 – §5.25)
# ---------------------------------------------------------------------------

def enc_load_imm(dst: int, imm14: int,
                 line: int = 0, filename: str = "<unknown>") -> int:
    """DATA.LOAD_IMM (§5.18).

    [31:30]=10 [29:26]=0000 [25:23]=dst [22:17]=0 [16:3]=imm14 [2:0]=0
    """
    _check_reg("LOAD_IMM dst", dst, line, filename)
    if not (0 <= imm14 < (1 << 14)):
        raise AsmError("E-RNG-001",
                       f"LOAD_IMM imm14 must be 0..16383, got {imm14}",
                       line=line, filename=filename)
    return _opcode(GROUP_DATA, S_LOAD_IMM) | (dst << 23) | (imm14 << 3)


def enc_mov(dst: int, src: int,
            line: int = 0, filename: str = "<unknown>") -> int:
    """DATA.MOV (§5.19).

    [31:30]=10 [29:26]=0001 [25:23]=dst [22:20]=src [19:3]=0 [2:0]=0
    """
    _check_reg("MOV dst", dst, line, filename)
    _check_reg("MOV src", src, line, filename)
    return _opcode(GROUP_DATA, S_MOV) | (dst << 23) | (src << 20)


def enc_add_imm(dst: int, src: int, imm14: int,
                line: int = 0, filename: str = "<unknown>") -> int:
    """DATA.ADD_IMM (§5.20).

    [31:30]=10 [29:26]=0010 [25:23]=dst [22:20]=src [19:17]=0
    [16:3]=imm14 (signed 14-bit) [2:0]=0
    """
    _check_reg("ADD_IMM dst", dst, line, filename)
    _check_reg("ADD_IMM src", src, line, filename)
    if not (-8192 <= imm14 <= 8191):
        raise AsmError("E-RNG-001",
                       f"ADD_IMM imm14 must be -8192..8191, got {imm14}",
                       line=line, filename=filename)
    bits = imm14 & 0x3FFF
    return (_opcode(GROUP_DATA, S_ADD_IMM)
            | (dst << 23)
            | (src << 20)
            | (bits << 3))


def enc_dec(reg: int,
            line: int = 0, filename: str = "<unknown>") -> int:
    """DATA.DEC (§5.21).

    [31:30]=10 [29:26]=0011 [25:23]=dst(=reg) [22:20]=src(=reg) [19:3]=0 [2:0]=0
    """
    _check_reg("DEC", reg, line, filename)
    return _opcode(GROUP_DATA, S_DEC) | (reg << 23) | (reg << 20)


def enc_and_imm(dst: int, src: int, imm14: int,
                line: int = 0, filename: str = "<unknown>") -> int:
    """DATA.AND_IMM (§5.22).

    [31:30]=10 [29:26]=0100 [25:23]=dst [22:20]=src [19:17]=0
    [16:3]=imm14 (unsigned 14-bit) [2:0]=0
    """
    _check_reg("AND_IMM dst", dst, line, filename)
    _check_reg("AND_IMM src", src, line, filename)
    if not (0 <= imm14 < (1 << 14)):
        raise AsmError("E-RNG-001",
                       f"AND_IMM imm14 must be 0..16383, got {imm14}",
                       line=line, filename=filename)
    return (_opcode(GROUP_DATA, S_AND_IMM)
            | (dst << 23)
            | (src << 20)
            | (imm14 << 3))


def enc_or_imm(dst: int, src: int, imm14: int,
               line: int = 0, filename: str = "<unknown>") -> int:
    """DATA.OR_IMM (§5.23).  Identical layout to AND_IMM with sub=0b0101."""
    _check_reg("OR_IMM dst", dst, line, filename)
    _check_reg("OR_IMM src", src, line, filename)
    if not (0 <= imm14 < (1 << 14)):
        raise AsmError("E-RNG-001",
                       f"OR_IMM imm14 must be 0..16383, got {imm14}",
                       line=line, filename=filename)
    return (_opcode(GROUP_DATA, S_OR_IMM)
            | (dst << 23)
            | (src << 20)
            | (imm14 << 3))


def enc_xor_imm(dst: int, src: int, imm14: int,
                line: int = 0, filename: str = "<unknown>") -> int:
    """DATA.XOR_IMM (§5.24).  Identical layout to AND_IMM with sub=0b0110."""
    _check_reg("XOR_IMM dst", dst, line, filename)
    _check_reg("XOR_IMM src", src, line, filename)
    if not (0 <= imm14 < (1 << 14)):
        raise AsmError("E-RNG-001",
                       f"XOR_IMM imm14 must be 0..16383, got {imm14}",
                       line=line, filename=filename)
    return (_opcode(GROUP_DATA, S_XOR_IMM)
            | (dst << 23)
            | (src << 20)
            | (imm14 << 3))


def enc_shift(dst: int, src: int, shift_kind: int, shamt: int,
              line: int = 0, filename: str = "<unknown>") -> int:
    """DATA.SHIFT (§5.25).

    [31:30]=10 [29:26]=0111 [25:23]=dst [22:20]=src
    [19]=arith [18]=dir [17]=0 [16:8]=0 [7:3]=shamt [2:0]=0
    shift_kind: SHIFT_LEFT=0, SHIFT_RIGHT=1, SHIFT_ARIGHT=2
    """
    _check_reg("SHIFT dst", dst, line, filename)
    _check_reg("SHIFT src", src, line, filename)
    if not (0 <= shamt < 32):
        raise AsmError("E-RNG-001",
                       f"SHIFT shamt must be 0..31, got {shamt}",
                       line=line, filename=filename)
    if shift_kind == SHIFT_LEFT:
        arith, dire = 0, 0
    elif shift_kind == SHIFT_RIGHT:
        arith, dire = 0, 1
    elif shift_kind == SHIFT_ARIGHT:
        arith, dire = 1, 1
    else:
        raise AsmError("E-OP-002",
                       f"unknown shift direction {shift_kind}; "
                       f"use left, right, or aright",
                       line=line, filename=filename)
    return (_opcode(GROUP_DATA, S_SHIFT)
            | (dst << 23)
            | (src << 20)
            | (arith << 19)
            | (dire << 18)
            | (shamt << 3))


# ---------------------------------------------------------------------------
# CRC-16/XMODEM
# ---------------------------------------------------------------------------

def crc16_xmodem(data: bytes) -> int:
    """CRC-16/XMODEM: poly=0x1021, init=0, no reflection, no XOR-out."""
    crc = 0
    for byte in data:
        crc ^= byte << 8
        for _ in range(8):
            if crc & 0x8000:
                crc = ((crc << 1) ^ 0x1021) & 0xFFFF
            else:
                crc = (crc << 1) & 0xFFFF
    return crc


# ---------------------------------------------------------------------------
# Wire packing
# ---------------------------------------------------------------------------

def pack_bytecode(words: List[int]) -> bytes:
    """Pack a list of 32-bit words to little-endian bytes (4 bytes each)."""
    return b"".join(struct.pack("<I", w) for w in words)


def build_frame(words: List[int]) -> bytes:
    """Build a UART frame: len_words(LE u16) + bytecode + CRC-16/XMODEM(LE u16).

    ``words`` must include the 2-word preamble.  Length covers the WHOLE
    program (preamble + body) in 32-bit words.  The CRC is computed over
    the length field AND the bytecode (all bytes before the CRC trailer).

    Rejects programs with no body (preamble-only) and programs larger than
    MAX_PROGRAM_WORDS + 2 preamble words (= 8194 words total).
    """
    total_words = len(words)
    # Rust rejects: len < PREAMBLE_WORDS+1 (no body) or len > MAX_TOTAL_WORDS
    if total_words < 3:
        raise AsmError("E-FRM-001",
                       "build_frame: program must have at least one body word")
    if total_words > MAX_PROGRAM_WORDS + 2:
        raise AsmError("E-FRM-001",
                       f"build_frame: program too large "
                       f"({total_words} words > {MAX_PROGRAM_WORDS + 2})")
    program_bytes = pack_bytecode(words)
    length_field = struct.pack("<H", total_words)
    # CRC covers the length field bytes AND the program bytes (matching Rust).
    crc = crc16_xmodem(length_field + program_bytes)
    crc_field = struct.pack("<H", crc)
    return length_field + program_bytes + crc_field


# ---------------------------------------------------------------------------
# Lexer / parser utilities
# ---------------------------------------------------------------------------

def _strip_comment(line: str) -> str:
    idx = line.find(";")
    return line[:idx] if idx >= 0 else line


def _parse_int(tok: str, line: int = 0,
               filename: str = "<unknown>") -> int:
    """Parse decimal, 0x hex, 0b binary int with optional _ separators."""
    if not tok:
        raise AsmError("E-RNG-001", "empty numeric literal",
                       line=line, filename=filename)
    negative = tok.startswith("-")
    body = tok[1:] if negative else tok
    clean = body.replace("_", "")
    try:
        if clean.startswith(("0x", "0X")):
            val = int(clean[2:], 16)
        elif clean.startswith(("0b", "0B")):
            val = int(clean[2:], 2)
        else:
            val = int(clean, 10)
    except ValueError:
        raise AsmError("E-RNG-001",
                       f"not a valid integer literal: '{tok}'",
                       line=line, filename=filename)
    return -val if negative else val


def _looks_like_ident(tok: str) -> bool:
    return bool(tok) and (tok[0].isascii() and tok[0].isalpha() or tok[0] == "_")


def _parse_reg(tok: str) -> Optional[int]:
    """Parse ``R0``..``R7`` (case-insensitive).  Returns None on failure."""
    lower = tok.lower()
    if not lower.startswith("r"):
        return None
    body = lower[1:]
    if len(body) != 1:
        return None
    try:
        n = int(body)
    except ValueError:
        return None
    return n if 0 <= n <= 7 else None


def _is_valid_ident(s: str) -> bool:
    """Label pattern: [A-Za-z_][A-Za-z0-9_]* (ASCII-only, no dashes)."""
    if not s:
        return False
    if not (s[0].isascii() and s[0].isalpha() or s[0] == "_"):
        return False
    return all((c.isascii() and c.isalnum()) or c == "_" for c in s[1:])


def _is_valid_bus_mode_ident(s: str) -> bool:
    """Bus-mode names allow hyphens: [A-Za-z_][A-Za-z0-9_-]* (ASCII-only)."""
    if not s:
        return False
    if not (s[0].isascii() and s[0].isalpha() or s[0] == "_"):
        return False
    return all((c.isascii() and c.isalnum()) or c in "_-" for c in s[1:])


def _tokenize_operands(operand_region: str) -> List[str]:
    """Split operands by comma or whitespace, preserving key=value tokens."""
    out: List[str] = []
    for piece in operand_region.split(","):
        for tok in piece.split():
            if tok:
                out.append(tok)
    return out


def _try_split_label(body: str) -> Optional[Tuple[str, str]]:
    """Try to split 'LABEL:rest'.  Returns None if no valid label prefix."""
    idx = body.find(":")
    if idx < 0:
        return None
    label = body[:idx].rstrip()
    rest = body[idx + 1:].lstrip()
    if not _is_valid_ident(label):
        return None
    return label, rest


# ---------------------------------------------------------------------------
# Statement dataclass
# ---------------------------------------------------------------------------

@dataclass
class Statement:
    filename: str
    line: int
    label: Optional[str]
    mnemonic: Optional[str]       # uppercase or None
    directive: Optional[str]      # lowercase (.equ, .dw) or None
    operands: List[str]


# ---------------------------------------------------------------------------
# Symbol table
# ---------------------------------------------------------------------------

class SymbolTable:
    def __init__(self) -> None:
        self._map: Dict[str, Tuple[str, int, int]] = {}
        # key -> (kind, value, line_no)  kind in {"label","equate"}

    def bind_label(self, name: str, pc: int, line: int,
                   filename: str) -> None:
        if not _is_valid_ident(name):
            raise AsmError("E-SYM-001",
                           f"invalid label name: '{name}'",
                           line=line, filename=filename)
        if is_reserved_name(name):
            raise AsmError("E-SYM-005",
                           f"label name '{name}' collides with a reserved "
                           f"mnemonic / symbol",
                           line=line, filename=filename)
        if name in self._map:
            prior_kind, _, prior_line = self._map[name]
            raise AsmError("E-SYM-001",
                           f"'{name}' re-defined (prior {prior_kind} "
                           f"on line {prior_line})",
                           line=line, filename=filename)
        self._map[name] = ("label", pc, line)

    def bind_equate(self, name: str, value: int, line: int,
                    filename: str) -> None:
        if not _is_valid_ident(name):
            raise AsmError("E-SYM-002",
                           f"invalid equate name: '{name}'",
                           line=line, filename=filename)
        if is_reserved_name(name):
            raise AsmError("E-SYM-005",
                           f"equate name '{name}' collides with a reserved "
                           f"mnemonic / symbol",
                           line=line, filename=filename)
        if name in self._map:
            prior_kind, _, prior_line = self._map[name]
            code = "E-SYM-001" if prior_kind == "label" else "E-SYM-002"
            raise AsmError(code,
                           f"'{name}' re-defined (prior {prior_kind} "
                           f"on line {prior_line})",
                           line=line, filename=filename)
        self._map[name] = ("equate", value, line)

    def get_label(self, name: str, line: int,
                  filename: str) -> int:
        if name not in self._map:
            raise AsmError("E-SYM-003",
                           f"undefined branch target: '{name}'",
                           line=line, filename=filename)
        kind, val, _ = self._map[name]
        if kind != "label":
            raise AsmError("E-SYM-003",
                           f"BRANCH_ON target '{name}' is an .equ "
                           f"constant, not a label",
                           line=line, filename=filename)
        return val

    def get_equate(self, name: str, line: int,
                   filename: str) -> int:
        if name not in self._map:
            raise AsmError("E-SYM-004",
                           f"undefined symbol: '{name}'",
                           line=line, filename=filename)
        kind, val, _ = self._map[name]
        if kind != "equate":
            raise AsmError("E-SYM-004",
                           f"'{name}' is a label (PC address), not a "
                           f"constant; expected an .equ value here",
                           line=line, filename=filename)
        return val

    def get_any(self, name: str, line: int, filename: str) -> int:
        """Resolve label or equate (for operand contexts)."""
        if name not in self._map:
            raise AsmError("E-SYM-004",
                           f"undefined symbol: '{name}'",
                           line=line, filename=filename)
        kind, val, _ = self._map[name]
        if kind == "equate":
            return val
        raise AsmError("E-SYM-004",
                        f"'{name}' is a label (PC address), not a "
                        f"constant; expected an .equ value here",
                        line=line, filename=filename)


def _resolve_literal_or_equate(tok: str, syms: SymbolTable,
                                line: int,
                                filename: str) -> int:
    if _looks_like_ident(tok):
        return syms.get_any(tok, line, filename)
    return _parse_int(tok, line, filename)


def _parse_int_raw(tok: str) -> Optional[int]:
    """Parse integer without raising (for pairing check)."""
    try:
        return _parse_int(tok)
    except (AsmError, ValueError):
        return None


# ---------------------------------------------------------------------------
# Lexer (pass 0)
# ---------------------------------------------------------------------------

RAW_PRAGMA = "(use-raw-primitives)"
ALLOWED_DIRECTIVES = frozenset([".equ", ".dw"])


def lex(source: str, filename: str) -> Tuple[List[Statement], bool]:
    """Tokenise source into Statements.  Returns (statements, raw_mode)."""
    statements: List[Statement] = []
    raw_mode = False
    first_instruction_seen = False

    for idx, raw_line in enumerate(source.splitlines()):
        line_no = idx + 1
        # Strip trailing CR (CRLF tolerance per §12.1).
        trimmed = raw_line.rstrip("\r")
        body = _strip_comment(trimmed).strip()

        if not body:
            continue

        # Raw-primitives pragma (§12.1).
        if body == RAW_PRAGMA:
            if first_instruction_seen:
                raise AsmError("E-RAW-002",
                               "raw pragma must be the first non-comment line",
                               line=line_no, filename=filename)
            raw_mode = True
            continue

        # Malformed pragma attempt.
        if body.startswith("("):
            raise AsmError("E-RAW-003",
                           f"pragma must read exactly '{RAW_PRAGMA}'; "
                           f"got '{body}'",
                           line=line_no, filename=filename)

        # Try to peel a label prefix.
        result = _try_split_label(body)
        if result is not None:
            label, body = result
        else:
            label = None

        if not body:
            # Label-only line.
            statements.append(Statement(
                filename=filename, line=line_no,
                label=label, mnemonic=None, directive=None, operands=[],
            ))
            continue

        # Split into head token + rest.
        parts = body.split(None, 1)
        head = parts[0]
        rest = parts[1] if len(parts) > 1 else ""

        # Directive?
        if head.startswith("."):
            directive = head.lower()
            if directive not in ALLOWED_DIRECTIVES:
                raise AsmError("E-LEX-005",
                               f"unknown directive '{directive}' "
                               f"(allowed: .equ, .dw)",
                               line=line_no, filename=filename)
            first_instruction_seen = True
            statements.append(Statement(
                filename=filename, line=line_no,
                label=label, mnemonic=None,
                directive=directive,
                operands=_tokenize_operands(rest),
            ))
            continue

        # Mnemonic: case-insensitive; normalise to UPPER.
        upper = head.upper()
        # Retired-mnemonic check (E-LEX-006) runs first so the
        # diagnostic can point at the canonical replacement.
        if upper in RETIRED_MNEMONICS:
            raise AsmError("E-LEX-006",
                           f"'{upper}' was retired in v0.2; "
                           f"use {RETIRED_MNEMONICS[upper]}",
                           line=line_no, filename=filename)
        if upper not in MNEMONICS:
            raise AsmError("E-LEX-001",
                           f"unknown mnemonic: '{upper}' "
                           f"(note: in v0.2 mnemonics are case-insensitive)",
                           line=line_no, filename=filename)

        first_instruction_seen = True
        statements.append(Statement(
            filename=filename, line=line_no,
            label=label, mnemonic=upper, directive=None,
            operands=_tokenize_operands(rest),
        ))

    return statements, raw_mode


# ---------------------------------------------------------------------------
# Pass 1: build symbol table + assign PCs
# ---------------------------------------------------------------------------

def _pc_advance(stmt: Statement) -> int:
    if stmt.directive == ".equ":
        return 0
    if stmt.directive == ".dw":
        if not stmt.operands:
            raise AsmError("E-FRM-003",
                           ".dw directive with no operands "
                           "(would emit zero body words; supply at "
                           "least one literal or equate)",
                           line=stmt.line, filename=stmt.filename)
        n = len(stmt.operands)
        if n > MAX_PROGRAM_WORDS:
            raise AsmError("E-FRM-001",
                           f".dw operand count {n} exceeds "
                           f"program-memory budget of {MAX_PROGRAM_WORDS} words",
                           line=stmt.line, filename=stmt.filename)
        return n
    if stmt.mnemonic is not None:
        return 1
    return 0


def pass1(statements: List[Statement],
          filename: str) -> Tuple[SymbolTable, List[Tuple[int, Statement]]]:
    """Build symbol table; return (symtab, [(pc, stmt), ...]) for encodeable stmts."""
    syms = SymbolTable()
    pc_stmts: List[Tuple[int, Statement]] = []
    pc = 0
    last_stmt: Optional[Statement] = None

    for stmt in statements:
        last_stmt = stmt
        if stmt.label is not None:
            syms.bind_label(stmt.label, pc, stmt.line, stmt.filename)

        if stmt.directive == ".equ":
            if len(stmt.operands) != 2:
                raise AsmError("E-OP-001",
                               ".equ takes exactly two operands: NAME, VALUE",
                               line=stmt.line, filename=stmt.filename)
            name = stmt.operands[0]
            value = _resolve_literal_or_equate(
                stmt.operands[1], syms, stmt.line, stmt.filename)
            syms.bind_equate(name, value, stmt.line, stmt.filename)
            continue

        advance = _pc_advance(stmt)
        if advance > 0:
            pc_stmts.append((pc, stmt))
        pc += advance
        if pc > MAX_PROGRAM_WORDS:
            raise AsmError("E-RNG-002",
                           f"program exceeds {MAX_PROGRAM_WORDS} instruction "
                           f"slots (PC overflow)",
                           line=stmt.line, filename=stmt.filename)

    # Reject programs with no body words (E-FRM-003).
    if pc == 0:
        if last_stmt is not None:
            loc_line = last_stmt.line
            loc_file = last_stmt.filename
        else:
            # Utterly empty source: use filename + line 1.
            loc_line = 1
            loc_file = filename
        raise AsmError("E-FRM-003",
                       "program contains no instructions "
                       "(body would be zero words; the canonical "
                       "minimum is a single HALT)",
                       line=loc_line, filename=loc_file)

    return syms, pc_stmts


# ---------------------------------------------------------------------------
# Pass 2: helpers
# ---------------------------------------------------------------------------

def _parse_kv_operands(stmt: Statement, allowed: List[str]) -> Dict[str, str]:
    """Parse key=value operands; raise on unknown key or duplicate."""
    out: Dict[str, str] = {}
    for tok in stmt.operands:
        if "=" not in tok:
            raise AsmError("E-OP-004",
                           f"expected key=value operand, got positional "
                           f"token '{tok}'",
                           line=stmt.line, filename=stmt.filename)
        key, val = tok.split("=", 1)
        if key not in allowed:
            sorted_allowed = sorted(allowed)
            raise AsmError("E-OP-002",
                           f"unknown operand key '{key}' "
                           f"(allowed: {sorted_allowed!r})",
                           line=stmt.line, filename=stmt.filename)
        if key in out:
            raise AsmError("E-OP-003",
                           f"duplicate operand key '{key}'",
                           line=stmt.line, filename=stmt.filename)
        out[key] = val
    return out


def _resolve_flag_triple(kv: Dict[str, str], stmt: Statement
                          ) -> Tuple[bool, bool, bool]:
    """Resolve expect/mask/capture from a kv dict."""
    expect_str = kv.get("expect", "X").upper()
    mask_str = kv.get("mask", "0")
    capture_str = kv.get("capture", "0")

    if expect_str not in ("0", "1", "X"):
        raise AsmError("E-OP-005",
                       f"expect must be 0|1|X, got '{expect_str}'",
                       line=stmt.line, filename=stmt.filename)
    if mask_str not in ("0", "1"):
        raise AsmError("E-OP-002",
                       f"mask must be 0|1, got '{mask_str}'",
                       line=stmt.line, filename=stmt.filename)
    if capture_str not in ("0", "1"):
        raise AsmError("E-OP-002",
                       f"capture must be 0|1, got '{capture_str}'",
                       line=stmt.line, filename=stmt.filename)

    mask = mask_str == "1"
    capture = capture_str == "1"

    if expect_str == "X":
        if mask:
            raise AsmError("E-OP-005",
                           "expect=X is don't-care and cannot be combined "
                           "with mask=1; set an explicit expect=0|1",
                           line=stmt.line, filename=stmt.filename)
        expect = False
    else:
        expect = expect_str == "1"

    return expect, mask, capture


def _resolve_tx_key(kv: Dict[str, str], key: str, stmt: Statement,
                    raw_mode: bool) -> int:
    """Resolve a tx=<sym> key.  Case-insensitive; raw_mode enables reserved."""
    if key not in kv:
        raise AsmError("E-OP-001",
                       f"missing required operand: {key}=<tx-symbol>",
                       line=stmt.line, filename=stmt.filename)
    sym = kv[key]
    lower = sym.lower()
    if lower in TX_SYMBOLS:
        return TX_SYMBOLS[lower]
    # raw/ mode: accept "reserved", "0b11", or "3".
    if raw_mode and lower in ("reserved", "0b11", "3"):
        return 0b11
    # Numeric literals 0..2 also accepted.
    try:
        n = int(sym)
        if 0 <= n <= 2:
            return n
        if n == 3:
            if raw_mode:
                return 3
            raise AsmError("E-WIRE-001",
                           "tx=reserved (0b11) requires '(use-raw-primitives)' "
                           "pragma",
                           line=stmt.line, filename=stmt.filename)
    except ValueError:
        pass
    raise AsmError("E-OP-002",
                   f"{key}='{sym}' is not a named tx symbol "
                   f"(allowed: {sorted(TX_SYMBOLS)!r})",
                   line=stmt.line, filename=stmt.filename)


def _resolve_cond(tok: str, stmt: Statement) -> int:
    upper = tok.upper()
    if upper not in COND_CODES:
        raise AsmError("E-LEX-004",
                       f"cond code '{tok}' is not named "
                       f"(allowed: {sorted(COND_CODES)!r})",
                       line=stmt.line, filename=stmt.filename)
    return COND_CODES[upper]


def _resolve_register(tok: str, stmt: Statement) -> int:
    n = _parse_reg(tok)
    if n is None:
        raise AsmError("E-OP-007",
                       f"register '{tok}' is not valid; use R0..R7",
                       line=stmt.line, filename=stmt.filename)
    return n


def _resolve_branch_target(tok: str, branch_pc: int,
                            syms: SymbolTable, stmt: Statement) -> int:
    if _looks_like_ident(tok):
        label_pc = syms.get_label(tok, stmt.line, stmt.filename)
        offset = label_pc - branch_pc - 1
    else:
        offset = _parse_int(tok, stmt.line, stmt.filename)
    if not (-512 <= offset <= 511):
        raise AsmError("E-RNG-003",
                       f"BRANCH_ON offset {offset} out of signed 10-bit "
                       f"range (branch_pc={branch_pc})",
                       line=stmt.line, filename=stmt.filename)
    return offset


def _bus_mode_is_pp(wire: int) -> bool:
    """True iff mode wire value is a push-pull class (§9, AGENTS §3.13)."""
    return wire in (2, 3)


# ---------------------------------------------------------------------------
# EMIT_BYTE pairing check  (§5.5, E-WIRE-003)
# ---------------------------------------------------------------------------

def _find_emit_byte_pair(pc_stmts: List[Tuple[int, Statement]],
                          start: int,
                          syms: SymbolTable) -> bool:
    """Return True if a valid pairing instruction follows EMIT_BYTE mask=1."""
    for _, stmt in pc_stmts[start:]:
        mne = stmt.mnemonic
        if mne is None:
            # .dw directive: skip.
            continue
        # BRANCH_ON MISMATCH, <target> — case-insensitive.
        if mne == "BRANCH_ON":
            if (stmt.operands
                    and stmt.operands[0].upper() == "MISMATCH"):
                return True
        # FLAG_CLEAR with bit 0 set in the mask operand.
        if mne == "FLAG_CLEAR":
            if stmt.operands:
                mask_tok = stmt.operands[0]
                if _looks_like_ident(mask_tok):
                    try:
                        m = syms.get_any(mask_tok, stmt.line,
                                         stmt.filename)
                        if m & 1:
                            return True
                    except AsmError:
                        pass
                else:
                    m = _parse_int_raw(mask_tok)
                    if m is not None and m & 1:
                        return True
        # Any other instruction: unpaired.
        return False
    return False


# ---------------------------------------------------------------------------
# Pass 2: encode instructions
# ---------------------------------------------------------------------------

def _try_parse_kv_or_positional(stmt: Statement, keys: List[str],
                                  expected_count: int) -> Dict[str, str]:
    """Parse key=value or positional operands (for LOAD_TIMING)."""
    any_kv = any("=" in o for o in stmt.operands)
    if any_kv:
        return _parse_kv_operands(stmt, keys)
    if len(stmt.operands) != expected_count:
        raise AsmError("E-OP-001",
                       f"expected {expected_count} positional operands, "
                       f"got {len(stmt.operands)}",
                       line=stmt.line, filename=stmt.filename)
    return dict(zip(keys, stmt.operands))


def _encode_mnemonic(mne: str, stmt: Statement, pc: int,
                     syms: SymbolTable,
                     active_role: Optional[bool],
                     active_bus_mode: Optional[int],
                     raw_mode: bool,
                     pc_stmts: List[Tuple[int, Statement]],
                     stmt_idx: int) -> int:
    ln = stmt.line
    fn = stmt.filename

    # ------------------------------------------------------------------
    if mne == "HALT":
        kv = _parse_kv_operands(stmt, ["status"])
        status_tok = kv.get("status", "0")
        status = _resolve_literal_or_equate(status_tok, syms, ln, fn)
        return enc_halt(int(status), ln, fn)

    # ------------------------------------------------------------------
    if mne == "EMIT_BIT_IMM":
        kv = _parse_kv_operands(stmt, ["tx", "expect", "mask", "capture"])
        tx = _resolve_tx_key(kv, "tx", stmt, raw_mode)
        e, mk, c = _resolve_flag_triple(kv, stmt)
        return enc_emit_bit_imm(tx, e, mk, c, raw_mode, ln, fn)

    # ------------------------------------------------------------------
    if mne == "EMIT_BIT_REG":
        kv = _parse_kv_operands(stmt, ["src", "expect", "mask", "capture"])
        if "src" not in kv:
            raise AsmError("E-OP-001",
                           "EMIT_BIT_REG requires src=<reg>", ln, fn)
        src = _resolve_register(kv["src"], stmt)
        e, mk, c = _resolve_flag_triple(kv, stmt)
        return enc_emit_bit_reg(src, e, mk, c, ln, fn)

    # ------------------------------------------------------------------
    if mne == "EMIT_QUARTER_IMM":
        kv = _parse_kv_operands(stmt,
                                 ["sda", "scl", "expect", "mask", "capture"])
        sda = _resolve_tx_key(kv, "sda", stmt, raw_mode)
        scl = _resolve_tx_key(kv, "scl", stmt, raw_mode)
        e, mk, c = _resolve_flag_triple(kv, stmt)
        # §3.13 / §5.3: target role under PP-class: scl=recessive illegal.
        if (not raw_mode
                and active_role is True
                and active_bus_mode is not None
                and _bus_mode_is_pp(active_bus_mode)
                and scl == 0b01):
            mode_name = {2: "i3c-PP", 3: "hdr-ddr"}.get(
                active_bus_mode, "<pp>")
            raise AsmError("E-WIRE-002",
                           f"EMIT_QUARTER scl=recessive is illegal in "
                           f"target role under PP-class BUS_MODE "
                           f"({mode_name})",
                           ln, fn)
        return enc_emit_quarter_imm(sda, scl, e, mk, c, raw_mode, ln, fn)

    # ------------------------------------------------------------------
    if mne == "EMIT_QUARTER_REG":
        kv = _parse_kv_operands(stmt, ["src", "expect", "mask", "capture"])
        if "src" not in kv:
            raise AsmError("E-OP-001",
                           "EMIT_QUARTER_REG requires src=<reg>", ln, fn)
        src = _resolve_register(kv["src"], stmt)
        e, mk, c = _resolve_flag_triple(kv, stmt)
        return enc_emit_quarter_reg(src, e, mk, c, ln, fn)

    # ------------------------------------------------------------------
    # EMIT_BYTE_REG (canonical). Bare `EMIT_BYTE` was sugar for
    # EMIT_BYTE_REG in early v0.2 drafts; the bare form is retired
    # (E-LEX-006, raised at lex time before reaching here).
    if mne == "EMIT_BYTE_REG":
        kv = _parse_kv_operands(stmt, ["expect", "mask", "capture"])
        e, mk, c = _resolve_flag_triple(kv, stmt)
        # §5.5 E-WIRE-003: mask=1 must be followed by pairing instruction.
        if mk and not raw_mode:
            if not _find_emit_byte_pair(pc_stmts, stmt_idx + 1, syms):
                raise AsmError("E-WIRE-003",
                               f"EMIT_BYTE_REG mask=1 at line {ln} must be "
                               f"followed by BRANCH_ON MISMATCH or "
                               f"FLAG_CLEAR with bit 0 set; declare "
                               f"'(use-raw-primitives)' to suppress",
                               ln, fn)
        return enc_emit_byte_reg(e, mk, c)

    # ------------------------------------------------------------------
    # EMIT_BYTE_IMM imm=<byte> [expect=... mask=... capture=...]
    # Single-instruction byte emit with the payload in the instruction
    # word (§5.5b); does not read R7. Same ACK-pairing rule as
    # EMIT_BYTE_REG.
    if mne == "EMIT_BYTE_IMM":
        kv = _parse_kv_operands(stmt, ["imm", "expect", "mask", "capture"])
        if "imm" not in kv:
            raise AsmError("E-OP-001",
                           "EMIT_BYTE_IMM requires imm=<byte>", ln, fn)
        imm_val = _parse_int(kv["imm"], ln, fn)
        if not (0 <= imm_val <= 255):
            raise AsmError("E-RNG-001",
                           f"EMIT_BYTE_IMM imm must be 0..255, "
                           f"got {imm_val}",
                           ln, fn)
        e, mk, c = _resolve_flag_triple(kv, stmt)
        # §5.5b inherits §5.5 E-WIRE-003 pairing rule.
        if mk and not raw_mode:
            if not _find_emit_byte_pair(pc_stmts, stmt_idx + 1, syms):
                raise AsmError("E-WIRE-003",
                               f"EMIT_BYTE_IMM mask=1 at line {ln} must be "
                               f"followed by BRANCH_ON MISMATCH or "
                               f"FLAG_CLEAR with bit 0 set; declare "
                               f"'(use-raw-primitives)' to suppress",
                               ln, fn)
        return enc_emit_byte_imm(imm_val, e, mk, c, ln, fn)

    # ------------------------------------------------------------------
    if mne == "SAMPLE_BIT_ON_SCL":
        allowed = (["dst", "expect", "mask", "capture"] if raw_mode
                   else ["expect", "mask", "capture"])
        kv = _parse_kv_operands(stmt, allowed)
        e, mk, c = _resolve_flag_triple(kv, stmt)
        if "dst" in kv:
            dst = _resolve_register(kv["dst"], stmt)
            if dst != 7 and not raw_mode:
                raise AsmError("E-REG-001",
                               "capture must write to R7; use raw/ pragma "
                               "to override", ln, fn)
        else:
            dst = 7   # canonical
        return enc_sample_bit_on_scl(dst, e, mk, c, raw_mode, ln, fn)

    # ------------------------------------------------------------------
    if mne == "DRIVE_BIT_ON_SCL":
        allowed = (["tx", "dst", "expect", "mask", "capture"] if raw_mode
                   else ["tx", "expect", "mask", "capture"])
        kv = _parse_kv_operands(stmt, allowed)
        tx = _resolve_tx_key(kv, "tx", stmt, raw_mode)
        e, mk, c = _resolve_flag_triple(kv, stmt)
        if "dst" in kv:
            if not raw_mode:
                raise AsmError("E-RAW-001",
                               "dst override on DRIVE_BIT_ON_SCL requires "
                               "'(use-raw-primitives)'", ln, fn)
            dst = _resolve_register(kv["dst"], stmt)
            return enc_drive_bit_on_scl_raw(dst, tx, e, mk, c, ln, fn)
        return enc_drive_bit_on_scl(tx, e, mk, c, raw_mode, ln, fn)

    # ------------------------------------------------------------------
    if mne == "STRETCH_SCL_IMM":
        if len(stmt.operands) != 1:
            raise AsmError("E-OP-001",
                           "STRETCH_SCL_IMM takes one positional operand: "
                           "n_quarters", ln, fn)
        n = _resolve_literal_or_equate(stmt.operands[0], syms, ln, fn)
        return enc_stretch_scl_imm(int(n), ln, fn)

    # ------------------------------------------------------------------
    if mne == "STRETCH_SCL_REG":
        kv = _parse_kv_operands(stmt, ["src"])
        if "src" not in kv:
            raise AsmError("E-OP-001",
                           "STRETCH_SCL_REG requires src=<reg>", ln, fn)
        src = _resolve_register(kv["src"], stmt)
        return enc_stretch_scl_reg(src, ln, fn)

    # ------------------------------------------------------------------
    if mne == "BRANCH_ON":
        if len(stmt.operands) != 2:
            raise AsmError("E-OP-001",
                           "BRANCH_ON takes two operands: cond, target",
                           ln, fn)
        cond = _resolve_cond(stmt.operands[0], stmt)
        if cond >= 12 and not raw_mode:
            raise AsmError("E-CTRL-001",
                           f"cond code {cond} is reserved; values 12..15 "
                           f"require raw/ pragma", ln, fn)
        offset = _resolve_branch_target(stmt.operands[1], pc, syms, stmt)
        return enc_branch_on(cond, int(offset), ln, fn)

    # ------------------------------------------------------------------
    if mne == "WAIT_ON":
        if len(stmt.operands) != 2:
            raise AsmError("E-OP-001",
                           "WAIT_ON takes two operands: cond, timeout",
                           ln, fn)
        cond = _resolve_cond(stmt.operands[0], stmt)
        if cond >= 12 and not raw_mode:
            raise AsmError("E-CTRL-001",
                           f"cond code {cond} is reserved; values 12..15 "
                           f"require raw/ pragma", ln, fn)
        timeout = _resolve_literal_or_equate(stmt.operands[1], syms, ln, fn)
        return enc_wait_on(cond, int(timeout), ln, fn)

    # ------------------------------------------------------------------
    if mne == "SET_BUS_MODE":
        if len(stmt.operands) != 1:
            raise AsmError("E-OP-001",
                           "SET_BUS_MODE takes one operand: <bus-mode>",
                           ln, fn)
        raw_tok = stmt.operands[0]
        lower = raw_tok.lower()
        if not raw_mode:
            if raw_tok[0].isdigit():
                raise AsmError("E-OP-006",
                               f"bus mode '{raw_tok}' is not named; "
                               f"use i2c, i3c-OD, i3c-PP, or hdr-ddr",
                               ln, fn)
            if not _is_valid_bus_mode_ident(lower):
                raise AsmError("E-OP-006",
                               f"bus mode '{raw_tok}' is not named "
                               f"(allowed: i2c, i3c-OD, i3c-PP, hdr-ddr)",
                               ln, fn)
            if lower not in BUS_MODES:
                raise AsmError("E-OP-006",
                               f"bus mode '{raw_tok}' is not named "
                               f"(allowed: {sorted(BUS_MODES)!r})",
                               ln, fn)
            return enc_set_bus_mode(BUS_MODES[lower], ln, fn)
        # raw/ mode: named or numeric.
        if lower in BUS_MODES:
            return enc_set_bus_mode(BUS_MODES[lower], ln, fn)
        n = _resolve_literal_or_equate(raw_tok, syms, ln, fn)
        if not (0 <= n < 16):
            raise AsmError("E-RNG-001",
                           f"SET_BUS_MODE mode must be 0..15, got {n}",
                           ln, fn)
        return enc_set_bus_mode(int(n), ln, fn)

    # ------------------------------------------------------------------
    if mne == "SET_ROLE":
        if len(stmt.operands) != 1:
            raise AsmError("E-OP-001",
                           "SET_ROLE takes one operand: controller|target",
                           ln, fn)
        tok = stmt.operands[0].lower()
        if tok not in ROLE_NAMES:
            raise AsmError("E-OP-008",
                           f"SET_ROLE operand must be 'controller' or "
                           f"'target', got '{stmt.operands[0]}'",
                           ln, fn)
        return enc_set_role(ROLE_NAMES[tok] != 0)

    # ------------------------------------------------------------------
    if mne == "FLAG_CLEAR":
        if len(stmt.operands) != 1:
            raise AsmError("E-OP-001",
                           "FLAG_CLEAR takes one operand: <5-bit mask>",
                           ln, fn)
        mask_val = _resolve_literal_or_equate(stmt.operands[0], syms, ln, fn)
        if not (0 <= mask_val <= 31):
            raise AsmError("E-OP-009",
                           f"FLAG_CLEAR mask must be a 5-bit value "
                           f"(0..31), got {mask_val}",
                           ln, fn)
        return enc_flag_clear(int(mask_val), ln, fn)

    # ------------------------------------------------------------------
    if mne == "MARK":
        kv = _parse_kv_operands(stmt, ["label"])
        if "label" not in kv:
            raise AsmError("E-OP-001",
                           "MARK requires label=<0..16383>", ln, fn)
        label_val = _resolve_literal_or_equate(kv["label"], syms, ln, fn)
        return enc_mark(int(label_val), ln, fn)

    # ------------------------------------------------------------------
    if mne == "LOAD_TIMING":
        kv = _try_parse_kv_or_positional(stmt, ["reg", "divider"], 2)
        if "reg" not in kv:
            raise AsmError("E-OP-001",
                           "LOAD_TIMING requires reg=<0..7>", ln, fn)
        if "divider" not in kv:
            raise AsmError("E-OP-001",
                           "LOAD_TIMING requires divider=<0..16383>", ln, fn)
        reg = _resolve_literal_or_equate(kv["reg"], syms, ln, fn)
        divider = _resolve_literal_or_equate(kv["divider"], syms, ln, fn)
        return enc_load_timing(int(reg), int(divider), ln, fn)

    # ------------------------------------------------------------------
    if mne == "LOAD_IMM":
        if len(stmt.operands) != 2:
            raise AsmError("E-OP-001",
                           "LOAD_IMM takes two operands: Rn, imm",
                           ln, fn)
        dst = _resolve_register(stmt.operands[0], stmt)
        imm = _resolve_literal_or_equate(stmt.operands[1], syms, ln, fn)
        return enc_load_imm(dst, int(imm), ln, fn)

    # ------------------------------------------------------------------
    if mne == "MOV":
        if len(stmt.operands) != 2:
            raise AsmError("E-OP-001",
                           "MOV takes two operands: Rdst, Rsrc", ln, fn)
        dst = _resolve_register(stmt.operands[0], stmt)
        src = _resolve_register(stmt.operands[1], stmt)
        return enc_mov(dst, src, ln, fn)

    # ------------------------------------------------------------------
    if mne == "ADD_IMM":
        if len(stmt.operands) != 3:
            raise AsmError("E-OP-001",
                           "ADD_IMM takes three operands: Rdst, Rsrc, imm",
                           ln, fn)
        dst = _resolve_register(stmt.operands[0], stmt)
        src = _resolve_register(stmt.operands[1], stmt)
        imm = _resolve_literal_or_equate(stmt.operands[2], syms, ln, fn)
        return enc_add_imm(dst, src, int(imm), ln, fn)

    # ------------------------------------------------------------------
    if mne == "DEC":
        if len(stmt.operands) != 1:
            raise AsmError("E-OP-001",
                           "DEC takes one operand: Rn", ln, fn)
        reg = _resolve_register(stmt.operands[0], stmt)
        return enc_dec(reg, ln, fn)

    # ------------------------------------------------------------------
    if mne == "AND_IMM":
        if len(stmt.operands) != 3:
            raise AsmError("E-OP-001",
                           "AND_IMM takes three operands: Rdst, Rsrc, imm",
                           ln, fn)
        dst = _resolve_register(stmt.operands[0], stmt)
        src = _resolve_register(stmt.operands[1], stmt)
        imm = _resolve_literal_or_equate(stmt.operands[2], syms, ln, fn)
        return enc_and_imm(dst, src, int(imm), ln, fn)

    # ------------------------------------------------------------------
    if mne == "OR_IMM":
        if len(stmt.operands) != 3:
            raise AsmError("E-OP-001",
                           "OR_IMM takes three operands: Rdst, Rsrc, imm",
                           ln, fn)
        dst = _resolve_register(stmt.operands[0], stmt)
        src = _resolve_register(stmt.operands[1], stmt)
        imm = _resolve_literal_or_equate(stmt.operands[2], syms, ln, fn)
        return enc_or_imm(dst, src, int(imm), ln, fn)

    # ------------------------------------------------------------------
    if mne == "XOR_IMM":
        if len(stmt.operands) != 3:
            raise AsmError("E-OP-001",
                           "XOR_IMM takes three operands: Rdst, Rsrc, imm",
                           ln, fn)
        dst = _resolve_register(stmt.operands[0], stmt)
        src = _resolve_register(stmt.operands[1], stmt)
        imm = _resolve_literal_or_equate(stmt.operands[2], syms, ln, fn)
        return enc_xor_imm(dst, src, int(imm), ln, fn)

    # ------------------------------------------------------------------
    if mne == "SHIFT":
        if len(stmt.operands) != 4:
            raise AsmError("E-OP-001",
                           "SHIFT takes four operands: Rdst, Rsrc, "
                           "direction, shamt (direction: left|right|aright)",
                           ln, fn)
        dst = _resolve_register(stmt.operands[0], stmt)
        src = _resolve_register(stmt.operands[1], stmt)
        dir_tok = stmt.operands[2].lower()
        if dir_tok not in SHIFT_DIRS:
            raise AsmError("E-OP-002",
                           f"unknown shift direction '{stmt.operands[2]}'; "
                           f"use left, right, or aright (not aleft)",
                           ln, fn)
        shift_kind = SHIFT_DIRS[dir_tok]
        shamt = _resolve_literal_or_equate(stmt.operands[3], syms, ln, fn)
        return enc_shift(dst, src, shift_kind, int(shamt), ln, fn)

    # ------------------------------------------------------------------
    # Sugar: JMP <label>  →  BRANCH_ON ALWAYS, <label>
    if mne == "JMP":
        if len(stmt.operands) != 1:
            raise AsmError("E-OP-001",
                           "JMP takes one operand: <label>", ln, fn)
        offset = _resolve_branch_target(stmt.operands[0], pc, syms, stmt)
        return enc_branch_on(COND_CODES["ALWAYS"], int(offset), ln, fn)

    # ------------------------------------------------------------------
    # Sugar: LOAD_LOOP n  →  LOAD_IMM R6, n
    if mne == "LOAD_LOOP":
        if len(stmt.operands) != 1:
            raise AsmError("E-OP-001",
                           "LOAD_LOOP takes one operand: n "
                           "(sugar for LOAD_IMM R6, n)", ln, fn)
        imm = _resolve_literal_or_equate(stmt.operands[0], syms, ln, fn)
        return enc_load_imm(6, int(imm), ln, fn)

    # Unreachable (lex already validated the mnemonic).
    raise AsmError("E-LEX-001", f"unhandled mnemonic: '{mne}'", ln, fn)


def pass2(syms: SymbolTable, pc_stmts: List[Tuple[int, Statement]],
          raw_mode: bool) -> List[int]:
    """Encode statements to 32-bit words."""
    out: List[int] = []
    active_role: Optional[bool] = None      # False=ctrl, True=target
    active_bus_mode: Optional[int] = None

    for i, (pc, stmt) in enumerate(pc_stmts):
        # .dw directive
        if stmt.directive == ".dw":
            for tok in stmt.operands:
                val = _resolve_literal_or_equate(
                    tok, syms, stmt.line, stmt.filename)
                if not (0 <= val <= 0xFFFF_FFFF):
                    raise AsmError("E-FRM-002",
                                   f".dw value {val:#x} out of 32-bit range",
                                   stmt.line, stmt.filename)
                out.append(val)
            continue

        mne = stmt.mnemonic
        assert mne is not None, "pc_stmts only contains directives or mnemonics"

        word = _encode_mnemonic(
            mne, stmt, pc, syms,
            active_role, active_bus_mode, raw_mode,
            pc_stmts, i,
        )

        # Update linear role/bus-mode tracker.
        if mne == "SET_ROLE":
            active_role = bool(word & (1 << 3))
        elif mne == "SET_BUS_MODE":
            active_bus_mode = (word >> 3) & 0xF

        out.append(word)

    return out


# ---------------------------------------------------------------------------
# Top-level assemble()
# ---------------------------------------------------------------------------

def assemble(source: str, filename: str) -> List[int]:
    """Assemble moleasm source into a list of 32-bit words.

    Returns the 2-word preamble followed by the instruction stream.

    Word 0: PREAMBLE_MAGIC = 0x0002_4D4C (bytes on wire: 4C 4D 02 00).
    Word 1: body length in 32-bit words (body only, preamble excluded).
    """
    statements, raw_mode = lex(source, filename)
    syms, pc_stmts = pass1(statements, filename)
    body = pass2(syms, pc_stmts, raw_mode)
    program = [PREAMBLE_MAGIC, len(body)] + body
    return program


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def _cli_main(argv: List[str]) -> int:
    parser = argparse.ArgumentParser(
        prog="mole-asm.py",
        description="Mole v0.2 moleasm assembler (golden Python oracle).",
    )
    parser.add_argument("input", nargs="?",
                        help=".moleasm source file (omit to run self-check)")
    parser.add_argument("-o", "--output", metavar="OUT",
                        help="output .molecode path (default: <input>.molecode)")
    parser.add_argument("--frame", action="store_true",
                        help="also write framed .mole.bin")
    args = parser.parse_args(argv)

    if args.input is None:
        return _self_check()

    src_path = Path(args.input)
    if not src_path.exists():
        print(f"error: input file not found: {src_path}", file=sys.stderr)
        return 1

    source = src_path.read_text(encoding="utf-8")
    try:
        words = assemble(source, str(src_path))
    except AsmError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1

    # Default output: INPUT.molecode
    out_path = Path(args.output) if args.output else src_path.with_suffix(".molecode")
    out_path.write_bytes(pack_bytecode(words))
    print(f"wrote {out_path}  ({len(words)} words, "
          f"{len(words) - 2} body words)")

    if args.frame:
        frame_path = src_path.with_suffix(".mole.bin")
        frame_path.write_bytes(build_frame(words))
        print(f"wrote {frame_path}  (framed)")

    return 0


# ---------------------------------------------------------------------------
# Self-check suite
# ---------------------------------------------------------------------------

def _self_check() -> int:
    """Run the self-check suite.  Returns 0 on success, 1 on failure."""
    failures: List[str] = []
    checks = 0

    def ok(desc: str) -> None:
        nonlocal checks
        checks += 1

    def fail(desc: str, detail: str) -> None:
        failures.append(f"FAIL [{desc}]: {detail}")

    def check(desc: str, condition: bool, detail: str = "") -> None:
        if condition:
            ok(desc)
        else:
            fail(desc, detail)

    # ------------------------------------------------------------------
    # 1. CRC catalog
    check("CRC catalog: 0x31C3",
          crc16_xmodem(b"123456789") == 0x31C3,
          f"got {crc16_xmodem(b'123456789'):#06x}")

    # 2. CRC empty
    check("CRC empty: 0x0000",
          crc16_xmodem(b"") == 0x0000,
          f"got {crc16_xmodem(b''):#06x}")

    # ------------------------------------------------------------------
    # 3. §12.4 worked examples (26 total: 25 live opcodes + §5.5b EMIT_BYTE_IMM)
    #
    # §5.11: the example uses a label "loop_top" that is 4 words before
    # the BRANCH_ON.  We lay out the source to produce exactly offset=-4.
    # For each example we record (source, expected_first_body_word, section).
    # For single-instruction sources words[2] IS the target opcode.
    # §5.5 needs a pairing suffix (BRANCH_ON MISMATCH); words[2] is
    # EMIT_BYTE_REG (or EMIT_BYTE_IMM for §5.5b).
    # §5.11 encodes a numeric offset directly so the source is single-insn.
    SPEC_12_4_EXAMPLES = [
        # (source, expected_first_body_word, spec_section)
        # §5.1
        ("EMIT_BIT_IMM tx=recessive\n",
         0x0000_0008, "§5.1"),
        # §5.2
        ("EMIT_BIT_REG src=R2 capture=1\n",
         0x0420_0001, "§5.2"),
        # §5.3
        ("EMIT_QUARTER_IMM sda=dominant scl=hiz\n",
         0x0800_0040, "§5.3"),
        # §5.4
        ("EMIT_QUARTER_REG src=R1\n",
         0x0C10_0000, "§5.4"),
        # §5.5 - EMIT_BYTE_REG needs pairing; suffix with BRANCH_ON
        # MISMATCH. words[2] is the EMIT_BYTE_REG (the opcode under test).
        ("EMIT_BYTE_REG expect=0 mask=1 capture=1\nBRANCH_ON MISMATCH, done\ndone: HALT\n",
         0x1000_0003, "§5.5"),
        # §5.5b - EMIT_BYTE_IMM imm=0x48, no flags (single-insn source).
        # group=00 sub=1001 → 0x2400_0000; imm=0x48 at [10:3] → 0x240.
        ("EMIT_BYTE_IMM imm=0x48\n",
         0x2400_0240, "§5.5b"),
        # §5.6
        ("SAMPLE_BIT_ON_SCL capture=1\n",
         0x1780_0001, "§5.6"),
        # §5.7
        ("DRIVE_BIT_ON_SCL tx=dominant\n",
         0x1800_0000, "§5.7"),
        # §5.8
        ("STRETCH_SCL_IMM 400\n",
         0x1C00_0C80, "§5.8"),
        # §5.9
        ("STRETCH_SCL_REG src=R0\n",
         0x2000_0000, "§5.9"),
        # §5.10
        ("HALT status=2\n",
         0x4000_0010, "§5.10"),
        # §5.11 BRANCH_ON MISMATCH, offset=-4.
        # The spec says "(assume loop_top is 4 words back: offset=-4)".
        # We encode the offset as a raw literal to keep this single-insn.
        ("BRANCH_ON MISMATCH, -4\n",
         0x4400_3FE0, "§5.11"),
        # §5.12 WAIT_ON SDA_LOW, 64
        ("WAIT_ON SDA_LOW, 64\n",
         0x4800_A200, "§5.12"),
        # §5.13 SET_BUS_MODE i3c-PP
        ("SET_BUS_MODE i3c-PP\n",
         0x4C00_0010, "§5.13"),
        # §5.14 SET_ROLE target
        ("SET_ROLE target\n",
         0x5000_0008, "§5.14"),
        # §5.15 FLAG_CLEAR 0b00001
        ("FLAG_CLEAR 0b00001\n",
         0x5400_0008, "§5.15"),
        # §5.16 MARK label=42
        ("MARK label=42\n",
         0x5800_0150, "§5.16"),
        # §5.17 LOAD_TIMING reg=0, divider=480
        ("LOAD_TIMING reg=0, divider=480\n",
         0x5C00_0F00, "§5.17"),
        # §5.18 LOAD_IMM R3, 100
        ("LOAD_IMM R3, 100\n",
         0x8180_0320, "§5.18"),
        # §5.19 MOV R1, R0
        ("MOV R1, R0\n",
         0x8480_0000, "§5.19"),
        # §5.20 ADD_IMM R0, R0, 4
        ("ADD_IMM R0, R0, 4\n",
         0x8800_0020, "§5.20"),
        # §5.21 DEC R6
        ("DEC R6\n",
         0x8F60_0000, "§5.21"),
        # §5.22 AND_IMM R0, R0, 0xFF
        ("AND_IMM R0, R0, 0xFF\n",
         0x9000_07F8, "§5.22"),
        # §5.23 OR_IMM R0, R0, 0x01
        ("OR_IMM R0, R0, 0x01\n",
         0x9400_0008, "§5.23"),
        # §5.24 XOR_IMM R0, R0, 0xFF
        ("XOR_IMM R0, R0, 0xFF\n",
         0x9800_07F8, "§5.24"),
        # §5.25 SHIFT R0, R0, left, 1
        ("SHIFT R0, R0, left, 1\n",
         0x9C00_0008, "§5.25"),
    ]

    for src, expected_hex, where in SPEC_12_4_EXAMPLES:
        try:
            words = assemble(src, "<inline>")
            # words[0] = preamble magic, words[1] = body length,
            # words[2] = first body word (the opcode under test in all cases).
            if len(words) < 3:
                fail(f"§12.4 {where}",
                     f"assembled to empty body; src={src!r}")
                continue
            got = words[2]
            if got != expected_hex:
                fail(f"§12.4 {where}",
                     f"assembled {got:#010x}, want {expected_hex:#010x}, "
                     f"src={src!r}")
            else:
                ok(f"§12.4 {where}")
        except AsmError as e:
            fail(f"§12.4 {where}", f"raised {e!r}; src={src!r}")

    # ------------------------------------------------------------------
    # 4. BUS_MODE renumbering: i3c-PP → wire value 2 (NOT 6 as in v0)
    try:
        words = assemble("SET_BUS_MODE i3c-PP\n", "<inline>")
        got_mode = (words[2] >> 3) & 0xF
        check("BUS_MODE renumbering i3c-PP=2",
              got_mode == 2,
              f"extracted mode field = {got_mode}, want 2")
    except AsmError as e:
        fail("BUS_MODE renumbering", str(e))

    # 4b. hdr-ddr → wire value 3
    try:
        words = assemble("SET_BUS_MODE hdr-ddr\n", "<inline>")
        got_mode = (words[2] >> 3) & 0xF
        check("BUS_MODE renumbering hdr-ddr=3",
              got_mode == 3,
              f"extracted mode field = {got_mode}, want 3")
    except AsmError as e:
        fail("BUS_MODE renumbering hdr-ddr", str(e))

    # 4c. i3c-OD → wire value 1
    try:
        words = assemble("SET_BUS_MODE i3c-OD\n", "<inline>")
        got_mode = (words[2] >> 3) & 0xF
        check("BUS_MODE renumbering i3c-OD=1",
              got_mode == 1,
              f"extracted mode field = {got_mode}, want 1")
    except AsmError as e:
        fail("BUS_MODE renumbering i3c-OD", str(e))

    # ------------------------------------------------------------------
    # 5. JMP sugar: JMP loop → BRANCH_ON ALWAYS, -1
    try:
        words = assemble("loop: JMP loop\n", "<inline>")
        # PC=0, JMP loop → BRANCH_ON ALWAYS, (0-0-1)=-1
        expected = enc_branch_on(COND_CODES["ALWAYS"], -1)
        check("JMP sugar",
              words[2] == expected,
              f"got {words[2]:#010x}, want {expected:#010x}")
    except AsmError as e:
        fail("JMP sugar", str(e))

    # ------------------------------------------------------------------
    # 6. LOAD_LOOP sugar: LOAD_LOOP 42 → LOAD_IMM R6, 42
    try:
        words = assemble("LOAD_LOOP 42\n", "<inline>")
        expected = enc_load_imm(6, 42)
        check("LOAD_LOOP sugar",
              words[2] == expected,
              f"got {words[2]:#010x}, want {expected:#010x}")
    except AsmError as e:
        fail("LOAD_LOOP sugar", str(e))

    # ------------------------------------------------------------------
    # 7. (use-raw-primitives) .dw directive
    try:
        words = assemble("(use-raw-primitives)\n.dw 0xDEADBEEF\n", "<inline>")
        check("raw .dw 0xDEADBEEF",
              words[2] == 0xDEAD_BEEF,
              f"got {words[2]:#010x}")
    except AsmError as e:
        fail("raw .dw", str(e))

    # ------------------------------------------------------------------
    # 8. EMIT_BYTE_REG pairing check error (E-WIRE-003).
    raised_e_wire_003 = False
    try:
        assemble("EMIT_BYTE_REG expect=0 mask=1\nHALT\n", "<inline>")
    except AsmError as e:
        if "E-WIRE-003" in e.code or "E-WIRE-003" in str(e):
            raised_e_wire_003 = True
    check("E-WIRE-003 raised for unpaired EMIT_BYTE_REG mask=1",
          raised_e_wire_003,
          "expected AsmError with code E-WIRE-003")

    # 8b. EMIT_BYTE_IMM pairing check (same E-WIRE-003 rule).
    raised_e_wire_003_imm = False
    try:
        assemble("EMIT_BYTE_IMM imm=0x55 expect=0 mask=1\nHALT\n", "<inline>")
    except AsmError as e:
        if "E-WIRE-003" in e.code or "E-WIRE-003" in str(e):
            raised_e_wire_003_imm = True
    check("E-WIRE-003 raised for unpaired EMIT_BYTE_IMM mask=1",
          raised_e_wire_003_imm,
          "expected AsmError with code E-WIRE-003")

    # ------------------------------------------------------------------
    # 9. EMIT_BYTE_REG mask=0 exemption (no error).
    try:
        words = assemble("EMIT_BYTE_REG expect=0 mask=0\nHALT\n", "<inline>")
        check("EMIT_BYTE_REG mask=0 exempt from pairing check",
              len(words) >= 3,
              "unexpectedly got empty result")
    except AsmError as e:
        fail("EMIT_BYTE_REG mask=0 exemption", f"raised {e!r}")

    # 9b. EMIT_BYTE_IMM mask=0 exemption.
    try:
        words = assemble("EMIT_BYTE_IMM imm=0xAA\nHALT\n", "<inline>")
        check("EMIT_BYTE_IMM mask=0 exempt from pairing check",
              len(words) >= 3,
              "unexpectedly got empty result")
    except AsmError as e:
        fail("EMIT_BYTE_IMM mask=0 exemption", f"raised {e!r}")

    # 9c. EMIT_BYTE_IMM imm out-of-range → E-RNG-001.
    raised_e_rng_001 = False
    try:
        assemble("EMIT_BYTE_IMM imm=256\nHALT\n", "<inline>")
    except AsmError as e:
        if "E-RNG-001" in e.code or "E-RNG-001" in str(e):
            raised_e_rng_001 = True
    check("E-RNG-001 raised for EMIT_BYTE_IMM imm=256",
          raised_e_rng_001,
          "expected AsmError with code E-RNG-001")

    # 9d. Bare EMIT_BYTE is retired (E-LEX-006); the diagnostic must
    # point at both canonical replacements (_IMM and _REG).
    raised_e_lex_006 = False
    msg_has_imm = False
    msg_has_reg = False
    try:
        assemble("EMIT_BYTE\nHALT\n", "<inline>")
    except AsmError as e:
        if "E-LEX-006" in e.code or "E-LEX-006" in str(e):
            raised_e_lex_006 = True
        msg_has_imm = "EMIT_BYTE_IMM" in str(e)
        msg_has_reg = "EMIT_BYTE_REG" in str(e)
    check("E-LEX-006 raised for bare EMIT_BYTE", raised_e_lex_006,
          "expected AsmError with code E-LEX-006")
    check("E-LEX-006 message names EMIT_BYTE_IMM", msg_has_imm,
          "diagnostic must point at the IMM replacement")
    check("E-LEX-006 message names EMIT_BYTE_REG", msg_has_reg,
          "diagnostic must point at the REG replacement")

    # ------------------------------------------------------------------
    # 10. Case-insensitive MISMATCH pairing (regression M1)
    try:
        words = assemble(
            "EMIT_BYTE_REG expect=0 mask=1\n"
            "BRANCH_ON mismatch, nak\n"
            "nak: HALT\n",
            "<inline>",
        )
        check("Case-insensitive MISMATCH pairing",
              len(words) >= 3,
              "unexpectedly got empty result")
    except AsmError as e:
        fail("Case-insensitive MISMATCH pairing", f"raised {e!r}")

    # ------------------------------------------------------------------
    # 11. Preamble magic and version
    try:
        words = assemble("HALT\n", "<inline>")
        check("Preamble magic 0x0002_4D4C",
              words[0] == PREAMBLE_MAGIC,
              f"got {words[0]:#010x}")
        check("Preamble body length = 1",
              words[1] == 1,
              f"got {words[1]}")
    except AsmError as e:
        fail("Preamble", str(e))

    # 12. Empty source → E-FRM-003 (rejected; zero body words)
    raised_e_frm_003_empty = False
    try:
        assemble("", "<inline>")
    except AsmError as e:
        if "E-FRM-003" in e.code or "E-FRM-003" in str(e):
            raised_e_frm_003_empty = True
    check("E-FRM-003 raised for empty source",
          raised_e_frm_003_empty,
          "expected AsmError with code E-FRM-003")

    # ------------------------------------------------------------------
    # 13. CRC round-trip: pack + crc ≠ 0 (sanity)
    try:
        words = assemble("HALT\n", "<inline>")
        bs = pack_bytecode(words)
        frame = build_frame(words)
        # Frame: 2 bytes length + bytecode + 2 bytes CRC.
        check("build_frame length",
              len(frame) == 2 + len(bs) + 2,
              f"len={len(frame)}")
    except AsmError as e:
        fail("CRC round-trip", str(e))

    # ------------------------------------------------------------------
    # 14. HALT status=0 canonical form
    try:
        a = assemble("HALT status=0\n", "<inline>")
        b = assemble("HALT\n", "<inline>")
        check("HALT default status=0",
              a == b,
              f"a={a!r} b={b!r}")
    except AsmError as e:
        fail("HALT default status", str(e))

    # ------------------------------------------------------------------
    # 15. .equ directive basic usage
    try:
        words = assemble(".equ COUNT, 10\nLOAD_IMM R0, COUNT\n", "<inline>")
        expected = enc_load_imm(0, 10)
        check(".equ constant resolution",
              words[2] == expected,
              f"got {words[2]:#010x}, want {expected:#010x}")
    except AsmError as e:
        fail(".equ directive", str(e))

    # ------------------------------------------------------------------
    # 16. E-RAW-002: pragma after instruction
    raised_raw002 = False
    try:
        assemble("HALT\n(use-raw-primitives)\n", "<inline>")
    except AsmError as e:
        if "E-RAW-002" in e.code or "E-RAW-002" in str(e):
            raised_raw002 = True
    check("E-RAW-002 raised when pragma after instruction",
          raised_raw002)

    # 17. E-LEX-001: unknown mnemonic
    raised_lex001 = False
    try:
        assemble("FROBNICATE\n", "<inline>")
    except AsmError as e:
        if "E-LEX-001" in e.code or "E-LEX-001" in str(e):
            raised_lex001 = True
    check("E-LEX-001 raised for unknown mnemonic", raised_lex001)

    # ------------------------------------------------------------------
    # 18. E-FRM-001: .dw operand count exceeds program memory.
    try:
        big = "(use-raw-primitives)\n.dw " + ", ".join(
            "0" for _ in range(8193)) + "\n"
        assemble(big, "<inline>")
        fail("E-FRM-001 .dw oversize",
             ".dw 8193 operands must raise E-FRM-001 but did not")
    except AsmError as e:
        check("E-FRM-001 raised for .dw operand count > 8192",
              e.code == "E-FRM-001",
              f".dw oversize: expected E-FRM-001, got {e.code!r}")

    # 19. n2: ASCII-only identifier: ASCII label accepted, non-ASCII rejected.
    try:
        words = assemble("xy: HALT\n", "<inline>")
        check("ASCII label 'xy' accepted",
              len(words) >= 2,
              "unexpectedly failed")
    except AsmError as e:
        fail("ASCII label 'xy' accepted", f"raised {e!r}")

    raised_nonascii = False
    try:
        assemble("xy\u00e9: HALT\n", "<inline>")
    except AsmError:
        raised_nonascii = True
    check("Non-ASCII label 'xyé' rejected",
          raised_nonascii,
          "non-ASCII label should be rejected")

    # ------------------------------------------------------------------
    # 20. E-FRM-003: empty source is rejected.
    raised_frm003_empty = False
    try:
        assemble("", "<inline>")
    except AsmError as e:
        if "E-FRM-003" in e.code or "E-FRM-003" in str(e):
            raised_frm003_empty = True
    check("E-FRM-003 raised for empty source",
          raised_frm003_empty,
          "expected AsmError with code E-FRM-003")

    # 21. E-FRM-003: .dw with no operands is rejected.
    raised_frm003_dw = False
    try:
        assemble(".dw\n", "<inline>")
    except AsmError as e:
        if "E-FRM-003" in e.code or "E-FRM-003" in str(e):
            raised_frm003_dw = True
    check("E-FRM-003 raised for .dw with no operands",
          raised_frm003_dw,
          "expected AsmError with code E-FRM-003")

    # ------------------------------------------------------------------
    print(f"\nResults: {checks} checks, {len(failures)} failures")
    for f in failures:
        print(f)
    if failures:
        print(f"\nFAIL: {len(failures)} check(s) failed", file=sys.stderr)
        return 1
    print(f"OK: {checks} checks passed")
    return 0


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

if __name__ == "__main__":
    sys.exit(_cli_main(sys.argv[1:]))
