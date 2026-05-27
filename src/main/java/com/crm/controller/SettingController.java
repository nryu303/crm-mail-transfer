package com.crm.controller;

import com.crm.dto.DomainSettingForm;
import com.crm.dto.MessageTemplateForm;
import com.crm.dto.RelayServerForm;
import com.crm.dto.ReplyPageSettingForm;
import com.crm.entity.MessageTemplate;
import com.crm.entity.RelayServer;
import com.crm.interceptor.AuthInterceptor;
import com.crm.service.AdminAuthService;
import com.crm.service.AuditLogService;
import com.crm.service.DomainSettingService;
import com.crm.service.MessageTemplateService;
import com.crm.service.RelayServerService;
import com.crm.service.ReplyPageSettingService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import javax.servlet.http.HttpSession;
import javax.validation.Valid;
import java.util.Optional;

@Controller
@RequestMapping("/manager/settings")
public class SettingController {

    private final RelayServerService relayServerService;
    private final MessageTemplateService templateService;
    private final ReplyPageSettingService replyPageSettingService;
    private final AdminAuthService adminAuthService;
    private final AuditLogService auditLog;
    private final DomainSettingService domainSettingService;
    private final com.crm.service.FolderSettingService folderSettingService;
    private final com.crm.service.HttpRelayOutboundMailService httpRelayOutboundMailService;
    private final com.crm.service.HomeHtmlService homeHtmlService;
    private final com.crm.service.CrmUserService crmUserService;
    private final com.crm.service.ReplyHtmlSlotService replyHtmlSlotService;
    private final com.crm.service.FolderRetentionService folderRetentionService;
    private final com.crm.service.ImapEnvSyncService imapEnvSyncService;

    public SettingController(RelayServerService relayServerService,
                             MessageTemplateService templateService,
                             ReplyPageSettingService replyPageSettingService,
                             AdminAuthService adminAuthService,
                             AuditLogService auditLog,
                             DomainSettingService domainSettingService,
                             com.crm.service.FolderSettingService folderSettingService,
                             org.springframework.beans.factory.ObjectProvider<com.crm.service.HttpRelayOutboundMailService> httpRelayProvider,
                             com.crm.service.HomeHtmlService homeHtmlService,
                             com.crm.service.CrmUserService crmUserService,
                             com.crm.service.ReplyHtmlSlotService replyHtmlSlotService,
                             com.crm.service.FolderRetentionService folderRetentionService,
                             com.crm.service.ImapEnvSyncService imapEnvSyncService) {
        this.relayServerService = relayServerService;
        this.templateService = templateService;
        this.replyPageSettingService = replyPageSettingService;
        this.adminAuthService = adminAuthService;
        this.auditLog = auditLog;
        this.domainSettingService = domainSettingService;
        this.folderSettingService = folderSettingService;
        this.httpRelayOutboundMailService = httpRelayProvider.getIfAvailable();
        this.homeHtmlService = homeHtmlService;
        this.crmUserService = crmUserService;
        this.replyHtmlSlotService = replyHtmlSlotService;
        this.folderRetentionService = folderRetentionService;
        this.imapEnvSyncService = imapEnvSyncService;
    }

    /** Page: current IMAP-monitor sync state + manual re-sync button. Auto-sync also fires
     *  whenever CarrierPoolService mutates the pool table; this page exists so the operator
     *  can verify counts and force a rebuild if anything looks off. */
    @GetMapping("/imap-env")
    public String imapEnvPage(Model model) {
        long poolSb = imapEnvSyncService.countActiveSoftbankPoolRows();
        long poolAu = imapEnvSyncService.countActiveAuPoolRows();
        com.crm.service.ImapEnvSyncService.EnvFileInfo sbInfo =
                imapEnvSyncService.readEnvFileInfoFor(
                        com.crm.service.ImapEnvSyncService.LIVE_PATH,
                        com.crm.service.ImapEnvSyncService.STAGING_PATH,
                        "SOFTBANK_IMAP_ACCOUNTS");
        com.crm.service.ImapEnvSyncService.EnvFileInfo auInfo =
                imapEnvSyncService.readEnvFileInfoFor(
                        com.crm.service.ImapEnvSyncService.AU_LIVE_PATH,
                        com.crm.service.ImapEnvSyncService.AU_STAGING_PATH,
                        "AU_IMAP_ACCOUNTS");
        model.addAttribute("poolSoftbankCount", poolSb);
        model.addAttribute("envMonitoredCount", sbInfo.accountCount);
        model.addAttribute("envMtime", sbInfo.mtimeDisplay);
        model.addAttribute("envLiveExists", sbInfo.liveExistsButUnreadable);
        model.addAttribute("poolAuCount", poolAu);
        model.addAttribute("auEnvMonitoredCount", auInfo.accountCount);
        model.addAttribute("auEnvMtime", auInfo.mtimeDisplay);
        model.addAttribute("auEnvLiveExists", auInfo.liveExistsButUnreadable);
        return "setting/imap-env";
    }

