# kototama hosts

Product name: **kototama**. The Solo5 word *tender* names the attendant-host
role, not a product. Root authority: `com-junkawasaki/root` ADR-2608139980.

This repository owns the abstract VM contract, admission envelope, budget, and
`kototama → aiueos` grant translation. Concrete hosts implement artifact
execution profiles of that VM and stay in sibling repos so kototama core does
not ship an engine, a loader, or a provider. The Lisp/IPLD/Datalog transition
contract and EVM/FVM/FEVM profiles are defined in
[`kototama-virtual-machine.md`](kototama-virtual-machine.md).

| Host | Repository | Artifact | Why it is not this tree |
|---|---|---|---|
| browser / native Wasm profile | `kotoba-lang/wasm-webcomponent` | AOT `.wasm` | extracted profile implementation; not a stack-wide default |
| native ELF profile | `kotoba-lang/kototama-native` | sealed native / ELF | ns `kototama.native.executor`; formerly `tender-native` |
| Component profile | `kotoba-lang/kototama-component` | admitted Component | Rust/Wasmtime micro-TCB must not enter kototama core (ADR-2607266000 D4) |
| JVM compatibility profile | `src/kototama/tender.cljk` | Core Wasm via Chicory | compatibility and diagnostic harness |

Aliases `:native-host` and `:component-host` on this repo's `deps.edn`
point at the sibling hosts. They are extra-deps, not core.

Do not add `provider` here. Providers are the hands aiueos may lend;
kototama binds them after a grant.
