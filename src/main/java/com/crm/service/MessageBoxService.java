package com.crm.service;

import com.crm.dto.MessageBoxItem;
import com.crm.entity.Message;
import com.crm.repository.MessageRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * メッセージボックス (message box / inbox history): read + soft-delete side of the feature,
 * shared identically by the public /reply/{token} footer section and the admin
 * /manager/users/{id}/message-box preview. Kept separate from MessageService, which is
 * already large and centred on the send pipeline — this is a narrow, read/dismiss-only
 * concern consumed by two different controllers.
 */
@Service
public class MessageBoxService {

    public static final int PAGE_SIZE = 5;

    private final MessageRepository messageRepository;

    public MessageBoxService(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    public Page<MessageBoxItem> listFor(Long userId, int page) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), PAGE_SIZE, Sort.unsorted());
        return messageRepository.findMessageBoxPage(userId, pageable).map(MessageBoxService::toItem);
    }

    private static MessageBoxItem toItem(Message m) {
        return new MessageBoxItem(m.getId(), m.getSentAt(), m.getSubject(),
                m.getBodyText(), deriveReplyLabel(m), m.getChannel());
    }

    /**
     * "Re: 件名" when a subject exists (EMAIL/BROADCAST). SMS has no subject field — operators
     * are told to put a name/identifier at the start of the SMS body instead, so fall back to
     * a short leading fragment of the body as the label.
     */
    public static String deriveReplyLabel(Message m) {
        String subject = m.getSubject();
        if (subject != null && !subject.trim().isEmpty()) return "Re: " + subject.trim();
        String body = m.getBodyText();
        if (body == null || body.trim().isEmpty()) return "Re:";
        String firstLine = body.split("\\r?\\n", 2)[0].trim();
        String snippet = firstLine.length() > 20 ? firstLine.substring(0, 20) + "…" : firstLine;
        return "Re: " + snippet;
    }

    @Transactional
    public int dismissSelected(Long userId, List<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) return 0;
        return messageRepository.dismissBoxByUserIdAndIds(userId, messageIds, LocalDateTime.now());
    }
}
