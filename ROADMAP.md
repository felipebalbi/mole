# Mole --- I3C / I2C compliance test rig --- feasibility & plan

**Project**: Mole (product line). SKUs: **Mole Verde** (pocket / per-dev
dongle), **Mole Rojo** (bench / compliance), **Mole Negro** (future
certification tier).

## Problem

Build a MIPI I3C / I2C compliance and conformance test rig, work-alike
to the MCCI Model 2710 SuperMITT, scoped to SDR + HDR-DDR (no
HDR-Ternary, so no analog I3C PHY needed). The rig must drive any
legal *and* illegal SDR / HDR-DDR / I2C sequences with deterministic
timing; observe the bus with quarter-bit resolution; inject errors
exactly when (and only when) the test author requested; exercise
CCC compliance and DAA edge cases; act as either controller *or*
target; emulate well-known I2C / I3C peripheral devices; and present
a scriptable surface to the developer.

## Verdict (TL;DR)

Feasible, architecturally clean, and the first prototype can be
built entirely on hardware you already own (1bitsquared icebreaker
+ a 3.3V DUT + two pull-up resistors + the FT2232H UART that's
already on the icebreaker).

The architecture is a **bit-stream + Scheme SDK** design:

- **FPGA hosts a tiny, protocol-agnostic bit-cycle engine** ("Layer 0"):
  ~10 opcodes, ~1800 LUTs, no knowledge of I2C / I3C, just drives
  quarter-bit patterns on SDA/SCL and compares them against expects.
- **Host hosts a Scheme SDK** ("Layer 1"): all of I2C and I3C lives
  here as Scheme source --- spec-compliant primitives (`sdr/write-byte`,
  `ccc/setdasa`, `i2c/start`) layered on top of raw bit-level
  primitives (`drive-bit`, `drive-quarter`). The SDK is the living,
  diffable encoding of the spec.
- **DSL is a Lisp/Scheme dialect** compiling to Layer-0 bytecode.

Five consequences worth naming explicitly:

1. **Same engine drives both I2C and I3C.** At the wire layer the buses
   are identical; the difference is which Scheme namespace your test
   imports.
2. **Same engine plays both controller and target.** Role is a config
   bit; the SDK exposes `i3c/controller/*` and `i3c/target/*`
   namespaces over a shared set of primitives.
3. **Error injection is exact-by-construction.** All PRNG lives in the
   host compiler, never in the engine. `error_ratio = 0` produces a
   bytecode with zero errors written into it; reproducibility is
   `seed + ratio`.
4. **Peripheral emulation is a first-class SDK deliverable.** Same
   target-role machinery lets us ship Scheme libraries that emulate
   TMP108, INA4230, accelerometers, fuel gauges, etc. at the wire
   level, so developers can validate firmware against a virtual
   sensor catalog without owning the parts.
5. **Two hardware tiers from one design**: pocket (iCE40 UP5K,
   SDR/I2C) and bench (ECP5-45K, adds HDR-DDR + deep capture).
   Same Scheme SDK, same bytecode format, same firmware story.

## Architecture --- v0 (icebreaker only, no Pico)

```
   Host (your laptop)
   +-------------------------------------------------+
   | Scheme compiler                                 |
   |   test.lisp -> bytecode.bin                     |
   | Result post-processor                           |
   |   results.bin -> human/machine report           |
   +-------------------+-----------------------------+
                       | USB (FT2232H, two channels)
                       |   ttyUSB0: UART  (program + results)
                       |   ttyUSB1: bitstream programming
                       v
   icebreaker (iCE40 UP5K-SG48)
   +-------------------------------------------------+
   | UART RX/TX (115k--3M baud, FT2232H)             |
   |    |                                            |
   |    v                                            |
   | SPRAM (128 KB) -- program + result ring         |
   |    |                                            |
   |    v                                            |
   | Bit-cycle engine (Layer 0)                      |
   |   - quarter-bit FSM                             |
   |   - 10-opcode decoder                           |
   |   - expect comparator                           |
   |   - capture path                                |
   |   - timing dividers (pp/od/i2c-freq)            |
   +--------+-----------------+----------------------+
            |                 |
            v                 v
       PMOD header        PMOD header (optional probe / scope)
            |
       2x pull-up resistors (jumpered values)
            |
            v
       DUT @ 3.3V (e.g., MCXA dev board running embassy-mcxa)
```

