package com.crm.util;

import java.security.SecureRandom;

public final class CredentialGenerator {

    // base58-style alphabet — no I l 0 O 1 to avoid on-phone input confusion
    private static final char[] ALPHABET =
            "23456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz".toCharArray();

    private static final SecureRandom RND = new SecureRandom();

    private CredentialGenerator() {}

    /** 8 chars, ~ 1.4e14 combinations. Uniqueness must still be checked against DB. */
    public static String generateLoginId() {
        return randomString(8);
    }

    /** 12 chars, ~ 5.6e20 combinations. */
    public static String generatePassword() {
        return randomString(12);
    }

    private static String randomString(int length) {
        char[] out = new char[length];
        for (int i = 0; i < length; i++) {
            out[i] = ALPHABET[RND.nextInt(ALPHABET.length)];
        }
        return new String(out);
    }
}
