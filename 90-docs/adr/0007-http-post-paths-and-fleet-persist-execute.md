# ADR-0007: http-post paths + fleet disk/B2 + tender execute

- **Status:** accepted
- **Date:** 2026-07-10

## Decision

### http-post (R2)

In `wasm-webcomponent` `actor-host.js`:

1. **inject** `opts.httpPost` (Node/tests) — primary verified path
2. **SAB+COOP** `http-post-bridge.js` `createSabHttpPostBridge` for
   crossOriginIsolated browsers
3. **JSPI** detected via `httpPostCapabilities().jspi` — not default wire

### fleet persist + execute (R3)

- `kototama.fleet-store`: disk always; B2 when env creds present
- `kototama.fleet-exec`: `make-execute` → tender/run-report; `bootstrap-and-run!`
- CLI `fleet-run <wasm>`

## Status

R2 remains advanced-partial (llm still Node-only). R3 = skeleton+persist.
