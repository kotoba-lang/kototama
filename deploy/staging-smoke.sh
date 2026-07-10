#!/usr/bin/env bash
# Staging smoke for R3 fleet — no root, no systemctl.
# Mirrors the systemd oneshot path: packaging validate → fleet-gate →
# daemon wrapper → status/audit observability.
set -euo pipefail
cd "$(dirname "$0")/.."

ROOT="${KOTOTAMA_FLEET_ROOT:-tmp/kototama-fleet-staging-smoke}"
WASM="${KOTOTAMA_WASM:-test/kototama/fixtures/kotoba-compiled-fact.wasm}"
export KOTOTAMA_HOME="${KOTOTAMA_HOME:-$PWD}"

echo "== packaging =="
bash deploy/validate-packaging.sh

echo "== fleet-gate =="
clojure -M:cli fleet-gate

echo "== daemon wrapper (1 pass) =="
chmod +x deploy/bin/kototama-fleet-daemon
deploy/bin/kototama-fleet-daemon \
  --wasm "$WASM" \
  --root "$ROOT" \
  --interval-ms 0 \
  --max-passes 1 \
  --max-ticks 1

echo "== fleet-status =="
# CLI defaults to tmp/kototama-fleet; symlink if custom root
if [[ "$ROOT" != "tmp/kototama-fleet" ]]; then
  mkdir -p tmp
  ln -sfn "$(cd "$ROOT" && pwd)" tmp/kototama-fleet
fi
clojure -M:cli fleet-status
clojure -M:cli fleet-audit | head -40

echo "== staging-smoke passed =="
echo "R3 stable criterion (ops): this script is the non-root staging substitute."
echo "On a real host: enable systemd timer per deploy/systemd/README.md"
