# mole-loader

Host-side loader and result-ring decoder for the Mole bit-cycle
engine.

`mole-loader` is the runtime counterpart to the `mole-asm`
assembler: it takes a `.mole.bin` artifact, ships it to a real Mole
engine over a serial port, waits for the program to finish, drains
the result ring, and decodes the captured records.

The CLI front-end lives in the sibling crate
[`mole-loader-cli`](../mole-loader-cli/) and produces the binary
`mole-loader`.

This crate is currently a scaffold. The decoder lands in a
follow-up commit; serial-port transport lands after that.
