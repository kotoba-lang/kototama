# kototama maturity ladder

Honest status of kototama as a **Wasm tender** (Solo5 pattern: host = tender,
guest = Wasm component). Not a marketing scorecard.

## Levels

| Level | Title | Status | What is real |
|---|---|---|---|
| **R0** | Contract / dry-run | **stable** | `kototama.contract` HostCaps + import surface; organism membrane refuses live publish |
| **R1** | Tender execution (JVM/Chicory) | **stable** | `kototama.tender` runs real `.wasm`; fuel + memory limits; aiueos adapter; session report; source lint; host-free + host-import fixtures from `kotoba wasm emit` |
| **R2** | Browser-native host parity | **advanced-partial** | parity matrix; 8/9 browser-linkable; http-post via inject / SAB+COOP bridge (`http-post-bridge.js`); host-free web fixtures |
| **R3** | Fleet multi-tenant tender | **skeleton+persist** | fleet + disk/B2 checkpoint store + tender `run-report` execute bridge |

**Current declared level: R2 (advanced-partial), R3 skeleton+persist.**

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
| http-post | yes | inject / SAB+COOP | inject |

Score today: **8/9** browser-linkable (`llm-infer` still Node-inject only).

### http-post paths (R2)

| Path | Env | How |
|---|---|---|
| inject | Node / tests | `opts.httpPost(url, body) => Uint8Array` |
| SAB + COOP | Browser `crossOriginIsolated` | `createSabHttpPostBridge()` + COOP/COEP headers |
| JSPI | Chrome experimental | not default; detect via `httpPostCapabilities().jspi` |

```bash
# in wasm-webcomponent
node test/verify-http-post.mjs
```

Host library: `actor-host.js`, `http-post-bridge.js`, `kgraph.js`.

## R3 skeleton+persist gates

```bash
clojure -M:cli fleet-demo
clojure -M:cli fleet-run test/kototama/fixtures/kotoba-compiled-fact.wasm
# writes tmp/kototama-fleet/*.edn
```

| Landed | Not yet |
|---|---|
| lease / budget / tick / governor | cross-node consensus |
| checkpoint/restore EDN v1 | recovery daemon |
| **disk store** (`fleet-store`) | aiueos fleet broker |
| **B2 optional** (env creds) | |
| **tender execute** (`fleet-exec`) | |

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
