package com.crm.service;

import com.crm.entity.CrmSetting;
import com.crm.repository.CrmSettingRepository;
import com.crm.repository.CrmUserRepository;
import com.crm.repository.MessageRepository;
import com.crm.repository.CarrierUserBindingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-folder retention + manual purge for MESSAGE and CARRIER_USER_BINDING rows.
 *
 * <p>Memory pressure on the CRM box grew with the broadcast history (108K
 * INBOUND_MAIL_LOG + 84K MESSAGE rows at 30K users). Operator-requested 2026-05-26:
 * give each folder a "retention days" knob plus on-demand buttons so an operator
 * can drop users into a 退避 folder and let the system trim them daily.
 *
 * <p>Retention values live in CRM_SETTING as {@code folder.retention.<name>} →
 * integer day count. Value 0 / unset disables auto-purge for that folder.
 */
@Service
public class FolderRetentionService {

    private static final Logger log = LoggerFactory.getLogger(FolderRetentionService.class);

    private final CrmSettingRepository settingRepository;
    private final CrmUserRepository userRepository;
    private final MessageRepository messageRepository;
    private final CarrierUserBindingRepository bindingRepository;

    public FolderRetentionService(CrmSettingRepository settingRepository,
                                  CrmUserRepository userRepository,
                                  MessageRepository messageRepository,
                                  CarrierUserBindingRepository bindingRepository) {
        this.settingRepository = settingRepository;
        this.userRepository = userRepository;
        this.messageRepository = messageRepository;
        this.bindingRepository = bindingRepository;
    }

    private static String key(String folderName) { return "folder.retention." + folderName; }

    /** Returns the operator-configured retention day count for the given folder, or 0 if unset. */
    public int getRetentionDays(String folderName) {
        if (folderName == null || folderName.isEmpty()) return 0;
        String v = settingRepository.findBySettingKey(key(folderName))
                .map(CrmSetting::getSettingValue).orElse(null);
        if (v == null || v.trim().isEmpty()) return 0;
        try {
            int n = Integer.parseInt(v.trim());
            return n < 0 ? 0 : n;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Map folder name → days, for every configured folder. Missing folders return 0. */
    public Map<String, Integer> getRetentionDaysMap(List<String> folderNames) {
        Map<String, Integer> out = new HashMap<>();
        if (folderNames == null) return out;
        for (String name : folderNames) out.put(name, getRetentionDays(name));
        return out;
    }

    @Transactional
    public void setRetentionDays(String folderName, int days) {
        if (folderName == null || folderName.isEmpty()) return;
        if (days < 0) days = 0;
        String k = key(folderName);
        String value = Integer.toString(days);
        CrmSetting s = settingRepository.findBySettingKey(k).orElseGet(() -> {
            CrmSetting ns = new CrmSetting();
            ns.setSettingKey(k);
            ns.setDescription("Auto-purge retention days for folder '" + folderName + "'");
            ns.setUpdatedAt(LocalDateTime.now());
            return ns;
        });
        s.setSettingValue(value);
        s.setUpdatedAt(LocalDateTime.now());
        settingRepository.save(s);
    }

    /**
     * Delete every MESSAGE row whose user_id is currently in {@code folderName}.
     * Returns the number of rows deleted. Called by the operator-facing "履歴削除" button
     * AND by the daily auto-purge tick when retentionDays==0 OR no per-message age filter.
     */
    @Transactional
    public int purgeMessagesForFolder(String folderName) {
        List<Long> ids = (folderName == null || folderName.isEmpty())
                ? userRepository.findIdsByFolderIsNull()
                : userRepository.findIdsByFolder(folderName);
        if (ids.isEmpty()) return 0;
        int deleted = 0;
        // Chunked deletes to limit the per-statement row-lock count (parallel dispatcher
        // also touches MESSAGE).
        final int chunkSize = 1000;
        for (int i = 0; i < ids.size(); i += chunkSize) {
            List<Long> chunk = ids.subList(i, Math.min(i + chunkSize, ids.size()));
            deleted += messageRepository.deleteByUserIdIn(chunk);
        }
        log.info("FolderRetention: deleted {} MESSAGE rows for folder '{}' ({} users)",
                deleted, folderName, ids.size());
        return deleted;
    }

    /**
     * Delete MESSAGE rows older than {@code olderThanDays} for users in the folder.
     * Used by the daily auto-purge tick.
     */
    @Transactional
    public int purgeOldMessagesForFolder(String folderName, int olderThanDays) {
        if (olderThanDays <= 0) return purgeMessagesForFolder(folderName);
        List<Long> ids = (folderName == null || folderName.isEmpty())
                ? userRepository.findIdsByFolderIsNull()
                : userRepository.findIdsByFolder(folderName);
        if (ids.isEmpty()) return 0;
        LocalDateTime cutoff = LocalDateTime.now().minusDays(olderThanDays);
        int deleted = 0;
        final int chunkSize = 1000;
        for (int i = 0; i < ids.size(); i += chunkSize) {
            List<Long> chunk = ids.subList(i, Math.min(i + chunkSize, ids.size()));
            deleted += messageRepository.deleteByUserIdInAndCreatedAtBefore(chunk, cutoff);
        }
        log.info("FolderRetention: deleted {} MESSAGE rows older than {}d for folder '{}'",
                deleted, olderThanDays, folderName);
        return deleted;
    }

    /** Drop every CARRIER_USER_BINDING row for users currently in the folder. */
    @Transactional
    public int purgeBindingsForFolder(String folderName) {
        List<Long> ids = (folderName == null || folderName.isEmpty())
                ? userRepository.findIdsByFolderIsNull()
                : userRepository.findIdsByFolder(folderName);
        if (ids.isEmpty()) return 0;
        int deleted = 0;
        final int chunkSize = 1000;
        for (int i = 0; i < ids.size(); i += chunkSize) {
            List<Long> chunk = ids.subList(i, Math.min(i + chunkSize, ids.size()));
            deleted += bindingRepository.deleteByUserIdIn(chunk);
        }
        log.info("FolderRetention: deleted {} CARRIER_USER_BINDING rows for folder '{}' ({} users)",
                deleted, folderName, ids.size());
        return deleted;
    }
}
