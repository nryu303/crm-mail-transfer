package com.crm.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.regex.Pattern;

/**
 * Requests (and reports the status of) Let's Encrypt cert + nginx HTTPS block
 * auto-provisioning for a 外部リンクドメイン生成 row.
 *
 * <p>The JVM runs as {@code centos} with {@code NoNewPrivileges=true}, so it can't run
 * certbot or reload nginx directly. Same stage-to-/tmp + root-owned systemd path-watcher
 * pattern as {@link ImapEnvSyncService}: this service writes the bare domain to a staging
 * file; {@code external-link-domain-cert-issue.path} (root) picks it up, runs
 * {@code deploy/systemd/certbot-issue-domain-cert.sh}, and writes a result file this
 * service polls. See deploy/systemd/README.md for the full flow and install steps.
 */
@Service
public class ExternalLinkDomainCertService {

    private static final Logger log = LoggerFactory.getLogger(ExternalLinkDomainCertService.class);

    private static final String REQUEST_PATH = "/tmp/external-link-domain-cert-request.new";
    private static final String RESULT_PATH_PREFIX = "/tmp/external-link-domain-cert-result.";

    /** Mirrors the shell script's validation — reject obviously-bad input before even
     *  staging the file, so a malformed value never reaches the root-run script. */
    private static final Pattern HOSTNAME_PATTERN = Pattern.compile(
            "^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$");

    /**
     * Stage a cert-issuance request for the given bare host (no scheme). No-op (returns
     * false) if the host doesn't look like a valid hostname — never stage garbage for the
     * root-side script to see. Failures to write the staging file are logged, not thrown:
     * this must never block the admin's create/activate action just because /tmp is
     * momentarily unwritable.
     */
    public boolean requestCertificate(String host) {
        if (host == null || !HOSTNAME_PATTERN.matcher(host.trim().toLowerCase()).matches()) {
            log.warn("Refusing to request a cert for invalid-looking host: {}", host);
            return false;
        }
        String normalized = host.trim().toLowerCase();
        try {
            Files.write(Paths.get(REQUEST_PATH),
                    (normalized + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            // Clear any stale result from a previous request for this host so status()
            // doesn't report a leftover SUCCESS/FAILED before the new run finishes.
            Files.deleteIfExists(Paths.get(RESULT_PATH_PREFIX + normalized));
            log.info("Staged external-link-domain cert request for {}", normalized);
            return true;
        } catch (IOException e) {
            log.warn("Failed to stage cert request for {}: {}", normalized, e.toString());
            return false;
        }
    }

    /** Current status for a host: PENDING (no result file yet — request in flight or the
     *  path-watcher isn't installed), SUCCESS, or a FAILED_* code from the script. */
    public String status(String host) {
        if (host == null) return "PENDING";
        String normalized = host.trim().toLowerCase();
        Path result = Paths.get(RESULT_PATH_PREFIX + normalized);
        try {
            if (!Files.exists(result)) return "PENDING";
            String content = new String(Files.readAllBytes(result), StandardCharsets.UTF_8).trim();
            return content.isEmpty() ? "PENDING" : content;
        } catch (IOException e) {
            return "PENDING";
        }
    }

    public boolean isSuccess(String host) { return "SUCCESS".equals(status(host)); }
}
