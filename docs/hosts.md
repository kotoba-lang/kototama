# kototama hosts

Product name: **kototama**. The Solo5 word *tender* names the attendant-host
role, not a product. Root authority: `com-junkawasaki/root` ADR-2608139980.

This repository owns the contract, admission envelope, budget, and
`kototama → aiueos` grant translation. Concrete hosts stay in sibling
repos so kototama core does not ship an engine, a loader, or a provider.

| Host | Repository | Artifact | Why it is not this tree |
|---|---|---|---|
| browser / native Wasm | `kotoba-lang/wasm-webcomponent` | AOT `.wasm` | extracted so any repo can adopt the pattern (ADR-2607061630) |
| native ELF | `kotoba-lang/kototama-native` | sealed native / ELF | ns `kototama.native.executor`; formerly `tender-native` |
| Component engine | `kotoba-lang/kototama-component` | admitted Component | Rust/Wasmtime micro-TCB must not enter kototama core (ADR-2607266000 D4) |
| JVM compat | `src/kototama/tender.clj` | Core Wasm via Chicory | verification harness, not the first path |

Aliases `:native-host` and `:component-host` on this repo's `deps.edn`
point at the sibling hosts. They are extra-deps, not core.

Do not add `provider` here. Providers are the hands aiueos may lend;
kototama binds them after a grant.
