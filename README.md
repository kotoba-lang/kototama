# kototama

## Role

In the `kotoba-lang → kototama → aiueos` stack
([ADR-2607022400](https://github.com/com-junkawasaki/root/blob/main/90-docs/adr/2607022400-kototama-unikernel-tender-runtime-vocabulary.md)),
kototama is the **Wasm execution runtime**: it hosts the Wasm components that
`kotoba-lang` compiles, under capability grants that `aiueos` (the OS/broker
layer) decides. That ADR also adopts Solo5's *tender* pattern as the target
native-runtime shape — kototama as tender, the Wasm components aiueos
launches as guest — but the tender/wasmtime-hosting implementation itself is
still follow-up work, not yet built here.

What this repository implements *today* is the CLJC contract surface that
runtime is built on: the actor/organism host-capability contracts described
below. Read the description immediately following as "the contract kototama's
runtime will execute against," not as a claim that Wasm hosting is already
wired up in this repo.

Kototama is the CLJC authority layer for actor/organism host capability
contracts.

The repository keeps the portable `.cljc` organism runtime and the pure
`kototama.contract` import-surface validator. Native compiler/runtime wrappers
are no longer defined here; host adapters should consume the CLJC data contract
instead of becoming the semantic authority.

## Contract Surface

- `src/kototama/contract.cljc` defines the `actor:host` import surface,
  `HostCaps`, `RuntimeLimits`, grant normalization, and import validation.
- `lib/kototama/*.cljc` contains the portable organism/cell runtime:
  gates, membrane, heartbeat, did:key, atproto shaping, and identity helpers.
- `lib/actor/publish.bb` is the shared actor publish runner.

## Test

```bash
clojure -M:test
bb --classpath lib lib/kototama/test_actor.clj
bb --classpath lib lib/kototama/test_atproto.cljc
```

`clojure -M:test` is the default repository gate. The babashka commands cover
the current organism runtime helpers when `bb` is available.

## Migration

The old Rust wrapper around `kotoba-clj` and `kami-engine-clj` has been removed
from this repo. Historical native implementation details remain available in git
history. New behavior should land first as CLJC/EDN contracts; native hosts can
adapt those contracts in their own repositories when needed.

See [`docs/rust-migration.md`](docs/rust-migration.md).

## License

MIT.
