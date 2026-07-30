# Grade A / T8.4 — live host runners (JVM + Node)

- Status: partial (JVM + Node live; + transport r/w + pg-pool inject fail-closed)
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
| `:kagi-sign-jvm-available` | `:kagi-sign` | decision-aware inject (no Keychain) |
| `:transport-connect-jvm-inject-available` | `:transport-connect` | inject transport-provider; empty allowlist → 0 |
| `:tls-open-jvm-inject-available` | `:tls-open` | inject; invalid handle → 0 |
| `:transport-close-jvm-inject-available` | `:transport-close` | inject; unknown handle → -1 |
| `:transport-write-jvm-inject-available` | `:transport-write` | inject; unknown handle → -1 |
| `:transport-read-jvm-inject-available` | `:transport-read` | inject; unknown handle → -1 |
| `:transport-rw-jvm-loopback-success` | `:transport-write` + `:transport-read` | loopback ServerSocket echo; write+read 2 bytes |
| `:tls-server-end-point-jvm-available` | `:tls-server-end-point` | inject; non-TLS handle → -1 |
| `:pg-pool-open-jvm-inject-available` | `:pg-pool-open` | fail-closed inject provider → -1 |
| `:pg-pool-acquire-jvm-inject-available` | `:pg-pool-acquire` | fail-closed inject; unknown pool → -1 |
| `:pg-pool-health-jvm-inject-available` | `:pg-pool-health` | fail-closed inject; unknown pool → -1 |
| `:pg-pool-close-jvm-inject-available` | `:pg-pool-close` | fail-closed inject; unknown pool → -1 |

### Node / browser (`web/verify-host-parity-live.mjs`)

Node WebAssembly + wasm-webcomponent `actor-host.js` for:

| case id | import(s) |
|---|---|
| crypto/clock/log (matrix-aligned) | |
| `:random-bytes-all-available` | `:random-bytes` (actor-host) |
| `:http-post-node-inject-available` | `:http-post` + inject + allowlist |
| `:llm-infer-node-available` | `:llm-infer` + inject |
| `:kagi-sign-node-inject-available` | `:kagi-sign` + kagiSigner/decisions inject |

Loads sibling `../wasm-webcomponent` when present; else pinned CDN commit.
Emits `HOST_PARITY_LIVE_JSON:` for Clojure integration (`run-node-live`).

### Legacy

`web/verify-actor-host.mjs` — smaller smoke (sha256/clock/log + session revoke).

## Non-claims

- Not full host-parity table (pg component-link surface; remaining pool ops
  query/release/stats/drain not yet live-proven individually; open is
  fail-closed inject only — no live SCRAM/PG success path here)
- Not signed ops AOT Components (T8.3)
- Does not replace pure matrix `run-conformance`
- Not claim T8.4 complete
- JVM live corpus: 24 proofs; Node: 11 proofs
- Loopback success is plain TCP (not TLS mutual-auth / production network ABAC)
- Pool inject uses `fail-closed-inject-provider` (no live PostgreSQL)

## Evidence

- `test/kototama/host_parity_live_test.clj` (24 JVM live proofs)
- `node web/verify-host-parity-live.mjs` (11 Node proofs)

## Related

- Reliability WBS T8.4
- ADR-w6-t84-host-parity-critical-fixtures
