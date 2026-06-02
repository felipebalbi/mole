#!/usr/bin/env python3
"""mole-asm --- the golden moleasm assembler.

Reads a `.moleasm` source file and emits raw 16-bit little-endian bytecode
into a sibling `.molecode` file. With `--frame` it also writes a framed
`.mole.bin` (len + words + CRC-16/XMODEM) ready to drop onto the
iCEbreaker UART at 1 Mbaud.

This is the *reference* implementation that the Rust `mole-asm` crate
(at `../../../mole-asm/`, with its CLI front-end at
`../../../mole-asm-cli/`) is diffed against. Every line of grammar
here lines up with one of:

    AGENTS.md  §3.16    moleasm syntax (locked)
    ROADMAP.md §"Example: I2C write-one-byte in moleasm"  conventions
    fpga/Mole/src/hw/Instruction.scala                     encoder oracle

The instruction-word encoder layer mirrors `Instruction.scala`'s `encode`
byte-for-byte; the round-trip suite in `__main__` asserts both the
already-handed-out `.mole.bin` blobs (`first-light`, `tmp108`) and the
ROADMAP worked example.

CLI:

    python mole-asm.py [-h] [--frame] [-o OUT] INPUT.moleasm

Defaults output to `INPUT.molecode` next to the input. `--frame` adds
`INPUT.mole.bin` alongside, framed and ready for UART.

Running this file as `__main__` (no args) regenerates the three bundled
`.moleasm` programs into `.molecode` + `.mole.bin` in this directory and
runs the full self-check suite.
"""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Iterable, Optional

# ===========================================================================
# Opcode positions (mirrors Opcode.position in Instruction.scala)
#
# Wire format: 5-bit opcode at [15:11], 11-bit payload at [10:0]. Slots
# 0x00..0x0D are v0; 0x0E (FLAG_CLEAR) and 0x0F (CAPTURE_RUN) are
# reserved-v0.5; 0x10 is SET_ROLE (added with the 5-bit opcode widening);
# 0x11..0x1F are reserved for v0.5+.
# ===========================================================================

OP_HALT = 0x00
OP_EMIT_BIT = 0x01
OP_EMIT_QUARTER = 0x02
OP_STRETCH_SCL = 0x03
OP_WAIT_ON = 0x04
OP_BRANCH_ON = 0x05
OP_JMP = 0x06
OP_SET_BUS_MODE = 0x07
OP_LOAD_TIMING = 0x08
OP_MARK = 0x09
OP_SAMPLE_BIT = 0x0A
OP_DRIVE_BIT = 0x0B
OP_LOAD_LOOP = 0x0C
OP_DEC_BRANCH = 0x0D
# 0x0E (FLAG_CLEAR) and 0x0F (CAPTURE_RUN) are reserved v0.5; reach
# them via `.dw` if you really must.
OP_SET_ROLE = 0x10
# 0x11..0x1F are reserved v0.5+; reachable via `.dw` only.


# ===========================================================================
# Named symbol tables (lower-case canonical, mirrors Instruction.scala)
# ===========================================================================

# TxSymbol.position --- per-line drive operand for EMIT_BIT, EMIT_QUARTER,
# DRIVE_BIT_ON_SCL. `reserved` (0b11) is held for v0.5 raw_override; the
# assembler refuses to emit it directly --- use `.dw` if you really need it.
TX_SYMBOLS: dict[str, int] = {
    "dominant": 0b00,
    "dom": 0b00,
    "recessive": 0b01,
    "rec": 0b01,
    "hiz": 0b10,
}

# BusMode wire values (defaultEncoding "busModeWire" in Instruction.scala).
# Non-sequential by design --- see ROADMAP §"Bus mode register".
BUS_MODES: dict[str, int] = {
    "i2c": 0,
    "i3c-od": 1,
    "i3c-pp": 6,
    "hdr-ddr": 7,
}

# CondCode.position --- shared namespace for BRANCH_ON and WAIT_ON.
# Note: ROADMAP §"Unified condition codes" lists `NEVER` at code 1, but the
# Scala implementation has `MISMATCH` at 1 and no `NEVER`. The Scala wins
# (it's what the engine decodes); the ROADMAP table needs a doc fix.
COND_CODES: dict[str, int] = {
    "ALWAYS": 0x0,
    "MISMATCH": 0x1,
    "NOT_MISMATCH": 0x2,
    "START_SEEN": 0x3,
    "STOP_SEEN": 0x4,
    "SDA_LOW": 0x5,
    "SDA_HIGH": 0x6,
    "SCL_HIGH": 0x7,
    "TIMEOUT": 0x8,
    "NOT_TIMEOUT": 0x9,
}

# LOAD_TIMING register aliases. `timingRegs(busModeReg[1:0])` is the
# active register, so each BUS_MODE maps to one of the four:
#   i2c     wire 0b000 -> mode[1:0]=00 -> reg 0  (i2c_freq)
#   i3c-OD  wire 0b001 -> mode[1:0]=01 -> reg 1  (i3c_od_freq)
#   i3c-PP  wire 0b110 -> mode[1:0]=10 -> reg 2  (i3c_pp_freq)
#   hdr-DDR wire 0b111 -> mode[1:0]=11 -> reg 3  (hdr_ddr_freq)
TIMING_REG_ALIASES: dict[str, int] = {
    "i2c_freq": 0,
    "i3c_od_freq": 1,
    "i3c_pp_freq": 2,
    "hdr_ddr_freq": 3,
}


# Loop-counter register aliases for LOAD_LOOP / DEC_BRANCH. One bit on
# the wire (lcr0 -> 0, lcr1 -> 1); the [9:8] pad above stays reserved
# for a future 4-LCR widening with no wire-format break.
LOOP_REG_ALIASES: dict[str, int] = {
    "lcr0": 0,
    "lcr1": 1,
}


# SET_ROLE role aliases. One bit on the wire (controller -> 0,
# target -> 1); the [9:0] pad stays reserved for future role-mode
# extensions.
ROLE_ALIASES: dict[str, int] = {
    "controller": 0,
    "target": 1,
}


# Mnemonic dispatch (UPPER CASE). Maps to a per-opcode operand-parse +
# encoder function; populated below the function definitions.
MNEMONICS: set[str] = {
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
    "SET_ROLE",
}

