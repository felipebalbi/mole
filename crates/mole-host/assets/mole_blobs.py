"""Hand-encoder for Mole programs --- generates wire blobs ready to be
written to /dev/ttyUSB0 at 1 Mbaud, 8N1.

Mirrors fpga/Mole/src/hw/Instruction.scala `encode()` byte-for-byte.
Use this until the Rust `mole-host` crate lands; at that point the
Rust encoder's golden tests will diff against the same `.mole.bin`
files this script emits.

Running this as `__main__` writes:

    first_light.mole.bin   (40 bytes,  infinite loop, no HALT)
    tmp108.mole.bin        (146 bytes, single shot, HALT triggers
                            8192-byte result drain)

into the script's own directory, and prints a hex / Python-bytes
summary of each frame.

The on-wire frame format and CRC algorithm are documented in
`fpga/Mole/WIRE_FORMAT.md`. The encode() helpers below mirror
`fpga/Mole/src/hw/Instruction.scala`.
"""

from __future__ import annotations

from pathlib import Path
from typing import Iterable

# -------- opcode positions (Opcode.position) --------------------------------
OP_HALT          = 0x0
OP_EMIT_BIT      = 0x1
OP_EMIT_QUARTER  = 0x2
OP_STRETCH_SCL   = 0x3
OP_WAIT_ON       = 0x4
OP_BRANCH_ON     = 0x5
OP_JMP           = 0x6
OP_SET_BUS_MODE  = 0x7
OP_LOAD_TIMING   = 0x8
OP_MARK          = 0x9
OP_SAMPLE        = 0xA
OP_DRIVE         = 0xB
# 0xC..0xF reserved-v0.5

# -------- TxSymbol.position -------------------------------------------------
TX_DOM = 0b00
TX_REC = 0b01
TX_HIZ = 0b10
# 0b11 reserved (raw_override)

# -------- CondCode.position -------------------------------------------------
C_ALWAYS       = 0x0
C_MISMATCH     = 0x1
C_NOT_MISMATCH = 0x2
C_START_SEEN   = 0x3
C_STOP_SEEN    = 0x4
C_SDA_LOW      = 0x5
C_SDA_HIGH     = 0x6
C_SCL_HIGH     = 0x7
C_TIMEOUT      = 0x8
C_NOT_TIMEOUT  = 0x9

# -------- BusMode wire values (defaultEncoding "busModeWire") ---------------
BM_I2C    = 0
BM_I3C_OD = 1
BM_I3C_PP = 6
BM_HDR    = 7

# -------- instruction encoders ---------------------------------------------

def flag_triple(expect: bool, mask: bool, capture: bool) -> int:
    return (
        ((1 if expect else 0) << 2)
        | ((1 if mask else 0) << 1)
        | (1 if capture else 0)
    )


def emit_bit(
    tx: int,
    expect: bool = False,
    mask: bool = False,
    capture: bool = False,
) -> int:
    return (OP_EMIT_BIT << 12) | (tx << 10) | flag_triple(expect, mask, capture)


def emit_quarter(
    sda: int,
    scl: int,
    expect: bool = False,
    mask: bool = False,
    capture: bool = False,
) -> int:
    return (
        (OP_EMIT_QUARTER << 12)
        | (sda << 10)
        | (scl << 8)
        | flag_triple(expect, mask, capture)
    )


def halt(status: int = 0) -> int:
    assert 0 <= status < 16
    return (OP_HALT << 12) | (status << 8)


def set_bus_mode(mode_wire: int) -> int:
    return (OP_SET_BUS_MODE << 12) | (mode_wire << 9)


def branch_on(cond: int, pc_rel_offset: int) -> int:
    assert -128 <= pc_rel_offset <= 127
    return (OP_BRANCH_ON << 12) | (cond << 8) | (pc_rel_offset & 0xff)


def jmp(addr: int) -> int:
    assert 0 <= addr < (1 << 12)
    return (OP_JMP << 12) | addr


# -------- CRC-16/XMODEM ----------------------------------------------------

def crc16_xmodem(data: bytes) -> int:
    crc = 0x0000
    for b in data:
        crc ^= b << 8
        for _ in range(8):
            crc = (
                ((crc << 1) ^ 0x1021) & 0xFFFF
                if crc & 0x8000
                else (crc << 1) & 0xFFFF
            )
    return crc


# -------- frame builder ----------------------------------------------------

def build_frame(words: Iterable[int]) -> bytes:
    ws = list(words)
    assert 1 <= len(ws) <= 4096, f"len {len(ws)} out of 1..4096"
    payload = bytearray()
    payload.append(len(ws) & 0xff)
    payload.append((len(ws) >> 8) & 0xff)
    for w in ws:
        assert 0 <= w <= 0xFFFF, f"word {w:#06x} > 16 bits"
        payload.append(w & 0xff)
        payload.append((w >> 8) & 0xff)
    crc = crc16_xmodem(bytes(payload))
    payload.append(crc & 0xff)
    payload.append((crc >> 8) & 0xff)
    return bytes(payload)


# ---------------------------------------------------------------------------
# Sanity self-check: WIRE_FORMAT.md §6 worked example + the CRC catalog
# check value.
# ---------------------------------------------------------------------------

def _selfcheck() -> None:
    expected = bytes.fromhex("020000900060DF9F")
    got = build_frame([0x9000, 0x6000])
    assert got == expected, (
        f"selfcheck FAIL\n  got: {got.hex(' ')}\n  exp: {expected.hex(' ')}"
    )
    assert crc16_xmodem(b"123456789") == 0x31C3, (
        "CRC-16/XMODEM catalog check failed"
    )


