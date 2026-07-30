# Grade A / T8.4 — live host runner (JVM expand)

- Status: partial (JVM live expanded; browser first smoke unchanged)
- Date: 2026-07-31
- WBS: T8.4

## Context

`kotoba.lang.host-parity/run-conformance` scores the L5 matrix as **pure
data** (import × host → availability). T8.4 remaining work is **live host
runners** that actually link/call imports on real runtimes
(kototama tender, wasm-webcomponent).

## Decision — JVM expand (second slice)

### JVM (`kototama.host-parity-live`)

Live-prove host-parity case ids + pure encode imports on tender:

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

API: `run-jvm-live` / `report`. Requires `wasm-tools` on PATH (same as
tender-test). Multi-import guests use `:imports` vector; grants cover the set.

### Browser

Unchanged: `web/verify-actor-host.mjs` remains the live browser smoke for
sha256 + clock + log-write + session revoke against wasm-webcomponent.

## Non-claims

- Does not live-run full host-parity case table (pg/pool/llm/http-post/kagi …)
- Does not implement Node live runner
- Does not replace pure matrix `run-conformance`
- Does not claim T8.4 complete
- `:random-bytes` is in host-parity matrix but **not** in tender import table yet

## Evidence

- `test/kototama/host_parity_live_test.clj` (9 live proofs)
- existing `web/verify-actor-host.mjs`

## Related

- Reliability WBS T8.4
- ADR-w6-t84-host-parity-critical-fixtures
- `docs/grade-a-runtime-differential.md` (host-free multi-engine, complementary)
