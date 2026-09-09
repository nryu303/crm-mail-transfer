package com.crm.controller;

import com.crm.entity.DiffDefinition;
import com.crm.entity.DiffSchedule;
import com.crm.entity.DiffScheduleStep;
import com.crm.entity.DiffStep;
import com.crm.interceptor.AuthInterceptor;
import com.crm.service.DiffDefinitionService;
import com.crm.service.DiffScheduleService;
import com.crm.service.FolderSettingService;
import com.crm.service.PlaceholderService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import javax.servlet.http.HttpSession;
import java.util.List;

/** 差分スケジュール設定・履歴・取消 — register/list/cancel diff schedules, and manage the
 *  reusable diff definitions (multi-step timelines) they apply. */
@Controller
@RequestMapping("/manager/settings/diff-schedule")
public class DiffScheduleController {

    private final DiffScheduleService scheduleService;
    private final DiffDefinitionService definitionService;
    private final FolderSettingService folderSettingService;

    public DiffScheduleController(DiffScheduleService scheduleService,
                                   DiffDefinitionService definitionService,
                                   FolderSettingService folderSettingService) {
        this.scheduleService = scheduleService;
        this.definitionService = definitionService;
        this.folderSettingService = folderSettingService;
    }

    private static Long adminId(HttpSession session) {
        Object id = session.getAttribute(AuthInterceptor.SESSION_ADMIN_ID);
        return id instanceof Long ? (Long) id : null;
    }

    private static String adminName(HttpSession session) {
        Object name = session.getAttribute(AuthInterceptor.SESSION_ADMIN_NAME);
        return name == null ? null : String.valueOf(name);
    }

    // ====== Main register/pending page ======
    @GetMapping({"", "/"})
    public String page(Model model) {
        model.addAttribute("definitions", definitionService.listAll());
        model.addAttribute("pending", scheduleService.listPendingSteps());
        model.addAttribute("folders", folderSettingService.listFolders());
        // Resolve parent schedule info for each pending step so the list can show diff name/target.
        java.util.Map<Long, DiffSchedule> schedulesById = new java.util.HashMap<>();
        for (DiffScheduleStep step : scheduleService.listPendingSteps()) {
            schedulesById.computeIfAbsent(step.getDiffScheduleId(),
                    id -> scheduleService.findScheduleById(id).orElse(null));
        }
        model.addAttribute("schedulesById", schedulesById);
        return "setting/diff-schedule";
    }

