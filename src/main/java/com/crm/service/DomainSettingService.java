package com.crm.service;

import com.crm.entity.CrmSetting;
import com.crm.repository.CrmSettingRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;

/**
 * Central source of truth for outbound FROM domain and reply-URL domain generation.
 *
 * Settings live in CRM_SETTING (key-value) so admin can edit them at runtime.
 * Falls back to application.yml {@code app.reply-page.base-url} for the initial
 * reply base URL when nothing has been saved yet.
 *
 * Two generators:
 *   buildReplyUrl(token)  — http[s]://[random.]replyBase/reply/{token}
 *   buildFromAddress()    — [random@]fromBase
 *
 * "Random" parts use a SecureRandom source with a length configured per field
 * (bounded to [4, 64] for sanity). Admin can disable randomness to use a fixed
 * prefix (configurable via the FIXED keys).
 */
@Service
public class DomainSettingService {

    public static final String KEY_REPLY_BASE_URL            = "reply.base_url";
    public static final String KEY_REPLY_RANDOM_SUBDOMAIN    = "reply.random_subdomain_enabled";
    public static final String KEY_REPLY_RANDOM_LENGTH       = "reply.random_subdomain_length";
    public static final String KEY_REPLY_FIXED_SUBDOMAIN     = "reply.fixed_subdomain";
    public static final String KEY_FROM_BASE_DOMAIN          = "from.base_domain";
    public static final String KEY_FROM_RANDOM_LOCAL         = "from.random_local_enabled";
    public static final String KEY_FROM_RANDOM_LENGTH        = "from.random_local_length";
    public static final String KEY_FROM_FIXED_LOCAL          = "from.fixed_local";

    /** Auto-expire policy for CARRIER_USER_BINDING (deletes rows older than N days). */
    public static final String KEY_BINDING_EXPIRE_ENABLED    = "binding.auto_expire_enabled";
    public static final String KEY_BINDING_EXPIRE_DAYS       = "binding.auto_expire_days";

    /**
     * If true (default) outbound mail goes through the configured 転送機 (HTTP relay → SSH file
     * upload → AMG → SMTP). If false, the CRM bypasses the relay and connects directly to the
     * carrier-pool SMTP host using the pool entry's stored credentials.
     *
     * Toggle from the relay-server settings page. No restart required — the relay outbound
     * adapter checks this flag on every send.
     */
    public static final String KEY_OUTBOUND_USE_RELAY        = "outbound.use_relay";

    /**
     * Basic Auth credentials for the public agency dashboard at {@code /media/**}.
     * Both must be non-empty for the dashboard to be accessible — if either is blank,
     * the dashboard returns 503 (intentionally surfaced so the operator notices the
     * miss-config rather than serving an unauthenticated stats page).
     */
    public static final String KEY_MEDIA_AUTH_USER     = "media.auth_user";
    public static final String KEY_MEDIA_AUTH_PASSWORD = "media.auth_password";

    /**
     * Sender-name (RFC5322 display name) policy. Used by HttpRelayOutboundMailService to
     * prefix the From header. Three modes for filter-evasion:
     *   • {@code none}    — no display name, just bare address (default).
     *   • {@code fixed}   — use {@link #KEY_SENDER_NAME_FIXED} verbatim.
     *   • {@code list}    — pick one of up to 30 entries in {@link #KEY_SENDER_NAME_LIST}
     *                       (newline-separated). Random pick per send.
     *   • {@code random}  — generate a random alphanumeric string each send. Length and
     *                       case style controlled by KEY_SENDER_NAME_RANDOM_LEN / _CASE.
     */
    public static final String KEY_SENDER_NAME_MODE       = "sender_name.mode";          // none/fixed/list/random
    public static final String KEY_SENDER_NAME_FIXED      = "sender_name.fixed";          // single value
    public static final String KEY_SENDER_NAME_LIST       = "sender_name.list";           // newline-separated, max 30
    public static final String KEY_SENDER_NAME_RANDOM_LEN = "sender_name.random_length";  // int, 4-32
    public static final String KEY_SENDER_NAME_RANDOM_CASE = "sender_name.random_case";   // lower/upper/mixed

