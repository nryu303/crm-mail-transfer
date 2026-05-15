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
    public static final List<String> DEFAULT_FOLDERS =
            Collections.unmodifiableList(java.util.Arrays.asList("フォルダA", "フォルダB", "フォルダC"));

    private final CrmSettingRepository repo;

    public FolderSettingService(CrmSettingRepository repo) { this.repo = repo; }

    public List<String> listFolders() {
        String raw = repo.findBySettingKey(KEY).map(CrmSetting::getSettingValue).orElse(null);
        if (raw == null || raw.trim().isEmpty()) return DEFAULT_FOLDERS;
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out.isEmpty() ? DEFAULT_FOLDERS : out;
    }

    /** Replace the whole folder list. Pass a list or comma-separated string. */
    @Transactional
    public void save(List<String> folders) {
        String value = folders == null ? "" :
                folders.stream().map(String::trim).filter(s -> !s.isEmpty())
                       .collect(Collectors.joining(","));
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
