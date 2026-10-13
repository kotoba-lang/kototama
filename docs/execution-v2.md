# V2 execution integration

The target-independent shape owner is `kotoba.core.execution`. Physical profiles
are in `kotoba.abi.target`; canonical content blocks are in
`kotoba.artifact.execution`. The authority issues a fresh invocation block,
selects immutable plan/policy/basis and permission ceilings, derives a permit
through `grant.execution-decision`, issues leases and signs the intent/leases.
Kototama verifies those blocks and signatures before admitting a host mechanism.

The pre-execution intent includes `invocation-cid`. It has no outcome or receipt.
A lease's existing `execution-identity-cid` field names this intent. Completion
creates a new identity CID from observed output and executor-signed receipts;
its existing v2 field set is unchanged. A fresh invocation permits another run
at the same basis; replay or a second intent for the same invocation is denied.

The v2 path is explicit. V1 descriptors, WIT, hashing, signatures and default
CLI admission remain compatible. Content verification is not authorization;
fixture coverage and main integration do not establish Q9 or production readiness.

## Scoped synchronous provider handoff

`admit!` derives a process-local `:scopes` set from the authenticated leases.
`run!` passes that same set and its bounded-lifetime `:call!` closure to the
trusted executor. A guest packet cannot supply `:scopes` (the packet remains
closed); a scope descriptor does not grant authority. Target adapters compare
the exact artifact-bound import scopes before instantiation and invoke only
through `:call!`, which reserves each use durably before the provider effect.
After execution/trap the closure is closed; journal reopen still denies replay.

AMU's explicit v3 artifact offers a scalar typed i64 provider bridge for Node
Wasm/Script. The existing v2 pure artifact/default v1 routes are separate.
Native loader effects and asynchronous task providers retain their own gates.
