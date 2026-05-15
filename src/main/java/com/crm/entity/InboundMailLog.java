package com.crm.entity;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "INBOUND_MAIL_LOG")
public class InboundMailLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "FROM_ADDRESS", nullable = false)
    private String fromAddress;

    @Column(name = "TO_ADDRESS", nullable = false)
    private String toAddress;

    @Column(name = "SUBJECT", columnDefinition = "TEXT")
    private String subject;

    @Column(name = "BODY_TEXT", columnDefinition = "LONGTEXT")
    private String bodyText;

    @Column(name = "RAW_CONTENT", columnDefinition = "LONGTEXT")
    private String rawContent;

    @Column(name = "MATCHED_USER_ID")
    private Long matchedUserId;

    @Column(name = "IS_PROCESSED")
    private Boolean isProcessed;

    @Column(name = "IS_REJECTED")
    private Boolean isRejected;

    @Column(name = "REJECT_REASON")
    private String rejectReason;

    @Column(name = "MESSAGE_ID_HEADER", length = 255)
    private String messageIdHeader;

    @Column(name = "CREATED_AT", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (isProcessed == null) isProcessed = Boolean.FALSE;
        if (isRejected == null) isRejected = Boolean.FALSE;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getFromAddress() { return fromAddress; }
    public void setFromAddress(String fromAddress) { this.fromAddress = fromAddress; }
    public String getToAddress() { return toAddress; }
    public void setToAddress(String toAddress) { this.toAddress = toAddress; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getBodyText() { return bodyText; }
    public void setBodyText(String bodyText) { this.bodyText = bodyText; }
    public String getRawContent() { return rawContent; }
    public void setRawContent(String rawContent) { this.rawContent = rawContent; }
    public Long getMatchedUserId() { return matchedUserId; }
    public void setMatchedUserId(Long matchedUserId) { this.matchedUserId = matchedUserId; }
    public Boolean getIsProcessed() { return isProcessed; }
    public void setIsProcessed(Boolean isProcessed) { this.isProcessed = isProcessed; }
    public Boolean getIsRejected() { return isRejected; }
    public void setIsRejected(Boolean isRejected) { this.isRejected = isRejected; }
    public String getRejectReason() { return rejectReason; }
    public void setRejectReason(String rejectReason) { this.rejectReason = rejectReason; }
    public String getMessageIdHeader() { return messageIdHeader; }
    public void setMessageIdHeader(String messageIdHeader) { this.messageIdHeader = messageIdHeader; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
