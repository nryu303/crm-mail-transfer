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
 * Manages the per-user reply-page HTML slots — there are SLOT_COUNT (10) of them per
 * user (CRM_USER.MEMO + MEMO_2..MEMO_10; 7-10 added 2026-09-02, displayed as a 5x2 grid
 * in the admin UI). Each slot gets an operator-supplied display title (global, stored in
 * CRM_SETTING keyed as memo.slot.N.title).
 *
 * <p>Also drives the bulk-edit page (/manager/settings/memo-html-bulk) that lets an
 * operator apply the same SLOT_COUNT-slot HTML set + 使用中 selection to every user in a folder.
 */
@Service
public class ReplyHtmlSlotService {

    public static final int SLOT_COUNT = 10;

    /** ①..⑩ in slot order (index 0 = slot 1) — shared by every template that needs to label
     *  the 10 reply-HTML slots (user detail, memo-html-bulk). */
    public static final List<String> CIRCLED_NUMBERS = List.of("①", "②", "③", "④", "⑤", "⑥", "⑦", "⑧", "⑨", "⑩");

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
        return circled(slotNo) + " 返信HTML";
    }

    /** ①..⑩ for slotNo 1..10; falls back to the plain number outside that range. */
    public static String circled(int slotNo) {
        return (slotNo >= 1 && slotNo <= SLOT_COUNT) ? CIRCLED_NUMBERS.get(slotNo - 1) : String.valueOf(slotNo);
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

    // ---- Bulk-edit page draft storage ----
    // 2026-09-10 operator request: the bulk-edit page's 10 slots had no way to persist
    // content except "適用" (which immediately overwrites every user in a chosen folder) —
    // there was no lightweight "just save what I typed" for staging/reusing HTML. This is a
    // separate CrmSetting-backed store, decoupled from any folder/user, that the bulk-edit
    // page loads into its textareas by default and can save back to independently of applying.

    private static String draftKey(int slotNo) { return "memo.bulk.draft." + slotNo; }

    /** The 10 draft slots, in order (index 0 = slot 1). Null entries mean "never saved". */
    public String[] listDraftSlots() {
        String[] out = new String[SLOT_COUNT];
        for (int i = 1; i <= SLOT_COUNT; i++) {
            out[i - 1] = settingRepository.findBySettingKey(draftKey(i))
                    .map(CrmSetting::getSettingValue).orElse(null);
        }
        return out;
    }

    @Transactional
    public void saveDraftSlots(String[] htmls) {
        if (htmls == null || htmls.length != SLOT_COUNT) {
            throw new IllegalArgumentException("htmls must have exactly " + SLOT_COUNT + " entries");
        }
        for (int i = 1; i <= SLOT_COUNT; i++) {
            String key = draftKey(i);
            String value = htmls[i - 1];
            CrmSetting s = settingRepository.findBySettingKey(key).orElseGet(() -> {
                CrmSetting ns = new CrmSetting();
                ns.setSettingKey(key);
                ns.setDescription("専用返信画面HTML 一括編集ページの下書き保存 (スロット " + key.substring(key.lastIndexOf('.') + 1) + ")");
                ns.setUpdatedAt(LocalDateTime.now());
                return ns;
            });
            s.setSettingValue(value);
            s.setUpdatedAt(LocalDateTime.now());
            settingRepository.save(s);
        }
    }

    /** One draft slot's HTML (1..SLOT_COUNT), or null if never saved. */
    public String getDraftSlot(int slotNo) {
        if (slotNo < 1 || slotNo > SLOT_COUNT) return null;
        return settingRepository.findBySettingKey(draftKey(slotNo))
                .map(CrmSetting::getSettingValue).orElse(null);
    }
}
