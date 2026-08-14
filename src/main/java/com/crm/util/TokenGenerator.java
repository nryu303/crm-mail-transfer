package com.crm.util;

import java.security.SecureRandom;

public final class TokenGenerator {

    private static final char[] ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    private static final SecureRandom RND = new SecureRandom();

    private TokenGenerator() {}

    /** 64-char URL-safe token for one-shot reply-page URLs. */
    public static String generateReplyToken() {
        return generate(64);
    }

    /** Default length for {@link #generateShortReplyToken()} — see that method's docs. */
    public static final int DEFAULT_SHORT_LENGTH = 10;
    public static final int MIN_SHORT_LENGTH = 4;
    public static final int MAX_SHORT_LENGTH = 20;

    /**
     * 10-char (default) token for SMS reply-page URLs. SMS is billed per ~65-char segment,
     * so the standard 64-char token (~86-char final URL) alone blows the whole budget. 62^10
     * is still far beyond any realistic brute-force risk for a link that's low-value and
     * expires (see ReplyPageService), so the shorter length is an acceptable trade-off.
     */
    public static String generateShortReplyToken() {
        return generate(DEFAULT_SHORT_LENGTH);
    }

    /** Same as {@link #generateShortReplyToken()} but with an operator-configurable length
     *  (see ExternalLinkDomain#shortTokenLength), clamped to [MIN_SHORT_LENGTH, MAX_SHORT_LENGTH]. */
    public static String generateShortReplyToken(int length) {
        int len = length;
        if (len < MIN_SHORT_LENGTH) len = MIN_SHORT_LENGTH;
        if (len > MAX_SHORT_LENGTH) len = MAX_SHORT_LENGTH;
        return generate(len);
    }

    private static String generate(int len) {
        char[] out = new char[len];
        for (int i = 0; i < len; i++) {
            out[i] = ALPHABET[RND.nextInt(ALPHABET.length)];
        }
        return new String(out);
    }
}
