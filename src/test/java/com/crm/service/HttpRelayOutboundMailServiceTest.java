package com.crm.service;

import com.crm.entity.RelayServer;
import com.crm.repository.RelayServerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link HttpRelayOutboundMailService}:
 *   • dispatcher routing rules (no active relay → local postfix; active=bridge IP → HTTP
 *     bridge; active=other IP → direct SMTP)
 *   • multipart MIME builder shape and content
 *
 * Spring is not started — dependencies are mocked with Mockito.
 */
class HttpRelayOutboundMailServiceTest {

    private RelayServerRepository relayRepo;
    private LocalPostfixOutboundMailService localPostfix;
    private DirectSmtpRelayOutboundMailService directSmtp;
    private DomainSettingService settings;
    private SenderNameResolver senderNameResolver;
    private SmtpOutboundMailService smtpFallback;

    private HttpRelayOutboundMailService svc;

    @BeforeEach
    void setUp() {
        relayRepo = mock(RelayServerRepository.class);
        localPostfix = mock(LocalPostfixOutboundMailService.class);
        directSmtp = mock(DirectSmtpRelayOutboundMailService.class);
        settings = mock(DomainSettingService.class);
        senderNameResolver = mock(SenderNameResolver.class);
        smtpFallback = mock(SmtpOutboundMailService.class);

        // No FROM-domain override by default.
        when(settings.getFromBaseDomain()).thenReturn(null);
        when(senderNameResolver.resolve()).thenReturn(null);
        when(localPostfix.send(any())).thenReturn(OutboundMailService.SendResult.ok());
        when(directSmtp.sendVia(any(), anyString(), anyInt(), any(), any()))
                .thenReturn(OutboundMailService.SendResult.ok());

        // Mail-source-dir must satisfy SAFE_DIR_PATTERN in the constructor's @PostConstruct check;
        // we never actually call SSH in these unit tests.
        svc = new HttpRelayOutboundMailService(
                "http://133.88.116.190:50000/api/mail/received",
                5000, 15000,
                "133.88.116.190", "root", "",
                "/tmp/mailsource", 15,
                smtpFallback, settings, senderNameResolver, relayRepo, localPostfix, directSmtp);
    }

    private static OutboundMailService.OutboundRequest req(String from, String to) {
        return new OutboundMailService.OutboundRequest(from, to, "subj", "body", null, 0, null, null);
    }

    private static RelayServer relay(Long id, String name, String ip, Integer port, boolean active) {
        RelayServer r = new RelayServer();
        r.setId(id);
        r.setName(name);
        r.setIpAddress(ip);
        r.setPort(port);
        r.setIsActive(active);
        return r;
    }

    // ─────────────────────────── dispatcher routing ──────────────────────────

    @Test
    void noActiveRelayRow_routesToLocalPostfix() {
        when(relayRepo.findAllByOrderByNameAsc()).thenReturn(Collections.emptyList());
        svc.send(req("a@avu74g.jp", "b@example.com"));
        verify(localPostfix).send(any());
        verify(directSmtp, never()).sendVia(any(), anyString(), anyInt(), any(), any());
    }

    @Test
    void allRowsInactive_routesToLocalPostfix() {
        when(relayRepo.findAllByOrderByNameAsc()).thenReturn(Arrays.asList(
                relay(1L, "転送機", "133.88.116.190", 25, false),
                relay(2L, "AMG", "157.7.89.36", 25, false)));
        svc.send(req("a@avu74g.jp", "b@example.com"));
        verify(localPostfix).send(any());
        verify(directSmtp, never()).sendVia(any(), anyString(), anyInt(), any(), any());
    }

    @Test
    void activeRelayIpMatchesHttpBridgeHost_isNotRoutedToDirectSmtp() {
        // The configured RELAY_URL host is 133.88.116.190, so a row with that IP must take the
        // HTTP-bridge (this class's SSH+POST) path — meaning directSmtp is NOT called and
        // localPostfix is NOT called.
        when(relayRepo.findAllByOrderByNameAsc()).thenReturn(Collections.singletonList(
                relay(1L, "転送機", "133.88.116.190", 25, true)));
        svc.send(req("a@avu74g.jp", "b@example.com"));
        verify(directSmtp, never()).sendVia(any(), anyString(), anyInt(), any(), any());
        verify(localPostfix, never()).send(any());
        // (The HTTP POST itself isn't asserted here — it would require mocking the HTTP client,
        // which is constructed inside send(). The branch decision is what matters.)
    }

    @Test
    void activeRelayIpDiffersFromBridgeHost_routesToDirectSmtp() {
        // AMG is not the same IP as the HTTP-bridge URL → direct SMTP path.
        when(relayRepo.findAllByOrderByNameAsc()).thenReturn(Collections.singletonList(
                relay(2L, "AMG", "157.7.89.36", 25, true)));
        svc.send(req("a@avu74g.jp", "b@example.com"));
        ArgumentCaptor<String> hostCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> portCap = ArgumentCaptor.forClass(Integer.class);
        verify(directSmtp).sendVia(any(), hostCap.capture(), portCap.capture(), any(), any());
        assertThat(hostCap.getValue()).isEqualTo("157.7.89.36");
        assertThat(portCap.getValue()).isEqualTo(25);
        verify(localPostfix, never()).send(any());
    }

