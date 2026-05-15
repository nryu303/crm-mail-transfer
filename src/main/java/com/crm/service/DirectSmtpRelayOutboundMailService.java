package com.crm.service;

import com.crm.util.LogSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;

/**
 * SMTP send to an arbitrary host:port without auth.
 *
 * Used when the active RELAY_SERVER row points to a relay other than the legacy
 * 133.88.116.190 HTTP-bridge — for example AMG (157.7.89.36) directly. The receiving
 * host is expected to whitelist our source IP (49.212.164.254) and accept unauthenticated
 * SMTP from us.
 *
 * Encoding strategy (after empirically observing AMG mangle outbound headers):
 *   • Send as multipart/alternative with both a text/plain part (UTF-8 + 8bit) and a
 *     text/html part (UTF-8 + 8bit, with a {@code <meta charset="UTF-8">} inside the
 *     HTML).
 *   • The HTML part carries the charset declaration *inside* the body content, so
 *     even if AMG rewrites the outer Content-Type / Content-Transfer-Encoding the
 *     recipient mailer can still detect UTF-8 from the embedded meta tag and from
 *     auto-detection on raw UTF-8 byte patterns.
 *   • text/plain stays for clients that don't render HTML; raw UTF-8 bytes are still
 *     auto-detected by modern mailers (Gmail, Outlook) when no charset is given.
 *
 * Earlier attempts and why they failed:
 *   1. Default JavaMail (UTF-8 + quoted-printable) — AMG dropped CTE, recipient saw
 *      "=E3=81=84..." literal QP escape sequences.
 *   2. UTF-8 + base64 — AMG dropped CTE, recipient saw raw base64 string.
 *   3. ISO-2022-JP + 7bit — AMG either dropped charset or recipient ignored it,
 *      producing garbled "$B$$$D$b..." JIS bytes.
 */
@Service
public class DirectSmtpRelayOutboundMailService {

