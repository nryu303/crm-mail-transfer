package com.crm.controller;

import com.crm.service.MessageService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

/**
 * 受信管理 — per-user inbox triage. Shows one row per user who has inbound messages,
 * sorted newest first, with unread count and the latest subject/preview.
 */
@Controller
public class InboxController {

    private final MessageService messageService;
    private final com.crm.service.AdminAuthService adminAuthService;
    private final com.crm.service.MessageTemplateService templateService;

    public InboxController(MessageService messageService,
                            com.crm.service.AdminAuthService adminAuthService,
                            com.crm.service.MessageTemplateService templateService) {
        this.messageService = messageService;
        this.adminAuthService = adminAuthService;
        this.templateService = templateService;
    }

    @GetMapping("/manager/inbox")
    public String inbox(@RequestParam(name = "unread", required = false) String unread,
                        Model model) {
        boolean unreadOnly = "1".equals(unread) || "true".equalsIgnoreCase(unread);
        List<MessageService.InboxRow> rows = messageService.inboxByUser(unreadOnly);
        long totalUnread = 0L;
        for (MessageService.InboxRow r : rows) totalUnread += r.getUnreadCount();
        model.addAttribute("rows", rows);
        model.addAttribute("unreadOnly", unreadOnly);
        model.addAttribute("totalUnread", totalUnread);
        // Bulk-reply panel data (operator request 2026-05-23 — same layout as the
        // single-user reply screen with template tabs + tag references).
        model.addAttribute("templates", templateService.listAll());
        model.addAttribute("templatePageTitles", templateService.listPageTitles());
        model.addAttribute("templateMaxPages", com.crm.service.MessageTemplateService.MAX_PAGES);
        model.addAttribute("builtinTags", com.crm.service.PlaceholderService.BUILTIN_TAGS);
        // User-specific tag keys are dynamic per-user, but for the bulk-reply panel we expose
        // the conventional 5-slot key names so operators can drop the tokens into the body.
        model.addAttribute("customTagTokens", java.util.Arrays.asList(
                "%amount%", "%product%", "%full_address%", "%date_jp%"));
        return "inbox/list";
    }

    /**
     * Delete all inbound (DIRECTION=IN) messages for the selected users.
     * Per-row checkbox sends userId; this removes every IN message under each one.
     */
    @PostMapping("/manager/inbox/bulk-delete")
    public String bulkDelete(@RequestParam(name = "userIds", required = false) List<Long> userIds,
                              @RequestParam(name = "confirmPassword", required = false) String confirmPassword,
                              javax.servlet.http.HttpSession session,
                              RedirectAttributes ra) {
        Long adminId = (Long) session.getAttribute(com.crm.interceptor.AuthInterceptor.SESSION_ADMIN_ID);
        if (!adminAuthService.verifyPassword(adminId, confirmPassword)) {
            ra.addFlashAttribute("flashError", "一括削除には管理者パスワードの確認が必要です");
            return "redirect:/manager/inbox";
        }
        int n = messageService.deleteInboundForUsers(userIds);
        ra.addFlashAttribute("flashSuccess", n + " 件の受信メッセージを削除しました");
        return "redirect:/manager/inbox";
    }

    /**
     * Reply to all selected users with the same subject/body. Placeholder tags
     * (%name%, %email%, %amount%, …) are substituted per-user before queueing.
     */
    /** Hard cap to prevent runaway POST bodies (DB column is TEXT 65535 bytes). */
    private static final int BULK_REPLY_BODY_MAX = 60_000;
    private static final int BULK_REPLY_SUBJ_MAX = 500;

    @PostMapping("/manager/inbox/bulk-reply")
    public String bulkReply(@RequestParam(name = "userIds", required = false) List<Long> userIds,
                             @RequestParam(name = "subject", required = false) String subject,
                             @RequestParam(name = "body",    required = false) String body,
                             RedirectAttributes ra) {
        if (subject != null && subject.length() > BULK_REPLY_SUBJ_MAX) {
            ra.addFlashAttribute("flashError", "件名は " + BULK_REPLY_SUBJ_MAX + " 文字以内で入力してください");
            return "redirect:/manager/inbox";
        }
        if (body != null && body.length() > BULK_REPLY_BODY_MAX) {
            ra.addFlashAttribute("flashError", "本文は " + BULK_REPLY_BODY_MAX + " 文字以内で入力してください");
            return "redirect:/manager/inbox";
        }
        if (userIds == null || userIds.isEmpty()) {
            ra.addFlashAttribute("flashError", "返信先のユーザーが選択されていません");
            return "redirect:/manager/inbox";
        }
        if (body == null || body.trim().isEmpty()) {
            ra.addFlashAttribute("flashError", "返信本文を入力してください");
            return "redirect:/manager/inbox";
        }
        int queued = messageService.bulkReplyToUsers(userIds, subject, body);
        ra.addFlashAttribute("flashSuccess",
                queued + " 件の返信メッセージをキューに登録しました");
        return "redirect:/manager/inbox";
    }
}
