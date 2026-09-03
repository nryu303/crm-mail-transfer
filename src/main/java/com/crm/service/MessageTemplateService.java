package com.crm.service;

import com.crm.dto.MessageTemplateForm;
import com.crm.entity.CrmSetting;
import com.crm.entity.MessageTemplate;
import com.crm.repository.CrmSettingRepository;
import com.crm.repository.MessageTemplateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class MessageTemplateService {

    /** Per-page cap. Number of pages is no longer fixed — operators can add pages
     *  freely via addPage()/deletePage(); see listActivePagePageNumbers(). */
    public static final int MAX_TEMPLATES = 50;

    /** CrmSetting key holding the ordered, comma-separated list of active page numbers
     *  (e.g. "1,2,3,4,5,6"). Falls back to the original fixed 1..5 the first time this
     *  is read on an existing installation that predates free-form paging. */
    private static final String PAGES_LIST_KEY = "template.pages.list";
    private static final String DEFAULT_PAGES = "1,2,3,4,5";

    private final MessageTemplateRepository repository;
    private final CrmSettingRepository settingRepository;

    public MessageTemplateService(MessageTemplateRepository repository,
                                  CrmSettingRepository settingRepository) {
        this.repository = repository;
        this.settingRepository = settingRepository;
    }

    /** Page-title storage uses one CrmSetting row per page: template.page.N.title. */
    private static String pageTitleKey(int pageNo) {
        return "template.page." + pageNo + ".title";
    }

    /** Ordered list of currently active page numbers. Persisted so the operator's
     *  add/delete/reorder-by-title choices survive restarts; auto-initialised to the
     *  original fixed 1..5 range on first read. */
    public List<Integer> listActivePageNumbers() {
        String raw = settingRepository.findBySettingKey(PAGES_LIST_KEY)
                .map(CrmSetting::getSettingValue).orElse(null);
        if (raw == null || raw.trim().isEmpty()) raw = DEFAULT_PAGES;
        List<Integer> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            try { out.add(Integer.parseInt(part)); } catch (NumberFormatException ignore) { /* skip malformed */ }
        }
        if (out.isEmpty()) out.add(1);
        return out;
    }

    @Transactional
    void savePageNumbers(List<Integer> pages) {
        String key = PAGES_LIST_KEY;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pages.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(pages.get(i));
        }
        CrmSetting s = settingRepository.findBySettingKey(key).orElseGet(() -> {
            CrmSetting ns = new CrmSetting();
            ns.setSettingKey(key);
            ns.setDescription("Ordered, comma-separated list of active message-template page numbers");
            ns.setUpdatedAt(LocalDateTime.now());
            return ns;
        });
        s.setSettingValue(sb.toString());
        s.setUpdatedAt(LocalDateTime.now());
        settingRepository.save(s);
    }

    /** Adds a new empty page (number = current max + 1) and returns its number. */
    @Transactional
    public int addPage() {
        List<Integer> pages = listActivePageNumbers();
        int next = 1;
        for (Integer p : pages) if (p >= next) next = p + 1;
        pages.add(next);
        savePageNumbers(pages);
        return next;
    }

    /** Removes a page from the active list. Refuses (returns false, no-op) if the page
     *  still has templates or is the last remaining page — callers should have the
     *  operator move/delete those templates first, and at least one page must exist. */
    @Transactional
    public boolean deletePage(int pageNo) {
        if (countByPage(pageNo) > 0) return false;
        List<Integer> pages = listActivePageNumbers();
        if (pages.size() <= 1) return false;
        if (!pages.remove(Integer.valueOf(pageNo))) return false;
        savePageNumbers(pages);
        return true;
    }

    /** True if pageNo is currently a registered page. */
    public boolean isActivePage(int pageNo) {
        return listActivePageNumbers().contains(pageNo);
    }

    /** First active page number — used as the fallback when an invalid/removed page is requested. */
    private int firstActivePage() {
        return listActivePageNumbers().get(0);
    }

    private int normalizePageNo(Integer pageNo) {
        if (pageNo == null) return firstActivePage();
        return isActivePage(pageNo) ? pageNo : firstActivePage();
    }

    public String getPageTitle(int pageNo) {
        String v = settingRepository.findBySettingKey(pageTitleKey(pageNo))
                .map(CrmSetting::getSettingValue).orElse(null);
        return (v == null || v.trim().isEmpty()) ? ("ページ " + pageNo) : v;
    }

    /** Ordered titles for every currently active page, in the same order as listActivePageNumbers(). */
    public List<String> listPageTitles() {
        List<Integer> pages = listActivePageNumbers();
        List<String> out = new ArrayList<>(pages.size());
        for (Integer p : pages) out.add(getPageTitle(p));
        return out;
    }

    @Transactional
    public void setPageTitle(int pageNo, String title) {
        if (!isActivePage(pageNo)) return;
        String key = pageTitleKey(pageNo);
        String value = title == null ? "" : title.trim();
        CrmSetting s = settingRepository.findBySettingKey(key).orElseGet(() -> {
            CrmSetting ns = new CrmSetting();
            ns.setSettingKey(key);
            ns.setDescription("Title for message-template page " + pageNo);
            ns.setUpdatedAt(LocalDateTime.now());
            return ns;
        });
        s.setSettingValue(value);
        s.setUpdatedAt(LocalDateTime.now());
        settingRepository.save(s);
    }

    public List<MessageTemplate> listAll() {
        return repository.findAllByOrderByDisplayOrderAscIdAsc();
    }

    /** Single-page view used by /manager/settings/message-templates?page=N
     *  and by the thread / inbox template panels. */
    public List<MessageTemplate> listByPage(int pageNo) {
        return repository.findByPageNoOrderByDisplayOrderAscIdAsc(normalizePageNo(pageNo));
    }

    public long countByPage(int pageNo) {
        return repository.countByPageNo(pageNo);
    }

    public Optional<MessageTemplate> findById(Long id) {
        return repository.findById(id);
    }

    public long count() {
        return repository.count();
    }

    @Transactional
    public MessageTemplate create(MessageTemplateForm form) {
        int pageNo = form.getPageNo() == null ? 1 : form.getPageNo();
        if (countByPage(pageNo) >= MAX_TEMPLATES) {
            throw new TooManyTemplatesException(pageNo);
        }
        MessageTemplate t = new MessageTemplate();
        form.applyTo(t);
        // 2026-05-24: a freshly-created template should land at the BOTTOM of its page
        // (operator-requested). MessageTemplateForm.displayOrder defaults to 0 when blank,
        // which would otherwise dump it at the very top above existing rows. Override that
        // here whenever the operator didn't set an explicit non-zero displayOrder.
        if (t.getDisplayOrder() == null || t.getDisplayOrder() == 0) {
            List<MessageTemplate> samePage = repository.findByPageNoOrderByDisplayOrderAscIdAsc(pageNo);
            int nextOrder = 0;
            for (MessageTemplate existing : samePage) {
                Integer o = existing.getDisplayOrder();
                if (o != null && o >= nextOrder) nextOrder = o + 1;
            }
            t.setDisplayOrder(nextOrder);
        }
        return repository.save(t);
    }

    @Transactional
    public MessageTemplate update(Long id, MessageTemplateForm form) {
        MessageTemplate t = repository.findById(id)
                .orElseThrow(() -> new NotFoundException(id));
        form.applyTo(t);
        return repository.save(t);
    }

    @Transactional
    public void delete(Long id) {
        repository.deleteById(id);
    }

    /**
     * Apply a new display order from the drag-and-drop list page. The submitted {@code ids}
     * list is the desired top-to-bottom sequence; we just renumber DISPLAY_ORDER as 0..N-1
     * in that order. Any DB row whose id isn't in the submitted list is left alone (it'll
     * sort to the end on the next listAll() because displayOrder is unchanged).
     */
    @Transactional
    public void reorder(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        int order = 0;
        for (Long id : ids) {
            if (id == null) continue;
            Optional<MessageTemplate> opt = repository.findById(id);
            if (!opt.isPresent()) continue;
            MessageTemplate t = opt.get();
            t.setDisplayOrder(order++);
            repository.save(t);
        }
    }

    public static class TooManyTemplatesException extends RuntimeException {
        public TooManyTemplatesException() {
            super("最大" + MAX_TEMPLATES + "件までしか登録できません");
        }
        public TooManyTemplatesException(int pageNo) {
            super("ページ " + pageNo + " は既に最大" + MAX_TEMPLATES + "件登録済みです");
        }
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(Long id) { super("template not found: " + id); }
    }
}
