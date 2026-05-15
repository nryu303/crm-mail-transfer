package com.crm.service;

import com.crm.entity.CrmUser;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Substitutes %tag% placeholders in message templates with per-user values.
 *
 * Built-in tags (always available, derived from system/user):
 *   %name%      -> display_name
 *   %email%     -> email
 *   %date_jp%   -> today in Japanese format (2026年4月19日)
 *
 * Per-user custom tags: admin configures up to 5 (tag1..tag5) key/value pairs
 * on the user edit screen. The key becomes the placeholder name.
 *
 * Example: user.tag1_key = "amount", user.tag1_value = "1,200円".
 * Template "お支払い金額は %amount% です" -> "お支払い金額は 1,200円 です".
 */
@Service
public class PlaceholderService {

    public static final List<BuiltinTag> BUILTIN_TAGS = Arrays.asList(
            new BuiltinTag("%name%", "表示名"),
            new BuiltinTag("%email%", "メールアドレス"),
            new BuiltinTag("%date_jp%", "当日の日付 (例: 2026年4月19日)")
    );

    private static final DateTimeFormatter JP_DATE =
            DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.JAPAN);

    /** Replace all known placeholders in template with values from this user. Null template -> null. */
    public String substitute(String template, CrmUser user) {
        if (template == null) return null;
        Map<String, String> bindings = buildBindings(user);
        String out = template;
        for (Map.Entry<String, String> e : bindings.entrySet()) {
            out = out.replace(e.getKey(), e.getValue());
        }
        return out;
    }

    /** Ordered map from placeholder token (with surrounding %) to replacement value. */
    public Map<String, String> buildBindings(CrmUser u) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("%name%", safe(u == null ? null : u.getDisplayName()));
        m.put("%email%", safe(u == null ? null : u.getEmail()));
        m.put("%date_jp%", LocalDate.now().format(JP_DATE));
        if (u != null) {
            addCustom(m, u.getTag1Key(), u.getTag1Value());
            addCustom(m, u.getTag2Key(), u.getTag2Value());
            addCustom(m, u.getTag3Key(), u.getTag3Value());
            addCustom(m, u.getTag4Key(), u.getTag4Value());
            addCustom(m, u.getTag5Key(), u.getTag5Value());
        }
        return m;
    }

    private static void addCustom(Map<String, String> m, String key, String val) {
        if (key == null) return;
        String k = key.trim();
        if (k.isEmpty()) return;
        if (!k.startsWith("%")) k = "%" + k;
        if (!k.endsWith("%")) k = k + "%";
        // Only override a (possibly built-in) binding when the user actually provided a value.
        // e.g. leaving %date_jp% empty keeps the auto today-date from the built-ins.
        if (val == null || val.isEmpty()) return;
        m.put(k, val);
    }

    private static String safe(String s) { return s == null ? "" : s; }

    public static class BuiltinTag {
        private final String token;
        private final String description;
        public BuiltinTag(String token, String description) {
            this.token = token;
            this.description = description;
        }
        public String getToken() { return token; }
        public String getDescription() { return description; }
    }
}
