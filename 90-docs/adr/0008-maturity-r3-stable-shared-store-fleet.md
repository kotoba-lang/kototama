# ADR-0008: R3 stable — shared-store multi-tenant fleet (not Raft)

**Status**: accepted  
**Date**: 2026-07-10  
**Landed on main**: `3b24afa` (merge `feat/r3-advanced-partial`)

## Decision

Declare **R3 (Fleet multi-tenant tender) = stable** under this honest definition:

> Ops-ready **local / shared-store** multi-tenant fleet: lease, budget, tick,
> governor, fence-gated claim, disk/B2 checkpoint, bounded daemon, tick audit,
> fleet-gate CI, and non-root staging-smoke.

Explicitly **out of scope** for this stable label:

- Raft / Paxos / multi-datacenter consensus
- Full aiueos fleet broker for every `actor:host` import kind

## Evidence

- `clojure -M:cli fleet-gate` (11 acceptance checks)
- `bash deploy/staging-smoke.sh` (packaging + gate + daemon + status/audit)
- CI: fleet-gate, daemon dry-run, packaging validate, staging-smoke
- aiueos GRANT/DENY E2E through `fleet-exec` + tender

## Consequences

- Doctor reports `:current :r3` with `:status :stable`
- Further work is optional hardening (broker surface, real multi-node ops),
  not a maturity-ladder promotion to invent consensus