    @PostMapping("/register")
    public String register(@RequestParam Long diffDefinitionId,
                            @RequestParam String targetType,
                            @RequestParam String targetRaw,
                            HttpSession session, RedirectAttributes ra) {
        try {
            DiffSchedule s = scheduleService.register(diffDefinitionId, targetType, targetRaw,
                    adminId(session), adminName(session));
            ra.addFlashAttribute("flashSuccess",
                    "差分スケジュールをセットしました（対象: " + countTargets(s) + " 名、差分: " + s.getDiffNameSnapshot() + "）");
        } catch (IllegalArgumentException | DiffScheduleService.NotFoundException e) {
            ra.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/manager/settings/diff-schedule";
    }

    private static int countTargets(DiffSchedule s) {
        String csv = s.getTargetUserIds();
        if (csv == null || csv.trim().isEmpty()) return 0;
        return csv.split(",").length;
    }

    /** Cancel one still-pending step. */
    @PostMapping("/steps/{id}/cancel")
    public String cancelStep(@PathVariable Long id, HttpSession session, RedirectAttributes ra) {
        boolean ok = scheduleService.cancelStep(id, adminName(session));
        ra.addFlashAttribute(ok ? "flashSuccess" : "flashError",
                ok ? "ステップを取消しました" : "取消できませんでした（既に実行済みの可能性があります）");
        return "redirect:/manager/settings/diff-schedule";
    }

    /** Cancel every remaining pending step of one registered schedule ("campaign run"). */
    @PostMapping("/{id}/cancel")
    public String cancelSchedule(@PathVariable Long id, HttpSession session, RedirectAttributes ra) {
        int n = scheduleService.cancelSchedule(id, adminName(session));
        ra.addFlashAttribute("flashSuccess", n + " 件のステップを取消しました");
        return "redirect:/manager/settings/diff-schedule";
    }

    @PostMapping("/cancel-by-target")
    public String cancelByTarget(@RequestParam(required = false) String targetType,
                                  @RequestParam(required = false) String targetValue,
                                  @RequestParam(required = false) Long diffDefinitionId,
                                  HttpSession session, RedirectAttributes ra) {
        int n = scheduleService.cancelByTarget(targetType, targetValue, diffDefinitionId, adminName(session));
        ra.addFlashAttribute("flashSuccess", n + " 件のステップを取消しました");
        return "redirect:/manager/settings/diff-schedule";
    }

    /** Bulk-cancel by phone/email search (partial match against actual target users, not the
     *  raw registration string — see {@link DiffScheduleService#cancelByUserSearch}). */
    @PostMapping("/cancel-by-user-search")
    public String cancelByUserSearch(@RequestParam String targetType,
                                      @RequestParam String query,
                                      HttpSession session, RedirectAttributes ra) {
        int n = scheduleService.cancelByUserSearch(targetType, query, adminName(session));
        ra.addFlashAttribute("flashSuccess", n + " 件のステップを取消しました");
        return "redirect:/manager/settings/diff-schedule";
    }

    // ====== Diff definitions (names, max MAX_DEFINITIONS) ======
    @GetMapping("/definitions")
    public String definitions(Model model) {
        List<DiffDefinition> defs = definitionService.listAll();
        java.util.Map<Long, Long> stepCounts = new java.util.HashMap<>();
        for (DiffDefinition d : defs) stepCounts.put(d.getId(), definitionService.stepCount(d.getId()));
        model.addAttribute("definitions", defs);
        model.addAttribute("stepCounts", stepCounts);
        model.addAttribute("maxDefinitions", DiffDefinitionService.MAX_DEFINITIONS);
        model.addAttribute("maxSteps", DiffDefinitionService.MAX_STEPS_PER_DEFINITION);
        return "setting/diff-schedule-definitions";
    }

    @PostMapping("/definitions")
    public String createDefinition(@RequestParam String name, RedirectAttributes ra) {
        try {
            DiffDefinition d = definitionService.create(name);
            ra.addFlashAttribute("flashSuccess", "差分名を追加しました。続けてステップを登録してください。");
            return "redirect:/manager/settings/diff-schedule/definitions/" + d.getId() + "/steps";
        } catch (DiffDefinitionService.TooManyDefinitionsException e) {
            ra.addFlashAttribute("flashError", e.getMessage());
            return "redirect:/manager/settings/diff-schedule/definitions";
        }
    }

    @PostMapping("/definitions/{id}/rename")
    public String renameDefinition(@PathVariable Long id, @RequestParam String name, RedirectAttributes ra) {
        boolean ok = definitionService.rename(id, name).isPresent();
        ra.addFlashAttribute(ok ? "flashSuccess" : "flashError", ok ? "差分名を更新しました" : "差分定義が見つかりません");
        return "redirect:/manager/settings/diff-schedule/definitions";
    }

    @PostMapping("/definitions/{id}/delete")
    public String deleteDefinition(@PathVariable Long id, RedirectAttributes ra) {
        definitionService.delete(id);
        ra.addFlashAttribute("flashSuccess", "差分名を削除しました");
        return "redirect:/manager/settings/diff-schedule/definitions";
    }

    // ====== Steps within one diff definition (max MAX_STEPS_PER_DEFINITION) ======
    @GetMapping("/definitions/{id}/steps")
    public String steps(@PathVariable Long id, Model model, RedirectAttributes ra) {
        java.util.Optional<DiffDefinition> def = definitionService.findById(id);
        if (!def.isPresent()) {
            ra.addFlashAttribute("flashError", "差分定義が見つかりません");
            return "redirect:/manager/settings/diff-schedule/definitions";
        }
        model.addAttribute("definition", def.get());
        model.addAttribute("steps", definitionService.listSteps(id));
        model.addAttribute("maxSteps", DiffDefinitionService.MAX_STEPS_PER_DEFINITION);
        model.addAttribute("builtinTags", PlaceholderService.BUILTIN_TAGS);
        return "setting/diff-schedule-steps";
    }

    @PostMapping("/definitions/{id}/steps")
    public String addStep(@PathVariable Long id,
                           @RequestParam String offsetMode,
                           @RequestParam(required = false) Integer offsetMinutes,
                           @RequestParam(required = false) Integer offsetDays,
                           @RequestParam(required = false) String offsetClockTime,
                           @RequestParam String stepType,
                           @RequestParam(required = false) String channel,
                           @RequestParam(required = false) String subject,
                           @RequestParam(required = false) String body,
                           @RequestParam(required = false) Integer memoSlot,
                           RedirectAttributes ra) {
        try {
            definitionService.addStep(id, offsetMode, offsetMinutes, offsetDays, offsetClockTime,
                    stepType, channel, subject, body, memoSlot);
            ra.addFlashAttribute("flashSuccess", "ステップを追加しました");
        } catch (IllegalArgumentException | DiffDefinitionService.TooManyStepsException
                 | DiffDefinitionService.NotFoundException e) {
            ra.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/manager/settings/diff-schedule/definitions/" + id + "/steps";
    }

    @PostMapping("/steps/{stepId}")
    public String updateStep(@PathVariable Long stepId,
                              @RequestParam Long diffDefinitionId,
                              @RequestParam String offsetMode,
                              @RequestParam(required = false) Integer offsetMinutes,
                              @RequestParam(required = false) Integer offsetDays,
                              @RequestParam(required = false) String offsetClockTime,
                              @RequestParam String stepType,
                              @RequestParam(required = false) String channel,
                              @RequestParam(required = false) String subject,
                              @RequestParam(required = false) String body,
                              @RequestParam(required = false) Integer memoSlot,
                              RedirectAttributes ra) {
        try {
            boolean ok = definitionService.updateStep(stepId, offsetMode, offsetMinutes, offsetDays,
                    offsetClockTime, stepType, channel, subject, body, memoSlot).isPresent();
            ra.addFlashAttribute(ok ? "flashSuccess" : "flashError", ok ? "ステップを更新しました" : "ステップが見つかりません");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/manager/settings/diff-schedule/definitions/" + diffDefinitionId + "/steps";
    }

    @PostMapping("/steps/{stepId}/delete")
    public String deleteStep(@PathVariable Long stepId, @RequestParam Long diffDefinitionId, RedirectAttributes ra) {
        definitionService.deleteStep(stepId);
        ra.addFlashAttribute("flashSuccess", "ステップを削除しました");
        return "redirect:/manager/settings/diff-schedule/definitions/" + diffDefinitionId + "/steps";
    }

    // ====== History ======
    @GetMapping("/history")
    public String history(@RequestParam(required = false) String status,
                           @RequestParam(required = false) String targetType,
                           @RequestParam(required = false) Long diffDefinitionId,
                           @RequestParam(defaultValue = "0") int page,
                           Model model) {
        org.springframework.data.domain.Page<DiffScheduleStep> results = scheduleService.searchHistory(
                (status == null || status.isEmpty()) ? null : status,
                (targetType == null || targetType.isEmpty()) ? null : targetType,
                diffDefinitionId,
                PageRequest.of(page, 50, Sort.by(Sort.Direction.DESC, "id")));
        // Resolve parent schedule for each result row (target summary, set time).
        java.util.Map<Long, DiffSchedule> schedulesById = new java.util.HashMap<>();
        for (DiffScheduleStep step : results.getContent()) {
            schedulesById.computeIfAbsent(step.getDiffScheduleId(),
                    id -> scheduleService.findScheduleById(id).orElse(null));
        }
        model.addAttribute("results", results);
        model.addAttribute("schedulesById", schedulesById);
        model.addAttribute("status", status);
        model.addAttribute("targetType", targetType);
        model.addAttribute("diffDefinitionId", diffDefinitionId);
        model.addAttribute("definitions", definitionService.listAll());
        return "setting/diff-schedule-history";
    }

    /** 選択削除 on the history page — hard-deletes the selected step rows (and their parent
     *  schedule if it ends up empty). Note the same shared-target caveat as the per-user
     *  delete: a step's parent schedule may have targeted multiple users at once. */
    @PostMapping("/history/delete")
    public String historyDelete(@RequestParam(name = "ids", required = false) List<Long> ids,
                                 HttpSession session, RedirectAttributes ra) {
        int n = (ids == null || ids.isEmpty()) ? 0 : scheduleService.deleteSteps(ids, adminName(session));
        ra.addFlashAttribute("flashSuccess", n + " 件削除しました");
        return "redirect:/manager/settings/diff-schedule/history";
    }
}
