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

/** A daily auto-move rule: at MOVE_TIME (HH:mm, local time) every day, all users currently
 *  in SOURCE_FOLDER are moved to DEST_FOLDER. Multiple rules can be active simultaneously. */
@Entity
@Table(name = "FOLDER_AUTO_MOVE_RULE")
public class FolderAutoMoveRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** null = 未設定 (FOLDER IS NULL) as the source bucket. */
    @Column(name = "SOURCE_FOLDER", length = 64)
    private String sourceFolder;

    /** null = 未設定 (FOLDER IS NULL) as the destination bucket. */
    @Column(name = "DEST_FOLDER", length = 64)
    private String destFolder;

    @Column(name = "MOVE_TIME", nullable = false, length = 5)
    private String moveTime;

    @Column(name = "ENABLED", nullable = false)
    private Boolean enabled;

    /** Last time this rule actually executed — guards against re-firing within the same
     *  matching minute across multiple poll ticks. */
    @Column(name = "LAST_RUN_AT")
    private LocalDateTime lastRunAt;

    @Column(name = "CREATED_AT", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "UPDATED_AT", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (enabled == null) enabled = Boolean.TRUE;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSourceFolder() { return sourceFolder; }
    public void setSourceFolder(String sourceFolder) { this.sourceFolder = sourceFolder; }
    public String getDestFolder() { return destFolder; }
    public void setDestFolder(String destFolder) { this.destFolder = destFolder; }
    public String getMoveTime() { return moveTime; }
    public void setMoveTime(String moveTime) { this.moveTime = moveTime; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public LocalDateTime getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(LocalDateTime lastRunAt) { this.lastRunAt = lastRunAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
