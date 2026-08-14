package com.crm.util;

/**
 * Conversion between the domestic format CRM_USER.PHONE_NUMBER is stored in
 * (e.g. "09093749952") and E.164 (e.g. "+819093749952"), which BytePlus SMS OpenAPI requires
 * for PhoneNumbers / an inbound MO webhook reports the sender as.
 */
public final class JapanesePhoneNumbers {

    /** Domestic ("0…") or already-E.164 → E.164 ("+81…"). Returns null for blank input. */
    public static String toE164(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("[^0-9+]", "");
        if (digits.isEmpty()) return null;
        if (digits.startsWith("+")) return digits;
        if (digits.startsWith("81")) return "+" + digits;
        if (digits.startsWith("0")) return "+81" + digits.substring(1);
        return "+81" + digits;
    }

    /** E.164 or bare-81 → domestic "0…" format matching how CRM_USER.PHONE_NUMBER is stored. */
    public static String toDomestic(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("[^0-9+]", "");
        if (digits.isEmpty()) return null;
        if (digits.startsWith("+81")) return "0" + digits.substring(3);
        if (digits.startsWith("81") && digits.length() >= 11) return "0" + digits.substring(2);
        if (digits.startsWith("+")) return digits.substring(1);
        return digits;
    }

    private JapanesePhoneNumbers() {}
}
