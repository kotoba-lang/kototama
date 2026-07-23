# kototama maturity ladder

Honest status of kototama as a **Wasm tender** (Solo5 pattern: host = tender,
guest = Wasm component). Not a marketing scorecard.

## Levels

| Level | Title | Status | What is real |
|---|---|---|---|
| **R0** | Contract / dry-run | **stable** | `kototama.contract` HostCaps + import surface; organism membrane refuses live publish |
| **R1** | Tender execution (JVM/Chicory) | **stable** | `kototama.tender` runs real `.wasm`; fuel + memory limits; aiueos adapter; session report; source lint; host-free + host-import fixtures from `kotoba wasm emit` |
| **R2** | Browser-native host parity | **advanced-partial** | parity matrix; 9/13 browser-linkable (`http-post` and `llm-infer` both real in a cross-origin-isolated tab via a Worker-hosted guest + SAB+Atomics bridge, `wasm-webcomponent` PR #8 / #11; ADR-2607230943's second wave -- `http-fetch`/`cbor-encode`/`json-encode`/`json-extract-field` -- is JVM-only so far, an honest gap); host-free web fixtures |
| **R3** | Fleet multi-tenant tender | **stable** | ops-ready local/shared-store fleet: fence+daemon+CI+staging-smoke (**not Raft**) |

**Current declared level: R3 stable** (R1 stable; R2 advanced-partial underneath).

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
| `kotoba-compiled-http-fetch.wasm` | `http_fetch` | `-1` (loopback URL refused by the SSRF denylist -- proves compiler↔tender linkage, not a live round trip) |
| `kotoba-compiled-cbor-encode.wasm` | `cbor_encode` | 5-byte CBOR map(1) `{"a":"b"}` |
| `kotoba-compiled-json-encode.wasm` | `json_encode` | `{"a":"b"}` |
| `kotoba-compiled-json-extract-field.wasm` | `json_extract_field` | `"v"` extracted from `{"k":"v"}` |

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
| llm-infer | yes | **yes** (Worker-hosted, via a caller-supplied proxy URL) | inject |
| http-post | yes | **yes** (Worker-hosted, cross-origin-isolated) | inject |
| http-fetch / cbor-encode / json-encode / json-extract-field (ADR-2607230943) | yes | **no** (honest gap -- no wasm-webcomponent port yet) | no |

Score today: **9/13** browser-linkable (was 9/9 before ADR-2607230943's
second wave added 4 JVM-only imports; see that section below). `http-post`
is real in a browser tab
as of `wasm-webcomponent` PR #8 (2026-07-16): `http-post-bridge.js`'s
`createSabHttpPostBridge` (SharedArrayBuffer+`Atomics.wait`, needs COOP/COEP
response headers) is wired into `actor-host.js`'s `http_post`, and the guest
itself runs inside a dedicated Worker (`kotoba-wasm-worker-host.js` /
`kotoba-wasm-worker-element.js`'s `<worker-http-post-demo>`) since
`Atomics.wait` is disallowed on the main/DOM thread — see
`examples/actor-host/worker-http-post.html` and
`test/browser/verify_http_post_browser.cljs` (real headless Chromium, real
HTTP round-trip, `npm run test:http-post-browser`). `llm-infer` is real in a
browser tab too as of `wasm-webcomponent` PR #11 (2026-07-16): it reuses the
SAME bridge instance as `http-post` (`kotoba-wasm-worker-host.js` passes one
bridge as both `httpPostBridge` and `llmInferBridge`), POSTing the raw
prompt bytes to a caller-supplied `llmInferUrl` and reading the raw
completion text back — deliberately NOT a direct call to a real LLM
provider, since a browser tab can never hold a provider API key without
shipping it to every visitor; `llmInferUrl` must point at a
developer-controlled proxy that holds any real credential server-side. See
`examples/actor-host/worker-llm-infer.html` and
`test/browser/verify_llm_infer_browser.cljs`
(`npm run test:llm-infer-browser`). An opt-in URL allowlist
(`:allowed-url-prefixes`/`allowedUrlPrefixes`) for `http-post` also landed
separately (`kototama` PR #32 / `wasm-webcomponent` PR #10). See "What we
deliberately do not claim" below for what's still not claimed (JSPI as the
default `http-post`/`llm-infer` wire).

### http-post in a real browser tab: landed 2026-07-16, after two false starts

This doc previously went through two wrong states on this exact point, in
both directions — worth recording so a future read of this file (or its
git history) doesn't re-litigate either mistake:

1. An earlier version claimed an `http-post-bridge.js` (SAB+COOP bridge)
   already existed and scored this 8/9 — it didn't; confirmed absent from
   `wasm-webcomponent`'s working tree and history at the time, corrected to
   7/9 (`kototama` PR #30).
2. That correction was itself about to go stale in the other direction:
   `wasm-webcomponent` PR #8 found that a *different*, independently
   written `http-post-bridge.js` had since been merged directly to that
   repo's `main` (bypassing PR review) with a real bug -- `TextDecoder.
   decode()` called on a view directly over the `SharedArrayBuffer`, which
   browsers reject outright -- meaning the bridge existed but had never
   actually completed a real request. PR #8 fixed that bug (`.slice()`
   instead of `.subarray()`), fixed a second real bug (a Worker-startup
   race between constructing the bridge and the first synchronous call),
   and proved the whole path end-to-end in real headless Chromium. `8/9` is
   the actual, verified state as of that PR, not a documentation-only
   correction like the first one was.

### llm-infer in a real browser tab: landed 2026-07-16, reusing http-post's bridge (9/9)

`wasm-webcomponent` PR #11 closed the remaining gap by generalizing, not
duplicating, PR #8's bridge: `kotoba-wasm-worker-host.js` constructs ONE
`createSabHttpPostBridge` instance and passes it to `actorHostImports` as
BOTH `httpPostBridge` and `llmInferBridge` — `llm_infer` POSTs the raw
prompt bytes through it to a caller-supplied `llmInferUrl` and reads the
raw completion text back, metered against `maxLlmInfers` the same in-band
`-1` way `http_post` already was (a real, previously-missing per-call
counter — only the pre-flight import-declaration count, always ≤1, was
checked before). `llmInferUrl` is intentionally NOT a built-in call to any
real LLM provider: a browser tab can never hold a provider API key without
shipping it to every visitor, so the actual provider call must live behind
a developer-controlled proxy the caller supplies the URL for.

Host library today: `actor-host.js`, `http-post-bridge.js`,
`kotoba-wasm-worker-host.js`, `kotoba-wasm-worker-element.js`, `kgraph.js`.

### Second wave: http-fetch/cbor-encode/json-encode/json-extract-field, JVM-only for now (2026-07-23, 9/13)

`com-junkawasaki/root` ADR-2607230943 added 4 new `actor:host` imports for
a future news-collecting fleet actor (kawaraban/cloud-itonami) that builds
CACAO token bytes and XRPC request/response bodies from a `.kotoba` guest:
`http-fetch` (GET, reusing `kotoba-core-contracts`' pre-existing
`http/fetch` id 205 rather than a new one), `cbor-encode`, `json-encode`,
and `json-extract-field` (the latter two share one `data/json` capability
id, same "one shared id for a small family" pattern `kami/engine` and
`graph/kotoba` already use). All 4 are implemented in `kototama.tender`
(JVM/Chicory) only -- no `wasm-webcomponent actor-host.js` port exists
yet, so the score moves from 9/9 to **9/13**, an honest new gap, not a
regression in what was already real. `http-fetch` reuses `http-post`'s
SSRF/DoS hardening (`blocked-http-post-destination?`/
`contract/url-allowed?`/connect+request timeouts) verbatim; `cbor-encode`/
`json-encode`/`json-extract-field` are pure computation with no
network/secret/write effect at all, so a browser/Node port (should one
ever be built) would need no SAB+Atomics bridge the way `http-post`/
`llm-infer` did -- just a JS port of the same flat key\<TAB\>value-pairs
parsing + encode/scan logic.

## R3 stable gates (shared-store fleet ops — not Raft)

```bash
# Full non-root staging substitute (preferred)
bash deploy/staging-smoke.sh

# Pieces
clojure -M:cli fleet-gate
bash deploy/validate-packaging.sh
clojure -M:cli fleet-status
clojure -M:cli fleet-audit

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
| optional aiueos grants + GRANT/DENY E2E | |
| epoch fencing + fence-gated tender | |
| systemd oneshot+timer + packaging validate | |
| tick audit + fleet-status / fleet-audit | |
| fleet-gate + CI (gate, daemon dry-run, packaging) | |
| **deploy/staging-smoke.sh** (non-root staging) | |

Fencing is **not** distributed consensus — higher epoch wins on a shared store.
`bootstrap` / `resume` / `recovery-pass` call `claim-before-run` so only the
holding node executes tender. See `deploy/systemd/README.md` for install.

**Status meaning:** `stable` = **ops-ready local/shared-store fleet** under automated
gates + staging-smoke. Multi-datacenter consensus remains out of scope.

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
