package com.crm.util;

public final class CsvUtil {

    private static final String EMAIL_REGEX = "^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$";

    // Reason codes stored in CRM_USER.ADDRESS_INVALID_REASON. Kept short so the column stays
    // VARCHAR(64) and the values are easy to filter on in DB queries.
    public static final String REASON_TRAILING_DOT = "trailing_dot";
    public static final String REASON_LEADING_DOT  = "leading_dot";
    public static final String REASON_DOUBLE_DOT   = "double_dot";

    private CsvUtil() {}

    public static boolean isValidEmail(String email) {
        return email != null && email.matches(EMAIL_REGEX);
    }

    /**
     * Returns a short reason code if the local-part (everything before {@code @}) violates
     * RFC 5321 §4.1.2 in ways that the relay's SMTP client cannot send, or {@code null} if
     * the local-part is fine. We allow these rows into CRM (they are valid docomo mailboxes
     * historically) but flag them as not-sendable so broadcasts skip them and the operator
     * can see why.
     *
     * Detected cases:
     *   * {@link #REASON_LEADING_DOT}  — local-part starts with '.'  e.g. ".foo@docomo.ne.jp"
     *   * {@link #REASON_TRAILING_DOT} — local-part ends with '.'   e.g. "foo.@docomo.ne.jp"
     *   * {@link #REASON_DOUBLE_DOT}   — local-part contains ".."    e.g. "fo..bar@docomo.ne.jp"
     *
     * Returns null for null/empty/non-email input (those are caught by {@link #isValidEmail}).
     */
    public static String detectInvalidLocalPart(String email) {
        if (email == null) return null;
        int at = email.indexOf('@');
        if (at <= 0) return null;
        String local = email.substring(0, at);
        if (local.endsWith("."))   return REASON_TRAILING_DOT;
        if (local.startsWith(".")) return REASON_LEADING_DOT;
        if (local.contains(".."))  return REASON_DOUBLE_DOT;
        return null;
    }

    /** Japanese label used in error tables / tooltips for an {@link #detectInvalidLocalPart} code. */
    public static String invalidLocalPartLabel(String reason) {
        if (reason == null) return "";
        switch (reason) {
            case REASON_LEADING_DOT:  return "先頭にドット";
            case REASON_TRAILING_DOT: return "末尾にドット";
            case REASON_DOUBLE_DOT:   return "連続ドット";
            default: return reason;
        }
    }

    public static String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
