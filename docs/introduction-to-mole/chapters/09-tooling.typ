#import "../lib.typ": *

// Tooling.

#section-slide("Tooling")

#slide[
  = The CLI today
  ```
  $ mole-asm --help
  Compile moleasm source to Mole bytecode

  Usage: mole-asm [OPTIONS] <SOURCE>

  Options:
    -o, --output <PATH>     Output file (default: <source>.mole.bin)
        --listing           Emit a human-readable listing alongside
        --format <FORMAT>   bin (default) | hex | annotated
        --check             Parse and check only; do not write
    -h, --help              Print help
    -V, --version           Print version
  ```

  Rust workspace at the repo root. `cargo install --path
  mole-asm-cli` and you're done.
]

#slide[
  = Simulation
  Every block has its own SpinalSim testbench under
  `fpga/Mole/src/sim/`.

  ```
  cd fpga/Mole
  make sim-engine         # bit-cycle engine, all controller tests
  make sim-uart           # UART block (RTS/CTS, parity, etc.)
  make sim-spram          # SPRAM result-ring controller
  make sim                # everything
  ```

  Each test prints `OK` or fails loudly with a defmt-style
  message and a VCD path. CI runs the full suite on every
  PR (Phase-0+).
]

#slide[
  = Bring-up
  ```
  cd fpga/Mole
  make bitstream-verde     # iCE40 UP5K via yosys + nextpnr-ice40
  make program-verde       # iceprog / openFPGALoader
  ```

  Bring-up notes per board live in `fpga/Mole/BRINGUP.md`,
  including pin maps, level-shifter requirements (Verde is
  3.3V; many DUTs are 1.8V), and known-good toolchain
  versions.
]