# ---------------------------------------------------------------------------
# Program 1: FIRST_LIGHT
#
#   Forever loop. SET_BUS_MODE i2c, then repeat:
#     - 4 quarters bus idle           (SDA hi, SCL hi)
#     - 2 quarters START              (SDA falls while SCL high)
#     - 2 quarters SCL down           (ready for first bit)
#     - 8 EMIT_BITs for address 0x90  (TMP108 7-bit 0x48 << 1, R/W=0)
#     - BRANCH_ON ALWAYS back to top
#
#   No HALT, no STOP. Scope sees clean SDA/SCL wiggle. Press reset
#   button (or power-cycle) to stop. The engine never drains because
#   it never executes HALT.
# ---------------------------------------------------------------------------

def first_light_program() -> list[int]:
    addr = 0x90
    bits_msb_first = [(addr >> i) & 1 for i in range(7, -1, -1)]
    bit_insns = [emit_bit(TX_REC if b == 1 else TX_DOM) for b in bits_msb_first]
    body = (
        [emit_quarter(TX_REC, TX_REC)] * 4
        + [emit_quarter(TX_DOM, TX_REC)] * 2
        + [emit_quarter(TX_DOM, TX_DOM)] * 2
        + bit_insns
    )
    # body sits at PC 1..N. Branch is at PC = 1+N. Want next PC = 1.
    # next_pc = (1 + N) + 1 + offset = 1  =>  offset = -(1 + N)
    branch_offset = -(1 + len(body))
    return [set_bus_mode(BM_I2C)] + body + [branch_on(C_ALWAYS, branch_offset)]


# ---------------------------------------------------------------------------
# Program 2: TMP108
#
#   SET_BUS_MODE i2c, START, send 0x90 (with ACK capture),
#   send 0x00 (pointer reg, with ACK capture), Repeated Start,
#   send 0x91 (with ACK capture), read MSB byte (8 capture bits +
#   controller ACK), read LSB byte (8 capture bits + controller NACK),
#   STOP, HALT. The result ring captures 19 bits + HALT word.
# ---------------------------------------------------------------------------

def _send_byte(b: int) -> list[int]:
    """Emit 8 controller-driven bits, MSB first. No capture."""
    return [
        emit_bit(TX_REC if ((b >> i) & 1) == 1 else TX_DOM)
        for i in range(7, -1, -1)
    ]


def _slave_ack_capture() -> int:
    """Controller releases SDA; expect slave to pull low (ACK). Capture."""
    return emit_bit(TX_REC, expect=False, mask=True, capture=True)


def _read_byte_capture() -> list[int]:
    """Controller releases SDA, slave drives. Capture each bit, no compare."""
    return [
        emit_bit(TX_REC, expect=False, mask=False, capture=True)
        for _ in range(8)
    ]


def _controller_ack() -> int:
    return emit_bit(TX_DOM)


def _controller_nack() -> int:
    return emit_bit(TX_REC)


def _start_seq() -> list[int]:
    return (
        [emit_quarter(TX_REC, TX_REC)] * 4    # bus idle
        + [emit_quarter(TX_DOM, TX_REC)] * 2  # SDA falls, SCL high -> START
        + [emit_quarter(TX_DOM, TX_DOM)] * 2  # SCL down, ready for bit 7
    )


def _repeated_start_seq() -> list[int]:
    return (
        [emit_quarter(TX_DOM, TX_DOM)] * 2    # pull SCL low cleanly
        + [emit_quarter(TX_REC, TX_DOM)] * 2  # release SDA while SCL low
        + [emit_quarter(TX_REC, TX_REC)] * 2  # release SCL = bus idle
        + [emit_quarter(TX_DOM, TX_REC)] * 2  # Sr: SDA falls while SCL high
        + [emit_quarter(TX_DOM, TX_DOM)] * 2  # SCL down, ready for first bit
    )


def _stop_seq() -> list[int]:
    return (
        [emit_quarter(TX_DOM, TX_DOM)] * 2    # pull SCL low, drive SDA low
        + [emit_quarter(TX_DOM, TX_REC)] * 2  # release SCL while SDA low
        + [emit_quarter(TX_REC, TX_REC)] * 2  # SDA rises while SCL high = STOP
    )


def tmp108_program() -> list[int]:
    prog: list[int] = []
    prog.append(set_bus_mode(BM_I2C))
    prog += _start_seq()
    prog += _send_byte(0x90)
    prog.append(_slave_ack_capture())
    prog += _send_byte(0x00)
    prog.append(_slave_ack_capture())
    prog += _repeated_start_seq()
    prog += _send_byte(0x91)
    prog.append(_slave_ack_capture())
    prog += _read_byte_capture()
    prog.append(_controller_ack())
    prog += _read_byte_capture()
    prog.append(_controller_nack())
    prog += _stop_seq()
    prog.append(halt(0))
    return prog


# ---------------------------------------------------------------------------

PROGRAMS: dict[str, list[int]] = {
    "first_light": first_light_program(),
    "tmp108": tmp108_program(),
}


def _dump(label: str, words: list[int], frame: bytes) -> None:
    print(f"\n=== {label} ===")
    print(f"  program words : {len(words)}")
    print(f"  frame bytes   : {len(frame)}")
    print(f"  CRC-16/XMODEM : {crc16_xmodem(frame[:-2]):#06x}")
    print(f"  frame hex     : {frame.hex(' ')}")


def main() -> int:
    _selfcheck()
    here = Path(__file__).resolve().parent
    for name, words in PROGRAMS.items():
        frame = build_frame(words)
        out = here / f"{name}.mole.bin"
        out.write_bytes(frame)
        _dump(f"{name} -> {out.name}", words, frame)
    print("\nselfcheck: OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
