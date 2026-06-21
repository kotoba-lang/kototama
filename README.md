# kototama — clojure-wasm-runtime

One canonical **Clojure/EDN-subset → WebAssembly** runtime. Unifies the two compilers
that grew up apart, and adds the browser path so Clojure compiles + runs **in the page**.

```
kotoba-edn (reader)  →  kotoba-clj (core)  +  kami-engine-clj (game layer)  →  kototama
                                                                                ├─ native (rlib, +run→wasmtime)
                                                                                └─ browser (wasm: compile / compile_game)
```

- **kotoba-clj** — general Clojure/EDN → wasm core.
- **kami-engine-clj** — `GAME_PRELUDE` + `kami:engine` host ABI for games.
- **kototama** — the seam + the **in-browser compiler**: edit CLJ → compile to wasm →
  `WebAssembly.instantiate` → run. No server, no native runtime. This is what powers
  live CLJ-game editing on [network-isekai](https://github.com/gftdcojp/network-isekai)
  / isekai.network.

## API

```rust
kototama::compile_clj(src)  -> Result<Vec<u8>, String>   // general program → wasm
kototama::compile_game(src) -> Result<Vec<u8>, String>   // logic.clj (+GAME_PRELUDE, kami:engine ABI)
```

Browser (wasm-bindgen, after `wasm-pack build --target web`):

```js
import init, { compile, compile_game } from "./pkg/kototama.js";
await init();
const wasm = compile_game(logicCljSource);          // Uint8Array of a real wasm module
const { instance } = await WebAssembly.instantiate(wasm, hostImports);
// drive instance.exports (tick / on-event …) from the JS/CLJS host (binds kami:engine/*)
```

## Build

```bash
cargo test --target <native>            # facade + both compilers (real wasm magic)
cargo build --features run              # native + wasmtime execution
wasm-pack build --target web            # → pkg/ : in-browser compiler
```

## Design

See [ADR-0001](90-docs/adr/0001-kototama-clojure-wasm-runtime.md): layered (core +
game), composition-first consolidation, the browser compile→instantiate loop, and the
migration that points kami-script-runtime + network-isekai at this one toolchain.

## License

MIT.
