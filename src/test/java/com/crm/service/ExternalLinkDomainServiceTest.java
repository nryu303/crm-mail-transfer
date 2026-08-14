package com.crm.service;

import com.crm.dto.ExternalLinkDomainForm;
import com.crm.entity.ExternalLinkDomain;
import com.crm.repository.CrmUserRepository;
import com.crm.repository.ExternalLinkDomainRepository;
import com.crm.repository.UserAccessLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ExternalLinkDomainService} — the single-active invariant mirrors
 * {@link RelayServerServiceTest}: exactly one 外部リンクドメイン may be 使用中 at a time,
 * since {@link DomainSettingService#buildReplyUrl(String)} reads a single active row.
 */
class ExternalLinkDomainServiceTest {

    private ExternalLinkDomainRepository repo;
    private UserAccessLogRepository accessLogRepo;
    private CrmUserRepository userRepo;
    private ExternalLinkDomainCertService certService;
    private ExternalLinkDomainService svc;

    @BeforeEach
    void setUp() {
        repo = mock(ExternalLinkDomainRepository.class);
        accessLogRepo = mock(UserAccessLogRepository.class);
        userRepo = mock(CrmUserRepository.class);
        certService = mock(ExternalLinkDomainCertService.class);
        when(repo.save(any(ExternalLinkDomain.class))).thenAnswer(inv -> {
            ExternalLinkDomain d = inv.getArgument(0);
            if (d.getId() == null) d.setId(99L);
            return d;
        });
        svc = new ExternalLinkDomainService(repo, accessLogRepo, userRepo, certService);
    }

    private static ExternalLinkDomain existing(Long id, String domainUrl, boolean active) {
        ExternalLinkDomain d = new ExternalLinkDomain();
        d.setId(id);
        d.setDomainUrl(domainUrl);
        d.setIsActive(active);
        return d;
    }

    private static ExternalLinkDomainForm formActive(String domainUrl, boolean active) {
        ExternalLinkDomainForm f = new ExternalLinkDomainForm();
        f.setDomainUrl(domainUrl);
        f.setIsActive(active);
        return f;
    }

    @Test
    void create_active_deactivatesAllOtherActiveRows() {
        when(repo.existsByDomainUrl(anyString())).thenReturn(false);
        ExternalLinkDomain existingActive1 = existing(1L, "https://a.jp", true);
        ExternalLinkDomain existingActive2 = existing(2L, "https://b.jp", true);
        ExternalLinkDomain existingInactive = existing(3L, "https://c.jp", false);
        when(repo.findAll()).thenReturn(Arrays.asList(existingActive1, existingActive2, existingInactive));

        svc.create(formActive("https://new.jp", true));

        ArgumentCaptor<ExternalLinkDomain> savedCap = ArgumentCaptor.forClass(ExternalLinkDomain.class);
        verify(repo, atLeast(3)).save(savedCap.capture());
        assertThat(savedCap.getAllValues())
                .filteredOn(d -> d.getId() != null && (d.getId().equals(1L) || d.getId().equals(2L)))
                .allMatch(d -> Boolean.FALSE.equals(d.getIsActive()));
    }

    @Test
    void create_inactive_leavesOthersAlone() {
        when(repo.existsByDomainUrl(anyString())).thenReturn(false);
        when(repo.findAll()).thenReturn(Arrays.asList(existing(1L, "https://a.jp", true)));

        svc.create(formActive("https://new.jp", false));
        verify(repo, never()).findAll();
    }

    @Test
    void update_promotingToActive_deactivatesAllOtherActiveRows() {
        when(repo.findById(2L)).thenReturn(Optional.of(existing(2L, "https://b.jp", false)));
        when(repo.existsByDomainUrl(anyString())).thenReturn(false);
        ExternalLinkDomain existingActive = existing(1L, "https://a.jp", true);
        ExternalLinkDomain subject = existing(2L, "https://b.jp", false);
        when(repo.findAll()).thenReturn(Arrays.asList(existingActive, subject));

        svc.update(2L, formActive("https://b.jp", true));

        ArgumentCaptor<ExternalLinkDomain> savedCap = ArgumentCaptor.forClass(ExternalLinkDomain.class);
        verify(repo, atLeast(2)).save(savedCap.capture());
        assertThat(savedCap.getAllValues())
                .filteredOn(d -> d.getId() != null && d.getId().equals(1L))
                .allMatch(d -> Boolean.FALSE.equals(d.getIsActive()));
    }

    @Test
    void update_demotingToInactive_doesNotTouchOthers() {
        when(repo.findById(1L)).thenReturn(Optional.of(existing(1L, "https://a.jp", true)));
        when(repo.existsByDomainUrl(anyString())).thenReturn(false);

        svc.update(1L, formActive("https://a.jp", false));
        verify(repo, never()).findAll();
    }

    @Test
    void activate_deactivatesAllOtherActiveRows() {
        ExternalLinkDomain target = existing(2L, "https://b.jp", false);
        when(repo.findById(2L)).thenReturn(Optional.of(target));
        ExternalLinkDomain other = existing(1L, "https://a.jp", true);
        when(repo.findAll()).thenReturn(Arrays.asList(other, target));

        ExternalLinkDomain result = svc.activate(2L);

        assertThat(result.getIsActive()).isTrue();
        ArgumentCaptor<ExternalLinkDomain> savedCap = ArgumentCaptor.forClass(ExternalLinkDomain.class);
        verify(repo, atLeast(2)).save(savedCap.capture());
        assertThat(savedCap.getAllValues())
                .filteredOn(d -> d.getId() != null && d.getId().equals(1L))
                .allMatch(d -> Boolean.FALSE.equals(d.getIsActive()));
    }

    @Test
    void deactivate_doesNotTouchOthers() {
        when(repo.findById(1L)).thenReturn(Optional.of(existing(1L, "https://a.jp", true)));

        ExternalLinkDomain result = svc.deactivate(1L);

        assertThat(result.getIsActive()).isFalse();
        verify(repo, never()).findAll();
    }

    @Test
    void create_duplicateDomain_throwsDuplicateDomainException() {
        when(repo.existsByDomainUrl("https://a.jp")).thenReturn(true);
        assertThatThrownBy(() -> svc.create(formActive("https://a.jp", true)))
                .isInstanceOf(ExternalLinkDomainService.DuplicateDomainException.class);
        verify(repo, never()).save(any(ExternalLinkDomain.class));
    }

    @Test
    void update_nonExistentId_throwsNotFoundException() {
        when(repo.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> svc.update(99L, formActive("https://x.jp", false)))
                .isInstanceOf(ExternalLinkDomainService.NotFoundException.class);
    }

    @Test
    void countAccesses_stripsSchemeBeforeQuerying_andCountsDistinctUsers() {
        // 正規のアクセス数: distinct users, not raw hit count — see UserAccessLogRepository.
        ExternalLinkDomain d = existing(1L, "https://ii5gh9ge.jp", true);
        when(accessLogRepo.countDistinctUserIdsByDomainHost("ii5gh9ge.jp")).thenReturn(7L);

        assertThat(svc.countAccesses(d)).isEqualTo(7L);
    }

    @Test
    void deleteAccessHistory_deletesByHost_andResultReflectsCount() {
        ExternalLinkDomain d = existing(1L, "https://ii5gh9ge.jp", true);
        when(accessLogRepo.deleteByDomainHost("ii5gh9ge.jp")).thenReturn(5);

        assertThat(svc.deleteAccessHistory(d)).isEqualTo(5);
    }

    @Test
    void create_active_requestsCertForNewDomain() {
        when(repo.existsByDomainUrl(anyString())).thenReturn(false);
        when(repo.findAll()).thenReturn(Arrays.asList());

        svc.create(formActive("https://ep84ti.jp", true));

        verify(certService).requestCertificate("ep84ti.jp");
    }

    @Test
    void activate_requestsCertForDomain() {
        ExternalLinkDomain target = existing(2L, "https://ep84ti.jp", false);
        when(repo.findById(2L)).thenReturn(Optional.of(target));
        when(repo.findAll()).thenReturn(Arrays.asList(target));

        svc.activate(2L);

        verify(certService).requestCertificate("ep84ti.jp");
    }

    @Test
    void listAccessedUsers_returnsPhoneAndEmailPerUser() {
        ExternalLinkDomain d = existing(1L, "https://ii5gh9ge.jp", true);
        when(accessLogRepo.findDistinctUserIdsByDomainHost("ii5gh9ge.jp"))
                .thenReturn(Arrays.asList(10L, 20L));

        com.crm.entity.CrmUser u10 = new com.crm.entity.CrmUser();
        u10.setId(10L);
        u10.setDisplayName("Alice");
        u10.setPhoneNumber("090-1111-2222");
        u10.setEmail("alice@example.com");
        when(userRepo.findById(10L)).thenReturn(Optional.of(u10));

        com.crm.entity.CrmUser u20 = new com.crm.entity.CrmUser();
        u20.setId(20L);
        u20.setDisplayName("Bob");
        u20.setPhoneNumber(null);
        u20.setEmail("bob@example.com");
        when(userRepo.findById(20L)).thenReturn(Optional.of(u20));

        java.util.List<ExternalLinkDomainService.AccessedUser> result = svc.listAccessedUsers(d);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getPhoneNumber()).isEqualTo("090-1111-2222");
        assertThat(result.get(1).getEmail()).isEqualTo("bob@example.com");
    }

    /**
     * ログイン順(最終ログイン降順、未ログインは末尾)— 2026-08-08 operator request to match
     * ユーザー管理's sort instead of raw access-log order, plus expose adCode/folder/
     * createdAt/lastLoginAt so the columns can match ユーザー管理 too.
     */
    @Test
    void listAccessedUsers_sortsByLastLoginDescendingWithNullsLast() {
        ExternalLinkDomain d = existing(1L, "https://ii5gh9ge.jp", true);
        when(accessLogRepo.findDistinctUserIdsByDomainHost("ii5gh9ge.jp"))
                .thenReturn(Arrays.asList(10L, 20L, 30L));

        com.crm.entity.CrmUser olderLogin = new com.crm.entity.CrmUser();
        olderLogin.setId(10L);
        olderLogin.setDisplayName("OlderLogin");
        olderLogin.setLastLoginAt(java.time.LocalDateTime.of(2026, 8, 1, 9, 0));
        olderLogin.setAdCode("sms_01");
        olderLogin.setFolder("SMS");
        olderLogin.setCreatedAt(java.time.LocalDateTime.of(2026, 1, 1, 0, 0));
        when(userRepo.findById(10L)).thenReturn(Optional.of(olderLogin));

        com.crm.entity.CrmUser neverLoggedIn = new com.crm.entity.CrmUser();
        neverLoggedIn.setId(20L);
        neverLoggedIn.setDisplayName("NeverLoggedIn");
        neverLoggedIn.setLastLoginAt(null);
        when(userRepo.findById(20L)).thenReturn(Optional.of(neverLoggedIn));

        com.crm.entity.CrmUser recentLogin = new com.crm.entity.CrmUser();
        recentLogin.setId(30L);
        recentLogin.setDisplayName("RecentLogin");
        recentLogin.setLastLoginAt(java.time.LocalDateTime.of(2026, 8, 7, 15, 0));
        when(userRepo.findById(30L)).thenReturn(Optional.of(recentLogin));

        java.util.List<ExternalLinkDomainService.AccessedUser> result = svc.listAccessedUsers(d);

        assertThat(result).extracting(ExternalLinkDomainService.AccessedUser::getDisplayName)
                .containsExactly("RecentLogin", "OlderLogin", "NeverLoggedIn");
        assertThat(result.get(1).getAdCode()).isEqualTo("sms_01");
        assertThat(result.get(1).getFolder()).isEqualTo("SMS");
        assertThat(result.get(1).getCreatedAt()).isEqualTo(java.time.LocalDateTime.of(2026, 1, 1, 0, 0));
    }

    @Test
    void findByHost_matchesBareHostFromStoredSchemeUrl() {
        // findByDomainUrlContainingIgnoreCase is a real DB query (case-insensitive at the SQL
        // level) — stub it as the DB would behave (matches regardless of the needle's case)
        // rather than relying on Mockito's exact-match default, which isn't case-insensitive.
        ExternalLinkDomain d = existing(1L, "https://ii5gh9ge.jp", true);
        when(repo.findByDomainUrlContainingIgnoreCase(anyString()))
                .thenReturn(Arrays.asList(d));

        Optional<ExternalLinkDomain> result = svc.findByHost("II5GH9GE.JP");

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(1L);
    }

    @Test
    void findByHost_noMatchingRow_returnsEmpty() {
        when(repo.findByDomainUrlContainingIgnoreCase(anyString()))
                .thenReturn(java.util.Collections.emptyList());

        assertThat(svc.findByHost("unregistered.jp")).isEmpty();
    }

    @Test
    void findByHost_blankHost_returnsEmptyWithoutQuerying() {
        assertThat(svc.findByHost("")).isEmpty();
        assertThat(svc.findByHost(null)).isEmpty();
        verify(repo, never()).findByDomainUrlContainingIgnoreCase(anyString());
    }

    // --- certStatus() durability (2026-08-05 fix: /tmp result file is not guaranteed to
    // survive, so a definitive live result must be persisted onto the row) ---

    @Test
    void certStatus_liveSuccess_persistsOntoRowAndReturnsIt() {
        ExternalLinkDomain d = existing(1L, "https://ep84ti.jp", true);
        d.setCertStatus(null);
        when(certService.status("ep84ti.jp")).thenReturn("SUCCESS");

        String result = svc.certStatus(d);

        assertThat(result).isEqualTo("SUCCESS");
        assertThat(d.getCertStatus()).isEqualTo("SUCCESS");
        verify(repo).save(d);
    }

    @Test
    void certStatus_liveFailed_persistsFailureReason() {
        ExternalLinkDomain d = existing(1L, "https://ep84ti.jp", true);
        when(certService.status("ep84ti.jp")).thenReturn("FAILED_CERTBOT");

        String result = svc.certStatus(d);

        assertThat(result).isEqualTo("FAILED_CERTBOT");
        assertThat(d.getCertStatus()).isEqualTo("FAILED_CERTBOT");
    }

    @Test
    void certStatus_livePendingButRowAlreadyRecordsSuccess_returnsPersistedSuccess() {
        // Regression test for the exact bug reported: cert genuinely succeeded, but the
        // /tmp result file was cleared before anyone checked — status() must not fall back
        // to a fresh PENDING once a durable SUCCESS has been recorded.
        ExternalLinkDomain d = existing(1L, "https://ep84ti.jp", true);
        d.setCertStatus("SUCCESS");
        when(certService.status("ep84ti.jp")).thenReturn("PENDING");

        String result = svc.certStatus(d);

        assertThat(result).isEqualTo("SUCCESS");
        verify(repo, never()).save(any(ExternalLinkDomain.class));
    }

    @Test
    void certStatus_livePendingAndNeverPersisted_returnsPending() {
        ExternalLinkDomain d = existing(1L, "https://ep84ti.jp", true);
        d.setCertStatus(null);
        when(certService.status("ep84ti.jp")).thenReturn("PENDING");

        assertThat(svc.certStatus(d)).isEqualTo("PENDING");
    }

    @Test
    void certStatus_sameResultAsAlreadyPersisted_doesNotResave() {
        ExternalLinkDomain d = existing(1L, "https://ep84ti.jp", true);
        d.setCertStatus("SUCCESS");
        when(certService.status("ep84ti.jp")).thenReturn("SUCCESS");

        svc.certStatus(d);

        verify(repo, never()).save(any(ExternalLinkDomain.class));
    }

    @Test
    void requestCert_resetsStatusToPendingBeforeRerequesting() {
        ExternalLinkDomain d = existing(5L, "https://ep84ti.jp", true);
        d.setCertStatus("FAILED_CERTBOT");
        when(repo.findById(5L)).thenReturn(Optional.of(d));

        svc.requestCert(5L);

        assertThat(d.getCertStatus()).isEqualTo("PENDING");
        verify(certService).requestCertificate("ep84ti.jp");
    }

    @Test
    void retryPendingCertRequests_skipsSuccessfulDomains_retriesOthers() {
        ExternalLinkDomain success = existing(1L, "https://ok.jp", true);
        success.setCertStatus("SUCCESS");
        ExternalLinkDomain neverRequested = existing(2L, "https://new.jp", false);
        neverRequested.setCertStatus(null);
        ExternalLinkDomain failed = existing(3L, "https://retry.jp", false);
        failed.setCertStatus("FAILED_CERTBOT");
        when(repo.findAll()).thenReturn(Arrays.asList(success, neverRequested, failed));

        svc.retryPendingCertRequests();

        verify(certService, never()).requestCertificate("ok.jp");
        verify(certService).requestCertificate("new.jp");
        verify(certService).requestCertificate("retry.jp");
    }
}
