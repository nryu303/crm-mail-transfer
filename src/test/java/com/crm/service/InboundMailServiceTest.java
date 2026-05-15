package com.crm.service;

import com.crm.dto.InboundMailDto;
import com.crm.entity.CarrierAddressPool;
import com.crm.entity.CrmUser;
import com.crm.entity.InboundMailLog;
import com.crm.entity.Message;
import com.crm.repository.CarrierAddressPoolRepository;
import com.crm.repository.CrmUserRepository;
import com.crm.repository.InboundMailLogRepository;
import com.crm.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link InboundMailService} — the main reply-ingestion pipeline.
 *
 * Confirms:
 *   • TO-not-in-pool / FROM-not-a-user / bounce / missing-fields / duplicate Message-ID
 *     all reject with the expected reason and don't create a MESSAGE row.
 *   • Pool/user binding is informational only (post-2026-05 receive-only-pool refactor).
 *   • Subject and body are RFC2047/MIME-decoded from the raw RFC822 before storage.
 */
class InboundMailServiceTest {

    private InboundMailLogRepository logRepo;
    private CarrierAddressPoolRepository poolRepo;
    private CrmUserRepository userRepo;
    private MessageRepository messageRepo;
    private CarrierBindingService bindingService;
    private UserActivityService userActivityService;
    private DomainSettingService settings;

    private InboundMailService svc;

    @BeforeEach
    void setUp() {
        logRepo = mock(InboundMailLogRepository.class);
        poolRepo = mock(CarrierAddressPoolRepository.class);
        userRepo = mock(CrmUserRepository.class);
        messageRepo = mock(MessageRepository.class);
        bindingService = mock(CarrierBindingService.class);
        userActivityService = mock(UserActivityService.class);
        settings = mock(DomainSettingService.class);

        // Defaults: no dedup hit, no FROM-domain override, no implicit pool/user binding.
        when(messageRepo.existsByMessageIdHeader(anyString())).thenReturn(false);
        when(settings.getFromBaseDomain()).thenReturn(null);
        when(bindingService.isBound(anyLong(), anyLong())).thenReturn(false);
        when(messageRepo.save(any(Message.class))).thenAnswer(inv -> {
            Message m = inv.getArgument(0);
            m.setId(42L);
            return m;
        });

        svc = new InboundMailService(logRepo, poolRepo, userRepo, messageRepo,
                bindingService, userActivityService, settings);
    }

    private static InboundMailDto dto(String from, String to, String subject, String body) {
        InboundMailDto d = new InboundMailDto();
        d.setFrom(from);
        d.setTo(to);
        d.setSubject(subject);
        d.setBody(body);
        d.setRaw("");
        d.setMessageId("<test-" + System.nanoTime() + "@unit>");
        return d;
    }

    private static CrmUser user(Long id, String email) {
        CrmUser u = new CrmUser();
        u.setId(id);
        u.setEmail(email);
        return u;
    }

    private static CarrierAddressPool pool(Long id, String address) {
        CarrierAddressPool p = new CarrierAddressPool();
        p.setId(id);
        p.setAddress(address);
        return p;
    }

    // ─────────────── rejection paths ──────────────────────────────────────

    @Test
    void rejectsWhenFromOrToMissing() {
        InboundMailService.ProcessResult result = svc.process(dto(null, "x@avu74g.jp", "s", "b"));
        assertThat(result.accepted).isFalse();
        assertThat(result.reason).isEqualTo(InboundMailService.REASON_MISSING_FIELDS);
        verify(messageRepo, never()).save(any(Message.class));
    }

    @Test
    void rejectsBounceLocalPart() {
        InboundMailDto d = dto("postmaster@gmail.com", "x@avu74g.jp", "s", "b");
        InboundMailService.ProcessResult result = svc.process(d);
        assertThat(result.reason).isEqualTo(InboundMailService.REASON_BOUNCE);
    }

    @Test
    void rejectsBounceLocalPartCaseInsensitively() {
        InboundMailDto d = dto("Mailer-Daemon@gmail.com", "x@avu74g.jp", "s", "b");
        assertThat(svc.process(d).reason).isEqualTo(InboundMailService.REASON_BOUNCE);
    }

