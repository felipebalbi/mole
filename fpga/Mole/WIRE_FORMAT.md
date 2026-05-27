# Mole host link wire format (v0)

This document is the **stable contract** between any host tool and
the Mole engine on the iCEbreaker. Once the Phase 2 bitstream ships
a tagged release, the format below is a wire contract --- breaking
it requires a bytecode-format version bump (see `../../AGENTS.md`
§3.17, §6 "Breaking changes").

The wire format is **raw binary on the UART**. There is no ASCII
escaping, no SLIP framing, no start-of-frame byte. Frames are
delimited by **idle time** on the line (see §"Resync rule").

UART settings: **2 000 000 baud, 8N1**, no flow control. The
iCEbreaker's FT2232H channel A is `/dev/ttyUSB0` on Linux and
typically `COM3` or higher on Windows.

---

## 1. Frame layout (host -> engine: program upload)

```
   offset  size  field
   ------  ----  -----
        0     2  len           (16-bit, little-endian)
        2     2  word[0]       (16-bit opcode, little-endian)
        4     2  word[1]       (16-bit opcode, little-endian)
        ...
   2+2(N-1)   2  word[N-1]     (16-bit opcode, little-endian)
        ?     2  crc           (16-bit CRC-16/XMODEM, little-endian)
```

Total frame size in bytes:

```
   frame_size = 2 + 2 * len + 2
              = 4 + 2 * len     bytes
```

Worked-out examples:

```
   len = 1   -> frame_size =  6 bytes  (2 len + 2 program + 2 crc)
   len = 2   -> frame_size =  8 bytes
   len = 4   -> frame_size = 12 bytes
   len = 16  -> frame_size = 36 bytes
   len = 4096 (max) -> frame_size = 8196 bytes
```

### 1.1 The `len` field

- `len` is the **number of 16-bit opcode words** in the program.
- `len` does **not** include itself.
- `len` does **not** include the trailing CRC.
- Valid range: `1 <= len <= 4096`. `len = 0` is rejected (no
  program to run). `len > 4096` is rejected (exceeds
  `MoleConfig.programWordCount`, which is itself capped at 4096
  by the 12-bit `JMP` absolute-address field; see
  `../../ROADMAP.md` §"Encoding width").
- Encoded **little-endian**: the low byte (`len & 0xFF`) goes on
  the wire first, then the high byte (`(len >> 8) & 0xFF`).

### 1.2 The program words

- Each opcode is exactly **16 bits** (`../../ROADMAP.md`
  §"Encoding width" and `AGENTS.md` §3.9).
- Each word is transmitted **little-endian**: low byte first,
  then high byte.
- Words are transmitted in program-counter order: `word[0]` is
  the instruction at SPRAM address 0, `word[1]` at address 1,
  and so on.
- The host is responsible for ensuring `word[N-1]` either halts
  the engine (`HALT`) or jumps somewhere that eventually does.
  The engine does not insert an implicit halt; if your program
  falls off the end, it runs whatever uninitialised SPRAM
  contains.

### 1.3 The CRC field

- 16-bit CRC over **all preceding bytes** (the `len` field +
  every program byte). The CRC bytes themselves are **not**
  covered by the CRC.
- Encoded **little-endian**: low byte first, then high byte.
- Algorithm: **CRC-16/XMODEM**. Full parameters in §3 below.

---

## 2. Frame layout (engine -> host: result drain)

When the engine completes a run (executes `HALT`), MoleTop sweeps
the entire **result ring** out the UART, low byte of each 16-bit
ring word first.

```
   total_bytes = MoleConfig.resultRingByteCount   (default: 8192)
```

There is no length prefix on the result drain. The host knows
exactly how many bytes to expect (it is a build-time constant of
the bitstream; the host learns it from the bring-up procedure,
not from the wire). The host decodes records from the ring by
inspecting tag bits inside each record and stops processing when
it hits the `HALT` record near the end.

The first 4 bytes of the ring are always the **Revision word**
(see `src/hw/Revision.scala`): `major (1 byte) | minor (1 byte) |
patch (2 bytes)`, little-endian per the 16-bit ring grain.

A drain always streams the **full ring**, padded with stale data
from previous runs if the engine wrote fewer bytes than the ring
holds. Hosts MUST decode by tag-bit until they see `HALT`; bytes
after `HALT` are not meaningful.

---

## 3. CRC-16/XMODEM --- full specification

