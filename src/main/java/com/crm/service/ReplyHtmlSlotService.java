package com.crm.service;

import com.crm.entity.CrmSetting;
import com.crm.entity.CrmUser;
import com.crm.repository.CrmSettingRepository;
import com.crm.repository.CrmUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages the per-user reply-page HTML slots — there are SLOT_COUNT (6) of them per
 * user (CRM_USER.MEMO + MEMO_2..MEMO_6). Each slot gets an operator-supplied display
 * title (global, stored in CRM_SETTING keyed as memo.slot.N.title).
 *
 * <p>Also drives the bulk-edit page (/manager/settings/memo-html-bulk) that lets an
 * operator apply the same 6-slot HTML set + 使用中 selection to every user in a folder.
 */
@Service
public class ReplyHtmlSlotService {

    public static final int SLOT_COUNT = 6;

    private final CrmSettingRepository settingRepository;
    private final CrmUserRepository userRepository;

    public ReplyHtmlSlotService(CrmSettingRepository settingRepository,
                                 CrmUserRepository userRepository) {
        this.settingRepository = settingRepository;
        this.userRepository = userRepository;
    }

    private static String slotTitleKey(int slotNo) {
        return "memo.slot." + slotNo + ".title";
    }

    /** Returns the operator-supplied title for slot N (1..SLOT_COUNT), or a default
     *  "① 返信HTML" style label when unset. */
    public String getSlotTitle(int slotNo) {
        if (slotNo < 1 || slotNo > SLOT_COUNT) slotNo = 1;
        String v = settingRepository.findBySettingKey(slotTitleKey(slotNo))
                .map(CrmSetting::getSettingValue).orElse(null);
        return (v == null || v.trim().isEmpty()) ? defaultTitle(slotNo) : v;
    }

    public List<String> listSlotTitles() {
        List<String> out = new ArrayList<>(SLOT_COUNT);
        for (int i = 1; i <= SLOT_COUNT; i++) out.add(getSlotTitle(i));
        return out;
    }

    @Transactional
    public void setSlotTitle(int slotNo, String title) {
        if (slotNo < 1 || slotNo > SLOT_COUNT) return;
        String key = slotTitleKey(slotNo);
        String value = title == null ? "" : title.trim();
        CrmSetting s = settingRepository.findBySettingKey(key).orElseGet(() -> {
            CrmSetting ns = new CrmSetting();
            ns.setSettingKey(key);
            ns.setDescription("Display title for reply-HTML slot " + slotNo);
            ns.setUpdatedAt(LocalDateTime.now());
            return ns;
        });
        s.setSettingValue(value);
        s.setUpdatedAt(LocalDateTime.now());
        settingRepository.save(s);
    }

    /** "① 返信HTML" style label used when a slot has no operator-supplied title. */
    private static String defaultTitle(int slotNo) {
        String circled;
        switch (slotNo) {
            case 1: circled = "①"; break;
            case 2: circled = "②"; break;
            case 3: circled = "③"; break;
            case 4: circled = "④"; break;
            case 5: circled = "⑤"; break;
            case 6: circled = "⑥"; break;
            default: circled = String.valueOf(slotNo);
        }
        return circled + " 返信HTML";
    }

    /**
     * Apply the supplied HTML set + active slot to every user matching {@code folder}.
     * The {@code htmls} array must be length SLOT_COUNT (slot 1 at index 0). Null
     * entries CLEAR the slot for matched users. Folder=null applies to 未設定 users.
     *
     * <p>Bulk update via per-row save() — slow at 30K+ but safe with the parallel
     * dispatcher running. Returns the number of users updated.
     */
    @Transactional
    public int bulkApply(String folder, String[] htmls, Integer activeSlot) {
        if (htmls == null || htmls.length != SLOT_COUNT) {
            throw new IllegalArgumentException("htmls must have exactly " + SLOT_COUNT + " entries");
        }
        int slot = (activeSlot == null || activeSlot < 1 || activeSlot > SLOT_COUNT) ? 1 : activeSlot;

        // Resolve users in the chosen folder. "" / null folder both mean 未設定 here.
        List<Long> ids;
        if (folder == null || folder.trim().isEmpty()) {
            ids = userRepository.findIdsByFolderIsNull();
        } else {
            ids = userRepository.findIdsByFolder(folder.trim());
        }
        if (ids.isEmpty()) return 0;

        // Process in 500-id chunks so a single transaction doesn't hold thousands of
        // CRM_USER row locks at once (the dispatcher updates CRM_USER too).
        int total = 0;
        final int chunkSize = 500;
        for (int i = 0; i < ids.size(); i += chunkSize) {
            List<Long> chunk = ids.subList(i, Math.min(i + chunkSize, ids.size()));
            for (CrmUser u : userRepository.findAllById(chunk)) {
                for (int s = 1; s <= SLOT_COUNT; s++) {
                    u.setMemoSlot(s, htmls[s - 1]);
                }
                u.setActiveMemoSlot(slot);
                userRepository.save(u);
                total++;
            }
        }
        return total;
    }
}