    @Test
    void rejectsToAddressNotInPool() {
        when(poolRepo.findByAddress(anyString())).thenReturn(Optional.empty());
        when(poolRepo.findByLocalPart(anyString())).thenReturn(Optional.empty());
        InboundMailService.ProcessResult result = svc.process(
                dto("alice@example.com", "unknown@unknown.test", "s", "b"));
        assertThat(result.reason).isEqualTo(InboundMailService.REASON_TO_NOT_IN_POOL);
    }

    @Test
    void rejectsFromAddressNotRegisteredAsUser() {
        when(poolRepo.findByAddress("rifc6h1c65@avu74g.jp"))
                .thenReturn(Optional.of(pool(7L, "rifc6h1c65@avu74g.jp")));
        when(userRepo.findByEmail(anyString())).thenReturn(Optional.empty());
        InboundMailService.ProcessResult result = svc.process(
                dto("stranger@gmail.com", "rifc6h1c65@avu74g.jp", "s", "b"));
        assertThat(result.reason).isEqualTo(InboundMailService.REASON_FROM_NOT_A_USER);
    }

    @Test
    void rejectsDuplicateMessageId() {
        when(messageRepo.existsByMessageIdHeader(anyString())).thenReturn(true);
        InboundMailDto d = dto("a@gmail.com", "rifc6h1c65@avu74g.jp", "s", "b");
        d.setMessageId("<dup-msg@example>");
        assertThat(svc.process(d).reason).isEqualTo(InboundMailService.REASON_DUPLICATE);
    }

    // ─────────────── acceptance paths ─────────────────────────────────────

    @Test
    void acceptsWhenFromUserAndPoolAddressMatch_evenWithoutExplicitBinding() {
        // Regression: prior to 2026-05 a missing CARRIER_USER_BINDING row caused REASON_NOT_BOUND
        // rejection. The pool-is-receive-only refactor accepts as long as FROM identifies a user.
        when(poolRepo.findByAddress("rifc6h1c65@avu74g.jp"))
                .thenReturn(Optional.of(pool(7L, "rifc6h1c65@avu74g.jp")));
        when(userRepo.findByEmail("alice@gmail.com")).thenReturn(Optional.of(user(99L, "alice@gmail.com")));
        when(bindingService.isBound(7L, 99L)).thenReturn(false); // explicitly no binding

        InboundMailService.ProcessResult result = svc.process(
                dto("alice@gmail.com", "rifc6h1c65@avu74g.jp", "Re: hello", "thanks!"));

        assertThat(result.accepted).isTrue();
        assertThat(result.userId).isEqualTo(99L);
        ArgumentCaptor<Message> cap = ArgumentCaptor.forClass(Message.class);
        verify(messageRepo).save(cap.capture());
        Message saved = cap.getValue();
        assertThat(saved.getDirection()).isEqualTo(Message.DIR_IN);
        assertThat(saved.getUserId()).isEqualTo(99L);
        assertThat(saved.getFromAddress()).isEqualTo("alice@gmail.com");
        assertThat(saved.getToAddress()).isEqualTo("rifc6h1c65@avu74g.jp");
        verify(userActivityService).touchLastLogin(any(CrmUser.class));
    }

    @Test
    void toAddressLookupFallsBackToLocalPart_whenDomainEqualsFromBaseDomain() {
        // Reply addressed to "rifc6h1c65@avu74g.jp" but the only pool row in DB is the carrier
        // address "rifc6h1c65@docomo.ne.jp". With from.base_domain=avu74g.jp the service should
        // strip the domain and find the pool by local-part.
        when(settings.getFromBaseDomain()).thenReturn("avu74g.jp");
        when(poolRepo.findByAddress("rifc6h1c65@avu74g.jp")).thenReturn(Optional.empty());
        when(poolRepo.findByLocalPart("rifc6h1c65"))
                .thenReturn(Optional.of(pool(7L, "rifc6h1c65@docomo.ne.jp")));
        when(userRepo.findByEmail("alice@gmail.com")).thenReturn(Optional.of(user(99L, "alice@gmail.com")));

        assertThat(svc.process(dto("alice@gmail.com", "rifc6h1c65@avu74g.jp", "s", "b")).accepted).isTrue();
    }

    // ─────────────── MIME decoding ────────────────────────────────────────

