package com.crm.entity;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.PrePersist;
import javax.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "CARRIER_ADDRESS_POOL")
public class CarrierAddressPool {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ADDRESS", nullable = false, unique = true)
    private String address;

    @Column(name = "CARRIER_CODE", nullable = false)
    private String carrierCode;

    @Column(name = "CARRIER_DOMAIN", nullable = false)
    private String carrierDomain;

    @Column(name = "SMTP_HOST", nullable = false)
    private String smtpHost;

    @Column(name = "SMTP_PORT")
    private Integer smtpPort;

    @Column(name = "SMTP_USERNAME", nullable = false)
    private String smtpUsername;

    /** AES-256 encrypted, base64-encoded. Never returned in plaintext to the UI. */
    @Column(name = "SMTP_PASSWORD", nullable = false)
    private String smtpPassword;

    @Column(name = "IS_ACTIVE")
    private Boolean isActive;

    @Column(name = "CREATED_AT", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (isActive == null) isActive = Boolean.TRUE;
        if (smtpPort == null) smtpPort = 587;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public String getCarrierCode() { return carrierCode; }
    public void setCarrierCode(String carrierCode) { this.carrierCode = carrierCode; }

    public String getCarrierDomain() { return carrierDomain; }
    public void setCarrierDomain(String carrierDomain) { this.carrierDomain = carrierDomain; }

    public String getSmtpHost() { return smtpHost; }
    public void setSmtpHost(String smtpHost) { this.smtpHost = smtpHost; }

    public Integer getSmtpPort() { return smtpPort; }
    public void setSmtpPort(Integer smtpPort) { this.smtpPort = smtpPort; }

    public String getSmtpUsername() { return smtpUsername; }
    public void setSmtpUsername(String smtpUsername) { this.smtpUsername = smtpUsername; }

    public String getSmtpPassword() { return smtpPassword; }
    public void setSmtpPassword(String smtpPassword) { this.smtpPassword = smtpPassword; }

    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
