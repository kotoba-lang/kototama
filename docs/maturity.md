# kototama maturity ladder

Honest status of kototama as a **Wasm tender** (Solo5 pattern: host = tender,
guest = Wasm component). Not a marketing scorecard.

## Levels

| Level | Title | Status | What is real |
|---|---|---|---|
| **R0** | Contract / dry-run | **stable** | `kototama.contract` HostCaps + import surface; organism membrane refuses live publish |
| **R1** | Tender execution (JVM/Chicory) | **stable** | `kototama.tender` runs real `.wasm`; fuel + memory limits; aiueos adapter; session report; source lint; host-free + host-import fixtures from `kotoba wasm emit` |
| **R2** | Browser-native host parity | **advanced-partial** | parity matrix (`kototama.browser`); 7/9 actor:host sync in `actor-host.js`; host-free web fixtures + `verify-host-free.mjs`; http-post/llm absent in tab |
| **R3** | Fleet multi-tenant tender | **skeleton** | pure `kototama.fleet`: lease / budget / tick / governor / checkpoint / run-loop-step — no cross-node consensus |

**Current declared level: R2 (advanced-partial), with R3 skeleton landed.**

## R1 acceptance gates

```bash
clojure -M:test
clojure -M:doctor
clojure -M:cli run test/kototama/fixtures/kotoba-compiled-fact.wasm
clojure -M:cli run test/kototama/fixtures/kotoba-compiled-peak-cells.wasm
```

### Checked-in emit fixtures (`test/kototama/fixtures/`)

| Guest | Imports | Expected `main` |
|---|---|---|
| `kotoba-compiled-fact.wasm` | none | `120` (= 5!) |
| `kotoba-compiled-peak-cells.wasm` | none | `240` (Williams integer proxy @ S=4096) |
| `kotoba-compiled-sha256-hex.wasm` | `sha256_hex` | writes empty-string digest |
| `kotoba-compiled-gen-keypair.wasm` | `gen_keypair` | 64 bytes seed+pub |

## R2 acceptance gates

```bash
node web/verify.mjs
node web/verify-kgraph.mjs
node web/verify-actor-host.mjs
node web/verify-host-free.mjs   # demo + fact + peak-cells under browser Wasm
clojure -M:cli parity           # JVM vs browser import matrix
```

### Browser import parity (actor:host)

| Import | JVM tender | Browser tab | Node (JS) |
|---|---|---|---|
| gen-keypair / sign / verify | yes | yes (noble sync) | yes |
| sha256-hex / clock / log-* | yes | yes | yes |
| llm-infer | yes | **no** | inject |
| http-post | yes | **no** | **no** |

Score today: **7/9** browser-yes (`kototama.browser/parity-score`).

Host library: `kotoba-lang/wasm-webcomponent` (`actor-host.js`, `kgraph.js`).
Policy re-enforcement at load: **actor-host.js yes**; **kgraph.js no**.

## R3 skeleton gates

```bash
clojure -M:cli fleet-demo
# covered by kototama.fleet-test in clojure -M:test
```

| Landed (pure cljc) | Not yet |
|---|---|
| lease create/renew/expire | cross-node consensus |
| budget charge | persistent store |
| plan-tick + apply-tick-result | recovery daemon |
| governor-allow? | aiueos fleet broker |
| registry multi-tenant index | |
| checkpoint/restore schema v1 | |
| run-loop-step (inject execute) | |

## Guest source rules (emit subset)

1. **No defn docstrings** — string parsed as arity.
2. **`(defn main [] …)`** required.
3. Allowed: multi-defn, recursion, let, integer `/`, `ns`.
4. Host imports only module `"kotoba"` fields in `guest/wasm-field-by-import-id`.

## Closed loops

```
R1: .kotoba --lint--> wasm emit --> .wasm --> tender/run-report (Chicory)
R2: .wasm --> browser/Node WebAssembly + actor-host.js | host-free {}
R3: lease --> governor --> plan-tick --> (inject tender) --> apply-tick-result
           --> checkpoint EDN
```

## What we deliberately do **not** claim

- Full browser network imports (http-post) without JSPI/COOP-COEP
- Production multi-node fleet scheduling
- That every kotoba language feature emits to Wasm
