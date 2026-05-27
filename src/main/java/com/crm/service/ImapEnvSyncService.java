package com.crm.service;

import com.crm.entity.CarrierAddressPool;
import com.crm.repository.CarrierAddressPoolRepository;
import com.crm.util.AesEncryptionUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the {@code /etc/softbank-fetcher.env} content from the live
 * {@link CarrierAddressPool} table by decrypting each row's SMTP password — which
 * per the 2026-05-14 verification doubles as the IMAP "メール接続用パスワード" on
 * SoftBank. Lets an admin synchronise the IMAP fetcher's account list with the
 * pool table in one click rather than hand-editing the env file every time a new
 * carrier address is registered.
 *
 * <p>The JVM runs as {@code centos} and the env file is root:root 0600, so this
 * service writes to a centos-owned staging path. The operator then moves the
 * file into place via {@code sudo install} (the admin UI calls a small helper
 * script for that step).
 */
@Service
public class ImapEnvSyncService {

    private static final Logger log = LoggerFactory.getLogger(ImapEnvSyncService.class);

    /** Staging path the JVM can write to without root. */
    public static final String STAGING_PATH = "/tmp/softbank-fetcher.env.new";
    /** Live env consumed by softbank-inbound-fetcher (read-only from the JVM, 0600 root:root). */
    public static final String LIVE_PATH = "/etc/softbank-fetcher.env";
    /** Same shape but for au (ezweb IMAP — see au-inbound-fetcher.py). */
    public static final String AU_STAGING_PATH = "/tmp/au-fetcher.env.new";
    public static final String AU_LIVE_PATH = "/etc/au-fetcher.env";

    private final CarrierAddressPoolRepository poolRepository;
    private final AesEncryptionUtil aes;

    public ImapEnvSyncService(CarrierAddressPoolRepository poolRepository, AesEncryptionUtil aes) {
        this.poolRepository = poolRepository;
        this.aes = aes;
    }

