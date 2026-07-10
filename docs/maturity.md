# kototama maturity ladder

Honest status of kototama as a **Wasm tender** (Solo5 pattern: host = tender,
guest = Wasm component). Not a marketing scorecard.

## Levels

| Level | Title | Status | What is real |
|---|---|---|---|
| **R0** | Contract / dry-run | **stable** | `kototama.contract` HostCaps + import surface; organism membrane refuses live publish |
| **R1** | Tender execution (JVM/Chicory) | **stable** | `kototama.tender` runs real `.wasm`; fuel + memory limits; aiueos adapter; session report; source lint; host-free + host-import fixtures from `kotoba wasm emit` |
| **R2** | Browser-native host parity | **advanced-partial** | parity matrix; 8/9 browser-linkable; http-post via inject / SAB+COOP bridge (`http-post-bridge.js`); host-free web fixtures |
| **R3** | Fleet multi-tenant tender | **advanced-partial** | disk/B2 + fence-gated tender + daemon + systemd + tick audit + `fleet-gate` harness (not Raft) |

**Current declared level: R3 advanced-partial** (R1 stable; R2 advanced-partial underneath).

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

## R3 advanced-partial gates

```bash
# One-shot acceptance (preferred CI / doctor path)
clojure -M:cli fleet-gate
# → pure loop + fence bootstrap + audit + resume + second-node skip
#   + recovery-pass + daemon + multi-tenant + aiueos path + packaging
bash deploy/validate-packaging.sh

# Manual drill
clojure -M:cli fleet-demo
clojure -M:cli fleet-run test/kototama/fixtures/kotoba-compiled-fact.wasm
clojure -M:cli fleet-list
clojure -M:cli fleet-resume <checkpoint-key> test/kototama/fixtures/kotoba-compiled-fact.wasm
clojure -M:cli fleet-recover test/kototama/fixtures/kotoba-compiled-fact.wasm
clojure -M:cli fleet-daemon test/kototama/fixtures/kotoba-compiled-fact.wasm \
  --interval-ms 200 --max-passes 3
```

| Landed | Not yet (deliberate) |
|---|---|
| lease / budget / tick / governor | Raft/Paxos multi-node consensus |
| checkpoint/restore EDN v1 | full aiueos fleet broker |
| disk store + optional B2 | |
| tender execute (`fleet-exec`) | |
| resume + recovery-pass | |
| bounded recovery daemon | |
| optional aiueos grants | |
| epoch fencing + fence-gated tender | |
| systemd oneshot+timer | |
| **tick audit journal** | |
| **`run-r3-gate!` / `fleet-gate` CLI** | |
| **lease heartbeat** (renew TTL per tick) | |
| **multi-tenant shared-store isolation** (gate) | |
| **optional aiueos grants** (`--use-aiueos` on fleet-run) | |
| **aiueos GRANT/DENY E2E** (fleet-exec + tender) | |
| **fleet-status / fleet-audit** CLI | |
| **CI fleet-gate + daemon dry-run + packaging validate** | |

Fencing is **not** distributed consensus — higher epoch wins on a shared store.
`bootstrap` / `resume` / `recovery-pass` call `claim-before-run` so only the
holding node executes tender. See `deploy/systemd/README.md` for install.

**Status meaning:** `advanced-partial` = all local/shared-store fleet surfaces work
under automated gate; multi-datacenter consensus is explicitly out of scope.

### Path to R3 `stable` (honest, no Raft)

1. ✅ `fleet-gate` in CI (`.github/workflows/ci.yml`)  
2. ✅ aiueos grant + deny E2E in test suite  
3. ✅ packaging static check (`deploy/validate-packaging.sh`) + daemon wrapper dry-run in CI  
4. Staging: enable systemd timer against real shared store (ops, not code)  
5. Still **do not** claim Raft/Paxos — “stable” = **ops-ready local/shared-store fleet**

When (1–3) stay green on main and (4) has been done once in staging, R3 may be
promoted to **stable** without inventing consensus.

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