    /** Global default send rate (messages per minute) used when broadcast forms don't carry
     *  an explicit override. Range 1-600, default 60. */
    public static final String KEY_BROADCAST_RATE_PER_MIN = "broadcast.rate_per_min";

    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int MIN_LEN = 4;
    private static final int MAX_LEN = 64;
    private static final int DEFAULT_LEN = 16;

    private final CrmSettingRepository repository;
    private final SecureRandom random = new SecureRandom();
    private final String fallbackReplyBase;

    public DomainSettingService(CrmSettingRepository repository,
                                @Value("${app.reply-page.base-url:}") String fallbackReplyBase) {
        this.repository = repository;
        this.fallbackReplyBase = (fallbackReplyBase == null) ? "" : fallbackReplyBase.replaceAll("/+$", "");
    }

    public String getReplyBaseUrl() {
        String v = get(KEY_REPLY_BASE_URL);
        return (v == null || v.trim().isEmpty()) ? fallbackReplyBase : v.replaceAll("/+$", "");
    }

    public boolean isReplyRandomSubdomainEnabled() { return getBool(KEY_REPLY_RANDOM_SUBDOMAIN, true); }
    public int getReplyRandomLength()              { return getLen(KEY_REPLY_RANDOM_LENGTH, DEFAULT_LEN); }
    public String getReplyFixedSubdomain()         { return get(KEY_REPLY_FIXED_SUBDOMAIN); }

    public String getFromBaseDomain()              { return get(KEY_FROM_BASE_DOMAIN); }
    public boolean isFromRandomEnabled()           { return getBool(KEY_FROM_RANDOM_LOCAL, true); }
    public int getFromRandomLength()               { return getLen(KEY_FROM_RANDOM_LENGTH, DEFAULT_LEN); }
    public String getFromFixedLocal()              { return get(KEY_FROM_FIXED_LOCAL); }

    /** Use the relay (転送機) for outbound mail. Default true to preserve existing behaviour. */
    public boolean isOutboundUseRelay() { return getBool(KEY_OUTBOUND_USE_RELAY, true); }
    public void setOutboundUseRelay(boolean useRelay) { save(KEY_OUTBOUND_USE_RELAY, useRelay ? "true" : "false"); }

    public String getMediaAuthUser() { return get(KEY_MEDIA_AUTH_USER); }
    public String getMediaAuthPassword() { return get(KEY_MEDIA_AUTH_PASSWORD); }
    public void setMediaAuthCredentials(String user, String password) {
        save(KEY_MEDIA_AUTH_USER, user == null ? "" : user.trim());
        save(KEY_MEDIA_AUTH_PASSWORD, password == null ? "" : password);
    }

