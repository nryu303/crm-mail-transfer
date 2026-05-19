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

    public List<HomeHtml> listAll() {
        return repository.findAllByOrderByUpdatedAtDesc();
    }

    public Optional<HomeHtml> findById(Long id) {
        return repository.findById(id);
    }

    public Optional<HomeHtml> findActive() {
        return repository.findFirstByIsActiveTrue();
    }

    @Transactional
    public HomeHtml create(String name, String htmlContent, boolean active) {
        HomeHtml h = new HomeHtml();
        h.setName(name);
        h.setHtmlContent(htmlContent);
        h.setIsActive(active);
        HomeHtml saved = repository.save(h);
        if (active) repository.clearActiveExcept(saved.getId());
        return saved;
    }

    @Transactional
    public HomeHtml update(Long id, String name, String htmlContent) {
        HomeHtml h = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("home html not found: " + id));
        h.setName(name);
        h.setHtmlContent(htmlContent);
        return repository.save(h);
    }

    @Transactional
    public void activate(Long id) {
        HomeHtml h = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("home html not found: " + id));
        h.setIsActive(true);
        repository.save(h);
        repository.clearActiveExcept(id);
    }

    /** Clear every active flag — used by "現在のホームを使わない (管理画面にリダイレクト)" toggle. */
    @Transactional
    public void deactivateAll() {
        for (HomeHtml h : repository.findAll()) {
            if (Boolean.TRUE.equals(h.getIsActive())) {
                h.setIsActive(false);
                repository.save(h);
            }
        }
    }

    @Transactional
    public void delete(Long id) {
        repository.deleteById(id);
    }
}