No Pico, no external flash chip, no PHY daughter card, no custom
PCB. Just the icebreaker, two resistors, and the DUT.

## Architecture --- v1+ (Pico polish layer)

```
        Host (USB)
          |
          v
  RP2350 (Rust + Embassy)
   - postcard-rpc transport
   - USB CDC + bulk
   - Bytecode upload, result fetch
   - Firmware upgrade / bitstream loading
          |
          v  SPI / QSPI (PMOD)
  icebreaker (unchanged)
```

The bit engine, SDK, and bytecode are unchanged. The Pico replaces
the FT2232H UART path with a structured RPC transport, enabling:

- USB CDC for the simple developer flow.
- USB bulk endpoint for high-throughput result fetch.
- Eventual postcard-rpc commands for richer host tooling.
- Field deployment without an FT2232H attached.

## Architecture --- v2 (bench tier, ECP5-45K)

Same engine, larger fabric → HDR-DDR support + deep capture +
optional PHY daughter card with programmable VIO. Same Scheme SDK,
same bytecode format. Bytecode programs that don't use HDR-DDR
opcodes run unchanged on either tier; the compiler statically
rejects programs that overrun the pocket tier's timing budget.

## Layer 0 --- the bit-cycle engine

### Design principle

The engine knows nothing about I2C or I3C. It is a wire-level
choreographer:

- Drives SDA / SCL at quarter-bit resolution.
- Compares sampled wire values against expected patterns + masks.
- Records samples to a result ring when the program says so.
- Has minimal control flow so a program can react to mismatches and
  loop.

Every protocol-aware shortcut we resist baking into the engine is a
place where "fix a spec bug" becomes "edit Scheme" instead of
"respin the bitstream". This is the architectural win.

### ISA (v0 --- 10 opcodes, 16-bit encoding)

Wire engine:

- `EMIT_BIT drive expect capture_en` --- one full bit (4 quarters at
  canonical positions). `drive` = SDA+SCL drive value + drive-enable;
  `expect` = compare value + mask; `capture_en` = also write sampled
  SDA to result ring.
- `EMIT_QUARTER drive expect capture_en` --- single quarter
  (glitches, sub-bit shaping).
- `STRETCH_SCL n` --- hold SCL low for `n` quarters (target-style
  stretching or measured bus-hold).
- `WAIT_SCL_RELEASE timeout` --- async escape: pause the quarter
  clock until SCL goes high externally, or timeout fires.
- `WAIT_SDA_LOW timeout` --- needed for IBI / Hot-Join (target
  signals by pulling SDA between Stop and next Start).

Control flow:

- `JMP addr`
- `BRANCH_ON_MISMATCH addr` --- conditional on the last `EMIT_*`'s
  expect result.
- `HALT status` --- end of program, status code returned to host.

Bookkeeping:

- `MARK label_id` --- insert labeled marker in result ring (also
  carries an implicit timestamp).
- `LOAD_TIMING reg word` --- load divider words for `pp-freq`,
  `od-freq`, `i2c-freq`. Programmable per-test.

Total: 10 opcodes. Comfortable headroom in a 16-bit encoding.

### Reserved for v0.5 (do not implement yet)

- `LOAD_REG reg, value`, `BRANCH_ON_CAPTURED_MASK reg, mask, addr`
  --- needed for runtime reactions (DAA arbitration where we react
  to PID bits as they come in). Defer until pure capture +
  host-post-process proves insufficient.
