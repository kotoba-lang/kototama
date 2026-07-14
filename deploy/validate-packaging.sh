#!/usr/bin/env bash
# Static + dry-run packaging checks for R3 systemd oneshot+timer.
# No root / no systemctl required — safe for CI and local.
set -euo pipefail
cd "$(dirname "$0")/.."

fail=0
check() {
  local msg="$1"; shift
  if "$@"; then
    echo "ok  $msg"
  else
    echo "FAIL $msg" >&2
    fail=1
  fi
}

check "daemon wrapper exists" test -f deploy/bin/kototama-fleet-daemon
check "daemon wrapper is executable bit or can chmod" \
  bash -c 'test -x deploy/bin/kototama-fleet-daemon || chmod +x deploy/bin/kototama-fleet-daemon'
check "service unit exists" test -f deploy/systemd/kototama-fleet-daemon.service
check "timer unit exists" test -f deploy/systemd/kototama-fleet-daemon.timer
check "README runbook exists" test -f deploy/systemd/README.md

# Required unit directives (oneshot + bounded daemon — not forever loops)
for key in Type=oneshot ExecStart= Environment=KOTOTAMA_HOME ProtectSystem=; do
  check "service has $key" grep -q "$key" deploy/systemd/kototama-fleet-daemon.service
done
for key in OnUnitActiveSec= Unit=kototama-fleet-daemon.service; do
  check "timer has $key" grep -q "$key" deploy/systemd/kototama-fleet-daemon.timer
done

check "wrapper --help exits 0" deploy/bin/kototama-fleet-daemon --help >/dev/null
check "wrapper rejects missing --wasm" \
  bash -c '! deploy/bin/kototama-fleet-daemon 2>/dev/null'

# Fact fixture present for dry-run
check "fact fixture present" \
  test -f test/kototama/fixtures/kotoba-compiled-fact.wasm

if [[ $fail -ne 0 ]]; then
  echo "packaging validation failed" >&2
  exit 1
fi
echo "packaging validation passed"
