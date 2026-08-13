# 0009 — Stack topology position, browser-parity gate, and canonical capability schema

Status: accepted
Date: 2026-07-24
Root authority: `com-junkawasaki/root` ADR-2607241100 (kotoba stack topology
and design cleanup). This ADR is the kototama-repo mirror; the canonical
topology and the full cross-repo cleanup list live there.

## Position in the stack topology

```
kotoba    = language + datom model
amu       = compiler（編む; project link）
kototama  = runtime（言霊; host + runtime link）  (THIS REPO)
aiueos    = capability OS / broker
kotobase  = datom database           (depends on: kotoba, never the reverse)
```

Hosts of kototama (not sibling products): `wasm-webcomponent` (browser),
`tender-native` (native ELF), `tender-component` (Component engine).
The Solo5 word *tender* is a role, not a product name (ADR-2608139980).

**The `kototama → aiueos` deps.edn edge is deliberate and load-bearing:**
"aiueos decides, kototama enforces" (ADR-2607022700) is expressed as a real
dependency direction — the tender imports the decision plane
(`kototama.aiueos_adapter` → `aiueos.cli/command-result`) and never computes a
grant itself. aiueos MUST NOT acquire a dependency on kototama; enforcement
composes downward only.

## Decision 1 — new `actor:host` imports require 2-runtime parity in the same wave

The application-profile completion gate (#6, `kotoba-lang/kotoba`
`docs/lang/application-profile.md`) already requires parity evidence across
at least two runtimes before a capability family is "implemented" — yet the
ADR-2607230943 second wave (`http-fetch`/`cbor-encode`/`json-encode`/
`json-extract-field`) and the third wave (`http-post-headers`) landed
JVM-tender-only, moving browser parity from 9/9 to 9/14. `docs/maturity.md`
records this honestly, but honesty in a doc is not enforcement.

**Decision:** a PR adding a new `actor:host` import to `kototama.contract`
must either (a) land the `wasm-webcomponent` browser wiring in the same wave
(cross-repo PR pair), or (b) carry an explicit waiver note in the PR body AND
a same-PR `docs/maturity.md` parity-table update marking the gap. The parity
score in `clojure -M:cli parity` is the machine check; CI should fail a
contract change that does not update the parity matrix.

The former 5-import backlog (`http-fetch`/`cbor-encode`/`json-encode`/
`json-extract-field`/`http-post-headers`) was closed by the codec port and
`wasm-webcomponent` PR #15; 14/14 is now the enforced baseline. Any future debt must be burned
down under this rule, not grandfathered forever.

## Decision 2 — `HostCaps` and grant vocabulary derive from one canonical schema

Today the capability vocabulary exists in four hand-maintained forms: the
compiler's closed host-import table, `kototama.contract`'s `HostCaps`,
aiueos's kernel-capability names, and the adapter translation between them.
`kototama.aiueos_adapter` covers only the 3 imports that have aiueos-kernel
counterparts (`log-write`/`clock-monotonic`/`random-bytes`); the rest take
caller-supplied `HostCaps`, which is a hole in "aiueos decides".

**Decision:** adopt the canonical typed capability-descriptor schema
(application-profile completion-gate item 1) as the single source; generate
`HostCaps` field-by-field from it, and extend the aiueos grant vocabulary so
every `actor:host` import has a decidable counterpart. Hand-written adapter
coverage gaps become schema-coverage gaps, which are mechanically listable.

## Decision 3 — the two Chicory compat paths share fixtures, not code

`kototama.tender` and `kotoba-lang/kotoba`'s `kotoba.wasm-exec` are two
deliberately separate JVM/Chicory bootstraps (com-junkawasaki/root
ADR-2607182200: no cross-repo test dependency). Keep the two implementations,
but move the checked-in `.wasm` conformance fixtures
(`test/kototama/fixtures/kotoba-compiled-*.wasm`) toward a shared
fixture set that both repos consume, so the justification completes: two
implementations, one oracle.
