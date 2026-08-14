package com.crm.entity;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.time.LocalDateTime;

/**
 * One row per end-user click on a tracked link (reply page / external short URL / inbound
 * reply). Gives the operator a visible history on the user detail screen ("アクセスログ") —
 * unlike {@link CrmUser#getLastLoginAt()}, which is a single overwritten timestamp, this
 * preserves every hit so an operator can confirm "the user did click, here's when."
 */
@Entity
@Table(name = "USER_ACCESS_LOG")
public class UserAccessLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "USER_ID", nullable = false)
    private Long userId;

    /** e.g. REPLY_VIEW / REPLY_SUBMIT / INBOUND_MAIL */
    @Column(name = "SOURCE", nullable = false, length = 32)
    private String source;

    @Column(name = "IP_ADDRESS", length = 64)
    private String ipAddress;

    @Column(name = "USER_AGENT", length = 500)
    private String userAgent;

    /**
     * Host header the click actually arrived on (e.g. "ii5gh9ge.jp"). Lets the operator see
     * which registered 外部リンクドメイン a given access came through, since only one domain
     * is 使用中 at a time but old links using a previously-active domain may still be clicked.
     * Null if the request had no Host header (shouldn't normally happen) or predates this field.
     */
    @Column(name = "DOMAIN_HOST", length = 255)
    private String domainHost;

    @Column(name = "CREATED_AT", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public String getDomainHost() { return domainHost; }
    public void setDomainHost(String domainHost) { this.domainHost = domainHost; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public static final String SOURCE_REPLY_VIEW = "REPLY_VIEW";
    public static final String SOURCE_REPLY_SUBMIT = "REPLY_SUBMIT";
    public static final String SOURCE_INBOUND_MAIL = "INBOUND_MAIL";
}
