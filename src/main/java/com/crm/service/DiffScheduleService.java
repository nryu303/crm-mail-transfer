package com.crm.service;

import com.crm.dto.UserSearchForm;
import com.crm.entity.CrmUser;
import com.crm.entity.DiffDefinition;
import com.crm.entity.DiffSchedule;
import com.crm.repository.CrmUserRepository;
import com.crm.repository.DiffDefinitionRepository;
import com.crm.repository.DiffScheduleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Registers, dispatches, cancels, and reports on diff schedules — "apply DIFF_DEFINITION X
 * (a reply-page memo slot switch) to target Y (phone/email list or folder) at time Z".
 */
@Service
public class DiffScheduleService {

    private static final Logger log = LoggerFactory.getLogger(DiffScheduleService.class);

    private final DiffScheduleRepository scheduleRepository;
    private final DiffDefinitionRepository definitionRepository;
    private final CrmUserService crmUserService;
    private final CrmUserRepository userRepository;
    private final AuditLogService auditLog;

    public DiffScheduleService(DiffScheduleRepository scheduleRepository,
                                DiffDefinitionRepository definitionRepository,
                                CrmUserService crmUserService,
                                CrmUserRepository userRepository,
                                AuditLogService auditLog) {
        this.scheduleRepository = scheduleRepository;
        this.definitionRepository = definitionRepository;
        this.crmUserService = crmUserService;
        this.userRepository = userRepository;
        this.auditLog = auditLog;
    }

    /** Register a new pending schedule. Resolves + freezes the target user-ID list now. */
    @Transactional
    public DiffSchedule register(Long diffDefinitionId, String targetType, String targetRaw,
                                  String offsetMode, Integer offsetMinutes,
                                  Integer offsetDays, String offsetClockTime,
                                  String setByAdminName) {
        DiffDefinition def = definitionRepository.findById(diffDefinitionId)
                .orElseThrow(() -> new NotFoundException("diff definition not found: " + diffDefinitionId));

        List<Long> ids = resolveTargetIds(targetType, targetRaw);

        LocalDateTime setAt = LocalDateTime.now();
        LocalDateTime scheduledFor = computeScheduledFor(setAt, offsetMode, offsetMinutes, offsetDays, offsetClockTime);

        DiffSchedule s = new DiffSchedule();
        s.setDiffDefinitionId(def.getId());
        s.setDiffNameSnapshot(def.getName());
        s.setMemoSlotSnapshot(def.getMemoSlot());
        s.setTargetType(targetType);
        s.setTargetValue(targetRaw);
        s.setTargetUserIds(ids.stream().map(String::valueOf).collect(Collectors.joining(",")));
        s.setOffsetMode(offsetMode);
        s.setOffsetMinutes(offsetMinutes);
        s.setOffsetDays(offsetDays);
        s.setOffsetClockTime(offsetClockTime);
        s.setSetAt(setAt);
        s.setScheduledFor(scheduledFor);
        s.setStatus(DiffSchedule.STATUS_PENDING);
        s.setSetByAdminName(setByAdminName);
        DiffSchedule saved = scheduleRepository.save(s);
        auditLog.record(AuditLogService.ACTION_DIFF_SCHEDULE_CREATE, "DiffSchedule", saved.getId(),
                "diff=" + def.getName() + " target=" + targetType + ":" + targetRaw
                        + " targets=" + ids.size() + " scheduledFor=" + scheduledFor);
        return saved;
    }

    private List<Long> resolveTargetIds(String targetType, String targetRaw) {
        UserSearchForm form = new UserSearchForm();
        if (DiffSchedule.TARGET_PHONE.equals(targetType)) {
            form.setPhoneNumber(targetRaw);
        } else if (DiffSchedule.TARGET_EMAIL.equals(targetType)) {
            form.setEmail(targetRaw);
        } else if (DiffSchedule.TARGET_FOLDER.equals(targetType)) {
            form.setFolder(targetRaw);
        } else {
            throw new IllegalArgumentException("unknown target type: " + targetType);
        }
        return crmUserService.findIdsBySearch(form, null);
    }

