package com.crm.service;

import com.crm.dto.BroadcastForm;
import com.crm.entity.CrmUser;
import com.crm.repository.BroadcastRepository;
import com.crm.repository.CarrierAddressPoolRepository;
import com.crm.repository.CrmUserRepository;
import com.crm.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An SMS-only user (email blank, phone set — the CSV-import case fixed for the client's
 * 2,571-row import) must never be routed into an EMAIL broadcast, since there's no
 * TO_ADDRESS to send to. createAndQueue() should treat them as unsendable, same as an
 * RFC-invalid-local-part user, not silently produce a null-TO_ADDRESS message.
 */
class BroadcastServiceEmailOnlyGuardTest {

    private BroadcastRepository broadcastRepo;
    private CrmUserRepository userRepo;
    private CarrierAddressPoolRepository poolRepo;
    private CarrierBindingService bindingService;
    private MessageRepository messageRepo;
    private PlaceholderService placeholderService;
    private ReplyPageService replyPageService;
    private DomainSettingService domainSettingService;
    private SmsSettingService smsSettingService;
    private BroadcastService svc;

    @BeforeEach
    void setUp() {
        broadcastRepo = mock(BroadcastRepository.class);
        userRepo = mock(CrmUserRepository.class);
        poolRepo = mock(CarrierAddressPoolRepository.class);
        bindingService = mock(CarrierBindingService.class);
        messageRepo = mock(MessageRepository.class);
        placeholderService = mock(PlaceholderService.class);
        replyPageService = mock(ReplyPageService.class);
        domainSettingService = mock(DomainSettingService.class);
        smsSettingService = mock(SmsSettingService.class);

        svc = new BroadcastService(broadcastRepo, userRepo, poolRepo, bindingService,
                messageRepo, placeholderService, replyPageService, domainSettingService,
                smsSettingService);

        when(bindingService.firstBoundFor(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(java.util.Optional.empty());
        when(domainSettingService.buildFromAddress()).thenReturn("info@example.com");
        when(placeholderService.substitute(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(CrmUser.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(messageRepo.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private static CrmUser phoneOnlyUser(Long id) {
        CrmUser u = new CrmUser();
        u.setId(id);
        u.setEmail(null);
        u.setPhoneNumber("09012345678");
        u.setStatus(CrmUser.STATUS_ACTIVE);
        return u;
    }

    private static CrmUser emailUser(Long id) {
        CrmUser u = new CrmUser();
        u.setId(id);
        u.setEmail("user" + id + "@example.com");
        u.setStatus(CrmUser.STATUS_ACTIVE);
        return u;
    }

    private static BroadcastForm formTargeting(java.util.List<Long> ids) {
        BroadcastForm f = new BroadcastForm();
        f.setTargetUserIds(ids);
        f.setBody("test body");
        f.setSubject("test subject");
        return f;
    }

    @Test
    void allTargetsPhoneOnly_throwsNoTargetsException_doesNotCreateBroadcast() {
        when(userRepo.findAllById(Arrays.asList(1L)))
                .thenReturn(Arrays.asList(phoneOnlyUser(1L)));

        assertThatThrownBy(() -> svc.createAndQueue(formTargeting(Arrays.asList(1L)), 1L))
                .isInstanceOf(BroadcastService.NoTargetsException.class);
    }

    @Test
    void mixedTargets_phoneOnlyUserExcluded_emailUserStillQueued() {
        when(userRepo.findAllById(Arrays.asList(1L, 2L)))
                .thenReturn(Arrays.asList(phoneOnlyUser(1L), emailUser(2L)));
        when(broadcastRepo.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    com.crm.entity.Broadcast b = inv.getArgument(0);
                    b.setId(99L);
                    return b;
                });

        com.crm.entity.Broadcast result = svc.createAndQueue(formTargeting(Arrays.asList(1L, 2L)), 1L);

        // Only the email user (id=2) counts as deliverable; the phone-only user (id=1) is
        // recorded as unsendable rather than crashing or producing a null TO_ADDRESS.
        assertThat(result.getTotalCount()).isEqualTo(1);
        assertThat(result.getUnsendableCount()).isEqualTo(1);
        assertThat(result.getUnsendableUserIds()).contains("1");
    }
}
