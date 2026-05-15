package com.crm.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Confidentiality + tamper-evidence checks for the AES-GCM helper.
 * Production stores SMTP/account passwords with this — corruption of the round-trip
 * (or weakening of the IV/tag handling) would be a security incident.
 */
class AesEncryptionUtilTest {

    private static final String GOOD_KEY = "test-secret-must-be-long-enough-to-pass-min";

    @Test
    void roundTrip_plainAscii() {
        AesEncryptionUtil a = new AesEncryptionUtil(GOOD_KEY);
        String enc = a.encrypt("hello world");
        assertThat(enc).isNotEqualTo("hello world");
        assertThat(a.decrypt(enc)).isEqualTo("hello world");
    }

    @Test
    void roundTrip_japaneseMultibyte() {
        AesEncryptionUtil a = new AesEncryptionUtil(GOOD_KEY);
        String enc = a.encrypt("いつもご利用ありがとうございます。");
        assertThat(a.decrypt(enc)).isEqualTo("いつもご利用ありがとうございます。");
    }

    @Test
    void encryptIsNonDeterministic_uniqueIvPerCall() {
        // GCM with a random IV must produce different ciphertext for identical plaintext.
        // If this regresses (deterministic IV), the scheme becomes brittle under chosen-plaintext.
        AesEncryptionUtil a = new AesEncryptionUtil(GOOD_KEY);
        String e1 = a.encrypt("same input");
        String e2 = a.encrypt("same input");
        assertThat(e1).isNotEqualTo(e2);
        assertThat(a.decrypt(e1)).isEqualTo(a.decrypt(e2));
    }

    @Test
    void rejectsKnownDevKey() {
        assertThatThrownBy(() -> new AesEncryptionUtil("changeme"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AesEncryptionUtil("dev-insecure-32byte-key-replace!"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsBlankKey() {
        assertThatThrownBy(() -> new AesEncryptionUtil(""))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AesEncryptionUtil("   "))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsTooShortKey() {
        assertThatThrownBy(() -> new AesEncryptionUtil("short"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tamperedCiphertext_failsToDecrypt() {
        // GCM authentication tag means flipping any byte in the ciphertext makes decrypt throw.
        AesEncryptionUtil a = new AesEncryptionUtil(GOOD_KEY);
        String enc = a.encrypt("secret");
        // Flip one base64 char near the middle to corrupt the ciphertext.
        char[] chars = enc.toCharArray();
        chars[chars.length / 2] = chars[chars.length / 2] == 'A' ? 'B' : 'A';
        String tampered = new String(chars);
        assertThatThrownBy(() -> a.decrypt(tampered)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void decrypt_null_returnsNull() {
        // Convention: NULL ciphertext columns (unset password) pass through without throwing.
        AesEncryptionUtil a = new AesEncryptionUtil(GOOD_KEY);
        assertThat(a.decrypt(null)).isNull();
    }

    @Test
    void encrypt_null_returnsNull() {
        AesEncryptionUtil a = new AesEncryptionUtil(GOOD_KEY);
        assertThat(a.encrypt(null)).isNull();
    }
}
