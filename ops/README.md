# ops/

Snapshots of host-side files that the application depends on but live outside
the project tree. Tracked here so they have rollback history alongside the
application code.

These files are **not deployed by the build** — they are reference copies of
what is actually installed on the host. When you change a host file, mirror
the change here in the same commit so the diff is reviewable.

## Mirrored files

| Repo path | Host path | Perms | Owner |
|---|---|---|---|
| `ops/systemd/crm.service` | `/etc/systemd/system/crm.service` | 0644 | root:root |
| `ops/systemd/softbank-inbound-fetcher.service` | `/etc/systemd/system/softbank-inbound-fetcher.service` | 0644 | root:root |
| `ops/systemd/softbank-inbound-fetcher.timer` | `/etc/systemd/system/softbank-inbound-fetcher.timer` | 0644 | root:root |
| `ops/scripts/softbank-inbound-fetcher.py` | `/usr/local/bin/softbank-inbound-fetcher.py` | 0750 | root:centos |

## Secrets — NOT mirrored

The following files contain secrets and **must NOT** be checked into git:

- `/etc/crm.env` (mode 0600 root:root) — DB_PASSWORD, AES_ENCRYPTION_KEY, RELAY_SSH_PASSWORD
- `/etc/softbank-fetcher.env` (mode 0600 root:root) — SOFTBANK_IMAP_ACCOUNTS

If a server needs to be rebuilt, recreate these files from the password
manager / shared vault.

## Sync workflow

When changing a host file:

```bash
sudo $EDITOR /etc/systemd/system/crm.service
sudo systemctl daemon-reload && sudo systemctl restart crm
sudo cp /etc/systemd/system/crm.service ops/systemd/crm.service
sudo chown centos:centos ops/systemd/crm.service
git add ops/systemd/crm.service && git commit
```
