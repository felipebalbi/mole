# Mole bring-up procedure (v0.2, iCEbreaker)

This doc walks through the first end-to-end smoke of a freshly
built Mole bitstream on an iCEbreaker board. It assumes a working
[`open-tool-forge`](https://github.com/open-tool-forge/fpga-toolchain)-style
toolchain (yosys, nextpnr-ice40, icestorm), `sbt`, and `cargo` on
PATH. macOS Homebrew and Linux oss-cad-suite are both
known-working host toolchains; the nextpnr GUI (`make gui`) is
Linux-only on oss-cad-suite (see the `gui` target's doc comment
in [`Makefile`](Makefile)).

For the wire format the host has to speak, see
[`WIRE_FORMAT.md`](WIRE_FORMAT.md). For the bit-level encoding
of every opcode, see
[`../../docs/MOLE-0.2-SPEC.md`](../../docs/MOLE-0.2-SPEC.md).
For the design rationale, see
[`../../ROADMAP.md`](../../ROADMAP.md) §"Mole Verde" and
[`TODO.md`](TODO.md).

For the **cross-machine hand-off procedure** (an agent picking up
hardware bring-up from a session that frozen the HDL), see
[`TODO.md`](TODO.md) §"Step 18 / Phase C.11" — it has the same
information as this file, restructured for an external agent
coming in cold.

---

## 1. Build the bitstream

```sh
cd fpga/Mole
make all          # produces gen/MoleTop.bin (Spinal -> yosys -> nextpnr -> icepack)
```

The chain elaborates `MoleTopVerilog` (12 MHz pad clock, PLL
multiplied to a **24 MHz** fabric clock, dual SPRAM-backed
program and result memory at 32-bit grain), synthesises with
`yosys -p synth_ice40`, places-and-routes with `nextpnr-ice40
--up5k --package sg48 --freq 24 --seed 3`, and packs the
bitstream with `icepack`. The `--freq 24` constraint is the
**real** fabric clock; nextpnr will fail the build if timing
doesn't close at 24 MHz on seed 3.

If a future change to the design makes seed 3 stop meeting
timing, try another seed via `make all SEED=<N>`; do **not**
raise the target frequency. The Verde target is 24 MHz; see
the C.X "Verde retarget" entry in [`TODO.md`](TODO.md) for the
X.1–X.5 plateau history that pinned that call.

Re-derive only the generated Verilog (e.g. to inspect a change)
via `make gen` --- it lands at `gen/MoleTop.v` and is gitignored.
Useful diagnostic targets: `make gui` (Qt place-and-route
viewer, Linux/oss-cad-suite) and `make report` (machine-readable
JSON timing report).

## 2. Flash the iCEbreaker

```sh
make flash        # iceprog gen/MoleTop.bin
```

`iceprog` talks to the FT2232H **channel B** of the on-board USB
bridge. The same FTDI chip's **channel A** is the host UART, and
the two channels can be used simultaneously --- you do **not**
need to power-cycle the board between flashing and talking to
the engine. On Linux the UART side of channel A typically shows
up as `/dev/ttyUSB0` (or `/dev/ttyUSB1` depending on the order
in which channel A and channel B claim ttyUSB indices); on macOS
look for `/dev/tty.usbserial-ibXXXXXX` (the exact suffix depends
on the device's chip-side serial number); on Windows look in
Device Manager for a "USB Serial Port (COMx)" that shares the
FT2232H's USB device with the JTAG side.

Expected post-flash behaviour: `iceprog` reports "VERIFY OK";
the **heartbeat LED** (big green LED on Mole Verde rev 1.x;
silkscreen LED_RGB1, FPGA pin 40 = `io_ledHeartbeat`) pulses at
~1 Hz on/off; the **running LED** (small green 0603; silkscreen
LEDG, FPGA pin 37 = `io_ledRunning`) is off (engine idle, in
`acceptLoad`); the **fault LED** (small red 0603; silkscreen
LEDR, FPGA pin 11 = `io_ledFault`) is off. The LED signals are
named in `icebreaker.pcf` by FUNCTION, not by physical color,
because the iCEbreaker silkscreen color labels disagree with the
actually populated LEDs on this board revision.

## 3. Talk to the engine

The supported host tool is `mole-loader-cli`. From the repo
root:

```sh
cargo build --release -p mole-asm-cli -p mole-loader-cli
# These produce target/release/mole-asm and target/release/mole-loader.
```

The CLIs set `crtscts` on the serial port builder automatically.
If you want to drive the link from a different host tool, the
UART must be opened at **1 000 000 baud, 8N1, RTS/CTS hardware
flow control** (active-low, FT2232H convention); see
[`WIRE_FORMAT.md`](WIRE_FORMAT.md) §3 for the full requirement
and the `stty` line for raw bring-up.

## 4. Three smoke programs

These exercise the three distinct observation channels: UART
round-trip, LEDs, and the SDA/SCL pads on the scope. All are
written in moleasm and assembled with `mole-asm` — no
hand-encoded bytes.

### 4.1 Short halt (UART round-trip proof)

Save as `/tmp/halt.moleasm`:

```moleasm
SET_BUS_MODE i2c
HALT         status=0
```

Assemble and send:

```sh
target/release/mole-asm assemble /tmp/halt.moleasm \
    -o /tmp/halt.molecode
target/release/mole-loader \
    --port /dev/ttyUSB0 \
    --frame /tmp/halt.molecode \
    --ring-bytes 8192
```

Expected: the result ring streams back almost immediately. The
loader-cli decodes a `HaltStatus { status: 0, mismatch: false,
overflow: false }`. The full 8192-byte ring contains
`Revision { major: 0, minor: 2, patch: 0 }` (or whatever the
build was tagged at) in the first 4 bytes; trailing garbage in
the middle; a clean HALT word in the last 4 bytes.

Validates: loader → engine → drainer → UART TX path with no bus
activity to scope. Sufficient to declare C.11.c (iCEbreaker
smoke) done.

### 4.2 Visible-LED run (long / infinite, running-LED proof)

Save as `/tmp/blinky.moleasm`:

```moleasm
SET_BUS_MODE i2c
loop:
    EMIT_BIT_IMM tx=dominant
    EMIT_BIT_IMM tx=hiz
    JMP loop
```

Assemble and send. Expected: the **running LED stays solid**
(`io_ledRunning := !(engineStarted && !halted)`, so it lights
the moment the engine starts executing and stays lit through
the infinite loop); the **heartbeat LED stops** (`io_ledHeartbeat`
is gated on `halted || !engineStarted`, so its counter pauses
the moment the engine starts); the **fault LED stays off**.
SDA toggles low/high at the configured bit rate forever.

Press the user button (`io_reset`, active-low) to recover: the
reset bridge re-runs the 2-FF chain, the phase FSM resets to
`acceptLoad`, the running LED goes off, and the heartbeat LED
resumes blinking. Validates the `io_ledRunning` routing and
that loops actually loop.

### 4.3 Bus toggle (oscilloscope proof)

Save as `/tmp/scope.moleasm`:

```moleasm
SET_BUS_MODE i2c
EMIT_BIT_IMM tx=dominant       ; SDA low for one bit
EMIT_BIT_IMM tx=hiz            ; SDA released
EMIT_QUARTER_IMM sda=hiz, scl=dominant   ; SCL pulled low
EMIT_QUARTER_IMM sda=hiz, scl=hiz        ; SCL released
HALT         status=0
```

Note: `EMIT_BIT_IMM` encodes SDA only; the engine generates SCL
automatically from the configured bit rate (canonical 4-quarter
shape, see [`AGENTS.md`](AGENTS.md) §"Quarter-bit is the timing
unit on the wire"). To explicitly drive SCL (e.g. for the smoke
program above) use `EMIT_QUARTER_IMM`, which encodes both SDA
and SCL symbols per ROADMAP §"When to use EMIT_QUARTER".

Expected on the scope (PMOD1A.1 = SCL, PMOD1A.2 = SDA):

- Quarter-bit-spaced edges paced by the active `LOAD_TIMING`
  divider (defaults to 6 fabric cycles per quarter at 24 MHz =
  **1 MHz bit rate**).
- SDA drops low for the first `EMIT_BIT_IMM`, releases for the
  second; then SCL toggles via the two `EMIT_QUARTER_IMM`s.
- All edges respect the external pull-up resistor's RC --- the
  rising edge is the pull-up's RC, the falling edge is sharper
  (NMOS pulls the line to GND directly).
- The bus settles to idle-high on `HALT` (both lines released);
  the result ring streams back over UART within the same
  millisecond.

Validates: the `SB_IO` open-drain wiring, the `MoleBus` /
`SymbolDecoder` path from `tx_symbol` to pad drivers, and the
external pull-ups against the I2C edge rates.

## 5. Troubleshooting

The status LEDs are named in `icebreaker.pcf` by FUNCTION
(`io_ledFault` / `io_ledRunning` / `io_ledHeartbeat`), not by
physical color, because the iCEbreaker silkscreen color labels
do not match the actually populated LEDs on Mole Verde rev 1.x.
On the bench you will see: a **small red 0603** for `io_ledFault`
(pin 11), a **small green 0603** for `io_ledRunning` (pin 37),
and a **big GREEN** LED for `io_ledHeartbeat` (pin 40, which the
iCEbreaker silkscreen labels `LED_RGB1` / "blue channel" but
physically lights green on this board rev).

| Symptom                                | Likely cause                                                                                                                                                                                                                                                                                                                                            |
|----------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Fault LED pulses, nothing drains         | Bad CRC, bad magic, wrong `body_len`, or a UART RX error mid-frame. The loader is in resync. Stop sending for **≥ 20 µs** (~20 UART bit times at 1 Mbaud) of idle-high on the line so the loader returns to `idleState`, then retry. See [`WIRE_FORMAT.md`](WIRE_FORMAT.md) §4 --- this is the host's contract.                                          |
| Running LED solid, nothing drains        | The program is in an infinite loop. Press the user button to reset; verify the program eventually hits a `HALT`.                                                                                                                                                                                                                                          |
| No LEDs change, no drain               | PLL never locked, or the bitstream did not flash. Power-cycle, re-flash via `make flash`, and check `dmesg` for FT2232H enumeration. The PLL-locked deassertion is what releases the fabric reset --- without it the engine sits in reset forever and `io_ledHeartbeat` never starts blinking.                                                            |
| Drain comes back but the HALT word looks wrong | Decode the HALT word via `mole-loader` (`HaltStatus`). Bit `[29]` set = **overflow** (record stream exceeded the ring; later records dropped). Bit `[28]` set = **mismatch** (sampled bit failed an `expect` compare somewhere). Bits `[27:23]` = 5-bit status code; `0x1F` is the engine's STATUS_TRAP (malformed instruction, reserved opcode, out-of-range BRANCH, etc.). See [`../../docs/MOLE-0.2-SPEC.md`](../../docs/MOLE-0.2-SPEC.md) §11.   |
| Bus edges look glitchy or droop slowly | Pull-up too weak (or missing). For I2C use 4.7 kΩ to 3.3 V; for I3C-OD windows use 1 kΩ to 1.8 V. PMOD1A doesn't have on-board pull-ups; you have to wire them externally. The engine drives PP-high only under `i3c-PP` / `hdr-ddr` modes; in I2C / I3C-OD modes the rising edge is RC-limited.                                                            |
| Frame sent but nothing drains back     | RTS#/CTS# is mis-wired or the host driver has `crtscts` disabled. The drainer halts whenever `io_uRts` reads HIGH (= RTS#-deasserted). With pin 19 internally pulled up, an unwired board reads HIGH and the drainer never sends. Verify the wiring (PMOD1A pins 18+19 → FT2232H channel A CTS#+RTS#) and re-run the `stty` line from [`WIRE_FORMAT.md`](WIRE_FORMAT.md) §3 (`crtscts -ixon -ixoff -ixany`).                                              |
| Host driver drops bytes mid-frame      | The host did **not** enable `crtscts` and ignored Mole's CTS# deassertion. Mole holds CTS# HIGH while a program is running or the result is draining; a host that doesn't honour it will pump bytes into the FT2232H's USB pipe that the FPGA loader will never accept (CTS# is checked at the FT, not at the FPGA UART RX). Use `mole-loader-cli` (sets the flag for you) or re-run the `stty` line.                                                      |
| Loader rejects program before serial port opens | `mole-loader-cli` runs the §16.4 pre-scan on the assembled body before opening the serial port. If a program contains a HALT word with reserved status `0x1D` or `0x1E`, or any other §16.4 violation, the CLI exits with code 4 ("program validation failed") and never touches the wire. Inspect the error message; this is the loader being strict on your behalf.   |

## 6. Next steps

Once smoke passes:

- **C.11.d (TMP108 regression):** the v0 fixture
  `mole-asm/tests/fixtures/tmp108.moleasm` needs a v0.2 re-port
  using `EMIT_BYTE_IMM` for compile-time-known bytes (I2C
  address, register pointer). See [`TODO.md`](TODO.md)
  §"C.11.a" and §"C.11.d".
- **C.11.e (MCXA268 I3C target soak):** the v0.2 acceptance
  gate. See [`TODO.md`](TODO.md) §"C.11.e".

The host-side `mole-asm` crate at
[`../../mole-asm/`](../../mole-asm/) is the v0.2 program
encoder; the `mole-loader` crate at
[`../../mole-loader/`](../../mole-loader/) is the v0.2 frame /
ring decoder. Until the Scheme SDK lands (Phase 0+, ROADMAP
§"Layer 1"), moleasm is the source language; both crates are
production-quality (393 passing tests at the latest tree
verification).
