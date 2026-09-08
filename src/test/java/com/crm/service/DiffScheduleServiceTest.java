package com.crm.service;

import com.crm.dto.BroadcastForm;
import com.crm.dto.UserSearchForm;
import com.crm.entity.Broadcast;
import com.crm.entity.CrmUser;
import com.crm.entity.DiffDefinition;
import com.crm.entity.DiffSchedule;
import com.crm.entity.DiffScheduleStep;
import com.crm.entity.DiffStep;
import com.crm.repository.CrmUserRepository;
import com.crm.repository.DiffDefinitionRepository;
import com.crm.repository.DiffScheduleRepository;
import com.crm.repository.DiffScheduleStepRepository;
import com.crm.repository.DiffStepRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
    private DiffScheduleStepRepository scheduleStepRepo;
    private DiffDefinitionRepository definitionRepo;
    private DiffStepRepository stepRepo;
    private CrmUserService crmUserService;
    private CrmUserRepository userRepository;
    private BroadcastService broadcastService;
    private AuditLogService auditLog;
    private DiffScheduleService svc;

    @BeforeEach
    void setUp() {
        scheduleRepo = mock(DiffScheduleRepository.class);
        scheduleStepRepo = mock(DiffScheduleStepRepository.class);
        definitionRepo = mock(DiffDefinitionRepository.class);
        stepRepo = mock(DiffStepRepository.class);
        crmUserService = mock(CrmUserService.class);
        userRepository = mock(CrmUserRepository.class);
        broadcastService = mock(BroadcastService.class);
        auditLog = mock(AuditLogService.class);
        svc = new DiffScheduleService(scheduleRepo, scheduleStepRepo, definitionRepo, stepRepo,
                crmUserService, userRepository, broadcastService, auditLog);
        when(scheduleRepo.save(any(DiffSchedule.class))).thenAnswer(inv -> {
            DiffSchedule s = inv.getArgument(0);
            if (s.getId() == null) s.setId(1L);
            return s;
        });
        when(scheduleStepRepo.save(any(DiffScheduleStep.class))).thenAnswer(inv -> {
            DiffScheduleStep s = inv.getArgument(0);
            if (s.getId() == null) s.setId(1000L);
            return s;
        });
    }

    private static DiffDefinition definition(Long id, String name) {
        DiffDefinition d = new DiffDefinition();
        d.setId(id);
        d.setName(name);
        return d;
    }

    private static DiffStep messageStep(Long defId, int order, String offsetMode, Integer minutes,
                                         Integer days, String clockTime, String channel, String body) {
        DiffStep s = new DiffStep();
        s.setDiffDefinitionId(defId);
        s.setStepOrder(order);
        s.setOffsetMode(offsetMode);
        s.setOffsetMinutes(minutes);
        s.setOffsetDays(days);
        s.setOffsetClockTime(clockTime);
        s.setStepType(DiffStep.STEP_MESSAGE);
        s.setChannel(channel);
        s.setBody(body);
        return s;
    }

    private static DiffStep htmlSwitchStep(Long defId, int order, int slot) {
        DiffStep s = new DiffStep();
        s.setDiffDefinitionId(defId);
        s.setStepOrder(order);
        s.setOffsetMode(DiffStep.OFFSET_MINUTES);
        s.setOffsetMinutes(1);
        s.setStepType(DiffStep.STEP_HTML_SWITCH);
        s.setMemoSlot(slot);
        return s;
    }

    // ---- computeScheduledFor ----

    @Test
    void computeScheduledFor_minutesMode_addsMinutesToSetAt() {
        LocalDateTime setAt = LocalDateTime.of(2026, 8, 30, 10, 0);
        LocalDateTime result = DiffScheduleService.computeScheduledFor(setAt, DiffStep.OFFSET_MINUTES, 3, null, null);
        assertThat(result).isEqualTo(LocalDateTime.of(2026, 8, 30, 10, 3));
    }

    @Test
    void computeScheduledFor_daysMode_usesFutureCalendarDayAtClockTime() {
        LocalDateTime setAt = LocalDateTime.of(2026, 8, 30, 9, 0);
        LocalDateTime result = DiffScheduleService.computeScheduledFor(setAt, DiffStep.OFFSET_DAYS, null, 1, "18:00");
        assertThat(result).isEqualTo(LocalDateTime.of(2026, 8, 31, 18, 0));
    }

    @Test
    void computeScheduledFor_daysMode_multiDayOffset() {
        LocalDateTime setAt = LocalDateTime.of(2026, 8, 30, 9, 0);
        LocalDateTime result = DiffScheduleService.computeScheduledFor(setAt, DiffStep.OFFSET_DAYS, null, 3, "12:00");
        assertThat(result).isEqualTo(LocalDateTime.of(2026, 9, 2, 12, 0));
    }

    @Test
    void computeScheduledFor_rejectsZeroOrNegativeMinutes() {
        LocalDateTime setAt = LocalDateTime.now();
        assertThatThrownBy(() -> DiffScheduleService.computeScheduledFor(setAt, DiffStep.OFFSET_MINUTES, 0, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void computeScheduledFor_rejectsZeroOrNegativeDays() {
        LocalDateTime setAt = LocalDateTime.now();
        assertThatThrownBy(() -> DiffScheduleService.computeScheduledFor(setAt, DiffStep.OFFSET_DAYS, null, 0, "10:00"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- register ----

    @Test
    void register_snapshotsTargetIdsAndMaterializesOneStepPerDefinitionStep() {
        when(definitionRepo.findById(1L)).thenReturn(Optional.of(definition(1L, "diffA")));
        when(stepRepo.findByDiffDefinitionIdOrderByStepOrderAsc(1L)).thenReturn(Arrays.asList(
                messageStep(1L, 0, DiffStep.OFFSET_MINUTES, 5, null, null, DiffStep.CHANNEL_SMS, "body1"),
                htmlSwitchStep(1L, 1, 2)));
        when(crmUserService.findIdsBySearch(any(UserSearchForm.class), any())).thenReturn(Arrays.asList(1L, 2L, 3L));

        DiffSchedule saved = svc.register(1L, DiffSchedule.TARGET_FOLDER, "フォルダA", 9L, "admin");

        assertThat(saved.getTargetUserIds()).isEqualTo("1,2,3");
        assertThat(saved.getDiffNameSnapshot()).isEqualTo("diffA");
        verify(scheduleStepRepo, times(2)).save(any(DiffScheduleStep.class));
    }

    @Test
    void register_throwsWhenDefinitionNotFound() {
        when(definitionRepo.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.register(99L, DiffSchedule.TARGET_FOLDER, "F", 9L, "admin"))
                .isInstanceOf(DiffScheduleService.NotFoundException.class);
    }

    @Test
    void register_throwsWhenDefinitionHasNoSteps() {
        when(definitionRepo.findById(1L)).thenReturn(Optional.of(definition(1L, "diffA")));
        when(stepRepo.findByDiffDefinitionIdOrderByStepOrderAsc(1L)).thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> svc.register(1L, DiffSchedule.TARGET_FOLDER, "F", 9L, "admin"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- cancelStep ----

    @Test
    void cancelStep_onlyCancelsWhenStillPending() {
        DiffScheduleStep s = new DiffScheduleStep();
        s.setId(5L);
        s.setStatus(DiffScheduleStep.STATUS_EXECUTED);
        when(scheduleStepRepo.findById(5L)).thenReturn(Optional.of(s));

        boolean ok = svc.cancelStep(5L, "admin");

        assertThat(ok).isFalse();
        verify(scheduleStepRepo, never()).save(any());
    }

    @Test
    void cancelStep_racesAgainstConcurrentExecution() {
        DiffScheduleStep alreadyExecuted = new DiffScheduleStep();
        alreadyExecuted.setId(6L);
        alreadyExecuted.setStatus(DiffScheduleStep.STATUS_EXECUTED);
        when(scheduleStepRepo.findById(6L)).thenReturn(Optional.of(alreadyExecuted));

        boolean ok = svc.cancelStep(6L, "admin");

        assertThat(ok).isFalse();
        assertThat(alreadyExecuted.getStatus()).isEqualTo(DiffScheduleStep.STATUS_EXECUTED);
    }

    @Test
    void cancelStep_succeedsWhenPending() {
        DiffScheduleStep s = new DiffScheduleStep();
        s.setId(7L);
        s.setStatus(DiffScheduleStep.STATUS_PENDING);
        when(scheduleStepRepo.findById(7L)).thenReturn(Optional.of(s));

        boolean ok = svc.cancelStep(7L, "admin_taro");

        assertThat(ok).isTrue();
        assertThat(s.getStatus()).isEqualTo(DiffScheduleStep.STATUS_CANCELLED);
        assertThat(s.getCancelledByAdminName()).isEqualTo("admin_taro");
        verify(scheduleStepRepo).save(s);
    }

    @Test
    void cancelSchedule_cancelsAllPendingStepsUnderOneSchedule() {
        DiffScheduleStep s1 = new DiffScheduleStep();
        s1.setId(1L); s1.setStatus(DiffScheduleStep.STATUS_PENDING);
        DiffScheduleStep s2 = new DiffScheduleStep();
        s2.setId(2L); s2.setStatus(DiffScheduleStep.STATUS_PENDING);
        when(scheduleStepRepo.findByStatusAndDiffScheduleId(DiffScheduleStep.STATUS_PENDING, 50L))
                .thenReturn(Arrays.asList(s1, s2));
        when(scheduleStepRepo.findById(1L)).thenReturn(Optional.of(s1));
        when(scheduleStepRepo.findById(2L)).thenReturn(Optional.of(s2));

        int n = svc.cancelSchedule(50L, "admin");

        assertThat(n).isEqualTo(2);
        assertThat(s1.getStatus()).isEqualTo(DiffScheduleStep.STATUS_CANCELLED);
        assertThat(s2.getStatus()).isEqualTo(DiffScheduleStep.STATUS_CANCELLED);
    }

    @Test
    void cancelByTarget_cancelsAllMatchingPendingRows_skipsNonPending() {
        DiffScheduleStep s1 = new DiffScheduleStep();
        s1.setId(1L); s1.setStatus(DiffScheduleStep.STATUS_PENDING);
        DiffScheduleStep s2 = new DiffScheduleStep();
        s2.setId(2L); s2.setStatus(DiffScheduleStep.STATUS_EXECUTED); // race: already fired
        when(scheduleStepRepo.findPendingByTarget("FOLDER", "F")).thenReturn(Arrays.asList(s1, s2));
        when(scheduleStepRepo.findById(1L)).thenReturn(Optional.of(s1));
        when(scheduleStepRepo.findById(2L)).thenReturn(Optional.of(s2));

        int n = svc.cancelByTarget("FOLDER", "F", null, "admin");

        assertThat(n).isEqualTo(1);
        assertThat(s1.getStatus()).isEqualTo(DiffScheduleStep.STATUS_CANCELLED);
        assertThat(s2.getStatus()).isEqualTo(DiffScheduleStep.STATUS_EXECUTED);
    }

    // ---- execute: HTML_SWITCH ----

    @Test
    void execute_htmlSwitch_activatesMemoSlotForEveryTargetUser_chunked() {
        List<Long> ids = new java.util.ArrayList<>();
        for (long i = 1; i <= 1200; i++) ids.add(i);
        String csv = String.join(",", ids.stream().map(String::valueOf).toArray(String[]::new));

        DiffSchedule schedule = new DiffSchedule();
        schedule.setId(1L);
        schedule.setTargetUserIds(csv);
        when(scheduleRepo.findById(1L)).thenReturn(Optional.of(schedule));

        DiffScheduleStep step = new DiffScheduleStep();
        step.setId(10L);
        step.setDiffScheduleId(1L);
        step.setStepType(DiffStep.STEP_HTML_SWITCH);
        step.setMemoSlotSnapshot(3);

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

        svc.execute(step);

        verify(userRepository, times(3)).findAllById(anyList()); // 500+500+200
        verify(userRepository, times(1200)).save(any(CrmUser.class));
        assertThat(step.getStatus()).isEqualTo(DiffScheduleStep.STATUS_EXECUTED);
        assertThat(step.getResultDetail()).contains("applied=1200").contains("missing=0");
    }

    @Test
    void execute_htmlSwitch_recordsMissingCountForDeletedUsers() {
        DiffSchedule schedule = new DiffSchedule();
        schedule.setId(2L);
        schedule.setTargetUserIds("1,2,3,4,5");
        when(scheduleRepo.findById(2L)).thenReturn(Optional.of(schedule));

        DiffScheduleStep step = new DiffScheduleStep();
        step.setId(11L);
        step.setDiffScheduleId(2L);
        step.setStepType(DiffStep.STEP_HTML_SWITCH);
        step.setMemoSlotSnapshot(1);

        List<CrmUser> found = Arrays.asList(user(1L), user(2L), user(3L));
        when(userRepository.findAllById(anyList())).thenReturn(found);

        svc.execute(step);

        assertThat(step.getResultDetail()).contains("applied=3").contains("missing=2").contains("total=5");
    }

    @Test
    void execute_missingParentSchedule_marksFailed() {
        when(scheduleRepo.findById(404L)).thenReturn(Optional.empty());

        DiffScheduleStep step = new DiffScheduleStep();
        step.setId(12L);
        step.setDiffScheduleId(404L);
        step.setStepType(DiffStep.STEP_HTML_SWITCH);

        svc.execute(step);

        assertThat(step.getStatus()).isEqualTo(DiffScheduleStep.STATUS_FAILED);
    }

    // ---- execute: MESSAGE ----

    @Test
    void execute_message_queuesSmsViaBroadcastServiceAndMarksExecuted() {
        DiffSchedule schedule = new DiffSchedule();
        schedule.setId(3L);
        schedule.setTargetUserIds("1,2");
        schedule.setDiffNameSnapshot("あいさつ");
        schedule.setSetByAdminId(9L);
        when(scheduleRepo.findById(3L)).thenReturn(Optional.of(schedule));

        DiffScheduleStep step = new DiffScheduleStep();
        step.setId(20L);
        step.setDiffScheduleId(3L);
        step.setStepType(DiffStep.STEP_MESSAGE);
        step.setChannel(DiffStep.CHANNEL_SMS);
        step.setBodySnapshot("こんにちは %name% さん");

        Broadcast b = new Broadcast();
        b.setId(77L);
        b.setTotalCount(2);
        when(broadcastService.createAndQueueSms(any(BroadcastForm.class), org.mockito.ArgumentMatchers.eq(9L)))
                .thenReturn(b);

        svc.execute(step);

        assertThat(step.getStatus()).isEqualTo(DiffScheduleStep.STATUS_EXECUTED);
        assertThat(step.getResultDetail()).contains("broadcastId=77").contains("queued=2");
        verify(broadcastService).createAndQueueSms(any(BroadcastForm.class), org.mockito.ArgumentMatchers.eq(9L));
        verify(broadcastService, never()).createAndQueue(any(BroadcastForm.class), any());
    }

    @Test
    void execute_message_queuesEmailWithSubjectViaBroadcastService() {
        DiffSchedule schedule = new DiffSchedule();
        schedule.setId(4L);
        schedule.setTargetUserIds("1");
        schedule.setDiffNameSnapshot("1日後のあいさつ2");
        schedule.setSetByAdminId(9L);
        when(scheduleRepo.findById(4L)).thenReturn(Optional.of(schedule));

        DiffScheduleStep step = new DiffScheduleStep();
        step.setId(21L);
        step.setDiffScheduleId(4L);
        step.setStepType(DiffStep.STEP_MESSAGE);
        step.setChannel(DiffStep.CHANNEL_EMAIL);
        step.setSubjectSnapshot("件名です");
        step.setBodySnapshot("本文です");

        Broadcast b = new Broadcast();
        b.setId(88L);
        b.setTotalCount(1);
        when(broadcastService.createAndQueue(any(BroadcastForm.class), org.mockito.ArgumentMatchers.eq(9L)))
                .thenReturn(b);

        svc.execute(step);

        assertThat(step.getStatus()).isEqualTo(DiffScheduleStep.STATUS_EXECUTED);
        verify(broadcastService).createAndQueue(any(BroadcastForm.class), org.mockito.ArgumentMatchers.eq(9L));
    }

    @Test
    void execute_message_marksFailedWhenNoTargets() {
        DiffSchedule schedule = new DiffSchedule();
        schedule.setId(5L);
        schedule.setTargetUserIds("1");
        schedule.setDiffNameSnapshot("diffA");
        schedule.setSetByAdminId(9L);
        when(scheduleRepo.findById(5L)).thenReturn(Optional.of(schedule));

        DiffScheduleStep step = new DiffScheduleStep();
        step.setId(22L);
        step.setDiffScheduleId(5L);
        step.setStepType(DiffStep.STEP_MESSAGE);
        step.setChannel(DiffStep.CHANNEL_SMS);
        step.setBodySnapshot("body");

        when(broadcastService.createAndQueueSms(any(BroadcastForm.class), any()))
                .thenThrow(new BroadcastService.NoTargetsException("no deliverable targets"));

        svc.execute(step);

        assertThat(step.getStatus()).isEqualTo(DiffScheduleStep.STATUS_FAILED);
        assertThat(step.getResultDetail()).contains("送信先なし");
    }

    private static CrmUser user(Long id) {
        CrmUser u = new CrmUser();
        u.setId(id);
        return u;
    }
}
