#import "../lib.typ": *

// Worked example 1: clean I2C write.

#section-slide("Worked example: I2C write")

#slide[
  = Goal
  Send one byte (`0x42`) to a TMP108 temperature sensor at
  address `0x48`, register `0x01`. Capture the ACK after the
  address byte, the ACK after the register byte, and the ACK
  after the data byte.

  Three ACKs, three captures. The host reads the result ring
  and confirms all three were `dominant` (target acknowledged).
]

#slide[
  = Setup
  ```
  .bus_mode i2c
  .timing div=125        // 400 kHz on a 50 MHz fabric

  // SDA + SCL idle high.  Pull-ups already enabled.
  ```

  No PRNG, no glitch ratio --- this is the compliant version.
]

#slide[
  = The transaction
  ```
  start:   emit_quarter sda=dom scl=hiz   // SDA falls while SCL high
           emit_quarter sda=dom scl=dom   // SCL low; address phase next

  // 0x48 << 1 | W = 0x90 = 1001 0000
           emit_bit tx=rec    // 1
           emit_bit tx=dom    // 0
           emit_bit tx=dom    // 0
           emit_bit tx=rec    // 1
           emit_bit tx=dom    // 0
           emit_bit tx=dom    // 0
           emit_bit tx=dom    // 0
           emit_bit tx=dom    // 0 -- W
           emit_bit tx=hiz expect=0 capture=1   // ACK
  ```
]

#slide[
  = Register + data
  ```
  // Register address 0x01
           emit_bit tx=dom    // 0
           emit_bit tx=dom    // 0
           emit_bit tx=dom    // 0
           emit_bit tx=dom    // 0
           emit_bit tx=dom    // 0
           emit_bit tx=dom    // 0
           emit_bit tx=dom    // 0
           emit_bit tx=rec    // 1
           emit_bit tx=hiz expect=0 capture=1   // ACK

  // Data byte 0x42 = 0100 0010
           emit_bit tx=dom    // 0
           // ... seven more emit_bit ...
           emit_bit tx=hiz expect=0 capture=1   // ACK

  stop:    emit_quarter sda=dom scl=hiz   // SCL rises
           emit_quarter sda=hiz scl=hiz   // SDA rises while SCL high
           halt
  ```
]

#slide[
  = What the host sees
  Three `CAPTURE` records in the result ring, in order:

  ```
  capture[0] = 0  (target ACKed address byte)
  capture[1] = 0  (target ACKed register byte)
  capture[2] = 0  (target ACKed data byte)
  ```

  Any `1` in those slots means the target NACKed --- and the
  host knows *exactly which byte* by position. No timestamping,
  no protocol parsing on-target. The engine wrote the data;
  the host knows the program; the program tells the host
  what each record means.
]
