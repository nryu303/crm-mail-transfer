package com.crm.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Log-injection guard: CR/LF/TAB must collapse to underscores so attackers can't forge log lines. */
class LogSafeTest {

    @Test
    void plainStringIsReturnedUnchanged() {
        assertThat(LogSafe.of("hello world")).isEqualTo("hello world");
    }

    @Test
    void nullStringBecomesLiteralNull() {
        assertThat(LogSafe.of((String) null)).isEqualTo("null");
    }

    @Test
    void nullObjectBecomesLiteralNull() {
        assertThat(LogSafe.of((Object) null)).isEqualTo("null");
    }

    @Test
    void crlfIsReplacedWithUnderscoreToPreventLogInjection() {
        // Attacker payload smuggling a second fake log line.
        String payload = "Hi\r\nWARN [admin] - User 999 logged in";
        assertThat(LogSafe.of(payload)).isEqualTo("Hi__WARN [admin] - User 999 logged in");
    }

    @Test
    void tabIsReplacedWithUnderscore() {
        assertThat(LogSafe.of("a\tb")).isEqualTo("a_b");
    }

    @Test
    void mixedControlChars_allReplaced() {
        assertThat(LogSafe.of("\r\n\t")).isEqualTo("___");
    }

    @Test
    void objectVariantStringifiesAndSanitises() {
        Object o = new Object() {
            @Override public String toString() { return "x\ny"; }
        };
        assertThat(LogSafe.of(o)).isEqualTo("x_y");
    }
}
