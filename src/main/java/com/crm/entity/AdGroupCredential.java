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
 * Per-group Basic Auth credentials for the agency dashboard.
 *
 * Every distinct {@code AD_CODE.NAME} (a "group") gets its own row with its own
 * {@code AUTH_USER} / {@code AUTH_PASSWORD}. The agency only sees their own group's
 * codes — handing out one group's URL+creds doesn't leak any other group's data.
 *
 * Auto-created on the first {@code AdCodeService.create()} for a new group name;
 * admins can rotate credentials from the group-detail page.
 */
@Entity
@Table(name = "AD_GROUP_CREDENTIAL")
public class AdGroupCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "GROUP_NAME", nullable = false, unique = true, length = 255)
    private String groupName;

    @Column(name = "AUTH_USER", nullable = false, length = 64)
    private String authUser;

    @Column(name = "AUTH_PASSWORD", nullable = false, length = 64)
    private String authPassword;

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
    void onUpdate() { updatedAt = LocalDateTime.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }
    public String getAuthUser() { return authUser; }
    public void setAuthUser(String authUser) { this.authUser = authUser; }
    public String getAuthPassword() { return authPassword; }
    public void setAuthPassword(String authPassword) { this.authPassword = authPassword; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
