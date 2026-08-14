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
 * A registered external short-link domain (外部リンクドメイン) used to build per-message
 * tracked short URLs (%reply_url%) for email and SMS sends.
 *
 * Only one row may be 使用中 (active) at a time — {@link com.crm.service.ExternalLinkDomainService}
 * enforces this the same way {@link RelayServer} enforces a single active relay. When active,
 * {@link com.crm.service.DomainSettingService#buildReplyUrl(String)} uses this row's domain
 * instead of the legacy single reply.base_url setting.
 */
@Entity
@Table(name = "EXTERNAL_LINK_DOMAIN")
public class ExternalLinkDomain {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "DOMAIN_URL", nullable = false, unique = true, length = 255)
    private String domainUrl;

    @Column(name = "IS_ACTIVE")
    private Boolean isActive;

    @Column(name = "MEMO", columnDefinition = "TEXT")
    private String memo;

    /**
     * What happens after an access is logged for a click on this domain's /reply/{token}:
     *   REPLY_FORM   — existing behaviour, renders the normal two-way reply form (default).
     *   REDIRECT     — 302 redirect to {@link #redirectUrl} (e.g. https://www.yahoo.co.jp).
     *   CUSTOM_HTML  — serve {@link #landingHtml} directly, full-page (sandboxed, no CRM chrome).
     * Only meaningful when this domain actually has a real A record pointed at the CRM server —
     * a click can't be logged at all otherwise, regardless of this setting.
     */
    @Column(name = "LANDING_MODE", nullable = false, length = 32)
    private String landingMode = MODE_REPLY_FORM;

    @Column(name = "REDIRECT_URL", length = 500)
    private String redirectUrl;

    @Column(name = "LANDING_HTML", columnDefinition = "LONGTEXT")
    private String landingHtml;

    /**
     * Token length used when generating short SMS reply-URLs
     * ({@link com.crm.service.ReplyPageService#createShortReplyPageFor}) while this domain
     * is 使用中. SMS is billed per ~65-char segment, so operators may want a shorter token
     * than the 10-char default when the domain itself is already long. Range enforced at
     * [4, 20] — below 4 risks collisions/brute-force, above 20 defeats the point.
     */
    @Column(name = "SHORT_TOKEN_LENGTH")
    private Integer shortTokenLength;

    /**
     * Last known TLS cert-issuance result (PENDING / SUCCESS / FAILED_*), persisted here
     * once observed so the UI stops showing 発行待ち forever if the ephemeral /tmp result
     * file the root-side script writes gets cleaned up before anyone checks it (2026-08-05:
     * this is exactly what happened — a cert had genuinely succeeded, but the ONLY record of
     * that was a /tmp file that disappeared, so the settings page had nothing durable to read
     * and fell back to its "no result file = PENDING" default forever). NULL = never requested.
     */
    @Column(name = "CERT_STATUS", length = 32)
    private String certStatus;

    @Column(name = "CREATED_AT", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "UPDATED_AT", nullable = false)
    private LocalDateTime updatedAt;

    public static final String MODE_REPLY_FORM = "REPLY_FORM";
    public static final String MODE_REDIRECT = "REDIRECT";
    public static final String MODE_CUSTOM_HTML = "CUSTOM_HTML";

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (isActive == null) isActive = Boolean.FALSE;
        if (landingMode == null) landingMode = MODE_REPLY_FORM;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getDomainUrl() { return domainUrl; }
    public void setDomainUrl(String domainUrl) { this.domainUrl = domainUrl; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public String getMemo() { return memo; }
    public void setMemo(String memo) { this.memo = memo; }
    public String getLandingMode() { return landingMode; }
    public void setLandingMode(String landingMode) { this.landingMode = landingMode; }
    public String getRedirectUrl() { return redirectUrl; }
    public void setRedirectUrl(String redirectUrl) { this.redirectUrl = redirectUrl; }
    public String getLandingHtml() { return landingHtml; }
    public void setLandingHtml(String landingHtml) { this.landingHtml = landingHtml; }
    public Integer getShortTokenLength() { return shortTokenLength; }
    public void setShortTokenLength(Integer shortTokenLength) { this.shortTokenLength = shortTokenLength; }
    public String getCertStatus() { return certStatus; }
    public void setCertStatus(String certStatus) { this.certStatus = certStatus; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