    /** Schedule both {@link #rebuildEnvFile()} (softbank) and {@link #rebuildAuEnvFile()}
     *  to run after the current transaction commits. No-op (executes immediately) if called
     *  outside a transaction. Designed for CarrierPoolService — pool create/update/delete
     *  should re-stage the env so the IMAP fetcher list stays in lock-step with the
     *  carrier-assignment table. Failures are logged at warn, not propagated, so a stuck
     *  filesystem can't roll back the underlying pool change. */
    public void triggerAfterCommit() {
        Runnable task = () -> {
            try { rebuildEnvFile(); }
            catch (Exception e) { log.warn("IMAP env auto-sync (softbank) failed: {}", e.toString()); }
            try { rebuildAuEnvFile(); }
            catch (Exception e) { log.warn("IMAP env auto-sync (au) failed: {}", e.toString()); }
        };
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronizationAdapter() {
                        @Override public void afterCommit() { task.run(); }
                    });
        } else {
            task.run();
        }
    }

    /** For the settings page: how many active SoftBank pool rows currently exist. */
    public long countActiveSoftbankPoolRows() {
        long n = 0;
        for (CarrierAddressPool p : poolRepository.findByIsActiveTrueOrderByIdAsc()) {
            if (p.getAddress() != null && p.getAddress().endsWith("@i.softbank.jp")) n++;
        }
        return n;
    }

    /** For the settings page: how many active au pool rows currently exist. */
    public long countActiveAuPoolRows() {
        long n = 0;
        for (CarrierAddressPool p : poolRepository.findByIsActiveTrueOrderByIdAsc()) {
            if ("au".equalsIgnoreCase(p.getCarrierCode())) n++;
        }
        return n;
    }

    /** Parametrised version of {@link #readLiveEnvFileInfo()} so the settings page can
     *  display both SoftBank and au counts.
     *
     *  @param livePath the OS path of the active env file consumed by the fetcher
     *  @param stagingPath the path the JVM writes into; checked as a fallback when the
     *         live file is unreadable (e.g. before the first install)
     *  @param accountVar env-var name to look for (SOFTBANK_IMAP_ACCOUNTS / AU_IMAP_ACCOUNTS)
     */
    public EnvFileInfo readEnvFileInfoFor(String livePath, String stagingPath, String accountVar) {
        Path live = Paths.get(livePath);
        // Existence check works even when the file is 0600 root:root, as long as /etc itself
        // is world-readable (the default). Lets us tell "no env yet" from "we just can't read it".
        boolean liveExists = Files.exists(live);
        try {
            Path p = live;
            if (!Files.isReadable(p)) {
                p = Paths.get(stagingPath);
                if (!Files.isReadable(p)) {
                    // Pull mtime from live if possible (Files.getLastModifiedTime works on
                    // 0600 files via /etc's read perm); otherwise show '—'.
                    String mtime = "—";
                    if (liveExists) {
                        try {
                            mtime = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                                    .withZone(java.time.ZoneId.systemDefault())
                                    .format(Files.getLastModifiedTime(live).toInstant());
                        } catch (Exception ignored) { /* leave as '—' */ }
                    }
                    return new EnvFileInfo(0, mtime, liveExists);
                }
            }
            String content = new String(Files.readAllBytes(p), "UTF-8");
            int count = 0;
            String prefix = accountVar + "=";
            for (String line : content.split("\n")) {
                if (line.startsWith(prefix)) {
                    String v = line.substring(prefix.length()).trim();
                    if (v.isEmpty()) { count = 0; break; }
                    count = v.split(";").length;
                    break;
                }
            }
            java.nio.file.attribute.FileTime mt = Files.getLastModifiedTime(p);
            String mtimeStr = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(java.time.ZoneId.systemDefault())
                    .format(mt.toInstant());
            return new EnvFileInfo(count, mtimeStr, liveExists && !p.equals(live));
        } catch (Exception e) {
            log.debug("readEnvFileInfoFor({}) failed: {}", livePath, e.toString());
            return new EnvFileInfo(0, "—", liveExists);
        }
    }

    public static class EnvFileInfo {
        public final int accountCount;
        public final String mtimeDisplay;
        /** True when /etc/<file> exists but is unreadable by the JVM (centos vs root:root 0600).
         *  Lets the settings page distinguish "no env file at all" from "an env file exists but
         *  we counted via the staging fallback / can't count at all". */
        public final boolean liveExistsButUnreadable;
        public EnvFileInfo(int n, String m) { this(n, m, false); }
        public EnvFileInfo(int n, String m, boolean liveExistsButUnreadable) {
            this.accountCount = n; this.mtimeDisplay = m;
            this.liveExistsButUnreadable = liveExistsButUnreadable;
        }
    }

    /** Reads the live env file (0644 readable copy or via systemd path-install ACL) and
     *  reports how many accounts the IMAP fetcher will actually log into on its next tick.
     *  Returns (0, "—") when the file is missing or unreadable — that's expected on a fresh
     *  install before the first sync. */
    public EnvFileInfo readLiveEnvFileInfo() {
        try {
            Path p = Paths.get(LIVE_PATH);
            if (!Files.isReadable(p)) {
                // Fall back to staging path so the dashboard still shows something useful.
                p = Paths.get(STAGING_PATH);
                if (!Files.isReadable(p)) return new EnvFileInfo(0, "—");
            }
            String content = new String(Files.readAllBytes(p), "UTF-8");
            int count = 0;
            for (String line : content.split("\n")) {
                if (line.startsWith("SOFTBANK_IMAP_ACCOUNTS=")) {
                    String v = line.substring("SOFTBANK_IMAP_ACCOUNTS=".length()).trim();
                    if (v.isEmpty()) { count = 0; break; }
                    count = v.split(";").length;
                    break;
                }
            }
            java.nio.file.attribute.FileTime mt = Files.getLastModifiedTime(p);
            String mtimeStr = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(java.time.ZoneId.systemDefault())
                    .format(mt.toInstant());
            return new EnvFileInfo(count, mtimeStr);
        } catch (Exception e) {
            log.debug("readLiveEnvFileInfo failed: {}", e.toString());
            return new EnvFileInfo(0, "—");
        }
    }

    /** Returns a summary of what was written: total pool rows, included, skipped (bad
     *  password / inactive / non-softbank). */
    public Result rebuildEnvFile() throws IOException {
        StringBuilder body = new StringBuilder(4096);
        body.append("# SoftBank IMAP fetcher credentials — root:root 0600\n");
        body.append("# AUTO-GENERATED FROM CARRIER_ADDRESS_POOL via ImapEnvSyncService — do not hand-edit.\n");
        body.append("# To regenerate: /manager/settings/imap-env/sync (admin only).\n");
        body.append("# IMAP password == SMTP password for SoftBank carry-over mail (verified 2026-05-14).\n");
        body.append("SOFTBANK_IMAP_ACCOUNTS=");

        int total = 0, included = 0, skipped = 0;
        List<String> skippedAddrs = new ArrayList<>();
        boolean first = true;
        List<CarrierAddressPool> rows = poolRepository.findByIsActiveTrueOrderByIdAsc();
        for (CarrierAddressPool p : rows) {
            total++;
            String addr = p.getAddress();
            if (addr == null || !addr.endsWith("@i.softbank.jp")) {
                skipped++;
                continue;
            }
            String pw;
            try {
                pw = aes.decrypt(p.getSmtpPassword());
            } catch (Exception e) {
                log.warn("Failed to decrypt password for pool {}: {}", addr, e.toString());
                skipped++;
                skippedAddrs.add(addr);
                continue;
            }
            if (pw == null || pw.isEmpty()) {
                skipped++;
                skippedAddrs.add(addr);
                continue;
            }
            if (!first) body.append(';');
            body.append(addr).append('|').append(pw);
            first = false;
            included++;
        }
        body.append('\n');

        Path out = Paths.get(STAGING_PATH);
        Files.write(out, body.toString().getBytes("UTF-8"),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        log.info("ImapEnvSync: wrote {} (total={}, included={}, skipped={})",
                STAGING_PATH, total, included, skipped);
        return new Result(total, included, skipped, skippedAddrs, STAGING_PATH);
    }

    /** Same shape as {@link #rebuildEnvFile()} but for the au IMAP fetcher.
     *
     *  <p>Format difference: au's ezweb IMAP authenticates against the SMTP USERNAME, not
     *  the @au.com address (verified from {@code au-inbound-fetcher.py}). So we write
     *  {@code AU_IMAP_ACCOUNTS=<smtp_user>|<password>;...} rather than {@code <addr>|<pw>}.
     *
     *  <p>Safety: when the pool has zero au rows we do NOT write the staging file. The
     *  operator may have a manually-configured /etc/au-fetcher.env from before this
     *  service existed, and wiping it would silently disable au inbound. The first au
     *  pool row added (via the carrier-pool UI or CSV import) flips this on.
     */
    public Result rebuildAuEnvFile() throws IOException {
        StringBuilder body = new StringBuilder(2048);
        body.append("# AU IMAP fetcher credentials — root:root 0600\n");
        body.append("# AUTO-GENERATED FROM CARRIER_ADDRESS_POOL via ImapEnvSyncService — do not hand-edit.\n");
        body.append("# Format: AU_IMAP_ACCOUNTS=<smtp_username>|<password>;... (NOT email — see au-inbound-fetcher.py).\n");
        body.append("AU_IMAP_ACCOUNTS=");

        int total = 0, included = 0, skipped = 0;
        List<String> skippedAddrs = new ArrayList<>();
        boolean first = true;
        List<CarrierAddressPool> rows = poolRepository.findByIsActiveTrueOrderByIdAsc();
        for (CarrierAddressPool p : rows) {
            if (!"au".equalsIgnoreCase(p.getCarrierCode())) continue;
            total++;
            String smtpUser = p.getSmtpUsername();
            if (smtpUser == null || smtpUser.trim().isEmpty()) {
                skipped++;
                skippedAddrs.add(p.getAddress());
                continue;
            }
            String pw;
            try {
                pw = aes.decrypt(p.getSmtpPassword());
            } catch (Exception e) {
                log.warn("Failed to decrypt au password for pool {}: {}", p.getAddress(), e.toString());
                skipped++;
                skippedAddrs.add(p.getAddress());
                continue;
            }
            if (pw == null || pw.isEmpty()) {
                skipped++;
                skippedAddrs.add(p.getAddress());
                continue;
            }
            if (!first) body.append(';');
            body.append(smtpUser.trim()).append('|').append(pw);
            first = false;
            included++;
        }
        body.append('\n');

        if (total == 0) {
            log.info("ImapEnvSync(au): pool has 0 au rows; preserving existing /etc/au-fetcher.env (not writing staging).");
            return new Result(0, 0, 0, java.util.Collections.<String>emptyList(),
                    AU_STAGING_PATH + " (skipped — pool empty)");
        }

        Path out = Paths.get(AU_STAGING_PATH);
        Files.write(out, body.toString().getBytes("UTF-8"),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        log.info("ImapEnvSync(au): wrote {} (total={}, included={}, skipped={})",
                AU_STAGING_PATH, total, included, skipped);
        return new Result(total, included, skipped, skippedAddrs, AU_STAGING_PATH);
    }

    public static class Result {
        public final int total;
        public final int included;
        public final int skipped;
        public final List<String> skippedAddrs;
        public final String stagingPath;

        public Result(int total, int included, int skipped, List<String> skippedAddrs, String stagingPath) {
            this.total = total;
            this.included = included;
            this.skipped = skipped;
            this.skippedAddrs = skippedAddrs;
            this.stagingPath = stagingPath;
        }
    }
}
