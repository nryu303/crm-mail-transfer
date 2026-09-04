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

/** A reusable "diff" template — activating it on a user means flipping their reply-page
 *  memo slot (CRM_USER.ACTIVE_MEMO_SLOT) to {@link #memoSlot}. Up to
 *  {@code DiffDefinitionService.MAX_DEFINITIONS} (10) may exist at once. */
@Entity
@Table(name = "DIFF_DEFINITION")
public class DiffDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "NAME", nullable = false, length = 100)
    private String name;

    /** Which CRM_USER memo slot (1..10) this diff activates on target users. */
    @Column(name = "MEMO_SLOT", nullable = false)
    private Integer memoSlot;

    @Column(name = "DISPLAY_ORDER")
    private Integer displayOrder;

    @Column(name = "CREATED_AT", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "UPDATED_AT", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (displayOrder == null) displayOrder = 0;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getMemoSlot() { return memoSlot; }
    public void setMemoSlot(Integer memoSlot) { this.memoSlot = memoSlot; }
    public Integer getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(Integer displayOrder) { this.displayOrder = displayOrder; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