# Reserved-v0.5 mnemonics; assembler rejects them and points the user at
# `.dw` for raw-word injection. Names mirror Opcode case names. Slots
# 0xC / 0xD (formerly WAIT_ADDRESSED / MISMATCH_CLEAR) graduated to v0
# as LOAD_LOOP / DEC_BRANCH; only the remaining reservations are listed.
RESERVED_V05_MNEMONICS: set[str] = {
    "FLAG_CLEAR",
    "CAPTURE_RUN",
}

# Names the user cannot redefine via labels or `.equ`.
RESERVED_NAMES: set[str] = (
    set(MNEMONICS)
    | set(RESERVED_V05_MNEMONICS)
    | set(TX_SYMBOLS)
    | set(BUS_MODES)
    | set(COND_CODES)
    | set(TIMING_REG_ALIASES)
    | set(LOOP_REG_ALIASES)
    | set(ROLE_ALIASES)
)


# ===========================================================================
# Diagnostic carrier
# ===========================================================================


class AsmError(Exception):
    """Raised on the first syntactic / semantic error. Carries a
    file:line prefix so the user can jump straight to the bad token."""

    def __init__(self, where: str, msg: str) -> None:
        super().__init__(f"{where}: {msg}")
        self.where = where
        self.msg = msg


# ===========================================================================
# Instruction-word encoders (mirror Instruction.scala `encode`)
#
# Wire format (post-widening): 5-bit opcode at [15:11], 11-bit payload
# at [10:0]. Every field shift below is anchored against bit 11.
# ===========================================================================


# Low bit of the opcode field within the 16-bit instruction word.
OPCODE_LO = 11


def _flag_triple(expect: bool, mask: bool, capture: bool) -> int:
    """Pack the expect/mask/capture flag triple into bits [2:0].

    Layout: [2]=expect [1]=mask [0]=capture. Per AGENTS §3.10 these
    positions are fixed on every opcode that carries the triple.
    """
    return (
        ((1 if expect else 0) << 2) | ((1 if mask else 0) << 1) | (1 if capture else 0)
    )


def enc_halt(status: int) -> int:
    if not 0 <= status < (1 << 4):
        raise ValueError(f"HALT status must be 0..15, got {status}")
    # Layout: [15:11]op [10:7]status [6:0]reserved=0
    return (OP_HALT << OPCODE_LO) | (status << 7)


def enc_emit_bit(tx: int, expect: bool, mask: bool, capture: bool) -> int:
    if not 0 <= tx < 4:
        raise ValueError(f"EMIT_BIT tx symbol must be 0..3, got {tx}")
    if tx == 0b11:
        raise ValueError("EMIT_BIT tx=reserved (0b11) is v0.5; use .dw")
    # Layout: [15:11]op [10:9]tx [8:3]reserved [2:0]flags
    return (OP_EMIT_BIT << OPCODE_LO) | (tx << 9) | _flag_triple(expect, mask, capture)


def enc_emit_quarter(
    sda: int, scl: int, expect: bool, mask: bool, capture: bool
) -> int:
    if not 0 <= sda < 4 or not 0 <= scl < 4:
        raise ValueError(f"EMIT_QUARTER sda/scl must be 0..3, got sda={sda} scl={scl}")
    if sda == 0b11 or scl == 0b11:
        raise ValueError("EMIT_QUARTER sda/scl=reserved (0b11) is v0.5; use .dw")
    # Layout: [15:11]op [10:9]sda [8:7]scl [6:3]reserved [2:0]flags
    return (
        (OP_EMIT_QUARTER << OPCODE_LO)
        | (sda << 9)
        | (scl << 7)
        | _flag_triple(expect, mask, capture)
    )


def enc_stretch_scl(n_quarters: int) -> int:
    if not 0 <= n_quarters < (1 << 11):
        raise ValueError(f"STRETCH_SCL n_quarters must be 0..2047, got {n_quarters}")
    # Layout: [15:11]op [10:0]n_quarters
    return (OP_STRETCH_SCL << OPCODE_LO) | n_quarters


def enc_wait_on(cond: int, timeout_quarters: int) -> int:
    if not 0 <= cond < 16:
        raise ValueError(f"WAIT_ON cond must be 0..15, got {cond}")
    if not 0 <= timeout_quarters < (1 << 7):
        raise ValueError(
            f"WAIT_ON timeout must be 0..127 quarters, got {timeout_quarters}"
        )
    # Layout: [15:11]op [10:7]cond [6:0]timeout
    return (OP_WAIT_ON << OPCODE_LO) | (cond << 7) | (timeout_quarters & 0x7F)


def enc_branch_on(cond: int, pc_rel_offset: int) -> int:
    if not 0 <= cond < 16:
        raise ValueError(f"BRANCH_ON cond must be 0..15, got {cond}")
    if not -64 <= pc_rel_offset <= 63:
        raise ValueError(
            f"BRANCH_ON pc-rel offset must be -64..63, got {pc_rel_offset}"
        )
    # Layout: [15:11]op [10:7]cond [6:0]offset_signed
    return (OP_BRANCH_ON << OPCODE_LO) | (cond << 7) | (pc_rel_offset & 0x7F)


def enc_jmp(addr: int) -> int:
    if not 0 <= addr < (1 << 11):
        raise ValueError(f"JMP addr must fit in 11 bits (0..2047), got {addr}")
    # Layout: [15:11]op [10:0]addr
    return (OP_JMP << OPCODE_LO) | addr


def enc_set_bus_mode(mode_wire: int) -> int:
    if mode_wire not in {0, 1, 6, 7}:
        raise ValueError(f"SET_BUS_MODE wire value must be 0|1|6|7, got {mode_wire}")
    # Layout: [15:11]op [10:8]mode_wire [7:0]reserved=0
    return (OP_SET_BUS_MODE << OPCODE_LO) | (mode_wire << 8)


def enc_load_timing(reg: int, divider_word: int) -> int:
    if not 0 <= reg < 4:
        raise ValueError(f"LOAD_TIMING reg must be 0..3, got {reg}")
    if not 0 <= divider_word < (1 << 9):
        raise ValueError(
            f"LOAD_TIMING divider_word must be 0..511, got {divider_word}"
        )
    # Layout: [15:11]op [10:9]reg [8:0]divider_word
    return (OP_LOAD_TIMING << OPCODE_LO) | (reg << 9) | divider_word


