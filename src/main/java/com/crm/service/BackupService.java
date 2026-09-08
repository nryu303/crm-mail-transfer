package com.crm.service;

import com.crm.entity.CrmSetting;
import com.crm.repository.CrmSettingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Scheduled DB backup: {@code mysqldump | gzip} into {@code /home/centos/crm-backups} as
 * {@code crm-<timestamp>.sql.gz} — the exact filename pattern {@link SystemStatsController}
 * already reads for the dashboard's "最新バックアップ" panel (that panel existed before any
 * job actually wrote files there; this service is what makes it non-empty).
 *
 * <p>Disabled by default — an operator must explicitly turn it on from 設定, since it writes
 * a full DB dump to local disk on a schedule and disk headroom on this box is limited
 * (2026-09-09 client request also asked for a capacity warning alongside the schedule UI).
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    /** Must stay under crm.service's ReadWritePaths= (see the systemd unit's sandbox
     *  directives) — ProtectHome=read-only blocks writes anywhere else under /home/centos. */
    private static final String BACKUP_DIR = "/home/centos/crm-platform/crm-backups";
    private static final String KEY_ENABLED = "backup.enabled";
    private static final String KEY_INTERVAL_HOURS = "backup.intervalHours";
    private static final String KEY_RETENTION_COUNT = "backup.retentionCount";
    private static final String KEY_LAST_RUN_AT = "backup.lastRunAt";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final CrmSettingRepository settingRepository;
    private final String dbName;
    private final String dbUser;
    private final String dbPassword;

    public BackupService(CrmSettingRepository settingRepository,
                          @Value("${spring.datasource.username}") String dbUser,
                          @Value("${spring.datasource.password}") String dbPassword,
                          @Value("${app.backup.db-name:crm}") String dbName) {
        this.settingRepository = settingRepository;
        this.dbUser = dbUser;
        this.dbPassword = dbPassword;
        this.dbName = dbName;
    }

    public static class Config {
        public boolean enabled;
        public int intervalHours;
        public int retentionCount;
        public LocalDateTime lastRunAt;
    }

    public Config getConfig() {
        Config c = new Config();
        c.enabled = "true".equals(settingValue(KEY_ENABLED));
        c.intervalHours = parseIntOr(settingValue(KEY_INTERVAL_HOURS), 24);
        c.retentionCount = parseIntOr(settingValue(KEY_RETENTION_COUNT), 7);
        String lastRun = settingValue(KEY_LAST_RUN_AT);
        c.lastRunAt = (lastRun == null || lastRun.isEmpty()) ? null : LocalDateTime.parse(lastRun);
        return c;
    }

    @Transactional
    public void saveConfig(boolean enabled, int intervalHours, int retentionCount) {
        if (intervalHours < 1) intervalHours = 1;
        if (retentionCount < 1) retentionCount = 1;
        setSetting(KEY_ENABLED, Boolean.toString(enabled));
        setSetting(KEY_INTERVAL_HOURS, Integer.toString(intervalHours));
        setSetting(KEY_RETENTION_COUNT, Integer.toString(retentionCount));
    }

    /** Called by the hourly scheduler tick — runs a backup if enabled and due. */
    public void runIfDue(LocalDateTime now) {
        Config c = getConfig();
        if (!c.enabled) return;
        if (c.lastRunAt != null && c.lastRunAt.plusHours(c.intervalHours).isAfter(now)) return;
        runBackupNow();
    }

    /** Executes mysqldump|gzip synchronously and prunes old backups beyond the retention count.
     *  Also callable directly from a "今すぐバックアップ" manual-trigger button. */
    @Transactional
    public void runBackupNow() {
        File dir = new File(BACKUP_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            log.warn("BackupService: could not create backup dir {}", BACKUP_DIR);
            return;
        }
        String fileName = "crm-" + LocalDateTime.now().format(TS) + ".sql.gz";
        File out = new File(dir, fileName);
        try {
            ProcessBuilder dumpPb = new ProcessBuilder("mysqldump",
                    "--single-transaction", "--quick", "-u", dbUser, dbName);
            dumpPb.environment().put("MYSQL_PWD", dbPassword);
            dumpPb.redirectErrorStream(false);
            Process dump = dumpPb.start();

            ProcessBuilder gzipPb = new ProcessBuilder("gzip", "-c");
            gzipPb.redirectOutput(out);
            Process gzip = gzipPb.start();

            // Pipe mysqldump stdout -> gzip stdin on a worker thread so neither process blocks.
            Thread pipe = new Thread(() -> {
                try (java.io.InputStream in = dump.getInputStream();
                     java.io.OutputStream gzIn = gzip.getOutputStream()) {
                    in.transferTo(gzIn);
                } catch (IOException ignored) {
                    // stream closed when either process exits early — surfaced via exit codes below
                }
            }, "backup-pipe");
            pipe.start();

            int dumpExit = dump.waitFor();
            pipe.join();
            int gzipExit = gzip.waitFor();

            if (dumpExit != 0 || gzipExit != 0) {
                String err = new String(dump.getErrorStream().readAllBytes());
                log.warn("BackupService: mysqldump exit={} gzip exit={} stderr={}", dumpExit, gzipExit, err);
                out.delete();
                return;
            }

            setSetting(KEY_LAST_RUN_AT, LocalDateTime.now().toString());
            log.info("BackupService: wrote {} ({} bytes)", fileName, out.length());
            pruneOld(dir);
        } catch (IOException | InterruptedException e) {
            log.warn("BackupService: backup failed: {}", e.toString());
            out.delete();
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    private void pruneOld(File dir) {
        Config c = getConfig();
        File[] files = dir.listFiles((d, name) -> name.startsWith("crm-") && name.endsWith(".sql.gz"));
        if (files == null || files.length <= c.retentionCount) return;
        List<File> sorted = new ArrayList<>(Arrays.asList(files));
        sorted.sort(Comparator.comparingLong(File::lastModified));
        int toDelete = sorted.size() - c.retentionCount;
        for (int i = 0; i < toDelete; i++) {
            if (sorted.get(i).delete()) {
                log.info("BackupService: pruned old backup {}", sorted.get(i).getName());
            }
        }
    }

    private String settingValue(String key) {
        return settingRepository.findBySettingKey(key).map(CrmSetting::getSettingValue).orElse(null);
    }

    private void setSetting(String key, String value) {
        CrmSetting s = settingRepository.findBySettingKey(key).orElseGet(() -> {
            CrmSetting ns = new CrmSetting();
            ns.setSettingKey(key);
            ns.setDescription("DB backup schedule setting: " + key);
            ns.setUpdatedAt(LocalDateTime.now());
            return ns;
        });
        s.setSettingValue(value);
        s.setUpdatedAt(LocalDateTime.now());
        settingRepository.save(s);
    }

    private static int parseIntOr(String v, int fallback) {
        if (v == null || v.trim().isEmpty()) return fallback;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return fallback; }
    }
}
