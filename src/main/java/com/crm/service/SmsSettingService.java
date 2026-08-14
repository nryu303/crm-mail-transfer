package com.crm.service;

import com.crm.entity.CrmSetting;
import com.crm.repository.CrmSettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * BytePlus SMS OpenAPI settings. The API uses Basic Auth only (Username = "Message Group ID",
 * Password = "OpenAPI password") — no AccessKeyID/SecretAccessKey. Settings live in CRM_SETTING
 * (key-value), same pattern as {@link DomainSettingService}.
 */
@Service
public class SmsSettingService {

    public static final String KEY_ENABLED         = "sms.enabled";
    public static final String KEY_USERNAME        = "sms.byteplus.username";   // Message Group ID
    public static final String KEY_PASSWORD        = "sms.byteplus.password";   // OpenAPI password
    public static final String KEY_SENDER_NAME     = "sms.sender_name";
    public static final String KEY_LOCAL_DELIVERY  = "sms.local_delivery_enabled";
    public static final String KEY_RELAY_IP        = "sms.relay_ip";
    /** Shared secret sent as X-Relay-Token to the relay's /api/sms/send (see sms-relay.py on the relay host). */
    public static final String KEY_RELAY_TOKEN     = "sms.relay_token";
    public static final String KEY_INBOUND_TOKEN   = "sms.inbound_token";

    // Sender-name generation (2026-07-10): BytePlus rejects/silently drops SMS whose "From"
    // is kanji/Japanese-only — the sender ID must be ASCII alphanumeric, <=10 chars. These
    // modes let the operator rotate through several valid identities instead of one static name.
    public static final String KEY_SENDER_NAME_MODE         = "sms.sender_name.mode";
    public static final String KEY_SENDER_NAME_FIXED_LIST   = "sms.sender_name.fixed_list";
    public static final String KEY_SENDER_NAME_RANDOM_LENGTH = "sms.sender_name.random_length";

    /**
     * SMS-specific send pacing, independent of the email broadcast rate (DomainSettingService's
     * broadcast.rate_per_min). Added 2026-08-06 per operator request after a 500-message SMS
     * broadcast (id 438) failed ~150 messages under burst load: the relay's own /api/sms/send
     * (sms-relay.py on 133.88.116.190) has a rate-limiter that isn't safe under true
     * concurrency (its hourly-counter file write raced across ThreadingHTTPServer threads and
     * crashed mid-request under our 8-worker parallel dispatch, surfacing to us as "relay
     * returned 502"). SMS messages are now dispatched serially (see ScheduledTaskService) at
     * this rate so the relay never receives two /api/sms/send calls at once, regardless of how
     * many workers the email dispatch path uses. Range [1, 600], default 60.
     */
    public static final String KEY_RATE_PER_MINUTE = "sms.rate_per_minute";

    public static final String MODE_FIXED        = "FIXED";
    public static final String MODE_RANDOM_ALNUM = "RANDOM_ALNUM";
    public static final String MODE_RANDOM_090   = "RANDOM_090";
    public static final String MODE_RANDOM_080   = "RANDOM_080";

    private static final String ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int MAX_SENDER_NAME_LEN = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final CrmSettingRepository repository;

    public SmsSettingService(CrmSettingRepository repository) {
        this.repository = repository;
    }

    public boolean isEnabled()          { return getBool(KEY_ENABLED, false); }
    public String getUsername()         { return get(KEY_USERNAME); }
    public String getPassword()         { return get(KEY_PASSWORD); }
    /** Legacy single fixed sender name — kept for callers that just need a display label (e.g. inbound record TO_ADDRESS). */
    public String getSenderName()       { return get(KEY_SENDER_NAME); }
    /** true = CRM calls BytePlus directly; false = CRM sends via the relay's /api/sms/send. */
    public boolean isLocalDelivery()    { return getBool(KEY_LOCAL_DELIVERY, true); }
    public String getRelayIp()          { return get(KEY_RELAY_IP); }
    public String getRelayToken()       { return get(KEY_RELAY_TOKEN); }

    /** SMS-specific dispatch rate (messages/minute), range-clamped [1, 600], default 60. */
    public int getRatePerMinute() {
        String v = get(KEY_RATE_PER_MINUTE);
        if (v == null || v.trim().isEmpty()) return 60;
        try {
            int n = Integer.parseInt(v.trim());
            if (n < 1) return 1;
            if (n > 600) return 600;
            return n;
        } catch (NumberFormatException e) { return 60; }
    }

    public void setRatePerMinute(int n) {
        if (n < 1) n = 1;
        if (n > 600) n = 600;
        save(KEY_RATE_PER_MINUTE, String.valueOf(n));
    }

    public String getSenderNameMode() {
        String v = get(KEY_SENDER_NAME_MODE);
        return v == null || v.trim().isEmpty() ? MODE_FIXED : v.trim();
    }

    public String getSenderNameFixedList() { return get(KEY_SENDER_NAME_FIXED_LIST); }

    public int getSenderNameRandomLength() {
        String v = get(KEY_SENDER_NAME_RANDOM_LENGTH);
        if (v == null) return 8;
        try {
            int n = Integer.parseInt(v.trim());
            return Math.max(1, Math.min(MAX_SENDER_NAME_LEN, n));
        } catch (NumberFormatException e) {
            return 8;
        }
    }

