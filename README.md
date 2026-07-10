# kototama

**Role: the `.kotoba` WASM runtime** — tender / host that **runs** guest
modules produced by the **kotoba language** (`kotoba wasm emit`).  
It does **not** own the language or the AOT compiler.

```text
kotoba   = language   (.kotoba → check → wasm emit → guest.wasm)  ← kotoba-lang/kotoba
kototama = runtime    (host & run that .wasm)                     ← this repo
aiueos   = OS / broker (decides grants; tender only enforces)
```

Stack vocabulary: [ADR-2607022400](https://github.com/com-junkawasaki/root/blob/main/90-docs/adr/2607022400-kototama-unikernel-tender-runtime-vocabulary.md).

**Not to be confused with [`kotoba-lang/kotodama`](https://github.com/kotoba-lang/kotodama)**
— both names romanize the same word (言霊/言魂, "word-spirit"; "kototama" and
"kotodama" are two real, independently-attested readings of the same kanji,
not a typo), but the repos are unrelated in scope: `kotodama` is the generic
functional-organism runtime (organism/ReAct machinery injected into any
actor, extracted from `etzhayyim/kototama`'s UNSPSC-specific predecessor);
this repo is the Wasm **execution** tender described below.
ADR-2607050900 audited the naming overlap and found no functional
duplication — just an undocumented spelling split, which this note closes.

## Role (detail)

In the `kotoba → kototama → aiueos` stack
([ADR-2607022400](https://github.com/com-junkawasaki/root/blob/main/90-docs/adr/2607022400-kototama-unikernel-tender-runtime-vocabulary.md)),
kototama is the **Wasm tender runtime**: it hosts the Wasm guests that
**kotoba** (the language) compiles (`.kotoba` → `kotoba wasm emit` → AOT `.wasm`),
under capability grants that `aiueos` decides. Solo5's *tender* pattern —
kototama hosts, the component is guest. **Do not reimplement the compiler here.**

**Compile guests with [`kotoba-lang/kotoba`](https://github.com/kotoba-lang/kotoba).**

### Runtime priority (ADR-2607100100 / ADR-2607102200 addendum 3)

**First path is not JVM/Chicory.** App/runtime order is:

```
kotoba wasm AOT  >  clojurewasm  >  ClojureScript  >  nbb
(JVM / Chicory is demoted — last-resort / explicit compat only)
```

| Path | Status | Notes |
|---|---|---|
| **`.kotoba` → AOT `.wasm` on native WebAssembly** | **first** | browser/Node via [`wasm-webcomponent`](https://github.com/kotoba-lang/wasm-webcomponent) (extracted from this repo's `web/`) |
| **clojurewasm** | next when FFI allows | host-import surface still limited (upstream Phase-16); revisit for host-import guests |
| **ClojureScript host wire** | when native WASM + CLJS host imports | e.g. portable `kotoba.kami-host` |
| **`kototama.tender` (JVM/Chicory)** | **demoted compat / CI** | landed historically (ADR-2607022900 / 2607062330); keep for bit-exact fixtures, not the design premise |

### Compat tender (Chicory) — still present, not primary

`src/kototama/tender.clj` wires every `kototama.contract` `actor:host` import
to a Chicory `HostFunction` with pre-flight + per-call grant checks,
`RuntimeLimits`, memory limits, and fuel. Useful as a verification harness
against real Wasm bytes — **not** what "use kototama" means for new work.
Language-repo `kotoba wasm run` is the same class of **compat bootstrap**.

## Contract Surface

- `src/kototama/contract.cljc` defines the `actor:host` import surface,
  `HostCaps`, `RuntimeLimits`, grant normalization, and import validation
  (pure data, zero-dep, no execution — see `kototama.tender` for that).
- `src/kototama/tender.clj` is the Chicory-based execution runtime (see
  above). `:clj`-only, matching `com.dylibso.chicory`'s own JVM-only
  nature; pulls in `com.dylibso.chicory/{wasm,runtime}` and
  `kotoba-lang/ed25519` (`kototama.contract` itself stays free of them).
- `src/kototama/aiueos_adapter.clj` closes the "aiueos decides, kototama
  enforces" loop for real: calls `aiueos.cli/command-result` (a real
  `io.github.kotoba-lang/aiueos` dependency, in-process — not the
  `bb decide` subprocess `aiueos.decide` also exposes for hosts that
  aren't already JVM/Clojure) and translates the actual grant/deny
  decision into a `kototama.contract/host-caps` value. `kototama.tender`
  never computes a grant itself either way (ADR-2607022700's rule); this
  only removes the need for every caller to hand-build `HostCaps` from a
  decision aiueos already made. Covers the subset of `actor:host` imports
  aiueos's own default kernel capabilities recognize (`log-write`/
  `clock-monotonic`/`random-bytes`) — `gen-keypair`/`sign`/`verify`/
  `sha256-hex`/`http-post`/`log-read` have no aiueos-kernel-capability
  counterpart and still take caller-supplied `HostCaps`, same as before.
- `lib/kototama/*.cljc` contains the portable organism/cell runtime:
  gates, membrane, heartbeat, did:key, atproto shaping, and identity helpers.
- `lib/actor/publish.bb` is the shared actor publish runner.

## Quick start (runtime)

```bash
# Guest must already be AOT-compiled by the language (kotoba):
#   kotoba wasm emit cell.kotoba --package-lock L -o cell.wasm

clojure -M:cli run path/to/guest.wasm --grant …     # execute (tender / CLI)
clojure -M:cli lint path/to/guest.kotoba            # emit-pitfall lint only (no compile)
clojure -M:cli inspect path/to/guest.wasm
clojure -M:doctor
node web/verify-host-free.mjs                       # first path: native WebAssembly engine
```

## Browser / native WASM host (`web/`)

[ADR-2607061630](https://github.com/com-junkawasaki/root/blob/main/90-docs/adr/2607061630-kototama-browser-wasm-aot-webcomponent.md)
and ADR-2607100100: the **first** execution premise is the host's native
WebAssembly engine on already-AOT `.wasm` from **kotoba** — not "JVM hosts a
Wasm interpreter". Hosting library:
[`kotoba-lang/wasm-webcomponent`](https://github.com/kotoba-lang/wasm-webcomponent)
(`web/` is a consumer). See `web/README.md` for R0 scope.  
`kotoba wasm run` and `kototama.tender` Chicory paths are **compat / CI**.

## Maturity

**Current level: R3 advanced-partial** (R1 stable; R2 advanced-partial).
Ladder and gates: [`docs/maturity.md`](docs/maturity.md).

| Level | Status |
|---|---|
| R0 contract / dry-run | stable |
| R1 tender (compat: JVM/Chicory) | stable as **compat suite** — session report, host-free guests, emit lint, CLI (not the first path) |
| **R2 browser / native WASM host** | **first product path** (advanced-partial) — AOT `.wasm` via wasm-webcomponent; 8/9 linkable; host-free web fixtures |
| **R3 fleet multi-tenant** | **advanced-partial** — disk/B2 + fence-gated tender + daemon + systemd + tick audit + `fleet-gate` (not Raft) |

```bash
clojure -M:doctor                                    # R0–R3 snapshot
clojure -M:cli parity                                # R2 import matrix
clojure -M:cli fleet-gate                            # R3 acceptance harness (CI)
bash deploy/validate-packaging.sh                    # systemd oneshot+timer static gate
clojure -M:cli fleet-demo                            # R3 pure loop demo
clojure -M:cli fleet-run path/to/guest.wasm          # tender execute + disk checkpoint
clojure -M:cli lint  path/to/guest.kotoba            # lint only — compile with kotoba
clojure -M:cli run     path/to/guest.wasm            # RUNTIME: run AOT guest
node web/verify-host-free.mjs                        # R2 host-free under browser Wasm
```

Host-free pure guests are first-class on **native WASM** (browser/Node via
wasm-webcomponent / `web/`) and still verified on the demoted JVM tender
(`fact(5)=120`, peak-cells `@4096→240`).

## Test

```bash
clojure -M:test
bb --classpath lib lib/kototama/test_actor.clj
bb --classpath lib lib/kototama/test_atproto.cljc
```

`clojure -M:test` is the default repository gate (contract + tender + aiueos
adapter + guest lint + maturity fixtures). `kototama.tender-test`
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
