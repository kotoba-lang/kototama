# Grade A / T8.4 — live host runners (JVM + Node)

- Status: partial (JVM expanded + Node/browser actor-host live)
- Date: 2026-07-31
- WBS: T8.4

## Context

`kotoba.lang.host-parity/run-conformance` scores the L5 matrix as **pure
data**. T8.4 requires **live host runners** that link/call imports on real
runtimes (kototama tender, wasm-webcomponent).

## Decision

### JVM (`kototama.host-parity-live/run-jvm-live`)

WAT guests + Chicory tender for:

| case id | import(s) |
|---|---|
| `:sha256-hex-all-available` | `:sha256-hex` |
| `:clock-monotonic-all` | `:clock-monotonic` |
| `:log-write-all-available` / `:log-read-all-available` | log |
| `:gen-keypair-all-available` / `:sign-all-available` / `:verify-all-available` | crypto |
| `:cbor-encode-jvm-live` / `:json-encode-jvm-live` | pure encode |

### Node / browser (`web/verify-host-parity-live.mjs`)

Node WebAssembly + wasm-webcomponent `actor-host.js` for:

| case id | import(s) |
|---|---|
| same crypto/clock/log as JVM (where matrix lists them) | |
| `:random-bytes-all-available` | `:random-bytes` (actor-host; not yet in tender table) |

Loads sibling `../wasm-webcomponent` when present; else pinned CDN commit.
Emits `HOST_PARITY_LIVE_JSON:` for Clojure integration (`run-node-live`).

### Legacy

`web/verify-actor-host.mjs` — smaller smoke (sha256/clock/log + session revoke).

## Non-claims

- Not full host-parity table (pg/pool/llm/transport…)
- Not signed ops AOT Components (T8.3)
- Not claim T8.4 complete

## Evidence

- `test/kototama/host_parity_live_test.clj`
- `node web/verify-host-parity-live.mjs`

## Related

- Reliability WBS T8.4
- ADR-w6-t84-host-parity-critical-fixtures
