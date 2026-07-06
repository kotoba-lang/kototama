# kototama browser WASM AOT PoC

Minimal proof that a `.kotoba`-emitted WASM binary can run **directly in a
browser's own WebAssembly engine** (already-AOT-compiled machine code, no
interpreter step), wrapped as a WebComponent (`<kototama-wasm-run>`) instead
of hosted on the JVM.

## Files

- `demo.wasm` — byte-for-byte output of `kotoba wasm emit src/demo.kotoba`
  in `kotoba-lang/kotoba` (73 bytes, zero imports, exports `main() : i32`
  which computes `40 + 2`).
- `kototama-wasm-run.js` — a `customElements.define`-registered element.
  `connectedCallback` fetches the `src` attribute, runs
  `WebAssembly.instantiateStreaming`, calls the named export (default
  `main`), and renders the result into its shadow DOM.
- `index.html` — demo page: `<kototama-wasm-run src="./demo.wasm">`.
- `verify.mjs` — dependency-free smoke test (`node web/verify.mjs`) that
  instantiates `demo.wasm` with the JS engine's native `WebAssembly` API
  (same engine — V8 — a Chromium browser uses) and asserts `main() === 42`.
  This checks the AOT-execution claim only; it does not exercise the DOM/
  customElements wrapper (no DOM in plain Node).
- `kgraph.js` — a browser-side port of `kotoba-lang/kotoba`'s
  `src/kotoba/kgraph.clj` (the pure in-memory EAVT datom store) plus a
  minimal EDN reader/writer for the two shapes the `kgraph-*` host-import
  wire ABI carries (`[e a v]` datoms, `{:find [...] :where [...]}` queries).
  `kgraphHostImports(store, memoryBox)` implements the exact
  `(module "kotoba")` import surface `kotoba.wasm-exec/kgraph-host-functions`
  implements on the JVM side — same field names, same `(ptr, len[, out-ptr,
  out-cap])` convention.
- `demo-kgraph.wasm` — byte-for-byte output of
  `kotoba wasm emit src/demo_kgraph.kotoba --policy src/demo_kgraph_policy.edn`
  (219 bytes; declares the `kgraph_assert`/`kgraph_query` imports).
- `kototama-wasm-kgraph-demo.js` — a second custom element wiring
  `demo-kgraph.wasm` to `kgraphHostImports`, rendering the resulting store
  and query result into its shadow DOM.
- `verify-kgraph.mjs` — dependency-free smoke test (`node web/verify-kgraph.mjs`)
  mirroring kotoba-lang/kotoba's own
  `wasm-binary-runs-kgraph-round-trip-through-real-host-functions` JVM test
  byte-for-byte: asserts `[1 :name "Aoi"]`, queries
  `{:find [?v] :where [[1 :name ?v]]}`, and checks both the store and the
  query result (`[["Aoi"]]`) match what the JVM/Chicory path produces.

## Run it

```bash
cd web && python3 -m http.server 8123
# open http://localhost:8123/ in a browser
```

## Scope (honest R0)

- **Only the `kgraph-*` host-import surface is ported.** `kgraph_assert`/
  `kgraph_retract`/`kgraph_get_objects`/`kgraph_query` are implemented in
  `kgraph.js`; `kse`/`auth`/`llm`/`evm`/`btc`/`egress`/`chain` and friends
  (referenced in the wider kotoba/kototama design docs) have no browser
  implementation. A component that calls any other `(module "kotoba")`
  import will still fail to instantiate in this page.
- **`kgraph.js`'s EDN reader/writer is intentionally not general-purpose.**
  It handles exactly the shapes the `kgraph-*` ABI carries (vectors, maps
  with keyword keys, keywords, strings, integers, `?var` symbols) — no
  sets, no reader macros, no arbitrary nesting. Extend it if a demo needs
  more; don't assume it parses arbitrary EDN.
- **No capability/policy enforcement at this layer.** The `--policy`
  gate kotoba-lang/kotoba applies at `wasm emit` time is a build-time
  check; nothing here re-verifies it at load time. Don't treat this page as
  a sandboxed multi-tenant host.
- This is the first slice of the shift kototama's role is taking (see the
  ADR referenced from the top-level README): execution premise moves from
  "JVM hosts a Wasm interpreter" (`kotoba wasm run` + Chicory) to "the
  browser's own engine runs the AOT binary", with kototama supplying the
  WebComponent hosting shell. The JVM+Chicory path is unaffected and remains
  the compile-time/test-time proof in `kotoba-lang/kotoba`.
