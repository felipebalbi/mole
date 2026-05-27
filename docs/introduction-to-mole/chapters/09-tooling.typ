#import "../lib.typ": *

// Tooling -- matches mole-asm-cli/src/main.rs (input, -o/--output,
// --frame, --frame-output). No invented flags.

#section-slide("09", "Tooling")

#code-slide(
  "Compile",
  kicker-text: "mole-asm CLI",
)[
```
$ mole-asm program.moleasm
  -> program.molecode               ; packed 16-bit words

$ mole-asm --frame program.moleasm
  -> program.molecode
  -> program.mole.bin               ; length + words + CRC, UART-ready
```
]

#code-slide(
  "Simulate",
  kicker-text: "SpinalSim + Verilator",
)[
```
$ cd fpga/Mole
$ make sim-engine     ; bit-cycle engine
$ make sim-uart       ; UART block
$ make sim-spram      ; result-ring controller
$ make sim            ; everything
```
]

#content-slide(
  "Bring-up",
  kicker-text: "iCEBreaker, end to end",
)[
  #v(0.2em)
  #code-panel(size: 16pt)[
```
$ make             ; yosys + nextpnr-ice40
$ make flash       ; iceprog
```
  ]
  #v(0.5em)
  #bullets(
    [Bring-up notes live in `fpga/Mole/BRINGUP.md`],
    [Pin maps, level-shifter notes, known-good versions],
  )
]