- `CAPTURE_RUN n into addr` --- pure listening for `n` quarters,
  no drive, no expect. Defer until loop-based capture shows
  measurable timing jitter.
- `CALL / RET` --- defer; inline SDK macros at compile time.

### Why this fits in iCE40 UP5K (5280 LUTs)

| Block | LUT estimate |
|---|---|
| Quarter-bit FSM + timing dividers | ~250 |
| 10-opcode decoder + dispatch | ~150 |
| PC + JMP / branch logic | ~120 |
| Expect comparator + capture path | ~250 |
| Result ring controller | ~250 |
| UART RX/TX + framing | ~300 |
| SPRAM interface | ~200 |
| Misc (timestamps, reset, status LEDs) | ~280 |
| **Total** | **~1800** |

Leaves ~3400 LUTs free for headroom, second engine instance, or
debug instrumentation. SPRAM (1 Mbit = 128 KB) holds the bytecode
program + result ring with no external flash. BRAM (120 Kbit ~ 15 KB)
becomes a small scratch / prefetch buffer if needed.

### Clocks (v0 / icebreaker)

- 12 MHz crystal (on icebreaker) → PLL → ~48 MHz fabric.
- Quarter-bit granularity: ~21 ns per quarter.
- Max I3C bit rate: ~48 MHz / 4 = 12 MHz wire (matches I3C SDR spec
  max of 12.5 MHz with a tiny margin).
- Max I2C bit rate: way above any I2C mode.
- `pp-freq`, `od-freq`, `i2c-freq` are runtime divider words
  loaded via `LOAD_TIMING` --- one PLL, multiple bus speeds.

### Clocks (v2 / ECP5-45K bench tier)

- 24 MHz crystal → PLL → 100 MHz fabric.
- Quarter-bit granularity: 10 ns.
- Max I3C bit rate: 25 MHz (covers HDR-DDR at the upper end of the
  practical range; the rig cap of 25 MHz is also a design choice
  that bounds QSPI / capture bandwidth cleanly).

## Bandwidth math

At 25 MHz wire rate:

- Hot path (well-formed traffic): one `EMIT_BIT` per bit = 25 MHz ×
  ~4 bytes/opcode = **100 MB/s instruction rate**. Fits BRAM trivially
  for short tests; fits Quad-SPI-DDR @ 100 MHz for long streamed tests.
- Glitch path (quarter-bit): 100 MHz × ~2 bytes = 200 MB/s, only used
  for short glitched segments, so the average stays low.

At v0 / icebreaker (12 MHz I3C cap, no QSPI):

- ~50 MB/s instruction rate worst case. SPRAM bandwidth (single port,
  128-bit wide internally at fabric clock) is ample.

## Layer 1 --- the Scheme SDK

### Namespace-based compliance guarantee

The SDK is split into two enforcement tiers by namespace:

- **`i2c/`, `i3c/`, `ccc/`, `hdr-ddr/`** --- guaranteed spec-compliant
  primitives. Always produce correct bit patterns, correct T-bit /
  parity, correct CRC, correct timing within the loaded freq config.
- **`raw/`** --- escape hatch for error injection. `raw/drive-bit`,
  `raw/drive-quarter`, `raw/emit-glitched-bit`, etc. Tests using
  raw primitives must declare `(use-raw-primitives)` at the top of
  the source file; the compiler refuses otherwise. This makes
  "is this test injecting errors?" answerable by `grep`.

### Layering

