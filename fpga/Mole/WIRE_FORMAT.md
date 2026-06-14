# Mole host link wire format (v0.2)

This document covers the **iCEbreaker-side** UART link contract:
pin mapping, baud, flow control, resync rule. The **bit-level
frame and ring encoding** is in
[`../../docs/MOLE-0.2-SPEC.md`](../../docs/MOLE-0.2-SPEC.md);
that file is normative and takes precedence whenever this
document and it disagree. The v0 wire format (16-bit
instructions, `len + payload + crc` framing) is **retired** ---
v0 bytecode is not cross-compatible with the v0.2 engine. The
v0-era contents of this file are preserved in commit history;
this revision is the v0.2 replacement.

## 1. v0.2 wire format pointer

The host-to-Mole frame is a **little-endian byte stream** on the
UART carrying a v0.2 program: 8-byte preamble (magic + length) +
N × 4-byte instruction words + CRC-16/XMODEM trailer. See:

- [`../../docs/MOLE-0.2-SPEC.md`](../../docs/MOLE-0.2-SPEC.md)
  §10 (wire format) for the preamble layout, the magic value
  (`0x0002_4D4C`), `MAX_PROGRAM_WORDS`, and the byte-order
  conventions.
- [`../../docs/MOLE-0.2-SPEC.md`](../../docs/MOLE-0.2-SPEC.md)
  §11 for the 32-bit HALT word layout
  (`tag[31:30]=0b11 | overflow[29] | mismatch[28] | status[27:23]
  | reserved[22:0]=0`) and the 5-bit status partitioning
  (user `0x00..0x1C`, reserved `0x1D..0x1E`, engine-trap `0x1F`).
- [`../../docs/MOLE-0.2-SPEC.md`](../../docs/MOLE-0.2-SPEC.md)
  §13 for the loader/runtime error catalogue (E-FRM-, E-PRG-,
  E-RNG-, E-RAW-, E-OP-, E-OPD- families) including which frame
  errors the loader rejects pre-write (§16.4 pre-scan) vs which
  surface as runtime traps.

The host-side encoder is the `mole-asm` crate at
[`../../mole-asm/`](../../mole-asm/) (CLI front-end in
[`../../mole-asm-cli/`](../../mole-asm-cli/)). The host-side
loader + ring decoder is the `mole-loader` crate at
[`../../mole-loader/`](../../mole-loader/) (CLI front-end in
[`../../mole-loader-cli/`](../../mole-loader-cli/)). The ABI
constants are in [`../../mole-abi/`](../../mole-abi/). For
bring-up, prefer driving the link via `mole-loader-cli` rather
than hand-encoding frames; the only reason to hand-encode is to
exercise a corner of the wire format the host crates do not yet
support, in which case file an issue first.

## 2. UART settings (board-specific)

