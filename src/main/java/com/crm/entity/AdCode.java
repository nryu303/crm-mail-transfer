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
 * Advertising / agency code. Each row is one campaign or partner that drives signups.
 *
 * Two-segment URL pattern for the external dashboard:
 *   /media/index/{code}/{access_token}
 *
 * Both segments are required so a leaked code alone can't be used to view stats —
 * the access_token acts as a per-row password.
 *
 * Each CRM_USER may carry an ad_code value matching {@link #code}; aggregate stats
 * (signups, payments) are computed by joining on that.
 */
@Entity
@Table(name = "AD_CODE")
public class AdCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "CODE", nullable = false, unique = true, length = 64)
    private String code;

    @Column(name = "ACCESS_TOKEN", nullable = false, length = 64)
    private String accessToken;

    @Column(name = "NAME", nullable = false, length = 255)
    private String name;

    @Column(name = "MEMO", columnDefinition = "TEXT")
    private String memo;

    @Column(name = "IS_ACTIVE")
    private Boolean isActive;

    @Column(name = "CREATED_AT", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "UPDATED_AT", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (isActive == null) isActive = Boolean.TRUE;
    }

    @PreUpdate
    void onUpdate() { updatedAt = LocalDateTime.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getMemo() { return memo; }
    public void setMemo(String memo) { this.memo = memo; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
