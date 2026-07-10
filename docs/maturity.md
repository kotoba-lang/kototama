# kototama maturity ladder

Honest status of kototama as a **Wasm tender** (Solo5 pattern: host = tender,
guest = Wasm component). Not a marketing scorecard.

## Levels

| Level | Title | Status | What is real |
|---|---|---|---|
| **R0** | Contract / dry-run | **stable** | `kototama.contract` HostCaps + import surface; organism membrane refuses live publish |
| **R1** | Tender execution (JVM/Chicory) | **stable** (this doc) | `kototama.tender` runs real `.wasm`; fuel + memory limits; aiueos adapter; session report; source lint; host-free + host-import fixtures from `kotoba wasm emit` |
| **R2** | Browser-native host parity | **partial** | `web/` actor-host + kgraph demo; not full `actor:host` surface; limited policy re-enforcement at load |
| **R3** | Fleet multi-tenant tender | **planned** | lease / budget / crash recovery durable outer loop — not landed |

**Current declared level: R1.**

## R1 acceptance gates (must stay green)

```bash
clojure -M:test          # contract + tender + aiueos + guest lint + maturity fixtures
clojure -M:doctor        # maturity snapshot
clojure -M:cli lint  test/kototama/fixtures/kotoba-compiled-fact.kotoba
clojure -M:cli inspect test/kototama/fixtures/kotoba-compiled-fact.wasm
clojure -M:cli run     test/kototama/fixtures/kotoba-compiled-fact.wasm
clojure -M:cli run     test/kototama/fixtures/kotoba-compiled-peak-cells.wasm
```

### Checked-in emit fixtures (`test/kototama/fixtures/`)

| Guest | Imports | Expected `main` |
|---|---|---|
| `kotoba-compiled-fact.wasm` | none | `120` (= 5!) |
| `kotoba-compiled-peak-cells.wasm` | none | `240` (Williams integer proxy @ S=4096) |
| `kotoba-compiled-sha256-hex.wasm` | `sha256_hex` | writes empty-string digest |
| `kotoba-compiled-gen-keypair.wasm` | `gen_keypair` | 64 bytes seed+pub |

All four are produced by **independent** `kotoba-lang/kotoba` `wasm emit`
(not hand-written WAT). Re-emit when the compiler encoding changes.

## Guest source rules (emit subset)

Verified against kotoba wasm emit (2026-07-10):

1. **No defn docstrings** — the string is parsed as arity → `main-arity` failure.
2. **`(defn main [] …)`** is required (0-arity export).
3. Allowed: multi-defn, recursion, nested/multi-bind `let`, integer `/`, `ns`.
4. Host imports only from module `"kotoba"` with field names in
   `kototama.guest/wasm-field-by-import-id`.

Lint before emit:

```clojure
(require '[kototama.guest :as g])
(g/lint-kotoba-source (slurp "guest.kotoba"))
```

## Execution API (R1)

| API | Role |
|---|---|
| `tender/instantiate` | Instance only (legacy) |
| `tender/open-session` | Instance + fuel counter + limits atom |
| `tender/run-report` | Structured `{:ok? :result :fuel-used :limits …}` |
| `tender/inspect-module` | Parse-only export/import surface |
| `guest/lint-kotoba-source` | Pre-emit static checks |
| `guest/profile` | host-free? / network? / secret? classification |
| `cli` doctor/lint/inspect/run | Operator entrypoints |

## Closed loop

```
.kotoba  --(lint)-->  kotoba wasm emit  -->  .wasm
                                              |
                     HostCaps + grants  <-----+
                                              v
                                    kototama.tender (Chicory)
                                              |
                                         run-report
```

aiueos decides grants (`kototama.aiueos-adapter`); tender never invents authority.

## What R1 deliberately does **not** claim

- Full browser import parity (R2)
- Multi-tenant durable fleet scheduling (R3)
- Float / ln Williams math inside pure `.kotoba` (use `.cljc` SSoT + integer proxy guest)
- That every kotoba language feature emits to Wasm