def enc_mark(label: int) -> int:
    if not 0 <= label < (1 << 8):
        raise ValueError(f"MARK label must be 0..255, got {label}")
    # Layout: [15:11]op [10:3]label [2:0]reserved=0
    return (OP_MARK << OPCODE_LO) | (label << 3)


def enc_sample_bit(expect: bool, mask: bool, capture: bool) -> int:
    # Layout: [15:11]op [10:3]reserved [2:0]flags
    return (OP_SAMPLE_BIT << OPCODE_LO) | _flag_triple(expect, mask, capture)


def enc_drive_bit(tx: int, expect: bool, mask: bool, capture: bool) -> int:
    if not 0 <= tx < 4:
        raise ValueError(f"DRIVE_BIT_ON_SCL tx symbol must be 0..3, got {tx}")
    if tx == 0b11:
        raise ValueError("DRIVE_BIT_ON_SCL tx=reserved (0b11) is v0.5; use .dw")
    # Layout: [15:11]op [10:9]tx [8:3]reserved [2:0]flags
    return (OP_DRIVE_BIT << OPCODE_LO) | (tx << 9) | _flag_triple(expect, mask, capture)


def enc_load_loop(reg: int, imm: int) -> int:
    """LOAD_LOOP reg, imm8 -> [15:11]op [10]reg [9:8]reserved=0 [7:0]imm8.

    `reg` is one bit on the wire (lcr0 = 0, lcr1 = 1); the [9:8] pad
    stays reserved so a future 4-LCR widening can claim those bits
    without breaking the wire format. See Instruction.scala's LoadLoop
    case-class doc for the full design note.
    """
    if not 0 <= reg < 2:
        raise ValueError(f"LOAD_LOOP reg must be 0 or 1, got {reg}")
    if not 0 <= imm < (1 << 8):
        raise ValueError(f"LOAD_LOOP imm must be 0..255, got {imm}")
    return (OP_LOAD_LOOP << OPCODE_LO) | (reg << 10) | (imm & 0xFF)


def enc_dec_branch(reg: int, pc_rel_offset: int) -> int:
    """DEC_BRANCH reg, offset -> decrement LCR[reg], back-edge if non-zero.

    Wire: [15:11]op [10]reg [9:8]reserved=0 [7:0]offset_signed. 8-bit
    wrap on the decrement (0 -> 0xFF). DEC_BRANCH keeps the wider
    signed-8 offset (-128..127) where BRANCH_ON narrowed to signed-7
    (-64..63) --- counted-loop bodies benefit from the longer reach.
    """
    if not 0 <= reg < 2:
        raise ValueError(f"DEC_BRANCH reg must be 0 or 1, got {reg}")
    if not -128 <= pc_rel_offset <= 127:
        raise ValueError(
            f"DEC_BRANCH pc-rel offset must be -128..127, got {pc_rel_offset}"
        )
    return (OP_DEC_BRANCH << OPCODE_LO) | (reg << 10) | (pc_rel_offset & 0xFF)


def enc_set_role(role: int) -> int:
    """SET_ROLE role -> switch controller (0) / target (1) at runtime.

    Wire: [15:11]op [10]role [9:0]reserved=0. Lives in opcode slot 0x10
    (the first slot exposed by the 5-bit opcode widening). All v0
    opcodes remain valid in either role; SET_ROLE only flips which
    role-specific arms the engine dispatches to.
    """
    if role not in (0, 1):
        raise ValueError(f"SET_ROLE role must be 0|1 (controller|target), got {role}")
    return (OP_SET_ROLE << OPCODE_LO) | (role << 10)


# ===========================================================================
# CRC-16/XMODEM and frame builder (kept here for the --frame flag)
# ===========================================================================


def crc16_xmodem(data: bytes) -> int:
    """Standard CRC-16/XMODEM: poly 0x1021, init 0x0000, no reflect,
    no XOR-out. Catalog check value: `crc16_xmodem(b'123456789')` = 0x31C3.
    """
    crc = 0x0000
    for b in data:
        crc ^= b << 8
        for _ in range(8):
            crc = (
                ((crc << 1) ^ 0x1021) & 0xFFFF if crc & 0x8000 else (crc << 1) & 0xFFFF
            )
    return crc


def build_frame(words: Iterable[int]) -> bytes:
    """Build a complete UART frame: len_lo, len_hi, words (each LE),
    crc_lo, crc_hi. CRC covers everything except itself.
    Per WIRE_FORMAT.md the engine accepts 1..2048 words per frame
    (matches the 11-bit JMP addr / program-memory budget).
    """
    ws = list(words)
    if not 1 <= len(ws) <= 2048:
        raise ValueError(f"frame len {len(ws)} out of 1..2048")
    payload = bytearray()
    payload.append(len(ws) & 0xFF)
    payload.append((len(ws) >> 8) & 0xFF)
    for w in ws:
        if not 0 <= w <= 0xFFFF:
            raise ValueError(f"word {w:#06x} out of 16-bit range")
        payload.append(w & 0xFF)
        payload.append((w >> 8) & 0xFF)
    crc = crc16_xmodem(bytes(payload))
    payload.append(crc & 0xFF)
    payload.append((crc >> 8) & 0xFF)
    return bytes(payload)


def pack_bytecode(words: Iterable[int]) -> bytes:
    """Pack 16-bit instruction words little-endian. This is the raw
    `.molecode` body: no frame, no CRC --- just the bytes that would
    land in SPRAM at runtime."""
    out = bytearray()
    for w in words:
        if not 0 <= w <= 0xFFFF:
            raise ValueError(f"word {w:#06x} out of 16-bit range")
        out.append(w & 0xFF)
        out.append((w >> 8) & 0xFF)
    return bytes(out)


# ===========================================================================
# Lexer
# ===========================================================================


_IDENT_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_\-]*")
_LABEL_DEF_RE = re.compile(r"^(?P<label>[A-Za-z_][A-Za-z0-9_]*)\s*:\s*(?P<rest>.*)$")


@dataclass
class Statement:
    """One source line, post-comment-strip, post-label-peel. Either an
    instruction, a directive, or label-only (label_on_own_line=True with
    no mnemonic / directive)."""

    line_no: int
    label: Optional[str]
    mnemonic: Optional[str]
    directive: Optional[str]
    operands: list[str] = field(default_factory=list)

    @property
    def where(self) -> str:
        return f"line {self.line_no}"


