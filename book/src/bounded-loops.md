# Bounded loops

v0.2 builds loops out of three small pieces:

1. **A 32-bit general-purpose register** (`R0`..`R7`) holding the
   iteration counter.
2. **`DEC Rx`** — decrements `Rx` in place and sets the
   sticky `REG_ZERO_FLAG` based on the post-decrement value.
3. **`BRANCH_ON {REG_ZERO|NOT_REG_ZERO}, <label>`** — consumes
   `REG_ZERO_FLAG` and back-edges if the condition matches.

Pair them at the end of a loop body and you have a counted
loop. Use different registers for the inner and outer counters
and you get arbitrary nesting depth.

## The shape

```text
        LOAD_IMM    R6, <count>             ; or: LOAD_LOOP <count>
<label>:
        ; ... body executed once per iteration ...
        DEC         R6                      ; R6 := R6 - 1; sets REG_ZERO_FLAG
        BRANCH_ON   NOT_REG_ZERO, <label>   ; back-edge while R6 != 0
        ; fall-through here after `count` iterations
```

`LOAD_IMM R6, N` writes the 14-bit immediate `N` into `R6`
(range `0..16383`). The sugar form `LOAD_LOOP N` is exactly
equivalent and reads as the intent (`R6` is the conventional
loop counter, but the sugar is hard-wired to `R6` — pick any
other register and write `LOAD_IMM` directly).

`DEC R6` decrements in place and writes `REG_ZERO_FLAG`. The
**next** instruction must be the matching `BRANCH_ON` —
inserting any other ALU op (`ADD_IMM`, `MOV`, another `DEC`,
`LOAD_IMM`, `AND_IMM`, `OR_IMM`, `XOR_IMM`, `SHIFT`, or any
WIRE-capturing op) overwrites `REG_ZERO_FLAG` and the assembler
emits `LINT-001`. The `BRANCH_ON` itself can sit immediately
after `DEC`; the v0.2 pipeline forwards `REG_ZERO_FLAG` to the
back-to-back consumer without a hazard (spec §14, §5.21).

## Worked example: send 8 dominant bits

The data-bit phase of an I²C write-one-byte, written as a loop:

```text
        LOAD_LOOP   8                       ; prime R6 = 8
bit_loop:
        EMIT_BIT_IMM tx=dominant            ; SDA low for one bit-time
        DEC         R6
        BRANCH_ON   NOT_REG_ZERO, bit_loop  ; back-edge while R6 != 0
        ; fall through here after eight EMIT_BIT_IMMs
```

Four instructions and one label replace eight repeated
`EMIT_BIT_IMM` lines.

## Nested loops

Any GP-register pair gives a clean two-level nest. The fixture
`mole-asm/tests/fixtures/loop-counter-demo.moleasm` prints a
3-by-4 grid of `MARK` records as a worked nested example.
Reduced to the loop scaffolding:

```text
        LOAD_IMM    R5, 3                   ; outer: 3 rows
row_loop:
        MARK        label=3                 ; "row begin"
        LOAD_LOOP   4                       ; inner: 4 columns (R6 = 4)
col_loop:
        MARK        label=4                 ; "(row, col)"
        DEC         R6
        BRANCH_ON   NOT_REG_ZERO, col_loop  ; -> col_loop, 4 iterations
        MARK        label=5                 ; "row end"
        DEC         R5
        BRANCH_ON   NOT_REG_ZERO, row_loop  ; -> row_loop, 3 iterations
```

`R6` resets to `4` at the top of every row because the inner
`LOAD_LOOP` re-runs each pass; `R5` walks down independently.
The total trip count is `3 * 4 = 12` `MARK label=4` records,
plus three `MARK label=3` + three `MARK label=5` row
boundaries, and a final `HALT`.

The same pattern extends to three or more nesting levels: pick
a different GP register for each loop counter (e.g. `R3` for the
deepest inner, `R4` for the middle, `R5` for the outer) and
write one `LOAD_IMM` / `DEC` / `BRANCH_ON NOT_REG_ZERO` triplet
per level.

## Sticky flags compose

The MISMATCH / TIMEOUT / START / STOP sticky flags survive the
loop scaffolding because `DEC` only writes `REG_ZERO_FLAG`; the
other four flags are independent. The standard fail-fast
pattern from the [Patterns](./patterns.md) chapter works
unchanged inside a loop body:

```text
        LOAD_LOOP     8
bit_loop:
        EMIT_BIT_IMM  tx=hiz expect=0 mask=1 capture=1
        BRANCH_ON     MISMATCH, fail        ; bail on first ACK miss
        DEC           R6
        BRANCH_ON     NOT_REG_ZERO, bit_loop
        ; ... all eight bits succeeded ...
        HALT          status=0
fail:
        MARK          label=1
        HALT          status=1
```

`BRANCH_ON MISMATCH` reads the most recent capture's
`MISMATCH_FLAG` — it is never confused by the `DEC` underneath,
which only touches `REG_ZERO_FLAG`.

## Edge cases

`LOAD_IMM R6, 0` (or `LOAD_LOOP 0`) is legal but the matching
`DEC` wraps the counter from `0` to `0xFFFF_FFFF` on the first
iteration and runs ~4 billion passes. The assembler warns on a
literal `0` in the source. If you really want a maximum-count
loop, document the intent with a comment.

`BRANCH_ON` uses a signed PC-relative offset of 10 bits, so the
branch target must sit within `+/-512` instructions of the
`BRANCH_ON`. For longer reach, hop via an intermediate
`JMP <label>` (sugar for `BRANCH_ON ALWAYS, <label>`).

## When *not* to use a counted loop

- **Compile-time-known short repeats.** Two or three repeated
  `EMIT_BIT_IMM`s are clearer inline than as a loop. Save the
  counter for cases where the repeat count is named (`.equ
  retry_limit, 3`) or the body is non-trivial.
- **Iteration counts above 16383.** `LOAD_IMM`'s 14-bit
  immediate caps the count at `0x3FFF`. Beyond that, factor
  the count into an outer loop times an inner loop, or build
  the count in two `LOAD_IMM` + `SHIFT` + `OR_IMM` steps.
- **Data-dependent loops.** Use `BRANCH_ON MISMATCH` / `BRANCH_ON
  NOT_MISMATCH` to escape early on a flag condition instead of
  burning down the counter.

## What the engine pays

`LOAD_IMM` adds one fabric cycle of latency (one fetch + decode
+ register write). `DEC` adds the same fetch / decode + one
register read-modify-write. `BRANCH_ON` is single-cycle once
its cond is available (`REG_ZERO_FLAG` forwarded from the
previous `DEC` via the v0.2 register-file bypass). For a tight
loop with an `EMIT_BIT_IMM` body, the per-iteration overhead is
two fabric cycles on top of the four quarter-bits the wire
takes anyway.

The full ISA is now in your toolkit. The next chapter walks
through three real end-to-end programs you can flash and watch.
