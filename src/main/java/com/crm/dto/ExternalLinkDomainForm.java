package com.crm.dto;

import com.crm.entity.ExternalLinkDomain;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

public class ExternalLinkDomainForm {

    @NotBlank(message = "ドメイン (http:// または https://) を入力してください")
    @Size(max = 255)
    private String domainUrl;

    private Boolean isActive = Boolean.FALSE;

    private String memo;

    /** REPLY_FORM (default) / REDIRECT / CUSTOM_HTML */
    private String landingMode = ExternalLinkDomain.MODE_REPLY_FORM;

    @Size(max = 500)
    private String redirectUrl;

    private String landingHtml;

    private Integer shortTokenLength;

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

    public static ExternalLinkDomainForm from(ExternalLinkDomain d) {
        ExternalLinkDomainForm f = new ExternalLinkDomainForm();
        f.domainUrl = d.getDomainUrl();
        f.isActive = d.getIsActive();
        f.memo = d.getMemo();
        f.landingMode = d.getLandingMode();
        f.redirectUrl = d.getRedirectUrl();
        f.landingHtml = d.getLandingHtml();
        f.shortTokenLength = d.getShortTokenLength();
        return f;
    }

    public void applyTo(ExternalLinkDomain d) {
        d.setDomainUrl(domainUrl == null ? null : domainUrl.trim().replaceAll("/+$", ""));
        d.setIsActive(isActive == null ? Boolean.FALSE : isActive);
        d.setMemo(memo);
        d.setLandingMode(normalizeMode(landingMode));
        d.setRedirectUrl(redirectUrl == null ? null : redirectUrl.trim());
        d.setLandingHtml(landingHtml);
        d.setShortTokenLength(normalizeShortTokenLength(shortTokenLength));
    }

    private static Integer normalizeShortTokenLength(Integer raw) {
        if (raw == null) return null;
        int n = raw;
        if (n < com.crm.util.TokenGenerator.MIN_SHORT_LENGTH) n = com.crm.util.TokenGenerator.MIN_SHORT_LENGTH;
        if (n > com.crm.util.TokenGenerator.MAX_SHORT_LENGTH) n = com.crm.util.TokenGenerator.MAX_SHORT_LENGTH;
        return n;
    }

    private static String normalizeMode(String raw) {
        if (ExternalLinkDomain.MODE_REDIRECT.equals(raw)) return ExternalLinkDomain.MODE_REDIRECT;
        if (ExternalLinkDomain.MODE_CUSTOM_HTML.equals(raw)) return ExternalLinkDomain.MODE_CUSTOM_HTML;
        return ExternalLinkDomain.MODE_REPLY_FORM;
    }
}
