# kototama browser WASM AOT demo

Proof that a `.kotoba`-emitted WASM binary can run **directly in a
browser's own WebAssembly engine** (already-AOT-compiled machine code, no
interpreter step), wrapped as a WebComponent — via
[`kotoba-lang/wasm-webcomponent`](https://github.com/kotoba-lang/wasm-webcomponent),
not hosted on the JVM, and not this repo's own copy of that hosting code
anymore (see "History" below).

## Files

- `demo.wasm` — byte-for-byte output of `kotoba wasm emit src/demo.kotoba`
  in `kotoba-lang/kotoba` (73 bytes, zero imports, exports `main() : i32`
  which computes `40 + 2`).
- `demo-kgraph.wasm` — byte-for-byte output of
  `kotoba wasm emit src/demo_kgraph.kotoba --policy src/demo_kgraph_policy.edn`
  (219 bytes; declares the `kgraph_assert`/`kgraph_query` imports).
- `index.html` — imports `KotobaWasmElement` + `kgraph.js` from
  `kotoba-lang/wasm-webcomponent` (pinned commit, via jsdelivr's GitHub
  proxy) and defines `<kototama-wasm-run>` / `<kototama-wasm-kgraph-demo>`
  with two short `.define()` calls. No hosting logic lives in this repo.
- `verify.mjs` / `verify-kgraph.mjs` — dependency-free Node smoke tests for
  the two `.wasm` files above. Node has no flag-free way to `import()` an
  `https:` URL, so `verify-kgraph.mjs` fetches the pinned source and hands
  it to a `data:` URL instead (standard ESM, works on any Node ≥ 18).

## Run it

```bash
cd web && python3 -m http.server 8123
# open http://localhost:8123/ in a browser
```

## Scope (honest R0)

- **Only the `kgraph-*` host-import surface is ported** (in
  `kotoba-lang/wasm-webcomponent`'s `kgraph.js`). `kse`/`auth`/`llm`/`evm`/
  `btc`/`egress`/`chain` and friends (referenced in the wider kotoba/
  kototama design docs) have no browser implementation. A component that
  calls any other `(module "kotoba")` import will still fail to
  instantiate against this page.
- **No capability/policy enforcement at this layer.** The `--policy` gate
  kotoba-lang/kotoba applies at `wasm emit` time is a build-time check;
  nothing here re-verifies it at load time. Don't treat this page as a
  sandboxed multi-tenant host.
- This is the first slice of the shift kototama's role is taking (see the
  ADR referenced from the top-level README): execution premise moves from
  "JVM hosts a Wasm interpreter" (`kotoba wasm run` + Chicory) to "the
  browser's own engine runs the AOT binary", with kototama supplying the
  WebComponent hosting shell. The JVM+Chicory path is unaffected and
  remains the compile-time/test-time proof in `kotoba-lang/kotoba`.

## History

This directory used to carry its own `kototama-wasm-run.js` /
`kototama-wasm-kgraph-demo.js` / `kgraph.js` (the original PoC, see git
history / ADR-2607061630). That code has been extracted into
`kotoba-lang/wasm-webcomponent` so other repos can reuse it instead of
copying it; this directory is now a **consumer** of that library, not the
canonical source for the hosting code.
