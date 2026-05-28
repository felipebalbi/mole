# Quickstart

This chapter walks the shortest possible end-to-end path: install the
assembler, write a one-line program, look at what it produces, and
ship it to a Mole.

## Install the assembler

From the repository root:

```sh
cargo install --path mole-asm-cli
```

This builds and installs the `mole-asm` binary into `~/.cargo/bin`
(make sure that directory is on your `PATH`). If you only want to run
it without installing, `cargo run -p mole-asm-cli --` followed by the
usual arguments works just as well.

Verify the install:

```sh
mole-asm --version
```

## A one-line program

Create a file called `hello.moleasm` containing exactly:

```text
HALT status=0
```

That's it. The `HALT` opcode stops the engine and records a numeric
status code (here 0 = "completed normally") for the host to read
back.

## Assemble it

```sh
mole-asm hello.moleasm
```

You should see:

```text
wrote 1 words (2 bytes) -> hello.molecode
```

`hello.molecode` is two bytes: the little-endian encoding of the
single 16-bit `HALT` instruction (`00 00`).

To also produce a UART-ready frame, add `--frame`:

```sh
mole-asm hello.moleasm --frame
```

This writes `hello.mole.bin` (6 bytes: 2 length + 2 instruction +
2 CRC). That file is what the on-target loader expects to see byte
for byte.

## Ship it to a Mole

The host runtime that actually wraps the serial port is a future
chapter (and a future crate); for now the [`stty`/`cat`] approach
from the BRINGUP guide works:

```sh
stty -F /dev/ttyUSB1 1000000 cs8 -cstopb -parenb \
    crtscts -ixon -ixoff -ixany raw -echo
cat hello.mole.bin > /dev/ttyUSB1
```

`crtscts` enables the FT2232H RTS#/CTS# hardware flow control
Mole speaks (the engine deasserts CTS# while running so the host
stops sending), and `-ixon -ixoff -ixany` disables any software
flow control (Mole speaks none, and accidentally enabling it
turns arbitrary frame bytes into XON/XOFF and breaks the
link). See `fpga/Mole/BRINGUP.md` §3 for the full wiring
contract.

Mole accepts the frame, validates the CRC, copies the program into
SPRAM, and starts executing. With a single-`HALT` program, the
engine immediately halts with status 0 and the result ring is
empty. The bus does nothing visible --- but the round trip from
*source* to *frame* to *engine* is now in place.

Anything you build later (an actual I2C transfer, a fault-injection
recipe, a long-running conformance loop) follows this exact same
path. The rest of this book is about filling in the program in the
middle.

## Look at one of the bundled fixtures

For a more interesting first run, assemble one of the committed
fixtures:

```sh
mole-asm mole-asm/tests/fixtures/first-light.moleasm --frame
```

`first-light` is the canonical "are the pads alive?" program: it
sets up I2C at 100 kHz and toggles SDA/SCL in a tight loop. If you
have a scope on the right header pins you should see two clean
square waves at ~98 kHz.

You now have:

- the assembler installed,
- a feel for the input / output shape,
- one program you understand and one program you can play with.

The next chapter explains the machine the bytecode targets, so the
rest of the language makes sense.
