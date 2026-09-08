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

/** One timed action within a {@link DiffDefinition}'s timeline: either send a real message
 *  (title+body for EMAIL, body-only for SMS — placeholders/tags resolved per recipient at
 *  execution time via the same pipeline as 一斉送信) or switch the target users' active
 *  reply-page memo slot (HTML_SWITCH). Ordered by {@link #stepOrder} for the vertical
 *  scrolling editor UI ("下にスクロールで未来のステップ"). */
@Entity
@Table(name = "DIFF_STEP")
public class DiffStep {

    public static final String STEP_MESSAGE = "MESSAGE";
    public static final String STEP_HTML_SWITCH = "HTML_SWITCH";

    public static final String CHANNEL_EMAIL = "EMAIL";
    public static final String CHANNEL_SMS = "SMS";

    /** 当日 (分後). */
    public static final String OFFSET_MINUTES = "MINUTES";
    /** 翌日以降 (日数+時刻). */
    public static final String OFFSET_DAYS = "DAYS";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "DIFF_DEFINITION_ID", nullable = false)
    private Long diffDefinitionId;

    @Column(name = "STEP_ORDER", nullable = false)
    private Integer stepOrder;

    @Column(name = "OFFSET_MODE", nullable = false, length = 16)
    private String offsetMode;
    @Column(name = "OFFSET_MINUTES")
    private Integer offsetMinutes;
    @Column(name = "OFFSET_DAYS")
    private Integer offsetDays;
    /** "HH:mm", used when offsetMode=DAYS. */
    @Column(name = "OFFSET_CLOCK_TIME", length = 5)
    private String offsetClockTime;

    @Column(name = "STEP_TYPE", nullable = false, length = 16)
    private String stepType;

    /** EMAIL | SMS — only meaningful when stepType=MESSAGE. */
    @Column(name = "CHANNEL", length = 16)
    private String channel;

    /** Only used for EMAIL (SMS has no subject line). */
    @Column(name = "SUBJECT", length = 500)
    private String subject;

    /** Message body for MESSAGE steps. */
    @Column(name = "BODY", columnDefinition = "LONGTEXT")
    private String body;

    /** Target memo slot (1..10) for HTML_SWITCH steps. */
    @Column(name = "MEMO_SLOT")
    private Integer memoSlot;

    @Column(name = "CREATED_AT", nullable = false)
    private LocalDateTime createdAt;
    @Column(name = "UPDATED_AT", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getDiffDefinitionId() { return diffDefinitionId; }
    public void setDiffDefinitionId(Long diffDefinitionId) { this.diffDefinitionId = diffDefinitionId; }
    public Integer getStepOrder() { return stepOrder; }
    public void setStepOrder(Integer stepOrder) { this.stepOrder = stepOrder; }
    public String getOffsetMode() { return offsetMode; }
    public void setOffsetMode(String offsetMode) { this.offsetMode = offsetMode; }
    public Integer getOffsetMinutes() { return offsetMinutes; }
    public void setOffsetMinutes(Integer offsetMinutes) { this.offsetMinutes = offsetMinutes; }
    public Integer getOffsetDays() { return offsetDays; }
    public void setOffsetDays(Integer offsetDays) { this.offsetDays = offsetDays; }
    public String getOffsetClockTime() { return offsetClockTime; }
    public void setOffsetClockTime(String offsetClockTime) { this.offsetClockTime = offsetClockTime; }
    public String getStepType() { return stepType; }
    public void setStepType(String stepType) { this.stepType = stepType; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public Integer getMemoSlot() { return memoSlot; }
    public void setMemoSlot(Integer memoSlot) { this.memoSlot = memoSlot; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
