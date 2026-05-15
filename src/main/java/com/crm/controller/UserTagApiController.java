package com.crm.controller;

import com.crm.entity.CrmUser;
import com.crm.repository.CrmUserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

/**
 * Lightweight JSON endpoint for inline-saving the per-user placeholder tag values from
 * the message-thread reply pane. Only the four named tag slots are exposed (the keys
 * themselves are fixed by {@link com.crm.dto.UserForm}).
 */
@RestController
@RequestMapping("/manager/api/users")
public class UserTagApiController {

    private final CrmUserRepository userRepository;

    public UserTagApiController(CrmUserRepository userRepository) { this.userRepository = userRepository; }

    @PutMapping("/{id}/tags")
    public Map<String, Object> updateTags(@PathVariable Long id, @RequestBody Map<String, String> body) {
        CrmUser u = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        // Fixed slot mapping — same as UserForm.applyTo()
        u.setTag1Key("amount");       u.setTag1Value(trim(body.get("amount")));
        u.setTag2Key("product");      u.setTag2Value(trim(body.get("product")));
        u.setTag3Key("full_address"); u.setTag3Value(trim(body.get("fullAddress")));
        u.setTag4Key("date_jp");      u.setTag4Value(trim(body.get("dateJp")));
        userRepository.save(u);
        Map<String, Object> r = new HashMap<>();
        r.put("ok", true);
        return r;
    }

    /** Save just the memo field (separate endpoint so the thread compose pane can save inline). */
    @PutMapping("/{id}/memo")
    public Map<String, Object> updateMemo(@PathVariable Long id, @RequestBody Map<String, String> body) {
        CrmUser u = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        u.setMemo(body.get("memo"));
        userRepository.save(u);
        Map<String, Object> r = new HashMap<>();
        r.put("ok", true);
        return r;
    }

    /** Save the admin-only individual memo. */
    @PutMapping("/{id}/internal-memo")
    public Map<String, Object> updateInternalMemo(@PathVariable Long id, @RequestBody Map<String, String> body) {
        CrmUser u = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        u.setInternalMemo(body.get("internalMemo"));
        userRepository.save(u);
        Map<String, Object> r = new HashMap<>();
        r.put("ok", true);
        return r;
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
