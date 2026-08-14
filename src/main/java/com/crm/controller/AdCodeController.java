package com.crm.controller;

import com.crm.entity.AdCode;
import com.crm.service.AdCodeService;
import com.crm.service.DomainSettingService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.YearMonth;
import java.util.Optional;

/**
 * Internal admin pages for advertising / agency codes.
 * - List with per-code aggregate stats (signups + paid totals)
 * - Create new code (auto-generates URL-safe code + access_token)
 * - Edit name / memo / active flag
 * - Rotate access_token (invalidates the previously-shared external URL)
 * - Drill-down view with monthly + daily breakdown (admin-side mirror of the external dashboard)
 *
 * The external partner-facing dashboard lives at {@link AdMediaController}.
 */
@Controller
@RequestMapping("/manager/ad-codes")
public class AdCodeController {

    private final AdCodeService service;
    private final DomainSettingService settings;
    private final com.crm.interceptor.MediaAuthInterceptor mediaAuthInterceptor;
    private final com.crm.service.AdGroupCredentialService groupCredentialService;
    private final com.crm.service.AdminAuthService adminAuthService;

    public AdCodeController(AdCodeService service,
                            DomainSettingService settings,
                            com.crm.interceptor.MediaAuthInterceptor mediaAuthInterceptor,
                            com.crm.service.AdGroupCredentialService groupCredentialService,
                            com.crm.service.AdminAuthService adminAuthService) {
        this.service = service;
        this.settings = settings;
        this.mediaAuthInterceptor = mediaAuthInterceptor;
        this.groupCredentialService = groupCredentialService;
        this.adminAuthService = adminAuthService;
    }

    /** Read the public-facing base URL fresh on every request so a settings change
     *  (admin updates ドメイン設定) takes effect without a restart. */
    private String externalBaseUrl() {
        String base = settings.getReplyBaseUrl();
        return base == null ? "" : base.replaceAll("/+$", "");
    }

    /**
     * Group-centric landing page. One row per group (= unique AD_CODE.name).
     * Shows aggregated stats + the per-group agency URL + a drill-in link to manage codes.
     * The {@code view=codes} query param flips to the legacy flat code list.
     */
    @GetMapping
    public String list(@RequestParam(name = "q", required = false) String q,
                       @RequestParam(name = "view", required = false) String view,
                       Model model) {
        boolean codesView = "codes".equals(view);
        if (codesView) {
            model.addAttribute("summaries", service.listWithSummaries(q, null));
        } else {
            model.addAttribute("groups", service.listGroupSummaries(q));
        }
        model.addAttribute("q", q == null ? "" : q);
        model.addAttribute("view", codesView ? "codes" : "groups");
        model.addAttribute("externalBaseUrl", externalBaseUrl());
        model.addAttribute("mediaAuthUser", settings.getMediaAuthUser());
        model.addAttribute("mediaAuthPassword", settings.getMediaAuthPassword());
        model.addAttribute("showLoginCount", settings.isAdCodeShowLoginCount());
        return "ad-code/list";
    }

    /** Show/hide the ログイン数（累計） column. Toggled from the checkbox on the list page. */
    @PostMapping("/show-login-count")
    public String toggleShowLoginCount(@RequestParam(name = "show", required = false) String show,
                                        RedirectAttributes ra) {
        boolean enabled = "true".equalsIgnoreCase(show) || "on".equalsIgnoreCase(show) || "1".equals(show);
        settings.setAdCodeShowLoginCount(enabled);
        ra.addFlashAttribute("flashSuccess",
                enabled ? "ログイン数（累計）列を表示しました" : "ログイン数（累計）列を非表示にしました");
        return "redirect:/manager/ad-codes";
    }

