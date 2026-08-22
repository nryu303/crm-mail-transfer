package com.crm.service;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import com.crm.util.LogSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.annotation.PostConstruct;

/**
 * 転送機 HTTP adapter. Two-step delivery:
 *
 *   1. Write the raw RFC5322 MIME message to {@code <mail-source-dir>/<uuid>.eml} on the
 *      relay host via SSH (the relay's mail.source.dir, default {@code /tmp/mailsource}).
 *   2. POST {@code email=<uuid>.eml} (just the basename, NOT a path) form-encoded to
 *      {@code POST /api/mail/received}. The relay reads the file, queues it, and its
 *      MailTimerWatchful worker delivers via carrier SMTP.
 *
 * The relay's {@code email=...} parameter is the FILENAME within {@code mail.source.dir},
 * not the raw mail content. We confirmed this against the running relay on 2026-04-27.
 *
 * Active when {@code app.outbound.adapter=relay}.
 */
@Service
@Primary
@ConditionalOnProperty(name = "app.outbound.adapter", havingValue = "relay")
public class HttpRelayOutboundMailService implements OutboundMailService {

    private static final Logger log = LoggerFactory.getLogger(HttpRelayOutboundMailService.class);
    private static final Charset ISO_8859_1 = StandardCharsets.ISO_8859_1;
    private static final DateTimeFormatter RFC5322_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);

    /**
     * Whitelist for {@code mail-source-dir}. The directory string is interpolated into a
     * shell command (see {@link #uploadViaSsh}); without this gate, an env-controlled value
     * containing a single quote could break out of the surrounding quotes and execute
     * arbitrary commands on the relay host. Pattern: must be absolute, only safe chars,
     * no shell metacharacters, no '..'.
     */
    private static final Pattern SAFE_DIR_PATTERN = Pattern.compile("^/[A-Za-z0-9._/-]+$");

    private final String endpointUrl;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final String sshHost;
    private final String sshUser;
    private final String sshPassword;
    private final String mailSourceDir;
    private final int sshTimeoutSec;
    private final SmtpOutboundMailService smtpFallback;
    private final DomainSettingService settingService;
    private final SenderNameResolver senderNameResolver;
    private final com.crm.repository.RelayServerRepository relayServerRepository;
    private final LocalPostfixOutboundMailService localPostfix;
    private final DirectSmtpRelayOutboundMailService directSmtp;

    public HttpRelayOutboundMailService(
            @Value("${app.relay-server.url}") String endpointUrl,
            @Value("${app.relay-server.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${app.relay-server.read-timeout-ms:15000}") int readTimeoutMs,
            @Value("${app.relay-server.ssh-host:}") String sshHost,
            @Value("${app.relay-server.ssh-user:root}") String sshUser,
            @Value("${app.relay-server.ssh-password:}") String sshPassword,
            @Value("${app.relay-server.mail-source-dir:/tmp/mailsource}") String mailSourceDir,
            @Value("${app.relay-server.ssh-timeout-sec:15}") int sshTimeoutSec,
            SmtpOutboundMailService smtpFallback,
            DomainSettingService settingService,
            SenderNameResolver senderNameResolver,
            com.crm.repository.RelayServerRepository relayServerRepository,
            LocalPostfixOutboundMailService localPostfix,
            DirectSmtpRelayOutboundMailService directSmtp) {
        this.endpointUrl = endpointUrl;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.sshHost = sshHost;
        this.sshUser = sshUser;
        this.sshPassword = sshPassword;
        this.mailSourceDir = mailSourceDir;
        this.sshTimeoutSec = sshTimeoutSec;
        this.smtpFallback = smtpFallback;
        this.settingService = settingService;
        this.senderNameResolver = senderNameResolver;
        this.relayServerRepository = relayServerRepository;
        this.localPostfix = localPostfix;
        this.directSmtp = directSmtp;
    }

    /** Hostname (or IP) portion of the configured relay URL — used by the settings UI to flag
     *  which RELAY_SERVER row is the one currently being talked to. */
    public String getActiveRelayHost() { return hostFromUrl(endpointUrl); }

    /**
     * The first RELAY_SERVER row marked {@code IS_ACTIVE=1}. Returns null when nothing is
     * active — the dispatcher then falls back to local postfix.
     * Read on every send so a settings change takes effect immediately.
     */
    private com.crm.entity.RelayServer pickActiveRelay() {
        java.util.List<com.crm.entity.RelayServer> all = relayServerRepository.findAllByOrderByNameAsc();
        for (com.crm.entity.RelayServer r : all) {
            if (Boolean.TRUE.equals(r.getIsActive())) return r;
        }
        return null;
    }

    /**
     * Replace the @-domain in {@code fromAddress} with the configured FROM base domain when set.
     * Returns the input unchanged when the setting is empty (default behaviour: use the carrier address).
     */
    private String applyFromDomainOverride(String fromAddress) {
        String customDomain = settingService.getFromBaseDomain();
        if (customDomain == null || customDomain.trim().isEmpty()) return fromAddress;
        if (fromAddress == null || fromAddress.indexOf('@') < 0) return fromAddress;
        String localPart = fromAddress.substring(0, fromAddress.indexOf('@'));
        return localPart + "@" + customDomain.trim();
    }

    /** Build a copy of {@code req} with a different fromAddress. Other fields are preserved. */
    private static OutboundRequest rewriteFrom(OutboundRequest req, String newFromAddress) {
        return new OutboundRequest(newFromAddress, req.toAddress, req.subject, req.body,
                req.smtpHost, req.smtpPort, req.smtpUsername, req.smtpPassword);
    }

    @PostConstruct
    public void validateConfig() {
        if (mailSourceDir == null || !SAFE_DIR_PATTERN.matcher(mailSourceDir).matches()) {
            throw new IllegalStateException(
                    "Invalid RELAY_MAIL_SOURCE_DIR: must be an absolute path with only "
                  + "[A-Za-z0-9._/-]; got: " + mailSourceDir);
        }
        if (mailSourceDir.contains("..")) {
            throw new IllegalStateException(
                    "RELAY_MAIL_SOURCE_DIR must not contain '..': " + mailSourceDir);
        }
    }

    @Override
    public SendResult send(OutboundRequest req) {
        // Apply FROM-domain override + sender-name policy regardless of which transport we end up using.
        String displayName = senderNameResolver.resolve();
        String fromAddress = applyFromDomainOverride(req.fromAddress);
        OutboundRequest effective = (fromAddress.equals(req.fromAddress)) ? req : rewriteFrom(req, fromAddress);

        // Outbound dispatch — first active RELAY_SERVER row decides:
        //   • IP matches the legacy HTTP-bridge endpoint (env RELAY_URL host) → use this class's
        //     SSH+POST bridge (existing behaviour, kept for the 133.88.116.190 deployment).
        //   • Other IP → direct SMTP to that host:port (e.g. AMG 157.7.89.36).
        //   • No active row → host's local postfix delivers directly via MX lookup.
        com.crm.entity.RelayServer activeRelay = pickActiveRelay();
        if (activeRelay == null) {
            log.info("[OUTBOUND] no active RELAY_SERVER → local postfix for to={}",
                    LogSafe.of(effective.toAddress));
            return localPostfix.send(effective);
        }

        String bridgeHost = hostFromUrl(endpointUrl);
        boolean useHttpBridge = activeRelay.getIpAddress() != null
                && activeRelay.getIpAddress().equalsIgnoreCase(bridgeHost);
        if (!useHttpBridge) {
            log.info("[OUTBOUND] using direct SMTP relay {}:{} for to={}",
                    LogSafe.of(activeRelay.getIpAddress()), activeRelay.getPort(), LogSafe.of(effective.toAddress));
            return directSmtp.sendVia(effective, activeRelay.getIpAddress(),
                    activeRelay.getPort() == null ? 25 : activeRelay.getPort(),
                    displayName, null);
        }

        // No Reply-To stamping: per operator policy, replies must flow through the carrier
        // address itself (rifc6h1c65@docomo.ne.jp) so deliverability scores aren't degraded
        // by a Reply-To that doesn't match the From domain. Carrier-side IMAP polling on the
        // relay is responsible for forwarding the reply to /api/inbound/receive-raw.
        String rawMime = buildRawMime(effective, displayName, null);
        String basename = "crm-" + UUID.randomUUID() + ".eml";

        // Step 1: write file to relay's watched directory via SSH
        try {
            String sshTarget = sshHost.isEmpty() ? hostFromUrl(endpointUrl) : sshHost;
            uploadViaSsh(sshTarget, basename, rawMime);
        } catch (Exception e) {
            log.warn("[RELAY] ssh upload failed: from={} to={} error={}",
                    LogSafe.of(req.fromAddress), LogSafe.of(req.toAddress), LogSafe.of(e.toString()));
            return SendResult.retriable("ssh upload failed: " + e.getMessage());
        }

        // Step 2: notify the relay with the basename. If the POST fails we delete the
        // file we just uploaded — otherwise /tmp/mailsource fills with orphans on every
        // network glitch.
        RequestConfig rc = RequestConfig.custom()
                .setConnectTimeout(connectTimeoutMs)
                .setConnectionRequestTimeout(connectTimeoutMs)
                .setSocketTimeout(readTimeoutMs)
                .build();
        String sshTarget = sshHost.isEmpty() ? hostFromUrl(endpointUrl) : sshHost;
        try (CloseableHttpClient http = HttpClientBuilder.create().setDefaultRequestConfig(rc).build()) {
            String body = "email=" + URLEncoder.encode(basename, ISO_8859_1.name());
            HttpPost post = new HttpPost(endpointUrl);
            post.setEntity(new StringEntity(body, ContentType.APPLICATION_FORM_URLENCODED.withCharset(ISO_8859_1)));

            try (CloseableHttpResponse resp = http.execute(post)) {
                int code = resp.getStatusLine().getStatusCode();
                String respBody = resp.getEntity() == null ? ""
                        : EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
                String trimmed = respBody == null ? "" : respBody.trim();
                if (code >= 200 && code < 300 && trimmed.startsWith("OK")) {
                    log.info("[RELAY] queued: from={} to={} file={} status={} resp={}",
                            LogSafe.of(req.fromAddress), LogSafe.of(req.toAddress),
                            basename, code, LogSafe.of(trimmed));
                    return SendResult.ok();
                }
                boolean transient_ = (code >= 500 && code < 600) || trimmed.startsWith("TEMPFAIL");
                log.warn("[RELAY] {}: from={} to={} file={} status={} resp={}",
                        transient_ ? "transient fail" : "permanent fail",
                        LogSafe.of(req.fromAddress), LogSafe.of(req.toAddress),
                        basename, code, LogSafe.of(truncate(trimmed, 300)));
                deleteRemoteFileBestEffort(sshTarget, basename);
                String msg = "relay returned " + code + ": " + truncate(trimmed, 200);
                return transient_ ? SendResult.retriable(msg) : SendResult.fail(msg);
            }
        } catch (java.net.SocketTimeoutException | java.net.ConnectException e) {
            log.warn("[RELAY] network error (retriable): from={} to={} error={}",
                    LogSafe.of(req.fromAddress), LogSafe.of(req.toAddress), LogSafe.of(e.toString()));
            deleteRemoteFileBestEffort(sshTarget, basename);
            return SendResult.retriable(e.toString());
        } catch (Exception e) {
            log.warn("[RELAY] error: from={} to={} error={}",
                    LogSafe.of(req.fromAddress), LogSafe.of(req.toAddress), LogSafe.of(e.toString()));
            deleteRemoteFileBestEffort(sshTarget, basename);
            return SendResult.fail(e.toString());
        }
    }

    /**
     * Pipe rawMime over stdin to {@code ssh user@host "cat > <dir>/<basename>"}.
     * When {@code ssh-password} is set we use {@code sshpass -e} so the password is read
     * from the {@code SSHPASS} env var instead of the cmdline (where {@code ps}/{@code /proc}
     * would expose it). When unset we rely on a pre-configured SSH key and {@code BatchMode=yes}.
     */
    private void uploadViaSsh(String host, String basename, String content) throws Exception {
        if (!isSafeBasename(basename)) {
            throw new IllegalArgumentException("unsafe basename: " + basename);
        }
        java.util.List<String> cmd = new java.util.ArrayList<>();
        if (!sshPassword.isEmpty()) {
            cmd.add("sshpass");
            cmd.add("-e"); // password from SSHPASS env, NOT cmdline
        }
        cmd.add("ssh");
        cmd.add("-o"); cmd.add("StrictHostKeyChecking=no");
        cmd.add("-o"); cmd.add("ConnectTimeout=" + Math.max(3, sshTimeoutSec / 2));
        cmd.add("-o"); cmd.add("BatchMode=" + (sshPassword.isEmpty() ? "yes" : "no"));
        cmd.add(sshUser + "@" + host);
        cmd.add("mkdir -p '" + mailSourceDir + "' && cat > '" + mailSourceDir + "/" + basename + "'");

        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        if (!sshPassword.isEmpty()) {
            pb.environment().put("SSHPASS", sshPassword);
        }
        Process p = pb.start();
        try (OutputStream os = p.getOutputStream()) {
            os.write(content.getBytes(StandardCharsets.UTF_8));
        }
        boolean done = p.waitFor(sshTimeoutSec, TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            throw new RuntimeException("ssh upload timed out after " + sshTimeoutSec + "s");
        }
        int rc = p.exitValue();
        if (rc != 0) {
            String err = new String(readAll(p.getInputStream()), StandardCharsets.UTF_8);
            throw new RuntimeException("ssh exited " + rc + ": " + truncate(err, 300));
        }
    }

    /** Best-effort cleanup of a file we wrote to the relay (used when the HTTP POST fails). */
    private void deleteRemoteFileBestEffort(String host, String basename) {
        if (!isSafeBasename(basename)) return;
        try {
            java.util.List<String> cmd = new java.util.ArrayList<>();
            if (!sshPassword.isEmpty()) { cmd.add("sshpass"); cmd.add("-e"); }
            cmd.add("ssh");
            cmd.add("-o"); cmd.add("StrictHostKeyChecking=no");
            cmd.add("-o"); cmd.add("ConnectTimeout=5");
            cmd.add("-o"); cmd.add("BatchMode=" + (sshPassword.isEmpty() ? "yes" : "no"));
            cmd.add(sshUser + "@" + host);
            cmd.add("rm -f '" + mailSourceDir + "/" + basename + "'");
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            if (!sshPassword.isEmpty()) pb.environment().put("SSHPASS", sshPassword);
            Process p = pb.start();
            p.waitFor(8, TimeUnit.SECONDS);
        } catch (Exception ignore) { /* best-effort */ }
    }

    /** Whitelist: only "crm-<uuid>.eml" style names. Prevents shell injection through basename. */
    private static boolean isSafeBasename(String s) {
        return s != null && s.matches("[A-Za-z0-9._-]{1,128}");
    }

    private static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        int n;
        while ((n = in.read(tmp)) > 0) buf.write(tmp, 0, n);
        return buf.toByteArray();
    }

    /** Strip {@code http(s)://} and any trailing port/path so we get a bare hostname. */
    private static String hostFromUrl(String url) {
        if (url == null) return "";
        int p = url.indexOf("://");
        String s = p >= 0 ? url.substring(p + 3) : url;
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(0, colon);
        return s;
    }

    /**
     * Minimal RFC5322 message wrapped as multipart/alternative (text/plain + text/html).
     * Both parts are UTF-8 with 8bit transfer encoding so the on-the-wire bytes don't carry
     * a {@code Content-Transfer-Encoding: base64} header that downstream relays
     * (carrier SMTP, AMG-style middleboxes) sometimes strip — leaving recipients staring
     * at raw "44GE44Gk44KC..." base64 chars. The HTML part also includes an in-body
     * {@code <meta charset="UTF-8">} so charset detection survives header rewrites.
     */
    static String buildRawMime(OutboundRequest req, String displayName, String replyToAddress) {
        String from = safe(req.fromAddress);
        String to = safe(req.toAddress);
        String subject = req.subject == null ? "" : req.subject;
        String body = req.body == null ? "" : req.body;
        String fromHeaderValue = (displayName == null || displayName.trim().isEmpty())
                ? from
                : "\"" + encodeHeader(displayName.trim()).replace("\"", "") + "\" <" + from + ">";

        String boundary = "=_crm_" + UUID.randomUUID();
        StringBuilder sb = new StringBuilder(body.length() + 1024);
        sb.append("From: ").append(fromHeaderValue).append("\r\n");
        sb.append("To: ").append(to).append("\r\n");
        if (replyToAddress != null && !replyToAddress.trim().isEmpty()) {
            sb.append("Reply-To: ").append(safe(replyToAddress)).append("\r\n");
        }
        sb.append("Subject: ").append(encodeHeader(subject)).append("\r\n");
        sb.append("Date: ").append(ZonedDateTime.now().format(RFC5322_DATE)).append("\r\n");
        sb.append("Message-ID: <").append(UUID.randomUUID()).append("@crm-platform>\r\n");
        sb.append("MIME-Version: 1.0\r\n");
        sb.append("Content-Type: multipart/alternative; boundary=\"").append(boundary).append("\"\r\n");
        sb.append("\r\n");

        // text/plain part — UTF-8 8bit, no base64 wrapper.
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Type: text/plain; charset=\"UTF-8\"\r\n");
        sb.append("Content-Transfer-Encoding: 8bit\r\n");
        sb.append("\r\n");
        sb.append(body);
        sb.append("\r\n");

        // text/html part — UTF-8 8bit with embedded meta charset so even if a relay rewrites
        // the outer Content-Type the recipient still picks up UTF-8 from the HTML itself.
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Type: text/html; charset=\"UTF-8\"\r\n");
        sb.append("Content-Transfer-Encoding: 8bit\r\n");
        sb.append("\r\n");
        sb.append("<!DOCTYPE html><html lang=\"ja\"><head><meta charset=\"UTF-8\">"
                + "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\"></head>"
                + "<body style=\"font-family:sans-serif;white-space:pre-wrap;line-height:1.6;\">");
        sb.append(htmlEscapeAndLinkify(body));
        sb.append("</body></html>\r\n");

        sb.append("--").append(boundary).append("--\r\n");
        return sb.toString();
    }

    /**
     * HTML-escape the given plaintext and turn bare {@code http(s)://...} runs into
     * anchor tags. Newlines are converted to explicit {@code <br>} tags rather than relying
     * solely on the surrounding {@code <body style="white-space:pre-wrap">} — several mail
     * clients (notably Gmail's HTML sanitiser) strip or ignore inline {@code white-space}
     * styling on ingest, which was collapsing every broadcast/reply body to one line for
     * recipients even though the CRM's own preview (which does honour the style) looked fine.
     * Explicit {@code <br>} survives that sanitisation because it's structural markup, not CSS.
     */
    private static String htmlEscapeAndLinkify(String s) {
        if (s == null || s.isEmpty()) return "";
        String escaped = s.replace("&", "&amp;")
                          .replace("<", "&lt;")
                          .replace(">", "&gt;")
                          .replace("\"", "&quot;")
                          .replace("'", "&#39;");
        java.util.regex.Pattern URL_RE = java.util.regex.Pattern.compile(
                "(https?://[^\\s<>\"']+)", java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher m = URL_RE.matcher(escaped);
        StringBuffer sb = new StringBuffer(escaped.length() + 64);
        while (m.find()) {
            String url = m.group(1);
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(
                    "<a href=\"" + url + "\">" + url + "</a>"));
        }
        m.appendTail(sb);
        return sb.toString().replaceAll("\r\n|\r|\n", "<br>\n");
    }

    /**
     * RFC 2047 "B" encoding for header values containing non-ASCII.
     * Always strip CR/LF first to prevent header injection via the Subject (e.g. an attacker
     * crafting "Hi\r\nBcc: leak@evil.com" — without this strip the relay sees an extra Bcc).
     */
    private static String encodeHeader(String value) {
        if (value == null || value.isEmpty()) return "";
        // Refuse anything that even starts to look like a fold-injected RFC2047 word.
        String stripped = value.replaceAll("[\r\n ]", " ");
        if (isAscii(stripped)) return stripped;
        String b64 = Base64.getEncoder().encodeToString(stripped.getBytes(StandardCharsets.UTF_8));
        return "=?UTF-8?B?" + b64 + "?=";
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) > 0x7F) return false;
        return true;
    }

    private static String safe(String s) { return s == null ? "" : s.replaceAll("[\r\n]", " "); }
    private static String truncate(String s, int n) {
        return s == null ? "" : (s.length() <= n ? s : s.substring(0, n) + "...");
    }
}
