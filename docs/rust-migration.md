# Rust Migration

Status: Rust wrapper removed.

`kototama` is now a CLJC-first contract and organism runtime repository. The
authoritative behavior lives in:

- `src/kototama/contract.cljc`
- `lib/kototama/*.cljc`
- `lib/actor/publish.bb`

Removed native wrapper files:

- `Cargo.toml`
- `src/lib.rs`
- `examples/compile.rs`

Historical Rust wrapper details remain available in git history. New behavior
must be expressed first as CLJC/EDN data and functions. Native hosts may adapt
that contract outside this repository when a platform-specific runtime is
needed.