    /** Drill-down: every code that belongs to a single group name. */
    @GetMapping("/group")
    public String groupDetail(@RequestParam(name = "name") String name,
                              @RequestParam(name = "q", required = false) String q,
                              Model model, RedirectAttributes ra) {
        if (name == null || name.trim().isEmpty()) {
            ra.addFlashAttribute("flashError", "グループ名が指定されていません");
            return "redirect:/manager/ad-codes";
        }
        model.addAttribute("groupName", name);
        model.addAttribute("groupSlug", com.crm.service.AdCodeService.slugify(name));
        model.addAttribute("summaries", service.listWithSummaries(q, name));
        model.addAttribute("q", q == null ? "" : q);
        model.addAttribute("showLoginCount", settings.isAdCodeShowLoginCount());
        model.addAttribute("externalBaseUrl", externalBaseUrl());
        model.addAttribute("externalGroupUrl",
                externalBaseUrl() + "/media/index/?g=" + com.crm.service.AdCodeService.slugify(name));
        // Per-group Basic Auth credentials. Auto-generated on first AdCode create; admin can rotate.
        com.crm.entity.AdGroupCredential creds = groupCredentialService.ensureFor(name);
        model.addAttribute("groupAuthUser", creds.getAuthUser());
        model.addAttribute("groupAuthPassword", creds.getAuthPassword());
        return "ad-code/group-detail";
    }

    /**
     * Delete an entire group: every AD_CODE row whose name matches, plus the group's
     * Basic Auth row (cleaned up automatically by AdCodeService.delete() when the last
     * code is removed). Requires admin password re-confirmation since this is a multi-row
     * destructive action.
     */
    @PostMapping("/group/delete")
    public String deleteGroup(@RequestParam("name") String name,
                              @RequestParam(name = "confirmPassword", required = false) String confirmPassword,
                              javax.servlet.http.HttpSession session,
                              RedirectAttributes ra) {
        Long adminId = (Long) session.getAttribute(com.crm.interceptor.AuthInterceptor.SESSION_ADMIN_ID);
        if (!adminAuthService.verifyPassword(adminId, confirmPassword)) {
            ra.addFlashAttribute("flashError", "グループ削除には管理者パスワードの確認が必要です");
            return "redirect:/manager/ad-codes";
        }
        if (name == null || name.trim().isEmpty()) {
            ra.addFlashAttribute("flashError", "グループ名が指定されていません");
            return "redirect:/manager/ad-codes";
        }
        // Find every code in the group, delete one by one (each call clears CRM_USER.ad_code
        // and, when the last code is removed, also cleans up AD_GROUP_CREDENTIAL).
        java.util.List<com.crm.service.AdCodeService.CodeSummary> codes = service.listWithSummaries(null, name);
        int deleted = 0;
        java.util.List<String> failures = new java.util.ArrayList<>();
        for (com.crm.service.AdCodeService.CodeSummary s : codes) {
            try {
                if (service.delete(s.getAdCode().getId())) deleted++;
            } catch (Exception e) {
                failures.add(s.getAdCode().getCode() + ": " + e.getMessage());
            }
        }
        // Belt-and-braces: explicitly drop credentials in case the group had no codes left.
        groupCredentialService.deleteByGroupName(name);
        if (failures.isEmpty()) {
            ra.addFlashAttribute("flashSuccess",
                    "グループ「" + name + "」を削除しました (広告コード " + deleted + " 件 + 認証情報)");
        } else {
            ra.addFlashAttribute("flashError",
                    "グループ「" + name + "」削除中に " + failures.size() + " 件のコードでエラー: "
                  + String.join(" / ", failures));
        }
        return "redirect:/manager/ad-codes";
    }

