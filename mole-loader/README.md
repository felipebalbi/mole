# mole-loader

Host-side loader and result-ring decoder for the Mole bit-cycle
engine.

`mole-loader` is the runtime counterpart to the `mole-asm`
assembler: it takes a `.mole.bin` artifact, ships it to a real Mole
engine over a serial port, waits for the program to finish, drains
the result ring, and decodes the captured records.

The CLI front-end lives in the sibling crate
[`mole-loader-cli`](../mole-loader-cli/) and produces the binary
`mole-loader`.

## Quick start

```text
# Verify a frame locally, no hardware needed.
mole-loader --dry-run path/to/program.mole.bin

# Send to a Mole engine over UART, drain results, print them.
mole-loader --port /dev/ttyUSB0 path/to/program.mole.bin
```

The full CLI surface, including stdin support (`-`), the
`--expect-revision X.Y.Z` assertion, `--ring-bytes` override, and
`--dump-ring` for archiving raw evidence, is documented in
`mole-loader --help`.

## What the crate provides

- `mole_loader::verify_frame(&[u8])` --- the inverse of
  `mole_asm::frame::build_frame`. Validates length header and
  CRC-16/XMODEM trailer before the loader ships the artifact.
- `mole_loader::decode_ring(&[u8])` --- parses the drained ring
  buffer into a typed `DecodedRing { revision, records, halt,
  trailing_garbage_words }`.
- `mole_loader::Transport` --- serial-port wrapper that opens the
  UART with mandatory hardware RTS/CTS, exposes `send_frame` and
  `drain_ring`, and reports progress through a `Progress`
  callback.

## Wire contract

- UART, default 2 Mbaud, 8N1, **mandatory hardware RTS/CTS flow
  control** (see `fpga/Mole/WIRE_FORMAT.md`).
- Host writes the framed program; engine pulls bytes via CTS.
- On `HALT` the engine drains `resultRingByteCount` bytes back,
  gated by host RTS.

The loader refuses to fall back to software / no flow control:
the underlying engine FSM has no recovery path for dropped bytes,
and silent failure modes are worse than a hard error.

## Trailing-garbage hazard

SPRAM on the FPGA is **not** zero-initialised. Slots between the
last record the engine wrote and the HALT terminator hold
undefined contents on real silicon (zeros only under Verilator).
The decoder reports `trailing_garbage_words` so callers can warn
the user; a future engine revision will either zero the ring at
HALT entry or emit an end-of-stream sentinel record, at which
point the decoder can switch to strict mode.
