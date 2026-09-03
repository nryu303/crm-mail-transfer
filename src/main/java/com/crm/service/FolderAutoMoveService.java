package com.crm.service;

import com.crm.entity.FolderAutoMoveRule;
import com.crm.repository.FolderAutoMoveRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/** Manages daily folder-to-folder auto-move rules and runs the ones due each tick. */
@Service
public class FolderAutoMoveService {

    private static final Logger log = LoggerFactory.getLogger(FolderAutoMoveService.class);
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    private final FolderAutoMoveRuleRepository ruleRepository;
    private final CrmUserService crmUserService;
    private final AuditLogService auditLog;

    public FolderAutoMoveService(FolderAutoMoveRuleRepository ruleRepository,
                                  CrmUserService crmUserService,
                                  AuditLogService auditLog) {
        this.ruleRepository = ruleRepository;
        this.crmUserService = crmUserService;
        this.auditLog = auditLog;
    }

    public List<FolderAutoMoveRule> listAll() {
        return ruleRepository.findAllByOrderByMoveTimeAsc();
    }

    public Optional<FolderAutoMoveRule> findById(Long id) {
        return ruleRepository.findById(id);
    }

    @Transactional
    public FolderAutoMoveRule create(String sourceFolder, String destFolder, String moveTime, boolean enabled) {
        FolderAutoMoveRule r = new FolderAutoMoveRule();
        r.setSourceFolder(normalize(sourceFolder));
        r.setDestFolder(normalize(destFolder));
        r.setMoveTime(moveTime);
        r.setEnabled(enabled);
        return ruleRepository.save(r);
    }

    @Transactional
    public Optional<FolderAutoMoveRule> update(Long id, String sourceFolder, String destFolder,
                                                String moveTime, boolean enabled) {
        Optional<FolderAutoMoveRule> opt = ruleRepository.findById(id);
        if (!opt.isPresent()) return Optional.empty();
        FolderAutoMoveRule r = opt.get();
        r.setSourceFolder(normalize(sourceFolder));
        r.setDestFolder(normalize(destFolder));
        r.setMoveTime(moveTime);
        r.setEnabled(enabled);
        return Optional.of(ruleRepository.save(r));
    }

    @Transactional
    public boolean toggle(Long id, boolean enabled) {
        Optional<FolderAutoMoveRule> opt = ruleRepository.findById(id);
        if (!opt.isPresent()) return false;
        FolderAutoMoveRule r = opt.get();
        r.setEnabled(enabled);
        ruleRepository.save(r);
        return true;
    }

    @Transactional
    public void delete(Long id) {
        ruleRepository.deleteById(id);
    }

    /** Called once per scheduler tick. Runs every ENABLED rule whose MOVE_TIME matches the
     *  current HH:mm and hasn't already run today. A per-rule failure does not stop the rest. */
    @Transactional
    public void runDueRules(LocalDateTime now) {
        String nowHm = now.format(HHMM);
        for (FolderAutoMoveRule initial : ruleRepository.findByEnabledTrue()) {
            if (!nowHm.equals(initial.getMoveTime())) continue;
            try {
                runOneIfDue(initial.getId(), now);
            } catch (Exception e) {
                log.warn("Folder auto-move failed for rule {}: {}", initial.getId(), e.toString());
            }
        }
    }

    private void runOneIfDue(Long ruleId, LocalDateTime now) {
        // Re-fetch immediately before mutating — the rule may have been disabled or deleted
        // between the findByEnabledTrue() snapshot above and this point.
        FolderAutoMoveRule fresh = ruleRepository.findById(ruleId).orElse(null);
        if (fresh == null || !Boolean.TRUE.equals(fresh.getEnabled())) return;
        if (fresh.getLastRunAt() != null && fresh.getLastRunAt().toLocalDate().equals(now.toLocalDate())) {
            return; // already ran today
        }
        int moved = crmUserService.renameFolderValue(fresh.getSourceFolder(), fresh.getDestFolder());
        fresh.setLastRunAt(now);
        ruleRepository.save(fresh);
        String detail = "moved " + moved + " users from "
                + label(fresh.getSourceFolder()) + " to " + label(fresh.getDestFolder());
        log.info("Folder auto-move: {}", detail);
        auditLog.record(AuditLogService.ACTION_FOLDER_AUTO_MOVE, "FolderAutoMoveRule", fresh.getId(), detail);
    }

    private static String normalize(String folder) {
        return (folder == null || folder.trim().isEmpty()) ? null : folder.trim();
    }

    private static String label(String folder) {
        return folder == null ? "（未設定）" : folder;
    }
}
