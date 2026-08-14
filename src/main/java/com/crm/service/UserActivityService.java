package com.crm.service;

import com.crm.entity.CrmUser;
import com.crm.entity.UserAccessLog;
import com.crm.repository.CrmUserRepository;
import com.crm.repository.UserAccessLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Updates {@link CrmUser#getLastLoginAt()} on activity (reply-page view, reply submit,
 * inbound email received).
 *
 * <p>2026-08-06: no longer throttled. An earlier revision skipped the write when the previous
 * value was under 1 hour old (to avoid hammering the row on repeated page refreshes), but the
 * operator wants 最終ログイン to always reflect the literal most-recent login — a user who
 * re-opened their reply link twice within the same hour was showing the OLDER timestamp,
 * which read as broken/stuck. Every genuine hit (bot UAs are already filtered by the caller)
 * now updates the row, matching {@link UserAccessLog}, which was already unthrottled.
 *
 * <p>Also appends a {@link UserAccessLog} row (アクセスログ) so the operator can see the full
 * click history on the user detail screen, not just the single most-recent timestamp.
 */
@Service
public class UserActivityService {

    private static final Logger log = LoggerFactory.getLogger(UserActivityService.class);

    private final CrmUserRepository userRepository;
    private final UserAccessLogRepository accessLogRepository;

    public UserActivityService(CrmUserRepository userRepository,
                                UserAccessLogRepository accessLogRepository) {
        this.userRepository = userRepository;
        this.accessLogRepository = accessLogRepository;
    }

    /** Record activity for this user (no-op if user is null). */
    public void touchLastLogin(CrmUser user) {
        touchLastLoginOnly(user);
    }

    /**
     * Record activity for this user AND append an access-log row with the request's source/IP/UA.
     * Use from any HTTP-facing click path (reply page view/submit, future short-link redirect).
     */
    public void touchLastLogin(CrmUser user, String source, String ipAddress, String userAgent) {
        touchLastLogin(user, source, ipAddress, userAgent, null);
    }

    /**
     * Same as the 4-arg overload but also records which Host header the click arrived on
     * (domainHost) — lets the 外部リンクドメイン生成 settings page show, per registered domain,
     * how many clicks/which users came through it.
     */
    public void touchLastLogin(CrmUser user, String source, String ipAddress, String userAgent,
                                String domainHost) {
        if (user == null) return;
        touchLastLoginOnly(user);

        UserAccessLog entry = new UserAccessLog();
        entry.setUserId(user.getId());
        entry.setSource(source);
        entry.setIpAddress(truncate(ipAddress, 64));
        entry.setUserAgent(truncate(userAgent, 500));
        entry.setDomainHost(truncate(domainHost, 255));
        accessLogRepository.save(entry);
    }

    private void touchLastLoginOnly(CrmUser user) {
        if (user == null) return;
        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);
        log.debug("lastLoginAt updated for user {}", user.getId());
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
