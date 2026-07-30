# Grade A / T8.4 — live host runners (JVM + Node)

- Status: partial (JVM expanded + Node live + random-bytes + http/llm + transport + kagi-sign inject)
- Date: 2026-07-31
- WBS: T8.4

## Context

`kotoba.lang.host-parity/run-conformance` scores the L5 matrix as **pure
data**. T8.4 requires **live host runners** that link/call imports on real
runtimes (kototama tender, wasm-webcomponent).

## Decision

### JVM (`kototama.host-parity-live/run-jvm-live`)

WAT guests + Chicory tender for:

| case id | import(s) | proof |
|---|---|---|
| `:sha256-hex-all-available` | `:sha256-hex` | WAT + Chicory |
| `:clock-monotonic-all` | `:clock-monotonic` | WAT + Chicory |
| `:log-write-all-available` | `:log-write` | WAT + Chicory |
| `:log-read-all-available` | `:log-read` | WAT + Chicory |
| `:gen-keypair-all-available` | `:gen-keypair` | WAT + Chicory |
| `:sign-all-available` | `:gen-keypair` + `:sign` | WAT + Chicory |
| `:verify-all-available` | gen+sign+`:verify` | WAT + Chicory |
| `:cbor-encode-jvm-live` | `:cbor-encode` | pure allowlist host |
| `:json-encode-jvm-live` | `:json-encode` | pure allowlist host |
| `:random-bytes-all-available` | `:random-bytes` | WAT + Chicory (tender) |
| `:http-post-jvm-available` | `:http-post` | loopback fail-closed (link+SSRF) |
| `:llm-infer-jvm-available` | `:llm-infer` | injected infer-fn, no network |
| `:transport-connect-jvm-inject-available` | `:transport-connect` | inject transport-provider; empty allowlist → 0 |
| `:kagi-sign-jvm-available` | `:kagi-sign` | injected signer + grant decision |

### Node / browser (`web/verify-host-parity-live.mjs`)

Node WebAssembly + wasm-webcomponent `actor-host.js` for:

| case id | import(s) |
|---|---|
| crypto/clock/log (matrix-aligned) | |
| `:random-bytes-all-available` | `:random-bytes` (actor-host) |
| `:http-post-node-inject-available` | `:http-post` + inject + allowlist |
| `:llm-infer-node-available` | `:llm-infer` + inject |

Loads sibling `../wasm-webcomponent` when present; else pinned CDN commit.
Emits `HOST_PARITY_LIVE_JSON:` for Clojure integration (`run-node-live`).

### Legacy

`web/verify-actor-host.mjs` — smaller smoke (sha256/clock/log + session revoke).

## Non-claims

- Not full host-parity table (pg/pool…; kagi-sign inject only, no OS Keychain)
- Not signed ops AOT Components (T8.3)
- Does not replace pure matrix `run-conformance`
- Not claim T8.4 complete
- JVM live corpus: 14 proofs (+ kagi-sign inject)

## Evidence

- `test/kototama/host_parity_live_test.clj` (14 JVM live proofs)
- `node web/verify-host-parity-live.mjs` (10 Node proofs)

## Related

- Reliability WBS T8.4
- ADR-w6-t84-host-parity-critical-fixtures
