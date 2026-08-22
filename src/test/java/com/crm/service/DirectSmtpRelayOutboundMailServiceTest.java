package com.crm.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DirectSmtpRelayOutboundMailService}'s private MIME builder.
 *
 * Built via reflection because the production method is package-private internals — we
 * don't want to widen the API surface just for testing.
 */
class DirectSmtpRelayOutboundMailServiceTest {

    private static byte[] invokeBuildRawMime(OutboundMailService.OutboundRequest req,
                                              String displayName) throws Exception {
        return DirectSmtpRelayOutboundMailService.buildRawMime(req, displayName, null);
    }

    private static byte[] invokeBuildRawMime(OutboundMailService.OutboundRequest req,
                                              String displayName, String replyTo) {
        return DirectSmtpRelayOutboundMailService.buildRawMime(req, displayName, replyTo);
    }

    private static String invokeHtmlEscape(String s) throws Exception {
        Method m = DirectSmtpRelayOutboundMailService.class.getDeclaredMethod("htmlEscapeAndLinkify", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, s);
    }

    private static OutboundMailService.OutboundRequest req(String from, String to, String subj, String body) {
        return new OutboundMailService.OutboundRequest(from, to, subj, body, null, 0, null, null);
    }

    @Test
    void buildsMultipartAlternativeWithUtf8PlainAndHtmlParts() throws Exception {
        byte[] raw = invokeBuildRawMime(
                req("a@avu74g.jp", "user@example.com", "件名テスト", "本文テスト"),
                "サポート窓口");
        String mime = new String(raw, StandardCharsets.UTF_8);

        assertThat(mime).contains("From: \"=?UTF-8?B?");
        assertThat(mime).contains("<a@avu74g.jp>");
        assertThat(mime).contains("To: user@example.com");
        assertThat(mime).contains("Subject: =?UTF-8?B?");
        assertThat(mime).contains("MIME-Version: 1.0");
        assertThat(mime).contains("Content-Type: multipart/alternative; boundary=");
        // text/plain part with 8bit transfer encoding (no outer Content-Transfer-Encoding: base64)
        assertThat(mime).containsPattern(Pattern.compile(
                "Content-Type: text/plain; charset=\"UTF-8\"\\s+Content-Transfer-Encoding: 8bit"));
        // text/html part with in-body meta charset (survives downstream header rewrites)
        assertThat(mime).contains("Content-Type: text/html; charset=\"UTF-8\"");
        assertThat(mime).contains("<meta charset=\"UTF-8\">");
        // body is included as plain UTF-8 (not base64-encoded)
        assertThat(mime).contains("本文テスト");
    }

    @Test
    void onlyOneOuterContentTransferEncoding_isNotBase64() throws Exception {
        // The original bug: a single text/plain part with Content-Transfer-Encoding: base64 in the
        // outer headers. With multipart, the outer message has no CTE at all — only the parts do.
        byte[] raw = invokeBuildRawMime(req("a@x", "b@y", "subj", "body"), null);
        String mime = new String(raw, StandardCharsets.UTF_8);
        int firstBoundary = mime.indexOf("--=_crm_");
        assertThat(firstBoundary).isPositive();
        String outerHeaders = mime.substring(0, firstBoundary);
        assertThat(outerHeaders).doesNotContain("Content-Transfer-Encoding");
    }

    @Test
    void fromHeaderEncodesNonAsciiDisplayName() throws Exception {
        byte[] raw = invokeBuildRawMime(req("a@x", "b@y", "s", "b"), "サポート窓口");
        String mime = new String(raw, StandardCharsets.UTF_8);
        // サポート窓口 in UTF-8 → base64 → 44K144Od44O844OI56qT5Y+j
        assertThat(mime).contains("From: \"=?UTF-8?B?44K144Od44O844OI56qT5Y+j?=\" <a@x>");
    }

    @Test
    void fromHeaderOmitsDisplayNameWhenNullOrBlank() throws Exception {
        String noName = new String(invokeBuildRawMime(req("a@x", "b@y", "s", "b"), null), StandardCharsets.UTF_8);
        String blankName = new String(invokeBuildRawMime(req("a@x", "b@y", "s", "b"), "  "), StandardCharsets.UTF_8);
        assertThat(noName).contains("From: a@x").doesNotContain("=?UTF-8?B?");
        assertThat(blankName).contains("From: a@x").doesNotContain("=?UTF-8?B?");
    }

    @Test
    void subjectEncodingChoosesUtf8WhenNonAscii() throws Exception {
        byte[] raw = invokeBuildRawMime(req("a@x", "b@y", "ASCII subject", "body"), null);
        String mime = new String(raw, StandardCharsets.UTF_8);
        // pure ASCII subject is NOT encoded as =?UTF-8?B?...?=
        assertThat(mime).contains("Subject: ASCII subject\r\n");
    }