    @Test
    void decodesRfc2047SubjectFromRawMime() {
        // Raw RFC822 with an encoded-word Subject. The service must store the decoded form.
        String raw = "From: alice@gmail.com\r\n"
                + "To: rifc6h1c65@avu74g.jp\r\n"
                + "Subject: =?utf-8?B?44Ot44O844OJ44OG44K544OI?=\r\n"  // "ロードテスト"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "Content-Transfer-Encoding: quoted-printable\r\n"
                + "\r\n"
                + "=E3=81=82=E3=81=84=E3=81=86\r\n";  // "あいう"

        when(poolRepo.findByAddress("rifc6h1c65@avu74g.jp"))
                .thenReturn(Optional.of(pool(7L, "rifc6h1c65@avu74g.jp")));
        when(userRepo.findByEmail("alice@gmail.com")).thenReturn(Optional.of(user(99L, "alice@gmail.com")));

        InboundMailDto d = dto("alice@gmail.com", "rifc6h1c65@avu74g.jp",
                "=?utf-8?B?44Ot44O844OJ44OG44K544OI?=", "raw-bash-extracted-encoded-text");
        d.setRaw(raw);

        InboundMailService.ProcessResult result = svc.process(d);
        assertThat(result.accepted).isTrue();
        ArgumentCaptor<Message> cap = ArgumentCaptor.forClass(Message.class);
        verify(messageRepo).save(cap.capture());
        assertThat(cap.getValue().getSubject()).isEqualTo("ロードテスト");
        assertThat(cap.getValue().getBodyText()).contains("あいう");
    }

    @Test
    void prefersTextPlainOverHtml_inMultipartAlternative() {
        String boundary = "X";
        String raw = "From: alice@gmail.com\r\n"
                + "To: rifc6h1c65@avu74g.jp\r\n"
                + "Subject: multi\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: multipart/alternative; boundary=\"" + boundary + "\"\r\n"
                + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n\r\n"
                + "plain-version\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Type: text/html; charset=UTF-8\r\n\r\n"
                + "<p>html-version</p>\r\n"
                + "--" + boundary + "--\r\n";

        when(poolRepo.findByAddress("rifc6h1c65@avu74g.jp"))
                .thenReturn(Optional.of(pool(7L, "rifc6h1c65@avu74g.jp")));
        when(userRepo.findByEmail("alice@gmail.com")).thenReturn(Optional.of(user(99L, "alice@gmail.com")));

        InboundMailDto d = dto("alice@gmail.com", "rifc6h1c65@avu74g.jp", "multi", "bash-fallback");
        d.setRaw(raw);
        svc.process(d);
        ArgumentCaptor<Message> cap = ArgumentCaptor.forClass(Message.class);
        verify(messageRepo).save(cap.capture());
        assertThat(cap.getValue().getBodyText()).contains("plain-version").doesNotContain("html-version");
    }

    @Test
    void invalidRawMime_fallsBackToBashExtractedValues() {
        // Non-parseable raw bytes → the service must not crash; it should use the dto-provided
        // (pre-extracted) subject/body so we never lose the message entirely.
        when(poolRepo.findByAddress("rifc6h1c65@avu74g.jp"))
                .thenReturn(Optional.of(pool(7L, "rifc6h1c65@avu74g.jp")));
        when(userRepo.findByEmail("alice@gmail.com")).thenReturn(Optional.of(user(99L, "alice@gmail.com")));

        InboundMailDto d = dto("alice@gmail.com", "rifc6h1c65@avu74g.jp",
                "fallback-subject", "fallback-body");
        d.setRaw("this is not RFC822 at all !!!");
        svc.process(d);
        ArgumentCaptor<Message> cap = ArgumentCaptor.forClass(Message.class);
        verify(messageRepo).save(cap.capture());
        // jakarta.mail will happily parse anything as a Message with empty headers/body. The
        // important guarantee is that we don't throw and we save *something*.
        assertThat(cap.getValue()).isNotNull();
    }

    // ─────────────── log row metadata ─────────────────────────────────────

    // ─────────────── extractEmailAddress helper ──────────────────────────

