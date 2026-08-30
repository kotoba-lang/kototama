# Kototama Virtual Machine specification v1

Kototama is the virtual-machine contract of the Kotoba world. It occupies the
same architectural position that the EVM occupies for Ethereum and the FVM
occupies for Filecoin, but it is not defined by one opcode table or one engine.
It is a deterministic transition relation over closed Lisp data, IPLD state,
bounded Datalog authority, and content-addressed receipts.

The machine-readable authority for this document is
[`spec/kototama-vm-v1.edn`](../spec/kototama-vm-v1.edn). The keywords MUST,
MUST NOT, REQUIRED, SHOULD and MAY are normative.

## Definition

Given a program, message, world state, authority context and resource budget,
Kototama produces an outcome, a successor state, events and a receipt:

```text
K |- <program, message, state, authority, budget>
  => <outcome, state', events, receipt>
```

The relation, not a particular interpreter, is the VM. A native engine, a Wasm
host, a JVM compatibility tender, an EVM bytecode adapter and an FVM-shaped
actor kernel can all implement Kototama. No implementation becomes the
definition merely because it shipped first.

Kototama does not define consensus, block production, fleet placement, grant
policy or compilation. Consensus decides which messages and roots are
committed. Amu proves what a program can attempt. Biscuit carries delegated
rights. The local authorizer decides whether those rights may be exercised.
Kototama performs the resulting transition and records it.

## The four planes

### Reduction plane: Lisp as the machine language

The semantic program is a closed S-expression. Kotoba, Clojure and
ClojureScript may be source dialects, but their host runtimes are not part of
the VM. Macros run before admission, symbols are resolved before execution,
and closures are explicit pairs of a code CID and a bounded environment.

The canonical wire form uses tagged DAG-CBOR vectors. It preserves list
semantics without depending on a JVM reader or JavaScript object behaviour.
There is no host `eval`, reflection, class loading, ambient FFI or accidental
access to the host filesystem, clock, network, environment or randomness.

Clojure contributes the value model: immutable persistent values, structural
equality, lexical scope and transformation by value. Imperative operations
such as EVM storage writes are expressed as pure transitions of the
message-local state overlay.

### State plane: IPLD as memory

Programs, actor code, actor state, messages and receipts are content addressed.
Reads MUST verify bytes against their CID before decoding. Writes MUST
canonicalize bytes before deriving the CID.

Every message executes against an overlay. Successful execution atomically
replaces the world root. Revert, refusal and resource exhaustion discard the
overlay. A nested call snapshots the current overlay; child failure restores
that snapshot without erasing earlier caller writes.

CARv2 packages and transports blocks. Possession of a CAR is never authority
to execute the blocks it contains.

### Logic plane: Datalog as admission and meaning

Datalog is not an alternative mutable runtime. It is the bounded relational
plane through which compiler evidence, runtime intent, delegated grants, local
policy and current availability meet. Facts are inert, function-free tuples;
rules are range-restricted and evaluated under explicit budgets.

A concrete capability exists only for the intersection:

```text
Amu static effect
  ∩ VM requested intent
  ∩ Biscuit delegated grant
  ∩ local policy allow
  ∩ runtime availability
  = invocation-local capability
```

Each fact retains its origin. A Biscuit token may supply `grant:*` facts but
MUST NOT impersonate `amu:*`, `vm:*`, `policy:*` or `runtime:*`. Biscuit is a
delegation envelope, not the final authority and not a substitute compiler.
Only the local authorizer may derive `allow`.

The v1 eligibility relation is a closed list too:

```clojure
(["vm:runs" actor definition]
 ["amu:requires" definition effect]
 ["amu:world" definition world]
 ["vm:requests" actor effect resource]
 ["grant:right" actor effect resource]
 ["policy:allows" actor effect resource]
 ["runtime:world" world]
 ["runtime:available" effect resource])
=> ["kotoba:eligible" actor effect resource]
```

This is data, not Clojure host code. Implementations may index or compile the
relation, but they MUST preserve its bounded semantics and provenance.

The capability produced by a successful join is unforgeable,
invocation-local and non-serializable. Raw Biscuit bearers, private keys and
host handles MUST NOT appear in a CAR or receipt.

### Evidence plane: receipts as values

Every attempt leaves a receipt, including reverts and exhausted executions.
The receipt binds the machine specification, implementation, compatibility
profile, message and program CIDs, before/after state roots, outcome, fuel,
events and authority decision. Authority evidence names the Amu manifest CID,
grant identifiers, local policy, world and epoch—not the bearer token.

Content addressing proves which value is cited. It does not by itself prove
authorization, uniqueness, consensus acceptance or at-most-once execution.

## Message and call model

A canonical message contains `chain-id`, sender, recipient, method, parameters,
value, nonce, fuel limit and epoch. It may name a compatibility profile, parent
receipt and effect intent. Its identity is the CID of that canonical value.

The core call vocabulary includes actor calls, static calls, delegated calls
and creation. A profile may narrow or map these operations. Call depth, value
size, collection size, logic derivations and recursion are bounded. Resource
exhaustion is an ordinary outcome value and MUST NOT become an uncatchable host
trap.

