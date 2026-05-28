# Mole bring-up procedure (v0, iCEbreaker)

This doc walks through the first end-to-end smoke of a freshly
built Mole bitstream on an iCEbreaker board. It assumes a working
[`open-tool-forge`](https://github.com/open-tool-forge/fpga-toolchain)-style
toolchain (yosys, nextpnr-ice40, icestorm) and `sbt` on PATH.

For the wire format the host has to speak, see
[`WIRE_FORMAT.md`](WIRE_FORMAT.md). For the design rationale, see
[`../../ROADMAP.md`](../../ROADMAP.md) §"Mole Verde" and
[`TODO.md`](TODO.md).

---

## 1. Build the bitstream

```sh
cd fpga/Mole
make all          # produces gen/MoleTop.bin (Spinal -> yosys -> nextpnr -> icepack)
```

The chain elaborates `MoleTopVerilog` (12 MHz pad clock, PLL
multiplied to a 24 MHz fabric clock, SPRAM-backed program and
result memory), synthesises with `yosys -p synth_ice40`,
places-and-routes with `nextpnr-ice40 --up5k --package sg48 --freq
24`, and packs the bitstream with `icepack`. The `--freq 24`
constraint is the **real** fabric clock; nextpnr will fail the
build if timing doesn't close at 24 MHz.

Re-derive only the generated Verilog (e.g. to inspect a change)
via `make gen` --- it lands at `gen/MoleTop.v` and is gitignored.

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
in which channel A and channel B claim ttyUSB indices); on
Windows look in Device Manager for a "USB Serial Port (COMx)"
that shares the FT2232H's USB device with the JTAG side.

## 3. Talk to the engine

Open the UART at **1 000 000 baud, 8N1, RTS/CTS hardware flow
control** (active-low, FT2232H convention) and send a frame in the
format from [`WIRE_FORMAT.md`](WIRE_FORMAT.md). The engine
auto-runs on a CRC-valid frame and streams the result ring back.
Total round-trip:

```
frame_size  = 4 + 2 * len     bytes   (host -> engine)
drain_size  = resultRingByteCount     bytes   (engine -> host)
```

With default `MoleConfig`, `resultRingByteCount = 8192` --- every
halt drains 8 192 bytes regardless of how many records the engine
actually wrote, since the wire format does not signal
end-of-record. The host decodes records by their high-2-bit tag
until it hits the HALT word at the last two bytes of the drain.

A working `stty` line on Linux:

```sh
stty -F /dev/ttyUSB0 1000000 cs8 -cstopb -parenb \
    crtscts -ixon -ixoff -ixany raw
```

`crtscts` enables the HW flow control; `-ixon -ixoff -ixany`
explicitly disables any software (XON/XOFF) flow control --- Mole
does not speak it and accidentally enabling it on the host turns
arbitrary frame bytes into XON/XOFF and breaks the link.

The pinout, named from the **FT2232H's** perspective (active-low):

- **PMOD1A pin 19 / FT2232H channel A `RTS#`** -> Mole's
  `io_uRts` input. The FT asserts RTS# (line LOW) when its USB
  pipe has room; Mole's drainer pushes bytes only while this
  line is LOW. Internal pull-up enabled on the FPGA pin, so an
  unwired board reads HIGH = RTS#-deasserted = drainer halted
  (a visible failure mode, not silent corruption).
- **PMOD1A pin 18 / FT2232H channel A `CTS#`** -> Mole's
  `io_uCts` output. Mole asserts CTS# (line LOW) only while the
  top-level phase FSM is in `acceptLoadState` (no program
  running, no drain in progress). A host with `crtscts`
  enabled holds its TX off whenever CTS# is HIGH, which
  enforces the spec invariant *"while program is not HALTED,
  don't accept data"*.

## 4. Three smoke programs