```scheme
;; Layer 1b (compliant, always spec-correct)
(define (sdr/write-bit value)
  (raw/drive-bit #b0011                       ; SCL: low,low,high,high
                 (if (= value 1) #b1111 #b0000)
                 #b0000))                     ; no expectation

(define (sdr/write-byte b)
  (for-each sdr/write-bit (byte->msb-bits b))
  (sdr/write-t-bit-parity b))                 ; always correct T-bit

(define (sdr/read-bit)
  (raw/drive-bit #b0011                       ; controller clocks SCL
                 #b0000                       ; SDA released
                 #b0000                       ; no expectation
                 #:capture #t))               ; sample, write to ring

(define (sdr/read-byte)
  (let loop ((i 0))
    (when (< i 8) (sdr/read-bit) (loop (+ i 1)))))

;; Layer 1a (raw, opt-in escape hatch)
(define (sdr/write-byte/bad-parity b)
  (for-each sdr/write-bit (byte->msb-bits b))
  (sdr/write-bit (bit-not (parity b))))       ; intentionally wrong
```

### Expectations as first-class

Every observable bus event is expressible as an expectation:

```scheme
(expect-ack)                       ; fail if last T/ACK slot wasn't ACK
(expect-rx-byte #x5C)              ; fail if read byte != 0x5C
(expect-rx-byte/mask #x80 #x80)    ; fail if high bit not set
(expect-ibi-from #x3A (timeout 1ms))
(capture-rx-byte into 'pid-lo)     ; sample + tag in result ring for host
```

Failure policy is per-test:

- `strict` --- first mismatch halts and reports `(pc, expected, actual,
  timestamp)`.
- `collect` --- continue, log every mismatch as a result event, report
  count at end.

### Read primitives

`EMIT_BIT` has a `capture_enable` bit. A read is `EMIT_BIT
drive_en=0 expect_mask=0 capture_en=1`. The sampled bit lands in the
result ring; the host extracts the byte after the run. For
in-program reactions (DAA), the v0.5 register-file + branch-on-mask
opcodes are the path forward; v0 covers compliance scenarios via
post-run analysis.

### Peripheral emulation library

Same target-role plumbing, dressed up:

```scheme
(define (emulate/tmp108 my-addr temperature)
  (loop
    (i3c/target/wait-for-addressed my-addr)
    (case (i3c/target/read-byte)               ; register pointer
      ((#x00) (i3c/target/respond-bytes (temperature->bytes temperature)))
      ((#x01) (i3c/target/respond-bytes default-config))
      (else   (i3c/target/respond-nack)))))
```

Ship as `i3c-peripheral-emulators` SDK alongside the compliance
suite. Catalog grows organically: TMP108, INA4230, BQ40Z50, the
fuel-gauge and charger catalog we already use in pico-de-gallo, etc.
Lets firmware developers iterate against virtual sensors without
owning the silicon.

## DSL --- Scheme as primary

Confirmed: Scheme/Lisp dialect, compiling to Layer-0 bytecode. YAML
deferred to a possible future declarative-test layer (compiles to
the same bytecode), Rust macro deferred to "tests-in-firmware-repo"
use case (also compiles to the same bytecode).

Implementation options for the compiler:

- **Embed Steel** (Rust-native Scheme) --- fast path to a working
  compiler with a real Scheme runtime.
- **Hand-roll a minimal Scheme** in Rust using `chumsky` for the
  parser --- more work but produces a smaller, dedicated
  compiler with tighter error messages.
- **Use Guile / Chibi as a host-side compiler** --- fastest to
  prototype, ugliest distribution story.

Recommend Steel for the Rust-native story and the alignment with the
pico-de-gallo software stack.

## Error injection model

Compile-time deterministic:

- Compiler takes `(seed, error_ratio)` as inputs.
- PRNG seeded with `seed` runs at compile time.
- For each bit position, compiler decides "inject or not" based on
  PRNG output and ratio threshold.
- Injection mutates the emitted bit pattern (wrong value, wrong T-bit,
  wrong parity, runt SCL, etc.).
- `error_ratio = 0` ⇒ zero PRNG draws cross the threshold ⇒
  zero injections written into the bytecode.

Runtime engine has no randomness. Reproducibility is by construction:
same `(source, seed, ratio)` ⇒ identical bytecode ⇒ identical wire
trace.

