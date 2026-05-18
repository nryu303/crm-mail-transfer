package com.crm.controller;

import com.crm.dto.MessageComposeForm;
import com.crm.entity.CrmUser;
import com.crm.entity.Message;
import com.crm.interceptor.AuthInterceptor;
import com.crm.service.CarrierBindingService;
import com.crm.service.CrmUserService;
import com.crm.service.MessageService;
import com.crm.service.MessageTemplateService;
import com.crm.service.PlaceholderService;
import org.springframework.data.domain.Page;
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
import java.util.List;
import java.util.Optional;

@Controller
public class MessageController {

    private final MessageService messageService;
    private final CrmUserService userService;
    private final PlaceholderService placeholderService;
    private final MessageTemplateService templateService;
    private final CarrierBindingService bindingService;
    private final com.crm.service.AdminAuthService adminAuthService;
    private final com.crm.service.PaymentService paymentService;

    public MessageController(MessageService messageService,
                             CrmUserService userService,
                             PlaceholderService placeholderService,
                             MessageTemplateService templateService,
                             CarrierBindingService bindingService,
                             com.crm.service.AdminAuthService adminAuthService,
                             com.crm.service.PaymentService paymentService) {
        this.messageService = messageService;
        this.userService = userService;
        this.placeholderService = placeholderService;
        this.templateService = templateService;
        this.bindingService = bindingService;
        this.adminAuthService = adminAuthService;
        this.paymentService = paymentService;
    }

    /** Global recent-messages list with tab filtering. */
    @GetMapping("/manager/messages")
    public String list(@RequestParam(name = "page", defaultValue = "0") int page,
                       @RequestParam(name = "tab", required = false) String tab,
                       Model model) {
        Page<Message> messages = messageService.recentMessages(page, 100, tab);
        model.addAttribute("messages", messages);
        model.addAttribute("tab", tab == null ? "all" : tab);
        // Resolve userId -> email for the ユーザー column so admin sees the address, not a number.
        java.util.Set<Long> uids = new java.util.HashSet<>();
        for (Message m : messages.getContent()) if (m.getUserId() != null) uids.add(m.getUserId());
        java.util.Map<Long, String> userEmails       = new java.util.HashMap<>();
        java.util.Map<Long, String> userDisplayNames = new java.util.HashMap<>();
        java.util.Map<Long, String> userAdCodes      = new java.util.HashMap<>();
        java.util.Map<Long, String> userFolders      = new java.util.HashMap<>();
        if (!uids.isEmpty()) {
            for (CrmUser u : userService.findAllByIds(uids)) {
                userEmails.put(u.getId(), u.getEmail());
                if (u.getDisplayName() != null && !u.getDisplayName().isEmpty()) {
                    userDisplayNames.put(u.getId(), u.getDisplayName());
                }
                if (u.getAdCode() != null) userAdCodes.put(u.getId(), u.getAdCode());
                if (u.getFolder() != null) userFolders.put(u.getId(), u.getFolder());
            }
        }
        model.addAttribute("userEmails", userEmails);
        model.addAttribute("userDisplayNames", userDisplayNames);
        model.addAttribute("userAdCodes", userAdCodes);
        model.addAttribute("userFolders", userFolders);
        return "message/list";
    }

    @PostMapping("/manager/messages/bulk-delete")
    public String bulkDelete(@RequestParam(name = "ids", required = false) List<Long> ids,
                              @RequestParam(name = "tab", required = false) String tab,
                              @RequestParam(name = "confirmPassword", required = false) String confirmPassword,
                              javax.servlet.http.HttpSession session,
                              RedirectAttributes ra) {
        Long adminId = (Long) session.getAttribute(com.crm.interceptor.AuthInterceptor.SESSION_ADMIN_ID);
        String back = "redirect:/manager/messages" + (tab != null && !tab.isEmpty() ? ("?tab=" + tab) : "");
        if (!adminAuthService.verifyPassword(adminId, confirmPassword)) {
            ra.addFlashAttribute("flashError", "一括削除には管理者パスワードの確認が必要です");
            return back;
        }
        int n = messageService.deleteByIds(ids);
        ra.addFlashAttribute("flashSuccess", n + " 件のメッセージを削除しました");
        return back;
    }

