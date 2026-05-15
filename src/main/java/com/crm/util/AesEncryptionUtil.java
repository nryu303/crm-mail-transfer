package com.crm.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256/GCM encryption for sensitive strings (e.g. SMTP passwords in the carrier pool).
 * Key is derived as SHA-256(configured passphrase) so the yml value may be any length.
 * Output format: base64( IV(12) || ciphertext || GCM_tag(16) ).
 */
@Component
public class AesEncryptionUtil {

    private static final String ALG = "AES";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    /** Reject these on startup so production never silently runs with a known dev key. */
    private static final java.util.Set<String> KNOWN_DEV_KEYS = java.util.Collections.unmodifiableSet(
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "dev-insecure-32byte-key-replace!",
                    "prod-please-override-this-in-env-file",
                    "changeme",
                    ""
            )));

    public AesEncryptionUtil(@Value("${app.aes-key}") String passphrase) {
        if (passphrase == null || passphrase.trim().isEmpty()) {
            throw new IllegalStateException(
                    "AES_ENCRYPTION_KEY is not set. Configure a 32-byte secret via the AES_ENCRYPTION_KEY env var.");
        }
        if (KNOWN_DEV_KEYS.contains(passphrase)) {
            throw new IllegalStateException(
                    "AES_ENCRYPTION_KEY is using a known development/placeholder value. "
                  + "Set a real secret via the AES_ENCRYPTION_KEY env var (32+ bytes recommended).");
        }
        if (passphrase.length() < 16) {
            throw new IllegalStateException(
                    "AES_ENCRYPTION_KEY is too short (" + passphrase.length() + " chars). "
                  + "Use at least 16 characters; 32+ recommended.");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(passphrase.getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(digest, ALG);
        } catch (Exception e) {
            throw new IllegalStateException("failed to derive AES key", e);
        }
    }

    public String encrypt(String plaintext) {
        if (plaintext == null) return null;
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buf = ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct);
            return Base64.getEncoder().encodeToString(buf.array());
        } catch (Exception e) {
            throw new IllegalStateException("AES encryption failed", e);
        }
    }

    public String decrypt(String encoded) {
        if (encoded == null) return null;
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            byte[] iv = new byte[IV_BYTES];
            byte[] ct = new byte[bytes.length - IV_BYTES];
            System.arraycopy(bytes, 0, iv, 0, IV_BYTES);
            System.arraycopy(bytes, IV_BYTES, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("AES decryption failed", e);
        }
    }
}