## Hardware tiers

| SKU | FPGA | I3C ceiling | I2C | HDR-DDR | Form factor | Target price |
|---|---|---|---|---|---|---|
| **Mole Verde** (pocket) | iCE40 UP5K-SG48 | 12 MHz SDR | all modes | no | icebreaker today; USB-stick PCB later | $299 |
| **Mole Rojo** (bench) | ECP5-45F-CABGA381 | 25 MHz | all modes | yes | small custom PCB | $599 |
| **Mole Negro** (future) | TBD (CertusPro-NX class) | per spec | all modes | yes + HDR-T | rack-friendly | premium |

Both tiers share: Scheme SDK, bytecode format, ISA, error-injection
model, peripheral emulation library, transport protocol.

## PHY --- staged

**v0 / v1: none.** 3.3V LVCMOS direct from FPGA I/O bank. Pull-ups
are jumper-selectable footprints on a PMOD adapter (or just two
through-hole resistors). Caps initial DUT support at 3.3V devices ---
acceptable because every dev-board target we care about today
(MCXA, pico-de-gallo, the ODP charger / fuel-gauge boards) is 3.3V.

**v2 PHY daughter card** (additive, drops into bench-tier PCB):

- Discrete level shifters (NLSV2T244 for HDR-DDR edge-rate margin).
- Programmable VIO LDO (TPS62065 + I2C-controlled divider, or
  LP8758).
- Switched-bank pull-ups via analog mux for compliance repeatability.
- Board-to-board mate, not a cable.

## Compliance scope

In scope (any tier):

- Every CCC, DAA edge case, IBI / Hot-Join arbitration, T-bit /
  RDTERM negotiation, error recovery, multi-master arbitration.
- I2C: every mode (SM, FM, FM+, HS), clock stretching, repeated start.
- Error injection: parity, framing, glitches, NACK, bus-busy,
  stretched timing, runt SCL pulses.
- Reproducible scripted test suites: every test is a hashed bytecode
  binary, runnable on any rig.

In scope only at bench tier:

- HDR-DDR controller / target / monitor.

Out of scope (needs lab gear):

- Eye diagrams, edge-rate / jitter measurement, EMI/EMC,
  sub-ns analog characterization, formal MIPI CTS certification.

The rig is a powerful pre-screen and daily-driver tool, not a
substitute for the formal CTS lab.

## Risks and open questions

1. **Scheme compiler maturity**: writing a real compiler with decent
   error messages is non-trivial. Mitigation: start with Steel,
   migrate to a hand-rolled compiler later only if needed.
2. **Engine LUT estimate is rough**: actual synth + place + route on
   UP5K may land higher than 1800 LUTs. Mitigation: build the engine
   incrementally, measure after each block; UP5K has 3400 LUTs of
   headroom over the estimate.
3. **iCE40 UP5K fabric speed**: 60--80 MHz routable in practice;
   12 MHz I3C SDR ceiling is comfortable but HDR-DDR is out of reach
   on this tier. Mitigation: HDR-DDR is bench-tier only by design.
4. **Async clock-stretching semantics**: `WAIT_SCL_RELEASE` is the
   one "real-time, not pre-computed" operation in the engine. Need
   to specify cleanly how it composes with the quarter-bit clock
   (recommend: pauses the divider, resumes from the same quarter).
5. **Result ring overflow on long runs**: 128 KB SPRAM caps single-
   run capture. Mitigation: stream results out over UART concurrently
   with execution, or split long tests into chunks.
6. **PRNG choice for error injection**: must be deterministic across
   compiler versions. Mitigation: pin a specific PRNG (e.g.,
   ChaCha20 with explicit seed) in the compiler spec.

## Phased plan

### v0 --- pocket prototype on icebreaker (no Pico, no flash, no PCB)

