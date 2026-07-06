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

## Run it

```bash
cd web && python3 -m http.server 8123
# open http://localhost:8123/ in a browser
```

## Scope (honest R0)

- **Zero-import modules only.** The host-import ABI (`kgraph-assert!` etc.
  — the same wire contract `kotoba.wasm-exec` implements on the JVM side in
  `kotoba-lang/kotoba`) has **no browser implementation here**. A component
  that calls any `(module "kotoba")` import will fail to instantiate in this
  page today.
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
