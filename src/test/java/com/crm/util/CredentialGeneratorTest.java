package com.crm.util;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Per-agency login id / password generator — must avoid input-confusable glyphs. */
class CredentialGeneratorTest {

    /** Confusable on phones / handwritten notes: I, l, 0, O, 1. */
    private static final Pattern CONFUSABLES = Pattern.compile("[Il0O1]");

    @Test
    void loginIdIsEightChars() {
        assertThat(CredentialGenerator.generateLoginId()).hasSize(8);
    }

    @Test
    void passwordIsTwelveChars() {
        assertThat(CredentialGenerator.generatePassword()).hasSize(12);
    }

    @Test
    void loginIdAvoidsConfusableGlyphs() {
        for (int i = 0; i < 200; i++) {
            String s = CredentialGenerator.generateLoginId();
            assertThat(CONFUSABLES.matcher(s).find())
                    .as("login id %s contains a confusable glyph", s)
                    .isFalse();
        }
    }

    @Test
    void passwordAvoidsConfusableGlyphs() {
        for (int i = 0; i < 200; i++) {
            String s = CredentialGenerator.generatePassword();
            assertThat(CONFUSABLES.matcher(s).find())
                    .as("password %s contains a confusable glyph", s)
                    .isFalse();
        }
    }

    @Test
    void passwordsAreUniqueAcrossManyCalls() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 5_000; i++) seen.add(CredentialGenerator.generatePassword());
        assertThat(seen).hasSize(5_000);
    }
}