Every CRC reference catalogue uses a slightly different name for
this variant. Mole uses what `pycrc` and the Rocksoft CRC catalogue
call **CRC-16/XMODEM** (also known as CRC-16/ZMODEM, CRC-16/ACORN,
CRC-CCITT-FALSE's non-reflected cousin). The full parameter set:

| Parameter      | Value     | Notes                                  |
|----------------|-----------|----------------------------------------|
| Width          | 16        | bits                                   |
| Polynomial     | `0x1021`  | normal form, x^16 + x^12 + x^5 + 1     |
| Initial value  | `0x0000`  | CRC register init                      |
| Reflect input  | false     | bytes processed MSB-first              |
| Reflect output | false     | final register used as-is              |
| XOR output     | `0x0000`  | no final XOR                           |
| Check          | `0x31C3`  | CRC of the ASCII string `"123456789"`  |

Note on residue: CRC-16/XMODEM's catalogue residue is `0x0000`,
but that property only holds when the CRC trailer is appended
**big-endian** (high byte first). Mole appends the trailer
**little-endian**, so feeding `payload || crc_lo || crc_hi` back
through the algorithm does **not** yield zero. The correct loader
pattern is: compute the CRC over the payload bytes only, then
compare against the 16-bit trailer reassembled from
`(crc_hi << 8) | crc_lo`. The hardware loader follows that pattern;
hosts validating their own builders should do the same.

### 3.1 Reference algorithm (bytewise, MSB-first)

```c
uint16_t crc16_xmodem_update(uint16_t crc, uint8_t byte) {
    crc ^= ((uint16_t)byte) << 8;
    for (int i = 0; i < 8; i++) {
        if (crc & 0x8000) {
            crc = (crc << 1) ^ 0x1021;
        } else {
            crc = crc << 1;
        }
    }
    return crc & 0xFFFF;
}

uint16_t crc16_xmodem(const uint8_t *data, size_t len) {
    uint16_t crc = 0x0000;       /* init */
    for (size_t i = 0; i < len; i++) {
        crc = crc16_xmodem_update(crc, data[i]);
    }
    return crc;                  /* no final XOR */
}
```

Python equivalent:

```python
def crc16_xmodem(data: bytes) -> int:
    crc = 0x0000
    for b in data:
        crc ^= b << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) & 0xFFFF if crc & 0x8000 \
                  else (crc << 1) & 0xFFFF
    return crc
```

### 3.2 Test vectors

Use these to validate any independent host implementation:

| Input bytes                       | Expected CRC (hex) |
|-----------------------------------|--------------------|
| empty (zero bytes)                | `0x0000`           |
| `0x00`                            | `0x0000`           |
| `0xFF`                            | `0x1EF0`           |
| ASCII `"123456789"`               | `0x31C3`           |
| `0x00 0x00`                       | `0x0000`           |
| `0xAA 0x55`                       | `0xF8E5`           |
| Example frame payload (see §6)    | `0x9FDF`           |

The `"123456789"` vector is the standard catalogue check value
and is the one to look for if you are diffing against `pycrc`,
crccalc.com, or the Boost CRC implementations.

### 3.3 Standard library quick-references

If you do not want to roll your own:

```python
# Python: install with `pip install crcmod`
import crcmod
crc16 = crcmod.mkCrcFun(0x11021, initCrc=0x0000,
                        rev=False, xorOut=0x0000)
crc16(b"123456789")        # -> 0x31C3
```

```rust
// Rust: crc = "3"
use crc::{Crc, CRC_16_XMODEM};
const X: Crc<u16> = Crc::<u16>::new(&CRC_16_XMODEM);
X.checksum(b"123456789");  // -> 0x31C3
```

In every case the parameter set above is what to verify against;
the library is just a convenience.

### 3.4 What gets CRC'd

The CRC covers the bytes in this order:

```
   len_lo, len_hi,
   word[0]_lo, word[0]_hi,
   word[1]_lo, word[1]_hi,
   ...,
   word[N-1]_lo, word[N-1]_hi
```

That is, the `len` field is included; the `crc` field is not. The
CRC is computed over the **wire byte stream** in transmission
order (little-endian halves already serialised), not over the
16-bit values directly. The two views give the same answer because
CRC-16/XMODEM is byte-oriented, but stating it explicitly removes
ambiguity for anyone who builds the frame from a list of u16s and
then runs the CRC over it.

---

## 4. Resync rule

The wire format has no in-band start-of-frame marker. If anything
goes wrong --- bad length, bad CRC, UART RX framing/parity/overrun
error --- the loader latches a fault, lights the red LED, and
enters a **Resync** state in which it drops every incoming byte
until the RX line has been continuously high for **at least two
UART byte-times** (~20 bit periods, ~10 microseconds at 2 Mbaud).

The host is therefore obligated to:

1. **After any host-side abort or retransmit:** stop sending,
   wait at least 10 microseconds (a safe round number is 1 ms),
   then send the next frame from byte 0.
2. **Between back-to-back frames:** the host SHOULD wait for the
   result drain to complete before sending the next program. If
   the host sends a frame while the engine is running or the
   result ring is still draining, the loader applies back-
   pressure on its UART input. The host's UART driver will see
   buffer-full conditions. The loader does NOT silently buffer.
3. **First frame after power-up or reset:** the line has been
   idle since boot, so no extra delay is needed beyond the usual
   "open the serial port, then send".

The two-byte-time threshold gives the loader enough margin to
distinguish frame boundaries from intra-frame inter-character gaps
that are technically legal at the UART layer but disallowed by
Mole's contract: **once a frame begins, all of its bytes MUST
follow without 10+ microsecond gaps**. In practice any host that
calls `write()` once with the full frame buffer satisfies this
trivially.

---

## 5. Engine start

There is no explicit "go" command. The loader auto-fires
`engine.start` the cycle after a CRC-valid frame's final SPRAM
write retires. This keeps the wire format minimal --- there is
exactly one frame type to encode --- and matches the typical use
case (build a frame, send it, observe the result).

---

## 6. Worked example: a 2-word program

The program: `SET_BUS_MODE i2c; HALT 0`.

Suppose the assembler produces:

```
   word[0] = 0x9000      (SET_BUS_MODE i2c, hypothetical encoding)
   word[1] = 0x6000      (HALT 0, hypothetical encoding)
```

(The exact encodings are defined in `src/hw/Instruction.scala`;
the numbers here are illustrative.)

Build the frame:

```
   len = 2          -> bytes: 0x02 0x00
   word[0] = 0x9000 -> bytes: 0x00 0x90
   word[1] = 0x6000 -> bytes: 0x00 0x60
```

So the pre-CRC byte stream is:

```
   0x02 0x00 0x00 0x90 0x00 0x60
```

Compute the CRC over those 6 bytes with the algorithm in §3.1.
The result is `0x9FDF`. Append it little-endian as
`crc_lo crc_hi`. The complete 8-byte frame on the wire is:

```
   0x02 0x00 0x00 0x90 0x00 0x60 0xDF 0x9F
```

(That sequence is one of the test vectors in §3.2.)

A complete Python builder (worth copy-pasting for first bring-up):

```python
import struct

def crc16_xmodem(data: bytes) -> int:
    crc = 0x0000
    for b in data:
        crc ^= b << 8
        for _ in range(8):
            crc = ((crc << 1) ^ 0x1021) & 0xFFFF if crc & 0x8000 \
                  else (crc << 1) & 0xFFFF
    return crc

def build_frame(words: list[int]) -> bytes:
    if not (1 <= len(words) <= 4096):
        raise ValueError("len must be in 1..4096")
    payload = struct.pack("<H", len(words))
    for w in words:
        if not (0 <= w <= 0xFFFF):
            raise ValueError(f"word {w:#06x} does not fit in 16 bits")
        payload += struct.pack("<H", w)
    crc = crc16_xmodem(payload)
    return payload + struct.pack("<H", crc)

# Send it
import serial
port = serial.Serial("/dev/ttyUSB0", 2_000_000, timeout=1)
port.write(build_frame([0x9000, 0x6000]))   # placeholder opcodes
```

---

## 7. Versioning

This is the **v0** wire format, frozen at the Phase 2 release.
Any future change to:

- the `len` field width or semantics,
- the byte order of `len` / opcodes / CRC,
- the CRC algorithm or its parameters,
- the resync rule,
- the result-drain format,

is a **breaking change**. Per `../../AGENTS.md` §3.17 and §6, a
breaking change requires:

- a bumped wire-format version (introduced as a leading version
  byte or magic prefix; the v0 format has none, which is why v1
  will have to introduce one explicitly),
- a `BREAKING CHANGE:` footer in the commit that lands the
  change,
- a matching update to this document.

Non-breaking additions (e.g. a new opcode that fits in a reserved
slot) do not bump the wire format.
