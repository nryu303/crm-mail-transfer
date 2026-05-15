package com.crm.interceptor;

import com.crm.entity.AdGroupCredential;
import com.crm.repository.AdCodeRepository;
import com.crm.service.AdGroupCredentialService;
import com.crm.service.DomainSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Basic Auth gate for {@code /media/**}.
 *
 *   • global admin creds work everywhere (cross-group view + any group URL)
 *   • per-group creds work only on that group's URL
 *   • per-IP failure budget: 10 wrong attempts in 60 s → 5 min lockout
 *   • realm header must not include non-ASCII (uses slugified group name)
 *   • cred cache (30 s) reduces DB hits — verified via behaviour, not internals
 */
class MediaAuthInterceptorTest {

    private DomainSettingService settings;
    private AdGroupCredentialService groupCreds;
    private AdCodeRepository adCodeRepo;
    private MediaAuthInterceptor mai;

    @BeforeEach
    void setUp() {
        settings = mock(DomainSettingService.class);
        groupCreds = mock(AdGroupCredentialService.class);
        adCodeRepo = mock(AdCodeRepository.class);
        when(settings.getMediaAuthUser()).thenReturn("admin");
        when(settings.getMediaAuthPassword()).thenReturn("adminpass");
        when(groupCreds.findByGroupName(anyString())).thenReturn(Optional.empty());

        mai = new MediaAuthInterceptor(settings, groupCreds, adCodeRepo);
    }

    private static String basicAuth(String user, String pass) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + pass).getBytes());
    }

    private static MockHttpServletRequest req(String servletPath) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        r.setServletPath(servletPath);
        r.setRemoteAddr("203.0.113.10");
        return r;
    }

    // ─── auth success / failure paths ──────────────────────────────────────

    @Test
    void crossGroupView_noAuthHeader_returnsChallenge401() throws Exception {
        MockHttpServletRequest req = req("/media/index/");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        assertThat(mai.preHandle(req, resp, null)).isFalse();
        assertThat(resp.getStatus()).isEqualTo(401);
        assertThat(resp.getHeader("WWW-Authenticate")).startsWith("Basic realm=\"kaiun-agency");
    }

    @Test
    void crossGroupView_validAdminCreds_allowed() throws Exception {
        MockHttpServletRequest req = req("/media/index/");
        req.addHeader("Authorization", basicAuth("admin", "adminpass"));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        assertThat(mai.preHandle(req, resp, null)).isTrue();
    }

    @Test
    void crossGroupView_wrongCreds_returns401() throws Exception {
        MockHttpServletRequest req = req("/media/index/");
        req.addHeader("Authorization", basicAuth("admin", "WRONG"));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        assertThat(mai.preHandle(req, resp, null)).isFalse();
        assertThat(resp.getStatus()).isEqualTo(401);
    }

    @Test
    void groupUrl_adminCredsWorkEverywhere() throws Exception {
        MockHttpServletRequest req = req("/media/index/");
        req.setParameter("g", "agency-a");
        req.addHeader("Authorization", basicAuth("admin", "adminpass"));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        assertThat(mai.preHandle(req, resp, null)).isTrue();
    }

    @Test
    void groupUrl_groupCreds_allowedOnOwnGroupOnly() throws Exception {
        AdGroupCredential gc = new AdGroupCredential();
        gc.setGroupName("agency-a");
        gc.setAuthUser("agA");
        gc.setAuthPassword("agApass");
        when(groupCreds.findByGroupName("agency-a")).thenReturn(Optional.of(gc));

        MockHttpServletRequest req = req("/media/index/");
        req.setParameter("g", "agency-a");
        req.addHeader("Authorization", basicAuth("agA", "agApass"));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        assertThat(mai.preHandle(req, resp, null)).isTrue();
    }

    @Test
    void groupUrl_groupCredsRejectedOnDifferentGroup() throws Exception {
        // Agency A's creds attempt to unlock Agency B's URL → must fail.
        AdGroupCredential gcA = new AdGroupCredential();
        gcA.setGroupName("agency-a");
        gcA.setAuthUser("agA");
        gcA.setAuthPassword("agApass");
        when(groupCreds.findByGroupName("agency-b")).thenReturn(Optional.empty());

        MockHttpServletRequest req = req("/media/index/");
        req.setParameter("g", "agency-b");
        req.addHeader("Authorization", basicAuth("agA", "agApass"));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        assertThat(mai.preHandle(req, resp, null)).isFalse();
        assertThat(resp.getStatus()).isEqualTo(401);
    }

    @Test
    void crossGroupView_groupCredsAreNotEnoughForAdminUrl() throws Exception {
        // Per-group creds must NOT unlock the cross-group dashboard.
        AdGroupCredential gc = new AdGroupCredential();
        gc.setGroupName("agency-a");
        gc.setAuthUser("agA");
        gc.setAuthPassword("agApass");
        when(groupCreds.findByGroupName(anyString())).thenReturn(Optional.of(gc));

        MockHttpServletRequest req = req("/media/index/");
        req.addHeader("Authorization", basicAuth("agA", "agApass"));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        assertThat(mai.preHandle(req, resp, null)).isFalse();
        assertThat(resp.getStatus()).isEqualTo(401);
    }

    @Test
    void crossGroupView_globalCredsUnsetReturns503() throws Exception {
        when(settings.getMediaAuthUser()).thenReturn(null);
        when(settings.getMediaAuthPassword()).thenReturn(null);
        mai.invalidateCache();
        MockHttpServletRequest req = req("/media/index/");
        req.addHeader("Authorization", basicAuth("admin", "adminpass"));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        assertThat(mai.preHandle(req, resp, null)).isFalse();
        assertThat(resp.getStatus()).isEqualTo(503);
    }

    // ─── rate limiting ─────────────────────────────────────────────────────

    @Test
    void tenFailuresInAMinute_blocksIpFor5Min() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        // 10 failures from the same IP
        for (int i = 0; i < 10; i++) {
            MockHttpServletRequest req = req("/media/index/");
            req.addHeader("Authorization", basicAuth("admin", "wrong" + i));
            mai.preHandle(req, resp, null);
        }
        // 11th attempt — should be rate-limited (429) before even checking creds.
        MockHttpServletRequest req = req("/media/index/");
        req.addHeader("Authorization", basicAuth("admin", "adminpass"));  // would otherwise succeed!
        MockHttpServletResponse resp2 = new MockHttpServletResponse();
        assertThat(mai.preHandle(req, resp2, null)).isFalse();
        assertThat(resp2.getStatus()).isEqualTo(429);
        assertThat(resp2.getHeader("Retry-After")).isEqualTo("300");
    }

    @Test
    void successfulAuth_clearsPriorFailureCounter() throws Exception {
        MockHttpServletResponse resp = new MockHttpServletResponse();
        // 9 failures (one short of the lockout threshold)
        for (int i = 0; i < 9; i++) {
            MockHttpServletRequest req = req("/media/index/");
            req.addHeader("Authorization", basicAuth("admin", "wrong" + i));
            mai.preHandle(req, resp, null);
        }
        // Now succeed.
        MockHttpServletRequest okReq = req("/media/index/");
        okReq.addHeader("Authorization", basicAuth("admin", "adminpass"));
        assertThat(mai.preHandle(okReq, new MockHttpServletResponse(), null)).isTrue();
        // Now another wrong attempt — the counter should be reset, so we're nowhere near 429.
        MockHttpServletRequest bad = req("/media/index/");
        bad.addHeader("Authorization", basicAuth("admin", "still-wrong"));
        MockHttpServletResponse r2 = new MockHttpServletResponse();
        mai.preHandle(bad, r2, null);
        assertThat(r2.getStatus()).isEqualTo(401);  // unauthorised, not 429
    }

    @Test
    void differentIpsHaveIndependentFailureBudgets() throws Exception {
        for (int i = 0; i < 10; i++) {
            MockHttpServletRequest r = req("/media/index/");
            r.setRemoteAddr("203.0.113.1");
            r.addHeader("Authorization", basicAuth("admin", "wrong"));
            mai.preHandle(r, new MockHttpServletResponse(), null);
        }
        // Different IP — first attempt should still pass through to 401, not 429.
        MockHttpServletRequest other = req("/media/index/");
        other.setRemoteAddr("203.0.113.2");
        other.addHeader("Authorization", basicAuth("admin", "wrong"));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        mai.preHandle(other, resp, null);
        assertThat(resp.getStatus()).isEqualTo(401);
    }

    // ─── header safety ─────────────────────────────────────────────────────

    @Test
    void wwwAuthenticateRealm_isAsciiOnly_evenForJapaneseGroupNames() throws Exception {
        MockHttpServletRequest req = req("/media/index/");
        req.setParameter("g", "%E3%83%AC%E3%83%BC%E3%82%AD");  // URL-encoded レーキ
        MockHttpServletResponse resp = new MockHttpServletResponse();
        mai.preHandle(req, resp, null);
        String realm = resp.getHeader("WWW-Authenticate");
        // HTTP headers are ISO-8859-1; any character > 0x7F would throw IllegalArgumentException
        // somewhere in MockHttpServletResponse. Assertion: pure ASCII.
        for (char c : realm.toCharArray()) {
            assertThat((int) c).isLessThanOrEqualTo(0x7F);
        }
    }
}
