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
@Table(name = "CRM_USER")
public class CrmUser {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_SUSPENDED = "SUSPENDED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "EMAIL", nullable = false, unique = true)
    private String email;

    /**
     * Set when this user's email has an RFC-invalid local-part that the relay's SMTP client
     * will reject (trailing/leading dot, consecutive dots). See {@link com.crm.util.CsvUtil}
     * for the reason codes. Broadcasts skip users where this is non-null; the user list and
     * detail page show a 「送信不可」 badge so the operator can spot them.
     */
    @Column(name = "ADDRESS_INVALID_REASON", length = 64)
    private String addressInvalidReason;

    @Column(name = "DISPLAY_NAME")
    private String displayName;

    @Column(name = "LOGIN_ID")
    private String loginId;

    @Column(name = "LOGIN_PASSWORD")
    private String loginPassword;

    @Column(name = "CARRIER_DOMAIN")
    private String carrierDomain;

    @Column(name = "STATUS")
    private String status;

    /** Advertising / agency tag — the {@code AD_CODE.code} that referred this user. */
    @Column(name = "AD_CODE", length = 64)
    private String adCode;

    /** "M" / "F" / null — drives the 男性/女性 split on the agency dashboard. */
    @Column(name = "GENDER", length = 8)
    private String gender;

    /** User-assigned grouping folder (free-form; choices are managed via settings). */
    @Column(name = "FOLDER", length = 64)
    private String folder;

    @Column(name = "LAST_LOGIN_AT")
    private LocalDateTime lastLoginAt;

    /** Per-user reply-page HTML (rendered as HTML on the public reply page).
     *  LONGTEXT (4GB) so operators can paste full landing-page HTML with
     *  base64-embedded images — TEXT (64KB) was being exceeded. */
    @Column(name = "MEMO", columnDefinition = "LONGTEXT")
    private String memo;

    /** Admin-only internal memo (never shown to the user). */
    @Column(name = "INTERNAL_MEMO", columnDefinition = "LONGTEXT")
    private String internalMemo;

    @Column(name = "TAG1_KEY")   private String tag1Key;
    @Column(name = "TAG1_VALUE") private String tag1Value;
    @Column(name = "TAG2_KEY")   private String tag2Key;
    @Column(name = "TAG2_VALUE") private String tag2Value;
    @Column(name = "TAG3_KEY")   private String tag3Key;
    @Column(name = "TAG3_VALUE") private String tag3Value;
    @Column(name = "TAG4_KEY")   private String tag4Key;
    @Column(name = "TAG4_VALUE") private String tag4Value;
    @Column(name = "TAG5_KEY")   private String tag5Key;
    @Column(name = "TAG5_VALUE") private String tag5Value;

    @Column(name = "LAST_PAYMENT_AT")
    private LocalDateTime lastPaymentAt;

    @Column(name = "CREATED_AT", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "UPDATED_AT", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (status == null) status = STATUS_ACTIVE;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getAddressInvalidReason() { return addressInvalidReason; }
    public void setAddressInvalidReason(String addressInvalidReason) { this.addressInvalidReason = addressInvalidReason; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getLoginId() { return loginId; }
    public void setLoginId(String loginId) { this.loginId = loginId; }

    public String getLoginPassword() { return loginPassword; }
    public void setLoginPassword(String loginPassword) { this.loginPassword = loginPassword; }

    public String getCarrierDomain() { return carrierDomain; }
    public void setCarrierDomain(String carrierDomain) { this.carrierDomain = carrierDomain; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getFolder() { return folder; }
    public void setFolder(String folder) { this.folder = folder; }
    public String getAdCode() { return adCode; }
    public void setAdCode(String adCode) { this.adCode = adCode; }
    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }

    public LocalDateTime getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(LocalDateTime lastLoginAt) { this.lastLoginAt = lastLoginAt; }

    public String getMemo() { return memo; }
    public void setMemo(String memo) { this.memo = memo; }

    public String getInternalMemo() { return internalMemo; }
    public void setInternalMemo(String internalMemo) { this.internalMemo = internalMemo; }

    public String getTag1Key() { return tag1Key; }
    public void setTag1Key(String v) { this.tag1Key = v; }
    public String getTag1Value() { return tag1Value; }
    public void setTag1Value(String v) { this.tag1Value = v; }

    public String getTag2Key() { return tag2Key; }
    public void setTag2Key(String v) { this.tag2Key = v; }
    public String getTag2Value() { return tag2Value; }
    public void setTag2Value(String v) { this.tag2Value = v; }

    public String getTag3Key() { return tag3Key; }
    public void setTag3Key(String v) { this.tag3Key = v; }
    public String getTag3Value() { return tag3Value; }
    public void setTag3Value(String v) { this.tag3Value = v; }

    public String getTag4Key() { return tag4Key; }
    public void setTag4Key(String v) { this.tag4Key = v; }
    public String getTag4Value() { return tag4Value; }
    public void setTag4Value(String v) { this.tag4Value = v; }

    public String getTag5Key() { return tag5Key; }
    public void setTag5Key(String v) { this.tag5Key = v; }
    public String getTag5Value() { return tag5Value; }
    public void setTag5Value(String v) { this.tag5Value = v; }

    public LocalDateTime getLastPaymentAt() { return lastPaymentAt; }
    public void setLastPaymentAt(LocalDateTime v) { this.lastPaymentAt = v; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
