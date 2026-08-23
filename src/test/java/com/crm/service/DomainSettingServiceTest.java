package com.crm.service;

import com.crm.entity.CrmSetting;
import com.crm.entity.ExternalLinkDomain;
import com.crm.repository.CrmSettingRepository;
import com.crm.repository.ExternalLinkDomainRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers {@link DomainSettingService#buildReplyUrl(String)} (always the CRM's own base URL,
 * regardless of any active {@link ExternalLinkDomain}) and {@link DomainSettingService#buildExternalUrl(String)}
 * (the active ExternalLinkDomain's URL, or null when none is active) — split 2026-08-23 so
 * %reply_url% always reaches the real two-way reply form while %external_url% is the separate
 * tag for REDIRECT/CUSTOM_HTML/tracked-domain behaviour.
 */
class DomainSettingServiceTest {

    private CrmSettingRepository settingRepo;
    private ExternalLinkDomainRepository domainRepo;
    private DomainSettingService svc;

    @BeforeEach
    void setUp() {
        settingRepo = mock(CrmSettingRepository.class);
        domainRepo = mock(ExternalLinkDomainRepository.class);
        when(settingRepo.findBySettingKey(anyString())).thenReturn(Optional.empty());
        svc = new DomainSettingService(settingRepo, domainRepo, "");
    }

    private static ExternalLinkDomain active(String domainUrl) {
        ExternalLinkDomain d = new ExternalLinkDomain();
        d.setId(1L);
        d.setDomainUrl(domainUrl);
        d.setIsActive(true);
        return d;
    }

    @Test
    void buildReplyUrl_activeExternalLinkDomain_isIgnored_usesLegacyBaseUrl() {
        // As of 2026-08-23, %reply_url% must ALWAYS reach the CRM's own reply form — it no
        // longer prefers an active ExternalLinkDomain. That behaviour moved to buildExternalUrl().
        when(domainRepo.findFirstByIsActiveTrue()).thenReturn(Optional.of(active("https://ii5gh9ge.jp")));
        CrmSetting base = new CrmSetting();
        base.setSettingKey(DomainSettingService.KEY_REPLY_BASE_URL);
        base.setSettingValue("https://legacy.example.com");
        when(settingRepo.findBySettingKey(DomainSettingService.KEY_REPLY_BASE_URL))
                .thenReturn(Optional.of(base));
        CrmSetting randomOff = new CrmSetting();
        randomOff.setSettingKey(DomainSettingService.KEY_REPLY_RANDOM_SUBDOMAIN);
        randomOff.setSettingValue("false");
        when(settingRepo.findBySettingKey(DomainSettingService.KEY_REPLY_RANDOM_SUBDOMAIN))
                .thenReturn(Optional.of(randomOff));

        String url = svc.buildReplyUrl("tok123");

        assertThat(url).isEqualTo("https://legacy.example.com/reply/tok123");
    }

    @Test
    void buildExternalUrl_usesActivePoolDomain_noSubdomainInjected() {
        when(domainRepo.findFirstByIsActiveTrue()).thenReturn(Optional.of(active("https://ii5gh9ge.jp")));

        String url = svc.buildExternalUrl("tok123");

        assertThat(url).isEqualTo("https://ii5gh9ge.jp/reply/tok123");
    }

    @Test
    void buildExternalUrl_stripsTrailingSlashFromPoolDomain() {
        when(domainRepo.findFirstByIsActiveTrue()).thenReturn(Optional.of(active("https://ii5gh9ge.jp/")));

        String url = svc.buildExternalUrl("tok123");

        assertThat(url).isEqualTo("https://ii5gh9ge.jp/reply/tok123");
    }

    @Test
    void buildExternalUrl_noActiveDomain_returnsNull() {
        when(domainRepo.findFirstByIsActiveTrue()).thenReturn(Optional.empty());

        assertThat(svc.buildExternalUrl("tok123")).isNull();
    }

    @Test
    void buildReplyUrl_noActivePoolDomain_fallsBackToLegacySetting() {
        when(domainRepo.findFirstByIsActiveTrue()).thenReturn(Optional.empty());
        CrmSetting base = new CrmSetting();
        base.setSettingKey(DomainSettingService.KEY_REPLY_BASE_URL);
        base.setSettingValue("https://legacy.example.com");
        when(settingRepo.findBySettingKey(DomainSettingService.KEY_REPLY_BASE_URL))
                .thenReturn(Optional.of(base));
        // Random subdomain defaults to enabled when unset — disable it explicitly for a
        // predictable assertion by stubbing that key's setting to "false".
        CrmSetting randomOff = new CrmSetting();
        randomOff.setSettingKey(DomainSettingService.KEY_REPLY_RANDOM_SUBDOMAIN);
        randomOff.setSettingValue("false");
        when(settingRepo.findBySettingKey(DomainSettingService.KEY_REPLY_RANDOM_SUBDOMAIN))
                .thenReturn(Optional.of(randomOff));

        String url = svc.buildReplyUrl("tok123");

        assertThat(url).isEqualTo("https://legacy.example.com/reply/tok123");
    }

    @Test
    void getReplyBaseUrl_ignoresActivePoolDomain_alwaysReturnsRealSetting() {
        // getReplyBaseUrl() is the CRM's own fixed identity (used by ドメイン設定,
        // 本ドメイン表示設定, and the agency dashboard) — it must NOT be overridden by
        // whichever 外部リンクドメイン happens to be active, or those screens would display
        // (and even resave) the wrong domain. Only buildReplyUrl() applies that override.
        when(domainRepo.findFirstByIsActiveTrue()).thenReturn(Optional.of(active("https://ii5gh9ge.jp")));
        CrmSetting base = new CrmSetting();
        base.setSettingKey(DomainSettingService.KEY_REPLY_BASE_URL);
        base.setSettingValue("https://nbbv7g.jp");
        when(settingRepo.findBySettingKey(DomainSettingService.KEY_REPLY_BASE_URL))
                .thenReturn(Optional.of(base));

        assertThat(svc.getReplyBaseUrl()).isEqualTo("https://nbbv7g.jp");
    }

    @Test
    void getActiveShortTokenLength_noActiveDomain_returnsDefault() {
        when(domainRepo.findFirstByIsActiveTrue()).thenReturn(Optional.empty());

        assertThat(svc.getActiveShortTokenLength())
                .isEqualTo(com.crm.util.TokenGenerator.DEFAULT_SHORT_LENGTH);
    }

    @Test
    void getActiveShortTokenLength_activeDomainWithoutOverride_returnsDefault() {
        ExternalLinkDomain d = active("https://ii5gh9ge.jp");
        d.setShortTokenLength(null);
        when(domainRepo.findFirstByIsActiveTrue()).thenReturn(Optional.of(d));

        assertThat(svc.getActiveShortTokenLength())
                .isEqualTo(com.crm.util.TokenGenerator.DEFAULT_SHORT_LENGTH);
    }

    @Test
    void getActiveShortTokenLength_activeDomainWithOverride_returnsConfiguredValue() {
        ExternalLinkDomain d = active("https://ii5gh9ge.jp");
        d.setShortTokenLength(6);
        when(domainRepo.findFirstByIsActiveTrue()).thenReturn(Optional.of(d));

        assertThat(svc.getActiveShortTokenLength()).isEqualTo(6);
    }
}
