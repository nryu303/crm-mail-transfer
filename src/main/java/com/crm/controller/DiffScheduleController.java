package com.crm.controller;

import com.crm.entity.DiffDefinition;
import com.crm.entity.DiffSchedule;
import com.crm.interceptor.AuthInterceptor;
import com.crm.service.DiffDefinitionService;
import com.crm.service.DiffScheduleService;
import com.crm.service.FolderSettingService;
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
 *  reusable diff definitions (memo-slot switches) they apply. */
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

    private static String adminName(HttpSession session) {
        Object name = session.getAttribute(AuthInterceptor.SESSION_ADMIN_NAME);
        return name == null ? null : String.valueOf(name);
    }

    // ====== Main split-screen page ======
    @GetMapping({"", "/"})
    public String page(Model model) {
        model.addAttribute("definitions", definitionService.listAll());
        model.addAttribute("pending", scheduleService.listPending());
        model.addAttribute("folders", folderSettingService.listFolders());
        return "setting/diff-schedule";
    }

    @PostMapping("/register")
    public String register(@RequestParam Long diffDefinitionId,
                            @RequestParam String targetType,
                            @RequestParam String targetRaw,
                            @RequestParam String offsetMode,
                            @RequestParam(required = false) Integer offsetMinutes,
                            @RequestParam(required = false) Integer offsetDays,
                            @RequestParam(required = false) String offsetClockTime,
                            HttpSession session, RedirectAttributes ra) {
        try {
            DiffSchedule s = scheduleService.register(diffDefinitionId, targetType, targetRaw,
                    offsetMode, offsetMinutes, offsetDays, offsetClockTime, adminName(session));
            ra.addFlashAttribute("flashSuccess",
                    "差分スケジュールをセットしました（対象: " + countTargets(s) + " 名、実行予定: "
                            + s.getScheduledFor() + "）");
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

    @PostMapping("/{id}/cancel")
    public String cancelOne(@PathVariable Long id, HttpSession session, RedirectAttributes ra) {
        boolean ok = scheduleService.cancel(id, adminName(session));
        ra.addFlashAttribute(ok ? "flashSuccess" : "flashError",
                ok ? "差分スケジュールを取消しました" : "取消できませんでした（既に実行済みの可能性があります）");
        return "redirect:/manager/settings/diff-schedule";
    }

    @PostMapping("/cancel-by-target")
    public String cancelByTarget(@RequestParam(required = false) String targetType,
                                  @RequestParam(required = false) String targetValue,
                                  @RequestParam(required = false) Long diffDefinitionId,
                                  HttpSession session, RedirectAttributes ra) {
        int n = scheduleService.cancelByTarget(targetType, targetValue, diffDefinitionId, adminName(session));
        ra.addFlashAttribute("flashSuccess", n + " 件の差分スケジュールを取消しました");
        return "redirect:/manager/settings/diff-schedule";
    }

    // ====== Diff definitions (max 10) ======
    @GetMapping("/definitions")
    public String definitions(Model model) {
        model.addAttribute("definitions", definitionService.listAll());
        model.addAttribute("maxDefinitions", DiffDefinitionService.MAX_DEFINITIONS);
        return "setting/diff-schedule-definitions";
    }

    @PostMapping("/definitions")
    public String createDefinition(@RequestParam String name, @RequestParam int memoSlot, RedirectAttributes ra) {
        try {
            definitionService.create(name, memoSlot);
            ra.addFlashAttribute("flashSuccess", "差分定義を追加しました");
        } catch (DiffDefinitionService.TooManyDefinitionsException e) {
            ra.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/manager/settings/diff-schedule/definitions";
    }

    @PostMapping("/definitions/{id}")
    public String updateDefinition(@PathVariable Long id, @RequestParam String name,
                                    @RequestParam int memoSlot, RedirectAttributes ra) {
        boolean ok = definitionService.update(id, name, memoSlot).isPresent();
        ra.addFlashAttribute(ok ? "flashSuccess" : "flashError", ok ? "差分定義を更新しました" : "差分定義が見つかりません");
        return "redirect:/manager/settings/diff-schedule/definitions";
    }

    @PostMapping("/definitions/{id}/delete")
    public String deleteDefinition(@PathVariable Long id, RedirectAttributes ra) {
        definitionService.delete(id);
        ra.addFlashAttribute("flashSuccess", "差分定義を削除しました");
        return "redirect:/manager/settings/diff-schedule/definitions";
    }

    // ====== History ======
    @GetMapping("/history")
    public String history(@RequestParam(required = false) String status,
                           @RequestParam(required = false) String targetType,
                           @RequestParam(required = false) Long diffDefinitionId,
                           @RequestParam(defaultValue = "0") int page,
                           Model model) {
        org.springframework.data.domain.Page<DiffSchedule> results = scheduleService.searchHistory(
                (status == null || status.isEmpty()) ? null : status,
                (targetType == null || targetType.isEmpty()) ? null : targetType,
                diffDefinitionId,
                PageRequest.of(page, 50, Sort.by(Sort.Direction.DESC, "setAt")));
        model.addAttribute("results", results);
        model.addAttribute("status", status);
        model.addAttribute("targetType", targetType);
        model.addAttribute("diffDefinitionId", diffDefinitionId);
        List<DiffDefinition> defs = definitionService.listAll();
        model.addAttribute("definitions", defs);
        return "setting/diff-schedule-history";
    }
}
