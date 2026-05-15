package com.crm.entity;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.PreUpdate;
import javax.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "MESSAGE")
public class Message {

    public static final String DIR_OUT = "OUT";
    public static final String DIR_IN = "IN";

    public static final String CHANNEL_EMAIL = "EMAIL";
    public static final String CHANNEL_WEB_REPLY = "WEB_REPLY";
    public static final String CHANNEL_BROADCAST = "BROADCAST";

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_QUEUED = "QUEUED";
    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_DELIVERED = "DELIVERED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_READ = "READ";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "USER_ID", nullable = false)
    private Long userId;

    @Column(name = "ADMIN_USER_ID")
    private Long adminUserId;

    @Column(name = "DIRECTION", nullable = false, length = 8)
    private String direction;

    @Column(name = "CHANNEL", nullable = false, length = 16)
    private String channel;

    @Column(name = "SUBJECT", columnDefinition = "TEXT")
    private String subject;

    @Column(name = "BODY_TEXT", columnDefinition = "LONGTEXT")
    private String bodyText;

    @Column(name = "BODY_HTML", columnDefinition = "LONGTEXT")
    private String bodyHtml;

    @Column(name = "FROM_ADDRESS")
    private String fromAddress;

    @Column(name = "TO_ADDRESS")
    private String toAddress;

    @Column(name = "REPLY_PAGE_TOKEN", length = 64)
    private String replyPageToken;

    @Column(name = "STATUS", length = 16)
    private String status;

    @Column(name = "SCHEDULED_AT")
    private LocalDateTime scheduledAt;

    @Column(name = "SENT_AT")
    private LocalDateTime sentAt;

    @Column(name = "READ_AT")
    private LocalDateTime readAt;

    @Column(name = "ERROR_MESSAGE", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "SEND_ATTEMPTS")
    private Integer sendAttempts;

    @Column(name = "NEXT_RETRY_AT")
    private LocalDateTime nextRetryAt;

    @Column(name = "BROADCAST_ID")
    private Long broadcastId;

    @Column(name = "REPLY_TO_MESSAGE_ID")
    private Long replyToMessageId;

    @Column(name = "MESSAGE_ID_HEADER", length = 255)
    private String messageIdHeader;

    @Column(name = "CREATED_AT", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "UPDATED_AT", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (status == null) status = STATUS_DRAFT;
        if (sendAttempts == null) sendAttempts = 0;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getAdminUserId() { return adminUserId; }
    public void setAdminUserId(Long adminUserId) { this.adminUserId = adminUserId; }
    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getBodyText() { return bodyText; }
    public void setBodyText(String bodyText) { this.bodyText = bodyText; }
    public String getBodyHtml() { return bodyHtml; }
    public void setBodyHtml(String bodyHtml) { this.bodyHtml = bodyHtml; }
    public String getFromAddress() { return fromAddress; }
    public void setFromAddress(String fromAddress) { this.fromAddress = fromAddress; }
    public String getToAddress() { return toAddress; }
    public void setToAddress(String toAddress) { this.toAddress = toAddress; }
    public String getReplyPageToken() { return replyPageToken; }
    public void setReplyPageToken(String replyPageToken) { this.replyPageToken = replyPageToken; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(LocalDateTime scheduledAt) { this.scheduledAt = scheduledAt; }
    public LocalDateTime getSentAt() { return sentAt; }
    public void setSentAt(LocalDateTime sentAt) { this.sentAt = sentAt; }
    public LocalDateTime getReadAt() { return readAt; }
    public void setReadAt(LocalDateTime readAt) { this.readAt = readAt; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Integer getSendAttempts() { return sendAttempts; }
    public void setSendAttempts(Integer sendAttempts) { this.sendAttempts = sendAttempts; }
    public LocalDateTime getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(LocalDateTime nextRetryAt) { this.nextRetryAt = nextRetryAt; }
    public Long getBroadcastId() { return broadcastId; }
    public void setBroadcastId(Long broadcastId) { this.broadcastId = broadcastId; }
    public Long getReplyToMessageId() { return replyToMessageId; }
    public void setReplyToMessageId(Long replyToMessageId) { this.replyToMessageId = replyToMessageId; }
    public String getMessageIdHeader() { return messageIdHeader; }
    public void setMessageIdHeader(String messageIdHeader) { this.messageIdHeader = messageIdHeader; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
