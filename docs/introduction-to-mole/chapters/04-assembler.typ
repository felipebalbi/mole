#import "../lib.typ": *

// Part 4: The assembler. moleasm as a human-readable surface for the
// ISA, and the `mole-asm` CLI that compiles it. Audience leaves
// knowing what they type, what comes out, and which artifact maps
// to which downstream consumer.

#section-slide("04", "The assembler")

#content-slide(
  "moleasm: the human face",
  kicker-text: "One line, one 16-bit instruction",
)[
  #stack(
    spacing: 1em,
    code-panel(size: 18pt)[
```
; Release SDA, expect the target to pull it low. Capture what we see.
EMIT_BIT  tx=hiz expect=0 mask=1 capture=1
```
    ],
    bullets(
      [`tx=dominant|recessive|hiz` -- short forms `dom` / `rec` also accepted.],
      [`expect=` / `mask=` / `capture=` on bearer opcodes; defaults are omitted.],
      [Branch targets are #emph[labels], never raw offsets.],
    ),
  )
]

#content-slide(
  "Two directives. Everything else is an opcode.",
  kicker-text: "moleasm in one slide",
)[
  #stack(
    spacing: 1em,
    code-panel(size: 16pt)[
```
.equ slow_div, 60          ; ~100 kHz at 24 MHz fabric
.dw  0xC000                ; raw word -- escape hatch

        LOAD_TIMING   i2c_freq, slow_div
        SET_BUS_MODE  i2c

retry:  EMIT_BIT      tx=hiz expect=0 mask=1 capture=1
        BRANCH_ON     MISMATCH, retry
```
    ],
    align(center, text(
      font: font-serif, size: 16pt, style: "italic", fill: muted,
    )[
      Bus mode and timing are opcodes -- they live on the wire,
      not in the toolchain.
    ]),
  )
]

#code-slide(
  "The pipeline",
  kicker-text: "mole-asm CLI",
)[
```
$ mole-asm program.moleasm
  -> program.molecode               ; packed little-endian 16-bit words

$ mole-asm --frame program.moleasm
  -> program.molecode
  -> program.mole.bin               ; length + words + CRC-16/XMODEM
```
]

#content-slide(
  "Two artifacts, two consumers",
  kicker-text: "Pick by where you're going next",
)[
  #stack(
    spacing: 1.2em,
    two-col(
      stack(
        spacing: 0.5em,
        tag(".molecode", color: secondary),
        text(size: 16pt, fill: ink-soft)[
          Packed bytecode. The naked instruction stream.
        ],
        text(size: 14pt, fill: muted, style: "italic")[
          For simulators, disassemblers, archival.
        ],
      ),
      stack(
        spacing: 0.5em,
        tag(".mole.bin", color: accent),
        text(size: 16pt, fill: ink-soft)[
          UART-ready frame: length + words + CRC.
        ],
        text(size: 14pt, fill: muted, style: "italic")[
          For the loader and the engine on the wire.
        ],
      ),
    ),
    align(center, callout(kind: "info")[
      `--frame` produces both. Diff `.molecode` against a golden;
      ship `.mole.bin` to a board.
    ]),
  )
]

#try-it-slide(
  [Why does the assembler emit two #emph[separate] artifacts instead
  of one combined file?],
  hint: [Think about what changes between a clean build and a
  CRC-corrupted UART round-trip.],
)

#content-slide(
  "Because the frame is a wire contract",
  kicker-text: "Answer",
)[
  #stack(
    spacing: 1em,
    bullets(
      [`.molecode` is what the program #emph[is]. Reproducible byte for byte from source.],
      [`.mole.bin` is what crossed the UART. Length + CRC let the engine reject a corrupt copy.],
      [Diffing two `.molecode` files is meaningful; diffing two `.mole.bin` files isn't (CRC noise).],
    ),
    align(center, text(
      font: font-serif, size: 16pt, style: "italic", fill: muted,
    )[Same separation as `.o` vs `.elf`. Different jobs.]),
  )
]

#recap-slide(
  "What Part 4 leaves you with",
  (
    [moleasm = one opcode per line, two directives, named labels for branches.],
    [`mole-asm` is a small batch transformer -- read source, write bytecode.],
    [Two artifacts: `.molecode` for the toolchain, `.mole.bin` for the wire.],
  ),
  next: [the loader -- how `.mole.bin` reaches the engine and what comes back.],
  deeper: [`book/src/{syntax,opcodes,quickstart}.md` -- the full language.],
)
