package com.crm.service;

import com.crm.entity.CrmSetting;
import com.crm.repository.CrmSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * save() with a blank password must keep the stored one — the settings form never re-displays
 * the real password (type="password" + browser autocomplete quirks make that unreliable), so a
 * blank submission has to mean "unchanged". Regression test for the 2026-07-09 incident where
 * saving an unrelated field (sender name) silently wiped a working BytePlus password because
 * save() previously overwrote it unconditionally.
 */
class SmsSettingServiceTest {

    private CrmSettingRepository repo;
    private SmsSettingService svc;

    @BeforeEach
    void setUp() {
        repo = mock(CrmSettingRepository.class);
        when(repo.save(any(CrmSetting.class))).thenAnswer(inv -> inv.getArgument(0));
        svc = new SmsSettingService(repo);
    }

    private static CrmSetting existing(String key, String value) {
        CrmSetting s = new CrmSetting();
        s.setSettingKey(key);
        s.setSettingValue(value);
        return s;
    }

    @Test
    void save_blankPassword_doesNotOverwriteStoredValue() {
        stubOtherKeysEmpty();

        svc.save(true, "8b195974", "", "新しい送信者名", true, "", "FIXED", "", 8, "", 60);

        verify(repo, never()).findBySettingKey(SmsSettingService.KEY_PASSWORD);
        ArgumentCaptor<CrmSetting> cap = ArgumentCaptor.forClass(CrmSetting.class);
        verify(repo, org.mockito.Mockito.atLeastOnce()).save(cap.capture());
        boolean passwordTouched = cap.getAllValues().stream()
                .anyMatch(s -> SmsSettingService.KEY_PASSWORD.equals(s.getSettingKey()));
        assertThat(passwordTouched).isFalse();
    }

    @Test
    void save_nonBlankPassword_overwritesStoredValue() {
        when(repo.findBySettingKey(SmsSettingService.KEY_PASSWORD))
                .thenReturn(Optional.of(existing(SmsSettingService.KEY_PASSWORD, "old-password")));
        stubOtherKeysEmpty();

        svc.save(true, "8b195974", "new-password", "sender", true, "", "FIXED", "", 8, "", 60);

        ArgumentCaptor<CrmSetting> cap = ArgumentCaptor.forClass(CrmSetting.class);
        verify(repo, org.mockito.Mockito.atLeastOnce()).save(cap.capture());
        boolean passwordUpdated = cap.getAllValues().stream()
                .anyMatch(s -> SmsSettingService.KEY_PASSWORD.equals(s.getSettingKey())
                        && "new-password".equals(s.getSettingValue()));
        assertThat(passwordUpdated).isTrue();
    }

    @Test
    void resolveSenderName_randomAlnum_matchesConfiguredLength() {
        when(repo.findBySettingKey(SmsSettingService.KEY_SENDER_NAME_MODE))
                .thenReturn(Optional.of(existing(SmsSettingService.KEY_SENDER_NAME_MODE, "RANDOM_ALNUM")));
        when(repo.findBySettingKey(SmsSettingService.KEY_SENDER_NAME_RANDOM_LENGTH))
                .thenReturn(Optional.of(existing(SmsSettingService.KEY_SENDER_NAME_RANDOM_LENGTH, "6")));

        String name = svc.resolveSenderName();

        assertThat(name).hasSize(6);
        assertThat(name).matches("[A-Za-z0-9]+");
    }

    @Test
    void resolveSenderName_random090_has11CharsAndPrefix() {
        when(repo.findBySettingKey(SmsSettingService.KEY_SENDER_NAME_MODE))
                .thenReturn(Optional.of(existing(SmsSettingService.KEY_SENDER_NAME_MODE, "RANDOM_090")));

        String name = svc.resolveSenderName();

        assertThat(name).hasSize(11);
        assertThat(name).startsWith("090");
        assertThat(name.substring(3)).matches("[0-9]+");
    }

    @Test
    void resolveSenderName_random080_has11CharsAndPrefix() {
        when(repo.findBySettingKey(SmsSettingService.KEY_SENDER_NAME_MODE))
                .thenReturn(Optional.of(existing(SmsSettingService.KEY_SENDER_NAME_MODE, "RANDOM_080")));

        String name = svc.resolveSenderName();

        assertThat(name).hasSize(11);
        assertThat(name).startsWith("080");
    }