    private static final Logger log = LoggerFactory.getLogger(DirectSmtpRelayOutboundMailService.class);
    private static final DateTimeFormatter RFC5322_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);

    public OutboundMailService.SendResult sendVia(OutboundMailService.OutboundRequest req,
                                                    String relayHost, int relayPort,
                                                    String displayName, String replyToAddress) {
        try {
            Properties props = new Properties();
            props.put("mail.smtp.host", relayHost);
            props.put("mail.smtp.port", String.valueOf(relayPort));
            props.put("mail.smtp.auth", "false");
            props.put("mail.smtp.connectiontimeout", "10000");
            props.put("mail.smtp.timeout", "30000");
            props.put("mail.smtp.writetimeout", "30000");
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.starttls.required", "false");
            // Allow JavaMail to negotiate 8BITMIME with the relay so 8-bit UTF-8 body parts
            // pass through without being downgraded to quoted-printable.
            props.put("mail.mime.allow8bitmime", "true");

            Session session = Session.getInstance(props);

            byte[] rawMime = buildRawMime(req, displayName, replyToAddress);
            MimeMessage msg = new MimeMessage(session, new ByteArrayInputStream(rawMime));
            // Don't call saveChanges — that would re-encode and undo our explicit transfer-encoding choices.
            Transport.send(msg);
            log.info("[DIRECT SMTP] sent: from={} ({}) to={} via={}:{} subject=[{}]",
                    LogSafe.of(req.fromAddress), LogSafe.of(displayName),
                    LogSafe.of(req.toAddress),
                    relayHost, relayPort, LogSafe.of(req.subject));
            return OutboundMailService.SendResult.ok();
        } catch (MessagingException e) {
            log.warn("[DIRECT SMTP] failed: from={} to={} via={}:{} error={}",
                    LogSafe.of(req.fromAddress), LogSafe.of(req.toAddress),
                    relayHost, relayPort, LogSafe.of(e.toString()));
            return e.getCause() instanceof java.net.SocketTimeoutException
                    || e.getCause() instanceof java.net.ConnectException
                    ? OutboundMailService.SendResult.retriable(e.getMessage())
                    : OutboundMailService.SendResult.fail(e.getMessage());
        } catch (Exception e) {
            log.warn("[DIRECT SMTP] unexpected error: {}", LogSafe.of(e.toString()), e);
            return OutboundMailService.SendResult.fail(e.toString());
        }
    }

    static byte[] buildRawMime(OutboundMailService.OutboundRequest req, String displayName, String replyToAddress) {
        String from = safe(req.fromAddress);
        String to = safe(req.toAddress);
        String subject = req.subject == null ? "" : req.subject;
        String body = req.body == null ? "" : req.body;

        String encodedDisplay = (displayName == null || displayName.trim().isEmpty())
                ? null
                : encodeHeaderUtf8(displayName.trim());
        String fromHeaderValue = (encodedDisplay == null)
                ? from
                : "\"" + encodedDisplay.replace("\"", "") + "\" <" + from + ">";

        String boundary = "=_crm_" + UUID.randomUUID();
        ByteArrayOutputStream out = new ByteArrayOutputStream(body.length() + 1024);
        appendAscii(out, "From: " + fromHeaderValue + "\r\n");
        appendAscii(out, "To: " + to + "\r\n");
        if (replyToAddress != null && !replyToAddress.trim().isEmpty()) {
            appendAscii(out, "Reply-To: " + safe(replyToAddress) + "\r\n");
        }
        appendAscii(out, "Subject: " + encodeHeaderUtf8(subject) + "\r\n");
        appendAscii(out, "Date: " + ZonedDateTime.now().format(RFC5322_DATE) + "\r\n");
        appendAscii(out, "Message-ID: <" + UUID.randomUUID() + "@crm-platform>\r\n");
        appendAscii(out, "MIME-Version: 1.0\r\n");
        appendAscii(out, "Content-Type: multipart/alternative; boundary=\"" + boundary + "\"\r\n");
        appendAscii(out, "\r\n");

        // text/plain part — UTF-8 8bit. Modern mailers auto-detect UTF-8 even without explicit charset.
        appendAscii(out, "--" + boundary + "\r\n");
        appendAscii(out, "Content-Type: text/plain; charset=\"UTF-8\"\r\n");
        appendAscii(out, "Content-Transfer-Encoding: 8bit\r\n");
        appendAscii(out, "\r\n");
        appendUtf8(out, body);
        appendAscii(out, "\r\n");

        // text/html part — UTF-8 8bit, with an in-body <meta charset="UTF-8"> so that even
        // if the relay rewrites the outer Content-Type, the recipient still gets the
        // charset hint from the HTML itself. Bare URLs become <a href="..."> for clickability.
        appendAscii(out, "--" + boundary + "\r\n");
        appendAscii(out, "Content-Type: text/html; charset=\"UTF-8\"\r\n");
        appendAscii(out, "Content-Transfer-Encoding: 8bit\r\n");
        appendAscii(out, "\r\n");
        appendUtf8(out, "<!DOCTYPE html><html lang=\"ja\"><head><meta charset=\"UTF-8\">"
                + "<meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\"></head>"
                + "<body style=\"font-family:sans-serif;white-space:pre-wrap;line-height:1.6;\">");
        appendUtf8(out, htmlEscapeAndLinkify(body));
        appendUtf8(out, "</body></html>");
        appendAscii(out, "\r\n");

        appendAscii(out, "--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    /** RFC 2047 'B' encoding with UTF-8 charset. */
    private static String encodeHeaderUtf8(String value) {
        if (value == null || value.isEmpty()) return "";
        String stripped = value.replaceAll("[\r\n]", " ");
        if (isAscii(stripped)) return stripped;
        String b64 = Base64.getEncoder().encodeToString(stripped.getBytes(StandardCharsets.UTF_8));
        return "=?UTF-8?B?" + b64 + "?=";
    }

    /**
     * HTML-escape the given plaintext, preserve newlines (the surrounding
     * {@code <body>} uses {@code white-space: pre-wrap}), and turn bare
     * {@code http(s)://...} runs into anchor tags.
     */
    private static String htmlEscapeAndLinkify(String s) {
        if (s == null || s.isEmpty()) return "";
        // Escape HTML metacharacters first.
        String escaped = s.replace("&", "&amp;")
                          .replace("<", "&lt;")
                          .replace(">", "&gt;")
                          .replace("\"", "&quot;")
                          .replace("'", "&#39;");
        // Linkify URLs. The regex stops at whitespace or the HTML chars we just escaped.
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
        return sb.toString();
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) > 0x7F) return false;
        return true;
    }

    private static void appendAscii(ByteArrayOutputStream out, String s) {
        try { out.write(s.getBytes(StandardCharsets.US_ASCII)); }
        catch (java.io.IOException e) { throw new RuntimeException(e); }
    }

    private static void appendUtf8(ByteArrayOutputStream out, String s) {
        try { out.write(s.getBytes(StandardCharsets.UTF_8)); }
        catch (java.io.IOException e) { throw new RuntimeException(e); }
    }

    private static String safe(String s) { return s == null ? "" : s.replaceAll("[\r\n]", " "); }
}