Storage corruption is different from actor failure. CID mismatch is a host
integrity fault; an actor cannot catch it and reinterpret corrupt bytes as a
business-level refusal.

## Compatibility profiles

“EVM compatible” and “FVM compatible” are incomplete claims. A Kototama
implementation MUST name a profile version, compatibility level, pinned
external protocol version and evidence suite.

### `core/v1`

The native Kotoba profile executes closed S-expressions over IPLD state with
bounded Datalog authorization and deterministic receipts. It requires no JVM,
Wasm engine, EVM bytecode or Filecoin protocol dependency.

### `fvm-actor/v1`

This profile exposes the FVM-shaped actor contract: actor addresses, numeric
methods, IPLD blockstore access, actor state roots, nested send, exit codes,
gas accounting and message-local overlay/revert. It MUST pin an FVM network
version, actor ABI and gas schedule.

It does not import Filecoin consensus, storage proofs or a FIL market into
Kototama. An implementation can conform at the message, actor-ABI or state
level and MUST state which levels it actually passes.

### `evm/v1`

This profile projects the EVM into Kototama values and transitions. It pins a
chain specification, hardfork, gas schedule and precompile set. It specifies
U256 modular arithmetic, a 1024-item stack, byte-addressed volatile memory,
separate code, 20-byte addresses, calldata and returndata, account
code/storage/nonces/balances, CALL/STATICCALL/DELEGATECALL,
CREATE/CREATE2, RETURN/REVERT, logs and Keccak-256.

Compatibility has four cumulative, separately reportable levels:

1. **message** — transactions and contract ABI values round-trip.
2. **semantic** — result, state delta, logs and gas match the pinned EVM.
3. **bytecode** — valid bytecode for that EVM version can execute directly or
   through a proven semantics-preserving translation.
4. **state** — canonical receipts and trie roots match.

The Kototama core does not acquire bytecode compatibility merely by providing
EVM-like calls. Semantic, bytecode and state claims require differential tests
against the pinned reference vectors.

### `fevm/v1`

FEVM compatibility is the composition of `fvm-actor/v1` and `evm/v1`, plus
the boundary between them: delegated-address mapping, EVM calldata carried by
an FVM message, FVM exit-code to EVM status mapping, event/log mapping and the
system-actor boundary. The Filecoin network version, FEVM actor version,
Ethereum hardfork, gas mapping and precompile set MUST all be pinned.

The composition deliberately takes EVM message and bytecode compatibility,
not Ethereum gas or state-root identity. FEVM meters execution in Filecoin gas;
its gas costs are not 1:1 with Ethereum and it has documented behavioural
differences. An FEVM claim therefore MUST expose Ethereum JSON-RPC/tooling,
the Ethereum Address Manager/delegated-address mapping, its Filecoin gas
mapping and the divergence set. It MUST NOT relabel Filecoin gas as Ethereum
gas.

This lets the same Kotoba S-expression describe an actor-native call, an
EVM-shaped contract call or an FEVM call while preserving one content-addressed
state and receipt model. Compatibility lives in a profile and adapter; it does
not leak mutable EVM or Filecoin machinery into the language core.

## Implementation declaration

Every engine publishes a `:kototama.vm/implementation-profile-v1` value. It
lists implemented profiles and levels, pinned versions, passed suites and
omissions. Partial conformance is valid; silent omission is not.

The common conformance suite covers transition determinism, fuel, nested
overlay/revert, IPLD integrity, authority provenance and receipts. EVM and
FEVM claims additionally require content-addressed upstream vectors and
differential execution evidence.

This separation is intentional:

```text
Kototama                  semantic VM specification
  ├─ native/Wasm hosts    core execution engines
  ├─ kotoba-vm            FVM-shaped actor-kernel implementation
  ├─ EVM adapter          evm/v1 implementation
  └─ FEVM adapter         fevm/v1 composition
```

Kototama is therefore one machine with several explicitly tested projections,
not a collection of unrelated runtimes and not a new concrete engine that must
replace all existing hosts.

## External compatibility authorities

Kototama profiles are projections onto versioned external authorities, not
forked copies of them:

- Ethereum execution behaviour is pinned to the
  [Ethereum execution specifications](https://github.com/ethereum/execution-specs)
  and, where formal notation is useful, a named version of the
  [Yellow Paper](https://ethereum.github.io/yellowpaper/paper.pdf).
- FVM architecture, actor calls, syscalls and IPLD behaviour are pinned to
  [FIP-0030](https://github.com/filecoin-project/FIPs/blob/master/FIPS/fip-0030.md)
  and its versioned conformance vectors.
- FEVM runtime integration and divergences are pinned to
  [FIP-0054](https://github.com/filecoin-project/FIPs/blob/master/FIPS/fip-0054.md),
  [FIP-0055](https://github.com/filecoin-project/FIPs/blob/master/FIPS/fip-0055.md)
  and the named Filecoin network version.

These references may evolve. A receipt and implementation declaration cite
the exact versions used for a run; the word “current” is never a compatibility
identifier.