def _strip_comment(line: str) -> str:
    """Remove from the first `;` to end-of-line. Per AGENTS.md any number
    of leading `;` is fine --- one `;` starts the comment."""
    idx = line.find(";")
    return line if idx < 0 else line[:idx]


def _tokenize_operands(operand_region: str) -> list[str]:
    """Split the operand region on commas, then on whitespace inside each
    comma-piece. Yields a flat list of tokens. Empty tokens are filtered."""
    tokens: list[str] = []
    for piece in operand_region.split(","):
        for tok in piece.split():
            if tok:
                tokens.append(tok)
    return tokens


def lex(source: str, filename: str) -> list[Statement]:
    """Convert source text into a list of Statements (one per non-empty,
    non-comment-only line). Validates label syntax and basic shape; does
    not validate operand structure (that's pass 1's job).
    """
    statements: list[Statement] = []
    # `splitlines()` handles \n, \r\n, and \r equally --- defensive for
    # files that get mangled by a Windows editor in transit.
    for line_no, raw in enumerate(source.splitlines(), start=1):
        body = _strip_comment(raw).strip()
        if not body:
            continue

        label: Optional[str] = None
        m = _LABEL_DEF_RE.match(body)
        if m:
            label = m.group("label")
            body = m.group("rest").strip()

        if not body:
            # Label-only line.
            statements.append(
                Statement(
                    line_no=line_no,
                    label=label,
                    mnemonic=None,
                    directive=None,
                )
            )
            continue

        # First whitespace-delimited token is the mnemonic or directive.
        # Accept any kind and any amount of whitespace between mnemonic and
        # operands --- `str.split(None, 1)` collapses runs of any whitespace
        # (space, tab, NBSP, ...) into a single separator.
        parts = body.split(None, 1)
        head = parts[0]
        rest = parts[1].strip() if len(parts) > 1 else ""

        if head.startswith("."):
            directive = head.lower()
            statements.append(
                Statement(
                    line_no=line_no,
                    label=label,
                    mnemonic=None,
                    directive=directive,
                    operands=_tokenize_operands(rest),
                )
            )
            continue

        # Mnemonic must be UPPER CASE per moleasm convention.
        if head != head.upper():
            raise AsmError(
                f"{filename}:{line_no}",
                f"mnemonic must be UPPER CASE: got {head!r}",
            )
        if head in RESERVED_V05_MNEMONICS:
            raise AsmError(
                f"{filename}:{line_no}",
                f"{head} is a reserved-v0.5 opcode; use `.dw` to inject "
                f"the raw word if you really mean it",
            )
        if head not in MNEMONICS:
            raise AsmError(f"{filename}:{line_no}", f"unknown mnemonic: {head!r}")

        statements.append(
            Statement(
                line_no=line_no,
                label=label,
                mnemonic=head,
                directive=None,
                operands=_tokenize_operands(rest),
            )
        )

    return statements


# ===========================================================================
# Symbol table
# ===========================================================================


@dataclass
class Symbol:
    name: str
    kind: str  # "label" or "equate"
    value: int  # PC (label) or constant (equate)
    line_no: int


def _validate_name(name: str, kind: str, where: str) -> None:
    if not _IDENT_RE.fullmatch(name):
        raise AsmError(where, f"invalid {kind} name: {name!r}")
    if name in RESERVED_NAMES:
        raise AsmError(
            where,
            f"{kind} name {name!r} collides with a reserved mnemonic / "
            f"symbol / alias",
        )


def _resolve_literal_or_equate(tok: str, symbols: dict[str, Symbol], where: str) -> int:
    """Parse a numeric literal OR look up an `.equ` name. Used by `.dw`
    arguments and by all operand positions that take a constant."""
    if tok and tok[0].isalpha() or tok.startswith("_"):
        # Identifier --- must be a previously-defined `.equ`.
        sym = symbols.get(tok)
        if sym is None:
            raise AsmError(where, f"undefined symbol: {tok!r}")
        if sym.kind != "equate":
            raise AsmError(
                where,
                f"{tok!r} is a label (PC address), not a constant; "
                f"expected an .equ value here",
            )
        return sym.value
    return _parse_int(tok, where)


def _parse_int(tok: str, where: str) -> int:
    """Parse a signed numeric literal: decimal (default), `0x` hex,
    `0b` binary. Leading `-` accepted for signed offsets."""
    if not tok:
        raise AsmError(where, "empty numeric literal")
    s = tok
    negative = False
    if s.startswith("-"):
        negative = True
        s = s[1:]
    try:
        if s.startswith("0x") or s.startswith("0X"):
            val = int(s, 16)
        elif s.startswith("0b") or s.startswith("0B"):
            val = int(s, 2)
        else:
            val = int(s, 10)
    except ValueError:
        raise AsmError(where, f"not a valid integer literal: {tok!r}")
    return -val if negative else val


# ===========================================================================
# Pass 1: collect labels + equates, assign PC slots
# ===========================================================================


def _pc_advance_for(stmt: Statement) -> int:
    """Number of 16-bit slots this statement consumes."""
    if stmt.directive == ".equ":
        return 0
    if stmt.directive == ".dw":
        return len(stmt.operands)
    if stmt.directive is not None:
        # Should have been caught by directive validation, but be defensive.
        return 0
    if stmt.mnemonic is not None:
        return 1
    return 0  # label-only


