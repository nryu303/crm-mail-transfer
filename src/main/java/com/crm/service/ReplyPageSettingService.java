package com.crm.service;

import com.crm.dto.ReplyPageSettingForm;
import com.crm.entity.ReplyPageSetting;
import com.crm.repository.ReplyPageSettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReplyPageSettingService {

    private final ReplyPageSettingRepository repository;

    public ReplyPageSettingService(ReplyPageSettingRepository repository) {
        this.repository = repository;
    }

    public ReplyPageSetting getOrCreate() {
        return repository.findById(1L).orElseGet(() -> {
            ReplyPageSetting s = new ReplyPageSetting();
            s.setId(1L);
            s.setRequireLogin(Boolean.FALSE);
            return repository.save(s);
        });
    }

    @Transactional
    public ReplyPageSetting save(ReplyPageSettingForm form) {
        ReplyPageSetting s = getOrCreate();
        // defaultHeaderHtml is rendered inside a sandboxed iframe — store raw so that
        // full-page HTML (DOCTYPE / <style> / <script> / Google Fonts <link>) is preserved
        // exactly as the admin pasted it.  Same policy as per-user CrmUser.memo.
        // footerHtml is injected directly into the reply page DOM via th:utext, so we
        // strip the bare minimum: </style> and <script to prevent obvious breakage.
        if (form.getFooterHtml() != null) {
            form.setFooterHtml(form.getFooterHtml()
                    .replace("</style", "")
                    .replace("<script", ""));
        }
        // CSS is inlined into <style>; strip closing tag to prevent injection.
        if (form.getDefaultCss() != null) {
            form.setDefaultCss(form.getDefaultCss()
                    .replace("</style", "")
                    .replace("<script", ""));
        }
        form.applyTo(s);
        return repository.save(s);
    }
}