- **Phase 0 --- ISA + compiler skeleton**: finalize the 10-opcode ISA
  encoding. Write a host-side bytecode encoder in Rust. Hand-assemble
  one tiny test program. Validate the encoding by simulation.
- **Phase 1 --- bit engine in HDL**: SpinalHDL or Amaranth. Implement
  the engine + UART RX/TX + SPRAM controller. Self-test by emitting a
  blinking-LED pattern from a hand-assembled program loaded over UART.
- **Phase 2 --- Layer 1 Scheme SDK seed**: minimal `i2c/`, `i3c/sdr/`,
  `raw/` namespaces. Steel-based compiler. Round-trip
  `cat test.lisp > /dev/ttyUSB0` ⇒ wire activity ⇒ result back
  over the same UART.
- **Phase 3 --- first real test**: drive an MCXA dev board running
  `embassy-mcxa` I3C target. Hand-port one of our existing soak
  scenarios to the Scheme SDK.
- **Phase 4 --- DAA + CCC coverage**: full CCC catalog, DAA edge
  cases. Compliance-style test catalog begins here.
- **Phase 5 --- target role + peripheral emulation**: implement
  `i3c/target/*` in the SDK. Build emulator for one well-known
  peripheral (e.g., TMP108). Validate the rig as a virtual sensor
  against an MCXA controller.
- **Phase 6 --- error injection**: compile-time PRNG-driven
  injection. Reproducibility tests (same seed ⇒ same trace).

### v1 --- Pico polish layer

- **Phase 7 --- RP2350 transport**: postcard-rpc over USB. Pico talks
  SPI/QSPI to the icebreaker. UART path stays as a fallback for dev.
- **Phase 8 --- host tooling**: better DSL editor integration,
  result visualizer, test catalog management. Python bindings for
  scripted regression.

### v2 --- bench tier

- **Phase 9 --- ECP5-45K bring-up board**: small custom PCB. Same
  engine, same SDK, larger fabric.
- **Phase 10 --- HDR-DDR layer**: HDR-DDR Scheme primitives.
  Validate against MCXA HDR-DDR target (when we add HDR-DDR support
  there).
- **Phase 11 --- PHY daughter card**: programmable VIO, switched
  pull-ups, slew control. Unlocks 1.2 V / 1.8 V DUTs.
- **Phase 12 --- compliance test suite**: comprehensive Scheme test
  catalog covering the MIPI compliance checklist. Result-diff CI.

## Productization

### Name

**Mole** --- continues the Latin-cuisine product family started with
pico-de-gallo, with three layered meanings that all reinforce the
product:

- *Mole* the sauce: famously complex, many ingredients carefully
  layered --- mirrors the structure of a compliance SDK.
- *Mole* the animal: burrows underground, sees what is hidden ---
  mirrors what a bus analyzer does.
- *Mole* the SI unit (6.022 × 10²³): scientific gravitas, signals
  "measurement instrument".

Sub-products carry mole varieties --- Verde (green, lighter, everyday),
Rojo (red, deeper, full-featured), Negro (Oaxacan, the darkest and
most complex).

Runner-up names kept in reserve: **Compás** (Spanish for compass +
musical measure --- literally describes a quarter-bit timing engine),
**Berimbau** (Brazilian capoeira instrument, strong rhythm semantics).

### Pricing

| SKU | Price | Anchor | Margin posture |
|---|---|---|---|
| Mole Verde | $299 | vs. $30 hobbyist FPGA boards | 8--12× BoM markup; software/SDK carries the price |
| Mole Rojo | $599 | vs. SuperMITT $995 (-40 %) | 5--7× BoM markup; healthy room for distributor channel |
| Mole Negro | TBD | premium tier, post-product-market-fit | --- |

**BoM estimates (small-volume direct)**:

- Mole Verde: UP5K (~$10) + Pico 2 (~$5) + USB-C + LDO + passives +
  small PCB → ~$25--40.