These are hand-encoded (not shipped as `.mole.bin`; see AGENTS
§3.4 / §4 --- we don't commit binary build artefacts) and cover
the three distinct observation channels: UART round-trip, LEDs,
and the SDA/SCL pads on the scope.

### 4.1 Short halt (UART round-trip proof)

```
SET_BUS_MODE i2c
HALT         0
```

Hex (little-endian on the wire, including the framing):

```
02 00                      ; len = 2
00 38                      ; SET_BUS_MODE i2c   (opcode 0x7, i2c.position=0, encoded as (7<<11)|(0<<8) = 0x3800 -> 00 38)
00 00                      ; HALT 0             (opcode 0x0, status=0, encoded as 0x0000 -> 00 00)
<crc lo> <crc hi>          ; CRC-16/XMODEM over the 6 payload bytes
```

Expected: the result ring streams back almost immediately. The
first **four bytes** are the Revision (low word first); the
**last two bytes** are the HALT word (`0xC000` for a clean
status-0 halt: `[15:14]=11`, no overflow, no mismatch, status=0,
reserved=0). The middle bytes are whatever the SPRAM previously
held --- ignore them on a short program.

Validates: loader -> engine -> drainer -> UART TX path with no
bus activity to scope.

### 4.2 Visible-LED run (long / infinite, green LED proof)

```
SET_BUS_MODE i2c
MARK         0       ; loop label
EMIT_BIT     sda=dom
EMIT_BIT     sda=hiz
JMP          mark0
```

Expected: the **green LED stays solid** (`!engine.done` is the
green-LED drive); the **blue heartbeat stops** (blue is gated on
`engine.done`); the red LED stays off. SDA toggles low/high at
the configured bit rate forever.

Press the user button (`io_reset`, active-low) to recover: the
reset bridge re-runs the 2-FF chain, the phase FSM resets to
`acceptLoad`, and the green LED goes back off. Validates the
`!engine.done` LED routing and that loops actually loop.

### 4.3 Bus toggle (oscilloscope proof)

```
SET_BUS_MODE i2c
EMIT_BIT     sda=dom
EMIT_BIT     sda=hiz
EMIT_BIT     scl=dom    ; encoded via EMIT_QUARTER with sda=hiz, scl=dom
EMIT_BIT     scl=hiz    ; encoded via EMIT_QUARTER with sda=hiz, scl=hiz
HALT         0
```

Note: `EMIT_BIT` encodes SDA only; the engine generates SCL
automatically from the configured bit rate. To explicitly drive
SCL (e.g. for the smoke program above) use `EMIT_QUARTER`, which
encodes both SDA and SCL symbols per ROADMAP §"When to use
EMIT_QUARTER".

Expected on the scope (PMOD1A.1 = SCL, PMOD1A.2 = SDA):

- Quarter-bit-spaced edges paced by the `quarterPeriodCyclesReset`
  divider in `MoleConfig` --- default `6` cycles per quarter at
  24 MHz fabric = **1 MHz bit rate**.
- SDA drops low for the first `EMIT_BIT`, releases for the
  second; then SCL toggles via the two `EMIT_QUARTER`s.
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

| Symptom                                | Likely cause                                                                                                                                                                                                                                                                                                                                                                                              |
|----------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Red LED pulses, nothing drains         | Bad CRC, wrong `len`, or a UART RX error mid-frame. The loader is in resync. Stop sending for **>= 20 UART bit times** (~20 us at 1 Mbaud) of idle-high on the line so the loader returns to `idleState`, then retry. See [`WIRE_FORMAT.md`](WIRE_FORMAT.md) §"Resync rule" --- this is the host's contract.                                                                                              |
| Green LED solid, nothing drains        | The program is in an infinite loop. Press the user button to reset; verify the program eventually hits a `HALT`.                                                                                                                                                                                                                                                                                          |
| No LEDs change, no drain               | PLL never locked, or the bitstream did not flash. Power-cycle, re-flash via `make flash`, and check `dmesg` for FT2232H enumeration. The PLL-locked deassertion is what releases the fabric reset --- without it the engine sits in reset forever and `io_ledG` stays low.                                                                                                                                |
| Drain comes back but the HALT word looks wrong | Read the last two bytes (low byte first) of the drain. Bit `[13]` set in the assembled 16-bit word means **overflow**: the engine tried to write more records than the result ring could hold. Bit `[12]` set means **MISMATCH_FLAG was high at HALT entry** (a sampled bit failed an `expect` compare). Bits `[11:8]` are the program-provided status code; `0xF` is the engine's reserved-opcode trap. |
| Bus edges look glitchy or droop slowly | Pull-up too weak (or missing). For I2C use 4.7 kohm to 3.3 V; for I3C-OD windows use 1 kohm. PMOD1A doesn't have on-board pull-ups; you have to wire them externally. The engine drives PP-high only under `i3c-PP` / `hdr-ddr` modes; in I2C / I3C-OD modes the rising edge is RC-limited.                                                                                                               |
| Frame sent but nothing drains back     | RTS#/CTS# is mis-wired or the host driver has `crtscts` disabled. The drainer halts whenever `io_uRts` reads HIGH (= RTS#-deasserted). With pin 19 internally pulled up, an unwired board reads HIGH and the drainer never sends. Verify the wiring (PMOD1A pins 18+19 -> FT2232H channel A CTS#+RTS#) and re-run the `stty` line from §3 (`crtscts -ixon -ixoff -ixany`).                                |
| Host driver drops bytes mid-frame      | The host did **not** enable `crtscts` and ignored Mole's CTS# deassertion. Mole holds CTS# HIGH while a program is running or the result is draining; a host that doesn't honour it will pump bytes into the FT2232H's USB pipe that the FPGA loader will never accept (CTS# is checked at the FT, not at the FPGA UART RX). Re-run the `stty` line from §3.                                              |

## 6. Next steps

Once smoke passes, the host-side `mole-asm` crate (already
landed at the repo root) is what produces the binary frame
described in `WIRE_FORMAT.md`. Until you switch over to it, any
host language that can talk to a serial port and compute
CRC-16/XMODEM works (Python with `pyserial` + `crcmod`, C with
`tio`, etc.).
