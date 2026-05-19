package com.crm.service;

import com.crm.entity.HomeHtml;
import com.crm.repository.HomeHtmlRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * CRUD + activation for the root-path HTML variants. The "currently displayed" row is
 * whichever record has {@code isActive=true}; {@link #activate} enforces uniqueness by
 * clearing the flag on every other row in the same transaction.
 *
 * Why the feature exists: by default hitting https://&lt;host&gt;/ redirected to the admin
 * login, which exposed the management surface publicly. The client asked for a way to
 * stash several landing-page HTML variants and pick one as the live homepage; the rest
 * stay around as drafts that can be previewed and swapped in later.
 */
@Service
public class HomeHtmlService {

    private final HomeHtmlRepository repository;

    public HomeHtmlService(HomeHtmlRepository repository) {
        this.repository = repository;
    }

    /** Number of fixed slots shown on the settings page (パターン1〜N). */
    public static final int SLOT_COUNT = 3;

    /**
     * Ensure exactly {@link #SLOT_COUNT} rows exist and return them in stable id-ASC order
     * (パターン1 = oldest id, パターン2 = next, etc.). On first load this seeds three blank
     * "パターン{n}" rows; existing rows are reused so admins never lose previously-saved HTML
     * because of a reseed. The settings page edits these in place rather than CRUDing new
     * records, so the on-screen slot count stays fixed at three.
     */
    @Transactional
    public List<HomeHtml> listSlots() {
        List<HomeHtml> existing = repository.findAll(org.springframework.data.domain.Sort.by("id"));
        while (existing.size() < SLOT_COUNT) {
            HomeHtml h = new HomeHtml();
            h.setName("パターン" + (existing.size() + 1));
            h.setHtmlContent("");
            h.setIsActive(Boolean.FALSE);
            existing.add(repository.save(h));
        }
        return existing.subList(0, SLOT_COUNT);
    }

    /**
     * Bulk-save the three editable slots from the settings page. Each (id, name, htmlContent)
     * triple updates the matching row in place; {@code activeId} (nullable) marks which row
     * should be marked is_active=true — every other row's flag is cleared in the same tx, so
     * the "one active row" invariant holds. id values that don't belong to an existing row
     * are ignored, so a stale form POST after an admin manually deleted a row can't corrupt
     * data.
     */
    @Transactional
    public void saveSlots(List<Long> ids, List<String> names, List<String> htmlContents, Long activeId) {
        if (ids == null) return;
        int n = ids.size();
        for (int i = 0; i < n; i++) {
            Long id = ids.get(i);
            if (id == null) continue;
            Optional<HomeHtml> opt = repository.findById(id);
            if (!opt.isPresent()) continue;
            HomeHtml h = opt.get();
            if (names != null && i < names.size()) {
                String name = names.get(i);
                h.setName((name == null || name.trim().isEmpty()) ? ("パターン" + (i + 1)) : name.trim());
            }
            if (htmlContents != null && i < htmlContents.size()) {
                h.setHtmlContent(htmlContents.get(i) == null ? "" : htmlContents.get(i));
            }
            h.setIsActive(activeId != null && activeId.equals(id));
            repository.save(h);
        }
        // Belt-and-braces: if activeId points to a row we DID see, make sure every other
        // row is cleared even if it wasn't in this submit (e.g. a manually-created 4th row
        // outside the 3-slot UI).
        if (activeId != null) repository.clearActiveExcept(activeId);
    }

    public Optional<HomeHtml> findActive() {
        return repository.findFirstByIsActiveTrue();
    }
}
