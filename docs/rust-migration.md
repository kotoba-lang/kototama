# Rust Migration

`kototama` is currently a small Rust wrapper around the Kotoba/CLJ compiler
stack and optional native execution. The target architecture is Rust-free at the
semantic layer: Kotoba/CLJC defines the package, host-capability, and runtime
contracts; native hosts only execute those contracts.

## Current Rust Surface

| file | role | target |
|---|---|---|
| `Cargo.toml` | package wrapper and feature selection | replace with Kotoba/CLJC package metadata plus host adapters |
| `src/lib.rs` | facade for compile/safe-compile/import-surface APIs | generated or delegated from Kotoba/CLJC contracts |
| `examples/*.rs` | host examples | keep only as host adapter examples while Rust native execution exists |

## Target Boundary

Authoritative:

- Kotoba/CLJC package contract
- `HostCaps` data model
- `RuntimeLimits` data model
- import-surface validation rules
- capability grant projection

Host adapter only:

- `wasmtime` execution
- `wasm-bindgen` packaging
- filesystem/CLI examples
- native test harnesses

## Migration Steps

1. Move `HostCaps`, `RuntimeLimits`, and import-surface schemas into a CLJC
   contract.
2. Generate Rust/JS host adapter shapes from that contract while native hosts
   still exist.
3. Move package lock and grant projection to `kotoba-lang/kotoba-lang`.
4. Keep `wasmtime` as an optional backend capability, not the runtime authority.
5. Remove handwritten Rust semantic decisions after the generated adapters are
   stable.

