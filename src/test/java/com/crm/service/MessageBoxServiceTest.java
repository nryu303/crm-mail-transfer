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
    private MessageBoxService svc;

    @BeforeEach
    void setUp() {
        messageRepository = mock(MessageRepository.class);
        svc = new MessageBoxService(messageRepository);
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
}
