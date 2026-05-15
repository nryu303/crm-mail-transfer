package com.crm.util;

/**
 * Sanitise a string before interpolating it into a log line. Strips CR/LF/TAB which
 * an attacker can use to forge log entries (CWE-117): a crafted Subject like
 * {@code "Hi\r\nWARN [admin] - User 999 logged in"} would otherwise show up as a
 * fake second log line in the journal.
 *
 * Use everywhere user-controlled values land in {@code log.info/warn/error}: email
 * subjects, addresses, headers, request paths, etc.
 */
public final class LogSafe {

    public static String of(String s) {
        if (s == null) return "null";
        return s.replaceAll("[\\r\\n\\t]", "_");
    }

    public static String of(Object o) {
        return o == null ? "null" : of(o.toString());
    }

    private LogSafe() {}
}
