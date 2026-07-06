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
- `actor-host-demo.wasm` — hand-assembled (`wasm-tools`) module importing
  `now`/`log_append`/`sha256_hex` (module `"kotoba"`) -- exercises
  `kototama.contract`'s `actor:host` ABI (partial), same fixture as
  `kotoba-lang/wasm-webcomponent`'s `examples/actor-host/`.
- `index.html` — **generated**, not hand-written (see `generate.cljs`
  below). Imports `KotobaWasmElement` + `kgraph.js` + `actor-host.js` from
  `kotoba-lang/wasm-webcomponent` (pinned commit, via jsdelivr's GitHub
  proxy) and defines three custom elements. No hosting logic lives in
  this repo.
- `generate.cljs` — an `nbb` (ClojureScript-on-Node) script that builds
  `index.html` from Hiccup EDN via
  [`kotoba-lang/html`](https://github.com/kotoba-lang/html)
  (`html.core/render`) and
  [`kotoba-lang/css`](https://github.com/kotoba-lang/css)
  (`css.core/style-node`), matching the org's `.cljc` runtime priority
  (kototama > cljs > nbb > jvm) applied to *authoring* this page. The
  `<script type="module">` WebComponent-wiring block is genuine
  executable JS, passed through verbatim via `[:hiccup/raw ...]`
  (html.core's own escape hatch for trusted markup). Regenerate after
  editing:
  ```bash
  cd web
  nbb --classpath "../../html/src:../../css/src" generate.cljs
  ```
  (adjust the `--classpath` entries to wherever your west checkout has
  `kotoba-lang/html` and `kotoba-lang/css`). The output is still a plain
  static file — no build step for a browser visiting the page, only for
  regenerating it.
- `verify.mjs` / `verify-kgraph.mjs` / `verify-actor-host.mjs` —
  dependency-free Node smoke tests for the three `.wasm` files above.
  Node has no flag-free way to `import()` an `https:` URL, so the latter
  two fetch the pinned source and hand it to a `data:` URL instead
  (standard ESM, works on any Node ≥ 18).

## Run it

```bash
cd web && python3 -m http.server 8123
# open http://localhost:8123/ in a browser
```

## Scope (honest R0)

- **`kgraph-*` and 4 of 8 `actor:host` imports are the only ported
  host-import surfaces** (in `kotoba-lang/wasm-webcomponent`'s
  `kgraph.js`/`actor-host.js`). `kse`/`auth`/`llm`/`evm`/`btc`/`egress`/
  `chain` and friends (referenced in the wider kotoba/kototama design
  docs) have no browser implementation, and neither do `actor:host`'s
  `gen-keypair`/`sign`/`verify`/`http-post` (a synchronous Wasm host
  import can't `await` the Web Crypto/`fetch` calls those would need —
  see `actor-host.js`'s header comment in wasm-webcomponent). A component
  that calls any other `(module "kotoba")` import will still fail to
  instantiate against this page.
- **`kgraph.js` has no capability/policy enforcement at load time**
  (the `--policy` gate kotoba-lang/kotoba applies at `wasm emit` time is
  a build-time check only). `actor-host.js` is the exception: it
  re-verifies `HostCaps`/`RuntimeLimits` pre-flight and per call, mirroring
  `kototama.tender`'s (JVM/Chicory) enforcement. Don't treat this page as
  a sandboxed multi-tenant host in general.
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
