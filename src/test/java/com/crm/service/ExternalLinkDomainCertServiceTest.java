package com.crm.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ExternalLinkDomainCertService} stages a request file for the root-side
 * certbot-issue-domain-cert.sh script (see deploy/systemd/README.md) and polls a result
 * file it writes back. Exercises real filesystem I/O against /tmp since that's the whole
 * point of the staging contract — no mocking the filesystem here.
 */
class ExternalLinkDomainCertServiceTest {

    private static final String REQUEST_PATH = "/tmp/external-link-domain-cert-request.new";

    private final ExternalLinkDomainCertService svc = new ExternalLinkDomainCertService();

    @AfterEach
    void cleanup() throws IOException {
        Files.deleteIfExists(Paths.get(REQUEST_PATH));
        Files.deleteIfExists(Paths.get("/tmp/external-link-domain-cert-result.ep84ti.jp"));
        Files.deleteIfExists(Paths.get("/tmp/external-link-domain-cert-result.invalid"));
    }

    @Test
    void requestCertificate_validHost_stagesRequestFile() throws IOException {
        boolean result = svc.requestCertificate("ep84ti.jp");

        assertThat(result).isTrue();
        Path p = Paths.get(REQUEST_PATH);
        assertThat(Files.exists(p)).isTrue();
        assertThat(new String(Files.readAllBytes(p), StandardCharsets.UTF_8).trim()).isEqualTo("ep84ti.jp");
    }

    @Test
    void requestCertificate_normalizesCaseAndWhitespace() throws IOException {
        svc.requestCertificate("  EP84TI.JP  ");

        String content = new String(Files.readAllBytes(Paths.get(REQUEST_PATH)), StandardCharsets.UTF_8).trim();
        assertThat(content).isEqualTo("ep84ti.jp");
    }

    @Test
    void requestCertificate_rejectsShellMetacharacters() {
        assertThat(svc.requestCertificate("evil.jp; rm -rf /")).isFalse();
        assertThat(svc.requestCertificate("$(whoami).jp")).isFalse();
        assertThat(svc.requestCertificate("../../../etc/passwd")).isFalse();
        assertThat(Files.exists(Paths.get(REQUEST_PATH))).isFalse();
    }

    @Test
    void requestCertificate_rejectsNullOrBlank() {
        assertThat(svc.requestCertificate(null)).isFalse();
        assertThat(svc.requestCertificate("")).isFalse();
        assertThat(svc.requestCertificate("   ")).isFalse();
    }

    @Test
    void status_noResultFile_returnsPending() {
        assertThat(svc.status("ep84ti.jp")).isEqualTo("PENDING");
    }

    @Test
    void status_resultFilePresent_returnsItsContent() throws IOException {
        Files.write(Paths.get("/tmp/external-link-domain-cert-result.ep84ti.jp"),
                "SUCCESS".getBytes(StandardCharsets.UTF_8));

        assertThat(svc.status("ep84ti.jp")).isEqualTo("SUCCESS");
        assertThat(svc.isSuccess("ep84ti.jp")).isTrue();
    }

    @Test
    void status_nullHost_returnsPending() {
        assertThat(svc.status(null)).isEqualTo("PENDING");
    }
}