UART link: **2 000 000 baud, 8N1, RTS/CTS hardware flow control**
(active-low, FT2232H convention). The iCEbreaker's FT2232H
channel A is `/dev/ttyUSB0` on Linux and typically `COM3` or
higher on Windows; on macOS it enumerates as
`/dev/tty.usbserial-ibXXXXXX` (the exact suffix depends on the
device's chip-side serial number).

The engine clock and the UART clock are **both 24 MHz on Verde**
(1:1 ratio off `PLLOUTGLOBAL`; see
[`../../docs/MOLE-0.2-SPEC.md`](../../docs/MOLE-0.2-SPEC.md) §2
clock-domain table). 2 Mbaud is 12× the uartClk, paired with
**8× RX oversample** at the `UartConfig` instantiation in
`MoleTop` — `baudRate * oversample = 16 MHz < 24 MHz` clears
the `UartConfig` DDS-overflow guard with ~33 % headroom.

Earlier history: the Verde production default was 1 Mbaud × 16×
(matches every textbook UART implementation; pinned conservatively
to keep the engine well off any DDS-overflow edge while the rest
of v0.2 stabilised). 2 Mbaud × 8× is now the default; 1 Mbaud
remains available as a fallback by rebuilding the engine with
`uartBaud = 1_000_000` + `oversample = 16` and passing
`--baud 1000000` to `mole-loader`.

## 3. Flow control --- `crtscts` is mandatory

`io_uCts` (PMOD1A pin 18, MoleTop OUT → FT2232H CTS#) is
asserted (LOW) only while the top-level phase FSM is in
`acceptLoadState`. A host driver with `crtscts` enabled
therefore holds its TX off whenever Mole is running a program or
draining the result ring --- this is what enforces the spec
invariant *"while program is not HALTED, don't accept data"*.

`io_uRts` (PMOD1A pin 19, MoleTop IN ← FT2232H RTS#) is asserted
(LOW) when the host's USB pipe has room. The drainer's TX
stream is gated on this, so when the host falls behind the
drainer stops issuing new bytes --- but, per standard
HW-flow-control semantics, an in-flight UART frame completes on
the wire regardless. The FPGA pin enables an internal pull-up
so an unwired board reads HIGH = RTS#-deasserted = drainer
halted (visible failure rather than metastable garbage).

A working `stty` line for raw bring-up:

```sh
stty -F /dev/ttyUSB0 2000000 cs8 -cstopb -parenb \
    crtscts -ixon -ixoff -ixany raw
```

`crtscts` enables the HW flow control; `-ixon -ixoff -ixany`
explicitly disables any software (XON/XOFF) flow control ---
Mole does not speak it and accidentally enabling it on the host
turns arbitrary frame bytes into XON/XOFF and breaks the link.

`mole-loader-cli` sets these flags on the `serialport::SerialPortBuilder`
before opening the port; if you are driving the link from another
host tool you must replicate this. The flags MUST be set on the
builder, not after `open()` returns --- on Linux this is racy
enough to drop the first frame's bytes intermittently.

## 4. Resync rule

The wire format has no in-band start-of-frame marker beyond the
magic word. If anything goes wrong --- bad CRC, bad length,
UART RX framing / parity / overrun error --- the loader latches
a fault, lights the red LED, and enters a **Resync** state in
which it drops every incoming byte until the RX line has been
continuously high for **at least two UART byte-times**
(~20 bit periods, ~10 µs at 2 Mbaud).

The host is therefore obligated to:

1. **After any host-side abort or retransmit:** stop sending,
   wait at least 20 µs (a safe round number is 1 ms), then send
   the next frame from byte 0.
2. **Between back-to-back frames:** the host SHOULD wait for the
   result drain to complete before sending the next program. If
   the host sends a frame while the engine is running or the
   result ring is still draining, the loader applies
   back-pressure on its UART input via CTS#. The host's
   `crtscts`-enabled UART driver will see buffer-full conditions.
   The loader does NOT silently buffer.
3. **First frame after power-up or reset:** the line has been
   idle since boot, so no extra delay is needed beyond the usual
   "open the serial port, then send".

The two-byte-time threshold gives the loader enough margin to
distinguish frame boundaries from intra-frame inter-character
gaps that are technically legal at the UART layer but disallowed
by Mole's contract: **once a frame begins, all of its bytes MUST
follow without 10+ µs gaps**. In practice any host that calls
`write()` once with the full frame buffer satisfies this
trivially. `mole-loader-cli` builds the full frame in memory
before issuing one `write_all()`.

## 5. Engine start

There is no explicit "go" command. The loader auto-fires
`engine.start` the cycle after a CRC-valid frame's final SPRAM
write retires. This keeps the wire format minimal --- there is
exactly one frame type to encode --- and matches the typical use
case (build a frame, send it, observe the result).

## 6. Result drain

When the engine HALTs, MoleTop sweeps the entire **result ring**
out the UART, 4 little-endian bytes per 32-bit ring word (b0=LSB
first).

```
total_bytes = MoleConfig.resultRingByteCount   (default: 8192)
            = 2048 * 4-byte words
```

There is no length prefix on the result drain. The host knows
exactly how many bytes to expect (it is a build-time constant of
the bitstream). The host decodes records from the ring by
inspecting tag bits inside each 32-bit word and stops processing
when it hits the **HALT word at `resultLimit`** (the last 32-bit
slot, decoded directly as a 32-bit record per §11).

The first 4 bytes of the ring are always the **Revision word**
(see [`src/hw/Revision.scala`](src/hw/Revision.scala)):
`major (1 byte) | minor (1 byte) | patch (2 bytes)`,
little-endian as a single 32-bit word at ring offset 0
(spec §11.2).

A drain always streams the **full ring**, padded with stale data
from previous runs if the engine wrote fewer bytes than the ring
holds. Hosts MUST decode by tag-bit until they reach the
reserved HALT slot at `resultLimit`; bytes between the last
emitted record and the HALT slot are *trailing garbage* and must
not be interpreted as records (this hazard burnt v0 Step 17 ---
see commits `6edd139` and `5094d4d` in branch history). The
`mole-loader::decode_ring` function handles this correctly out
of the box.

## 7. Result-ring overflow and HALT-word semantics

The result ring is bounded: it lives at `[resultBase,
resultLimit]` where `resultLimit = resultBase + resultWordCount
- 1`. The top-of-ring slot is **reserved exclusively for the
HALT status word** so an overflowing record stream cannot
clobber it. Hosts relying on a clean HALT to terminate the
decode walk can do so unconditionally.

A record write is admitted only if its full footprint fits at or
below `recordLimit = resultLimit - 1`:

- **CAPTURE** (1 word) requires `resultWp ≤ recordLimit`.
- **MARK** (3 words) requires `resultWp ≤ recordLimit - 2`.

A write whose footprint does not fit sets the **overflow bit**
in the HALT word (bit `[29]`) and is silently dropped. The
engine does **not** abort the program on overflow --- it
continues fetching and executing; subsequent CAPTURE / MARK
opcodes that would also overflow are silently dropped in the
same way.

The full HALT word layout, status partitioning, and ABI
constants are in
[`../../docs/MOLE-0.2-SPEC.md`](../../docs/MOLE-0.2-SPEC.md) §11
and [`../../mole-abi/src/lib.rs`](../../mole-abi/src/lib.rs).
Per AGENTS §3.15, the four sticky engine flags (MISMATCH,
TIMEOUT, START, STOP) surface through two channels: MISMATCH
ships in HALT bit `[28]`; TIMEOUT / START / STOP are observable
via program logic (BRANCH_ON consumes the flag and the program
HALTs with a distinct status code per outcome).

## 8. Bus-safety invariant (INV-BUS-NO-CONTENTION)

This document covers the host-link wire format. The companion
invariant on the *electrical* bus (SDA / SCL) is that the engine
never simultaneously asserts both `driveLow` and `driveHigh` on
the same `MoleBus` line --- doing so would cause push-pull
shoot-through on PP-class `BUS_MODE`s. The canonical statement
of this rule, the decode truth table, and the rationale live in
[`AGENTS.md`](AGENTS.md) §"Open-drain primitive: custom MoleBus";
that file is the single source of truth. Defense-in-depth is
layered: `SymbolDecoder` is structurally incapable of producing
the contention pair, a sim-time `assert` in the engine catches
any future writer that bypasses the decoder, and a second
`assert` in `MoleIoBufUp5k` guards the pad boundary. The
exhaustive sweep at `sim-symbol-decoder-contention` audits every
`(BUS_MODE, tx_symbol)` cell on every CI run.

## 9. Versioning

This is the **v0.2** wire format. Per the pre-Phase-0 mutability
caveat in
[`../../docs/MOLE-0.2-SPEC.md`](../../docs/MOLE-0.2-SPEC.md) §1
and §10, the format is **not yet a stable contract** --- any
field may change without a version bump until the first tagged
Phase 0 encoder release ships. After that point, any change to
the magic / preamble / instruction encoding / HALT word /
result-ring layout requires:

- a bumped `FORMAT_VERSION` in `mole-abi`,
- a `BREAKING CHANGE:` footer in the commit per `../../AGENTS.md`
  §6,
- a matching update to `docs/MOLE-0.2-SPEC.md`,
- a matching update to this document.

The current `FORMAT_VERSION` is `0x0002`. The v0 format
(`FORMAT_VERSION = 0x0001`, no magic, 16-bit instructions,
4-bit HALT status) is incompatible and not supported by the
v0.2 engine or encoder.