    /** Rotate the per-group Basic Auth credentials. The agency's old URL/creds become invalid. */
    @PostMapping("/group/rotate-auth")
    public String rotateGroupAuth(@RequestParam("name") String name, RedirectAttributes ra) {
        groupCredentialService.rotate(name);
        ra.addFlashAttribute("flashSuccess",
                "グループ「" + name + "」のID/パスワードを再発行しました。旧クレデンシャルは無効になります。");
        try {
            return "redirect:/manager/ad-codes/group?name=" + java.net.URLEncoder.encode(
                    name == null ? "" : name, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return "redirect:/manager/ad-codes";
        }
    }

    /** Update the global Basic-Auth credentials used to gate /media/**. */
    @PostMapping("/media-auth")
    public String saveMediaAuth(@RequestParam("user") String user,
                                @RequestParam("password") String password,
                                RedirectAttributes ra) {
        if (user == null || user.trim().isEmpty() || password == null || password.isEmpty()) {
            ra.addFlashAttribute("flashError", "ID とパスワードの両方を入力してください");
            return "redirect:/manager/ad-codes";
        }
        settings.setMediaAuthCredentials(user.trim(), password);
        // Drop the cached copy so the next /media/** request picks up the new creds immediately
        // instead of waiting up to 30 sec for the cache TTL to expire.
        mediaAuthInterceptor.invalidateCache();
        ra.addFlashAttribute("flashSuccess", "代理店ダッシュボードのID/パスワードを更新しました");
        return "redirect:/manager/ad-codes";
    }

    @GetMapping("/new")
    public String createForm() {
        return "ad-code/form";
    }

    @PostMapping
    public String create(@RequestParam(name = "name", required = false) String name,
                         @RequestParam(name = "code", required = false) String manualCode,
                         @RequestParam(name = "memo", required = false) String memo,
                         RedirectAttributes ra) {
        if (name == null || name.trim().isEmpty()) {
            ra.addFlashAttribute("flashError", "代理店名 (名称) は必須です");
            return "redirect:/manager/ad-codes/new";
        }
        try {
            AdCode created = service.create(name, manualCode, memo);
            ra.addFlashAttribute("flashSuccess",
                    "広告コード「" + created.getName() + "」を発行しました (code=" + created.getCode() + ")");
            return "redirect:/manager/ad-codes/" + created.getId();
        } catch (AdCodeService.DuplicateCodeException
               | org.springframework.dao.DataIntegrityViolationException e) {
            // Either our pre-check caught it, or the DB UNIQUE constraint did (concurrent insert race).
            ra.addFlashAttribute("flashError",
                    "その広告コードは既に登録されています。別のコードを指定するか空欄にしてください");
            return "redirect:/manager/ad-codes/new";
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("flashError", e.getMessage());
            return "redirect:/manager/ad-codes/new";
        }
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id,
                         @RequestParam(name = "year", required = false) Integer year,
                         @RequestParam(name = "month", required = false) String monthStr,
                         Model model, RedirectAttributes ra) {
        Optional<AdCode> opt = service.findById(id);
        if (!opt.isPresent()) {
            ra.addFlashAttribute("flashError", "広告コードが見つかりません");
            return "redirect:/manager/ad-codes";
        }
        AdCode a = opt.get();
        YearMonth target = parseMonth(monthStr).orElse(YearMonth.now());
        int targetYear = year != null ? year : YearMonth.now().getYear();
        model.addAttribute("adCode", a);
        // Group URL: shared across every AD_CODE row that has the same name. The agency lands
        // on /media/index/?g={slug} and sees only the codes that share this group name.
        String groupSlug = com.crm.service.AdCodeService.slugify(a.getName());
        model.addAttribute("externalGroupUrl", externalBaseUrl() + "/media/index/?g=" + groupSlug);
        model.addAttribute("externalCodeUrl", externalBaseUrl() + "/media/index/" + a.getCode() + "?g=" + groupSlug);
        model.addAttribute("daily", service.dailyStats(a.getCode(), target));
        model.addAttribute("monthly", service.monthlyStats(a.getCode(), targetYear));
        model.addAttribute("targetMonth", target);
        model.addAttribute("targetYear", targetYear);
        return "ad-code/detail";
    }

    @PostMapping("/{id}/update")
    public String update(@PathVariable Long id,
                         @RequestParam(name = "name", required = false) String name,
                         @RequestParam(name = "memo", required = false) String memo,
                         @RequestParam(name = "isActive", required = false) String isActiveStr,
                         RedirectAttributes ra) {
        Boolean active = isActiveStr == null ? null : ("true".equalsIgnoreCase(isActiveStr) || "on".equalsIgnoreCase(isActiveStr));
        if (!service.update(id, name, memo, active).isPresent()) {
            ra.addFlashAttribute("flashError", "広告コードが見つかりません");
            return "redirect:/manager/ad-codes";
        }
        ra.addFlashAttribute("flashSuccess", "広告コードを更新しました");
        return "redirect:/manager/ad-codes/" + id;
    }

    @PostMapping("/{id}/rotate-token")
    public String rotate(@PathVariable Long id, RedirectAttributes ra) {
        Optional<AdCode> r = service.rotateToken(id);
        if (!r.isPresent()) {
            ra.addFlashAttribute("flashError", "広告コードが見つかりません");
        } else {
            ra.addFlashAttribute("flashSuccess",
                    "アクセストークンを再発行しました — 旧URLは無効になりました");
        }
        return "redirect:/manager/ad-codes/" + id;
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes ra) {
        if (service.delete(id)) {
            ra.addFlashAttribute("flashSuccess", "広告コードを削除しました");
        } else {
            ra.addFlashAttribute("flashError", "広告コードが見つかりません");
        }
        return "redirect:/manager/ad-codes";
    }

    /** Bulk-delete individual ad-codes by id (used by the flat-codes view and the group-detail page). */
    @PostMapping("/bulk-delete")
    public String bulkDelete(@RequestParam(name = "ids", required = false) java.util.List<Long> ids,
                             RedirectAttributes ra) {
        if (ids == null || ids.isEmpty()) {
            ra.addFlashAttribute("flashError", "削除対象が選択されていません");
            return "redirect:/manager/ad-codes";
        }
        int n = 0;
        for (Long id : ids) {
            if (id == null) continue;
            try { if (service.delete(id)) n++; } catch (Exception ignored) {}
        }
        ra.addFlashAttribute("flashSuccess", n + " 件の広告コードを削除しました");
        return "redirect:/manager/ad-codes";
    }

    /**
     * Bulk-delete groups by name. Each group expands into all its ad-codes + the group-level
     * credentials row. Same admin-password gate as the previous one-by-one /group/delete.
     */
    @PostMapping("/group/bulk-delete")
    public String bulkDeleteGroups(@RequestParam(name = "names", required = false) java.util.List<String> names,
                                    @RequestParam(name = "confirmPassword", required = false) String confirmPassword,
                                    javax.servlet.http.HttpSession session,
                                    RedirectAttributes ra) {
        Long adminId = (Long) session.getAttribute(com.crm.interceptor.AuthInterceptor.SESSION_ADMIN_ID);
        if (!adminAuthService.verifyPassword(adminId, confirmPassword)) {
            ra.addFlashAttribute("flashError", "グループ削除には管理者パスワードの確認が必要です");
            return "redirect:/manager/ad-codes";
        }
        if (names == null || names.isEmpty()) {
            ra.addFlashAttribute("flashError", "削除対象が選択されていません");
            return "redirect:/manager/ad-codes";
        }
        int groupsDone = 0, codesDeleted = 0;
        java.util.List<String> failures = new java.util.ArrayList<>();
        for (String name : names) {
            if (name == null || name.trim().isEmpty()) continue;
            String trimmed = name.trim();
            java.util.List<com.crm.service.AdCodeService.CodeSummary> codes = service.listWithSummaries(null, trimmed);
            int localDeleted = 0;
            for (com.crm.service.AdCodeService.CodeSummary s : codes) {
                try { if (service.delete(s.getAdCode().getId())) localDeleted++; }
                catch (Exception e) { failures.add(s.getAdCode().getCode() + ": " + e.getMessage()); }
            }
            codesDeleted += localDeleted;
            groupCredentialService.deleteByGroupName(trimmed);
            groupsDone++;
        }
        if (failures.isEmpty()) {
            ra.addFlashAttribute("flashSuccess",
                    groupsDone + " 件のグループを削除しました (広告コード " + codesDeleted + " 件 + 認証情報)");
        } else {
            ra.addFlashAttribute("flashError",
                    groupsDone + " 件のグループ削除中に " + failures.size() + " 件のコードでエラー: "
                  + String.join(" / ", failures));
        }
        return "redirect:/manager/ad-codes";
    }

    private static Optional<YearMonth> parseMonth(String raw) {
        if (raw == null || raw.trim().isEmpty()) return Optional.empty();
        try { return Optional.of(YearMonth.parse(raw.trim())); }
        catch (Exception e) { return Optional.empty(); }
    }
}