    /**
     * Resolve the actual sender identity to use for THIS outbound send. Must be called exactly
     * once per message and the result persisted on the Message row — sendNow() must reuse that
     * stored value rather than calling this again, or a random mode would send one name to
     * BytePlus while recording a different one in our own history.
     */
    public String resolveSenderName() {
        String mode = getSenderNameMode();
        switch (mode) {
            case MODE_RANDOM_ALNUM:
                return randomAlnum(getSenderNameRandomLength());
            case MODE_RANDOM_090:
                return "090" + randomDigits(8);
            case MODE_RANDOM_080:
                return "080" + randomDigits(8);
            case MODE_FIXED:
            default:
                return pickFixedPattern();
        }
    }

    private String pickFixedPattern() {
        List<String> patterns = new ArrayList<>();
        String raw = getSenderNameFixedList();
        if (raw != null) {
            for (String line : raw.split("\\r?\\n")) {
                String t = line.trim();
                if (!t.isEmpty()) patterns.add(t);
            }
        }
        if (patterns.isEmpty()) {
            String legacy = getSenderName();
            return (legacy == null || legacy.trim().isEmpty()) ? "info" : legacy.trim();
        }
        return patterns.get(RANDOM.nextInt(patterns.size()));
    }

    private static String randomAlnum(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(ALNUM.charAt(RANDOM.nextInt(ALNUM.length())));
        return sb.toString();
    }

    private static String randomDigits(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(RANDOM.nextInt(10));
        return sb.toString();
    }

    /**
     * Up to 5 fixed patterns, ASCII alphanumeric only, <=10 chars each — matches BytePlus's
     * "From" constraint (kanji/Japanese-only sender IDs are rejected/never delivered).
     * Returns a validation error message, or null if the input is acceptable.
     */
    public static String validateFixedList(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String[] lines = raw.split("\\r?\\n");
        int count = 0;
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            count++;
            if (count > 5) return "固定パターンは5件までです";
            if (t.length() > MAX_SENDER_NAME_LEN) return "「" + t + "」は10文字を超えています";
            if (!t.matches("[A-Za-z0-9]+")) return "「" + t + "」は半角英数字のみで入力してください(漢字・日本語は送信できません)";
        }
        return null;
    }

    /**
     * Shared secret embedded in the BytePlus MO webhook URL ({@code /api/inbound/sms/{token}}).
     * BytePlus doesn't document a request-signing scheme, so a hard-to-guess path segment is
     * the auth mechanism (mirrors the practice of most webhook providers without HMAC support).
     * Generated once on first access and persisted.
     */
    @Transactional
    public String getOrCreateInboundToken() {
        String existing = get(KEY_INBOUND_TOKEN);
        if (existing != null && !existing.trim().isEmpty()) return existing;
        String token = java.util.UUID.randomUUID().toString().replace("-", "")
                + java.util.UUID.randomUUID().toString().replace("-", "");
        save(KEY_INBOUND_TOKEN, token);
        return token;
    }

    /**
     * A blank {@code password} means "keep the existing one" — mirrors
     * {@link CarrierPoolService#update}. The settings form never re-displays the stored
     * password (browsers with {@code autocomplete="new-password"} tend to blank it anyway),
     * so treating a submitted-blank password as "unchanged" is required: otherwise saving
     * any other field (e.g. sender name) silently wipes working BytePlus credentials, which
     * is exactly what happened in production on 2026-07-09.
     */
    public void save(boolean enabled, String username, String password, String senderName,
                      boolean localDelivery, String relayIp,
                      String senderNameMode, String senderNameFixedList, Integer senderNameRandomLength,
                      String relayToken, Integer ratePerMinute) {
        save(KEY_ENABLED, String.valueOf(enabled));
        save(KEY_USERNAME, username == null ? "" : username.trim());
        if (password != null && !password.isEmpty()) {
            save(KEY_PASSWORD, password);
        }
        save(KEY_SENDER_NAME, senderName == null ? "" : senderName.trim());
        save(KEY_LOCAL_DELIVERY, String.valueOf(localDelivery));
        save(KEY_RELAY_IP, relayIp == null ? "" : relayIp.trim());
        save(KEY_SENDER_NAME_MODE, senderNameMode == null || senderNameMode.trim().isEmpty() ? MODE_FIXED : senderNameMode.trim());
        save(KEY_SENDER_NAME_FIXED_LIST, senderNameFixedList == null ? "" : senderNameFixedList.trim());
        save(KEY_SENDER_NAME_RANDOM_LENGTH, String.valueOf(
                senderNameRandomLength == null ? 8 : Math.max(1, Math.min(MAX_SENDER_NAME_LEN, senderNameRandomLength))));
        // Blank means "keep existing" — same reasoning as password (never re-displayed in full).
        if (relayToken != null && !relayToken.isEmpty()) {
            save(KEY_RELAY_TOKEN, relayToken);
        }
        setRatePerMinute(ratePerMinute == null ? 60 : ratePerMinute);
    }

    private String get(String key) {
        return repository.findBySettingKey(key).map(CrmSetting::getSettingValue).orElse(null);
    }

    private boolean getBool(String key, boolean def) {
        String v = get(key);
        return v == null ? def : "true".equalsIgnoreCase(v.trim()) || "1".equals(v.trim());
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