    @Test
    void crlfStrippingPreventsHeaderInjectionInSubject() throws Exception {
        // Classic injection attempt: smuggle a Bcc header through the Subject. The encoder must
        // collapse CR/LF to spaces so the relay does NOT see "Bcc:" as a new header line —
        // the malicious payload survives as text inside Subject, but is harmless there.
        byte[] raw = invokeBuildRawMime(
                req("a@x", "b@y", "Hi\r\nBcc: leak@evil.example", "body"), null);
        String mime = new String(raw, StandardCharsets.UTF_8);
        // Header section ends at the first blank line (CRLFCRLF). No real Bcc header should
        // appear before that point.
        int headerEnd = mime.indexOf("\r\n\r\n");
        assertThat(headerEnd).isPositive();
        String headers = mime.substring(0, headerEnd);
        assertThat(headers)
                .doesNotContain("\r\nBcc:")
                .doesNotContain("\nBcc:");
    }

    @Test
    void htmlEscapeAndLinkify_escapesAngleBracketsAndQuotes() throws Exception {
        String out = invokeHtmlEscape("<script>alert(\"xss\")</script>");
        assertThat(out)
                .doesNotContain("<script>")
                .contains("&lt;script&gt;")
                .contains("&quot;xss&quot;");
    }

    @Test
    void htmlEscapeAndLinkify_wrapsHttpAndHttpsUrlsInAnchors() throws Exception {
        String out = invokeHtmlEscape("see http://example.com/foo and https://x.test/bar");
        assertThat(out)
                .contains("<a href=\"http://example.com/foo\">http://example.com/foo</a>")
                .contains("<a href=\"https://x.test/bar\">https://x.test/bar</a>");
    }

    @Test
    void htmlEscapeAndLinkify_stopsUrlAtWhitespace() throws Exception {
        String out = invokeHtmlEscape("link http://example.com next word");
        assertThat(out).contains("<a href=\"http://example.com\">http://example.com</a> next word");
    }

    @Test
    void htmlEscapeAndLinkify_handlesEmptyAndNull() throws Exception {
        assertThat(invokeHtmlEscape(null)).isEmpty();
        assertThat(invokeHtmlEscape("")).isEmpty();
    }

    /**
     * Regression test: relying only on the surrounding <body style="white-space:pre-wrap">
     * to preserve line breaks isn't enough — Gmail's HTML sanitiser (and others) strip inline
     * white-space styling on ingest, collapsing multi-line broadcast/reply bodies into one
     * line for the recipient even though the CRM's own preview looked correct. Newlines must
     * be converted to explicit <br> tags, which survive that sanitisation.
     */
    @Test
    void htmlEscapeAndLinkify_convertsNewlinesToBrTags() throws Exception {
        String out = invokeHtmlEscape("1行目\n2行目\n3行目");
        assertThat(out).isEqualTo("1行目<br>\n2行目<br>\n3行目");
    }

    @Test
    void htmlEscapeAndLinkify_convertsCrLfNewlines() throws Exception {
        String out = invokeHtmlEscape("1行目\r\n2行目");
        assertThat(out).isEqualTo("1行目<br>\n2行目");
    }

    @Test
    void buildRawMime_htmlPart_preservesMultiLineBodyAsBrTags() throws Exception {
        byte[] raw = invokeBuildRawMime(
                req("a@avu74g.jp", "user@example.com", "件名", "1行目\n2行目\n3行目"), "サポート窓口");
        String mime = new String(raw, StandardCharsets.UTF_8);
        assertThat(mime).contains("1行目<br>\n2行目<br>\n3行目");
    }

    @Test
    void replyToHeader_addedWhenProvided() {
        byte[] raw = invokeBuildRawMime(
                req("rifc6h1c65@avu74g.jp", "b@example.com", "s", "b"),
                null, "rifc6h1c65@avu74g.jp");
        String mime = new String(raw, StandardCharsets.UTF_8);
        // Reply-To must appear in the header block (before the first multipart boundary).
        int firstBoundary = mime.indexOf("--=_crm_");
        assertThat(mime.substring(0, firstBoundary))
                .contains("Reply-To: rifc6h1c65@avu74g.jp");
    }

    @Test
    void replyToHeader_omittedWhenNullOrBlank() {
        byte[] raw = invokeBuildRawMime(
                req("rifc6h1c65@avu74g.jp", "b@example.com", "s", "b"), null, null);
        assertThat(new String(raw, StandardCharsets.UTF_8)).doesNotContain("Reply-To:");

        raw = invokeBuildRawMime(
                req("rifc6h1c65@avu74g.jp", "b@example.com", "s", "b"), null, "  ");
        assertThat(new String(raw, StandardCharsets.UTF_8)).doesNotContain("Reply-To:");
    }
}
