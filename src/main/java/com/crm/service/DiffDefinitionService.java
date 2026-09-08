package com.crm.service;

import com.crm.entity.DiffDefinition;
import com.crm.entity.DiffStep;
import com.crm.repository.DiffDefinitionRepository;
import com.crm.repository.DiffStepRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** CRUD for diff definitions (max {@link #MAX_DEFINITIONS}) and the timed steps within each
 *  one (max {@link #MAX_STEPS_PER_DEFINITION}). */
@Service
public class DiffDefinitionService {

    public static final int MAX_DEFINITIONS = 20;
    public static final int MAX_STEPS_PER_DEFINITION = 20;

    private final DiffDefinitionRepository definitionRepository;
    private final DiffStepRepository stepRepository;

    public DiffDefinitionService(DiffDefinitionRepository definitionRepository,
                                  DiffStepRepository stepRepository) {
        this.definitionRepository = definitionRepository;
        this.stepRepository = stepRepository;
    }

    // ---- Definitions ----

    public List<DiffDefinition> listAll() {
        return definitionRepository.findAllByOrderByDisplayOrderAscIdAsc();
    }

    public Optional<DiffDefinition> findById(Long id) {
        return definitionRepository.findById(id);
    }

    public long count() {
        return definitionRepository.count();
    }

    public long stepCount(Long diffDefinitionId) {
        return stepRepository.countByDiffDefinitionId(diffDefinitionId);
    }

    @Transactional
    public DiffDefinition create(String name) {
        if (definitionRepository.count() >= MAX_DEFINITIONS) {
            throw new TooManyDefinitionsException();
        }
        DiffDefinition d = new DiffDefinition();
        d.setName(name);
        d.setDisplayOrder((int) definitionRepository.count());
        return definitionRepository.save(d);
    }

    @Transactional
    public Optional<DiffDefinition> rename(Long id, String name) {
        Optional<DiffDefinition> opt = definitionRepository.findById(id);
        if (!opt.isPresent()) return Optional.empty();
        DiffDefinition d = opt.get();
        d.setName(name);
        return Optional.of(definitionRepository.save(d));
    }

    @Transactional
    public void delete(Long id) {
        stepRepository.deleteByDiffDefinitionId(id);
        definitionRepository.deleteById(id);
    }

    // ---- Steps ----

    public List<DiffStep> listSteps(Long diffDefinitionId) {
        return stepRepository.findByDiffDefinitionIdOrderByStepOrderAsc(diffDefinitionId);
    }

    /** Appends a new step at the end of the definition's timeline. */
    @Transactional
    public DiffStep addStep(Long diffDefinitionId, String offsetMode, Integer offsetMinutes,
                             Integer offsetDays, String offsetClockTime, String stepType,
                             String channel, String subject, String body, Integer memoSlot) {
        if (!definitionRepository.existsById(diffDefinitionId)) {
            throw new NotFoundException("diff definition not found: " + diffDefinitionId);
        }
        long existing = stepRepository.countByDiffDefinitionId(diffDefinitionId);
        if (existing >= MAX_STEPS_PER_DEFINITION) {
            throw new TooManyStepsException();
        }
        validateStep(offsetMode, offsetMinutes, offsetDays, offsetClockTime, stepType, channel, body, memoSlot);

        DiffStep s = new DiffStep();
        s.setDiffDefinitionId(diffDefinitionId);
        s.setStepOrder((int) existing);
        s.setOffsetMode(offsetMode);
        s.setOffsetMinutes(offsetMinutes);
        s.setOffsetDays(offsetDays);
        s.setOffsetClockTime(offsetClockTime);
        s.setStepType(stepType);
        s.setChannel(DiffStep.STEP_MESSAGE.equals(stepType) ? channel : null);
        s.setSubject(DiffStep.STEP_MESSAGE.equals(stepType) && DiffStep.CHANNEL_EMAIL.equals(channel) ? subject : null);
        s.setBody(DiffStep.STEP_MESSAGE.equals(stepType) ? body : null);
        s.setMemoSlot(DiffStep.STEP_HTML_SWITCH.equals(stepType) ? clampSlot(memoSlot) : null);
        return stepRepository.save(s);
    }

    @Transactional
    public Optional<DiffStep> updateStep(Long stepId, String offsetMode, Integer offsetMinutes,
                                          Integer offsetDays, String offsetClockTime, String stepType,
                                          String channel, String subject, String body, Integer memoSlot) {
        Optional<DiffStep> opt = stepRepository.findById(stepId);
        if (!opt.isPresent()) return Optional.empty();
        validateStep(offsetMode, offsetMinutes, offsetDays, offsetClockTime, stepType, channel, body, memoSlot);

        DiffStep s = opt.get();
        s.setOffsetMode(offsetMode);
        s.setOffsetMinutes(offsetMinutes);
        s.setOffsetDays(offsetDays);
        s.setOffsetClockTime(offsetClockTime);
        s.setStepType(stepType);
        s.setChannel(DiffStep.STEP_MESSAGE.equals(stepType) ? channel : null);
        s.setSubject(DiffStep.STEP_MESSAGE.equals(stepType) && DiffStep.CHANNEL_EMAIL.equals(channel) ? subject : null);
        s.setBody(DiffStep.STEP_MESSAGE.equals(stepType) ? body : null);
        s.setMemoSlot(DiffStep.STEP_HTML_SWITCH.equals(stepType) ? clampSlot(memoSlot) : null);
        return Optional.of(stepRepository.save(s));
    }

    @Transactional
    public void deleteStep(Long stepId) {
        Optional<DiffStep> opt = stepRepository.findById(stepId);
        if (!opt.isPresent()) return;
        Long defId = opt.get().getDiffDefinitionId();
        stepRepository.deleteById(stepId);
        // Renumber remaining steps 0..N-1 so ordering stays contiguous for future inserts/display.
        List<DiffStep> remaining = stepRepository.findByDiffDefinitionIdOrderByStepOrderAsc(defId);
        int order = 0;
        for (DiffStep s : remaining) {
            s.setStepOrder(order++);
            stepRepository.save(s);
        }
    }

    private static void validateStep(String offsetMode, Integer offsetMinutes, Integer offsetDays,
                                      String offsetClockTime, String stepType, String channel,
                                      String body, Integer memoSlot) {
        if (DiffStep.OFFSET_MINUTES.equals(offsetMode)) {
            if (offsetMinutes == null || offsetMinutes < 1) throw new IllegalArgumentException("分後の値は1以上で指定してください");
        } else if (DiffStep.OFFSET_DAYS.equals(offsetMode)) {
            if (offsetDays == null || offsetDays < 1) throw new IllegalArgumentException("日数は1以上で指定してください");
            if (offsetClockTime == null || offsetClockTime.trim().isEmpty()) throw new IllegalArgumentException("送信時刻を指定してください");
        } else {
            throw new IllegalArgumentException("unknown offset mode: " + offsetMode);
        }
        if (DiffStep.STEP_MESSAGE.equals(stepType)) {
            if (channel == null || (!DiffStep.CHANNEL_EMAIL.equals(channel) && !DiffStep.CHANNEL_SMS.equals(channel))) {
                throw new IllegalArgumentException("チャネル(メール/SMS)を指定してください");
            }
            if (body == null || body.trim().isEmpty()) throw new IllegalArgumentException("本文を入力してください");
        } else if (DiffStep.STEP_HTML_SWITCH.equals(stepType)) {
            if (memoSlot == null || memoSlot < 1 || memoSlot > 10) throw new IllegalArgumentException("切替先スロット(1〜10)を指定してください");
        } else {
            throw new IllegalArgumentException("unknown step type: " + stepType);
        }
    }

    private static Integer clampSlot(Integer slot) {
        if (slot == null) return 1;
        return (slot < 1 || slot > 10) ? 1 : slot;
    }

    public static class TooManyDefinitionsException extends RuntimeException {
        public TooManyDefinitionsException() {
            super("差分名は最大" + MAX_DEFINITIONS + "件までしか登録できません");
        }
    }

    public static class TooManyStepsException extends RuntimeException {
        public TooManyStepsException() {
            super("1つの差分名につきステップは最大" + MAX_STEPS_PER_DEFINITION + "件までしか登録できません");
        }
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String msg) { super(msg); }
    }
}