    /** Stage a fresh /etc/softbank-fetcher.env from the current pool table. Writes to a
     *  centos-owned path; a systemd path-watcher (softbank-env-install.path) copies the
     *  file into /etc with root privileges so the JVM doesn't need sudo. */
    @PostMapping("/imap-env/sync")
    @org.springframework.web.bind.annotation.ResponseBody
    public java.util.Map<String, Object> syncImapEnv() {
        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        try {
            com.crm.service.ImapEnvSyncService.Result sb = imapEnvSyncService.rebuildEnvFile();
            com.crm.service.ImapEnvSyncService.Result au = imapEnvSyncService.rebuildAuEnvFile();
            body.put("ok", true);
            body.put("softbank", toMap(sb));
            body.put("au", toMap(au));
            // Back-compat fields (older JS reads these flat keys — they show the softbank result).
            body.put("total", sb.total);
            body.put("included", sb.included);
            body.put("skipped", sb.skipped);
            body.put("skippedAddrs", sb.skippedAddrs);
            body.put("stagingPath", sb.stagingPath);
        } catch (Exception e) {
            body.clear();
            body.put("ok", false);
            body.put("error", e.toString());
        }
        return body;
    }

    private static java.util.Map<String, Object> toMap(com.crm.service.ImapEnvSyncService.Result r) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("total", r.total);
        m.put("included", r.included);
        m.put("skipped", r.skipped);
        m.put("skippedAddrs", r.skippedAddrs);
        m.put("stagingPath", r.stagingPath);
        return m;
    }

    // ====== Folder settings ======
    @GetMapping("/folders")
    public String foldersForm(Model model) {
        java.util.List<String> folders = folderSettingService.listFolders();
        java.util.Map<String, Long> counts = crmUserService.countByFolder();

        // Build the per-configured-folder count list (preserves operator's ordering).
        java.util.List<java.util.Map<String, Object>> configuredRows = new java.util.ArrayList<>();
        for (String name : folders) {
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("name", name);
            row.put("count", counts.getOrDefault(name, 0L));
            configuredRows.add(row);
        }

        // Stranded = folder values present in CRM_USER but NOT in the configured list.
        // This is where the 2026-05-19 "5,001 users stranded under 5000件切り分け / フォルダD"
        // incident showed up: renaming a folder name in the textarea above does NOT touch
        // CRM_USER.FOLDER strings, so the old name lingers on the user rows.
        java.util.Set<String> configuredSet = new java.util.HashSet<>(folders);
        java.util.List<java.util.Map<String, Object>> strandedRows = new java.util.ArrayList<>();
        long unsetCount = 0L;
        for (java.util.Map.Entry<String, Long> e : counts.entrySet()) {
            String name = e.getKey();
            if (name == null || name.isEmpty()) {
                unsetCount = e.getValue();
                continue;
            }
            if (!configuredSet.contains(name)) {
                java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("name", name);
                row.put("count", e.getValue());
                strandedRows.add(row);
            }
        }
        // Stable, count-descending order — biggest stranded groups first so the admin
        // sees the urgent ones at the top.
        strandedRows.sort((a, b) -> Long.compare((Long) b.get("count"), (Long) a.get("count")));

        model.addAttribute("folderCount", folders.size());
        model.addAttribute("configuredRows", configuredRows);
        model.addAttribute("strandedRows", strandedRows);
        model.addAttribute("unsetCount", unsetCount);
        // Per-folder retention day count for the new auto-purge UI (2026-05-26).
        model.addAttribute("retentionDaysByFolder",
                folderRetentionService.getRetentionDaysMap(folders));
        return "setting/folders";
    }

    /** Manual one-shot: delete every MESSAGE row for users in this folder. */
    @PostMapping("/folders/purge-messages")
    public String foldersPurgeMessages(@RequestParam("folderName") String folderName,
                                        @RequestParam(name = "confirmPassword", required = false) String confirmPassword,
                                        javax.servlet.http.HttpSession session,
                                        RedirectAttributes ra) {
        Long adminId = (Long) session.getAttribute(com.crm.interceptor.AuthInterceptor.SESSION_ADMIN_ID);
        if (!adminAuthService.verifyPassword(adminId, confirmPassword)) {
            ra.addFlashAttribute("flashError", "履歴一括削除には管理者パスワードの確認が必要です");
            return "redirect:/manager/settings/folders";
        }
        int n = folderRetentionService.purgeMessagesForFolder(folderName);
        auditLog.record(com.crm.service.AuditLogService.ACTION_USER_DELETE,
                "Message", null, "folder-purge folder=" + folderName + " count=" + n);
        ra.addFlashAttribute("flashSuccess",
                "フォルダ「" + (folderName == null || folderName.isEmpty() ? "（未設定）" : folderName)
                + "」内ユーザーの一斉送信返信履歴を " + n + " 件削除しました");
        return "redirect:/manager/settings/folders";
    }

    /** Manual one-shot: delete every CARRIER_USER_BINDING for users in this folder. */
    @PostMapping("/folders/purge-bindings")
    public String foldersPurgeBindings(@RequestParam("folderName") String folderName,
                                        @RequestParam(name = "confirmPassword", required = false) String confirmPassword,
                                        javax.servlet.http.HttpSession session,
                                        RedirectAttributes ra) {
        Long adminId = (Long) session.getAttribute(com.crm.interceptor.AuthInterceptor.SESSION_ADMIN_ID);
        if (!adminAuthService.verifyPassword(adminId, confirmPassword)) {
            ra.addFlashAttribute("flashError", "キャリア解除には管理者パスワードの確認が必要です");
            return "redirect:/manager/settings/folders";
        }
        int n = folderRetentionService.purgeBindingsForFolder(folderName);
        ra.addFlashAttribute("flashSuccess",
                "フォルダ「" + (folderName == null || folderName.isEmpty() ? "（未設定）" : folderName)
                + "」内ユーザーのキャリア登録割り当てを " + n + " 件削除しました");
        return "redirect:/manager/settings/folders";
    }

    /** Save the per-folder retention days (operator slider). 0 = disable. */
    @PostMapping("/folders/retention")
    public String foldersSaveRetention(@RequestParam("folderName") String folderName,
                                        @RequestParam("days") Integer days,
                                        RedirectAttributes ra) {
        folderRetentionService.setRetentionDays(folderName, days == null ? 0 : days);
        ra.addFlashAttribute("flashSuccess",
                "フォルダ「" + folderName + "」の自動削除を "
                + ((days == null || days == 0) ? "無効" : days + " 日") + " に設定しました");
        return "redirect:/manager/settings/folders";
    }

    @PostMapping("/folders")
    public String foldersSave(@RequestParam(name = "folderName", required = false) java.util.List<String> folderNames,
                              RedirectAttributes ra) {
        java.util.List<String> parsed = new java.util.ArrayList<>();
        if (folderNames != null) {
            for (String name : folderNames) {
                String t = name == null ? "" : name.trim();
                if (!t.isEmpty()) parsed.add(t);
            }
        }
        folderSettingService.save(parsed);
        ra.addFlashAttribute("flashSuccess", parsed.size() + " 個のフォルダを保存しました");
        return "redirect:/manager/settings/folders";
    }

    /**
     * Rescue route for users stranded on an old folder name. Migrates every CRM_USER row
     * whose FOLDER equals {@code from} to {@code to}, in a single bulk UPDATE inside one
     * transaction. {@code to} may equal an existing configured folder (merge) or a brand
     * new name (which the admin should also add to the configured list separately).
     */
    @PostMapping("/folders/rebind")
    public String foldersRebind(@RequestParam("fromFolder") String fromFolder,
                                 @RequestParam("toFolder") String toFolder,
                                 RedirectAttributes ra) {
        String from = fromFolder == null ? "" : fromFolder.trim();
        String to   = toFolder   == null ? "" : toFolder.trim();
        if (from.isEmpty()) {
            ra.addFlashAttribute("flashError", "移行元のフォルダ名が指定されていません");
            return "redirect:/manager/settings/folders";
        }
        int moved = crmUserService.renameFolderValue(from, to);
        String toLabel = to.isEmpty() ? "（未設定）" : to;
        ra.addFlashAttribute("flashSuccess",
                "フォルダ「" + from + "」の " + moved + " 名を「" + toLabel + "」に移行しました");
        return "redirect:/manager/settings/folders";
    }

    // ====== Audit log viewer ======
    @GetMapping("/audit-log")
    public String auditLog(@RequestParam(name = "page", defaultValue = "0") int page, Model model) {
        model.addAttribute("logs", auditLog.recent(page, 50));
        return "setting/audit-log";
    }

    // ====== Sender-name settings ======
    @GetMapping("/sender-names")
    public String senderNamesForm(Model model) {
        model.addAttribute("mode", domainSettingService.getSenderNameMode());
        model.addAttribute("fixed", domainSettingService.getSenderNameFixed());
        model.addAttribute("listText", String.join("\n", domainSettingService.getSenderNameList()));
        model.addAttribute("randomLength", domainSettingService.getSenderNameRandomLength());
        model.addAttribute("randomCase", domainSettingService.getSenderNameRandomCase());
        return "setting/sender-names";
    }

    @PostMapping("/sender-names")
    public String senderNamesSave(@RequestParam(name = "mode", required = false) String mode,
                                   @RequestParam(name = "fixed", required = false) String fixed,
                                   @RequestParam(name = "listText", required = false) String listText,
                                   @RequestParam(name = "randomLength", required = false, defaultValue = "8") int randomLength,
                                   @RequestParam(name = "randomCase", required = false, defaultValue = "mixed") String randomCase,
                                   RedirectAttributes ra) {
        domainSettingService.setSenderNamePolicy(mode, fixed, listText, randomLength, randomCase);
        ra.addFlashAttribute("flashSuccess", "送信者名設定を保存しました");
        return "redirect:/manager/settings/sender-names";
    }

    // --- Settings index ---
    @GetMapping({"", "/"})
    public String index(Model model) {
        model.addAttribute("relayCount", relayServerService.listAll().size());
        model.addAttribute("templateCount", templateService.count());
        model.addAttribute("maxTemplates", MessageTemplateService.MAX_TEMPLATES);
        return "setting/index";
    }

    // ====== Relay servers ======
    @GetMapping("/relay-servers")
    public String relayList(Model model) {
        model.addAttribute("relays", relayServerService.listAll());
        // Active relay host = the one HttpRelayOutboundMailService is configured to talk to.
        // Used by the template to badge the "in-use" row. May be empty if the relay adapter
        // isn't active (e.g. stub mode in dev).
        model.addAttribute("activeRelayHost",
                httpRelayOutboundMailService == null ? "" : httpRelayOutboundMailService.getActiveRelayHost());
        model.addAttribute("useRelay", domainSettingService.isOutboundUseRelay());
        model.addAttribute("broadcastRatePerMinute", domainSettingService.getBroadcastRatePerMinute());
        return "setting/relay-list";
    }

    /** Save the global broadcast send-rate (used as default by new broadcast forms). */
    @PostMapping("/relay-servers/rate")
    public String saveBroadcastRate(@RequestParam("ratePerMinute") Integer rate,
                                     RedirectAttributes ra) {
        if (rate == null) rate = 60;
        domainSettingService.setBroadcastRatePerMinute(rate);
        ra.addFlashAttribute("flashSuccess", "送信インターバル (rate/min) を " + rate + " 通/分に更新しました");
        return "redirect:/manager/settings/relay-servers";
    }

    @PostMapping("/relay-servers/toggle")
    public String relayToggle(@RequestParam(name = "useRelay", required = false) String useRelay,
                              RedirectAttributes ra) {
        boolean enabled = "true".equalsIgnoreCase(useRelay) || "on".equalsIgnoreCase(useRelay) || "1".equals(useRelay);
        domainSettingService.setOutboundUseRelay(enabled);
        ra.addFlashAttribute("flashSuccess", enabled
                ? "リレー (転送機) を使用する設定に切り替えました"
                : "リレーを使用しない設定に切り替えました (登録済みカウントの SMTP で直接送信されます)");
        return "redirect:/manager/settings/relay-servers";
    }

    @GetMapping("/relay-servers/new")
    public String relayCreateForm(Model model) {
        model.addAttribute("form", new RelayServerForm());
        model.addAttribute("editing", false);
        return "setting/relay-form";
    }

    @PostMapping("/relay-servers")
    public String relayCreate(@Valid @ModelAttribute("form") RelayServerForm form,
                              BindingResult br, RedirectAttributes ra, Model model) {
        if (br.hasErrors()) {
            model.addAttribute("editing", false);
            return "setting/relay-form";
        }
        try {
            relayServerService.create(form);
            ra.addFlashAttribute("flashSuccess", "リレーサーバーを追加しました");
            return "redirect:/manager/settings/relay-servers";
        } catch (RelayServerService.DuplicateNameException e) {
            br.rejectValue("name", "duplicate", "この名前は既に登録されています");
            model.addAttribute("editing", false);
            return "setting/relay-form";
        }
    }

    @GetMapping("/relay-servers/{id}/edit")
    public String relayEditForm(@PathVariable Long id, Model model, RedirectAttributes ra) {
        Optional<RelayServer> r = relayServerService.findById(id);
        if (!r.isPresent()) {
            ra.addFlashAttribute("flashError", "リレーサーバーが見つかりません");
            return "redirect:/manager/settings/relay-servers";
        }
        model.addAttribute("form", RelayServerForm.from(r.get()));
        model.addAttribute("relayId", id);
        model.addAttribute("editing", true);
        return "setting/relay-form";
    }

    @PostMapping("/relay-servers/{id}")
    public String relayUpdate(@PathVariable Long id,
                              @Valid @ModelAttribute("form") RelayServerForm form,
                              BindingResult br, RedirectAttributes ra, Model model) {
        if (br.hasErrors()) {
            model.addAttribute("relayId", id);
            model.addAttribute("editing", true);
            return "setting/relay-form";
        }
        try {
            relayServerService.update(id, form);
            ra.addFlashAttribute("flashSuccess", "リレーサーバーを更新しました");
            return "redirect:/manager/settings/relay-servers";
        } catch (RelayServerService.DuplicateNameException e) {
            br.rejectValue("name", "duplicate", "この名前は既に登録されています");
            model.addAttribute("relayId", id);
            model.addAttribute("editing", true);
            return "setting/relay-form";
        } catch (RelayServerService.NotFoundException e) {
            ra.addFlashAttribute("flashError", "リレーサーバーが見つかりません");
            return "redirect:/manager/settings/relay-servers";
        }
    }

    @PostMapping("/relay-servers/{id}/delete")
    public String relayDelete(@PathVariable Long id, RedirectAttributes ra) {
        relayServerService.delete(id);
        ra.addFlashAttribute("flashSuccess", "リレーサーバーを削除しました");
        return "redirect:/manager/settings/relay-servers";
    }

    /** Bulk-delete from the relay-servers list page (checkbox + 選択削除 pattern). */
    @PostMapping("/relay-servers/bulk-delete")
    public String relayBulkDelete(@RequestParam(name = "ids", required = false) java.util.List<Long> ids,
                                  RedirectAttributes ra) {
        if (ids == null || ids.isEmpty()) {
            ra.addFlashAttribute("flashError", "削除対象が選択されていません");
            return "redirect:/manager/settings/relay-servers";
        }
        int n = 0;
        for (Long id : ids) {
            if (id == null) continue;
            try { relayServerService.delete(id); n++; } catch (Exception ignored) {}
        }
        ra.addFlashAttribute("flashSuccess", n + " 件のリレーサーバーを削除しました");
        return "redirect:/manager/settings/relay-servers";
    }

    // ====== Message templates ======
    @GetMapping("/message-templates")
    public String templateList(@RequestParam(name = "q", required = false) String q,
                               @RequestParam(name = "page", required = false, defaultValue = "1") Integer pageNo,
                               Model model) {
        if (pageNo == null || pageNo < 1 || pageNo > MessageTemplateService.MAX_PAGES) pageNo = 1;
        java.util.List<com.crm.entity.MessageTemplate> all = templateService.listByPage(pageNo);
        java.util.List<com.crm.entity.MessageTemplate> filtered;
        if (q == null || q.trim().isEmpty()) {
            filtered = all;
        } else {
            String needle = q.trim().toLowerCase();
            filtered = new java.util.ArrayList<>();
            for (com.crm.entity.MessageTemplate t : all) {
                if ((t.getName() != null && t.getName().toLowerCase().contains(needle))
                    || (t.getSubject() != null && t.getSubject().toLowerCase().contains(needle))
                    || (t.getBody() != null && t.getBody().toLowerCase().contains(needle))) {
                    filtered.add(t);
                }
            }
        }
        model.addAttribute("templates", filtered);
        model.addAttribute("q", q == null ? "" : q);
        model.addAttribute("pageNo", pageNo);
        model.addAttribute("maxPages", MessageTemplateService.MAX_PAGES);
        model.addAttribute("pageTitles", templateService.listPageTitles());
        // Per-page counts so the tab strip can display "X/50"
        int[] perPage = new int[MessageTemplateService.MAX_PAGES + 1];
        for (int i = 1; i <= MessageTemplateService.MAX_PAGES; i++) perPage[i] = (int) templateService.countByPage(i);
        model.addAttribute("perPageCounts", perPage);
        model.addAttribute("maxTemplates", MessageTemplateService.MAX_TEMPLATES);
        model.addAttribute("canCreate", templateService.countByPage(pageNo) < MessageTemplateService.MAX_TEMPLATES);
        return "setting/template-list";
    }

    /** Save the operator-supplied titles for pages 1..MAX_PAGES. */
    @PostMapping("/message-templates/page-titles")
    public String templatePageTitles(@RequestParam java.util.Map<String, String> params,
                                      RedirectAttributes ra) {
        for (int i = 1; i <= MessageTemplateService.MAX_PAGES; i++) {
            String v = params.get("title" + i);
            if (v != null) templateService.setPageTitle(i, v);
        }
        ra.addFlashAttribute("flashSuccess", "ページタイトルを保存しました");
        return "redirect:/manager/settings/message-templates";
    }

    @GetMapping("/message-templates/new")
    public String templateCreateForm(@RequestParam(name = "page", required = false, defaultValue = "1") Integer pageNo,
                                     Model model, RedirectAttributes ra) {
        if (pageNo == null || pageNo < 1 || pageNo > MessageTemplateService.MAX_PAGES) pageNo = 1;
        if (templateService.countByPage(pageNo) >= MessageTemplateService.MAX_TEMPLATES) {
            ra.addFlashAttribute("flashError",
                    "ページ " + pageNo + " は既に最大" + MessageTemplateService.MAX_TEMPLATES + "件登録済みです");
            return "redirect:/manager/settings/message-templates?page=" + pageNo;
        }
        MessageTemplateForm f = new MessageTemplateForm();
        f.setPageNo(pageNo);
        model.addAttribute("form", f);
        model.addAttribute("editing", false);
        model.addAttribute("maxPages", MessageTemplateService.MAX_PAGES);
        model.addAttribute("pageTitles", templateService.listPageTitles());
        model.addAttribute("builtinTags", com.crm.service.PlaceholderService.BUILTIN_TAGS);
        return "setting/template-form";
    }

    @PostMapping("/message-templates")
    public String templateCreate(@Valid @ModelAttribute("form") MessageTemplateForm form,
                                 BindingResult br, RedirectAttributes ra, Model model) {
        if (br.hasErrors()) {
            model.addAttribute("editing", false);
            model.addAttribute("maxPages", MessageTemplateService.MAX_PAGES);
            model.addAttribute("pageTitles", templateService.listPageTitles());
            return "setting/template-form";
        }
        try {
            templateService.create(form);
            ra.addFlashAttribute("flashSuccess", "定型文を追加しました");
            return "redirect:/manager/settings/message-templates?page=" + form.getPageNo();
        } catch (MessageTemplateService.TooManyTemplatesException e) {
            ra.addFlashAttribute("flashError", e.getMessage());
            return "redirect:/manager/settings/message-templates?page=" + form.getPageNo();
        }
    }

    @GetMapping("/message-templates/{id}/edit")
    public String templateEditForm(@PathVariable Long id, Model model, RedirectAttributes ra) {
        Optional<MessageTemplate> t = templateService.findById(id);
        if (!t.isPresent()) {
            ra.addFlashAttribute("flashError", "定型文が見つかりません");
            return "redirect:/manager/settings/message-templates";
        }
        model.addAttribute("form", MessageTemplateForm.from(t.get()));
        model.addAttribute("templateId", id);
        model.addAttribute("editing", true);
        model.addAttribute("maxPages", MessageTemplateService.MAX_PAGES);
        model.addAttribute("pageTitles", templateService.listPageTitles());
        model.addAttribute("builtinTags", com.crm.service.PlaceholderService.BUILTIN_TAGS);
        return "setting/template-form";
    }

    @PostMapping("/message-templates/{id}")
    public String templateUpdate(@PathVariable Long id,
                                 @Valid @ModelAttribute("form") MessageTemplateForm form,
                                 BindingResult br, RedirectAttributes ra, Model model) {
        if (br.hasErrors()) {
            model.addAttribute("templateId", id);
            model.addAttribute("editing", true);
            model.addAttribute("maxPages", MessageTemplateService.MAX_PAGES);
            model.addAttribute("pageTitles", templateService.listPageTitles());
            return "setting/template-form";
        }
        try {
            templateService.update(id, form);
            ra.addFlashAttribute("flashSuccess", "定型文を更新しました");
            return "redirect:/manager/settings/message-templates?page=" + form.getPageNo();
        } catch (MessageTemplateService.NotFoundException e) {
            ra.addFlashAttribute("flashError", "定型文が見つかりません");
            return "redirect:/manager/settings/message-templates";
        }
    }

    @PostMapping("/message-templates/{id}/delete")
    public String templateDelete(@PathVariable Long id, RedirectAttributes ra) {
        templateService.delete(id);
        ra.addFlashAttribute("flashSuccess", "定型文を削除しました");
        return "redirect:/manager/settings/message-templates";
    }

    @PostMapping("/message-templates/bulk-delete")
    public String templateBulkDelete(@RequestParam(name = "ids", required = false) java.util.List<Long> ids,
                                      RedirectAttributes ra) {
        if (ids == null || ids.isEmpty()) {
            ra.addFlashAttribute("flashError", "削除対象が選択されていません");
            return "redirect:/manager/settings/message-templates";
        }
        int n = 0;
        for (Long id : ids) {
            if (id == null) continue;
            try { templateService.delete(id); n++; } catch (Exception ignored) {}
        }
        ra.addFlashAttribute("flashSuccess", n + " 件の定型文を削除しました");
        return "redirect:/manager/settings/message-templates";
    }

    /** Receive a new top-to-bottom ID list from the drag-and-drop UI on the templates list page.
     *  Returns 204 on success so the in-page JS can keep the DOM as-is (no reload flash). */
    @PostMapping("/message-templates/reorder")
    @org.springframework.web.bind.annotation.ResponseBody
    public org.springframework.http.ResponseEntity<Void> templateReorder(
            @RequestParam(name = "ids", required = false) java.util.List<Long> ids) {
        templateService.reorder(ids);
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    // ====== Domain settings (reply URL + FROM address generation) ======
    @GetMapping("/domain")
    public String domainForm(Model model) {
        DomainSettingForm form = new DomainSettingForm();
        form.setReplyBaseUrl(domainSettingService.getReplyBaseUrl());
        form.setReplyRandomSubdomainEnabled(domainSettingService.isReplyRandomSubdomainEnabled());
        form.setReplyRandomSubdomainLength(domainSettingService.getReplyRandomLength());
        form.setReplyFixedSubdomain(domainSettingService.getReplyFixedSubdomain());
        form.setFromBaseDomain(domainSettingService.getFromBaseDomain());
        form.setFromRandomLocalEnabled(domainSettingService.isFromRandomEnabled());
        form.setFromRandomLocalLength(domainSettingService.getFromRandomLength());
        form.setFromFixedLocal(domainSettingService.getFromFixedLocal());
        form.setBindingExpireEnabled(domainSettingService.isBindingExpireEnabled());
        form.setBindingExpireDays(domainSettingService.getBindingExpireDays());
        model.addAttribute("form", form);
        return "setting/domain";
    }

    @PostMapping("/domain")
    public String domainSave(@ModelAttribute("form") DomainSettingForm form, RedirectAttributes ra) {
        domainSettingService.save(DomainSettingService.KEY_REPLY_BASE_URL, s(form.getReplyBaseUrl()));
        domainSettingService.save(DomainSettingService.KEY_REPLY_RANDOM_SUBDOMAIN,
                String.valueOf(Boolean.TRUE.equals(form.getReplyRandomSubdomainEnabled())));
        domainSettingService.save(DomainSettingService.KEY_REPLY_RANDOM_LENGTH,
                String.valueOf(form.getReplyRandomSubdomainLength() == null ? 16 : form.getReplyRandomSubdomainLength()));
        domainSettingService.save(DomainSettingService.KEY_REPLY_FIXED_SUBDOMAIN, s(form.getReplyFixedSubdomain()));
        domainSettingService.save(DomainSettingService.KEY_FROM_BASE_DOMAIN, s(form.getFromBaseDomain()));
        domainSettingService.save(DomainSettingService.KEY_FROM_RANDOM_LOCAL,
                String.valueOf(Boolean.TRUE.equals(form.getFromRandomLocalEnabled())));
        domainSettingService.save(DomainSettingService.KEY_FROM_RANDOM_LENGTH,
                String.valueOf(form.getFromRandomLocalLength() == null ? 16 : form.getFromRandomLocalLength()));
        domainSettingService.save(DomainSettingService.KEY_FROM_FIXED_LOCAL, s(form.getFromFixedLocal()));
        domainSettingService.save(DomainSettingService.KEY_BINDING_EXPIRE_ENABLED,
                String.valueOf(Boolean.TRUE.equals(form.getBindingExpireEnabled())));
        domainSettingService.save(DomainSettingService.KEY_BINDING_EXPIRE_DAYS,
                String.valueOf(form.getBindingExpireDays() == null ? 60 : form.getBindingExpireDays()));
        ra.addFlashAttribute("flashSuccess", "ドメイン設定を保存しました");
        return "redirect:/manager/settings/domain";
    }

    private static String s(String v) { return v == null ? "" : v.trim(); }

    // ====== Reply page settings (singleton) ======
    @GetMapping("/reply-page")
    public String replyPageForm(Model model) {
        model.addAttribute("form", ReplyPageSettingForm.from(replyPageSettingService.getOrCreate()));
        return "setting/reply-page";
    }

    @PostMapping("/reply-page")
    public String replyPageSave(@ModelAttribute("form") ReplyPageSettingForm form,
                                 RedirectAttributes ra) {
        replyPageSettingService.save(form);
        ra.addFlashAttribute("flashSuccess", "返信画面設定を保存しました");
        return "redirect:/manager/settings/reply-page";
    }

    // ====== Reply-HTML bulk edit (6 slots × N users in a folder) ======
    @GetMapping("/memo-html-bulk")
    public String memoHtmlBulkForm(@RequestParam(name = "folder", required = false) String folder,
                                    @RequestParam(name = "loadFromUserId", required = false) Long loadFromUserId,
                                    Model model) {
        model.addAttribute("folders", folderSettingService.listFolders());
        model.addAttribute("slotCount", com.crm.service.ReplyHtmlSlotService.SLOT_COUNT);
        model.addAttribute("slotTitles", replyHtmlSlotService.listSlotTitles());
        model.addAttribute("selectedFolder", folder == null ? "" : folder);
        // Optional bootstrap: copy the 6 HTMLs from an existing user (so the operator can
        // start from "the current state of user X" rather than from scratch).
        String[] htmls = new String[com.crm.service.ReplyHtmlSlotService.SLOT_COUNT];
        Integer activeSlot = 1;
        if (loadFromUserId != null) {
            java.util.Optional<com.crm.entity.CrmUser> uOpt = crmUserService.findById(loadFromUserId);
            if (uOpt.isPresent()) {
                com.crm.entity.CrmUser u = uOpt.get();
                for (int s = 1; s <= htmls.length; s++) htmls[s - 1] = u.getMemoSlot(s);
                activeSlot = u.getActiveMemoSlot();
            }
        }
        model.addAttribute("htmls", htmls);
        model.addAttribute("activeSlot", activeSlot);
        return "setting/memo-html-bulk";
    }

    @PostMapping("/memo-html-bulk/titles")
    public String memoHtmlSaveTitles(@RequestParam java.util.Map<String, String> params,
                                      RedirectAttributes ra) {
        for (int i = 1; i <= com.crm.service.ReplyHtmlSlotService.SLOT_COUNT; i++) {
            String v = params.get("title" + i);
            if (v != null) replyHtmlSlotService.setSlotTitle(i, v);
        }
        ra.addFlashAttribute("flashSuccess", "スロット名を保存しました");
        return "redirect:/manager/settings/memo-html-bulk";
    }

    @PostMapping("/memo-html-bulk")
    public String memoHtmlBulkApply(@RequestParam(name = "folder", required = false) String folder,
                                     @RequestParam(name = "activeSlot", required = false, defaultValue = "1") Integer activeSlot,
                                     @RequestParam java.util.Map<String, String> params,
                                     RedirectAttributes ra) {
        if (folder == null) folder = "";
        String[] htmls = new String[com.crm.service.ReplyHtmlSlotService.SLOT_COUNT];
        for (int s = 1; s <= htmls.length; s++) htmls[s - 1] = params.get("html" + s);
        int n = replyHtmlSlotService.bulkApply(folder.isEmpty() ? null : folder, htmls, activeSlot);
        String folderLabel = folder.isEmpty() ? "（未設定）" : folder;
        ra.addFlashAttribute("flashSuccess",
                "フォルダ「" + folderLabel + "」内 " + n + " 名に返信HTMLを一括適用しました（使用中: スロット" + activeSlot + "）");
        String encoded;
        try {
            encoded = java.net.URLEncoder.encode(folder, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            encoded = "";
        }
        return "redirect:/manager/settings/memo-html-bulk?folder=" + encoded;
    }

    // ====== Admin password change ======
    @GetMapping("/admin-password")
    public String adminPasswordForm() {
        return "setting/admin-password";
    }

    @PostMapping("/admin-password")
    public String adminPasswordSave(@RequestParam(name = "oldPassword", required = false) String oldPassword,
                                     @RequestParam(name = "newPassword", required = false) String newPassword,
                                     @RequestParam(name = "confirmPassword", required = false) String confirmPassword,
                                     HttpSession session,
                                     RedirectAttributes ra,
                                     Model model) {
        if (newPassword == null || !newPassword.equals(confirmPassword)) {
            model.addAttribute("errorMessage", "新しいパスワードと確認用パスワードが一致しません");
            return "setting/admin-password";
        }
        Long adminId = (Long) session.getAttribute(AuthInterceptor.SESSION_ADMIN_ID);
        if (adminId == null) return "redirect:/manager/login";
        try {
            adminAuthService.changePassword(adminId, oldPassword, newPassword);
            auditLog.record(AuditLogService.ACTION_ADMIN_PASSWORD_CHANGE, "AdminUser", adminId, null);
            session.invalidate();
            ra.addFlashAttribute("flashSuccess", "パスワードを変更しました。新しいパスワードで再度ログインしてください。");
            return "redirect:/manager/login";
        } catch (AdminAuthService.PasswordChangeException e) {
            model.addAttribute("errorMessage", e.getMessage());
            return "setting/admin-password";
        }
    }

    // ====== 本ドメイン表示設定 (root path / landing HTML — 3 fixed slots) ======

    @GetMapping("/home-html")
    public String homeHtmlForm(Model model) {
        model.addAttribute("slots", homeHtmlService.listSlots());
        return "setting/home-html";
    }

    @PostMapping("/home-html")
    public String homeHtmlSave(@RequestParam(name = "ids", required = false) java.util.List<Long> ids,
                                @RequestParam(name = "names", required = false) java.util.List<String> names,
                                @RequestParam(name = "htmlContents", required = false) java.util.List<String> htmlContents,
                                @RequestParam(name = "activeId", required = false) Long activeId,
                                RedirectAttributes ra) {
        homeHtmlService.saveSlots(ids, names, htmlContents, activeId);
        ra.addFlashAttribute("flashSuccess", "本ドメイン表示設定を保存しました");
        return "redirect:/manager/settings/home-html";
    }
}
