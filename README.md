# kototama

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
