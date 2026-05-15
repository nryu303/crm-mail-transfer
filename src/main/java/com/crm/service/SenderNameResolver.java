package com.crm.service;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.List;

/**
 * Resolve the From-header display name for one outbound send. Reads the sender-name policy
 * from {@link DomainSettingService} and returns the name to prepend to the From address.
 *
 * Filter-evasion options:
 *   • none   — return null, no display name (callers leave the bare address)
 *   • fixed  — always the same string
 *   • list   — random pick from up to 30 admin-supplied names
 *   • random — generate a random alphanumeric string of configured length and case
 */
@Service
public class SenderNameResolver {

    private static final char[] LOWER = "abcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final char[] UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    private static final char[] DIGITS = "0123456789".toCharArray();

    private final DomainSettingService settings;
    private final SecureRandom random = new SecureRandom();

    public SenderNameResolver(DomainSettingService settings) { this.settings = settings; }

    /** Returns the display name to use for this send, or null/empty if no name should be added. */
    public String resolve() {
        String mode = settings.getSenderNameMode();
        switch (mode) {
            case "fixed": {
                String f = settings.getSenderNameFixed();
                return (f == null || f.trim().isEmpty()) ? null : f.trim();
            }
            case "list": {
                List<String> list = settings.getSenderNameList();
                if (list.isEmpty()) return null;
                return list.get(random.nextInt(list.size()));
            }
            case "random": {
                int len = settings.getSenderNameRandomLength();
                String caseStyle = settings.getSenderNameRandomCase();
                return generate(len, caseStyle);
            }
            default:
                return null;
        }
    }

    private String generate(int len, String caseStyle) {
        char[] alphabet;
        if ("upper".equals(caseStyle)) {
            alphabet = concat(UPPER, DIGITS);
        } else if ("lower".equals(caseStyle)) {
            alphabet = concat(LOWER, DIGITS);
        } else {
            alphabet = concat(concat(LOWER, UPPER), DIGITS);
        }
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(alphabet[random.nextInt(alphabet.length)]);
        return sb.toString();
    }

    private static char[] concat(char[] a, char[] b) {
        char[] out = new char[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
