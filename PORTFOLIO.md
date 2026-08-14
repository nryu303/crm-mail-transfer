# CRM Carrier Messaging Platform — Technical Documentation

A production CRM system for managing carrier email (SoftBank, docomo, au) communication with end customers in Japan. Designed for high-throughput outbound broadcasts, reliable inbound reply ingestion, and operator-friendly day-to-day management.

---

## 1. Executive Summary

**What it is**: A Spring Boot-based CRM that integrates with Japanese mobile carrier email infrastructure (i.softbank.jp / docomo.ne.jp / ezweb.ne.jp) to run targeted outbound campaigns and capture user replies — all through a unified operator UI.

**Why it exists**: Carrier email in Japan is a closed ecosystem with strict deliverability rules, mailbox quirks, and ever-shifting anti-spam policies. Off-the-shelf CRMs (HubSpot, Salesforce) cannot speak this protocol stack. This platform bridges the gap.

**Scale (production today)**:
- 35,000+ users in DB
- ~700 messages/minute outbound throughput (8-worker parallel dispatcher)
- 43 SoftBank IMAP accounts continuously monitored (2-min polling)
- 22,500+ user-to-carrier-address bindings (M:N)
- 100% scheduled broadcast success rate (post-2026-05-29 fixes)
- Single 4 GB / 4 vCPU server (room to grow)

**Headline tech challenges solved**:
1. Multi-account IMAP fetching with bounce-backlog draining (eliminated 2-hour reply delays)
2. Frozen-snapshot semantics for scheduled broadcasts (operator-driven behavior change)
3. AES-256-GCM credential storage with auto-sync to fetcher env files via systemd path-watcher
4. Strict M:N carrier-pool / user binding enforcement against stale-mailbox cross-contamination
5. Spring Data JPA `@Modifying` + `@Transactional` discipline across 7 repositories
6. Race-condition gates in concurrent dispatcher (re-fetch before send) to honor operator cancels

---

## 2. Tech Stack

| Layer | Choice | Notes |
|---|---|---|
| Language | **Java 8** (OpenJDK 1.8.0_482) | Constrained by carrier-mail SDK ecosystem |
| Framework | **Spring Boot 2.7.18** | Web MVC + Data JPA + Validation + Mail |
| ORM | **Hibernate 5.6.15** (via Spring Data JPA) | JPQL + native queries |
| Database | **MySQL 5.7** in podman container | InnoDB, utf8mb4 |
| Templates | **Thymeleaf** + Layout Dialect | Server-side rendering for operator UI |
| Frontend | **Tailwind CSS** (CDN), vanilla JS | No SPA — operator UX is form-based |
| Build | **Maven 3.x** + spring-boot-maven-plugin | Single fat-jar deployment |
| HTTP relay | **Apache HttpClient** + raw SSH for SMTP relay | Custom relay protocol over SSH/HTTP |
| IMAP fetcher | **Python 3** (per-carrier daemons) | Triggered by systemd timers, POSTs to webhook |
| Auth | **BCrypt** (12 rounds) via spring-security-crypto | Per-session CSRF token via custom interceptor |
| Encryption | **AES-256-GCM** (javax.crypto) | SMTP password storage |
| HTML sanitization | **OWASP Java HTML Sanitizer** | Operator-pasted HTML in reply pages |
| Reverse proxy | **nginx** (TLS terminator) | Forwards `127.0.0.1:50000` |
| Process supervision | **systemd** | One unit per daemon, timer-driven fetchers |
| Test framework | **JUnit 5** + **Mockito** + **AssertJ** | 186 tests, mock-heavy |