    /**
     * 当日(分後): setAt + N minutes. 翌日以降(日数+時刻): setAt's calendar date + N days, at
     * the operator-specified clock time (HH:mm). Both are relative to SET_AT.
     */
    static LocalDateTime computeScheduledFor(LocalDateTime setAt, String mode,
                                              Integer minutes, Integer days, String clockTime) {
        if (DiffSchedule.OFFSET_MINUTES.equals(mode)) {
            if (minutes == null || minutes < 1) throw new IllegalArgumentException("分後の値は1以上で指定してください");
            return setAt.plusMinutes(minutes);
        }
        if (DiffSchedule.OFFSET_DAYS.equals(mode)) {
            if (days == null || days < 1) throw new IllegalArgumentException("日数は1以上で指定してください");
            if (clockTime == null || clockTime.trim().isEmpty()) throw new IllegalArgumentException("送信時刻を指定してください");
            LocalTime t = LocalTime.parse(clockTime);
            return setAt.toLocalDate().plusDays(days).atTime(t);
        }
        throw new IllegalArgumentException("unknown offset mode: " + mode);
    }

    public List<DiffSchedule> listPending() {
        return scheduleRepository.findByStatusOrderByScheduledForAsc(DiffSchedule.STATUS_PENDING);
    }

    public Page<DiffSchedule> searchHistory(String status, String targetType, Long definitionId, Pageable pageable) {
        return scheduleRepository.search(status, targetType, definitionId, pageable);
    }

    /** Cancel a single pending schedule. Re-fetches immediately before mutating so a schedule
     *  that fired between the pending-list render and this click is correctly left alone. */
    @Transactional
    public boolean cancel(Long scheduleId, String cancelledByAdminName) {
        DiffSchedule s = scheduleRepository.findById(scheduleId).orElse(null);
        if (s == null || !DiffSchedule.STATUS_PENDING.equals(s.getStatus())) return false;
        s.setStatus(DiffSchedule.STATUS_CANCELLED);
        s.setCancelledAt(LocalDateTime.now());
        s.setCancelledByAdminName(cancelledByAdminName);
        scheduleRepository.save(s);
        auditLog.record(AuditLogService.ACTION_DIFF_SCHEDULE_CANCEL, "DiffSchedule", s.getId(),
                "cancelled by " + cancelledByAdminName);
        return true;
    }

    /** Bulk-cancel every PENDING schedule matching one dimension (target type+value, or diff
     *  definition). Loops calling {@link #cancel} (re-checking each row) rather than a blind
     *  bulk UPDATE, so any schedule that fires mid-operation is correctly skipped. */
    @Transactional
    public int cancelByTarget(String targetType, String targetValue, Long diffDefinitionId, String cancelledByAdminName) {
        List<DiffSchedule> candidates = (diffDefinitionId != null)
                ? scheduleRepository.findByStatusAndDiffDefinitionId(DiffSchedule.STATUS_PENDING, diffDefinitionId)
                : scheduleRepository.findByStatusAndTargetTypeAndTargetValue(DiffSchedule.STATUS_PENDING, targetType, targetValue);
        int n = 0;
        for (DiffSchedule c : candidates) {
            if (cancel(c.getId(), cancelledByAdminName)) n++;
        }
        return n;
    }

    /** Execute one due schedule: activate MEMO_SLOT_SNAPSHOT for every target user, chunked
     *  500-at-a-time (mirrors ReplyHtmlSlotService.bulkApply's chunking). Only flips
     *  activeMemoSlot — never touches the slot's HTML content. */
    @Transactional
    public void execute(DiffSchedule s) {
        List<Long> ids = parseIds(s.getTargetUserIds());
        int applied = 0;
        final int chunkSize = 500;
        for (int i = 0; i < ids.size(); i += chunkSize) {
            List<Long> chunk = ids.subList(i, Math.min(i + chunkSize, ids.size()));
            List<CrmUser> users = userRepository.findAllById(chunk);
            for (CrmUser u : users) {
                u.setActiveMemoSlot(s.getMemoSlotSnapshot());
                userRepository.save(u);
                applied++;
            }
        }
        int missing = ids.size() - applied;
        s.setStatus(DiffSchedule.STATUS_EXECUTED);
        s.setExecutedAt(LocalDateTime.now());
        s.setResultDetail("applied=" + applied + " missing=" + missing + " total=" + ids.size());
        scheduleRepository.save(s);
        log.info("Diff-schedule executed: id={} {}", s.getId(), s.getResultDetail());
        auditLog.record(AuditLogService.ACTION_DIFF_SCHEDULE_EXECUTE, "DiffSchedule", s.getId(), s.getResultDetail());
    }

    private static List<Long> parseIds(String csv) {
        if (csv == null || csv.trim().isEmpty()) return Collections.emptyList();
        List<Long> out = new ArrayList<>();
        for (String p : csv.split(",")) {
            try { out.add(Long.parseLong(p.trim())); } catch (NumberFormatException ignored) { /* skip malformed */ }
        }
        return out;
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String msg) { super(msg); }
    }
}
