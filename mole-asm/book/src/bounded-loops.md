# Bounded loops

Two opcodes -- `LOAD_LOOP` and `DEC_BRANCH` -- plus two 8-bit
counter registers (`lcr0` and `lcr1`) cover almost every loop
you will ever write in moleasm.

## The shape

```text
        LOAD_LOOP   <reg>, <count>          ; prime the counter
<label>:
        ; ... body executed once per iteration ...
        DEC_BRANCH  <reg>, <label>          ; back-edge while reg != 0
        ; fall-through here after `count` iterations
```

`LOAD_LOOP r, N` writes the 8-bit immediate `N` into `LCR[r]`.
`DEC_BRANCH r, target` decrements `LCR[r]` then back-edges to
`target` if the result is non-zero. The loop body therefore runs
exactly `N` times before falling through.

Two registers means you can nest one level without a single
scratch slot in the result ring; see [Nested loops](#nested-loops)
below.

## Worked example: send 8 dominant bits

The data-bit phase of an I2C write-one-byte, written as a loop:

```text
        LOAD_LOOP   lcr0, 8                 ; eight data bits
bit_loop:
        EMIT_BIT    tx=dominant             ; SDA low for one bit-time
        DEC_BRANCH  lcr0, bit_loop          ; -> bit_loop while lcr0 != 0
        ; fall through here after eight EMIT_BITs
```

Three instructions and one label replace eight repeated
`EMIT_BIT` lines. The encoded form is also denser: four 16-bit
words instead of eight, leaving twice the room in the 16-word
program memory of a Mole Verde test fixture.

## Nested loops

The fixture `mole-asm/tests/fixtures/loop-counter-demo.moleasm`
prints a 3-by-4 grid of `MARK` records as a worked nested
example. Reduced to the loop scaffolding:

```text
        LOAD_LOOP   lcr1, 3                 ; outer: 3 rows
row_loop:
        MARK        label=3                 ; "row begin"
        LOAD_LOOP   lcr0, 4                 ; inner: 4 columns
col_loop:
        MARK        label=4                 ; "(row, col)"
        DEC_BRANCH  lcr0, col_loop          ; -> col_loop, 4 iterations
        MARK        label=5                 ; "row end"
        DEC_BRANCH  lcr1, row_loop          ; -> row_loop, 3 iterations
```

`lcr0` resets to `4` at the top of every row because the inner
`LOAD_LOOP` re-runs each pass; `lcr1` walks down independently.
The total trip count is `3 * 4 = 12` `MARK label=4` records,
plus three `MARK label=3` + three `MARK label=5` row boundaries,
and a final `HALT`.

## Sticky flags compose

`DEC_BRANCH` does **not** touch any of the sticky engine flags
(`MISMATCH_FLAG`, `TIMEOUT_FLAG`, `START_FLAG`, `STOP_FLAG`),
so the standard fail-fast pattern from the
[Patterns](./patterns.md) chapter works unchanged inside a loop
body:

```text
        LOAD_LOOP     lcr0, 8
bit_loop:
        EMIT_BIT      tx=hiz expect=0 mask=1 capture=1
        BRANCH_ON     MISMATCH, fail        ; bail on first ACK miss
        DEC_BRANCH    lcr0, bit_loop
        ; ... all eight bits succeeded ...
        HALT          status=0
fail:
        MARK          label=1
        HALT          status=1
```

`BRANCH_ON MISMATCH` reads the most recent capture's flag --- it
is never confused by the `DEC_BRANCH` underneath.

## Edge cases

`LOAD_LOOP r, 0` is legal but unusual: the matching `DEC_BRANCH`
wraps the counter from `0` to `0xFF` on the first iteration and
runs a full 256 passes. The assembler warns on a literal `0` in
the source. If you really want 256 iterations, write the literal
`0` with a comment explaining the intent.

`DEC_BRANCH r, offset` uses a signed 8-bit PC-relative offset, so
the branch target must sit within +/-128 instructions of the
`DEC_BRANCH`. For longer reach, hop via an intermediate `JMP`
just as you would for `BRANCH_ON`.

## When *not* to use the loop counter

- **Compile-time-known short repeats.** Two or three repeated
  `EMIT_BIT`s are clearer inline than as a loop. Save the
  counter for cases where the repeat count is named (`.equ
  retry_limit, 3`) or where the body is non-trivial.
- **More than two levels of nesting.** Two counters cover one
  level of nesting (outer + inner). Beyond that you would need
  to spill a counter into the result ring or to manual
  unrolling. A future expansion may add more LCRs; in v0 the
  ceiling is two.
- **Iteration counts above 255.** Either factor the count into
  an outer loop times an inner loop, or fall back to a
  pre-counted unrolled sequence.

## What the engine pays

`LOAD_LOOP` adds one fabric cycle of latency (one fetch + decode
+ register write). `DEC_BRANCH` adds the same fetch / decode +
one register read-modify-write, then the branch is free (the
engine prefetches both possible next instructions on every
fetch). For a tight loop with an `EMIT_BIT` body, the per-
iteration overhead is two fabric cycles on top of the four
quarter-bits the wire takes anyway.

The full ISA is now in your toolkit. The next chapter walks
through three real end-to-end programs you can flash and watch.