    @Test
    void firstActiveRowWins_alphabeticallyOrdered() {
        // Repository returns ordered list, dispatcher picks the first IS_ACTIVE=1 row.
        when(relayRepo.findAllByOrderByNameAsc()).thenReturn(Arrays.asList(
                relay(1L, "AMG", "157.7.89.36", 25, true),
                relay(2L, "転送機", "133.88.116.190", 25, true)));
        svc.send(req("a@avu74g.jp", "b@example.com"));
        ArgumentCaptor<String> hostCap = ArgumentCaptor.forClass(String.class);
        verify(directSmtp).sendVia(any(), hostCap.capture(), anyInt(), any(), any());
        assertThat(hostCap.getValue()).isEqualTo("157.7.89.36");
    }

    @Test
    void nullPortDefaultsTo25() {
        when(relayRepo.findAllByOrderByNameAsc()).thenReturn(Collections.singletonList(
                relay(2L, "AMG", "157.7.89.36", null, true)));
        svc.send(req("a@avu74g.jp", "b@example.com"));
        ArgumentCaptor<Integer> portCap = ArgumentCaptor.forClass(Integer.class);
        verify(directSmtp).sendVia(any(), anyString(), portCap.capture(), any(), any());
        assertThat(portCap.getValue()).isEqualTo(25);
    }

    @Test
    void fromDomainOverride_appliedBeforeDispatch() {
        when(settings.getFromBaseDomain()).thenReturn("avu74g.jp");
        when(relayRepo.findAllByOrderByNameAsc()).thenReturn(Collections.singletonList(
                relay(2L, "AMG", "157.7.89.36", 25, true)));
        svc.send(req("rifc6h1c65@docomo.ne.jp", "user@gmail.com"));

        ArgumentCaptor<OutboundMailService.OutboundRequest> reqCap =
                ArgumentCaptor.forClass(OutboundMailService.OutboundRequest.class);
        verify(directSmtp).sendVia(reqCap.capture(), eq("157.7.89.36"), eq(25), any(), any());
        assertThat(reqCap.getValue().fromAddress).isEqualTo("rifc6h1c65@avu74g.jp");
    }

    @Test
    void displayNameFromSenderNameResolver_passedToDirectSmtp() {
        when(senderNameResolver.resolve()).thenReturn("サポート窓口");
        when(relayRepo.findAllByOrderByNameAsc()).thenReturn(Collections.singletonList(
                relay(2L, "AMG", "157.7.89.36", 25, true)));
        svc.send(req("a@avu74g.jp", "b@example.com"));
        ArgumentCaptor<String> nameCap = ArgumentCaptor.forClass(String.class);
        verify(directSmtp).sendVia(any(), anyString(), anyInt(), nameCap.capture(), any());
        assertThat(nameCap.getValue()).isEqualTo("サポート窓口");
    }

    @Test
    void getActiveRelayHost_returnsHostnameFromUrl() {
        assertThat(svc.getActiveRelayHost()).isEqualTo("133.88.116.190");
    }

    // ────────────────────────── multipart MIME builder ───────────────────────

    @Test
    void buildRawMime_isMultipartAlternativeUtf8_8bit_withMetaCharset() {
        String mime = HttpRelayOutboundMailService.buildRawMime(req("a@x", "b@y"), "サポート窓口", null);

        assertThat(mime).contains("Content-Type: multipart/alternative; boundary=");
        assertThat(mime).containsPattern(Pattern.compile(
                "Content-Type: text/plain; charset=\"UTF-8\"\\s+Content-Transfer-Encoding: 8bit"));
        assertThat(mime).contains("<meta charset=\"UTF-8\">");
        // No outer Content-Transfer-Encoding (regression: old code wrapped a single text/plain
        // part with base64 outer CTE, which downstream relays sometimes stripped).
        int firstBoundary = mime.indexOf("--=_crm_");
        assertThat(mime.substring(0, firstBoundary)).doesNotContain("Content-Transfer-Encoding");
        // Display name encoded.
        assertThat(mime).contains("From: \"=?UTF-8?B?44K144Od44O844OI56qT5Y+j?=\" <a@x>");
    }

    @Test
    void htmlEscapeAndLinkify_isReachableFromBridge() throws Exception {
        Method m = HttpRelayOutboundMailService.class.getDeclaredMethod("htmlEscapeAndLinkify", String.class);
        m.setAccessible(true);
        assertThat((String) m.invoke(null, "hello <world> http://x.test"))
                .contains("&lt;world&gt;")
                .contains("<a href=\"http://x.test\">http://x.test</a>");
    }

    @Test
    void replyToHeader_addedWhenProvided() {
        String mime = HttpRelayOutboundMailService.buildRawMime(
                req("rifc6h1c65@avu74g.jp", "b@example.com"),
                null, "rifc6h1c65@avu74g.jp");
        int firstBoundary = mime.indexOf("--=_crm_");
        assertThat(mime.substring(0, firstBoundary))
                .contains("Reply-To: rifc6h1c65@avu74g.jp");
    }

    @Test
    void replyToHeader_omittedWhenNull() {
        String mime = HttpRelayOutboundMailService.buildRawMime(
                req("rifc6h1c65@avu74g.jp", "b@example.com"), null, null);
        assertThat(mime).doesNotContain("Reply-To:");
    }
}
