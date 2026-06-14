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
mole-asm assemble hello.moleasm
```

You should see:

```text
wrote 1 body words (12 bytes) -> hello.molecode
```

`hello.molecode` is 12 bytes: the 8-byte preamble (MAGIC + LEN)
followed by the single 32-bit `HALT status=0` instruction
(`0x40000000`, on the wire little-endian as `00 00 00 40`). The
CRC trailer is only present in the framed `.mole.bin`.

To also produce a UART-ready frame, add `--frame`:

```sh
mole-asm assemble hello.moleasm --frame
```

This writes `hello.mole.bin` (14 bytes: 4 MAGIC + 4 LEN + 4
instruction + 2 CRC). That file is what the on-target loader
expects to see byte for byte.

## Ship it to a Mole

The host loader that wraps the serial port lives in
`mole-loader-cli`:

```sh
cargo install --path mole-loader-cli
mole-loader -p /dev/ttyUSB1 hello.mole.bin
```

The loader configures the port (8N1, hardware RTS/CTS),
streams the frame, waits for the engine to HALT, drains the
result ring, and prints decoded records. For a single-`HALT`
program the ring contains exactly two records: a REVISION word
at slot 0 and a HALT word at the tail, so the loader prints:

```text
revision: 0.1.1
records:  0 total (0 CAPTURE, 0 MARK)
halt:     status=0x0 mismatch=false overflow=false
```

The bus does nothing visible — but the round trip from *source*
to *frame* to *engine* to *result ring* is now in place.

Anything you build later (an actual I2C transfer, a
fault-injection recipe, a long-running conformance loop)
follows this exact same path. The rest of this book is about
filling in the program in the middle.

## Look at one of the bundled fixtures

For a more interesting first run, assemble one of the committed
fixtures:

```sh
mole-asm assemble mole-asm/tests/fixtures/first-light.moleasm --frame
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
