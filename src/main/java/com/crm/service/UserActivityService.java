package com.crm.service;

import com.crm.entity.CrmUser;
import com.crm.repository.CrmUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Updates {@link CrmUser#getLastLoginAt()} on activity (reply-page view, reply submit,
 * inbound email received) with a built-in throttle so we don't write to the row on every
 * page refresh.
 */
@Service
public class UserActivityService {

    /** Minimum interval between successive lastLoginAt writes for the same user. */
    private static final Duration THROTTLE = Duration.ofHours(1);

    private static final Logger log = LoggerFactory.getLogger(UserActivityService.class);

    private final CrmUserRepository userRepository;

    public UserActivityService(CrmUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** Record activity for this user. No-op if the previous write is within the throttle window. */
    public void touchLastLogin(CrmUser user) {
        if (user == null) return;
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime prev = user.getLastLoginAt();
        if (prev != null && Duration.between(prev, now).compareTo(THROTTLE) < 0) {
            return; // still within the throttle window — skip the write
        }
        user.setLastLoginAt(now);
        userRepository.save(user);
        log.debug("lastLoginAt updated for user {}", user.getId());
    }
}
