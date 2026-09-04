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
 * One instance of "apply DIFF_DEFINITION X to target Y at time Z". Doubles as both the
 * pending-schedule list (STATUS=PENDING) and the execution history (EXECUTED/CANCELLED/
 * FAILED) so the two views share one row per registration instead of duplicating data.
 *
 * <p>The target user-ID list is resolved and frozen at SET time ({@link #targetUserIds}) —
 * the same "recipient list frozen at schedule creation" semantics already used for scheduled
 * Broadcasts (see ScheduledTaskService.dispatchOne() javadoc) — so later membership changes
 * to a targeted folder don't retroactively change who this schedule applies to.
 */
@Entity
@Table(name = "DIFF_SCHEDULE")
public class DiffSchedule {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_EXECUTED = "EXECUTED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_FAILED = "FAILED";

    public static final String TARGET_PHONE = "PHONE";
    public static final String TARGET_EMAIL = "EMAIL";
    public static final String TARGET_FOLDER = "FOLDER";

    /** 当日(分後). */
    public static final String OFFSET_MINUTES = "MINUTES";
    /** 翌日以降(日数+時刻). */
    public static final String OFFSET_DAYS = "DAYS";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "DIFF_DEFINITION_ID", nullable = false)
    private Long diffDefinitionId;

    /** Copy of DIFF_DEFINITION.NAME at SET time — survives later rename/delete of the definition. */
    @Column(name = "DIFF_NAME_SNAPSHOT", nullable = false, length = 100)
    private String diffNameSnapshot;

    @Column(name = "MEMO_SLOT_SNAPSHOT", nullable = false)
    private Integer memoSlotSnapshot;

    @Column(name = "TARGET_TYPE", nullable = false, length = 16)
    private String targetType;

    /** Operator-entered raw target value: folder name, or a short display summary for the
     *  phone/email list (the actual resolved list lives in {@link #targetUserIds}). */
    @Column(name = "TARGET_VALUE", length = 500)
    private String targetValue;

    /** Comma-separated CRM_USER.ID snapshot resolved at SET time. */
    @Column(name = "TARGET_USER_IDS", columnDefinition = "LONGTEXT", nullable = false)
    private String targetUserIds;

    @Column(name = "OFFSET_MODE", nullable = false, length = 16)
    private String offsetMode;
    @Column(name = "OFFSET_MINUTES")
    private Integer offsetMinutes;
    @Column(name = "OFFSET_DAYS")
    private Integer offsetDays;
    /** "HH:mm", used when offsetMode=DAYS. */
    @Column(name = "OFFSET_CLOCK_TIME", length = 5)
    private String offsetClockTime;

    /** セット時刻 — the moment the schedule was registered; offsets are relative to this. */
    @Column(name = "SET_AT", nullable = false)
    private LocalDateTime setAt;
    /** Computed absolute fire datetime. */
    @Column(name = "SCHEDULED_FOR", nullable = false)
    private LocalDateTime scheduledFor;
    @Column(name = "EXECUTED_AT")
    private LocalDateTime executedAt;
    @Column(name = "CANCELLED_AT")
    private LocalDateTime cancelledAt;

    @Column(name = "STATUS", nullable = false, length = 16)
    private String status;
    @Column(name = "RESULT_DETAIL", columnDefinition = "TEXT")
    private String resultDetail;
    @Column(name = "SET_BY_ADMIN_NAME", length = 255)
    private String setByAdminName;
    @Column(name = "CANCELLED_BY_ADMIN_NAME", length = 255)
    private String cancelledByAdminName;

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
    public Long getDiffDefinitionId() { return diffDefinitionId; }
    public void setDiffDefinitionId(Long diffDefinitionId) { this.diffDefinitionId = diffDefinitionId; }
    public String getDiffNameSnapshot() { return diffNameSnapshot; }
    public void setDiffNameSnapshot(String diffNameSnapshot) { this.diffNameSnapshot = diffNameSnapshot; }
    public Integer getMemoSlotSnapshot() { return memoSlotSnapshot; }
    public void setMemoSlotSnapshot(Integer memoSlotSnapshot) { this.memoSlotSnapshot = memoSlotSnapshot; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public String getTargetValue() { return targetValue; }
    public void setTargetValue(String targetValue) { this.targetValue = targetValue; }
    public String getTargetUserIds() { return targetUserIds; }
    public void setTargetUserIds(String targetUserIds) { this.targetUserIds = targetUserIds; }
    public String getOffsetMode() { return offsetMode; }
    public void setOffsetMode(String offsetMode) { this.offsetMode = offsetMode; }
    public Integer getOffsetMinutes() { return offsetMinutes; }
    public void setOffsetMinutes(Integer offsetMinutes) { this.offsetMinutes = offsetMinutes; }
    public Integer getOffsetDays() { return offsetDays; }
    public void setOffsetDays(Integer offsetDays) { this.offsetDays = offsetDays; }
    public String getOffsetClockTime() { return offsetClockTime; }
    public void setOffsetClockTime(String offsetClockTime) { this.offsetClockTime = offsetClockTime; }
    public LocalDateTime getSetAt() { return setAt; }
    public void setSetAt(LocalDateTime setAt) { this.setAt = setAt; }
    public LocalDateTime getScheduledFor() { return scheduledFor; }
    public void setScheduledFor(LocalDateTime scheduledFor) { this.scheduledFor = scheduledFor; }
    public LocalDateTime getExecutedAt() { return executedAt; }
    public void setExecutedAt(LocalDateTime executedAt) { this.executedAt = executedAt; }
    public LocalDateTime getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(LocalDateTime cancelledAt) { this.cancelledAt = cancelledAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getResultDetail() { return resultDetail; }
    public void setResultDetail(String resultDetail) { this.resultDetail = resultDetail; }
    public String getSetByAdminName() { return setByAdminName; }
    public void setSetByAdminName(String setByAdminName) { this.setByAdminName = setByAdminName; }
    public String getCancelledByAdminName() { return cancelledByAdminName; }
    public void setCancelledByAdminName(String cancelledByAdminName) { this.cancelledByAdminName = cancelledByAdminName; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
