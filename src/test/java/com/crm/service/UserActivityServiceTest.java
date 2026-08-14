package com.crm.service;

import com.crm.entity.CrmUser;
import com.crm.entity.UserAccessLog;
import com.crm.repository.CrmUserRepository;
import com.crm.repository.UserAccessLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link UserActivityService} has two write paths on the same click, both unthrottled
 * (2026-08-06): a lastLoginAt overwrite, and a USER_ACCESS_LOG insert (アクセスログ) so the
 * operator can see full click history, not just the most recent hit.
 */
class UserActivityServiceTest {

    private CrmUserRepository userRepository;
    private UserAccessLogRepository accessLogRepository;
    private UserActivityService svc;

    @BeforeEach
    void setUp() {
        userRepository = mock(CrmUserRepository.class);
        accessLogRepository = mock(UserAccessLogRepository.class);
        svc = new UserActivityService(userRepository, accessLogRepository);
    }

    private static CrmUser userWithLastLogin(LocalDateTime lastLoginAt) {
        CrmUser u = new CrmUser();
        u.setId(1L);
        u.setLastLoginAt(lastLoginAt);
        return u;
    }

    @Test
    void touchLastLoginWithSource_firstVisit_writesLastLoginAndAccessLog() {
        CrmUser user = userWithLastLogin(null);

        svc.touchLastLogin(user, UserAccessLog.SOURCE_REPLY_VIEW, "203.0.113.5", "Mozilla/5.0");

        assertThat(user.getLastLoginAt()).isNotNull();
        verify(userRepository).save(user);

        ArgumentCaptor<UserAccessLog> cap = ArgumentCaptor.forClass(UserAccessLog.class);
        verify(accessLogRepository).save(cap.capture());
        UserAccessLog saved = cap.getValue();
        assertThat(saved.getUserId()).isEqualTo(1L);
        assertThat(saved.getSource()).isEqualTo(UserAccessLog.SOURCE_REPLY_VIEW);
        assertThat(saved.getIpAddress()).isEqualTo("203.0.113.5");
        assertThat(saved.getUserAgent()).isEqualTo("Mozilla/5.0");
    }

    @Test
    void touchLastLoginWithSource_repeatedVisitMinutesLater_stillUpdatesLastLogin() {
        // 2026-08-06 regression test: 最終ログイン must always reflect the literal most-recent
        // login. A user who re-opens their reply link minutes (not hours) after their first
        // visit was previously stuck showing the OLDER timestamp (1h throttle) — the operator
        // reported this as looking broken/stuck ("再度ログインしても最終ログインが更新されない").
        LocalDateTime recentLogin = LocalDateTime.now().minusMinutes(5);
        CrmUser user = userWithLastLogin(recentLogin);

        svc.touchLastLogin(user, UserAccessLog.SOURCE_REPLY_SUBMIT, "203.0.113.9", "curl/8.0");

        assertThat(user.getLastLoginAt()).isAfter(recentLogin);
        verify(userRepository).save(user);
        verify(accessLogRepository).save(any(UserAccessLog.class));
    }

    @Test
    void touchLastLogin_nullUser_noop() {
        svc.touchLastLogin(null, UserAccessLog.SOURCE_REPLY_VIEW, "1.2.3.4", "ua");
        verify(userRepository, never()).save(any(CrmUser.class));
        verify(accessLogRepository, never()).save(any(UserAccessLog.class));
    }

    @Test
    void legacyTouchLastLogin_doesNotWriteAccessLog() {
        CrmUser user = userWithLastLogin(null);

        svc.touchLastLogin(user);

        verify(userRepository).save(user);
        verify(accessLogRepository, never()).save(any(UserAccessLog.class));
    }
}