    @Test
    void resolveSenderName_fixedMode_picksFromConfiguredList() {
        when(repo.findBySettingKey(SmsSettingService.KEY_SENDER_NAME_MODE))
                .thenReturn(Optional.of(existing(SmsSettingService.KEY_SENDER_NAME_MODE, "FIXED")));
        when(repo.findBySettingKey(SmsSettingService.KEY_SENDER_NAME_FIXED_LIST))
                .thenReturn(Optional.of(existing(SmsSettingService.KEY_SENDER_NAME_FIXED_LIST, "CRM01\ninfoABC")));

        String name = svc.resolveSenderName();

        assertThat(name).isIn("CRM01", "infoABC");
    }

    @Test
    void resolveSenderName_fixedModeWithEmptyList_fallsBackToLegacySenderName() {
        when(repo.findBySettingKey(SmsSettingService.KEY_SENDER_NAME_MODE))
                .thenReturn(Optional.of(existing(SmsSettingService.KEY_SENDER_NAME_MODE, "FIXED")));
        when(repo.findBySettingKey(SmsSettingService.KEY_SENDER_NAME_FIXED_LIST))
                .thenReturn(Optional.empty());
        when(repo.findBySettingKey(SmsSettingService.KEY_SENDER_NAME))
                .thenReturn(Optional.of(existing(SmsSettingService.KEY_SENDER_NAME, "legacyName")));

        assertThat(svc.resolveSenderName()).isEqualTo("legacyName");
    }

    @Test
    void validateFixedList_rejectsJapanese() {
        assertThat(SmsSettingService.validateFixedList("高橋健一")).isNotNull();
    }

    @Test
    void validateFixedList_rejectsOver10Chars() {
        assertThat(SmsSettingService.validateFixedList("abcdefghijk")).isNotNull();
    }

    @Test
    void validateFixedList_rejectsMoreThan5Lines() {
        assertThat(SmsSettingService.validateFixedList("a\nb\nc\nd\ne\nf")).isNotNull();
    }

    @Test
    void validateFixedList_acceptsValidAlphanumericLines() {
        assertThat(SmsSettingService.validateFixedList("CRM01\ninfoABC\nshop123")).isNull();
    }

    @Test
    void getRatePerMinute_unset_defaultsTo60() {
        assertThat(svc.getRatePerMinute()).isEqualTo(60);
    }

    @Test
    void getRatePerMinute_clampsToValidRange() {
        when(repo.findBySettingKey(SmsSettingService.KEY_RATE_PER_MINUTE))
                .thenReturn(Optional.of(existing(SmsSettingService.KEY_RATE_PER_MINUTE, "9999")));
        assertThat(svc.getRatePerMinute()).isEqualTo(600);
    }

    @Test
    void setRatePerMinute_clampsBelowMinimum() {
        svc.setRatePerMinute(0);
        ArgumentCaptor<CrmSetting> cap = ArgumentCaptor.forClass(CrmSetting.class);
        verify(repo).save(cap.capture());
        assertThat(cap.getValue().getSettingKey()).isEqualTo(SmsSettingService.KEY_RATE_PER_MINUTE);
        assertThat(cap.getValue().getSettingValue()).isEqualTo("1");
    }

    private void stubOtherKeysEmpty() {
        when(repo.findBySettingKey(eq(SmsSettingService.KEY_USERNAME))).thenReturn(Optional.empty());
        when(repo.findBySettingKey(eq(SmsSettingService.KEY_ENABLED))).thenReturn(Optional.empty());
        when(repo.findBySettingKey(eq(SmsSettingService.KEY_SENDER_NAME))).thenReturn(Optional.empty());
        when(repo.findBySettingKey(eq(SmsSettingService.KEY_LOCAL_DELIVERY))).thenReturn(Optional.empty());
        when(repo.findBySettingKey(eq(SmsSettingService.KEY_RELAY_IP))).thenReturn(Optional.empty());
        when(repo.findBySettingKey(eq(SmsSettingService.KEY_SENDER_NAME_MODE))).thenReturn(Optional.empty());
        when(repo.findBySettingKey(eq(SmsSettingService.KEY_SENDER_NAME_FIXED_LIST))).thenReturn(Optional.empty());
        when(repo.findBySettingKey(eq(SmsSettingService.KEY_SENDER_NAME_RANDOM_LENGTH))).thenReturn(Optional.empty());
    }

}
