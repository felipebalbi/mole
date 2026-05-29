#import "../lib.typ": *
#import "../figures/ring-layout.typ": ring-layout-figure

// Part 5: The loader. mole-loader is the runtime counterpart to
// mole-asm: it ships the frame, waits for HALT, drains the result
// ring, and decodes it. Audience leaves knowing the wire contract,
// the round-trip shape, and the three record types they will see.

#section-slide("05", "The loader")

#content-slide(
  "What `mole-loader` does",
  kicker-text: "The runtime counterpart to mole-asm",
)[
  #numbered(
    [Verifies the `.mole.bin` frame locally (length + CRC).],
    [Opens the serial port -- 1 Mbaud, 8N1, mandatory hardware RTS/CTS.],
    [Sends the program; the engine pulls bytes via CTS.],
    [Waits for `HALT`; drains exactly `resultRingByteCount` bytes back.],
    [Decodes the ring into `REVISION` + records + `HALT`.],
    [Exits non-zero on mismatch, ring overflow, or engine trap.],
  )
  #note[
    Six steps. Each one is independently verifiable -- step 1 works
    with no hardware (`--dry-run`), step 5 works on a captured
    `--dump-ring` blob. The loader is just glue between two pure
    functions and a serial port.
  ]
]

#code-slide(
  "The basic invocation",
  kicker-text: "mole-loader CLI",
)[
```
$ mole-loader --dry-run program.mole.bin           ; verify frame locally
$ mole-loader --port /dev/ttyUSB0 program.mole.bin ; round-trip to a board
$ mole-loader --port /dev/ttyUSB0 \
              --expect-revision 1.0.0 \
              --dump-ring evidence.bin \
              program.mole.bin
```
]

#content-slide(
  "Why mandatory hardware flow control",
  kicker-text: "The wire contract is uncompromising",
)[
  #stack(
    spacing: 1em,
    bullets(
      [The engine FSM has no recovery path for a dropped byte.],
      [Software flow control would turn frame bytes into XON / XOFF.],
      [No flow control at all = silent corruption. The loader refuses to fall back.],
    ),
    align(center, text(
      font: font-serif, size: 18pt, style: "italic", fill: accent,
    )[A hard error beats a silent failure mode, every time.]),
  )
  #note[
    This is the kind of decision that earns its keep the first time
    a flaky USB cable convinces the team they have an engine bug.
    The loader hard-errors at port-open time, not three days later.
  ]
]

#definition-slide(
  "Result ring",
  sub: [What the engine writes back. Three record types plus a header.],
)[
  #stack(
    spacing: 1em,
    align(center, box(width: 96%, ring-layout-figure)),
    bullets(
      [*REVISION* at the base -- identifies the engine bitstream.],
      [*CAPTURE* per `capture=1` -- one sampled SDA bit each.],
      [*MARK* per `MARK` opcode -- host-visible breadcrumb + timestamp.],
      [*HALT* terminates the stream and carries status + flags.],
    ),
  )
]

#content-slide(
  "What the host shows you",
  kicker-text: "Decoded ring, fresh from a clean run",
)[
  #code-panel(size: 14pt)[
```
loaded 38 bytes from program.mole.bin
verified frame: 16 program words, CRC matches
opening /dev/ttyUSB0 at 1000000 baud (8N1, hardware RTS/CTS)
load  [================================] 38/38   done
drain [================================] 8192/8192 done
revision: 1.0.0
records:  3 total (2 CAPTURE, 1 MARK)
  [   0] CAPTURE sda=0
  [   1] CAPTURE sda=0
  [   2] MARK    label=0x01 ts=8420 cycles
halt:     status=0x0 mismatch=false overflow=false
```
  ]
]

#recap-slide(
  "What Part 5 leaves you with",
  (
    [`mole-loader` verifies, ships, waits, drains, decodes -- in that order.],
    [The wire is 1 Mbaud, 8N1, hardware RTS/CTS. No fallbacks, by design.],
    [Three record types plus a HALT word; exit code follows the engine flags.],
  ),
  next: [an end-to-end worked example -- one byte to a real EEPROM.],
  deeper: [`mole-loader/README.md` -- crate-level docs and the wire contract.],
)