**Why this stack**: Mature, boring, observable. Java 8 was non-negotiable because some carrier-side toolchains we link with (relay's `obob.jar`) require it. Spring Boot gives transaction management and dependency injection without exotic patterns. Thymeleaf keeps the operator-facing UI shippable by a single engineer.

---

## 3. Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                    Browser (operator UI)                          │
└────────────────────────────┬─────────────────────────────────────┘
                             │ HTTPS
                             ▼
                       ┌──────────┐
                       │  nginx   │ TLS terminator
                       └─────┬────┘
                             │ HTTP :50000
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│                  Spring Boot CRM (this repo)                     │
│                                                                  │
│  Controllers ──▶ Services ──▶ Repositories ──▶ MySQL             │
│       │              │                                           │
│       │              ├──▶ Outbound relay (SSH/HTTP)              │
│       │              ├──▶ Inbound webhook receiver               │
│       │              └──▶ Scheduled tasks (8-worker dispatcher)  │
│       │                                                          │
│       └──▶ Thymeleaf templates (40+) for operator views          │
└────┬─────────────────────────────────────────────────┬───────────┘
     │ HTTP POST                                       │ JDBC
     │                                                 │
     ▼                                                 ▼
┌──────────────────┐                            ┌────────────┐
│ Inbound webhook  │ ◀── Python fetcher          │  MySQL 5.7 │
│ /api/inbound/    │     (softbank / au)         │ (podman)   │
│  receive-raw     │     systemd timer every 2m  └────────────┘
└──────────────────┘
     ▲
     │ POST raw MIME
     │
┌─────────────────────────────────────────────────────────────────┐
│  External: relay SSH/HTTP server, carrier IMAP servers,         │
│            AMG (SMTP credentials provider)                      │
└─────────────────────────────────────────────────────────────────┘
```

### Process boundaries

| Process | Role | Lifetime |
|---|---|---|
| `crm.service` (Spring Boot fat-jar) | Web UI + REST API + scheduled tasks + dispatcher | Long-running, supervised by systemd |
| `softbank-inbound-fetcher.service` (Python) | Pull UNSEEN mail from 43 SoftBank IMAP accounts, POST raw MIME to CRM webhook | One-shot, fired every 2 min by `.timer` |
| `au-inbound-fetcher.service` (Python) | Same for au's ezweb IMAP | One-shot, every 2 min |
| `softbank-env-install.service` (root, oneshot) | Promote `/tmp/softbank-fetcher.env.new` → `/etc/softbank-fetcher.env` when the JVM writes it | Triggered by `softbank-env-install.path` watcher |
| `container-crm-mysql.service` (podman user unit) | MySQL 5.7 | Long-running |
| `nginx` | TLS terminator | System-wide |

### Why split JVM and fetchers

The JVM runs as `centos` with `NoNewPrivileges=true` so it cannot `sudo`. The IMAP credential file `/etc/softbank-fetcher.env` is `root:root 0600`. Three integration patterns considered:

1. **JVM does everything** → requires giving the JVM root or relaxing NoNewPrivileges. Rejected: blast radius.
2. **Cron-driven shell script reads DB, writes /etc** → requires duplicating AES decryption logic in shell. Rejected: drift risk.
3. **JVM stages to /tmp + systemd path-watcher promotes** → chosen. Clear privilege boundary, atomic file replace, magic-header guard prevents stray /tmp clobbers.

---

## 4. Domain Model

```
CRM_USER ─────┬──── owns N ───── MESSAGE (DIR_IN | DIR_OUT)
              │                       │
              │                       └── belongs to ── BROADCAST
              │
              ├──── bound to N ────── CARRIER_USER_BINDING ────── N ───── CARRIER_ADDRESS_POOL
              │                            (M:N join)                        │
              │                                                              ▼
              │                                                       SMTP credentials
              │                                                       (AES-256-GCM encrypted)
              │
              └──── has N ────────── PAYMENT, USER_BILLING

ADMIN_USER ──── BCrypt-hashed login

REPLY_PAGE ──── belongs to a MESSAGE, has 6 attachment slots (REPLY_PAGE_ATTACHMENT)
                Operator embeds %reply_url% in body → users click → reply via web form

INBOUND_MAIL_LOG ──── every IMAP-fetched mail is recorded here regardless of accept/reject
                       (audit + reject-reason histogram + replay queue)
```

### Notable invariants

- **Binding policy** (re-enforced 2026-05-27): inbound mail is only attached to a user's thread if a row exists in `CARRIER_USER_BINDING` for the (sender's user, recipient pool address) pair. Closes a stale-mailbox attack where the previous owner of a SoftBank address kept receiving mail.
- **Snapshot scheduling** (changed 2026-05-29): broadcast recipient lists are materialized at create time (one MESSAGE row per user). At dispatch time, every materialized row fires — no filter re-evaluation, no anti-duplication exclusion. Operator decision: their workflow involves multiple campaigns to overlapping audiences, and the previous "skip if user got another send" gate was surprising them.
- **Pool churn grace**: if a pool row was created within the last 5 minutes, inbound for a now-missing pool address is **deferred**, not rejected. The deferred row is retried for up to 30 minutes by a scheduled task. Handles the operator's "delete-then-re-import pool CSV" workflow without losing legitimate replies.

---

## 5. Key Features & The Engineering Behind Them

### 5.1 Multi-account IMAP fetcher with bounce-backlog draining

**Problem**: 43 SoftBank pool addresses are polled every 2 minutes. With a per-account fetch cap of 10, draining a backlog (e.g. 670 Postmaster bounces from a campaign with bad addresses) took 2+ hours, during which real user replies sat at the back of the queue.

**Investigation** (2026-05-28 incident): tracked the timeline of a user reply that took 2h 9min to surface in the CRM:
- 23:39:09 SoftBank MX received the mail
- 23:39:10 mail landed in target inbox at queue position ~#670 (behind a Postmaster pile)
- Fetcher processed 10/tick × 30 ticks/hour = 300/hour
- 01:46:02 our fetcher finally pulled it (2h 7min later)

**Solution**:
```python
# /usr/local/bin/softbank-inbound-fetcher.py
def drain_bounces(conn, user):
    """Bulk-flag Postmaster/Mailer-Daemon mails as \Seen at IMAP level
    WITHOUT fetching their bodies. CRM rejects them anyway as REASON_BOUNCE."""
    for keyword in ("postmaster", "mailer-daemon"):
        typ, data = conn.uid("search", None, "UNSEEN", "FROM", keyword)
        if typ == "OK" and data and data[0]:
            uid_csv = b",".join(data[0].split()).decode("ascii")
            conn.uid("store", uid_csv, "+FLAGS", "(\\Seen)")
```

Plus `MAX_FETCH` raised 10 → 50. Result: 670-message backlog drains in seconds instead of 2 hours.

### 5.2 Auto-sync IMAP env file from DB

**Problem**: Operator adds a new SoftBank pool row in the CRM UI. The IMAP fetcher reads its account list from `/etc/softbank-fetcher.env`. Without sync, the new address is sendable but **not monitored** — replies to it disappear into the void.

**Architecture**:
```
                CARRIER_ADDRESS_POOL (MySQL)
                         │
                         │ TransactionSynchronization.afterCommit()
                         ▼
              ┌──────────────────────────┐
              │ ImapEnvSyncService       │ (Spring service)
              │                          │
              │ rebuildEnvFile():        │
              │  - decrypts SMTP pwd     │
              │  - writes header + body  │
              │  - target = /tmp/...new  │
              └──────────────┬───────────┘
                             │
            JVM is centos, /tmp is centos-writable
                             │
                             ▼
                    /tmp/softbank-fetcher.env.new
                             │
                             │ inotify(IN_CLOSE_WRITE)
                             ▼
              ┌──────────────────────────────────┐
              │ softbank-env-install.path        │  systemd
              │  PathChanged=/tmp/...env.new     │
              └──────────────┬───────────────────┘
                             │ triggers
                             ▼
              ┌──────────────────────────────────┐
              │ softbank-env-install.service     │  runs as root
              │  ExecStartPre: header check      │  one-shot
              │  ExecStart: install -m 0600 to   │
              │             /etc/softbank-..env  │
              └──────────────────────────────────┘
                             │
            Next IMAP fetcher timer tick re-reads env, picks up
            new accounts. No service restart needed.
```

**Code (Spring side)**:
```java
public void triggerAfterCommit() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronizationAdapter() {
                @Override public void afterCommit() {
                    try { rebuildEnvFile(); rebuildAuEnvFile(); }
                    catch (Exception e) { log.warn("IMAP env auto-sync failed: {}", e.toString()); }
                }
            });
    }
}
```

Triggered from `CarrierPoolService.create()/update()/delete()/deleteByIds()/importCsv()`.

### 5.3 Concurrent dispatcher with race-condition gates

**Problem**: 8-worker thread pool processing 5,000-row broadcasts. Operator hits cancel at "送信済み 167". Without protection, the worker pool kept iterating its in-memory snapshot and sent another 645 messages before realizing.

**Solution** (per-message re-fetch gate):
```java
private void dispatchOne(Message msg, ConcurrentMap<Long, Boolean> broadcastCancelled) {
    // CRITICAL race-condition gate: re-fetch the row from DB before each send
    // so a cancel that arrived AFTER findDueForDispatch loaded this batch into
    // memory catches us BEFORE we dispatch.
    Message effective = messageRepository.findById(msg.getId()).orElse(msg);
    if (!Message.STATUS_QUEUED.equals(effective.getStatus())) return;
    
    // Broadcast-level cancel cache (per tick, one DB read per parent broadcast)
    if (msg.getBroadcastId() != null) {
        Boolean isCancelled = broadcastCancelled.computeIfAbsent(msg.getBroadcastId(), bid -> {
            Broadcast b = broadcastRepo.findById(bid).orElse(null);
            return b != null && STATUS_CANCELLED.equals(b.getStatus());
        });
        if (Boolean.TRUE.equals(isCancelled)) {
            msg.setStatus(Message.STATUS_CANCELLED);
            messageRepository.save(msg);
            return;
        }
    }
    
    messageService.sendNow(msg, pool);
}
```

The `ConcurrentHashMap` cache means a 5K-row broadcast hits the parent BROADCAST row once, not 5,000 times.

### 5.4 Strict M:N binding enforcement against stale-mailbox cross-contamination

**Problem**: SoftBank pool addresses get recycled across operators. A previous owner of `other.unasked@i.softbank.jp` exchanged mail with `bluess1413@gmail.com` on 5/16. The reply sat unread in the SoftBank inbox. When we onboarded the address and pointed our IMAP fetcher at it, the 9-day-old reply was pulled and attached to `bluess1413@gmail.com`'s thread on our system — even though we had never sent them anything from that address.

**Detection** (the diagnostic SQL):
```sql
SELECT MIN(m.createdAt) FROM Message m
WHERE m.userId = :userId AND m.direction = 'OUT'
```

Compare against the inbound's RFC 5322 `Date:` header. If the reply predates our first send to the user by more than a 5-minute clock-skew buffer → reject with `REASON_STALE_PRE_RELATIONSHIP`.

```java
java.time.LocalDateTime inboundDate = parseRfc5322Date(extractRawHeader(dto.getRaw(), "Date"));
if (inboundDate != null) {
    java.time.LocalDateTime firstOut = messageRepository.findEarliestOutboundDate(user.getId());
    if (firstOut != null && inboundDate.isBefore(firstOut.minusMinutes(5))) {
        return reject(entry, REASON_STALE_PRE_RELATIONSHIP);
    }
}
```

### 5.5 Carrier abuse-report auto-suspension

**Problem**: Some recipients reply to carrier anti-spam mailboxes (`stop@meiwaku.softbankmobile.co.jp`, `imode-meiwaku@nttdocomo.co.jp`, etc.) CC-ing our pool address. Continued sending to those users risks getting our relay IP carrier-blocked.

**Solution**: scan the raw TO header on every inbound. If it contains any of the 5 known abuse-report addresses, flip the matched user to `SUSPENDED` and reject the mail with `REASON_SPAM_COMPLAINT`:

```java
private static final Set<String> ABUSE_REPORT_ADDRESSES = Set.of(
    "stop@meiwaku.softbankmobile.co.jp",
    "imode-meiwaku@nttdocomo.co.jp",
    "info@antiphishing.jp",
    "mailagain@dekyo.or.jp",
    "meiwaku@kddi.com"
);

