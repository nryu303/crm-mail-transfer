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
 * One "registration episode": DIFF_DEFINITION X applied to target Y, set at time Z. Holds
 * the frozen target-user-ID snapshot shared by every one of its {@link DiffScheduleStep}
 * children — resolved once at SET time (same "recipient list frozen at schedule creation"
 * semantics as scheduled Broadcasts), not re-resolved per step or at execution time.
 */
@Entity
@Table(name = "DIFF_SCHEDULE")
public class DiffSchedule {

    public static final String TARGET_PHONE = "PHONE";
    public static final String TARGET_EMAIL = "EMAIL";
    public static final String TARGET_FOLDER = "FOLDER";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "DIFF_DEFINITION_ID", nullable = false)
    private Long diffDefinitionId;

    /** Copy of DIFF_DEFINITION.NAME at SET time — survives later rename/delete. */
    @Column(name = "DIFF_NAME_SNAPSHOT", nullable = false, length = 100)
    private String diffNameSnapshot;

    @Column(name = "TARGET_TYPE", nullable = false, length = 16)
    private String targetType;

    /** Operator-entered raw target value: folder name, or a short display summary for the
     *  phone/email list (the actual resolved list lives in {@link #targetUserIds}). */
    @Column(name = "TARGET_VALUE", length = 500)
    private String targetValue;

    /** Comma-separated CRM_USER.ID snapshot resolved at SET time. */
    @Column(name = "TARGET_USER_IDS", columnDefinition = "LONGTEXT", nullable = false)
    private String targetUserIds;

    /** セット時刻 — every step's offset is computed relative to this. */
    @Column(name = "SET_AT", nullable = false)
    private LocalDateTime setAt;

    @Column(name = "SET_BY_ADMIN_ID")
    private Long setByAdminId;
    @Column(name = "SET_BY_ADMIN_NAME", length = 255)
    private String setByAdminName;

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
    public String getDiffNameSnapshot() { return diffNameSnapshot; }
    public void setDiffNameSnapshot(String diffNameSnapshot) { this.diffNameSnapshot = diffNameSnapshot; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public String getTargetValue() { return targetValue; }
    public void setTargetValue(String targetValue) { this.targetValue = targetValue; }
    public String getTargetUserIds() { return targetUserIds; }
    public void setTargetUserIds(String targetUserIds) { this.targetUserIds = targetUserIds; }
    public LocalDateTime getSetAt() { return setAt; }
    public void setSetAt(LocalDateTime setAt) { this.setAt = setAt; }
    public Long getSetByAdminId() { return setByAdminId; }
    public void setSetByAdminId(Long setByAdminId) { this.setByAdminId = setByAdminId; }
    public String getSetByAdminName() { return setByAdminName; }
    public void setSetByAdminName(String setByAdminName) { this.setByAdminName = setByAdminName; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
