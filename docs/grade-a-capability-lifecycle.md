# Grade A capability lifecycle

Each `open-session` creates an authority state scoped to that Wasm instance:

- `:active` is initialized only from imports admitted by `HostCaps`;
- every host-function invocation atomically rechecks active authority and
  increments its per-import use counter;
- `revoke-import!` removes authority immediately and records its reason;
- `drop-import!` voluntarily removes authority for the remaining session.

All nine `actor:host` implementations call the same `ensure-granted!`
chokepoint, so an already-linked Chicory function does not retain authority
after session revocation. Reinstantiation is not required.

Verification:

```sh
clojure -M:test -n kototama.tender-test
```

T-02 remains `in-progress`. Qualification still needs table-driven runtime
revocation tests for all nine imports, bounded-use consumption policies, and
revocation propagation from the fleet/policy control plane into sessions that
are already running.
