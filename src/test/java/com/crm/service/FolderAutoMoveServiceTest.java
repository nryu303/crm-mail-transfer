package com.crm.service;

import com.crm.entity.FolderAutoMoveRule;
import com.crm.repository.FolderAutoMoveRuleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Daily folder-to-folder auto-move rule dispatch — mirrors ScheduledTaskService's
 *  re-fetch-before-mutate race-safety pattern and per-rule failure isolation. */
class FolderAutoMoveServiceTest {

    private FolderAutoMoveRuleRepository ruleRepo;
    private CrmUserService crmUserService;
    private AuditLogService auditLog;
    private FolderAutoMoveService svc;

    @BeforeEach
    void setUp() {
        ruleRepo = mock(FolderAutoMoveRuleRepository.class);
        crmUserService = mock(CrmUserService.class);
        auditLog = mock(AuditLogService.class);
        svc = new FolderAutoMoveService(ruleRepo, crmUserService, auditLog);
    }

    private static FolderAutoMoveRule rule(Long id, String src, String dest, String time,
                                            boolean enabled, LocalDateTime lastRunAt) {
        FolderAutoMoveRule r = new FolderAutoMoveRule();
        r.setId(id);
        r.setSourceFolder(src);
        r.setDestFolder(dest);
        r.setMoveTime(time);
        r.setEnabled(enabled);
        r.setLastRunAt(lastRunAt);
        return r;
    }

    @Test
    void runDueRules_firesRuleWhenClockMatchesMoveTime() {
        FolderAutoMoveRule r = rule(1L, "A", "B", "18:00", true, null);
        when(ruleRepo.findByEnabledTrue()).thenReturn(Collections.singletonList(r));
        when(ruleRepo.findById(1L)).thenReturn(Optional.of(r));
        when(crmUserService.renameFolderValue("A", "B")).thenReturn(5);

        svc.runDueRules(LocalDateTime.of(2026, 8, 30, 18, 0));

        verify(crmUserService).renameFolderValue("A", "B");
        verify(ruleRepo).save(r);
        verify(auditLog).record(eq(AuditLogService.ACTION_FOLDER_AUTO_MOVE), eq("FolderAutoMoveRule"), eq(1L), anyString());
    }

    @Test
    void runDueRules_skipsRuleAlreadyRunToday() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 30, 18, 0);
        FolderAutoMoveRule r = rule(1L, "A", "B", "18:00", true, now.minusHours(2));
        when(ruleRepo.findByEnabledTrue()).thenReturn(Collections.singletonList(r));
        when(ruleRepo.findById(1L)).thenReturn(Optional.of(r));

        svc.runDueRules(now);

        verify(crmUserService, never()).renameFolderValue(any(), any());
    }

    @Test
    void runDueRules_skipsWhenClockDoesNotMatch() {
        FolderAutoMoveRule r = rule(1L, "A", "B", "09:00", true, null);
        when(ruleRepo.findByEnabledTrue()).thenReturn(Collections.singletonList(r));

        svc.runDueRules(LocalDateTime.of(2026, 8, 30, 18, 0));

        verify(crmUserService, never()).renameFolderValue(any(), any());
        verify(ruleRepo, never()).findById(any());
    }

    @Test
    void runDueRules_skipsWhenFreshFetchShowsDisabled() {
        // Initial snapshot said enabled, but the row was disabled between the list-fetch
        // and the per-rule re-fetch — same race-safety pattern as ScheduledTaskService.dispatchOne().
        FolderAutoMoveRule stale = rule(1L, "A", "B", "18:00", true, null);
        FolderAutoMoveRule fresh = rule(1L, "A", "B", "18:00", false, null);
        when(ruleRepo.findByEnabledTrue()).thenReturn(Collections.singletonList(stale));
        when(ruleRepo.findById(1L)).thenReturn(Optional.of(fresh));

        svc.runDueRules(LocalDateTime.of(2026, 8, 30, 18, 0));

        verify(crmUserService, never()).renameFolderValue(any(), any());
    }

    @Test
    void runDueRules_continuesAfterOneRuleThrows() {
        FolderAutoMoveRule r1 = rule(1L, "A", "B", "18:00", true, null);
        FolderAutoMoveRule r2 = rule(2L, "C", "D", "18:00", true, null);
        when(ruleRepo.findByEnabledTrue()).thenReturn(Arrays.asList(r1, r2));
        when(ruleRepo.findById(1L)).thenReturn(Optional.of(r1));
        when(ruleRepo.findById(2L)).thenReturn(Optional.of(r2));
        when(crmUserService.renameFolderValue("A", "B")).thenThrow(new RuntimeException("boom"));
        when(crmUserService.renameFolderValue("C", "D")).thenReturn(3);

        svc.runDueRules(LocalDateTime.of(2026, 8, 30, 18, 0));

        verify(crmUserService).renameFolderValue("A", "B");
        verify(crmUserService).renameFolderValue("C", "D");
        verify(ruleRepo, times(1)).save(r2);
    }
}
