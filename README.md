# kototama — clojure-wasm-runtime

One canonical **Clojure/EDN-subset → WebAssembly** runtime. Unifies the compilers that grew
up apart, adds the browser path so Clojure compiles + runs **in the page**, and hosts the
**artificial-organism / atproto-actor** runtime — actors authored in `kotoba-clj`, stored on
the `kotoba` Datom log, run as organisms by `kototama`, publishing to atproto apps.

```
kotoba-edn (reader)  →  kotoba-clj (core)  +  kami-engine-clj (game layer)   →  kototama
                                            +  actor (atproto/organism layer)     ├─ native (rlib, +run→wasmtime)
                                                                                  └─ browser (wasm: compile / compile_game / compile_actor)
```

- **kotoba-clj** — general Clojure/EDN → wasm core.
- **kami-engine-clj** — `GAME_PRELUDE` + `kami:engine` host ABI for games.
- **actor** — `ACTOR_PRELUDE` + `actor:host` ABI for **atproto actors + artificial organisms**
  (the etzhayyim `20-actors/*` lineage). The shared runtime — self-certifying did:key,
  kotoba commit-DAG heartbeat, charter-gate vocabulary, outward membrane — lives in
  `lib/actor/*.cljc` (portable, bb/JVM today; scalar slice compiles to wasm now). See
  [ADR-0002](90-docs/adr/0002-actor-organism-runtime-lib.md).
- **kototama** — the seam + the **in-browser compiler**: edit CLJ → compile to wasm →
  `WebAssembly.instantiate` → run. No server, no native runtime. This is what powers
  live CLJ-game editing on [network-isekai](https://github.com/gftdcojp/network-isekai)
  / isekai.network.

## Stack — design → store → live → publish

kototama is the **organism runtime** at the centre of a four-station loop. An actor is
*designed* in Clojure, *stores* its state on a content-addressed DB, *lives* as a compiled
organism, and *publishes* to the social web — each station a separate repo, composed:

```
  DESIGN                STORE                  LIVE                       PUBLISH
  kotoba-clj      →     kotoba           →     kototama             →     app-aozora / com.etzhayyim
  (CLJ/EDN subset)      (Datom log = DB)       (artificial organism)      (atproto destinations)

  author the actor      append-only            compile_actor → wasm       outward membrane → dry-run
  in lib/actor/*.cljc   commit-DAG (EAVT,      organism heartbeat:        posts; live broadcast (Council
  (gates/membrane/      content-addressed,     sense → fold → decide      Lv6+ + self-signed) → atproto
  heartbeat/didkey)     verify-chain)          → persist (idempotent)     records under app.aozora.* /
                        ↑ actor:host           ↑ actor:host               com.etzhayyim.* lexicons
                        log-read/append!       gen-keypair/sign           ↑ actor:host http-post
```

1. **DESIGN — `kotoba-clj`.** The actor is written in the Clojure/EDN subset (`lib/actor/*.cljc`):
   its charter gates, outward membrane, heartbeat fold, did:key identity. The same source the
   `ACTOR_PRELUDE` is distilled from — design and runtime are one language.
2. **STORE — `kotoba` as the DB substrate.** State is **not** a database row; it is an
   append-only, content-addressed **Datom log** (EAVT, Datomic-isomorphic, `verify-chain`
   tamper-evident). kototama binds to it through `actor:host` `log-read` / `log-append!`. The
   organism's whole life is a replayable commit-DAG.
3. **LIVE — `kototama` as the artificial organism.** `compile_actor` turns the CLJ into a wasm
   organism; `actor.heartbeat` runs the idempotent **sense → fold → decide → persist** beat
   (crash/re-run safe — an unchanged beat is a structural no-op). The organism self-generates
   and present-only-signs with its OWN did:key (`actor:host/gen-keypair`/`sign`); no server
   holds a key.
4. **PUBLISH — atproto apps (`app-aozora`, `com.etzhayyim.*`).** `actor.membrane` shapes a
   **dry-run** post when every gate holds. A **live** broadcast is governance-gated (Council
   Lv6+ + the actor's own signature, never a server key) and then `actor:host/http-post`s the
   record to an atproto destination — **app-aozora** (the appview/PDS) and the
   **com.etzhayyim.\*** lexicon namespace. The actor is the *bearer*; the apps are the *reach*.

Every hop is content-addressed and keyless-by-default: design is source, store is a CID chain,
live is a verifiable wasm CID, publish is a self-signed record. Same loop runs under bb/JVM
today and (its scalar slice) in wasm now.

## API

```rust
kototama::compile_clj(src)   -> Result<Vec<u8>, String>  // general program → wasm
kototama::compile_game(src)  -> Result<Vec<u8>, String>  // logic.clj (+GAME_PRELUDE, kami:engine ABI)
kototama::compile_actor(src) -> Result<Vec<u8>, String>  // actor logic (+ACTOR_PRELUDE, actor:host ABI)
```

### actor / organism layer (`lib/actor/`)

The shared runtime for atproto actors + artificial organisms — portable `.cljc`, runs today
under babashka:

```bash
bb --classpath lib lib/actor/test_actor.clj      # gates·membrane·heartbeat·didkey — 6 tests / 31 assertions
bb --classpath lib lib/actor/test_atproto.cljc   # atproto·identity            — 4 tests / 11 assertions
```

Implemented (ADR-0002 — promoted from the etzhayyim kanjō cell):
- `actor.gates` — charter-gate vocabulary (≥2 sources · cash≡0 · no-server-key · dry-run ·
  sim-only · **no-advice**) + `may-draft?` / `why-refused`. `no-advice?` / `assert-no-advice`
  reject advice/valuation/forecast text (EN on word boundaries — "ope-rating income" ≠ "rating";
  JA on substring).
- `actor.atproto` — AT-Protocol surface: `->json` · content-addressed `rkey` (FNV-1a) ·
  `profile-record` / `record` / `feed-post` / `at-uri` (parameterized by the actor's
  DID/handle/NSID). `feed-post` text crosses `gates/assert-no-advice` — the publish membrane.
- `actor.identity` — the KEY-MATERIAL half: Ed25519 `generate` (bb-verified) · `did-of` ·
  `public-record`. **Reuses `actor.didkey` for the did:key encoding** (no duplication). Private
  key never in git.
- `actor.didkey` — self-certifying `did:key` (Ed25519, multicodec 0xed01 + base58btc → `z6Mk…`)
  + `attest-message`. `actor.membrane` — draft / `build-live` self-publication seam.
  `actor.heartbeat` — idempotent-by-content commit-DAG beat. `actor:host` ABI (`host.edn`).

Tests: `test_actor.clj` (gates·membrane·heartbeat·didkey, 6/31) + `test_atproto.cljc`
(atproto·identity, 4/11) — all under bb.
- `actor:host` ABI (`lib/actor/host.edn`) — the crypto/net/storage capability boundary
  (`gen-keypair/sign/verify`, `sha256-hex`, `http-post`, `log-read/append!`, `now`) — the
  actor is the *bearer* of capability, never the holder of a server key.

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

- [ADR-0001](90-docs/adr/0001-kototama-clojure-wasm-runtime.md) — layered (core + game),
  composition-first consolidation, the browser compile→instantiate loop, and the migration
  that points kami-script-runtime + network-isekai at this one toolchain.
- [ADR-0002](90-docs/adr/0002-actor-organism-runtime-lib.md) — the **actor / artificial-organism**
  third layer: the design→store→live→publish loop above, the `actor:host` capability boundary,
  and the no-server-key / outward-broadcast governance boundaries.

## License

MIT.
