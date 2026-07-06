# kototama

## Role

In the `kotoba-lang → kototama → aiueos` stack
([ADR-2607022400](https://github.com/com-junkawasaki/root/blob/main/90-docs/adr/2607022400-kototama-unikernel-tender-runtime-vocabulary.md)),
kototama is the **Wasm execution runtime**: it hosts the Wasm components that
`kotoba-lang` compiles, under capability grants that `aiueos` (the OS/broker
layer) decides. That ADR adopts Solo5's *tender* pattern — kototama as
tender, the Wasm component it runs as guest — and
[ADR-2607022900](https://github.com/com-junkawasaki/root/blob/main/90-docs/adr/2607022900-aiueos-chicory-jvm-native-adapter.md)
decided the tender's execution layer is **JVM/Clojure via
`com.dylibso.chicory`**, not Rust/wasmtime.

**`kototama.tender` (`src/kototama/tender.clj`) is that execution layer,
landed** ([ADR-2607062330](https://github.com/com-junkawasaki/root/blob/main/90-docs/adr/2607062330-kototama-tender-chicory-execution-runtime.md)):
every one of `kototama.contract`'s 8 `actor:host` imports (`gen-keypair`/
`sign`/`verify`/`sha256-hex`/`http-post`/`log-read`/`log-write`/`clock-monotonic`) is
wired to a real Chicory `HostFunction` that only performs its effect when
`contract/validate-import-surface` says the caller's `HostCaps` grant it —
checked pre-flight (before any `Instance` is built) and again per call
(defense in depth). `RuntimeLimits` (`:max-http-posts`/`:max-log-*-bytes`)
are enforced by kototama itself (Chicory has no native per-category call
counter) as an in-band `-1` a well-behaved guest can see and back off
from — distinct from a `:grants` violation, which is a structural
authority breach and hard-aborts the call instead. `:max-memory-pages` is
enforced too, via Chicory's STABLE `withMemoryLimits` API (not the
:unsafe fuel-listener hook) — independent of which imports are granted,
applied to any guest that declares a memory section. A per-instruction
fuel listener traps a runaway/looping guest. Verified against real Wasm
binaries (`wasm-tools`-assembled WAT fixtures through the actual Chicory
`Parser`/`Instance` pipeline, not mocked), including cross-checking a
real `sign`→`verify` round trip and `sha256-hex` against a known digest.

## Contract Surface

- `src/kototama/contract.cljc` defines the `actor:host` import surface,
  `HostCaps`, `RuntimeLimits`, grant normalization, and import validation
  (pure data, zero-dep, no execution — see `kototama.tender` for that).
- `src/kototama/tender.clj` is the Chicory-based execution runtime (see
  above). `:clj`-only, matching `com.dylibso.chicory`'s own JVM-only
  nature; pulls in `com.dylibso.chicory/{wasm,runtime}` and
  `kotoba-lang/ed25519` (`kototama.contract` itself stays free of them).
- `lib/kototama/*.cljc` contains the portable organism/cell runtime:
  gates, membrane, heartbeat, did:key, atproto shaping, and identity helpers.
- `lib/actor/publish.bb` is the shared actor publish runner.

## Browser WASM AOT demo (`web/`)

[ADR-2607061630](https://github.com/com-junkawasaki/root/blob/main/90-docs/adr/2607061630-kototama-browser-wasm-aot-webcomponent.md)
starts shifting kototama's execution premise from "JVM hosts a Wasm
interpreter" (`kotoba wasm run` + `com.dylibso.chicory` in
`kotoba-lang/kotoba`) toward "the browser's own engine runs the
already-AOT-compiled binary directly", with kototama supplying the hosting
shell as a WebComponent instead of a JVM process. The hosting code itself
(`KotobaWasmElement`, `kgraph.js`) has since been extracted into
[`kotoba-lang/wasm-webcomponent`](https://github.com/kotoba-lang/wasm-webcomponent)
so other repos can reuse it — `web/` is now a **consumer** of that library,
not its canonical source. It defines `<kototama-wasm-run>` (a zero-import
`.kotoba`-emitted `.wasm` module) and `<kototama-wasm-kgraph-demo>` (a
module that calls `kgraph-assert!`/`kgraph-query`, backed by the library's
browser-side port of kotoba's in-memory EAVT datom store) — verified
against the exact same demo and expected values as kotoba-lang/kotoba's
own JVM/Chicory test. See `web/README.md` for the
remaining honest R0 scope (only `kgraph-*` is ported, not `kse`/`auth`/`llm`/
etc.; no capability/policy re-enforcement at load time). The JVM+Chicory
path in kotoba-lang/kotoba is unaffected and remains the compile-time/
test-time proof; this is additive, not a replacement.

## Test

```bash
clojure -M:test
bb --classpath lib lib/kototama/test_actor.clj
bb --classpath lib lib/kototama/test_atproto.cljc
```

`clojure -M:test` is the default repository gate. `kototama.tender-test`
shells out to the `wasm-tools` CLI (Bytecode Alliance) to assemble its WAT
fixtures into real Wasm bytes at test time — install it (`cargo install
wasm-tools` or your package manager) if it isn't already on `PATH`. The
babashka commands cover the current organism runtime helpers when `bb` is
available.

## Migration

The old Rust wrapper around `kotoba-clj` and `kami-engine-clj` has been removed
from this repo. Historical native implementation details remain available in git
history. New behavior should land first as CLJC/EDN contracts; native hosts can
adapt those contracts in their own repositories when needed.

See [`docs/rust-migration.md`](docs/rust-migration.md).

## License

MIT.
