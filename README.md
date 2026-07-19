# kototama

**Role: the Kotoba/Wasm component tender and linker** — admits, links, and
runs components produced by the **kotoba language** (`kotoba wasm emit`).
It does **not** own the language or the AOT compiler.

```text
kotoba   = component language (.kotoba → check → wasm emit)       ← kotoba-lang/kotoba
kototama = runtime tender/linker (admit + link + run components)  ← this repo
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
kototama is the **Wasm component tender/linker**: it admits and instantiates
the components that **kotoba** compiles (`.kotoba` → `kotoba wasm emit` → AOT
`.wasm`), links declared imports/exports, and binds only capabilities granted
by `aiueos`. Host and guest are relative runtime roles, not source extensions:
a Kotoba-written HTTP or database provider may host a higher-level import
while remaining a guest of scoped socket/TLS/secret capabilities. Kototama
does not let same-language providers bypass those imports. **Do not
reimplement the compiler here.**

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
| **clojurewasm** | next when FFI allows | component-import surface still limited (upstream Phase-16); revisit for imported-capability components |
| **ClojureScript host wire** | when native WASM + CLJS host imports | e.g. portable `kotoba.kami-host` |
| **`kototama.tender` (JVM/Chicory)** | **demoted compat / CI** | landed historically (ADR-2607022900 / 2607062330); keep for bit-exact fixtures, not the design premise |

### Compat tender (Chicory) — still present, not primary

`src/kototama/tender.clj` wires every `kototama.contract` `actor:host` import
to a Chicory `HostFunction` with pre-flight + per-call grant checks,
`RuntimeLimits`, memory limits, and fuel. Useful as a verification harness
against real Wasm bytes — **not** what "use kototama" means for new work.
Language-repo `kotoba wasm run` is the same class of **compat bootstrap**.

`src/kototama/transport_provider.clj` is the opt-in JVM prototype for the
bounded socket/TLS ABI. It exposes opaque affine handles only, requires exact
`host:port` allowlisting, enables TLS hostname verification, and meters
connections plus cumulative read/write bytes. Callers construct it explicitly
and pass its HostFunctions to `open-session`; ambient networking stays absent.

`src/kototama/linker.clj` links separate Wasm instances without sharing linear
memory. It grows an isolated provider scratch page, rewrites pointer arguments,
copies declared input/output buffers, rejects missing/duplicate/type-invalid
bindings, and keeps consumer high-level grants separate from provider
transport grants. The checked E2E path is real `.kotoba` consumer → real
`.kotoba` HTTP provider → native verified TLS.

The HTTP provider emits a bounded HTTP/1.1 GET, parses a 100–599 status code,
requires CRLF-CRLF header termination, and never follows redirects implicitly.
Native reads and TLS handshakes use the finite `:max-transport-read-ms` limit;
slow peers fail closed and release their handles.

Browser `http-get` is a separate explicitly injected high-level path. Node can
instead link the compiled `.kotoba` HTTP provider across independent memories
to its bounded worker/SAB TLS transport. Both are denied unless the matching
grants and finite quotas are supplied; the browser adapter additionally checks
host, port, and path before invoking its fetch worker bridge.

The database sibling now supplies a generic bounded u32-big-endian frame
exchange. A separately compiled Kotoba DB consumer/provider pair is tested over
the same native verified TLS path; product-specific database authentication
and transaction protocols remain separate follow-ups.

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
# Component must already be AOT-compiled by the language (kotoba):
#   kotoba wasm emit cell.kotoba --package-lock L -o cell.wasm

clojure -M:cli run path/to/guest.wasm --grant …     # execute (tender / CLI)
clojure -M:cli lint path/to/component.kotoba        # emit-pitfall lint only (no compile)
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

**Current level: R3 stable** (shared-store fleet ops; R1 stable; R2 advanced-partial).
Ladder and gates: [`docs/maturity.md`](docs/maturity.md).

| Level | Status |
|---|---|
| R0 contract / dry-run | stable |
| R1 tender (compat: JVM/Chicory) | stable as **compat suite** — session report, host-free guests, emit lint, CLI (not the first path) |
| **R2 browser / native WASM host** | **first product path** (advanced-partial) — AOT `.wasm` via wasm-webcomponent; 10/59 linkable; full declared surface tracked; host-free web fixtures |
| **R3 fleet multi-tenant** | **stable** — ops-ready local/shared-store fleet (fence+daemon+CI+staging-smoke; **not Raft**) |

```bash
clojure -M:doctor                                    # R0–R3 snapshot
clojure -M:cli parity                                # R2 import matrix
bash scripts/verify-postgresql-matrix.sh             # PostgreSQL 14/15/17, JDK 21+
clojure -M:cli fleet-gate                            # R3 acceptance harness (CI)
bash deploy/validate-packaging.sh                    # systemd oneshot+timer static gate
bash deploy/staging-smoke.sh                         # non-root staging substitute
clojure -M:cli fleet-demo                            # R3 pure loop demo
clojure -M:cli fleet-run path/to/guest.wasm          # tender execute + disk checkpoint
clojure -M:cli lint  path/to/component.kotoba        # lint only — compile with kotoba
clojure -M:cli run     path/to/guest.wasm            # RUNTIME: run AOT guest
node web/verify-host-free.mjs                        # R2 host-free under browser Wasm
```

Zero-import pure components are first-class on **native WASM** (browser/Node via
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
