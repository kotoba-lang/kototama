# systemd packaging for kototama fleet recovery

This is a **oneshot + timer** pattern, not a forever `while true` service.
Each activation runs a bounded `fleet-daemon` (default max-passes=3).

## Install

```bash
# 1) Checkout / pin kototama somewhere readable by the service user
sudo mkdir -p /opt/kototama /var/lib/kototama/fleet
sudo git clone https://github.com/kotoba-lang/kototama.git /opt/kototama
cd /opt/kototama && sudo git checkout <pinned-sha>

# 2) Install wrapper (expects `clojure` on PATH for the service user)
sudo install -m 755 deploy/bin/kototama-fleet-daemon /usr/local/bin/kototama-fleet-daemon

# 3) Units
sudo install -m 644 deploy/systemd/kototama-fleet-daemon.service /etc/systemd/system/
sudo install -m 644 deploy/systemd/kototama-fleet-daemon.timer /etc/systemd/system/
# edit Environment= in the service file for WASM path / root / node id

sudo systemctl daemon-reload
sudo systemctl enable --now kototama-fleet-daemon.timer
systemctl list-timers | grep kototama
journalctl -u kototama-fleet-daemon.service -n 50
```

## Manual dry-run (same code path)

```bash
cd /opt/kototama
export KOTOTAMA_FLEET_ROOT=/var/lib/kototama/fleet
clojure -M:cli fleet-daemon path/to/guest.wasm --interval-ms 200 --max-passes 2
```

## Cross-node note

Multiple hosts may share `KOTOTAMA_FLEET_ROOT` (NFS) or B2. Lease fencing is
pure data in `kototama.fleet-fence` (epoch + owner). It is **not** Raft —
see `docs/maturity.md`.