- Mole Rojo: ECP5-45K (~$25) + Pico 2 (~$5) + level shifters / PHY
  (~$15) + USB-C + flash + connectors + LDOs (~$15) + larger PCB
  (~$20) → ~$80--120.

### Positioning vs. MCCI SuperMITT

- **Price**: Mole Rojo $599 vs. SuperMITT $995 --- ~40 % undercut.
- **Software story**: Mole ships an *open*, *diff-able*, *hackable*
  Scheme SDK + peripheral-emulator library + curated compliance test
  catalog. SuperMITT's firmware is closed. This is the defensible
  long-term moat --- hardware can be copied; a living, contributable
  SDK is much harder.
- **Audience**: open-source-friendly, Rust-using, Linux-comfortable
  engineers --- self-serve buyers who do not need (or want)
  sales@mcci.com hand-holding.
- **Distribution**: direct (own webshop) + Crowd Supply launch + small
  distributor footprint later.

### Pricing risks / caveats

1. **Certifications eat early margin**: CE EMC + FCC Part 15B
   unintentional-radiator testing runs ~$5--20k per design depending
   on test house. Budget ~$10k per SKU, ~$20k total fixed cost to
   amortize before first profit dollar.
2. **Support is the hidden tax**: at $299 / $599 in the engineering
   tools market, customers expect real responsiveness. Mitigation:
   excellent docs, excellent Scheme-compiler error messages, public
   issue tracker, community-first support model.
3. **The MCCI $995 includes hand-holding**: their TAM overlaps but
   is not identical to ours. Do not model Mole unit volume off
   SuperMITT sales --- our buyer self-serves, which means smaller
   TAM but higher per-unit margin and lower CAC.
4. **Hobbyist-board comparison risk on Mole Verde**: at $299 a
   developer's instinct is to compare against $30 FPGA dev boards.
   The Mole-Verde value prop must lead with the SDK / docs /
   curated tests / peripheral emulators --- not the silicon. Lean
   on "the cheapest way to put real I3C compliance coverage on
   every developer's desk".

### Volume / break-even sketch

Conservative single-developer-operation assumptions:

- Mole Verde at $299, ~$35 BoM, ~$50 fulfillment+support overhead
  per unit → ~$214 contribution margin.
- Mole Rojo at $599, ~$100 BoM, ~$80 fulfillment+support overhead
  per unit → ~$419 contribution margin.
- Fixed certification + tooling + initial inventory: ~$30--50k.
- Break-even at ~150 Verde *or* ~80 Rojo *or* (more realistic) a
  blended ~100 units across SKUs.

That is a *small* number of units to be cash-positive --- which
matches the "indie engineering tool" profile. The hard part is
demand generation, not unit economics.

### Brand stack

- **pico-de-gallo** --- HIL USB peripheral for firmware automation
  (existing).
- **Mole** --- I3C / I2C compliance and emulation rig (this project).
- Both names live under the same Latin-cuisine family. Future product
  lines can keep the theme open (sauces, condiments, dishes) without
  collision.

## Notes

- The entire pico-de-gallo software investment (postcard-rpc,
  Embassy USB, FFI, Python bindings, HIL test patterns) carries
  over to the v1+ Pico-as-transport role. Nothing duplicated.
- The Scheme SDK + bytecode pair is the durable, hashable, portable
  artifact per-DUT --- a stronger compliance story than what MCCI
  achieves with raw FX3 streaming, because every wire bit is
  explicit in source.
- The peripheral-emulator library is a second-order ecosystem
  asset: "test your firmware against the entire ODP sensor catalog
  without owning the silicon" is a story that sells itself.
- Open-toolchain throughout (yosys + nextpnr-ice40 + icestorm for
  pocket; yosys + nextpnr-ecp5 + prjtrellis for bench). No node-
  locked licenses, no NDAs, reproducible CI.
- v0 needs zero ordered parts. Everything is on your desk today.