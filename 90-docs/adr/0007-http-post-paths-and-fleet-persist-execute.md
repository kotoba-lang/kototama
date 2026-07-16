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

## Addendum (2026-07-16): the SAB+COOP `http-post` bridge was never built

Re-auditing `wasm-webcomponent` while working on kototama's own maturity
docs found that item 2 above (**SAB+COOP** `http-post-bridge.js` /
`createSabHttpPostBridge`) and item 3 (`httpPostCapabilities().jspi`) do
not exist anywhere in that repo's working tree or git history — confirmed
by direct search, not just a stale grep. `actor-host.js`'s own current
header comment is accurate and was already honest about this: `http-post`
is "the one import that's fundamentally unavailable" in a browser tab today
and is deliberately absent from the exported import object (a guest
declaring it fails to link with a clear error), not silently
degraded. Only item 1 (Node/test `opts.httpPost` inject) is real. This
Decision's http-post section described a plan as already landed; it wasn't
-- `docs/maturity.md` and the top-level `README.md` repeated the same
"8/9 browser-linkable, http-post via inject / SAB+COOP bridge" claim and
have been corrected to 7/9 (`llm-infer` Node-inject only, `http-post` not
implemented in-browser) in the same pass. Building the actual bridge (or
adopting JSPI once broadly shipped) remains real, not-yet-scheduled future
work -- tracked as a to-do, not re-claimed as done here.
