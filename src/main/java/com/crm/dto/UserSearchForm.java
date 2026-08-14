package com.crm.dto;

public class UserSearchForm {
    /** Accepts newline- or comma-separated tokens; each token is substring-matched OR. */
    private String email;
    /** Same semantics as email. */
    private String displayName;
    /** Same semantics as email. */
    private String phoneNumber;
    private String status;
    /** Legacy URL param tolerated for backwards-compat with old bookmarks; ignored. */
    private String carrierCode;
    /**
     * Filters that accept multiple values from the user-list <select multiple> widgets.
     * Each list is the canonical store; the singular getter/setter pair below is a
     * backwards-compat alias used by older code and ?folder=X-style bookmarks. Empty
     * list = no filter.
     */
    private java.util.List<String> emailDomains = new java.util.ArrayList<>();
    private java.util.List<String> folders      = new java.util.ArrayList<>();
    private java.util.List<String> adCodes      = new java.util.ArrayList<>();
    private java.util.List<String> genders      = new java.util.ArrayList<>();

    // Period filters (all optional). Dates are interpreted in local tz at midnight/next-midnight.
    private String loginFrom;
    private String loginTo;
    /** When true, return only users whose last_login_at IS NULL (= 未ログイン). Mirrors
     *  the 「未」 checkbox next to 「ログイン期間」. Takes effect even when loginFrom/To
     *  are also set — narrows to users matching neither past login. */
    private boolean loginUnset;
    private String sendFrom;
    private String sendTo;
    private String paymentFrom;
    private String paymentTo;
    private String registerFrom;
    private String registerTo;

    /** Cumulative outbound-SENT count range filter (operator-supplied; either bound optional).
     *  Range semantics: count BETWEEN min..max inclusive. min==null treated as 0;
     *  max==null treated as unbounded. */
    private Integer sentCountMin;
    private Integer sentCountMax;
    /** Cumulative inbound-message count range filter; same semantics as sentCount*. */
    private Integer replyCountMin;
    private Integer replyCountMax;

    private int page = 0;
    private int size = 500;

    /** Parse email into tokens (split on \n, \r, comma). Empty list if blank. */
    public java.util.List<String> emailTokens() { return splitTokens(email); }
    public java.util.List<String> displayNameTokens() { return splitTokens(displayName); }
    public java.util.List<String> phoneNumberTokens() { return splitTokens(phoneNumber); }

    private static java.util.List<String> splitTokens(String raw) {
        if (raw == null || raw.trim().isEmpty()) return java.util.Collections.emptyList();
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String part : raw.split("[\\r\\n,]+")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getCarrierCode() { return carrierCode; }
    public void setCarrierCode(String carrierCode) { this.carrierCode = carrierCode; }

    public java.util.List<String> getEmailDomains() { return emailDomains; }
    public void setEmailDomains(java.util.List<String> emailDomains) {
        this.emailDomains = emailDomains == null ? new java.util.ArrayList<>() : trimmed(emailDomains);
    }
    public java.util.List<String> getFolders() { return folders; }
    public void setFolders(java.util.List<String> folders) {
        this.folders = folders == null ? new java.util.ArrayList<>() : trimmed(folders);
    }
    public java.util.List<String> getAdCodes() { return adCodes; }
    public void setAdCodes(java.util.List<String> adCodes) {
        this.adCodes = adCodes == null ? new java.util.ArrayList<>() : trimmed(adCodes);
    }
    public java.util.List<String> getGenders() { return genders; }
    public void setGenders(java.util.List<String> genders) {
        this.genders = genders == null ? new java.util.ArrayList<>() : trimmed(genders);
    }

    /**
     * Singular getters/setters — kept so old code (BroadcastController.selectFolderForBroadcast
     * sourceFolder alias, etc.) and bookmarked ?folder=X URLs continue to work. Reading
     * returns the first list element (or null when empty); writing replaces the entire list.
     */
    public String getEmailDomain() { return emailDomains.isEmpty() ? null : emailDomains.get(0); }
    public void setEmailDomain(String emailDomain) { this.emailDomains = singletonOrEmpty(emailDomain); }
    public String getFolder() { return folders.isEmpty() ? null : folders.get(0); }
    public void setFolder(String folder) { this.folders = singletonOrEmpty(folder); }
    public String getAdCode() { return adCodes.isEmpty() ? null : adCodes.get(0); }
    public void setAdCode(String adCode) { this.adCodes = singletonOrEmpty(adCode); }
    public String getGender() { return genders.isEmpty() ? null : genders.get(0); }
    public void setGender(String gender) { this.genders = singletonOrEmpty(gender); }

    private static java.util.List<String> trimmed(java.util.List<String> in) {
        java.util.List<String> out = new java.util.ArrayList<>(in.size());
        for (String s : in) {
            if (s == null) continue;
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
    private static java.util.List<String> singletonOrEmpty(String v) {
        if (v == null) return new java.util.ArrayList<>();
        String t = v.trim();
        if (t.isEmpty()) return new java.util.ArrayList<>();
        java.util.List<String> out = new java.util.ArrayList<>(1);
        out.add(t);
        return out;
    }

    public String getLoginFrom() { return loginFrom; }
    public void setLoginFrom(String loginFrom) { this.loginFrom = loginFrom; }
    public String getLoginTo() { return loginTo; }
    public void setLoginTo(String loginTo) { this.loginTo = loginTo; }
    public boolean isLoginUnset() { return loginUnset; }
    public void setLoginUnset(boolean loginUnset) { this.loginUnset = loginUnset; }

    public Integer getSentCountMin() { return sentCountMin; }
    public void setSentCountMin(Integer sentCountMin) { this.sentCountMin = sentCountMin; }
    public Integer getSentCountMax() { return sentCountMax; }
    public void setSentCountMax(Integer sentCountMax) { this.sentCountMax = sentCountMax; }
    public Integer getReplyCountMin() { return replyCountMin; }
    public void setReplyCountMin(Integer replyCountMin) { this.replyCountMin = replyCountMin; }
    public Integer getReplyCountMax() { return replyCountMax; }
    public void setReplyCountMax(Integer replyCountMax) { this.replyCountMax = replyCountMax; }

    public String getSendFrom() { return sendFrom; }
    public void setSendFrom(String sendFrom) { this.sendFrom = sendFrom; }
    public String getSendTo() { return sendTo; }
    public void setSendTo(String sendTo) { this.sendTo = sendTo; }

    public String getPaymentFrom() { return paymentFrom; }
    public void setPaymentFrom(String paymentFrom) { this.paymentFrom = paymentFrom; }
    public String getPaymentTo() { return paymentTo; }
    public void setPaymentTo(String paymentTo) { this.paymentTo = paymentTo; }

    public String getRegisterFrom() { return registerFrom; }
    public void setRegisterFrom(String registerFrom) { this.registerFrom = registerFrom; }
    public String getRegisterTo() { return registerTo; }
    public void setRegisterTo(String registerTo) { this.registerTo = registerTo; }

    public int getPage() { return page; }
    public void setPage(int page) { this.page = Math.max(page, 0); }

    public int getSize() { return size; }
    public void setSize(int size) { this.size = (size < 1 || size > 1000) ? 500 : size; }

    public boolean hasAnyFilter() {
        return hasText(email) || hasText(displayName) || hasText(status) || hasText(carrierCode)
                || !emailDomains.isEmpty() || !folders.isEmpty()
                || !adCodes.isEmpty() || !genders.isEmpty();
    }

    private static boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
