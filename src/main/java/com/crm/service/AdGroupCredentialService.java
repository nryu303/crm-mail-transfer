package com.crm.service;

import com.crm.entity.AdGroupCredential;
import com.crm.repository.AdGroupCredentialRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Optional;

/**
 * Per-group credentials store for the agency dashboard. One row per AD_CODE.name
 * (= one group). Auto-created when the first ad-code in a new group is registered.
 *
 * Username and password are URL-safe random strings; the admin can regenerate them
 * from the group-detail page (which invalidates the previous URL/credentials).
 */
@Service
public class AdGroupCredentialService {

    private static final char[] URL_ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();
    private static final int USER_LEN = 12;
    private static final int PASS_LEN = 16;
    private static final int MAX_GENERATE_ATTEMPTS = 8;

    private final AdGroupCredentialRepository repository;
    private final SecureRandom random = new SecureRandom();

    public AdGroupCredentialService(AdGroupCredentialRepository repository) {
        this.repository = repository;
    }

    public Optional<AdGroupCredential> findByGroupName(String groupName) {
        return repository.findByGroupName(groupName);
    }

    public Optional<AdGroupCredential> findByAuthUser(String authUser) {
        return repository.findByAuthUser(authUser);
    }

    /**
     * Get-or-create credentials for the given group. Idempotent — calling twice for the
     * same name returns the existing credentials.
     */
    @Transactional
    public AdGroupCredential ensureFor(String groupName) {
        String key = groupName == null ? "" : groupName.trim();
        return repository.findByGroupName(key).orElseGet(() -> {
            AdGroupCredential c = new AdGroupCredential();
            c.setGroupName(key);
            c.setAuthUser(generateUniqueUser());
            c.setAuthPassword(randomString(PASS_LEN));
            return repository.save(c);
        });
    }

    /** Regenerate (rotate) both user and password for a group. */
    @Transactional
    public Optional<AdGroupCredential> rotate(String groupName) {
        return repository.findByGroupName(groupName).map(c -> {
            c.setAuthUser(generateUniqueUser());
            c.setAuthPassword(randomString(PASS_LEN));
            return repository.save(c);
        });
    }

    /** Delete credentials for a group (used when no codes remain in that group). */
    @Transactional
    public void deleteByGroupName(String groupName) {
        repository.findByGroupName(groupName).ifPresent(repository::delete);
    }

    private String generateUniqueUser() {
        for (int i = 0; i < MAX_GENERATE_ATTEMPTS; i++) {
            String candidate = randomString(USER_LEN);
            if (!repository.existsByAuthUser(candidate)) return candidate;
        }
        throw new IllegalStateException("could not generate unique auth_user");
    }

    private String randomString(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(URL_ALPHABET[random.nextInt(URL_ALPHABET.length)]);
        return sb.toString();
    }
}
