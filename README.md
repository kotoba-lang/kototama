# kototama

`kototama` is the Kotoba **runtime** — 言霊, the spirit of the word when it
acts. It validates, budgets, **runtime-links**, and runs artifacts that
[`amu`](https://github.com/kotoba-lang/amu) wove. It does **not** own the
language, the compiler, grant policy, or fleet placement.

Solo5 called this role a *tender*. That word is a nautical metaphor for the
attendant host. It is not the product name. The product is kototama.
Hosts live in sibling repos so the dependency direction stays checkable
(ADR-2607266000 / ADR-2608139980).

```text
kotoba     言葉      language
amu        編む      compiler — project link, one closed cloth
abi        経        WIT / admission contract (no implementation)
kototama   言霊      runtime — host, budget, runtime link
aiueos     あいうえお  authority (decides grants; supplies named providers)
murakumo   叢雲      cluster control plane (places and observes, never grants)
sahai      差配      T6 placement loop (leases/checkpoints/fencing)
```

## Hosts (all kototama, not sibling products)

| Host | Repo | What it runs |
|---|---|---|
| browser / native Wasm | [`wasm-webcomponent`](https://github.com/kotoba-lang/wasm-webcomponent) | AOT `.wasm` on the host engine. First path. Extracted so other repos can adopt it |
| native ELF | [`kototama-native`](https://github.com/kotoba-lang/kototama-native) | `kototama.native.executor` — sealed native artifacts under a capability gate |
| Component engine | [`kototama-component`](https://github.com/kotoba-lang/kototama-component) | already-admitted Components via Wasmtime micro-TCB / jco / workerd |
| JVM compat | `kototama.tender` (this repo) | Chicory harness. Not the first path |

`provider` is the hand kototama may bind after aiueos grants. It is not
shipped inside this repo.

See [`docs/hosts.md`](docs/hosts.md).

Stack vocabulary: [ADR-2607022400](https://github.com/com-junkawasaki/root/blob/main/90-docs/adr/2607022400-kototama-unikernel-tender-runtime-vocabulary.md)
(role word *tender*), product naming [ADR-2608139980](https://github.com/com-junkawasaki/root/blob/main/90-docs/adr/2608139980-amu-weaves-kototama-binds.edn).
Stack topology, the deliberate `kototama → aiueos` dependency direction, the
browser-parity gate for new `actor:host` imports, and the canonical
capability-schema plan: [docs/0009](docs/0009-stack-topology-parity-gate-capability-schema.md)
(root authority: `com-junkawasaki/root` ADR-2607241100).

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

In the `kotoba → amu → kototama → aiueos` stack, kototama hosts the guests
that **amu** wove, under capability grants that `aiueos` decides. The
attendant-host pattern (Solo5 *tender*) still holds: kototama hosts, the
artifact is guest. **Do not reimplement the compiler here.** Project linking
(many sources → one cloth) is amu. Runtime linking (imports → granted
providers) is kototama.

The current portable contract is WIT plus the WebAssembly Component Model on
WASI 0.3, versioned in [`kotoba-lang/abi`](https://github.com/kotoba-lang/abi).
Kototama verifies and composes compiler-produced component worlds;
sync functions remain sync, while async functions/futures/streams require
explicit cancellation and bounded lifetime/item/byte budgets. A host accepts
only declared WIT imports and a verified, scoped aiueos grant; it must not turn
a WASI import into ambient filesystem, network, clock, random, environment, or
process access. Kototama is the product-facing engine API; its initial engine
adapter may embed a mature native engine such as Wasmtime. Boot, isolation,
device adapters, and root-key use remain a small native micro-TCB. A nested
Wasm engine is not the product path: it adds an engine but cannot replace the
outer native engine that starts and isolates it. See
[`ADR-2607252500`](https://github.com/com-junkawasaki/root/blob/main/90-docs/adr/2607252500-kotoba-wasm-component-first-execution-boundary.edn).

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

The workerd path is intentionally a Core-Wasm compatibility adapter because
workerd does not instantiate Component Model binaries directly. The production
JavaScript boundary in `workerd/kototama-core-host.mjs` compares the module's
actual imports with the exact admitted manifest, exposes no ambient WASI, and
rechecks authorization on every named provider call. `npm run test:workerd`
runs that boundary and a real provider call inside a pinned workerd binary.
This reuses the ABI's capability semantics; it does not claim that a Component
binary and a Core module are the same artifact.

### Compat tender (Chicory) — still present, not primary

`src/kototama/tender.clj` wires every `kototama.contract` `actor:host` import
to a Chicory `HostFunction` with pre-flight + per-call grant checks,
`RuntimeLimits`, memory limits, and fuel. Useful as a verification harness
against real Wasm bytes — **not** what "use kototama" means for new work.
Language-repo `kotoba wasm run` (`kotoba.wasm-exec` in `kotoba-lang/kotoba`)
is the same class of **compat bootstrap**, but a deliberately separate
implementation, not shared code: it exists so that repo's own emit path
can prove its own output runs, in its own test suite, without a cross-repo
test dependency on kototama. See com-junkawasaki/root ADR-2607182200 for
the full cross-repo dependency graph and why these two JVM/Chicory paths
are not considered duplicated work to consolidate.

## Contract Surface

- `src/kototama/contract.cljc` defines the `actor:host` import surface,
  `HostCaps`, `RuntimeLimits`, grant normalization, and import validation
  (pure data, zero-dep, no execution — see `kototama.tender` for that).
- `src/kototama/tamaki_contract.cljc` independently admits Tamaki's versioned
  capability envelope before `HostCaps` construction. Business capabilities
  are not authority: imports, grants, limits, effect policy, ABI version, and
  network allowlists are rechecked at this boundary.
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

## Published site (`index.html`, `docs/index.html`)

GitHub Pages serves this repository from the default branch root, so the demo
and every document are already public. Both directory addresses used to 404 —
there was no document at either, and no map of what was published.
`scripts/generate-site.cljs` builds them from the repository tree: the lead
paragraph is read out of this README, each document title is that file's own
`#` heading, each byte count is `stat` on the real file. It is built on
[`jp-go-dds`](https://github.com/kotoba-lang/jp-go-digital-design-system), the
workspace's base design system.

```bash
D=../jp-go-digital-design-system
nbb --classpath "$D/src:../html/src:../css/src" scripts/generate-site.cljs \
  --dds-css "$D/resources/jp_go_dds/dds.css"            # regenerate
nbb --classpath "$D/src:../html/src:../css/src" scripts/generate-site.cljs \
  --dds-css "$D/resources/jp_go_dds/dds.css" --check    # 1 if hand-edited
```

The output is generated — do not hand-edit it. `--check` exits 1 when the
committed HTML and a fresh generation disagree, and the generator refuses
(exit 1) rather than publish an index with no documents, a demo section with
no payloads, or a document title it had to invent from a filename. An input
it cannot read at all exits 2, so "could not run" never looks like "ran and
found nothing wrong".

## Maturity

**Current tender level: R2 advanced-partial** (R1 stable). The former R3
shared-store placement implementation and its stable gate now live in
[`kotoba-lang/fleet`](https://github.com/kotoba-lang/fleet).
Ladder and gates: [`docs/maturity.md`](docs/maturity.md).

| Level | Status |
|---|---|
| R0 contract / dry-run | stable |
| R1 tender (compat: JVM/Chicory) | stable as **compat suite** — session report, host-free guests, emit lint, CLI (not the first path) |
| **R2 browser / native WASM host** | **first product path** (advanced-partial) — AOT `.wasm` via wasm-webcomponent; 9/9 linkable (`http-post` and `llm-infer` both real via a Worker-hosted SAB+Atomics bridge, needs COOP/COEP; `llm-infer` additionally needs a caller-supplied proxy URL); host-free web fixtures |
| T6 fleet placement | external — stable gate owned by `kotoba-lang/fleet` |

```bash
clojure -M:doctor                                    # tender/browser snapshot
clojure -M:cli parity                                # R2 import matrix
bash deploy/validate-packaging.sh                    # authority receiver packaging
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
