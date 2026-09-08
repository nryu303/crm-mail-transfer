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

/**
 * One materialised, independently-scheduled execution of a {@link DiffStep}, snapshotted
 * at the moment its parent {@link DiffSchedule} was registered — so a later edit to the
 * DIFF_STEP template (title/body/slot/offset) never corrupts an already-pending execution.
 * Doubles as both the pending-list row and the history row (status transitions
 * PENDING → EXECUTED | CANCELLED | FAILED), mirroring the original single-step DiffSchedule
 * design this class replaces at per-step granularity.
 */
@Entity
@Table(name = "DIFF_SCHEDULE_STEP")
public class DiffScheduleStep {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_EXECUTED = "EXECUTED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "DIFF_SCHEDULE_ID", nullable = false)
    private Long diffScheduleId;

    @Column(name = "STEP_ORDER", nullable = false)
    private Integer stepOrder;

    @Column(name = "OFFSET_MODE", nullable = false, length = 16)
    private String offsetMode;
    @Column(name = "OFFSET_MINUTES")
    private Integer offsetMinutes;
    @Column(name = "OFFSET_DAYS")
    private Integer offsetDays;
    @Column(name = "OFFSET_CLOCK_TIME", length = 5)
    private String offsetClockTime;

    /** Computed absolute fire datetime = parent DiffSchedule.setAt + this step's offset. */
    @Column(name = "SCHEDULED_FOR", nullable = false)
    private LocalDateTime scheduledFor;

    @Column(name = "STEP_TYPE", nullable = false, length = 16)
    private String stepType;
    @Column(name = "CHANNEL", length = 16)
    private String channel;
    @Column(name = "SUBJECT_SNAPSHOT", length = 500)
    private String subjectSnapshot;
    @Column(name = "BODY_SNAPSHOT", columnDefinition = "LONGTEXT")
    private String bodySnapshot;
    @Column(name = "MEMO_SLOT_SNAPSHOT")
    private Integer memoSlotSnapshot;

    @Column(name = "STATUS", nullable = false, length = 16)
    private String status;
    @Column(name = "EXECUTED_AT")
    private LocalDateTime executedAt;
    @Column(name = "CANCELLED_AT")
    private LocalDateTime cancelledAt;
    @Column(name = "CANCELLED_BY_ADMIN_NAME", length = 255)
    private String cancelledByAdminName;
    @Column(name = "RESULT_DETAIL", columnDefinition = "TEXT")
    private String resultDetail;

    @Column(name = "CREATED_AT", nullable = false)
    private LocalDateTime createdAt;
    @Column(name = "UPDATED_AT", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (status == null) status = STATUS_PENDING;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getDiffScheduleId() { return diffScheduleId; }
    public void setDiffScheduleId(Long diffScheduleId) { this.diffScheduleId = diffScheduleId; }
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
    public LocalDateTime getScheduledFor() { return scheduledFor; }
    public void setScheduledFor(LocalDateTime scheduledFor) { this.scheduledFor = scheduledFor; }
    public String getStepType() { return stepType; }
    public void setStepType(String stepType) { this.stepType = stepType; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getSubjectSnapshot() { return subjectSnapshot; }
    public void setSubjectSnapshot(String subjectSnapshot) { this.subjectSnapshot = subjectSnapshot; }
    public String getBodySnapshot() { return bodySnapshot; }
    public void setBodySnapshot(String bodySnapshot) { this.bodySnapshot = bodySnapshot; }
    public Integer getMemoSlotSnapshot() { return memoSlotSnapshot; }
    public void setMemoSlotSnapshot(Integer memoSlotSnapshot) { this.memoSlotSnapshot = memoSlotSnapshot; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getExecutedAt() { return executedAt; }
    public void setExecutedAt(LocalDateTime executedAt) { this.executedAt = executedAt; }
    public LocalDateTime getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(LocalDateTime cancelledAt) { this.cancelledAt = cancelledAt; }
    public String getCancelledByAdminName() { return cancelledByAdminName; }
    public void setCancelledByAdminName(String cancelledByAdminName) { this.cancelledByAdminName = cancelledByAdminName; }
    public String getResultDetail() { return resultDetail; }
    public void setResultDetail(String resultDetail) { this.resultDetail = resultDetail; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
