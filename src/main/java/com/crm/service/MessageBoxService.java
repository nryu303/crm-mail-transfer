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

    /**
     * As of 2026-08-23, %reply_url% always points at the CRM's own reply form (see
     * DomainSettingService#buildReplyUrl) — it no longer routes through whichever 外部リンク
     * ドメイン happens to be 使用中. That domain-tracking behaviour moved to the separate
     * %external_url% tag. メッセージボックス is therefore always reachable via %reply_url% and
     * no longer needs to hide its own content based on external-domain state.
     */
    public Page<MessageBoxItem> listFor(Long userId, int page) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), PAGE_SIZE, Sort.unsorted());
        return messageRepository.findMessageBoxPage(userId, pageable).map(MessageBoxService::toItem);
    }

    /** Matches a bare http(s) URL run, same boundary rule as the outbound-mail linkifier
     *  (stops at whitespace/quote chars). Used to strip the %reply_url% link out of the
     *  body shown in メッセージボックス — the box is reached BY that link, so repeating the
     *  URL inside its own destination page is redundant and, per operator request, should
     *  not be shown; only the title/body text itself is relevant there. */
    private static final java.util.regex.Pattern URL_RE = java.util.regex.Pattern.compile(
            "https?://[^\\s<>\"']+", java.util.regex.Pattern.CASE_INSENSITIVE);

    private static MessageBoxItem toItem(Message m) {
        return new MessageBoxItem(m.getId(), m.getSentAt(), m.getSubject(),
                stripUrls(m.getBodyText()), deriveReplyLabel(m), m.getChannel());
    }

    /** Removes any bare URL from the body and trims the trailing whitespace/newlines that
     *  removal leaves behind, so メッセージボックス shows only the title/body text. */
    static String stripUrls(String bodyText) {
        if (bodyText == null) return null;
        return URL_RE.matcher(bodyText).replaceAll("").replaceAll("[ \\t]+\\n", "\n").stripTrailing();
    }

    /**
     * "Re: 件名" when a subject exists (EMAIL/BROADCAST). SMS has no subject field — operators
     * are told to put a name/identifier at the start of the SMS body instead, so fall back to
     * a short leading fragment of the body as the label.
     */
    public static String deriveReplyLabel(Message m) {
        String subject = m.getSubject();
        if (subject != null && !subject.trim().isEmpty()) return "Re: " + subject.trim();
        String body = stripUrls(m.getBodyText());
        if (body == null || body.trim().isEmpty()) return "Re:";
        String firstLine = body.split("\\r?\\n", 2)[0].trim();
        String snippet = firstLine.length() > 20 ? firstLine.substring(0, 20) + "…" : firstLine;
        return "Re: " + snippet;
    }

    /** 受信履歴 tab — this user's own past inbound submissions. */
    public Page<MessageBoxItem> listInboundFor(Long userId, int page) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), PAGE_SIZE, Sort.unsorted());
        return messageRepository.findInboundBoxPage(userId, pageable).map(MessageBoxService::toItem);
    }

    @Transactional
    public int dismissSelected(Long userId, List<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) return 0;
        return messageRepository.dismissBoxByUserIdAndIds(userId, messageIds, LocalDateTime.now());
    }

    @Transactional
    public int dismissAll(Long userId) {
        return messageRepository.dismissAllBoxByUserId(userId, LocalDateTime.now());
    }

    /** 受信履歴 tab's 全件削除 — separate from {@link #dismissAll} since that one only ever
     *  touched direction=OUT rows (送信履歴). Selective delete reuses {@link #dismissSelected}
     *  unchanged — it already matches by id+userId regardless of direction. */
    @Transactional
    public int dismissAllInbound(Long userId) {
        return messageRepository.dismissAllInboundBoxByUserId(userId, LocalDateTime.now());
    }
}
