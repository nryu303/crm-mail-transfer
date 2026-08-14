package com.crm.dto;

import java.time.LocalDateTime;

/**
 * Template-facing view of a メッセージボックス entry — deliberately not the {@code Message}
 * entity itself, so internal fields (fromAddress, sentBodyText, etc.) never leak into the
 * public /reply/{token} page or the admin preview.
 */
public class MessageBoxItem {

    private final Long id;
    private final LocalDateTime sentAt;
    private final String subject;
    private final String bodyText;
    private final String replyLabel;
    private final String channel;

    public MessageBoxItem(Long id, LocalDateTime sentAt, String subject, String bodyText,
                           String replyLabel, String channel) {
        this.id = id;
        this.sentAt = sentAt;
        this.subject = subject;
        this.bodyText = bodyText;
        this.replyLabel = replyLabel;
        this.channel = channel;
    }

    public Long getId() { return id; }
    public LocalDateTime getSentAt() { return sentAt; }
    public String getSubject() { return subject; }
    public String getBodyText() { return bodyText; }
    public String getReplyLabel() { return replyLabel; }
    public String getChannel() { return channel; }
}
