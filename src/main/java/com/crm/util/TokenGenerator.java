package com.crm.util;

import java.security.SecureRandom;

public final class TokenGenerator {

    private static final char[] ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    private static final SecureRandom RND = new SecureRandom();

    private TokenGenerator() {}

    /** 64-char URL-safe token for one-shot reply-page URLs. */
    public static String generateReplyToken() {
        char[] out = new char[64];
        for (int i = 0; i < 64; i++) {
            out[i] = ALPHABET[RND.nextInt(ALPHABET.length)];
        }
        return new String(out);
    }
}
