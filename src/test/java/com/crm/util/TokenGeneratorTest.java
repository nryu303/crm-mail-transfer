package com.crm.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Reply-page tokens must be unguessable, fixed-width, and URL-safe. */
class TokenGeneratorTest {

    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9]+");

    @Test
    void replyToken_isExactly64Chars() {
        for (int i = 0; i < 30; i++) {
            assertThat(TokenGenerator.generateReplyToken()).hasSize(64);
        }
    }

    @Test
    void replyToken_isAlphaNumericOnly() {
        for (int i = 0; i < 30; i++) {
            String t = TokenGenerator.generateReplyToken();
            assertThat(ALLOWED.matcher(t).matches())
                    .as("token %s is purely alphanumeric", t)
                    .isTrue();
        }
    }

    @Test
    void replyTokens_areUniqueAcrossManyCalls() {
        // 64-char alphanumeric = 62^64 ≈ 2^380 bits — collision in 10k samples is astronomical.
        // This is a sanity-check on the entropy source, not a statistical proof.
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) seen.add(TokenGenerator.generateReplyToken());
        assertThat(seen).hasSize(10_000);
    }

    @Test
    void shortReplyToken_isExactly10Chars() {
        for (int i = 0; i < 30; i++) {
            assertThat(TokenGenerator.generateShortReplyToken()).hasSize(10);
        }
    }

    @Test
    void shortReplyToken_isAlphaNumericOnly() {
        for (int i = 0; i < 30; i++) {
            String t = TokenGenerator.generateShortReplyToken();
            assertThat(ALLOWED.matcher(t).matches())
                    .as("token %s is purely alphanumeric", t)
                    .isTrue();
        }
    }

    @Test
    void shortReplyTokens_areUniqueAcrossManyCalls() {
        // 10-char alphanumeric = 62^10 ≈ 8.4e17 — collision in 10k samples is negligible.
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) seen.add(TokenGenerator.generateShortReplyToken());
        assertThat(seen).hasSize(10_000);
    }

    @Test
    void shortReplyTokenWithLength_honoursRequestedLength() {
        assertThat(TokenGenerator.generateShortReplyToken(6)).hasSize(6);
        assertThat(TokenGenerator.generateShortReplyToken(15)).hasSize(15);
    }

    @Test
    void shortReplyTokenWithLength_clampsOutOfRangeValues() {
        assertThat(TokenGenerator.generateShortReplyToken(1)).hasSize(TokenGenerator.MIN_SHORT_LENGTH);
        assertThat(TokenGenerator.generateShortReplyToken(999)).hasSize(TokenGenerator.MAX_SHORT_LENGTH);
    }
}
