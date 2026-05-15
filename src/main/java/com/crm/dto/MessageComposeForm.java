package com.crm.dto;

import org.springframework.format.annotation.DateTimeFormat;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.time.LocalDateTime;

public class MessageComposeForm {

    @NotBlank(message = "件名を入力してください")
    @Size(max = 500)
    private String subject;

    @NotBlank(message = "本文を入力してください")
    private String body;

    /** Optional. If set and in the future, message is queued; if null, sent immediately. */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime scheduledAt;

    /** If set, this outbound is a reply to the specified inbound MESSAGE.ID. */
    private Long replyToMessageId;

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public LocalDateTime getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(LocalDateTime scheduledAt) { this.scheduledAt = scheduledAt; }

    public Long getReplyToMessageId() { return replyToMessageId; }
    public void setReplyToMessageId(Long replyToMessageId) { this.replyToMessageId = replyToMessageId; }
}