    /** Chat-style thread view for one user, with inline compose form. */
    @GetMapping("/manager/users/{userId}/thread")
    public String thread(@PathVariable Long userId,
                         @RequestParam(name = "replyTo", required = false) Long replyTo,
                         Model model, RedirectAttributes ra) {
        Optional<CrmUser> user = userService.findById(userId);
        if (!user.isPresent()) {
            ra.addFlashAttribute("flashError", "ユーザーが見つかりません");
            return "redirect:/manager/users";
        }
        // Mark inbound as read when admin opens the thread (drives dashboard unread-count)
        messageService.markThreadAsRead(userId);
        List<Message> thread = messageService.threadFor(userId);
        // Compute per-user thread stats for pane-tr header
        long threadWebReply = 0, threadMailReply = 0, threadOut = 0;
        for (com.crm.entity.Message m : thread) {
            if (com.crm.entity.Message.DIR_IN.equals(m.getDirection())) {
                if ("WEB_REPLY".equals(m.getChannel())) threadWebReply++;
                else if ("EMAIL".equals(m.getChannel())) threadMailReply++;
            } else if (com.crm.entity.Message.DIR_OUT.equals(m.getDirection())) {
                threadOut++;
            }
        }
        java.math.BigDecimal totalPaid = paymentService.sumPaidByUser(userId);

        model.addAttribute("user", user.get());
        model.addAttribute("thread", thread);
        model.addAttribute("threadWebReply", threadWebReply);
        model.addAttribute("threadMailReply", threadMailReply);
        model.addAttribute("threadOut", threadOut);
        model.addAttribute("totalPaid", totalPaid != null ? totalPaid : java.math.BigDecimal.ZERO);
        model.addAttribute("bindings", placeholderService.buildBindings(user.get()));
        model.addAttribute("builtinTags", PlaceholderService.BUILTIN_TAGS);
        model.addAttribute("templates", templateService.listAll());
        model.addAttribute("boundAddresses", bindingService.listBoundFor(userId));
        // Left-upper inbox list (all users with any inbound, newest first).
        model.addAttribute("inboxRows", messageService.inboxByUser(false));
        if (!model.containsAttribute("form")) {
            MessageComposeForm form = new MessageComposeForm();
            if (replyTo != null) {
                // Pre-fill with reply context
                thread.stream()
                        .filter(m -> replyTo.equals(m.getId()) && Message.DIR_IN.equals(m.getDirection()))
                        .findFirst()
                        .ifPresent(original -> {
                            form.setReplyToMessageId(original.getId());
                            String subj = original.getSubject() == null ? "" : original.getSubject();
                            if (!subj.startsWith("Re:")) subj = "Re: " + subj;
                            form.setSubject(subj);
                        });
            }
            model.addAttribute("form", form);
        }
        return "message/thread";
    }

    /** Submit a new outbound message for a specific user. */
    @PostMapping("/manager/users/{userId}/messages")
    public String sendMessage(@PathVariable Long userId,
                              @Valid @ModelAttribute("form") MessageComposeForm form,
                              BindingResult br,
                              HttpSession session,
                              RedirectAttributes ra,
                              Model model) {
        if (br.hasErrors()) {
            Optional<CrmUser> user = userService.findById(userId);
            if (!user.isPresent()) {
                ra.addFlashAttribute("flashError", "ユーザーが見つかりません");
                return "redirect:/manager/users";
            }
            model.addAttribute("user", user.get());
            model.addAttribute("thread", messageService.threadFor(userId));
            model.addAttribute("bindings", placeholderService.buildBindings(user.get()));
            model.addAttribute("builtinTags", PlaceholderService.BUILTIN_TAGS);
            model.addAttribute("templates", templateService.listAll());
            return "message/thread";
        }
        Long adminId = (Long) session.getAttribute(AuthInterceptor.SESSION_ADMIN_ID);
        try {
            Message sent = messageService.compose(userId, adminId, form);
            String kind = Message.STATUS_QUEUED.equals(sent.getStatus()) ? "予約送信" : "送信";
            ra.addFlashAttribute("flashSuccess", "メッセージを" + kind + "しました");
        } catch (MessageService.MessageException e) {
            ra.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/manager/users/" + userId + "/thread";
    }

    /**
     * Dismiss a user from the thread page's left-upper 受信 list. No password required —
     * the client confirmed this should be a one-click operation. Every IN message for the
     * user is flagged INBOX_DISMISSED_AT=NOW(); rows stay in MESSAGE so the 過去のやり取り
     * pane is unaffected. Returns 204 so the in-page JS can update the DOM without a
     * full reload (which would lose any draft reply the operator was typing).
     */
    @PostMapping("/manager/users/{userId}/inbox/dismiss")
    @org.springframework.web.bind.annotation.ResponseBody
    public org.springframework.http.ResponseEntity<java.util.Map<String, Object>> dismissInbox(
            @PathVariable Long userId) {
        int n = messageService.dismissInboxForUser(userId);
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("dismissed", n);
        return org.springframework.http.ResponseEntity.ok(body);
    }

    @PostMapping("/manager/messages/{id}/cancel")
    public String cancel(@PathVariable Long id, RedirectAttributes ra) {
        if (messageService.cancelScheduled(id)) {
            ra.addFlashAttribute("flashSuccess", "予約送信をキャンセルしました");
        } else {
            ra.addFlashAttribute("flashError", "キャンセルできませんでした (予約状態のみキャンセル可能です)");
        }
        return "redirect:/manager/messages";
    }

    @GetMapping("/manager/messages/export.csv")
    public void exportCsv(@org.springframework.web.bind.annotation.RequestParam(name = "tab", required = false) String tab,
                          javax.servlet.http.HttpServletResponse response) throws java.io.IOException {
        response.setContentType("text/csv; charset=UTF-8");
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        response.setHeader("Content-Disposition", "attachment; filename=\"messages.csv\"; filename*=UTF-8''messages.csv");
        messageService.exportCsv(tab, response.getWriter());
        response.getWriter().flush();
    }

    @PostMapping("/manager/messages/{id}/retry")
    public String retry(@PathVariable Long id,
                        @org.springframework.web.bind.annotation.RequestParam(name = "returnTo", required = false) String returnTo,
                        RedirectAttributes ra) {
        if (messageService.retrySend(id)) {
            ra.addFlashAttribute("flashSuccess", "メッセージを再送しました");
        } else {
            ra.addFlashAttribute("flashError", "再送できませんでした (失敗またはキャンセル状態の送信メッセージのみ対象です)");
        }
        if (returnTo != null && returnTo.startsWith("/manager/")) {
            return "redirect:" + returnTo;
        }
        return "redirect:/manager/messages";
    }
}