def pass1(
    statements: list[Statement], filename: str
) -> tuple[dict[str, Symbol], list[tuple[int, Statement]]]:
    """Walk all statements once. Assign each a PC. Collect labels +
    `.equ` constants into a single symbol table. Returns the symbol table
    plus a list of (pc, statement) for pass 2 to encode."""
    symbols: dict[str, Symbol] = {}
    pc_stmts: list[tuple[int, Statement]] = []
    pc = 0

    def bind(name: str, sym: Symbol) -> None:
        where = f"{filename}:{sym.line_no}"
        _validate_name(name, sym.kind, where)
        prior = symbols.get(name)
        if prior is not None:
            raise AsmError(
                where,
                f"{sym.kind} {name!r} re-defined "
                f"(prior {prior.kind} on line {prior.line_no})",
            )
        symbols[name] = sym

    for stmt in statements:
        if stmt.label is not None:
            bind(
                stmt.label,
                Symbol(name=stmt.label, kind="label", value=pc, line_no=stmt.line_no),
            )

        if stmt.directive == ".equ":
            if len(stmt.operands) != 2:
                raise AsmError(
                    f"{filename}:{stmt.line_no}",
                    ".equ takes exactly two operands: NAME, VALUE",
                )
            name, val_tok = stmt.operands
            value = _resolve_literal_or_equate(
                val_tok, symbols, f"{filename}:{stmt.line_no}"
            )
            bind(
                name,
                Symbol(
                    name=name,
                    kind="equate",
                    value=value,
                    line_no=stmt.line_no,
                ),
            )
            # `.equ` does not occupy a PC slot.
            continue

        advance = _pc_advance_for(stmt)
        if advance > 0:
            pc_stmts.append((pc, stmt))
        pc += advance

        if pc >= (1 << 11):
            raise AsmError(
                f"{filename}:{stmt.line_no}",
                f"program exceeds 2048 instruction slots (PC overflow)",
            )

    return symbols, pc_stmts


# ===========================================================================
# Per-opcode operand parsers (pass 2 helpers)
# ===========================================================================


def _parse_kv_operands(stmt: Statement, allowed: set[str]) -> dict[str, str]:
    """Parse `key=value key=value ...` operands. Reject duplicates, reject
    keys not in `allowed`, reject positional tokens (must contain `=`)."""
    out: dict[str, str] = {}
    for tok in stmt.operands:
        if "=" not in tok:
            raise AsmError(
                stmt.where,
                f"expected key=value operand, got positional token {tok!r}",
            )
        key, _, val = tok.partition("=")
        if key not in allowed:
            raise AsmError(
                stmt.where,
                f"unknown operand key {key!r} (allowed: {sorted(allowed)})",
            )
        if key in out:
            raise AsmError(stmt.where, f"duplicate operand key {key!r}")
        out[key] = val
    return out


def _resolve_flag_triple(kv: dict[str, str], where: str) -> tuple[bool, bool, bool]:
    """Decode `expect=` / `mask=` / `capture=` per the moleasm convention.
    Defaults: expect=X (don't care, bit=0), mask=0, capture=0.
    `expect=X` combined with `mask=1` is rejected as contradictory."""
    expect_str = kv.get("expect", "X").upper()
    mask_str = kv.get("mask", "0")
    capture_str = kv.get("capture", "0")

    if expect_str not in {"0", "1", "X"}:
        raise AsmError(where, f"expect must be 0|1|X, got {expect_str!r}")
    if mask_str not in {"0", "1"}:
        raise AsmError(where, f"mask must be 0|1, got {mask_str!r}")
    if capture_str not in {"0", "1"}:
        raise AsmError(where, f"capture must be 0|1, got {capture_str!r}")

    mask = mask_str == "1"
    capture = capture_str == "1"

    if expect_str == "X":
        if mask:
            raise AsmError(
                where,
                "expect=X is don't-care and cannot be combined with mask=1; "
                "set an explicit expect=0|1 if you want to compare",
            )
        expect = False
    else:
        expect = expect_str == "1"

    return expect, mask, capture


def _resolve_tx(kv: dict[str, str], key: str, where: str) -> int:
    if key not in kv:
        raise AsmError(where, f"missing required operand: {key}=<symbol>")
    sym = kv[key]
    if sym not in TX_SYMBOLS:
        raise AsmError(
            where,
            f"{key}={sym!r} is not a named tx symbol "
            f"(allowed: {sorted(set(TX_SYMBOLS))})",
        )
    return TX_SYMBOLS[sym]


# ===========================================================================
# Pass 2: encode each (pc, statement) into a 16-bit word
# ===========================================================================


def pass2(
    symbols: dict[str, Symbol],
    pc_stmts: list[tuple[int, Statement]],
    filename: str,
) -> list[int]:
    """Encode each statement. Resolves label references for BRANCH_ON and
    JMP. Returns the bytecode word stream in PC order."""
    out: list[int] = []
    for pc, stmt in pc_stmts:
        where = f"{filename}:{stmt.line_no}"
        try:
            if stmt.directive == ".dw":
                for tok in stmt.operands:
                    val = _resolve_literal_or_equate(tok, symbols, where)
                    if not 0 <= val <= 0xFFFF:
                        raise AsmError(
                            where,
                            f".dw value {val:#x} out of 16-bit range",
                        )
                    out.append(val & 0xFFFF)
                continue

            assert stmt.mnemonic is not None
            word = _encode_mnemonic(stmt, pc, symbols, where)
            out.append(word & 0xFFFF)

        except AsmError:
            raise
        except (ValueError, AssertionError) as e:
            raise AsmError(where, str(e))

    return out


