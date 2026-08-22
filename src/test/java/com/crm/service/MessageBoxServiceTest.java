package com.crm.service;

import com.crm.dto.MessageBoxItem;
import com.crm.entity.Message;
import com.crm.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessageBoxServiceTest {

    private MessageRepository messageRepository;
    private DomainSettingService domainSettingService;
    private MessageBoxService svc;

    @BeforeEach
    void setUp() {
        messageRepository = mock(MessageRepository.class);
        domainSettingService = mock(DomainSettingService.class);
        when(domainSettingService.isActiveLinkDomainExternalLanding()).thenReturn(false);
        svc = new MessageBoxService(messageRepository, domainSettingService);
    }

    private static Message emailMessage(Long id, String subject, String body) {
        Message m = new Message();
        m.setId(id);
        m.setChannel(Message.CHANNEL_EMAIL);
        m.setSubject(subject);
        m.setBodyText(body);
        m.setSentAt(LocalDateTime.now());
        return m;
    }

    private static Message smsMessage(Long id, String body) {
        Message m = new Message();
        m.setId(id);
        m.setChannel(Message.CHANNEL_SMS);
        m.setSubject(null);
        m.setBodyText(body);
        m.setSentAt(LocalDateTime.now());
        return m;
    }

    @Test
    void listFor_mapsMessagesToItemsPreservingOrder() {
        List<Message> messages = Arrays.asList(
                emailMessage(1L, "曽我部です。", "テストテスト"),
                smsMessage(2L, "田中です。用件があります"));
        Page<Message> page = new PageImpl<>(messages);
        when(messageRepository.findMessageBoxPage(eq(99L), any())).thenReturn(page);

        Page<MessageBoxItem> result = svc.listFor(99L, 0);

        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getContent().get(0).getId()).isEqualTo(1L);
        assertThat(result.getContent().get(0).getReplyLabel()).isEqualTo("Re: 曽我部です。");
        assertThat(result.getContent().get(1).getId()).isEqualTo(2L);
        assertThat(result.getContent().get(1).getReplyLabel()).isEqualTo("Re: 田中です。用件があります");
    }

    @Test
    void listFor_usesPageSizeOfFive() {
        when(messageRepository.findMessageBoxPage(anyLong(), any()))
                .thenReturn(new PageImpl<>(Collections.emptyList()));

        svc.listFor(1L, 0);

        org.mockito.ArgumentCaptor<org.springframework.data.domain.Pageable> cap =
                org.mockito.ArgumentCaptor.forClass(org.springframework.data.domain.Pageable.class);
        verify(messageRepository).findMessageBoxPage(eq(1L), cap.capture());
        assertThat(cap.getValue().getPageSize()).isEqualTo(MessageBoxService.PAGE_SIZE);
        assertThat(cap.getValue().getPageNumber()).isEqualTo(0);
    }

    @Test
    void deriveReplyLabel_withSubject_usesSubject() {
        Message m = emailMessage(1L, "サポート窓口です。", "本文");
        assertThat(MessageBoxService.deriveReplyLabel(m)).isEqualTo("Re: サポート窓口です。");
    }

    @Test
    void deriveReplyLabel_smsWithoutSubject_usesLeadingBodyFragment() {
        Message m = smsMessage(2L, "高橋です。本日はお世話になりました、追加のご連絡です");
        String label = MessageBoxService.deriveReplyLabel(m);
        assertThat(label).startsWith("Re: 高橋です。");
    }

    @Test
    void deriveReplyLabel_smsWithEmptyBody_returnsBareReLabel() {
        Message m = smsMessage(3L, null);
        assertThat(MessageBoxService.deriveReplyLabel(m)).isEqualTo("Re:");
    }

    @Test
    void dismissSelected_delegatesToRepositoryScopedToUser() {
        when(messageRepository.dismissBoxByUserIdAndIds(eq(7L), any(), any())).thenReturn(2);

        int n = svc.dismissSelected(7L, Arrays.asList(1L, 2L));

        assertThat(n).isEqualTo(2);
        verify(messageRepository).dismissBoxByUserIdAndIds(eq(7L), eq(Arrays.asList(1L, 2L)), any());
    }

    @Test
    void dismissSelected_emptyIds_doesNotCallRepository() {
        int n = svc.dismissSelected(7L, Collections.emptyList());

        assertThat(n).isEqualTo(0);
        verify(messageRepository, never()).dismissBoxByUserIdAndIds(any(), any(), any());
    }

    @Test
    void dismissAll_delegatesToRepositoryScopedToUser() {
        when(messageRepository.dismissAllBoxByUserId(eq(7L), any())).thenReturn(5);

        int n = svc.dismissAll(7L);

        assertThat(n).isEqualTo(5);
        verify(messageRepository).dismissAllBoxByUserId(eq(7L), any());
    }

    /**
     * 外部リンクドメイン exclusion is now decided at VIEW time (not baked into the query via
     * EXCLUDED_FROM_BOX), so when the currently-active domain is in REDIRECT/CUSTOM_HTML mode
     * the whole box must come back empty — regardless of what the repository would return —
     * mirroring that a visitor can't even reach the reply form in that state.
     */
    @Test
    void listFor_activeExternalLinkDomain_returnsEmptyPageWithoutQueryingRepository() {
        when(domainSettingService.isActiveLinkDomainExternalLanding()).thenReturn(true);

        Page<MessageBoxItem> result = svc.listFor(99L, 0);

        assertThat(result.getContent()).isEmpty();
        verify(messageRepository, never()).findMessageBoxPage(any(), any());
    }

    @Test
    void listFor_noActiveExternalLinkDomain_queriesRepositoryNormally() {
        when(domainSettingService.isActiveLinkDomainExternalLanding()).thenReturn(false);
        when(messageRepository.findMessageBoxPage(eq(99L), any()))
                .thenReturn(new PageImpl<>(Collections.singletonList(emailMessage(1L, "件名", "本文"))));

        Page<MessageBoxItem> result = svc.listFor(99L, 0);

        assertThat(result.getContent()).hasSize(1);
    }

    @Test
    void stripUrls_removesTrailingReplyUrlAndTrimsWhitespace() {
        String result = MessageBoxService.stripUrls("テストテスト本文です https://nbbv7g.jp/reply/abc123");
        assertThat(result).isEqualTo("テストテスト本文です");
        assertThat(result).doesNotContain("http");
    }

    @Test
    void stripUrls_noUrl_returnsUnchanged() {
        String result = MessageBoxService.stripUrls("URLを含まない本文");
        assertThat(result).isEqualTo("URLを含まない本文");
    }

    @Test
    void listFor_bodyTextInItem_hasUrlStripped() {
        List<Message> messages = Collections.singletonList(
                emailMessage(1L, "件名", "本文テキスト https://nbbv7g.jp/reply/xyz789"));
        when(messageRepository.findMessageBoxPage(eq(50L), any()))
                .thenReturn(new PageImpl<>(messages));

        Page<MessageBoxItem> result = svc.listFor(50L, 0);

        assertThat(result.getContent().get(0).getBodyText()).isEqualTo("本文テキスト");
    }
}