    /** Sender-name policy ("none"/"fixed"/"list"/"random"). Defaults to "none". */
    public String getSenderNameMode() {
        String v = get(KEY_SENDER_NAME_MODE);
        return (v == null || v.trim().isEmpty()) ? "none" : v.trim().toLowerCase();
    }
    public String getSenderNameFixed() { return get(KEY_SENDER_NAME_FIXED); }
    public java.util.List<String> getSenderNameList() {
        String raw = get(KEY_SENDER_NAME_LIST);
        if (raw == null) return java.util.Collections.emptyList();
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String line : raw.split("\\r?\\n")) {
            String t = line.trim();
            if (!t.isEmpty()) out.add(t);
        }
        // Cap at 30 to match the operator-facing form size.
        if (out.size() > 30) out = out.subList(0, 30);
        return out;
    }
    public int getSenderNameRandomLength() {
        String v = get(KEY_SENDER_NAME_RANDOM_LEN);
        if (v == null) return 8;
        try {
            int n = Integer.parseInt(v.trim());
            if (n < 4) return 4;
            if (n > 32) return 32;
            return n;
        } catch (NumberFormatException e) { return 8; }
    }
    public String getSenderNameRandomCase() {
        String v = get(KEY_SENDER_NAME_RANDOM_CASE);
        return (v == null || v.trim().isEmpty()) ? "mixed" : v.trim().toLowerCase();
    }

    /** Global send-rate (msgs/min). Range-clamped to [1, 600], default 60. */
    public int getBroadcastRatePerMinute() {
        String v = get(KEY_BROADCAST_RATE_PER_MIN);
        if (v == null || v.trim().isEmpty()) return 60;
        try {
            int n = Integer.parseInt(v.trim());
            if (n < 1) return 1;
            if (n > 600) return 600;
            return n;
        } catch (NumberFormatException e) { return 60; }
    }

    public void setBroadcastRatePerMinute(int n) {
        if (n < 1) n = 1;
        if (n > 600) n = 600;
        save(KEY_BROADCAST_RATE_PER_MIN, String.valueOf(n));
    }
    public void setSenderNamePolicy(String mode, String fixedValue, String listText,
                                     int randomLength, String randomCase) {
        save(KEY_SENDER_NAME_MODE, mode == null ? "none" : mode.trim().toLowerCase());
        save(KEY_SENDER_NAME_FIXED, fixedValue == null ? "" : fixedValue);
        save(KEY_SENDER_NAME_LIST, listText == null ? "" : listText);
        save(KEY_SENDER_NAME_RANDOM_LEN, String.valueOf(randomLength));
        save(KEY_SENDER_NAME_RANDOM_CASE, randomCase == null ? "mixed" : randomCase);
    }

    /** Auto-expire is OFF by default. Admin must explicitly enable it on the settings page. */
    public boolean isBindingExpireEnabled() { return getBool(KEY_BINDING_EXPIRE_ENABLED, false); }
    public int getBindingExpireDays() {
        String v = get(KEY_BINDING_EXPIRE_DAYS);
        if (v == null) return 60;
        try {
            int n = Integer.parseInt(v.trim());
            if (n < 1) return 1;
            if (n > 365) return 365;
            return n;
        } catch (NumberFormatException e) { return 60; }
    }

    /** Build a reply URL for a given token using current settings. */
    public String buildReplyUrl(String token) {
        String base = getReplyBaseUrl();
        if (base.isEmpty()) return "/reply/" + token;
        // Inject subdomain between scheme and host if enabled
        String subdomain = resolveReplySubdomain();
        if (subdomain != null && !subdomain.isEmpty()) {
            int schemeEnd = base.indexOf("://");
            if (schemeEnd > 0) {
                String scheme = base.substring(0, schemeEnd);
                String hostAndRest = base.substring(schemeEnd + 3);
                return scheme + "://" + subdomain + "." + hostAndRest + "/reply/" + token;
            }
            return subdomain + "." + base + "/reply/" + token;
        }
        return base + "/reply/" + token;
    }

    /** Build a FROM address using current settings. Returns null if base domain unset. */
    public String buildFromAddress() {
        String domain = getFromBaseDomain();
        if (domain == null || domain.trim().isEmpty()) return null;
        String local = resolveFromLocal();
        return local + "@" + domain.trim();
    }

    private String resolveReplySubdomain() {
        if (isReplyRandomSubdomainEnabled()) return randomString(getReplyRandomLength());
        String fixed = getReplyFixedSubdomain();
        return (fixed == null) ? "" : fixed.trim();
    }

    private String resolveFromLocal() {
        if (isFromRandomEnabled()) return randomString(getFromRandomLength());
        String fixed = getFromFixedLocal();
        return (fixed == null || fixed.trim().isEmpty()) ? "info" : fixed.trim();
    }

    private String randomString(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    private String get(String key) {
        return repository.findBySettingKey(key).map(CrmSetting::getSettingValue).orElse(null);
    }

    private boolean getBool(String key, boolean def) {
        String v = get(key);
        return v == null ? def : "true".equalsIgnoreCase(v.trim()) || "1".equals(v.trim());
    }

    private int getLen(String key, int def) {
        String v = get(key);
        if (v == null) return def;
        try {
            int n = Integer.parseInt(v.trim());
            if (n < MIN_LEN) return MIN_LEN;
            if (n > MAX_LEN) return MAX_LEN;
            return n;
        } catch (NumberFormatException e) { return def; }
    }

    @Transactional
    public void save(String key, String value) {
        CrmSetting s = repository.findBySettingKey(key).orElseGet(() -> {
            CrmSetting ns = new CrmSetting();
            ns.setSettingKey(key);
            return ns;
        });
        s.setSettingValue(value);
        repository.save(s);
    }
}