def _encode_mnemonic(
    stmt: Statement, pc: int, symbols: dict[str, Symbol], where: str
) -> int:
    """Dispatch on stmt.mnemonic and produce the encoded 16-bit word."""
    m = stmt.mnemonic
    assert m is not None

    if m == "HALT":
        kv = _parse_kv_operands(stmt, {"status"})
        status_tok = kv.get("status", "0")
        status = _resolve_literal_or_equate(status_tok, symbols, where)
        return enc_halt(status)

    if m == "EMIT_BIT":
        kv = _parse_kv_operands(stmt, {"tx", "expect", "mask", "capture"})
        tx = _resolve_tx(kv, "tx", where)
        e, mk, c = _resolve_flag_triple(kv, where)
        return enc_emit_bit(tx, e, mk, c)

    if m == "EMIT_QUARTER":
        kv = _parse_kv_operands(stmt, {"sda", "scl", "expect", "mask", "capture"})
        sda = _resolve_tx(kv, "sda", where)
        scl = _resolve_tx(kv, "scl", where)
        e, mk, c = _resolve_flag_triple(kv, where)
        return enc_emit_quarter(sda, scl, e, mk, c)

    if m == "SAMPLE_BIT_ON_SCL":
        kv = _parse_kv_operands(stmt, {"expect", "mask", "capture"})
        e, mk, c = _resolve_flag_triple(kv, where)
        return enc_sample_bit(e, mk, c)

    if m == "DRIVE_BIT_ON_SCL":
        kv = _parse_kv_operands(stmt, {"tx", "expect", "mask", "capture"})
        tx = _resolve_tx(kv, "tx", where)
        e, mk, c = _resolve_flag_triple(kv, where)
        return enc_drive_bit(tx, e, mk, c)

    if m == "MARK":
        kv = _parse_kv_operands(stmt, {"label"})
        if "label" not in kv:
            raise AsmError(where, "MARK requires label=<0..255>")
        label_val = _resolve_literal_or_equate(kv["label"], symbols, where)
        return enc_mark(label_val)

    if m == "STRETCH_SCL":
        if len(stmt.operands) != 1:
            raise AsmError(
                where, "STRETCH_SCL takes one positional operand: n_quarters"
            )
        n = _resolve_literal_or_equate(stmt.operands[0], symbols, where)
        return enc_stretch_scl(n)

    if m == "SET_BUS_MODE":
        if len(stmt.operands) != 1:
            raise AsmError(
                where, "SET_BUS_MODE takes one positional operand: <bus-mode>"
            )
        mode_name = stmt.operands[0].lower()
        if mode_name not in BUS_MODES:
            raise AsmError(
                where,
                f"bus mode {stmt.operands[0]!r} is not named "
                f"(allowed: {sorted(BUS_MODES)})",
            )
        return enc_set_bus_mode(BUS_MODES[mode_name])

    if m == "LOAD_TIMING":
        if len(stmt.operands) != 2:
            raise AsmError(
                where,
                "LOAD_TIMING takes two positional operands: reg, divider_word",
            )
        reg_tok, word_tok = stmt.operands
        if reg_tok in TIMING_REG_ALIASES:
            reg = TIMING_REG_ALIASES[reg_tok]
        else:
            reg = _resolve_literal_or_equate(reg_tok, symbols, where)
        word_val = _resolve_literal_or_equate(word_tok, symbols, where)
        return enc_load_timing(reg, word_val)

    if m == "WAIT_ON":
        if len(stmt.operands) != 2:
            raise AsmError(
                where, "WAIT_ON takes two positional operands: cond, timeout"
            )
        cond_tok, timeout_tok = stmt.operands
        cond = _resolve_cond(cond_tok, where)
        timeout = _resolve_literal_or_equate(timeout_tok, symbols, where)
        return enc_wait_on(cond, timeout)

    if m == "BRANCH_ON":
        if len(stmt.operands) != 2:
            raise AsmError(
                where,
                "BRANCH_ON takes two positional operands: cond, target",
            )
        cond_tok, target_tok = stmt.operands
        cond = _resolve_cond(cond_tok, where)
        offset = _resolve_branch_target(target_tok, pc, symbols, where)
        return enc_branch_on(cond, offset)

    if m == "JMP":
        if len(stmt.operands) != 1:
            raise AsmError(where, "JMP takes one positional operand: <addr-or-label>")
        target_tok = stmt.operands[0]
        addr = _resolve_jmp_target(target_tok, symbols, where)
        return enc_jmp(addr)

    if m == "LOAD_LOOP":
        if len(stmt.operands) != 2:
            raise AsmError(
                where, "LOAD_LOOP takes two positional operands: reg, imm8"
            )
        reg_tok, imm_tok = stmt.operands
        reg = _resolve_loop_reg(reg_tok, where)
        imm = _resolve_literal_or_equate(imm_tok, symbols, where)
        return enc_load_loop(reg, imm)

    if m == "DEC_BRANCH":
        if len(stmt.operands) != 2:
            raise AsmError(
                where, "DEC_BRANCH takes two positional operands: reg, target"
            )
        reg_tok, target_tok = stmt.operands
        reg = _resolve_loop_reg(reg_tok, where)
        offset = _resolve_dec_branch_target(target_tok, pc, symbols, where)
        return enc_dec_branch(reg, offset)

    if m == "SET_ROLE":
        if len(stmt.operands) != 1:
            raise AsmError(
                where, "SET_ROLE takes one positional operand: <controller|target>"
            )
        role = _resolve_role(stmt.operands[0], where)
        return enc_set_role(role)

    raise AsmError(where, f"unhandled mnemonic in encoder: {m!r}")


def _resolve_cond(tok: str, where: str) -> int:
    if tok not in COND_CODES:
        raise AsmError(
            where,
            f"cond code {tok!r} is not named " f"(allowed: {sorted(COND_CODES)})",
        )
    return COND_CODES[tok]


def _resolve_branch_target(
    tok: str, branch_pc: int, symbols: dict[str, Symbol], where: str
) -> int:
    """BRANCH_ON target: either a label name (compute signed offset) or a
    raw signed numeric offset. PC math: next_pc = branch_pc + 1 + offset.
    The 7-bit signed field caps reach at -64..63 instructions.
    """
    if tok and tok[0].isalpha() or tok.startswith("_"):
        sym = symbols.get(tok)
        if sym is None:
            raise AsmError(where, f"undefined branch target: {tok!r}")
        if sym.kind != "label":
            raise AsmError(
                where,
                f"BRANCH_ON target {tok!r} is an .equ constant, not a label",
            )
        offset = sym.value - branch_pc - 1
    else:
        offset = _parse_int(tok, where)
    if not -64 <= offset <= 63:
        raise AsmError(
            where,
            f"BRANCH_ON offset {offset} out of signed 7-bit range "
            f"(branch_pc={branch_pc})",
        )
    return offset


def _resolve_jmp_target(tok: str, symbols: dict[str, Symbol], where: str) -> int:
    """JMP target: either a label name (use absolute PC) or a raw 11-bit
    address literal."""
    if tok and tok[0].isalpha() or tok.startswith("_"):
        sym = symbols.get(tok)
        if sym is None:
            raise AsmError(where, f"undefined jump target: {tok!r}")
        if sym.kind != "label":
            raise AsmError(
                where,
                f"JMP target {tok!r} is an .equ constant, not a label "
                f"(use a numeric literal if you really want an .equ as addr)",
            )
        return sym.value
    return _parse_int(tok, where)


def _resolve_loop_reg(tok: str, where: str) -> int:
    """LOAD_LOOP / DEC_BRANCH reg: `lcr0` / `lcr1` or a literal 0 / 1."""
    if tok in LOOP_REG_ALIASES:
        return LOOP_REG_ALIASES[tok]
    n = _parse_int(tok, where)
    if not 0 <= n < 2:
        raise AsmError(
            where,
            f"loop reg {tok!r} must be lcr0|lcr1 or a literal 0|1 "
            f"(allowed names: {sorted(LOOP_REG_ALIASES)})",
        )
    return n


