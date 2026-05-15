package com.crm.controller;

import com.crm.entity.SharedMemo;
import com.crm.interceptor.AuthInterceptor;
import com.crm.repository.SharedMemoRepository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import javax.servlet.http.HttpSession;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** JSON CRUD for the shared memo panel on the dashboard. */
@RestController
@RequestMapping("/manager/api/shared-memos")
public class SharedMemoController {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm");

    private final SharedMemoRepository repo;

    public SharedMemoController(SharedMemoRepository repo) { this.repo = repo; }

    @GetMapping
    public List<Map<String, Object>> list() {
        return repo.findAllByOrderByUpdatedAtDesc().stream().map(SharedMemoController::toMap)
                .collect(Collectors.toList());
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, String> body, HttpSession session) {
        String title = trim(body.get("title"));
        String content = body.get("content");
        if (content != null) content = content.trim();
        if ((title == null || title.isEmpty()) && (content == null || content.isEmpty())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "title or content required");
        }
        SharedMemo m = new SharedMemo();
        m.setTitle(title);
        m.setContent(content);
        m.setAdminUserId((Long) session.getAttribute(AuthInterceptor.SESSION_ADMIN_ID));
        return toMap(repo.save(m));
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable Long id, @RequestBody Map<String, String> body) {
        SharedMemo m = repo.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (body.containsKey("title"))   m.setTitle(trim(body.get("title")));
        if (body.containsKey("content")) m.setContent(body.get("content"));
        return toMap(repo.save(m));
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable Long id) {
        if (!repo.existsById(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        repo.deleteById(id);
        Map<String, Object> r = new HashMap<>();
        r.put("ok", true);
        return r;
    }

    private static String trim(String s) { return s == null ? null : s.trim(); }

    private static Map<String, Object> toMap(SharedMemo m) {
        Map<String, Object> out = new HashMap<>();
        out.put("id", m.getId());
        out.put("title", m.getTitle());
        out.put("content", m.getContent());
        out.put("createdAt", m.getCreatedAt() == null ? null : m.getCreatedAt().format(FMT));
        out.put("updatedAt", m.getUpdatedAt() == null ? null : m.getUpdatedAt().format(FMT));
        return out;
    }
}
