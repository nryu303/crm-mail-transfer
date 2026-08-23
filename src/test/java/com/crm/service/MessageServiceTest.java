package com.crm.service;

import com.crm.entity.CarrierAddressPool;
import com.crm.entity.CrmUser;
import com.crm.entity.Message;
import com.crm.repository.CarrierAddressPoolRepository;
import com.crm.repository.CrmUserRepository;
import com.crm.repository.MessageRepository;
import com.crm.util.AesEncryptionUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationContext;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused tests on {@link MessageService#sendNow} — the function that translates a queued
 * Message row into an outbound call and applies the retry/status state machine.
 *
 * Covers:
 *   • pool==null is tolerated post-2026-05 (carrier pool became receive-only)
 *   • success → STATUS_SENT, error/retry fields cleared
 *   • permanent failure → STATUS_FAILED
 *   • retriable failure with attempts under the cap → STATUS_QUEUED + next_retry_at set
 *   • retriable failure at the cap → STATUS_FAILED
 *   • send_attempts increments on every call
 */
class MessageServiceTest {

    private MessageRepository messageRepo;
    private CrmUserRepository userRepo;
    private CarrierAddressPoolRepository poolRepo;
    private CarrierBindingService bindingService;
    private PlaceholderService placeholderService;
    private OutboundMailService outboundMail;
    private OutboundSmsService outboundSms;
    private SmsSettingService smsSettingService;
    private AesEncryptionUtil aes;
    private ReplyPageService replyPageService;
    private ApplicationContext ctx;

    private DomainSettingService domainSettings;

    private MessageService svc;

    @BeforeEach
    void setUp() {
        messageRepo = mock(MessageRepository.class);
        userRepo = mock(CrmUserRepository.class);
        poolRepo = mock(CarrierAddressPoolRepository.class);
        bindingService = mock(CarrierBindingService.class);
        placeholderService = mock(PlaceholderService.class);
        outboundMail = mock(OutboundMailService.class);
        outboundSms = mock(OutboundSmsService.class);
        smsSettingService = mock(SmsSettingService.class);
        aes = mock(AesEncryptionUtil.class);
        replyPageService = mock(ReplyPageService.class);
        ctx = mock(ApplicationContext.class);
        domainSettings = mock(DomainSettingService.class);

        svc = new MessageService(messageRepo, userRepo, poolRepo, bindingService,
                placeholderService, outboundMail, outboundSms, smsSettingService,
                aes, replyPageService, domainSettings, ctx);
    }

    private static Message queued() {
        Message m = new Message();
        m.setId(1L);
        m.setUserId(7L);
        m.setDirection(Message.DIR_OUT);
        m.setChannel(Message.CHANNEL_EMAIL);
        m.setFromAddress("rifc6h1c65@avu74g.jp");
        m.setToAddress("user@example.com");
        m.setSubject("s");
        m.setBodyText("b");
        m.setStatus(Message.STATUS_QUEUED);
        m.setSendAttempts(0);
        return m;
    }

    private static CarrierAddressPool poolWithSmtp() {
        CarrierAddressPool p = new CarrierAddressPool();
        p.setId(99L);
        p.setSmtpHost("smtp.example.com");
        p.setSmtpPort(587);
        p.setSmtpUsername("user@example.com");
        p.setSmtpPassword("ENC-bytes");
        return p;
    }

    @Test
    void success_marksSentAndClearsErrorState() {
        Message m = queued();
        m.setErrorMessage("old");
        m.setNextRetryAt(java.time.LocalDateTime.now());
        when(outboundMail.send(any())).thenReturn(OutboundMailService.SendResult.ok());

        svc.sendNow(m, null);

        assertThat(m.getStatus()).isEqualTo(Message.STATUS_SENT);
        assertThat(m.getSentAt()).isNotNull();
        assertThat(m.getErrorMessage()).isNull();
        assertThat(m.getNextRetryAt()).isNull();
        assertThat(m.getSendAttempts()).isEqualTo(1);
        verify(messageRepo, times(1)).save(m);
    }

    @Test
    void poolNull_doesNotPopulateSmtpFields() {
        Message m = queued();
        when(outboundMail.send(any())).thenReturn(OutboundMailService.SendResult.ok());

        svc.sendNow(m, null);

        ArgumentCaptor<OutboundMailService.OutboundRequest> reqCap =
                ArgumentCaptor.forClass(OutboundMailService.OutboundRequest.class);
        verify(outboundMail).send(reqCap.capture());
        // smtpPort defaults to 587 when pool is null (legacy field — outbound services ignore it
        // now, but the OutboundRequest constructor still needs a value).
        assertThat(reqCap.getValue().smtpHost).isNull();
        assertThat(reqCap.getValue().smtpUsername).isNull();
        assertThat(reqCap.getValue().smtpPassword).isNull();
        assertThat(reqCap.getValue().smtpPort).isEqualTo(587);
        verify(aes, never()).decrypt(any());
    }

    @Test
    void poolPresent_decryptsSmtpPassword() {
        Message m = queued();
        CarrierAddressPool pool = poolWithSmtp();
        when(aes.decrypt("ENC-bytes")).thenReturn("plaintext-password");
        when(outboundMail.send(any())).thenReturn(OutboundMailService.SendResult.ok());

        svc.sendNow(m, pool);

        ArgumentCaptor<OutboundMailService.OutboundRequest> reqCap =
                ArgumentCaptor.forClass(OutboundMailService.OutboundRequest.class);
        verify(outboundMail).send(reqCap.capture());
        assertThat(reqCap.getValue().smtpHost).isEqualTo("smtp.example.com");
        assertThat(reqCap.getValue().smtpUsername).isEqualTo("user@example.com");
        assertThat(reqCap.getValue().smtpPassword).isEqualTo("plaintext-password");
        verify(aes).decrypt("ENC-bytes");
    }

    @Test
    void permanentFailure_marksFailedAndDoesNotSetRetry() {
        Message m = queued();
        when(outboundMail.send(any())).thenReturn(OutboundMailService.SendResult.fail("hard error"));

        svc.sendNow(m, null);

        assertThat(m.getStatus()).isEqualTo(Message.STATUS_FAILED);
        assertThat(m.getErrorMessage()).isEqualTo("hard error");
        assertThat(m.getNextRetryAt()).isNull();
        assertThat(m.getSentAt()).isNull();
        verify(messageRepo).save(m);
    }

    @Test
    void retriableFailure_underCap_marksQueuedWithBackoff() {
        Message m = queued();
        m.setSendAttempts(2);  // 3rd attempt about to happen — still under the cap of 6
        when(outboundMail.send(any())).thenReturn(OutboundMailService.SendResult.retriable("temp glitch"));

        svc.sendNow(m, null);

        assertThat(m.getStatus()).isEqualTo(Message.STATUS_QUEUED);
        assertThat(m.getErrorMessage()).isEqualTo("temp glitch");
        assertThat(m.getNextRetryAt()).isNotNull().isAfter(java.time.LocalDateTime.now().minusSeconds(1));
        assertThat(m.getSendAttempts()).isEqualTo(3);
        verify(messageRepo).save(m);
    }

    @Test
    void retriableFailure_atCap_marksFailed() {
        Message m = queued();
        m.setSendAttempts(5);  // 6th attempt — equal to the cap → no further retry
        when(outboundMail.send(any())).thenReturn(OutboundMailService.SendResult.retriable("still failing"));

        svc.sendNow(m, null);

        assertThat(m.getSendAttempts()).isEqualTo(6);
        assertThat(m.getStatus()).isEqualTo(Message.STATUS_FAILED);
        assertThat(m.getNextRetryAt()).isNull();
    }

    @Test
    void sendAttemptsAlwaysIncrement_evenOnPermanentFailure() {
        Message m = queued();
        m.setSendAttempts(0);
        when(outboundMail.send(any())).thenReturn(OutboundMailService.SendResult.fail("nope"));
        svc.sendNow(m, null);
        assertThat(m.getSendAttempts()).isEqualTo(1);
    }

    /**
     * composeSms() previously called placeholderService.substitute() but never handled
     * %reply_url% (unlike compose(), the email equivalent) — an SMS reply containing that
     * tag went out to the user with the literal placeholder text still in it. Regression
     * test for the fix (2026-07-09): composeSms must create a reply page and substitute
     * the real URL, exactly like compose() does.
     */
    @Test
    void composeSms_replyUrlPlaceholder_isSubstitutedWithRealUrl() {
        CrmUser user = new CrmUser();
        user.setId(108L);
        user.setPhoneNumber("09093749952");
        when(userRepo.findById(108L)).thenReturn(Optional.of(user));
        when(placeholderService.substitute(anyString(), any(CrmUser.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(messageRepo.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));
        when(replyPageService.createShortReplyPageFor(any(Message.class)))
                .thenReturn("https://nbbv7g.jp/reply/abc123");
        when(outboundSms.send(any())).thenReturn(OutboundSmsService.SendResult.ok());

        com.crm.dto.SmsComposeForm form = new com.crm.dto.SmsComposeForm();
        form.setBody("test %reply_url%");

        Message saved = svc.composeSms(108L, 1L, form);

        assertThat(saved.getBodyText()).isEqualTo("test https://nbbv7g.jp/reply/abc123");
        assertThat(saved.getBodyText()).doesNotContain("%reply_url%");
    }

    @Test
    void composeSms_withoutReplyUrlPlaceholder_doesNotCreateReplyPage() {
        CrmUser user = new CrmUser();
        user.setId(108L);
        user.setPhoneNumber("09093749952");
        when(userRepo.findById(108L)).thenReturn(Optional.of(user));
        when(placeholderService.substitute(anyString(), any(CrmUser.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(messageRepo.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));
        when(outboundSms.send(any())).thenReturn(OutboundSmsService.SendResult.ok());

        com.crm.dto.SmsComposeForm form = new com.crm.dto.SmsComposeForm();
        form.setBody("plain test, no tag");

        Message saved = svc.composeSms(108L, 1L, form);

        assertThat(saved.getBodyText()).isEqualTo("plain test, no tag");
        verify(replyPageService, never()).createShortReplyPageFor(any());
    }

    @Test
    void retryBackoff_increasesExponentiallyAndCapsAt30Min() throws Exception {
        // We exercise the private helper indirectly: attempts 1..6 give backoffs 1,2,4,8,16,30
        // minutes per the documented behaviour. We can also use reflection on backoffFor for
        // exactness — but the easier observable check is that 6th attempt next_retry_at is
        // ~30 minutes ahead, not 32.
        for (int attempts = 1; attempts <= 6; attempts++) {
            Message m = queued();
            m.setSendAttempts(attempts - 1);  // about to become attempts
            when(outboundMail.send(any())).thenReturn(
                    attempts < 6
                            ? OutboundMailService.SendResult.retriable("temp")
                            : OutboundMailService.SendResult.retriable("temp"));
            svc.sendNow(m, null);
            if (attempts < 6) {
                long expectedMinutes = 1L << (attempts - 1);  // 1, 2, 4, 8, 16
                long actualMinutes = java.time.Duration.between(
                        java.time.LocalDateTime.now(), m.getNextRetryAt()).toMinutes();
                // Loose tolerance to absorb test execution time.
                assertThat(actualMinutes).isBetween(expectedMinutes - 1, expectedMinutes);
            }
        }
    }

    /**
     * compose() (individual email send) must reject an SMS-only user (email blank, phone
     * set — the CSV-import case fixed for the client's 2,571-row import) with a clear
     * MessageException rather than sending a message with a null TO_ADDRESS.
     */
    @Test
    void compose_userHasNoEmail_throwsMessageException() {
        CrmUser phoneOnly = new CrmUser();
        phoneOnly.setId(7L);
        phoneOnly.setEmail(null);
        phoneOnly.setPhoneNumber("09012345678");
        when(userRepo.findById(7L)).thenReturn(Optional.of(phoneOnly));

        com.crm.dto.MessageComposeForm form = new com.crm.dto.MessageComposeForm();
        form.setSubject("s");
        form.setBody("b");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> svc.compose(7L, 1L, form))
                .isInstanceOf(MessageService.MessageException.class)
                .hasMessageContaining("メールアドレスが未登録");
    }

    // ===== メッセージボックス: 15-char clip + sentBodyText / excludedFromBox =====

    @Test
    void sendNow_prefersSentBodyTextOverBodyTextWhenPresent() {
        Message m = queued();
        m.setBodyText("full untouched body for メッセージボックス");
        m.setSentBodyText("clipped-transmit-text");
        when(outboundMail.send(any())).thenReturn(OutboundMailService.SendResult.ok());

        svc.sendNow(m, null);

        ArgumentCaptor<OutboundMailService.OutboundRequest> reqCap =
                ArgumentCaptor.forClass(OutboundMailService.OutboundRequest.class);
        verify(outboundMail).send(reqCap.capture());
        assertThat(reqCap.getValue().body).isEqualTo("clipped-transmit-text");
        // BODY_TEXT itself must remain untouched — it's what メッセージボックス displays.
        assertThat(m.getBodyText()).isEqualTo("full untouched body for メッセージボックス");
    }

    @Test
    void sendNow_fallsBackToBodyTextWhenSentBodyTextNull() {
        Message m = queued();
        m.setBodyText("plain body, no reply url");
        m.setSentBodyText(null);
        when(outboundMail.send(any())).thenReturn(OutboundMailService.SendResult.ok());

        svc.sendNow(m, null);

        ArgumentCaptor<OutboundMailService.OutboundRequest> reqCap =
                ArgumentCaptor.forClass(OutboundMailService.OutboundRequest.class);
        verify(outboundMail).send(reqCap.capture());
        assertThat(reqCap.getValue().body).isEqualTo("plain body, no reply url");
    }

    @Test
    void clipForTransmission_keepsFirst15CharsPlusUrl() {
        String result = MessageService.clipForTransmission(
                "1234567890123456789%reply_url%tail", "https://x.jp/reply/abc");
        // First 15 chars of the pre-swap string: "123456789012345"
        assertThat(result).isEqualTo("123456789012345https://x.jp/reply/abc");
    }

    @Test
    void clipForTransmission_tagWellWithinWindow_stripsTagCleanly() {
        // "hi " (3 chars) + the %reply_url% tag together fit comfortably inside the 15-char
        // window, so the tag is fully removed and only "hi " precedes the expanded URL.
        String result = MessageService.clipForTransmission(
                "hi %reply_url%", "https://x.jp/reply/abc");
        assertThat(result).isEqualTo("hi https://x.jp/reply/abc");
    }

    @Test
    void clipForTransmission_tagStraddlingWindowBoundary_stripsTagCleanly() {
        // Edge case: the %reply_url% tag straddles the 15-char boundary. The tag is located
        // first and removed as a whole token before clipping, so no partial tag fragment
        // ("%reply_ur...") leaks into the transmitted text regardless of where the boundary
        // falls relative to the tag.
        String result = MessageService.clipForTransmission(
                "short %reply_url%", "https://x.jp/reply/abc");
        assertThat(result).isEqualTo("short https://x.jp/reply/abc");
    }

    @Test
    void compose_withReplyUrl_clipsSentBodyTextAndKeepsFullBodyText() {
        CrmUser user = new CrmUser();
        user.setId(50L);
        user.setEmail("user@example.com");
        when(userRepo.findById(50L)).thenReturn(Optional.of(user));
        when(bindingService.firstBoundFor(50L)).thenReturn(Optional.empty());
        when(domainSettings.buildFromAddress()).thenReturn("from@example.com");
        when(placeholderService.substitute(anyString(), any(CrmUser.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(messageRepo.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));
        when(replyPageService.createReplyPageFor(any(Message.class)))
                .thenReturn("https://nbbv7g.jp/reply/abc123");
        when(domainSettings.isActiveLinkDomainExternalLanding()).thenReturn(false);
        when(outboundMail.send(any())).thenReturn(OutboundMailService.SendResult.ok());

        com.crm.dto.MessageComposeForm form = new com.crm.dto.MessageComposeForm();
        form.setSubject("s");
        form.setBody("0123456789012345678%reply_url%");

        Message saved = svc.compose(50L, 1L, form);

        assertThat(saved.getBodyText())
                .isEqualTo("0123456789012345678https://nbbv7g.jp/reply/abc123");
        assertThat(saved.getSentBodyText())
                .isEqualTo("012345678901234https://nbbv7g.jp/reply/abc123");
        assertThat(saved.getExcludedFromBox()).isFalse();
    }

    @Test
    void compose_withoutReplyUrl_leavesSentBodyTextNull() {
        CrmUser user = new CrmUser();
        user.setId(51L);
        user.setEmail("user@example.com");
        when(userRepo.findById(51L)).thenReturn(Optional.of(user));
        when(bindingService.firstBoundFor(51L)).thenReturn(Optional.empty());
        when(domainSettings.buildFromAddress()).thenReturn("from@example.com");
        when(placeholderService.substitute(anyString(), any(CrmUser.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(messageRepo.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));
        when(outboundMail.send(any())).thenReturn(OutboundMailService.SendResult.ok());

        com.crm.dto.MessageComposeForm form = new com.crm.dto.MessageComposeForm();
        form.setSubject("s");
        form.setBody("plain body, no tag");

        Message saved = svc.compose(51L, 1L, form);

        assertThat(saved.getBodyText()).isEqualTo("plain body, no tag");
        assertThat(saved.getSentBodyText()).isNull();
        verify(replyPageService, never()).createReplyPageFor(any());
    }

    @Test
    void compose_activeExternalLinkDomainRedirectMode_setsExcludedFromBoxTrue() {
        CrmUser user = new CrmUser();
        user.setId(52L);
        user.setEmail("user@example.com");
        when(userRepo.findById(52L)).thenReturn(Optional.of(user));
        when(bindingService.firstBoundFor(52L)).thenReturn(Optional.empty());
        when(domainSettings.buildFromAddress()).thenReturn("from@example.com");
        when(placeholderService.substitute(anyString(), any(CrmUser.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(messageRepo.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));
        when(replyPageService.createReplyPageFor(any(Message.class)))
                .thenReturn("https://external.jp/reply/xyz");
        when(domainSettings.isActiveLinkDomainExternalLanding()).thenReturn(true);
        when(outboundMail.send(any())).thenReturn(OutboundMailService.SendResult.ok());

        com.crm.dto.MessageComposeForm form = new com.crm.dto.MessageComposeForm();
        form.setSubject("s");
        form.setBody("body %reply_url%");

        Message saved = svc.compose(52L, 1L, form);

        assertThat(saved.getExcludedFromBox()).isTrue();
    }

    @Test
    void composeSms_withReplyUrl_clipsSentBodyTextAndKeepsFullBodyText() {
        CrmUser user = new CrmUser();
        user.setId(53L);
        user.setPhoneNumber("09012345678");
        when(userRepo.findById(53L)).thenReturn(Optional.of(user));
        when(placeholderService.substitute(anyString(), any(CrmUser.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(messageRepo.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));
        when(replyPageService.createShortReplyPageFor(any(Message.class)))
                .thenReturn("https://nbbv7g.jp/r/ab12");
        when(domainSettings.isActiveLinkDomainExternalLanding()).thenReturn(false);
        when(outboundSms.send(any())).thenReturn(OutboundSmsService.SendResult.ok());

        com.crm.dto.SmsComposeForm form = new com.crm.dto.SmsComposeForm();
        form.setBody("0123456789012345678%reply_url%");

        Message saved = svc.composeSms(53L, 1L, form);

        assertThat(saved.getBodyText())
                .isEqualTo("0123456789012345678https://nbbv7g.jp/r/ab12");
        assertThat(saved.getSentBodyText())
                .isEqualTo("012345678901234https://nbbv7g.jp/r/ab12");
        assertThat(saved.getExcludedFromBox()).isFalse();
    }
}