def _resolve_role(tok: str, where: str) -> int:
    """SET_ROLE role: `controller` / `target` (case-insensitive) or a
    literal 0 / 1."""
    lowered = tok.lower()
    if lowered in ROLE_ALIASES:
        return ROLE_ALIASES[lowered]
    n = _parse_int(tok, where)
    if n not in (0, 1):
        raise AsmError(
            where,
            f"SET_ROLE operand {tok!r} must be controller|target or a literal 0|1 "
            f"(allowed names: {sorted(ROLE_ALIASES)})",
        )
    return n


def _resolve_dec_branch_target(
    tok: str, branch_pc: int, symbols: dict[str, Symbol], where: str
) -> int:
    """DEC_BRANCH target: same shape and PC math as BRANCH_ON; distinct
    error labels so debug output points at the right opcode.
    """
    if tok and tok[0].isalpha() or tok.startswith("_"):
        sym = symbols.get(tok)
        if sym is None:
            raise AsmError(where, f"undefined DEC_BRANCH target: {tok!r}")
        if sym.kind != "label":
            raise AsmError(
                where,
                f"DEC_BRANCH target {tok!r} is an .equ constant, not a label",
            )
        offset = sym.value - branch_pc - 1
    else:
        offset = _parse_int(tok, where)
    if not -128 <= offset <= 127:
        raise AsmError(
            where,
            f"DEC_BRANCH offset {offset} out of signed 8-bit range "
            f"(branch_pc={branch_pc})",
        )
    return offset


# ===========================================================================
# Top-level assemble entry point
# ===========================================================================


def assemble(source: str, *, filename: str = "<input>") -> list[int]:
    """Source-text → list of encoded 16-bit instruction words."""
    statements = lex(source, filename)
    symbols, pc_stmts = pass1(statements, filename)
    return pass2(symbols, pc_stmts, filename)


# ===========================================================================
# Self-checks
# ===========================================================================


def _selfcheck_crc_and_frame() -> None:
    """CRC catalog check + WIRE_FORMAT.md §6 worked example."""
    assert crc16_xmodem(b"123456789") == 0x31C3, "CRC-16/XMODEM catalog check failed"
    expected = bytes.fromhex("020000900060DF9F")
    got = build_frame([0x9000, 0x6000])
    assert got == expected, (
        f"WIRE_FORMAT.md §6 frame check failed\n  got: {got.hex(' ')}\n"
        f"  exp: {expected.hex(' ')}"
    )


_ROADMAP_EXAMPLE_SRC = """
; ROADMAP §"Example: I2C write-one-byte in moleasm" --- ported.
;
; The ROADMAP source uses `label=ok` and `label=nak` on MARK lines
; (symbolic names); we use numeric tags here because MARK takes an
; 8-bit numeric label and the assembler doesn't auto-allocate IDs.
; Encoded bytes are byte-for-byte verifiable.

        LOAD_TIMING   i2c_freq, 60          ; ~100 kHz @ 24 MHz Verde fabric
        SET_BUS_MODE  i2c

        ; -- Start condition --
        EMIT_QUARTER  sda=recessive scl=recessive
        EMIT_QUARTER  sda=dominant  scl=recessive
        EMIT_QUARTER  sda=dominant  scl=dominant

        ; -- Address byte 0xA0 = 1010_0000 (MSB first) + R/W=0 --
        EMIT_BIT      tx=recessive
        EMIT_BIT      tx=dominant
        EMIT_BIT      tx=recessive
        EMIT_BIT      tx=dominant
        EMIT_BIT      tx=dominant
        EMIT_BIT      tx=dominant
        EMIT_BIT      tx=dominant
        EMIT_BIT      tx=dominant

        ; -- ACK slot --
        EMIT_BIT      tx=hiz expect=0 mask=1 capture=1
        BRANCH_ON     MISMATCH, nak

        ; -- Data byte 0xAB = 1010_1011 --
        EMIT_BIT      tx=recessive
        EMIT_BIT      tx=dominant
        EMIT_BIT      tx=recessive
        EMIT_BIT      tx=dominant
        EMIT_BIT      tx=recessive
        EMIT_BIT      tx=dominant
        EMIT_BIT      tx=recessive
        EMIT_BIT      tx=recessive

        ; -- ACK slot --
        EMIT_BIT      tx=hiz expect=0 mask=1 capture=1
        BRANCH_ON     MISMATCH, nak

        ; -- Stop condition --
        EMIT_QUARTER  sda=dominant  scl=dominant
        EMIT_QUARTER  sda=dominant  scl=recessive
        EMIT_QUARTER  sda=recessive scl=recessive

        MARK          label=1               ; ok
        HALT          status=0

nak:
        MARK          label=2               ; nak
        HALT          status=1
"""


def _selfcheck_roadmap_example() -> None:
    """ROADMAP §"I2C write-one-byte" hand-computed reference. 32 words.

    Note: ROADMAP says "31 instructions = 62 bytes" but the example
    actually has 32 instructions = 64 bytes (counted line-by-line in
    §1118). Doc fix needed; the assembler emits the literal count."""
    words = assemble(_ROADMAP_EXAMPLE_SRC, filename="roadmap-example")
    expected = [
        0x403C,  # LOAD_TIMING i2c_freq=0, 60     -> (8<<11) | 60
        0x3800,  # SET_BUS_MODE i2c               -> (7<<11) | (0<<8)
        0x1280,  # Q0: sda=rec scl=rec            -> (2<<11) | (1<<9) | (1<<7)
        0x1080,  # Q1: sda=dom scl=rec            -> (2<<11) | (0<<9) | (1<<7)
        0x1000,  # Q2: sda=dom scl=dom            -> (2<<11)
        # addr 0xA0 = 1010_0000 (rec, dom, rec, dom, dom, dom, dom, dom)
        0x0A00,
        0x0800,
        0x0A00,
        0x0800,
        0x0800,
        0x0800,
        0x0800,
        0x0800,
        # ACK slot: tx=hiz expect=0 mask=1 capture=1 -> (1<<11) | (2<<9) | 0b011
        0x0C03,
        # BRANCH_ON MISMATCH, nak (nak at PC 30; branch at PC 14;
        # offset = 30 - 14 - 1 = 15 = 0x0F) -> (5<<11) | (1<<7) | 0x0F
        0x288F,
        # data 0xAB = 1010_1011 (rec, dom, rec, dom, rec, dom, rec, rec)
        0x0A00,
        0x0800,
        0x0A00,
        0x0800,
        0x0A00,
        0x0800,
        0x0A00,
        0x0A00,
        # ACK slot
        0x0C03,
        # BRANCH_ON MISMATCH, nak (branch at PC 24; offset = 30-25 = 5)
        0x2885,
        # STOP: dom/dom, dom/rec, rec/rec
        0x1000,
        0x1080,
        0x1280,
        # MARK label=1                  -> (9<<11) | (1<<3)
        0x4808,
        # HALT status=0
        0x0000,
        # nak: MARK label=2             -> (9<<11) | (2<<3)
        0x4810,
        # HALT status=1                 -> (0<<11) | (1<<7)
        0x0080,
    ]
    assert (
        len(words) == len(expected) == 32
    ), f"ROADMAP example word count: got {len(words)}, expected 32"
    for i, (got, exp) in enumerate(zip(words, expected)):
        assert (
            got == exp
        ), f"ROADMAP example word {i}: got {got:#06x}, expected {exp:#06x}"


