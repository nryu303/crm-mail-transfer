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
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Scheduled broadcast snapshot-exclude logic: when a QUEUED broadcast row fires AFTER the
 * user already received another OUT message (sent between broadcast.createdAt and the
 * row's scheduledAt), the row must be cancelled, NOT sent.
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
        svc = new ScheduledTaskService(msgRepo, poolRepo, messageService,
                settingRepo, bindingRepo, domainSettings, broadcastRepo,
                folderSettings, folderRetention);
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
    void cancelsBroadcastRow_whenUserReceivedAnotherSendAfterBroadcastCreation() {
        LocalDateTime bCreated = LocalDateTime.now().minusHours(2);
        LocalDateTime scheduled = LocalDateTime.now().minusMinutes(1); // due
        Message m = scheduledBroadcastRow(1L, 7L, 99L, bCreated, scheduled);

        when(msgRepo.findDueForDispatch(eq(Message.STATUS_QUEUED), any())).thenReturn(
                Collections.singletonList(m));
        when(broadcastRepo.findById(99L)).thenReturn(Optional.of(broadcast(99L, bCreated)));
        // User has 1 other finalised send after the broadcast was scheduled — exclusion fires.
        when(msgRepo.countOutboundFinalisedSince(7L, bCreated, 1L)).thenReturn(1L);

        svc.dispatchQueued();

        // sendNow MUST NOT be called for this row.
        verify(messageService, never()).sendNow(any(), any());
        // Row is marked CANCELLED and saved with a reason.
        ArgumentCaptor<Message> cap = ArgumentCaptor.forClass(Message.class);
        verify(msgRepo).save(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(Message.STATUS_CANCELLED);
        assertThat(cap.getValue().getErrorMessage()).contains("excluded");
    }

    @Test
    void dispatchesNormally_whenNoOtherSendsHappenedAfterBroadcastCreation() {
        LocalDateTime bCreated = LocalDateTime.now().minusHours(2);
        LocalDateTime scheduled = LocalDateTime.now().minusMinutes(1);
        Message m = scheduledBroadcastRow(2L, 7L, 99L, bCreated, scheduled);

        when(msgRepo.findDueForDispatch(eq(Message.STATUS_QUEUED), any())).thenReturn(
                Collections.singletonList(m));
        when(broadcastRepo.findById(99L)).thenReturn(Optional.of(broadcast(99L, bCreated)));
        when(msgRepo.countOutboundFinalisedSince(7L, bCreated, 2L)).thenReturn(0L);

        svc.dispatchQueued();

        verify(messageService).sendNow(eq(m), any());
        verify(msgRepo, never()).save(any(Message.class));
    }

    @Test
    void ignoresExclusion_forNonBroadcastQueuedMessage() {
        // No broadcastId → exclusion logic should be skipped entirely.
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
    void ignoresExclusion_whenScheduledAtEqualsCreatedAt_immediateSend() {
        // Immediate broadcast row: scheduledAt is not strictly after createdAt → no exclusion.
        // (The cancel-race gate added 2026-05-21 DOES still call findById to check whether
        // the parent broadcast was cancelled — that's orthogonal to the exclusion logic and
        // necessary even for immediate-send broadcasts. The test no longer requires the
        // broadcast to be looked up zero times; it just confirms exclusion-via-other-sends
        // doesn't fire.)
        LocalDateTime t = LocalDateTime.now().minusMinutes(1);
        Message m = scheduledBroadcastRow(4L, 7L, 99L, t, t);

        when(msgRepo.findDueForDispatch(eq(Message.STATUS_QUEUED), any())).thenReturn(
                Collections.singletonList(m));

        svc.dispatchQueued();

        verify(messageService).sendNow(eq(m), any());
        verify(msgRepo, never()).countOutboundFinalisedSince(anyLong(), any(), anyLong());
    }

    private static <T> T eq(T expected) { return org.mockito.ArgumentMatchers.eq(expected); }
}
