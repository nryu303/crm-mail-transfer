package com.crm.dto;

public class UserSearchForm {
    /** Accepts newline- or comma-separated tokens; each token is substring-matched OR. */
    private String email;
    /** Same semantics as email. */
    private String displayName;
    private String status;
    /** Legacy URL param tolerated for backwards-compat with old bookmarks; ignored. */
    private String carrierCode;
    /** Match emails whose part after '@' equals this value. */
    private String emailDomain;
    /** Match exact folder name (or "__NONE__" for users without a folder). */
    private String folder;
    /** Match exact ad_code (or "__NONE__" for users with no ad_code). */
    private String adCode;
    /** "M" / "F" / "__NONE__" / "" (any). */
    private String gender;

    // Period filters (all optional). Dates are interpreted in local tz at midnight/next-midnight.
    private String loginFrom;
    private String loginTo;
    private String sendFrom;
    private String sendTo;
    private String paymentFrom;
    private String paymentTo;
    private String registerFrom;
    private String registerTo;

    private int page = 0;
    private int size = 100;

    /** Parse email into tokens (split on \n, \r, comma). Empty list if blank. */
    public java.util.List<String> emailTokens() { return splitTokens(email); }
    public java.util.List<String> displayNameTokens() { return splitTokens(displayName); }

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

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getCarrierCode() { return carrierCode; }
    public void setCarrierCode(String carrierCode) { this.carrierCode = carrierCode; }

    public String getEmailDomain() { return emailDomain; }
    public void setEmailDomain(String emailDomain) { this.emailDomain = emailDomain; }

    public String getFolder() { return folder; }
    public void setFolder(String folder) { this.folder = folder; }
    public String getAdCode() { return adCode; }
    public void setAdCode(String adCode) { this.adCode = adCode; }
    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }

    public String getLoginFrom() { return loginFrom; }
    public void setLoginFrom(String loginFrom) { this.loginFrom = loginFrom; }
    public String getLoginTo() { return loginTo; }
    public void setLoginTo(String loginTo) { this.loginTo = loginTo; }

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
    public void setSize(int size) { this.size = (size < 1 || size > 200) ? 100 : size; }

    public boolean hasAnyFilter() {
        return hasText(email) || hasText(displayName) || hasText(status) || hasText(carrierCode)
                || hasText(emailDomain) || hasText(folder);
    }

    private static boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
