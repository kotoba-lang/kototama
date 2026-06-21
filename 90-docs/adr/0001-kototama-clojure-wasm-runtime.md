# ADR-0001 — kototama: one Clojure-WASM runtime

Status: accepted (2026-06-21)
Repo: `com-junkawasaki/kototama`
Consolidates: `kotoba-clj` (com-junkawasaki/kotoba) + `kami-engine-clj` (com-junkawasaki/kami-engine)

## Context

Two Clojure/EDN-subset → WebAssembly compilers grew up apart:

- **kotoba-clj** — general-purpose core: kotoba-edn reader + wasm codegen, native
  wasmtime runner, Component-Model emission. `compile_str → wasm`.
- **kami-engine-clj** — the game layer: `GAME_PRELUDE` (vec/map/timer/vec3) + the
  `kami:engine` host ABI for games. `compile_str_with_prelude → wasm`.

Both read Clojure *as EDN* via the same reader (kotoba-edn). They overlap heavily and
should be one toolchain. The directive: *kami engine の基本を clj/datomic で、ゲーム
開発が全てできるように* — that needs **one canonical CLJ→WASM runtime**, and it must
run **in the browser** so editing CLJ recompiles + runs live (CodePen for CLJ games).

## Decision

**kototama is the single clojure-wasm-runtime.** It is the seam that unifies the two
compilers and adds the browser path.

```
              kotoba-edn  (reader — Clojure source IS read as EDN; single SSoT)
                   │
        ┌──────────┴───────────┐
   kotoba-clj              kami-engine-clj
   (general core)          (GAME_PRELUDE + kami:engine ABI)
        └──────────┬───────────┘
                   ▼
               kototama                 ← this repo
        compile_clj / compile_game
        ├─ native  (rlib; + `run` feature → wasmtime execute)
        └─ browser (cdylib → wasm; `compile` / `compile_game` via wasm-bindgen)
```

### 1. Layered, not forked

kototama depends on both crates (no code duplication) and re-exports them. The layering
is explicit: **kotoba-clj is the core**; **kami-engine-clj is a prelude+ABI layer** on
top. New game capability = extend the game layer; new language capability = extend the
core. One reader (kotoba-edn) underneath both.

API:
- `compile_clj(src) -> wasm` — general programs.
- `compile_game(src) -> wasm` — game `logic.clj` (GAME_PRELUDE + kami:engine ABI).
- (feature `run`) native execution via wasmtime, for CLI / tests / the native host.

### 2. The compiler runs in the browser

kototama builds to `wasm32` (cdylib). `wasm-pack build --target web` yields
`compile(src)` / `compile_game(src)` that **compile Clojure → wasm bytes inside the
page**. The browser then runs the emitted module via `WebAssembly.instantiate` — no
server, no native runtime. This is the engine for **B** (live CLJ editing on
network-isekai): edit `logic.clj` → `kototama.compile_game` → instantiate → tick.

Host imports (`kami:engine/*` — scene/physics/input/render/audio/time/random) are bound
on the JS/CLJS side (the browser host), mirroring the native `kami-script-runtime`.
Determinism (all-i64 ABI) carries over: same module, same host → same run.

### 3. Relationship to the rest

- **kami-script-runtime** (native host) keeps driving compiled modules; it can depend on
  kototama instead of kami-engine-clj directly (one toolchain).
- **network-isekai** uses the browser build: editor → `compile_game` → instantiate →
  the Model-A loop ticks it; render via `run_with_render_ir` (ADR-0002 there).
- **Datomic/kotoba**: forked `logic.clj` is content-addressed EDN; kototama is the
  compiler that turns a fork into a runnable module.

### Migration

1. ✅ kototama crate: facade over kotoba-clj + kami-engine-clj; native + wasm builds;
   `compile`/`compile_game` exported; round-trip tests (real wasm magic).
2. ▶ network-isekai: load kototama wasm, compile `logic.clj` on edit, instantiate, and
   drive it from the CLJS host (bind `kami:engine/*`) → live gameplay in the browser.
3. ▶ Point `kami-script-runtime` (native) at kototama so native + web share one
   compiler; deprecate direct kami-engine-clj/kotoba-clj entry points.
4. ▶ Fold the standalone kotoba-clj / kami-engine-clj crates behind kototama as their
   single public surface (keep them as internal layers).

## Consequences

- One CLJ→WASM toolchain for native and web; the browser becomes a full authoring +
  execution environment for CLJ games (no rebuild step, no server).
- The two existing crates stay as internal layers (no big code move now), reducing risk;
  consolidation is by composition first, code-merge later if warranted.
