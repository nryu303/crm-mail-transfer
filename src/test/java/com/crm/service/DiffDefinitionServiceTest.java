package com.crm.service;

import com.crm.entity.DiffDefinition;
import com.crm.entity.DiffStep;
import com.crm.repository.DiffDefinitionRepository;
import com.crm.repository.DiffStepRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DiffDefinitionServiceTest {

    private DiffDefinitionRepository defRepo;
    private DiffStepRepository stepRepo;
    private DiffDefinitionService svc;

    @BeforeEach
    void setUp() {
        defRepo = mock(DiffDefinitionRepository.class);
        stepRepo = mock(DiffStepRepository.class);
        svc = new DiffDefinitionService(defRepo, stepRepo);
        when(defRepo.save(any(DiffDefinition.class))).thenAnswer(inv -> {
            DiffDefinition d = inv.getArgument(0);
            if (d.getId() == null) d.setId(1L);
            return d;
        });
        when(stepRepo.save(any(DiffStep.class))).thenAnswer(inv -> {
            DiffStep s = inv.getArgument(0);
            if (s.getId() == null) s.setId(100L);
            return s;
        });
    }

    @Test
    void create_rejectsWhenAtMaxDefinitions() {
        when(defRepo.count()).thenReturn((long) DiffDefinitionService.MAX_DEFINITIONS);

        assertThatThrownBy(() -> svc.create("new diff"))
                .isInstanceOf(DiffDefinitionService.TooManyDefinitionsException.class);
    }

    @Test
    void create_succeedsBelowMax() {
        when(defRepo.count()).thenReturn(1L);

        DiffDefinition d = svc.create("diffA");

        assertThat(d.getName()).isEqualTo("diffA");
    }

    @Test
    void addStep_rejectsWhenDefinitionMissing() {
        when(defRepo.existsById(42L)).thenReturn(false);

        assertThatThrownBy(() -> svc.addStep(42L, DiffStep.OFFSET_MINUTES, 5, null, null,
                DiffStep.STEP_MESSAGE, DiffStep.CHANNEL_SMS, null, "body", null))
                .isInstanceOf(DiffDefinitionService.NotFoundException.class);
    }

    @Test
    void addStep_rejectsWhenAtMaxSteps() {
        when(defRepo.existsById(1L)).thenReturn(true);
        when(stepRepo.countByDiffDefinitionId(1L)).thenReturn((long) DiffDefinitionService.MAX_STEPS_PER_DEFINITION);

        assertThatThrownBy(() -> svc.addStep(1L, DiffStep.OFFSET_MINUTES, 5, null, null,
                DiffStep.STEP_MESSAGE, DiffStep.CHANNEL_SMS, null, "body", null))
                .isInstanceOf(DiffDefinitionService.TooManyStepsException.class);
    }

    @Test
    void addStep_minutesModeRequiresPositiveMinutes() {
        when(defRepo.existsById(1L)).thenReturn(true);
        when(stepRepo.countByDiffDefinitionId(1L)).thenReturn(0L);

        assertThatThrownBy(() -> svc.addStep(1L, DiffStep.OFFSET_MINUTES, 0, null, null,
                DiffStep.STEP_MESSAGE, DiffStep.CHANNEL_SMS, null, "body", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addStep_daysModeRequiresClockTime() {
        when(defRepo.existsById(1L)).thenReturn(true);
        when(stepRepo.countByDiffDefinitionId(1L)).thenReturn(0L);

        assertThatThrownBy(() -> svc.addStep(1L, DiffStep.OFFSET_DAYS, null, 1, null,
                DiffStep.STEP_MESSAGE, DiffStep.CHANNEL_SMS, null, "body", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addStep_messageStepRequiresChannelAndBody() {
        when(defRepo.existsById(1L)).thenReturn(true);
        when(stepRepo.countByDiffDefinitionId(1L)).thenReturn(0L);

        assertThatThrownBy(() -> svc.addStep(1L, DiffStep.OFFSET_MINUTES, 5, null, null,
                DiffStep.STEP_MESSAGE, null, null, "body", null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> svc.addStep(1L, DiffStep.OFFSET_MINUTES, 5, null, null,
                DiffStep.STEP_MESSAGE, DiffStep.CHANNEL_SMS, null, "  ", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addStep_htmlSwitchStepRejectsOutOfRangeSlot() {
        when(defRepo.existsById(1L)).thenReturn(true);
        when(stepRepo.countByDiffDefinitionId(1L)).thenReturn(0L);

        assertThatThrownBy(() -> svc.addStep(1L, DiffStep.OFFSET_MINUTES, 5, null, null,
                DiffStep.STEP_HTML_SWITCH, null, null, null, 99))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addStep_htmlSwitchStepAcceptsValidSlot() {
        when(defRepo.existsById(1L)).thenReturn(true);
        when(stepRepo.countByDiffDefinitionId(1L)).thenReturn(0L);

        DiffStep s = svc.addStep(1L, DiffStep.OFFSET_MINUTES, 5, null, null,
                DiffStep.STEP_HTML_SWITCH, null, null, null, 4);

        assertThat(s.getMemoSlot()).isEqualTo(4);
        assertThat(s.getStepOrder()).isEqualTo(0);
    }

    @Test
    void addStep_appendsAtNextOrder() {
        when(defRepo.existsById(1L)).thenReturn(true);
        when(stepRepo.countByDiffDefinitionId(1L)).thenReturn(3L);

        DiffStep s = svc.addStep(1L, DiffStep.OFFSET_MINUTES, 5, null, null,
                DiffStep.STEP_MESSAGE, DiffStep.CHANNEL_EMAIL, "subj", "body", null);

        assertThat(s.getStepOrder()).isEqualTo(3);
        assertThat(s.getSubject()).isEqualTo("subj");
    }

    @Test
    void updateStep_returnsEmptyWhenNotFound() {
        when(stepRepo.findById(42L)).thenReturn(Optional.empty());

        Optional<DiffStep> result = svc.updateStep(42L, DiffStep.OFFSET_MINUTES, 5, null, null,
                DiffStep.STEP_MESSAGE, DiffStep.CHANNEL_SMS, null, "body", null);

        assertThat(result).isEmpty();
    }

    @Test
    void updateStep_updatesFields() {
        DiffStep existing = new DiffStep();
        existing.setId(3L);
        existing.setDiffDefinitionId(1L);
        existing.setStepOrder(0);
        existing.setOffsetMode(DiffStep.OFFSET_MINUTES);
        existing.setOffsetMinutes(1);
        existing.setStepType(DiffStep.STEP_MESSAGE);
        existing.setChannel(DiffStep.CHANNEL_SMS);
        existing.setBody("old body");
        when(stepRepo.findById(3L)).thenReturn(Optional.of(existing));

        Optional<DiffStep> result = svc.updateStep(3L, DiffStep.OFFSET_DAYS, null, 2, "10:00",
                DiffStep.STEP_MESSAGE, DiffStep.CHANNEL_EMAIL, "new subj", "new body", null);

        assertThat(result).isPresent();
        assertThat(existing.getOffsetMode()).isEqualTo(DiffStep.OFFSET_DAYS);
        assertThat(existing.getOffsetDays()).isEqualTo(2);
        assertThat(existing.getOffsetClockTime()).isEqualTo("10:00");
        assertThat(existing.getChannel()).isEqualTo(DiffStep.CHANNEL_EMAIL);
        assertThat(existing.getSubject()).isEqualTo("new subj");
        assertThat(existing.getBody()).isEqualTo("new body");
    }

    @Test
    void deleteStep_renumbersRemainingSteps() {
        DiffStep target = new DiffStep();
        target.setId(2L);
        target.setDiffDefinitionId(1L);
        when(stepRepo.findById(2L)).thenReturn(Optional.of(target));

        DiffStep remaining1 = new DiffStep();
        remaining1.setId(1L);
        remaining1.setStepOrder(0);
        DiffStep remaining2 = new DiffStep();
        remaining2.setId(3L);
        remaining2.setStepOrder(2);
        List<DiffStep> remaining = new ArrayList<>(Arrays.asList(remaining1, remaining2));
        when(stepRepo.findByDiffDefinitionIdOrderByStepOrderAsc(1L)).thenReturn(remaining);

        svc.deleteStep(2L);

        assertThat(remaining1.getStepOrder()).isEqualTo(0);
        assertThat(remaining2.getStepOrder()).isEqualTo(1);
    }

    @Test
    void deleteStep_noOpWhenNotFound() {
        when(stepRepo.findById(99L)).thenReturn(Optional.empty());

        svc.deleteStep(99L);
        // no exception, no renumbering attempted
    }
}
