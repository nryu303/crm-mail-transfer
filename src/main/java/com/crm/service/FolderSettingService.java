package com.crm.service;

import com.crm.entity.CrmSetting;
import com.crm.repository.CrmSettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Manages the configurable list of folder names users can be grouped into.
 * Stored in CRM_SETTING under key "folder.names" as a comma-separated string.
 */
@Service
public class FolderSettingService {

    public static final String KEY = "folder.names";
    /** Suggested archive folder name — seeded on first install (empty CRM_SETTING) but the
     *  operator can rename or delete it freely afterwards. No code depends on its presence. */
    public static final String ARCHIVE_FOLDER = "退避";
    public static final List<String> DEFAULT_FOLDERS =
            Collections.unmodifiableList(java.util.Arrays.asList("A", "B", "C", "D", ARCHIVE_FOLDER));

    private final CrmSettingRepository repo;

    public FolderSettingService(CrmSettingRepository repo) { this.repo = repo; }

    public List<String> listFolders() {
        String raw = repo.findBySettingKey(KEY).map(CrmSetting::getSettingValue).orElse(null);
        if (raw == null || raw.trim().isEmpty()) return DEFAULT_FOLDERS;
        // LinkedHashSet preserves first-seen order while collapsing duplicates — defensive
        // against a malformed saved value (2026-05-21: operator's list had "ーーーーーーーー"
        // present twice, which surfaced as a doubled checkbox in the filter dropdown).
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) seen.add(t);
        }
        return new ArrayList<>(seen);
    }

    /** Replace the whole folder list with whatever the operator saved. No auto-injection
     *  of any "system" folder — the operator has full control over the list. */
    @Transactional
    public void save(List<String> folders) {
        java.util.LinkedHashSet<String> dedup = new java.util.LinkedHashSet<>();
        if (folders != null) {
            for (String s : folders) {
                if (s == null) continue;
                String t = s.trim();
                if (!t.isEmpty()) dedup.add(t);
            }
        }
        String value = String.join(",", dedup);
        CrmSetting s = repo.findBySettingKey(KEY).orElseGet(() -> {
            CrmSetting ns = new CrmSetting();
            ns.setSettingKey(KEY);
            ns.setDescription("Comma-separated folder names shown on the user list");
            ns.setUpdatedAt(LocalDateTime.now());
            return ns;
        });
        s.setSettingValue(value);
        s.setUpdatedAt(LocalDateTime.now());
        repo.save(s);
    }
}
