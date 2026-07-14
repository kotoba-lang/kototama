# wasm/ — kotoba-wasm port of `kototama.unspsc.life/prior-shortcut?`

`prior_shortcut.kotoba` is a narrow-slice port of
`kototama.unspsc.life/prior-shortcut?` (`../src/kototama/unspsc/life.cljc`)
into the minimal `.kotoba` language subset, compiled to a real WASM module
via `kotoba wasm emit`, and hosted directly via Chicory
(`test/wasm/prior_shortcut_test.clj`).

This is ADR-2607151500's kotoba-lang-org-internal narrow-slice port
candidate, realized. The first candidate considered
(`kototama.unspsc.capability`'s `segment-capabilities` table) turned out to
store predicate **closures as data** (keyed by UNSPSC segment) — exactly the
general first-class-function pattern ADR-2607150000's triage rejected for
`.kotoba` (T2 effect-soundness: a static effect-fixpoint analysis can't see
through an arbitrary closure argument). `prior-shortcut?` has no such
dependency: it's a genuinely pure integer/boolean decision, and — unlike
the abandoned candidate — has a real call site today
(`kototama.unspsc.organism`, line ~68).

## Why the source differs from `kototama.unspsc.life`

The `.kotoba` compiler's safe subset has no string-equality operation, so:

- The original function takes one `consensus` map and compares
  `(:dominant-status consensus)` against the string `"authorized"`. The
  port takes 4 flattened positional scalar args instead (no maps needed at
  all here), with the string comparison pushed to the **caller**: the host
  precomputes `dominant-status-authorized` (1 if
  `(= (:dominant-status consensus) "authorized")` holds, 0 otherwise)
  before writing it into the guest's linear memory. Same convention
  `cloud-itonami-isic-6492`'s `wasm/affordability.kotoba` and its governor
  port already established for turning map-shaped inputs into a host-side
  precomputation + flat scalar ABI.

## ABI — parameterized invocation

`kotoba wasm emit` rejects any `main` with parameters, so real inputs are
passed through the guest's exported linear memory instead. A host writes 4
little-endian i32 values before calling `main()`:

| offset | field                          |
|--------|--------------------------------|
| 0      | `outcome-count`                |
| 4      | `confidence-permille` (0..1000)|
| 8      | `input-match-count`            |
| 12     | `dominant-status-authorized` (flag: 0 or non-0) |

`main()` returns `1` (take the prior shortcut) or `0` (fall through to full
evaluation).

## Rebuilding

```sh
cd ../../kotoba              # sibling checkout, west-managed (orgs/kotoba-lang/kotoba)
bin/kotoba-clj wasm emit ../kototama/clj/wasm/prior_shortcut.kotoba \
  --package-lock kotoba.lock.edn \
  --output ../kototama/clj/wasm/prior_shortcut.wasm --json
```

## Verification

Every oracle case in `test/wasm/prior_shortcut_test.clj` is copied directly
from `kototama.unspsc.life-test`'s own `prior-consensus-parity` test
(`test/kototama/unspsc/life_test.clj`), translated to the flattened-scalar
ABI above — so this test doubles as a cross-check that the `.kotoba` port
and the in-process reference function never disagree. Hosted directly via
a real Chicory `Instance` (no host imports needed — this module calls no
capability/heap ops, only `mem-i32-at`/arithmetic/comparison), not a
separate interpreter.

## Follow-ups

- Not wired into `kototama.unspsc.organism`'s actual call site — this pass
  only proves the decision compiles and runs correctly as `.kotoba`/WASM,
  mirroring how the cloud-itonami governor port kept "compile + verify"
  and "wire the facade to call it" as separate steps.
- No fleet/production deployment — out of scope here.