if (containsAbuseReportAddress(dto.getTo())) {
    user.setStatus(CrmUser.STATUS_SUSPENDED);
    userRepository.save(user);
    log.warn("Inbound flagged as spam complaint — auto-suspending user {} ({})",
            user.getId(), LogSafe.of(user.getEmail()));
    return reject(entry, REASON_SPAM_COMPLAINT);
}
```

Deployed 2026-05-27. Caught its first real case within 24 hours: `yamakawatamiya@gmail.com` (user 44900) auto-suspended after CC-ing 4 carrier anti-spam addresses in a complaint reply.

### 5.6 Snapshot-frozen scheduled broadcasts

**Operator request** (2026-05-29): they schedule a broadcast for 20:00 targeting folder1. At 19:00 a user moves folder1→folder2. At 20:00, the broadcast should still fire to that user because they were in folder1 at schedule time.

**Implementation**: the broadcast creation flow already materialized one MESSAGE row per matching user at create time. The dispatcher's old "skip if user received another OUT in between" exclusion gate was removed. Now every materialized row fires unless the parent broadcast itself was explicitly cancelled.

```java
// Before (removed):
// if (msg.getBroadcastId() != null && msg.getScheduledAt() != null
//         && msg.getScheduledAt().isAfter(msg.getCreatedAt())) {
//     long otherSends = messageRepository.countOutboundFinalisedSince(...);
//     if (otherSends > 0) { CANCEL; return; }
// }

