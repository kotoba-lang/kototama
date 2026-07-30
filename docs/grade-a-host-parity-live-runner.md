# Grade A / T8.4 — live host runner (first slice)

- Status: partial (JVM live first slice)
- Date: 2026-07-31
- WBS: T8.4

## Context

`kotoba.lang.host-parity/run-conformance` scores the L5 matrix as **pure
data** (import × host → availability). T8.4 remaining work is **live host
runners** that actually link/call imports on real runtimes
(kototama tender, wasm-webcomponent).

## Decision — first slice

### JVM (`kototama.host-parity-live`)

Live-prove host-parity case ids that map to tender-supported imports:

| host-parity case id | import | proof |
|---|---|---|
| `:sha256-hex-all-available` | `:sha256-hex` | WAT guest + Chicory |
| `:clock-monotonic-all` | `:clock-monotonic` | WAT guest + Chicory |
| `:log-write-all-available` | `:log-write` | WAT guest + Chicory |
| `:gen-keypair-all-available` | `:gen-keypair` | WAT guest + Chicory |

API: `run-jvm-live` / `report`. Requires `wasm-tools` on PATH (same as
tender-test).

### Browser

Unchanged: `web/verify-actor-host.mjs` remains the live browser smoke for
sha256 + clock + log-write + session revoke against wasm-webcomponent.

## Non-claims

- Does not live-run full host-parity case table (pg/pool/llm/transport …)
- Does not implement Node live runner
- Does not replace pure matrix `run-conformance`
- Does not claim T8.4 complete

## Evidence

- `test/kototama/host_parity_live_test.clj`
- existing `web/verify-actor-host.mjs`

## Related

- Reliability WBS T8.4
- ADR-w6-t84-host-parity-critical-fixtures
- `docs/grade-a-runtime-differential.md` (host-free multi-engine, complementary)
