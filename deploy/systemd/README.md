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

# external-link-domain-cert-issue (systemd path-watcher)

Auto-issues a Let's Encrypt cert + nginx HTTPS server block for a new/reactivated
外部リンクドメイン生成 row, so it's reachable over HTTPS without a manual `certbot`
run every time an operator registers a domain.

## What it does

- Same privilege-boundary pattern as `softbank-env-install`: the CRM JVM (centos,
  `NoNewPrivileges=true`) writes the bare domain name to
  `/tmp/external-link-domain-cert-request.new` via `ExternalLinkDomainCertService`.
- `external-link-domain-cert-issue.path` watches that file and triggers the
  oneshot `external-link-domain-cert-issue.service`, which runs
  `deploy/systemd/certbot-issue-domain-cert.sh` as root.
- The script validates the domain strictly (hostname-shape regex — defense
  against a malicious/malformed value reaching a root-run script), runs
  `certbot certonly --webroot` against the shared `/.well-known/acme-challenge/`
  location already present in `ops/nginx-crm.conf`'s catch-all block, writes a
  templated HTTPS server block to `/etc/nginx/conf.d/external-link-domains/<domain>.conf`,
  validates with `nginx -t`, and reloads nginx. On any failure it removes the
  half-written conf file so a broken block never gets loaded.
- Result is written to `/tmp/external-link-domain-cert-result.<domain>` (or
  `.invalid` for a rejected domain) so the JVM can poll and show
  pending/success/failure in the settings UI.

## Prerequisite (one-off, before first use)

`ops/nginx-crm.conf` must already have the shared ACME-challenge location (added
alongside this feature) and the `include /etc/nginx/conf.d/external-link-domains/*.conf;`
directive. Re-deploy that file if it predates this feature.

```
sudo cp ops/nginx-crm.conf /etc/nginx/conf.d/crm-dev.conf   # match your live filename
sudo mkdir -p /var/www/letsencrypt /etc/nginx/conf.d/external-link-domains
sudo nginx -t && sudo systemctl reload nginx
```

Also required (both discovered the hard way on 2026-08-05 — without them certbot fails
every issuance with a 403/timeout even though everything above looks correct):

- **Firewall**: port 80 must be open, not just 443. `firewall-cmd --list-services` should
  include `http`. If it doesn't:
  ```
  sudo firewall-cmd --permanent --add-service=http
  sudo firewall-cmd --reload
  ```
- **SELinux** (enforcing mode), two separate policies needed:
  - `/var/www/letsencrypt` (ACME challenge files) needs `httpd_sys_content_t`:
    ```
    sudo semanage fcontext -a -t httpd_sys_content_t "/var/www/letsencrypt(/.*)?"
    sudo restorecon -Rv /var/www/letsencrypt
    ```
  - `/etc/nginx/conf.d/external-link-domains` (per-domain HTTPS blocks) needs
    `httpd_config_t` — nginx's config-reading label, different from content-serving:
    ```
    sudo semanage fcontext -a -t httpd_config_t "/etc/nginx/conf.d/external-link-domains(/.*)?"
    sudo restorecon -Rv /etc/nginx/conf.d/external-link-domains
    ```
    The script also `restorecon`s each conf file it writes (a plain `mv` from `/tmp`
    keeps the source's `tmp_t` label, so this alone isn't enough without the policy above).

## Install (one-off operator action)

```
sudo install -o root -g root -m 0755 deploy/systemd/certbot-issue-domain-cert.sh /usr/local/bin/
sudo install -o root -g root -m 0644 deploy/systemd/external-link-domain-cert-issue.service /etc/systemd/system/
sudo install -o root -g root -m 0644 deploy/systemd/external-link-domain-cert-issue.path    /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now external-link-domain-cert-issue.path
```

## Verify

```
sudo systemctl status external-link-domain-cert-issue.path   # should be active (waiting)
# Trigger from the CRM (settings > 外部リンクドメイン生成 > 新規追加 or 使用中にする)
sudo journalctl -u external-link-domain-cert-issue.service --since "2 minutes ago"
sudo tail -30 /var/log/letsencrypt/external-link-domain-cert.log
ls /etc/nginx/conf.d/external-link-domains/                  # should show <domain>.conf
```

## Uninstall

```
sudo systemctl disable --now external-link-domain-cert-issue.path
sudo rm /etc/systemd/system/external-link-domain-cert-issue.{path,service}
sudo rm /usr/local/bin/certbot-issue-domain-cert.sh
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
