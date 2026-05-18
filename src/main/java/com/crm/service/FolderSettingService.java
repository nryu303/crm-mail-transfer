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
    /**
     * Permanent system folder used as the archive destination for inactive users.
     * Always appended to the folder list (even if the operator removed it from the
     * settings save), so 全ユーザー移動先として常時利用可能。
     */
    public static final String ARCHIVE_FOLDER = "退避";
    public static final List<String> DEFAULT_FOLDERS =
            Collections.unmodifiableList(java.util.Arrays.asList("A", "B", "C", "D", ARCHIVE_FOLDER));

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
        if (out.isEmpty()) return DEFAULT_FOLDERS;
        // Always ensure the system archive folder is present (operator can re-order
        // but never delete it). Appended at the end of the operator's list.
        if (!out.contains(ARCHIVE_FOLDER)) out.add(ARCHIVE_FOLDER);
        return out;
    }

    /** Replace the whole folder list. Pass a list or comma-separated string.
     *  The system 退避 folder is always re-injected if the caller omitted it. */
    @Transactional
    public void save(List<String> folders) {
        List<String> cleaned = folders == null ? new ArrayList<>() :
                folders.stream().map(String::trim).filter(s -> !s.isEmpty())
                       .collect(Collectors.toList());
        if (!cleaned.contains(ARCHIVE_FOLDER)) cleaned.add(ARCHIVE_FOLDER);
        String value = String.join(",", cleaned);
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
