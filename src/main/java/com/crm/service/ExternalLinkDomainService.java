package com.crm.service;

import com.crm.dto.ExternalLinkDomainForm;
import com.crm.entity.CrmUser;
import com.crm.entity.ExternalLinkDomain;
import com.crm.repository.CrmUserRepository;
import com.crm.repository.ExternalLinkDomainRepository;
import com.crm.repository.UserAccessLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 外部リンクドメイン生成: manages the pool of external domains used to build per-message
 * short URLs (see {@link DomainSettingService#buildReplyUrl(String)}).
 *
 * Exactly one domain may be 使用中 (active) at a time, mirroring the single-active-relay
 * invariant in {@link RelayServerService}. Activating a row demotes any other active row.
 */
@Service
public class ExternalLinkDomainService {

    private static final Logger log = LoggerFactory.getLogger(ExternalLinkDomainService.class);

    private final ExternalLinkDomainRepository repository;
    private final UserAccessLogRepository accessLogRepository;
    private final CrmUserRepository userRepository;
    private final ExternalLinkDomainCertService certService;

    public ExternalLinkDomainService(ExternalLinkDomainRepository repository,
                                     UserAccessLogRepository accessLogRepository,
                                     CrmUserRepository userRepository,
                                     ExternalLinkDomainCertService certService) {
        this.repository = repository;
        this.accessLogRepository = accessLogRepository;
        this.userRepository = userRepository;
        this.certService = certService;
    }

    public List<ExternalLinkDomain> listAll() {
        return repository.findAllByOrderByDomainUrlAsc();
    }

    public Optional<ExternalLinkDomain> findById(Long id) {
        return repository.findById(id);
    }

    /** The domain currently in use for short-URL generation, if any. */
    public Optional<ExternalLinkDomain> findActive() {
        return repository.findFirstByIsActiveTrue();
    }

    /**
     * Resolve the registered row whose bare host matches the given Host header value —
     * used at click time (see ReplyPageController) to decide the post-access-log behaviour
     * (REPLY_FORM / REDIRECT / CUSTOM_HTML) for THIS click, since a token may have been
     * generated while a different domain was active than the one currently serving it.
     */
    public Optional<ExternalLinkDomain> findByHost(String host) {
        if (host == null || host.trim().isEmpty()) return Optional.empty();
        String needle = host.trim();
        return repository.findByDomainUrlContainingIgnoreCase(needle).stream()
                .filter(d -> needle.equalsIgnoreCase(hostOf(d.getDomainUrl())))
                .findFirst();
    }

    @Transactional
    public ExternalLinkDomain create(ExternalLinkDomainForm form) {
        String domainUrl = normalized(form.getDomainUrl());
        if (domainUrl != null && repository.existsByDomainUrl(domainUrl)) {
            throw new DuplicateDomainException(domainUrl);
        }
        ExternalLinkDomain d = new ExternalLinkDomain();
        form.applyTo(d);
        ExternalLinkDomain saved = repository.save(d);
        if (Boolean.TRUE.equals(saved.getIsActive())) {
            deactivateAllExcept(saved.getId());
        }
        requestCertIfNeeded(saved);
        return saved;
    }

    @Transactional
    public ExternalLinkDomain update(Long id, ExternalLinkDomainForm form) {
        ExternalLinkDomain d = repository.findById(id)
                .orElseThrow(() -> new NotFoundException(id));
        String newDomainUrl = normalized(form.getDomainUrl());
        if (newDomainUrl != null && !newDomainUrl.equals(d.getDomainUrl())
                && repository.existsByDomainUrl(newDomainUrl)) {
            throw new DuplicateDomainException(newDomainUrl);
        }
        form.applyTo(d);
        ExternalLinkDomain saved = repository.save(d);
        if (Boolean.TRUE.equals(saved.getIsActive())) {
            deactivateAllExcept(saved.getId());
        }
        return saved;
    }

    /** Activate this domain (使用中 に切替) and demote every other row to 未使用. */
    @Transactional
    public ExternalLinkDomain activate(Long id) {
        ExternalLinkDomain d = repository.findById(id)
                .orElseThrow(() -> new NotFoundException(id));
        d.setIsActive(true);
        ExternalLinkDomain saved = repository.save(d);
        deactivateAllExcept(saved.getId());
        requestCertIfNeeded(saved);
        return saved;
    }

    /** Re-issue/renew the cert for this domain on demand (settings page "証明書を発行" button). */
    @Transactional
    public void requestCert(Long id) {
        repository.findById(id).ifPresent(d -> {
            d.setCertStatus("PENDING");
            repository.save(d);
            requestCertIfNeeded(d);
        });
    }

    /**
     * Current cert-issuance status for a domain: PENDING / SUCCESS / FAILED_*.
     *
     * <p>The root-side script's result lives in an ephemeral /tmp file (see
     * {@link ExternalLinkDomainCertService}) that is NOT guaranteed to survive — 2026-08-05:
     * a cert had genuinely succeeded, but the only record of that was the /tmp file, which
     * disappeared before anyone checked the settings page, leaving it stuck showing 発行待ち
     * forever with no way to tell "never requested" apart from "succeeded, but we lost the
     * receipt." Fix: once the /tmp file reports a definitive outcome, persist it onto the row
     * (durable, survives /tmp cleanup / reboots). If the /tmp file is gone or still pending,
     * fall back to whatever was last durably recorded instead of resetting to PENDING.
     */
    @Transactional
    public String certStatus(ExternalLinkDomain domain) {
        String host = hostOf(domain.getDomainUrl());
        if (host == null) return "PENDING";
        String live = certService.status(host);
        if (!"PENDING".equals(live)) {
            if (!live.equals(domain.getCertStatus())) {
                domain.setCertStatus(live);
                repository.save(domain);
            }
            return live;
        }
        return domain.getCertStatus() != null ? domain.getCertStatus() : "PENDING";
    }

    private void requestCertIfNeeded(ExternalLinkDomain d) {
        String host = hostOf(d.getDomainUrl());
        if (host != null) certService.requestCertificate(host);
    }

    /**
     * Daily at 04:00 — re-request a cert for any registered domain that has never
     * successfully obtained one (certStatus NULL/PENDING/FAILED_*). Covers two cases:
     *   1. Domains registered before this auto-TLS feature existed (2026-08-04, five rows
     *      created hours before the feature shipped never had a request staged at all).
     *   2. A transient certbot/nginx failure (rate limit, momentary DNS blip) that would
     *      otherwise sit FAILED forever without an operator noticing and clicking 再発行.
     * Skips domains already SUCCESS — Let's Encrypt certs are valid ~90 days and renewal
     * is out of scope here (this endpoint is about first-issue reliability, not renewal).
     */
    @Scheduled(cron = "0 0 4 * * *")
    public void retryPendingCertRequests() {
        int requested = 0;
        for (ExternalLinkDomain d : repository.findAll()) {
            if ("SUCCESS".equals(d.getCertStatus())) continue;
            String host = hostOf(d.getDomainUrl());
            if (host == null) continue;
            certService.requestCertificate(host);
            requested++;
        }
        if (requested > 0) {
            log.info("外部リンクドメイン cert retry sweep: re-requested {} domain(s)", requested);
        }
    }

    /** Turn the given domain off with no replacement becoming active. */
    @Transactional
    public ExternalLinkDomain deactivate(Long id) {
        ExternalLinkDomain d = repository.findById(id)
                .orElseThrow(() -> new NotFoundException(id));
        d.setIsActive(false);
        return repository.save(d);
    }

    private void deactivateAllExcept(Long keepId) {
        for (ExternalLinkDomain other : repository.findAll()) {
            if (other.getId().equals(keepId)) continue;
            if (Boolean.TRUE.equals(other.getIsActive())) {
                other.setIsActive(false);
                repository.save(other);
            }
        }
    }

    @Transactional
    public void delete(Long id) {
        repository.deleteById(id);
    }

    /** 正規のアクセス数: distinct users who clicked a link served under this domain's host
     *  (not raw hit count — a user clicking twice still counts once). Matches exactly what
     *  {@link #listAccessedUsers(ExternalLinkDomain)} lists, and drops to 0 once the access
     *  history for this domain is deleted (see {@link #deleteAccessHistory(ExternalLinkDomain)}). */
    public long countAccesses(ExternalLinkDomain domain) {
        String host = hostOf(domain.getDomainUrl());
        if (host == null) return 0L;
        return accessLogRepository.countDistinctUserIdsByDomainHost(host);
    }

    /** Permanently deletes every USER_ACCESS_LOG row recorded under this domain's host.
     *  アクセス数 and アクセスユーザー both immediately reflect the deletion (0 / empty). */
    @Transactional
    public int deleteAccessHistory(ExternalLinkDomain domain) {
        String host = hostOf(domain.getDomainUrl());
        if (host == null) return 0;
        return accessLogRepository.deleteByDomainHost(host);
    }

    /**
     * Users who clicked a link while it was served under this domain's host, sorted by
     * lastLoginAt descending — same ログイン順 as the user-management list (2026-08-08,
     * previously sorted by access order and only showed 氏名/電話番号/アドレス; the operator
     * asked for this to match ユーザー管理's columns and sort exactly).
     */
    public List<AccessedUser> listAccessedUsers(ExternalLinkDomain domain) {
        String host = hostOf(domain.getDomainUrl());
        List<AccessedUser> out = new ArrayList<>();
        if (host == null) return out;
        for (Long userId : accessLogRepository.findDistinctUserIdsByDomainHost(host)) {
            userRepository.findById(userId).ifPresent(u -> out.add(new AccessedUser(u)));
        }
        out.sort((a, b) -> {
            java.time.LocalDateTime la = a.getLastLoginAt();
            java.time.LocalDateTime lb = b.getLastLoginAt();
            if (la == null && lb == null) return 0;
            if (la == null) return 1;   // nulls (never logged in) sort last
            if (lb == null) return -1;
            return lb.compareTo(la);    // descending: most recent login first
        });
        return out;
    }

    /** Strip scheme (http:// or https://) from a stored domain URL to get the bare host,
     *  matching what {@code HttpServletRequest#getServerName()} returns at click time. */
    private static String hostOf(String domainUrl) {
        if (domainUrl == null) return null;
        String t = domainUrl.trim();
        int schemeEnd = t.indexOf("://");
        if (schemeEnd >= 0) t = t.substring(schemeEnd + 3);
        t = t.replaceAll("/.*$", "");
        return t.isEmpty() ? null : t;
    }

    private static String normalized(String raw) {
        if (raw == null) return null;
        String t = raw.trim().replaceAll("/+$", "");
        return t.isEmpty() ? null : t;
    }

    public static class AccessedUser {
        private final Long userId;
        private final String displayName;
        private final String phoneNumber;
        private final String email;
        private final String adCode;
        private final String folder;
        private final java.time.LocalDateTime createdAt;
        private final java.time.LocalDateTime lastLoginAt;

        public AccessedUser(CrmUser u) {
            this.userId = u.getId();
            this.displayName = u.getDisplayName();
            this.phoneNumber = u.getPhoneNumber();
            this.email = u.getEmail();
            this.adCode = u.getAdCode();
            this.folder = u.getFolder();
            this.createdAt = u.getCreatedAt();
            this.lastLoginAt = u.getLastLoginAt();
        }

        public Long getUserId() { return userId; }
        public String getDisplayName() { return displayName; }
        public String getPhoneNumber() { return phoneNumber; }
        public String getEmail() { return email; }
        public String getAdCode() { return adCode; }
        public String getFolder() { return folder; }
        public java.time.LocalDateTime getCreatedAt() { return createdAt; }
        public java.time.LocalDateTime getLastLoginAt() { return lastLoginAt; }
    }

    public static class DuplicateDomainException extends RuntimeException {
        public DuplicateDomainException(String domainUrl) { super("duplicate external link domain: " + domainUrl); }
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(Long id) { super("external link domain not found: " + id); }
    }
}
