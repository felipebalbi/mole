#import "../lib.typ": *

// Quarter-bit timing.

#section-slide("Quarter-bit timing")

#slide[
  = The fabric clock is the quarter-bit clock
  Every I3C / I2C bit on the wire is four engine ticks.

  - Tick 0 --- drive the *first quarter*.
  - Tick 1 --- drive the *second quarter*.
  - Tick 2 --- drive the *third quarter*.
  - Tick 3 --- drive the *fourth quarter*.

  At I3C SDR's 12.5 MHz bit rate, that's a *50 MHz* fabric
  clock --- comfortable for iCE40 UP5K. At I2C Fast-mode's
  400 kHz, it's 1.6 MHz --- trivially.

  No PLL-multiplied sub-quarter ticks. No half-tick fudge.
  Bit-rate change = divider change.
]

#slide[
  = Why four quarters?
  Three reasons, in order of importance.

  + *Setup and hold.* SDA must be stable across the SCL rising
    edge. Splitting a bit into quarters lets the SDK express
    "drive new SDA value in Q0, raise SCL in Q1, hold both
    through Q2, lower SCL in Q3" --- *spec-correct setup/hold*
    without any timing magic in the engine.
  + *Glitch budget.* A single-quarter pulse on SDA is the
    minimum injectable disturbance. Anything finer would
    require a faster fabric clock for no real test value.
  + *Repeated start.* The Sr sequence is naturally expressed
    as a sequence of quarter-bit drives without any
    special-case opcode.
]

#slide[
  = What `EMIT_BIT` actually emits
  `EMIT_BIT` is the bread-and-butter opcode: drive *one* bit on
  SDA using a canonical I2C/I3C waveform that's four quarters
  long.

  ```
  Q0:  SDA <- new value,  SCL <- low
  Q1:  SDA  = held,       SCL <- high
  Q2:  SDA  = held,       SCL  = high   (sample window)
  Q3:  SDA  = held,       SCL <- low
  ```

  `EMIT_QUARTER` lets you override one of those quarters
  explicitly --- the escape hatch for everything `EMIT_BIT`
  can't express by itself.
]
