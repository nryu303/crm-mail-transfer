package com.crm.service;

import com.crm.dto.UserSearchForm;
import com.crm.entity.CrmUser;
import com.crm.entity.DiffDefinition;
import com.crm.entity.DiffSchedule;
import com.crm.repository.CrmUserRepository;
import com.crm.repository.DiffDefinitionRepository;
import com.crm.repository.DiffScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiffScheduleServiceTest {

    private DiffScheduleRepository scheduleRepo;
    private DiffDefinitionRepository definitionRepo;
    private CrmUserService crmUserService;
    private CrmUserRepository userRepository;
    private AuditLogService auditLog;
    private DiffScheduleService svc;

    @BeforeEach
    void setUp() {
        scheduleRepo = mock(DiffScheduleRepository.class);
        definitionRepo = mock(DiffDefinitionRepository.class);
        crmUserService = mock(CrmUserService.class);
        userRepository = mock(CrmUserRepository.class);
        auditLog = mock(AuditLogService.class);
        svc = new DiffScheduleService(scheduleRepo, definitionRepo, crmUserService, userRepository, auditLog);
        when(scheduleRepo.save(any(DiffSchedule.class))).thenAnswer(inv -> {
            DiffSchedule s = inv.getArgument(0);
            if (s.getId() == null) s.setId(1L);
            return s;
        });
    }

    private static DiffDefinition definition(Long id, String name, int slot) {
        DiffDefinition d = new DiffDefinition();
        d.setId(id);
        d.setName(name);
        d.setMemoSlot(slot);
        return d;
    }

    // ---- computeScheduledFor ----

    @Test
    void computeScheduledFor_minutesMode_addsMinutesToSetAt() {
        LocalDateTime setAt = LocalDateTime.of(2026, 8, 30, 10, 0);
        LocalDateTime result = DiffScheduleService.computeScheduledFor(setAt, DiffSchedule.OFFSET_MINUTES, 3, null, null);
        assertThat(result).isEqualTo(LocalDateTime.of(2026, 8, 30, 10, 3));
    }

    @Test
    void computeScheduledFor_daysMode_usesNextCalendarDayAtClockTime() {
        LocalDateTime setAt = LocalDateTime.of(2026, 8, 30, 9, 0);
        LocalDateTime result = DiffScheduleService.computeScheduledFor(setAt, DiffSchedule.OFFSET_DAYS, null, 1, "18:00");
        assertThat(result).isEqualTo(LocalDateTime.of(2026, 8, 31, 18, 0));
    }

    @Test
    void computeScheduledFor_daysMode_multiDayOffset() {
        LocalDateTime setAt = LocalDateTime.of(2026, 8, 30, 9, 0);
        LocalDateTime result = DiffScheduleService.computeScheduledFor(setAt, DiffSchedule.OFFSET_DAYS, null, 3, "12:00");
        assertThat(result).isEqualTo(LocalDateTime.of(2026, 9, 2, 12, 0));
    }

    @Test
    void computeScheduledFor_rejectsZeroOrNegativeMinutes() {
        LocalDateTime setAt = LocalDateTime.now();
        assertThatThrownBy(() -> DiffScheduleService.computeScheduledFor(setAt, DiffSchedule.OFFSET_MINUTES, 0, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void computeScheduledFor_rejectsZeroOrNegativeDays() {
        LocalDateTime setAt = LocalDateTime.now();
        assertThatThrownBy(() -> DiffScheduleService.computeScheduledFor(setAt, DiffSchedule.OFFSET_DAYS, null, 0, "10:00"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- register ----

    @Test
    void register_snapshotsTargetIdsAtSetTime() {
        when(definitionRepo.findById(1L)).thenReturn(Optional.of(definition(1L, "diffA", 2)));
        when(crmUserService.findIdsBySearch(any(UserSearchForm.class), any())).thenReturn(Arrays.asList(1L, 2L, 3L));

        DiffSchedule saved = svc.register(1L, DiffSchedule.TARGET_FOLDER, "フォルダA",
                DiffSchedule.OFFSET_MINUTES, 5, null, null, "admin");

        assertThat(saved.getTargetUserIds()).isEqualTo("1,2,3");
        assertThat(saved.getDiffNameSnapshot()).isEqualTo("diffA");
        assertThat(saved.getMemoSlotSnapshot()).isEqualTo(2);
        assertThat(saved.getStatus()).isEqualTo(DiffSchedule.STATUS_PENDING);
    }

    @Test
    void register_throwsWhenDefinitionNotFound() {
        when(definitionRepo.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.register(99L, DiffSchedule.TARGET_FOLDER, "F",
                DiffSchedule.OFFSET_MINUTES, 1, null, null, "admin"))
                .isInstanceOf(DiffScheduleService.NotFoundException.class);
    }

    // ---- cancel ----

    @Test
    void cancel_onlyCancelsWhenStillPending() {
        DiffSchedule s = new DiffSchedule();
        s.setId(5L);
        s.setStatus(DiffSchedule.STATUS_EXECUTED);
        when(scheduleRepo.findById(5L)).thenReturn(Optional.of(s));

        boolean ok = svc.cancel(5L, "admin");

        assertThat(ok).isFalse();
        verify(scheduleRepo, never()).save(any());
    }

    @Test
    void cancel_racesAgainstConcurrentExecution() {
        // Simulates the dispatcher having already flipped the row to EXECUTED between the
        // pending-list render and this cancel click — the re-fetch inside cancel() must see
        // that and refuse to overwrite it back to CANCELLED.
        DiffSchedule alreadyExecuted = new DiffSchedule();
        alreadyExecuted.setId(6L);
        alreadyExecuted.setStatus(DiffSchedule.STATUS_EXECUTED);
        when(scheduleRepo.findById(6L)).thenReturn(Optional.of(alreadyExecuted));

        boolean ok = svc.cancel(6L, "admin");

        assertThat(ok).isFalse();
        assertThat(alreadyExecuted.getStatus()).isEqualTo(DiffSchedule.STATUS_EXECUTED);
    }

    @Test
    void cancel_succeedsWhenPending() {
        DiffSchedule s = new DiffSchedule();
        s.setId(7L);
        s.setStatus(DiffSchedule.STATUS_PENDING);
        when(scheduleRepo.findById(7L)).thenReturn(Optional.of(s));

        boolean ok = svc.cancel(7L, "admin_taro");

        assertThat(ok).isTrue();
        assertThat(s.getStatus()).isEqualTo(DiffSchedule.STATUS_CANCELLED);
        assertThat(s.getCancelledByAdminName()).isEqualTo("admin_taro");
        verify(scheduleRepo).save(s);
    }

    @Test
    void cancelByTarget_cancelsAllMatchingPendingRows_skipsNonPending() {
        DiffSchedule s1 = new DiffSchedule();
        s1.setId(1L); s1.setStatus(DiffSchedule.STATUS_PENDING);
        DiffSchedule s2 = new DiffSchedule();
        s2.setId(2L); s2.setStatus(DiffSchedule.STATUS_EXECUTED); // race: already fired
        when(scheduleRepo.findByStatusAndTargetTypeAndTargetValue(DiffSchedule.STATUS_PENDING, "FOLDER", "F"))
                .thenReturn(Arrays.asList(s1, s2));
        when(scheduleRepo.findById(1L)).thenReturn(Optional.of(s1));
        when(scheduleRepo.findById(2L)).thenReturn(Optional.of(s2));

        int n = svc.cancelByTarget("FOLDER", "F", null, "admin");

        assertThat(n).isEqualTo(1);
        assertThat(s1.getStatus()).isEqualTo(DiffSchedule.STATUS_CANCELLED);
        assertThat(s2.getStatus()).isEqualTo(DiffSchedule.STATUS_EXECUTED);
    }

    // ---- execute ----

    @Test
    void execute_activatesMemoSlotForEveryTargetUser_chunked() {
        List<Long> ids = new java.util.ArrayList<>();
        for (long i = 1; i <= 1200; i++) ids.add(i);
        String csv = String.join(",", ids.stream().map(String::valueOf).toArray(String[]::new));

        DiffSchedule s = new DiffSchedule();
        s.setId(1L);
        s.setTargetUserIds(csv);
        s.setMemoSlotSnapshot(3);

        // Each chunk of up to 500 IDs returns the same number of CrmUser objects.
        when(userRepository.findAllById(anyList())).thenAnswer(inv -> {
            List<Long> chunk = inv.getArgument(0);
            List<CrmUser> users = new java.util.ArrayList<>();
            for (Long id : chunk) {
                CrmUser u = new CrmUser();
                u.setId(id);
                users.add(u);
            }
            return users;
        });

        svc.execute(s);

        verify(userRepository, times(3)).findAllById(anyList()); // 500+500+200
        verify(userRepository, times(1200)).save(any(CrmUser.class));
        assertThat(s.getStatus()).isEqualTo(DiffSchedule.STATUS_EXECUTED);
        assertThat(s.getResultDetail()).contains("applied=1200").contains("missing=0");
    }

    @Test
    void execute_recordsMissingCountForDeletedUsers() {
        DiffSchedule s = new DiffSchedule();
        s.setId(2L);
        s.setTargetUserIds("1,2,3,4,5");
        s.setMemoSlotSnapshot(1);

        // Only 3 of the 5 requested IDs still exist.
        List<CrmUser> found = Arrays.asList(user(1L), user(2L), user(3L));
        when(userRepository.findAllById(anyList())).thenReturn(found);

        svc.execute(s);

        assertThat(s.getResultDetail()).contains("applied=3").contains("missing=2").contains("total=5");
    }

    private static CrmUser user(Long id) {
        CrmUser u = new CrmUser();
        u.setId(id);
        return u;
    }
}
