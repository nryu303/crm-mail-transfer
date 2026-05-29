package com.crm.service;

import com.crm.entity.Broadcast;
import com.crm.entity.Message;
import com.crm.repository.BroadcastRepository;
import com.crm.repository.CarrierAddressPoolRepository;
import com.crm.repository.CarrierUserBindingRepository;
import com.crm.repository.CrmSettingRepository;
import com.crm.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Scheduled broadcast snapshot semantics: every materialised MESSAGE row of a scheduled
 * broadcast fires at dispatch time regardless of intermediate OUT activity for the same
 * user. Cancel-race protection (operator cancelled the parent broadcast) is still honoured.
 *
 * The scheduler-lock branch is short-circuited by stubbing the CrmSettingRepository so the
 * lock is always claimable; we focus on the per-message exclusion path.
 */
class ScheduledTaskServiceTest {

    private MessageRepository msgRepo;
    private CarrierAddressPoolRepository poolRepo;
    private MessageService messageService;
    private CrmSettingRepository settingRepo;
    private CarrierUserBindingRepository bindingRepo;
    private DomainSettingService domainSettings;
    private BroadcastRepository broadcastRepo;
    private ScheduledTaskService svc;

    @BeforeEach
    void setUp() {
        msgRepo = mock(MessageRepository.class);
        poolRepo = mock(CarrierAddressPoolRepository.class);
        messageService = mock(MessageService.class);
        settingRepo = mock(CrmSettingRepository.class);
        bindingRepo = mock(CarrierUserBindingRepository.class);
        domainSettings = mock(DomainSettingService.class);
        broadcastRepo = mock(BroadcastRepository.class);

        // Make the dispatcher lock always acquirable (empty value, no holder).
        when(settingRepo.findBySettingKey(anyString())).thenReturn(Optional.empty());

        FolderSettingService folderSettings = mock(FolderSettingService.class);
        FolderRetentionService folderRetention = mock(FolderRetentionService.class);
        com.crm.repository.InboundMailLogRepository inboundLogRepo =
                mock(com.crm.repository.InboundMailLogRepository.class);
        InboundMailService inboundMail = mock(InboundMailService.class);
        svc = new ScheduledTaskService(msgRepo, poolRepo, messageService,
                settingRepo, bindingRepo, domainSettings, broadcastRepo,
                folderSettings, folderRetention, inboundLogRepo, inboundMail);
    }

    private static Message scheduledBroadcastRow(Long id, Long userId, Long broadcastId,
                                                  LocalDateTime createdAt, LocalDateTime scheduledAt) {
        Message m = new Message();
        m.setId(id);
        m.setUserId(userId);
        m.setBroadcastId(broadcastId);
        m.setDirection(Message.DIR_OUT);
        m.setFromAddress("from@avu74g.jp");
        m.setToAddress("to@example.com");
        m.setSubject("s");
        m.setBodyText("b");
        m.setStatus(Message.STATUS_QUEUED);
        m.setCreatedAt(createdAt);
        m.setScheduledAt(scheduledAt);
        return m;
    }

    private static Broadcast broadcast(Long id, LocalDateTime createdAt) {
        Broadcast b = new Broadcast();
        b.setId(id);
        b.setCreatedAt(createdAt);
        return b;
    }

    @Test
    void dispatchesScheduledBroadcastRow_evenWhenUserReceivedOtherSendsInBetween() {
        // 2026-05-29: client requested snapshot semantics — every materialised MESSAGE
        // row of a scheduled broadcast must fire at dispatch time, regardless of any
        // other OUT activity for the same user in the gap between schedule and dispatch.
        // (Earlier exclusion logic was removed; this test asserts the new behaviour.)
        LocalDateTime bCreated = LocalDateTime.now().minusHours(2);
        LocalDateTime scheduled = LocalDateTime.now().minusMinutes(1); // due
        Message m = scheduledBroadcastRow(1L, 7L, 99L, bCreated, scheduled);

        when(msgRepo.findDueForDispatch(eq(Message.STATUS_QUEUED), any())).thenReturn(
                Collections.singletonList(m));
        when(broadcastRepo.findById(99L)).thenReturn(Optional.of(broadcast(99L, bCreated)));

        svc.dispatchQueued();

        // Row must be sent normally — no CANCELLED save.
        verify(messageService).sendNow(eq(m), any());
        verify(msgRepo, never()).save(any(Message.class));
        verify(msgRepo, never()).countOutboundFinalisedSince(anyLong(), any(), anyLong());
    }

    @Test
    void dispatchesNormally_forNonBroadcastQueuedMessage() {
        // No broadcastId → broadcast-cancel check is skipped entirely, message is sent.
        Message m = scheduledBroadcastRow(3L, 7L, null,
                LocalDateTime.now().minusHours(2), LocalDateTime.now().minusMinutes(1));

        when(msgRepo.findDueForDispatch(eq(Message.STATUS_QUEUED), any())).thenReturn(
                Collections.singletonList(m));

        svc.dispatchQueued();

        verify(messageService).sendNow(eq(m), any());
        verify(broadcastRepo, never()).findById(anyLong());
        verify(msgRepo, never()).countOutboundFinalisedSince(anyLong(), any(), anyLong());
    }

    @Test
    void dispatchesImmediateBroadcast_whenScheduledAtEqualsCreatedAt() {
        // Immediate (non-scheduled) broadcast row dispatches normally.
        LocalDateTime t = LocalDateTime.now().minusMinutes(1);
        Message m = scheduledBroadcastRow(4L, 7L, 99L, t, t);

        when(msgRepo.findDueForDispatch(eq(Message.STATUS_QUEUED), any())).thenReturn(
                Collections.singletonList(m));
        when(broadcastRepo.findById(99L)).thenReturn(Optional.of(broadcast(99L, t)));

        svc.dispatchQueued();

        verify(messageService).sendNow(eq(m), any());
        verify(msgRepo, never()).countOutboundFinalisedSince(anyLong(), any(), anyLong());
    }

    private static <T> T eq(T expected) { return org.mockito.ArgumentMatchers.eq(expected); }
}