def _selfcheck_dw_equ() -> None:
    """`.equ x, 0x1234` + `.dw x, 0xC000` → [0x1234, 0xC000]."""
    src = """
        .equ x, 0x1234
        .dw  x, 0xC000
    """
    words = assemble(src, filename="dw-equ-check")
    assert words == [
        0x1234,
        0xC000,
    ], f".equ/.dw round-trip failed: got {[hex(w) for w in words]}"


def _selfcheck_whitespace_tolerance() -> None:
    """Mnemonic / operand separator must accept any whitespace, any amount:
    tabs, multiple spaces, mixed runs. Regression: the lexer used to call
    `body.partition(" ")` which only recognised a single space character and
    rejected tab-indented operand blocks."""
    src = (
        ".equ\tslow_div,\t59\n"
        "start:\n"
        "\tLOAD_TIMING\t\t i2c_freq,  slow_div\n"
        "\tSET_BUS_MODE   \ti2c\n"
        "\tHALT\tstatus=0\n"
    )
    words = assemble(src, filename="whitespace-check")
    # LOAD_TIMING i2c_freq=0, 59 -> (8<<11) | 59 = 0x403B
    # SET_BUS_MODE i2c           -> (7<<11)      = 0x3800
    # HALT status=0              ->                0x0000
    assert words == [
        0x403B,
        0x3800,
        0x0000,
    ], f"whitespace tolerance failed: got {[hex(w) for w in words]}"


def _selfcheck() -> None:
    _selfcheck_crc_and_frame()
    _selfcheck_roadmap_example()
    _selfcheck_dw_equ()
    _selfcheck_whitespace_tolerance()


# ===========================================================================
# CLI
# ===========================================================================


def main(argv: Optional[list[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        prog="mole-asm",
        description="Assemble a .moleasm source file into a .molecode "
        "binary. With --frame, also emit a UART-ready .mole.bin alongside.",
    )
    parser.add_argument(
        "input",
        nargs="?",
        help="path to the .moleasm source file. If omitted, runs the "
        "bundled batch (regenerates first-light / tmp108 / "
        "i2c-write-one-byte / loop-counter-demo / i2c-soak / "
        "i3c-write-byte next to this script) plus the self-check suite.",
    )
    parser.add_argument(
        "-o",
        "--output",
        help="output .molecode path. Defaults to "
        "INPUT_with_suffix_replaced.molecode next to the input.",
    )
    parser.add_argument(
        "--frame",
        nargs="?",
        const=True,
        default=None,
        metavar="PATH",
        help="also emit a framed .mole.bin (len + CRC) ready to drop onto "
        "the iCEbreaker UART. With no value, writes alongside the .molecode "
        "output; with PATH, writes to that exact path (matches the Rust "
        "CLI's --frame-output for cross-implementation parity tests).",
    )
    args = parser.parse_args(argv)

    _selfcheck()

    if args.input is None:
        return _run_bundled_batch()

    inp = Path(args.input)
    if args.output:
        out_molecode = Path(args.output)
    else:
        out_molecode = inp.with_suffix(".molecode")

    try:
        source = inp.read_text(encoding="utf-8")
        words = assemble(source, filename=str(inp))
    except AsmError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1

    out_molecode.write_bytes(pack_bytecode(words))
    print(
        f"assembled {len(words)} words ({len(words) * 2} bytes) " f"-> {out_molecode}"
    )

    if args.frame is not None:
        if args.frame is True:
            out_frame = out_molecode.with_suffix(".mole.bin")
        else:
            out_frame = Path(args.frame)
        frame = build_frame(words)
        out_frame.write_bytes(frame)
        crc = crc16_xmodem(frame[:-2])
        print(
            f"framed {len(frame)} bytes (CRC-16/XMODEM={crc:#06x}) " f"-> {out_frame}"
        )

    return 0


_BUNDLED_PROGRAMS = (
    "first-light",
    "tmp108",
    "i2c-write-one-byte",
    "loop-counter-demo",
    "i2c-soak",
    "i3c-write-byte",
)


def _run_bundled_batch() -> int:
    """Default __main__ behaviour: assemble every .moleasm source in
    the script's directory, write .molecode + .mole.bin (framed) for each."""
    here = Path(__file__).resolve().parent
    rc = 0
    for name in _BUNDLED_PROGRAMS:
        src = here / f"{name}.moleasm"
        if not src.is_file():
            print(f"skip {name}: source {src} not found")
            continue
        try:
            source = src.read_text(encoding="utf-8")
            words = assemble(source, filename=str(src))
        except AsmError as e:
            print(f"error in {name}: {e}", file=sys.stderr)
            rc = 1
            continue
        code_path = src.with_suffix(".molecode")
        code_path.write_bytes(pack_bytecode(words))
        frame_path = src.with_suffix(".mole.bin")
        frame = build_frame(words)
        frame_path.write_bytes(frame)
        crc = crc16_xmodem(frame[:-2])
        print(
            f"{name:24s}  {len(words):3d} words  "
            f"{len(frame):4d} frame bytes  CRC={crc:#06x}"
        )
    print("\nselfcheck: OK")
    return rc


if __name__ == "__main__":
    raise SystemExit(main())
