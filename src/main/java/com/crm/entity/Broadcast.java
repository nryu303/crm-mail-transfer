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
@Table(name = "BROADCAST")
public class Broadcast {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_SCHEDULED = "SCHEDULED";
    public static final String STATUS_SENDING = "SENDING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ADMIN_USER_ID", nullable = false)
    private Long adminUserId;

    @Column(name = "TITLE", nullable = false, length = 500)
    private String title;

    @Column(name = "SUBJECT", nullable = false, columnDefinition = "TEXT")
    private String subject;

    @Column(name = "BODY_TEXT", nullable = false, columnDefinition = "LONGTEXT")
    private String bodyText;

    @Column(name = "BODY_HTML", columnDefinition = "LONGTEXT")
    private String bodyHtml;

    @Column(name = "CHANNEL", length = 16)
    private String channel;

    @Column(name = "TARGET_FILTER", columnDefinition = "TEXT")
    private String targetFilter;

    @Column(name = "SCHEDULED_AT")
    private LocalDateTime scheduledAt;

    @Column(name = "STATUS", length = 16)
    private String status;

    @Column(name = "RATE_PER_MINUTE")
    private Integer ratePerMinute;

    @Column(name = "TOTAL_COUNT")
    private Integer totalCount;

    @Column(name = "SENT_COUNT")
    private Integer sentCount;

    @Column(name = "FAILED_COUNT")
    private Integer failedCount;

    /** Users in the broadcast's filter whose CRM_USER.ADDRESS_INVALID_REASON was set —
     *  skipped at queue time, never counted toward TOTAL_COUNT but tracked here so the
     *  detail page can surface them as 「送信不可: N件」 with click-through. */
    @Column(name = "UNSENDABLE_COUNT")
    private Integer unsendableCount;

    /** Comma-separated CRM_USER.id list of the unsendable users. Stored as TEXT so even
     *  a 5K-user broadcast with all addresses dot-broken fits (~30KB worst case). */
    @Column(name = "UNSENDABLE_USER_IDS", columnDefinition = "TEXT")
    private String unsendableUserIds;

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
        if (channel == null) channel = "EMAIL";
        if (ratePerMinute == null) ratePerMinute = 60;
        if (totalCount == null) totalCount = 0;
        if (sentCount == null) sentCount = 0;
        if (failedCount == null) failedCount = 0;
        if (unsendableCount == null) unsendableCount = 0;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getAdminUserId() { return adminUserId; }
    public void setAdminUserId(Long adminUserId) { this.adminUserId = adminUserId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getBodyText() { return bodyText; }
    public void setBodyText(String bodyText) { this.bodyText = bodyText; }
    public String getBodyHtml() { return bodyHtml; }
    public void setBodyHtml(String bodyHtml) { this.bodyHtml = bodyHtml; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getTargetFilter() { return targetFilter; }
    public void setTargetFilter(String targetFilter) { this.targetFilter = targetFilter; }
    public LocalDateTime getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(LocalDateTime scheduledAt) { this.scheduledAt = scheduledAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getRatePerMinute() { return ratePerMinute; }
    public void setRatePerMinute(Integer ratePerMinute) { this.ratePerMinute = ratePerMinute; }
    public Integer getTotalCount() { return totalCount; }
    public void setTotalCount(Integer totalCount) { this.totalCount = totalCount; }
    public Integer getSentCount() { return sentCount; }
    public void setSentCount(Integer sentCount) { this.sentCount = sentCount; }
    public Integer getFailedCount() { return failedCount; }
    public void setFailedCount(Integer failedCount) { this.failedCount = failedCount; }
    public Integer getUnsendableCount() { return unsendableCount; }
    public void setUnsendableCount(Integer unsendableCount) { this.unsendableCount = unsendableCount; }
    public String getUnsendableUserIds() { return unsendableUserIds; }
    public void setUnsendableUserIds(String unsendableUserIds) { this.unsendableUserIds = unsendableUserIds; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
