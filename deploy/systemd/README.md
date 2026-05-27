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