// After:
// Scheduled-broadcast snapshot semantics: recipient list is FROZEN at creation
// time. Every materialised MESSAGE row fires at dispatch time regardless of
// intermediate activity. Cancel-race (parent CANCELLED) is preserved.
```

### 5.7 Outbound rate limiting & throughput

Configured rate: `broadcast.rate_per_min=600`. The dispatcher uses 8 worker threads × ~700 ms per send (SSH + HTTP roundtrip to the relay) = effective throughput ~700 msgs/min.

```java
private final int parallelWorkers = Integer.parseInt(
    System.getProperty("app.scheduler.parallel-workers", "8"));
private final ExecutorService workerPool = Executors.newFixedThreadPool(parallelWorkers, r -> {
    Thread t = new Thread(r, "crm-dispatch");
    t.setDaemon(true);
    return t;
});
```

Bounded by HikariCP pool (10 connections; 8 workers + main + handlers fits cleanly).

### 5.8 AES-256-GCM credential storage

SMTP passwords for ~43 pool addresses are stored in `CARRIER_ADDRESS_POOL.SMTP_PASSWORD` as AES-256-GCM ciphertext:

```java
public String encrypt(String plaintext) {
    byte[] iv = new byte[IV_BYTES];
    random.nextBytes(iv);
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
    byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
    // Output format: base64( IV(12) || ciphertext || GCM_tag(16) )
    return Base64.getEncoder().encodeToString(
        ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
}
```

Key is SHA-256 of a passphrase from `AES_ENCRYPTION_KEY` env var (provided via systemd `EnvironmentFile=/etc/crm.env`). Startup validation rejects known dev keys (`dev-insecure-32byte-key-replace!`, `changeme`, etc.) and short passphrases.

---

## 6. Reliability Engineering Highlights

Recent stability work that turned operator-visible incidents into postmortems and code:

### 6.1 Spring Data JPA `@Modifying` / `@Transactional` audit

**Symptom** (2026-05-29): every scheduled broadcast was failing every row with `TransactionRequiredException: Executing an update/delete query`.

**Root cause**: 3 `@Modifying` queries in `BroadcastRepository` (`incrementCounters`, `markCompletedIfDone`, `flipToCompletedIfNoQueued`) were called from the worker thread pool — which is NOT in a Spring transaction. The queries themselves lacked `@Transactional`.

**Fix**: added `@Transactional` to each. Then audited **all** repositories:

| Repository | @Modifying queries | After audit |
|---|---|---|
| `InboundMailLogRepository` | 2 | ✅ all @Transactional |
| `MessageRepository` | 5 | ✅ all @Transactional |
| `BroadcastRepository` | 3 | ✅ all @Transactional |
| `AuditLogRepository` | 1 | ✅ all @Transactional |
| `HomeHtmlRepository` | 1 | ✅ all @Transactional |
| `CrmUserRepository` | 3 | ✅ all @Transactional |
| `CarrierUserBindingRepository` | 5 | ✅ all @Transactional |

Found 1 additional latent bug: `CarrierUserBindingRepository.deleteOlderThan` was called from `ScheduledTaskService.purgeExpiredBindings()` (cron 03:00 daily, no method-level `@Transactional`). Masked in production because the operator hadn't enabled `binding.auto_expire`, but would have thrown on first activation.

**Lesson** (saved to memory for future-me): defensive `@Transactional` on every `@Modifying` is the safe default. Caller-side coverage works today but is fragile against refactoring.

### 6.2 Restart safety protocol

Memory file `feedback_never_restart_during_import.md` codifies the rule learned from real incidents:

1. **5-minute journal window check**: `journalctl -u crm --since "5 minutes ago" | grep -c "insert into CRM_USER"` must be 0.
2. **Row-growth check**: `SELECT COUNT(*) FROM CRM_USER WHERE created_at >= DATE_SUB(NOW(), INTERVAL 2 MINUTE)` must be 0.
3. **Batch restarts**: never restart twice within 60 seconds (caused 2-3 min user-visible downtime on 2026-05-27).

The 30s `TimeoutStopSec` on the systemd unit was extending shutdowns past the SIGKILL — operator-installable drop-in raises it to 90s.

### 6.3 Defensive code patterns codified across the codebase

- Every `@Modifying` carries `@Transactional` (defense in depth).
- Every IMAP fetcher pre-drains bounces before fetching real replies.
- Every inbound match validates: (1) TO in pool, (2) FROM is a registered user, (3) user is ACTIVE, (4) user has outbound history, (5) explicit `CARRIER_USER_BINDING` exists, (6) reply post-dates our first send to them, (7) TO header doesn't CC anti-spam.
- Pool-churn grace defers (vs. drops) inbound mail during operator pool re-imports.

---

## 7. Code Quality & Testing

**Test suite**: 186 tests, JUnit 5 + Mockito + AssertJ. All passing post-recent-changes.

```
[INFO] Tests run: 186, Failures: 0, Errors: 0, Skipped: 1
[INFO] BUILD SUCCESS
```

**Highlights**:
- `InboundMailServiceTest` — 21 tests covering each rejection reason + acceptance paths
- `ScheduledTaskServiceTest` — verifies snapshot semantics + cancel-race behavior
- `HttpRelayOutboundMailServiceTest`, `DirectSmtpRelayOutboundMailServiceTest` — relay-side logic
- `PlaceholderServiceTest` — `%name%` / `%amount%` / `%reply_url%` substitution
- `CarrierPoolServiceTest` — pool CRUD + CSV import path
- `ReplyPageSettingServiceXssTest` — operator-pasted HTML hardening

**Conventions**:
- Mocks default to the happy-path in `setUp()`. Tests that exercise rejection branches override individual stubs.
- Real Date/Time math uses `LocalDateTime.now().minusYears(10)` as far-past defaults to keep semantic intent visible.

**Static checks**: enforced by review-time discipline; not yet automated in CI. Future work: add Checkstyle + SpotBugs.

---

## 8. Operations & Deployment

### 8.1 Single-server topology

Production runs on one Sakura Cloud VPS (103.96.120.13):
- 4 GB RAM, 4 vCPU, 100 GB SSD
- AlmaLinux 9
- nginx (TLS via Let's Encrypt) → 127.0.0.1:50000 (CRM JVM)
- podman MySQL container as a **user** systemd unit (`container-crm-mysql.service`)
- Python fetchers + JVM as **system** systemd units

### 8.2 systemd unit hierarchy

```
crm.service                          ← Spring Boot fat-jar
softbank-inbound-fetcher.timer       ← fires every 2 min
└── softbank-inbound-fetcher.service ← Python, POSTs to webhook
au-inbound-fetcher.timer             ← same, for au
└── au-inbound-fetcher.service
softbank-env-install.path            ← inotify on /tmp/softbank-fetcher.env.new
└── softbank-env-install.service     ← root, installs to /etc
```

Drop-ins live under `/etc/systemd/system/<unit>.d/`. Operator-installable units (3 of them) ship under `deploy/systemd/` in the repo with a README.

### 8.3 Build & deploy

```bash
mvn -q -DskipTests package        # produces target/crm.jar
sudo systemctl restart crm        # picks up the new jar
```

**Risk patterns documented**:
- Don't `mvn package` while a CSV import is running (overwrites the running jar, breaks LaunchedURLClassLoader at runtime).
- Don't restart while the dispatcher has SENDING messages.
- Batch multiple changes — one build, one restart.

### 8.4 Branching & PR flow

```
main ────────────────────────────────────────
   └── feat/imap-env-sync ── (this branch, 22 commits, ready to merge)
```

PRs reviewed via GitHub. Commit messages follow the format:
```
<type>(<scope>): <summary>

<context — what symptom, what root cause>
<fix description>
<verification>

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
```

---

## 9. Notable Design Decisions

### 9.1 Why server-rendered Thymeleaf over an SPA

**Decision**: server-rendered HTML, Thymeleaf templates, vanilla JS for sprinkles. No React, no Vue.

**Reasoning**:
- Operator UI has ~10 main screens. SPA's complexity tax doesn't pay off at this scale.
- Form-heavy workflow (broadcast send, user search, settings). HTML forms are ideal.
- One engineer ships and operates this system; tighter stack = less context to hold.
- TLS + nginx + session cookies give us CSRF-friendly auth without SPA token plumbing.

**Trade-off acknowledged**: any future mobile operator-side use will need a different surface. Today: out of scope.

### 9.2 Why per-row commit during CSV import

`CrmUserService.importCsv()` does NOT wrap the whole import in `@Transactional`. Each row's `repository.save(u)` opens its own implicit JpaRepository transaction.

**Reasoning**: if the JVM dies mid-import (e.g. operator does an unsafe restart), the rows committed so far are preserved. A single big transaction would lose every uncommitted row on the kill.

**Documented in memory** as a no-go pattern to "refactor for atomicity later."

### 9.3 Why systemd path-watcher instead of polling

For env file sync, alternatives were:
- Polling cron every 1 minute → 0-60s latency, wasteful CPU.
- HTTP webhook from JVM to a root-running listener → requires writing yet another daemon.
- Systemd path-unit (inotify-driven) → 0-2s latency, no extra process.

Chosen: path-unit. Single OS feature, atomic file replace, magic-header safety check, observable via `journalctl -u softbank-env-install.service`.

### 9.4 Why strict binding instead of "FROM uniquely identifies user"

A 2026-05 refactor relaxed the binding requirement, reasoning that the FROM address uniquely identifies a user. Two weeks later the operator reported stale-mailbox cross-contamination (covered in §5.4 / §5.5).

Lesson: when a recycled carrier mailbox + a user's recycled address pair into something we didn't send, "FROM uniquely identifies user" is wrong. Re-enforced strict binding 2026-05-27. Saved as a memory pin to resist future re-relaxation requests without re-considering this incident.

### 9.5 Why Japanese-language operator UI

The operator is a Japanese business operating in the Japanese carrier ecosystem. UI text, error messages, dashboard labels, and the few report formats we produce are all Japanese. Tests and code comments are English (engineering audience), commit messages mixed.

---

## 10. Future Work

Concrete items on the backlog (prioritized):

1. **Background-resumable import** — refactor CSV import to persist a cursor so it can survive restart. Eliminates the per-row-commit + don't-restart-during-import operational burden.
2. **Dashboard backlog widget** — surface IMAP UNSEEN counts per pool address so the operator can see queue pressure before it becomes a complaint.
3. **CI pipeline** — currently no automated build/test on push. Add GitHub Actions to run `mvn test` on every PR.
4. **Phase B–G UI overhaul** — folder system, 4-pane reply UI, dashboard analytics charts. Tracked in plan file; partially shipped opportunistically.
5. **Container-based deploy** — move the JVM into a podman container alongside MySQL for cleaner system isolation. Currently runs as a centos-user fat-jar.
6. **Observability** — add Micrometer metrics + Prometheus scrape endpoint. Currently observability is journalctl + ad-hoc SQL.

---

## 11. Repository Layout

```
crm-platform/
├── pom.xml                                  ← Maven build
├── src/main/java/com/crm/
│   ├── CrmApplication.java                  ← Spring Boot entry point
│   ├── config/                              ← Security, MVC, scheduling config
│   ├── controller/         (18 files)       ← Spring MVC endpoints
│   ├── dto/                                 ← Form-backing objects
│   ├── entity/             (21 files)       ← JPA entities
│   ├── interceptor/                         ← Auth, CSRF, audit-log
│   ├── repository/         (21 files)       ← Spring Data JPA repos
│   ├── service/            (36 files)       ← Business logic
│   └── util/                                ← AES, logging helpers, etc.
├── src/main/resources/
│   ├── application.yml                      ← Boot config
│   ├── schema.sql                           ← DDL (mode=always reapplies idempotently)
│   ├── data.sql                             ← Seed (1 admin user)
│   └── templates/          (46 files)       ← Thymeleaf views
├── src/test/java/                           ← 186 tests
├── deploy/
│   ├── softbank-inbound-fetcher.py          ← operator-installable Python
│   ├── au-inbound-fetcher.py
│   ├── install-fetchers.sh                  ← one-shot installer for the above
│   └── systemd/
│       ├── softbank-env-install.{service,path}
│       ├── crm-service.d/timeout.conf
│       └── README.md
└── target/crm.jar                           ← build output (gitignored)
```

**Stats**:
- 19,209 lines of Java across 147 files
- 46 Thymeleaf templates
- 186 tests, all passing
- 100 commits on this branch

---

## 12. Key Files to Read in Order (Recommended Tour)

For a reader who wants to grok the architecture in 30 minutes:

1. `pom.xml` — confirm the stack
2. `application.yml` — boot config + scheduling knobs
3. `entity/CrmUser.java`, `entity/Message.java`, `entity/Broadcast.java`, `entity/CarrierAddressPool.java`, `entity/CarrierUserBinding.java` — domain model
4. `service/InboundMailService.java` — the inbound matching pipeline (every reject reason has a story)
5. `service/BroadcastService.java` — broadcast lifecycle: createAndQueue → schedule → dispatch → finalize
6. `service/ScheduledTaskService.java` — 6 scheduled tasks, the dispatcher worker pool
7. `service/ImapEnvSyncService.java` — the AES-encrypted env file generator
8. `repository/BroadcastRepository.java` — the `@Modifying` + `@Transactional` discipline
9. `controller/SettingController.java` — operator-facing settings (IMAP sync, folders, retention)
10. `util/AesEncryptionUtil.java` — AES-256-GCM credential encryption

---

## 13. Demonstrable Skills (For Portfolio)

This project demonstrates working knowledge of:

- **Java enterprise patterns**: Spring Boot, Spring Data JPA, dependency injection, transaction management, scheduled tasks, custom interceptors.
- **Database design**: M:N relationships, denormalized counters with atomic increment via `@Modifying`, race-condition handling.
- **Concurrency**: thread pools, `ConcurrentHashMap` caching across worker threads, race-condition gates via re-fetch.
- **Spring `@Transactional` discipline**: when self-tx works (single `JpaRepository.save()`), when caller-tx works, when neither works (worker pool calling `@Modifying`).
- **Linux systemd**: service units, timers, path units, drop-ins, privilege boundaries (`NoNewPrivileges`, file permissions, oneshot promotion).
- **Cryptography in production**: AES-256-GCM, key derivation, dev-key rejection, env-var-based key delivery.
- **Email protocol engineering**: IMAP UNSEEN flagging, RFC 5322 Date parsing, MIME multipart handling, raw header extraction.
- **Operations under failure**: postmortems on TX exceptions, JVM restart safety protocols, defensive code patterns codified in memory.
- **Incident response**: 2-hour reply delay diagnosed via timeline reconstruction from `Received:` headers + journal correlation.
- **Code review judgment**: when to remove vs. refactor (snapshot exclusion logic), when to defensively widen scope of a fix (`@Transactional` across all 7 repositories), when to ship a feature gated on operator decision (auto-suspend on spam complaint).

---

*This documentation reflects the state of the platform as of 2026-05-31.*
*Branch: `feat/imap-env-sync` (22 commits since main)*
