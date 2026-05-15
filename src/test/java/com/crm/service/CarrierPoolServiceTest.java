package com.crm.service;

import com.crm.dto.CarrierPoolForm;
import com.crm.entity.CarrierAddressPool;
import com.crm.repository.CarrierAddressPoolRepository;
import com.crm.repository.CarrierUserBindingRepository;
import com.crm.util.AesEncryptionUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CarrierPoolService#update} — the new edit-page entry point. The contract is:
 *   • A NEW {@code smtpPassword} is re-encrypted; a BLANK one keeps the stored ciphertext.
 *   • Renaming an existing address to one used by another row throws DuplicateAddressException.
 *   • Renaming to the same value is allowed (no duplicate check fires).
 *   • Missing IDs throw IllegalArgumentException so the controller can surface a 404-style flash.
 */
class CarrierPoolServiceTest {

    private CarrierAddressPoolRepository poolRepo;
    private AesEncryptionUtil aes;
    private CarrierPoolService svc;

    @BeforeEach
    void setUp() {
        poolRepo = mock(CarrierAddressPoolRepository.class);
        CarrierUserBindingRepository bindingRepo = mock(CarrierUserBindingRepository.class);
        aes = mock(AesEncryptionUtil.class);
        when(aes.encrypt(anyString())).thenAnswer(inv -> "ENC[" + inv.getArgument(0) + "]");
        when(poolRepo.save(any(CarrierAddressPool.class))).thenAnswer(inv -> inv.getArgument(0));
        svc = new CarrierPoolService(poolRepo, bindingRepo, aes);
    }

    private static CarrierAddressPool existing(Long id, String address, String encPw) {
        CarrierAddressPool p = new CarrierAddressPool();
        p.setId(id);
        p.setAddress(address);
        p.setCarrierCode("docomo");
        p.setCarrierDomain("docomo.ne.jp");
        p.setSmtpHost("smtp.example.com");
        p.setSmtpPort(587);
        p.setSmtpUsername("user");
        p.setSmtpPassword(encPw);
        p.setIsActive(true);
        return p;
    }

    private static CarrierPoolForm form(String address, String pwOrBlank) {
        CarrierPoolForm f = new CarrierPoolForm();
        f.setAddress(address);
        f.setCarrierCode("docomo");
        f.setCarrierDomain("docomo.ne.jp");
        f.setSmtpHost("smtp.example.com");
        f.setSmtpPort(587);
        f.setSmtpUsername("user");
        f.setSmtpPassword(pwOrBlank);
        f.setIsActive(true);
        return f;
    }

    @Test
    void update_blankPassword_keepsStoredCipher() {
        CarrierAddressPool stored = existing(7L, "a@x.test", "ENC[old]");
        when(poolRepo.findById(7L)).thenReturn(Optional.of(stored));

        svc.update(7L, form("a@x.test", ""));

        ArgumentCaptor<CarrierAddressPool> cap = ArgumentCaptor.forClass(CarrierAddressPool.class);
        verify(poolRepo).save(cap.capture());
        assertThat(cap.getValue().getSmtpPassword()).isEqualTo("ENC[old]");
        verify(aes, never()).encrypt(anyString());
    }

    @Test
    void update_nonBlankPassword_reEncrypts() {
        CarrierAddressPool stored = existing(7L, "a@x.test", "ENC[old]");
        when(poolRepo.findById(7L)).thenReturn(Optional.of(stored));

        svc.update(7L, form("a@x.test", "newpw"));

        ArgumentCaptor<CarrierAddressPool> cap = ArgumentCaptor.forClass(CarrierAddressPool.class);
        verify(poolRepo).save(cap.capture());
        assertThat(cap.getValue().getSmtpPassword()).isEqualTo("ENC[newpw]");
        verify(aes).encrypt("newpw");
    }

    @Test
    void update_renameToExistingAddress_throws() {
        CarrierAddressPool stored = existing(7L, "old@x.test", "ENC[x]");
        when(poolRepo.findById(7L)).thenReturn(Optional.of(stored));
        when(poolRepo.existsByAddress("taken@x.test")).thenReturn(true);

        assertThatThrownBy(() -> svc.update(7L, form("taken@x.test", "")))
                .isInstanceOf(CarrierPoolService.DuplicateAddressException.class);
    }

    @Test
    void update_renameToSameAddress_doesNotFireDuplicateCheck() {
        CarrierAddressPool stored = existing(7L, "same@x.test", "ENC[x]");
        when(poolRepo.findById(7L)).thenReturn(Optional.of(stored));
        // existsByAddress would otherwise default to false from Mockito, but we assert it's
        // not even consulted when the name didn't change.
        svc.update(7L, form("same@x.test", ""));
        verify(poolRepo, never()).existsByAddress("same@x.test");
    }

    @Test
    void update_missingId_throwsIllegalArgument() {
        when(poolRepo.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> svc.update(99L, form("x@y", "")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void update_isActiveFlag_isAppliedFromForm() {
        CarrierAddressPool stored = existing(7L, "a@x.test", "ENC[x]");
        stored.setIsActive(true);
        when(poolRepo.findById(7L)).thenReturn(Optional.of(stored));

        CarrierPoolForm f = form("a@x.test", "");
        f.setIsActive(false);
        svc.update(7L, f);

        ArgumentCaptor<CarrierAddressPool> cap = ArgumentCaptor.forClass(CarrierAddressPool.class);
        verify(poolRepo).save(cap.capture());
        assertThat(cap.getValue().getIsActive()).isFalse();
    }
}
