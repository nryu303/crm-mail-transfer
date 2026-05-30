# softbank-env-install (systemd path-watcher)

Auto-installs `/tmp/softbank-fetcher.env.new` (written by the CRM JVM) into
`/etc/softbank-fetcher.env` (consumed by the IMAP fetcher) whenever the CRM
re-stages it after a `CARRIER_ADDRESS_POOL` mutation.

## What it does

- The CRM JVM runs as `centos` with `NoNewPrivileges=true`, so it cannot
  `sudo` to elevate. It can only write to `/tmp/softbank-fetcher.env.new`.
- The `softbank-env-install.path` unit watches that file with `PathChanged=`
  (closed-after-write) and triggers the oneshot `softbank-env-install.service`
  which runs as root and atomically copies the file to `/etc/...env` 0600.
- The IMAP fetcher (`softbank-inbound-fetcher.service`, timer-driven every
  ~2 min) re-reads `EnvironmentFile=/etc/softbank-fetcher.env` each tick, so
  no restart is needed — the next tick picks up the new credentials.

## Safety guard

`ExecStartPre=` refuses to install the staging file unless its first line is
the magic header written by `ImapEnvSyncService` — a narrow protection
against a stray `/tmp` clobber being promoted to `/etc`.

## Install (one-off operator action)

```
sudo install -o root -g root -m 0644 deploy/systemd/softbank-env-install.service /etc/systemd/system/
sudo install -o root -g root -m 0644 deploy/systemd/softbank-env-install.path    /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now softbank-env-install.path
```

## Verify

```
sudo systemctl status softbank-env-install.path   # should be active (waiting)
# Trigger a rebuild from the CRM (settings > IMAP 監視同期 > 同期を実行)
sudo journalctl -u softbank-env-install.service --since "1 minute ago"
# Should show: install completed, no errors.
sudo stat /etc/softbank-fetcher.env                # mtime should be fresh
```

## Uninstall

```
sudo systemctl disable --now softbank-env-install.path
sudo rm /etc/systemd/system/softbank-env-install.{path,service}
sudo systemctl daemon-reload
```

---

# crm.service drop-in: TimeoutStopSec=90

Why: the base `crm.service` ships with `TimeoutStopSec=30`. Spring Boot
graceful shutdown for this app routinely takes 30–60s (8-worker dispatcher
pool draining + open HTTP connections), so every restart was tripping the
30s timeout → systemd `SIGKILL` → noisy `NoClassDefFoundError` traces in
the journal and a small risk of in-flight dispatch loss. Raising to 90s
gives the graceful shutdown room to actually finish.

## Install (one-off operator action)

```
sudo mkdir -p /etc/systemd/system/crm.service.d
sudo install -o root -g root -m 0644 deploy/systemd/crm-service.d/timeout.conf /etc/systemd/system/crm.service.d/
sudo systemctl daemon-reload
```

(No service restart required — the new TimeoutStopSec applies on the next
`systemctl restart crm`.)

## Verify

```
systemctl show crm -p TimeoutStopUSec    # should print TimeoutStopUSec=1min 30s
```

## Uninstall

```
sudo rm /etc/systemd/system/crm.service.d/timeout.conf
sudo rmdir --ignore-fail-on-non-empty /etc/systemd/system/crm.service.d
sudo systemctl daemon-reload
```
