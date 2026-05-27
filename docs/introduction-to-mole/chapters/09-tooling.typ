#import "../lib.typ": *

// Tooling.

#section-slide("09", "Tooling")

#code-slide(
  "Compile",
  kicker-text: "mole-asm CLI",
)[
```
$ mole-asm program.moleasm
  -> program.mole.bin

$ mole-asm --listing program.moleasm
  -> program.mole.bin
  -> program.lst       (annotated)
```
]

#code-slide(
  "Simulate",
  kicker-text: "SpinalSim + Verilator",
)[
```
$ cd fpga/Mole
$ make sim-engine     # bit-cycle engine
$ make sim-uart       # UART block
$ make sim-spram      # result-ring controller
$ make sim            # everything
```
]

#content-slide(
  "Bring-up",
  kicker-text: "iCEBreaker, end to end",
)[
  #v(0.2em)
  #code-panel(size: 16pt)[
```
$ make bitstream    # yosys + nextpnr-ice40
$ make program      # iceprog
```
  ]
  #v(0.5em)
  #bullets(
    [Bring-up notes live in `fpga/Mole/BRINGUP.md`],
    [Pin maps, level-shifter notes, known-good versions],
  )
]
