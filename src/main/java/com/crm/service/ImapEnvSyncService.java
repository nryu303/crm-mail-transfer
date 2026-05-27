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

    private final CarrierAddressPoolRepository poolRepository;
    private final AesEncryptionUtil aes;

    public ImapEnvSyncService(CarrierAddressPoolRepository poolRepository, AesEncryptionUtil aes) {
        this.poolRepository = poolRepository;
        this.aes = aes;
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