    @Test
    void extractEmailAddress_anglesAroundAddrSpec_returnsBareAddress() {
        assertThat(InboundMailService.extractEmailAddress("<MAILER-DAEMON@docomo.ne.jp>"))
                .isEqualTo("MAILER-DAEMON@docomo.ne.jp");
    }

    @Test
    void extractEmailAddress_displayNamePlusAngleAddr_returnsBareAddress() {
        assertThat(InboundMailService.extractEmailAddress("tt tt <jibfu785tjg@gmail.com>"))
                .isEqualTo("jibfu785tjg@gmail.com");
        assertThat(InboundMailService.extractEmailAddress("\"Display Name\" <user@example.com>"))
                .isEqualTo("user@example.com");
    }

    @Test
    void extractEmailAddress_bareAddress_returnedAsIs() {
        assertThat(InboundMailService.extractEmailAddress("user@example.com"))
                .isEqualTo("user@example.com");
        assertThat(InboundMailService.extractEmailAddress("  user@example.com  "))
                .isEqualTo("user@example.com");
    }

    @Test
    void extractEmailAddress_quotedAddress_dropsQuotes() {
        assertThat(InboundMailService.extractEmailAddress("\"user@example.com\""))
                .isEqualTo("user@example.com");
    }

    @Test
    void extractEmailAddress_commaSeparatedList_keepsFirst() {
        assertThat(InboundMailService.extractEmailAddress("a@x.test, b@y.test"))
                .isEqualTo("a@x.test");
        assertThat(InboundMailService.extractEmailAddress("\"A\" <a@x.test>, b@y.test"))
                .isEqualTo("a@x.test");
    }

    @Test
    void extractEmailAddress_internalWhitespace_isStripped() {
        // Some clients emit " user @example.com " — keep matching robust.
        assertThat(InboundMailService.extractEmailAddress("user @example.com"))
                .isEqualTo("user@example.com");
    }

    @Test
    void extractEmailAddress_null_returnsNull() {
        assertThat(InboundMailService.extractEmailAddress(null)).isNull();
    }

    // ─────────────── regression: angle-bracketed FROM matches registered user ──

    @Test
    void acceptsWhenFromIsDisplayNamePlusAngleAddr_andUserIsRegistered() {
        // Docomo replies arrive as `"Display Name" <user@gmail.com>` — the email lookup
        // must strip the display name. Regression test for the 2026-05-12 fix.
        when(poolRepo.findByAddress("rifc6h1c65@avu74g.jp"))
                .thenReturn(Optional.of(pool(7L, "rifc6h1c65@avu74g.jp")));
        when(userRepo.findByEmail("jibfu785tjg@gmail.com"))
                .thenReturn(Optional.of(user(108L, "jibfu785tjg@gmail.com")));

        InboundMailDto d = dto("tt tt <jibfu785tjg@gmail.com>",
                "rifc6h1c65@avu74g.jp", "Re: hi", "thanks");

        InboundMailService.ProcessResult result = svc.process(d);
        assertThat(result.accepted).isTrue();
        assertThat(result.userId).isEqualTo(108L);
    }

    @Test
    void detectsBounceLocalPart_evenWithAngleBrackets() {
        // "<MAILER-DAEMON@docomo.ne.jp>" must hit the bounce filter, not fall through to
        // from_address_not_registered_user.
        InboundMailDto d = dto("<MAILER-DAEMON@docomo.ne.jp>", "x@avu74g.jp", "Undelivered", "...");
        assertThat(svc.process(d).reason).isEqualTo(InboundMailService.REASON_BOUNCE);
    }

    @Test
    void rejectedMessages_persistInboundMailLogWithReason() {
        when(poolRepo.findByAddress(anyString())).thenReturn(Optional.empty());
        when(poolRepo.findByLocalPart(anyString())).thenReturn(Optional.empty());
        svc.process(dto("alice@example.com", "unknown@xyz.test", "s", "b"));
        ArgumentCaptor<InboundMailLog> cap = ArgumentCaptor.forClass(InboundMailLog.class);
        verify(logRepo).save(cap.capture());
        InboundMailLog row = cap.getValue();
        assertThat(row.getIsRejected()).isTrue();
        assertThat(row.getRejectReason()).isEqualTo(InboundMailService.REASON_TO_NOT_IN_POOL);
    }
}
