package com.crm.controller;

import com.crm.entity.ReplyPageSetting;
import com.crm.service.ReplyPageSettingService;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Lightweight JSON endpoint for inline-saving ReplyPageSetting.urlLeadText from the
 * message-thread 置き換えタグ panel (next to the %reply_url% / %external_url% rows), so the
 * operator doesn't have to navigate to /manager/settings/reply-page to change this one field.
 * Same "save" pattern as {@link UserTagApiController}, but this setting is global (applies to
 * every future %reply_url% / %external_url% substitution), not per-user.
 */
@RestController
@RequestMapping("/manager/api/settings")
public class ReplyUrlLeadTextApiController {

    private final ReplyPageSettingService replyPageSettingService;

    public ReplyUrlLeadTextApiController(ReplyPageSettingService replyPageSettingService) {
        this.replyPageSettingService = replyPageSettingService;
    }

    @PutMapping("/url-lead-text")
    public Map<String, Object> updateUrlLeadText(@RequestBody Map<String, String> body) {
        ReplyPageSetting s = replyPageSettingService.getOrCreate();
        String raw = body.get("urlLeadText");
        s.setUrlLeadText(raw == null ? null : trim(raw));
        replyPageSettingService.saveUrlLeadText(s);
        Map<String, Object> r = new HashMap<>();
        r.put("ok", true);
        return r;
    }

    private static String trim(String s) {
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
