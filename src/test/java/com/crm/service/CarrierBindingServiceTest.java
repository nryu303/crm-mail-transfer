package com.crm.service;

import com.crm.entity.CarrierAddressPool;
import com.crm.entity.CarrierUserBinding;
import com.crm.entity.CrmUser;
import com.crm.repository.CarrierAddressPoolRepository;
import com.crm.repository.CarrierUserBindingRepository;
import com.crm.repository.CrmUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M:N bind/unbind semantics. Duplicates must be idempotent (re-binding same pair = no-op)
 * and unknown user/pool IDs must not cause silent NPEs.
 */
class CarrierBindingServiceTest {

    private CarrierAddressPoolRepository poolRepo;
    private CarrierUserBindingRepository bindingRepo;
    private CrmUserRepository userRepo;
    private CarrierBindingService svc;

    @BeforeEach
    void setUp() {
        poolRepo = mock(CarrierAddressPoolRepository.class);
        bindingRepo = mock(CarrierUserBindingRepository.class);
        userRepo = mock(CrmUserRepository.class);
        svc = new CarrierBindingService(poolRepo, bindingRepo, userRepo);
        // user 1 + pool 10 always exist by default.
        when(userRepo.existsById(1L)).thenReturn(true);
        when(poolRepo.existsById(10L)).thenReturn(true);
        // requireUser/requirePool use findById internally — stub the entity lookup too.
        CrmUser user1 = new CrmUser();
        user1.setId(1L);
        when(userRepo.findById(1L)).thenReturn(Optional.of(user1));
        when(poolRepo.findById(10L)).thenReturn(Optional.of(pool(10L)));
    }

    private static CarrierAddressPool pool(Long id) {
        CarrierAddressPool p = new CarrierAddressPool();
        p.setId(id);
        p.setIsActive(true);
        return p;
    }

    @Test
    void bindAllAvailable_createsOneBindingPerActivePool() {
        when(poolRepo.findByIsActiveTrueOrderByIdAsc())
                .thenReturn(Arrays.asList(pool(10L), pool(11L)));
        when(bindingRepo.existsByPoolIdAndUserId(anyLong(), eq(1L))).thenReturn(false);

        int created = svc.bindAllAvailable(1L);

        assertThat(created).isEqualTo(2);
        verify(bindingRepo, times(2)).save(any(CarrierUserBinding.class));
    }

    @Test
    void bindAllAvailable_skipsAlreadyBoundPools() {
        when(poolRepo.findByIsActiveTrueOrderByIdAsc())
                .thenReturn(Arrays.asList(pool(10L), pool(11L)));
        when(bindingRepo.existsByPoolIdAndUserId(10L, 1L)).thenReturn(true);  // already bound
        when(bindingRepo.existsByPoolIdAndUserId(11L, 1L)).thenReturn(false);

        int created = svc.bindAllAvailable(1L);

        assertThat(created).isEqualTo(1);
        verify(bindingRepo, times(1)).save(any(CarrierUserBinding.class));
    }

    @Test
    void bindOneToMany_skipsNullsAndNonExistentUsers() {
        when(bindingRepo.existsByPoolIdAndUserId(anyLong(), anyLong())).thenReturn(false);
        when(userRepo.existsById(2L)).thenReturn(true);
        // user 3 does not exist — should be silently skipped, not crash

        int created = svc.bindOneToMany(10L, Arrays.asList(1L, null, 2L, 3L));

        assertThat(created).isEqualTo(2);  // 1 and 2 bound; null + 3 skipped
        ArgumentCaptor<CarrierUserBinding> cap = ArgumentCaptor.forClass(CarrierUserBinding.class);
        verify(bindingRepo, times(2)).save(cap.capture());
    }

    @Test
    void bindOneToMany_nullOrEmptyUserList_returnsZero() {
        assertThat(svc.bindOneToMany(10L, null)).isZero();
        assertThat(svc.bindOneToMany(10L, Collections.emptyList())).isZero();
        verify(bindingRepo, never()).save(any());
    }

    @Test
    void bindOneToMany_unknownPoolId_returnsZero() {
        when(poolRepo.existsById(999L)).thenReturn(false);
        assertThat(svc.bindOneToMany(999L, Arrays.asList(1L))).isZero();
        verify(bindingRepo, never()).save(any());
    }

    @Test
    void bindSpecific_returnsFalseWhenAlreadyBound() {
        when(bindingRepo.existsByPoolIdAndUserId(10L, 1L)).thenReturn(true);
        assertThat(svc.bindSpecific(1L, 10L)).isFalse();
        verify(bindingRepo, never()).save(any());
    }

    @Test
    void bindSpecific_createsNewBindingWhenAbsent() {
        when(bindingRepo.existsByPoolIdAndUserId(10L, 1L)).thenReturn(false);
        assertThat(svc.bindSpecific(1L, 10L)).isTrue();
        verify(bindingRepo).save(any(CarrierUserBinding.class));
    }

    @Test
    void unbindOne_deletesExistingBinding() {
        CarrierUserBinding row = new CarrierUserBinding(10L, 1L);
        when(bindingRepo.findByPoolIdAndUserId(10L, 1L)).thenReturn(Optional.of(row));
        assertThat(svc.unbindOne(1L, 10L)).isTrue();
        verify(bindingRepo).delete(row);
    }

    @Test
    void unbindOne_noOpWhenMissing() {
        when(bindingRepo.findByPoolIdAndUserId(10L, 1L)).thenReturn(Optional.empty());
        assertThat(svc.unbindOne(1L, 10L)).isFalse();
        verify(bindingRepo, never()).delete(any(CarrierUserBinding.class));
    }

    @Test
    void isBound_delegatesToRepository() {
        when(bindingRepo.existsByPoolIdAndUserId(10L, 1L)).thenReturn(true);
        assertThat(svc.isBound(10L, 1L)).isTrue();
        when(bindingRepo.existsByPoolIdAndUserId(11L, 1L)).thenReturn(false);
        assertThat(svc.isBound(11L, 1L)).isFalse();
    }
}
