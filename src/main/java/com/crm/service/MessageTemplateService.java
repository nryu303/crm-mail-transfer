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

    /** Per-page cap; 5 pages total => 250 templates org-wide. */
    public static final int MAX_TEMPLATES = 50;
    public static final int MAX_PAGES = 5;

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

    public String getPageTitle(int pageNo) {
        if (pageNo < 1 || pageNo > MAX_PAGES) pageNo = 1;
        String v = settingRepository.findBySettingKey(pageTitleKey(pageNo))
                .map(CrmSetting::getSettingValue).orElse(null);
        return (v == null || v.trim().isEmpty()) ? ("ページ " + pageNo) : v;
    }

    /** Ordered list of page titles, indexed 0..MAX_PAGES-1 (page 1..MAX_PAGES). */
    public List<String> listPageTitles() {
        List<String> out = new ArrayList<>(MAX_PAGES);
        for (int i = 1; i <= MAX_PAGES; i++) out.add(getPageTitle(i));
        return out;
    }

    @Transactional
    public void setPageTitle(int pageNo, String title) {
        if (pageNo < 1 || pageNo > MAX_PAGES) return;
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
        if (pageNo < 1 || pageNo > MAX_PAGES) pageNo = 1;
        return repository.findByPageNoOrderByDisplayOrderAscIdAsc(pageNo);
    }

    public long countByPage(int pageNo) {
        if (pageNo < 1 || pageNo > MAX_PAGES) pageNo = 1;
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
