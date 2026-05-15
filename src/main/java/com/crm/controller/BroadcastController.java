package com.crm.controller;

import com.crm.dto.BroadcastForm;
import com.crm.entity.Broadcast;
import com.crm.interceptor.AuthInterceptor;
import com.crm.service.BroadcastService;
import com.crm.service.CrmUserService;
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
@RequestMapping("/manager/messages/broadcast")
public class BroadcastController {

    private final BroadcastService broadcastService;
    private final MessageTemplateService templateService;
    private final CrmUserService userService;
    private final com.crm.repository.MessageRepository messageRepository;
    private final com.crm.service.AdminAuthService adminAuthService;
    private final com.crm.service.DomainSettingService settingService;
    private final com.crm.service.MessageService messageService;

    public BroadcastController(BroadcastService broadcastService,
                               MessageTemplateService templateService,
                               CrmUserService userService,
                               com.crm.repository.MessageRepository messageRepository,
                               com.crm.service.AdminAuthService adminAuthService,
                               com.crm.service.DomainSettingService settingService,
                               com.crm.service.MessageService messageService) {
        this.broadcastService = broadcastService;
        this.templateService = templateService;
        this.userService = userService;
        this.messageRepository = messageRepository;
        this.adminAuthService = adminAuthService;
        this.settingService = settingService;
        this.messageService = messageService;
    }

    /** Email-domain choices for the broadcast filter (replaces old carrierCode dropdown). */
    @ModelAttribute("emailDomainChoices")
    public List<String> emailDomainChoices() {
        return java.util.Arrays.asList("docomo.ne.jp", "i.softbank.jp", "ezweb.ne.jp", "au.com", "softbank.ne.jp");
    }

    @ModelAttribute("builtinTags")
    public List<PlaceholderService.BuiltinTag> builtinTags() { return PlaceholderService.BUILTIN_TAGS; }

    /**
     * Integrated 一斉送信返信履歴 view: every OUT message dispatched by a broadcast,
     * together with every IN reply pointing back to one of those OUTs, merged in
     * reverse-chronological order. Optional ?addr= filters by toAddress / fromAddress.
     * 100 rows per page (operator request).
     */
    @GetMapping
    public String list(@RequestParam(name = "page", defaultValue = "0") int page,
                       @RequestParam(name = "addr", required = false) String addr,
                       Model model) {
        String addrTrim = (addr == null) ? null : addr.trim();
        String addrLike = (addrTrim == null || addrTrim.isEmpty())
                ? null : "%" + addrTrim.toLowerCase() + "%";
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(
                page, 100,
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt"));
        Page<com.crm.entity.Message> messages = messageRepository.findBroadcastRelated(addrLike, pageable);

        // Resolve userIds → user attributes for the table columns
        java.util.Set<Long> uids = new java.util.HashSet<>();
        for (com.crm.entity.Message m : messages.getContent()) {
            if (m.getUserId() != null) uids.add(m.getUserId());
        }
        java.util.Map<Long, String> userEmails       = new java.util.HashMap<>();
        java.util.Map<Long, String> userDisplayNames = new java.util.HashMap<>();
        java.util.Map<Long, String> userAdCodes      = new java.util.HashMap<>();
        java.util.Map<Long, String> userFolders      = new java.util.HashMap<>();
        if (!uids.isEmpty()) {
            for (com.crm.entity.CrmUser u : userService.findAllByIds(uids)) {
                userEmails.put(u.getId(), u.getEmail());
                String name = (u.getDisplayName() == null || u.getDisplayName().isEmpty())
                        ? "" : u.getDisplayName();
                userDisplayNames.put(u.getId(), name);
                if (u.getAdCode() != null) userAdCodes.put(u.getId(), u.getAdCode());
                if (u.getFolder() != null) userFolders.put(u.getId(), u.getFolder());
            }
        }
        // FROM column shows the raw m.fromAddress (carrier pool address for OUT, user's email
        // for IN). Operator request: see which carrier address (e.g. tst8bm5xu3@i.softbank.jp)
        // was used to send — the recipient-facing domain override is not what they need here.
        model.addAttribute("messages", messages);
        model.addAttribute("userEmails", userEmails);
        model.addAttribute("userDisplayNames", userDisplayNames);
        model.addAttribute("userAdCodes", userAdCodes);
        model.addAttribute("userFolders", userFolders);
        model.addAttribute("addr", addrTrim == null ? "" : addrTrim);
        return "message/broadcast-list";
    }

    /** Broadcast-level summary (totals, status, bulk-delete). Kept as a sub-page. */
    @GetMapping("/summary")
    public String summary(@RequestParam(name = "page", defaultValue = "0") int page, Model model) {
        Page<Broadcast> broadcasts = broadcastService.list(page, 20);
        model.addAttribute("broadcasts", broadcasts);
        return "message/broadcast-summary";
    }

    @GetMapping("/new")
    public String createForm(@RequestParam(name = "userIds", required = false) List<Long> userIds,
                              Model model) {
        if (!model.containsAttribute("form")) {
            BroadcastForm f = new BroadcastForm();
            if (userIds != null && !userIds.isEmpty()) f.setTargetUserIds(userIds);
            // Rate-per-minute is configured globally on the settings page; the broadcast form
            // no longer exposes it (operator request) but the field is still wired through.
            f.setRatePerMinute(settingService.getBroadcastRatePerMinute());
            model.addAttribute("form", f);
        }
        // Pre-resolve selected users for the UI badge
        if (userIds != null && !userIds.isEmpty()) {
            model.addAttribute("selectedUsers", userService.findAllByIds(userIds));
        }
        model.addAttribute("templates", templateService.listAll());
        // Recent history for the right panel: last 50 outbound messages (SENT + scheduled).
        org.springframework.data.domain.Page<com.crm.entity.Message> recent = messageRepository.findAll(
                org.springframework.data.domain.PageRequest.of(0, 50,
                        org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt")));
        java.util.List<com.crm.entity.Message> history = new java.util.ArrayList<>();
        for (com.crm.entity.Message m : recent.getContent()) {
            if (com.crm.entity.Message.DIR_OUT.equals(m.getDirection())
                || com.crm.entity.Message.DIR_IN.equals(m.getDirection())) {
                history.add(m);
            }
        }
        model.addAttribute("history", history);
        return "message/broadcast-form";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("form") BroadcastForm form,
                         BindingResult br, HttpSession session, RedirectAttributes ra, Model model) {
        if (br.hasErrors()) {
            model.addAttribute("templates", templateService.listAll());
            return "message/broadcast-form";
        }
        Long adminId = (Long) session.getAttribute(AuthInterceptor.SESSION_ADMIN_ID);
        try {
            Broadcast b = broadcastService.createAndQueue(form, adminId);
            String kind = Broadcast.STATUS_SCHEDULED.equals(b.getStatus()) ? "予約登録" : "送信開始";
            ra.addFlashAttribute("flashSuccess",
                    "一斉送信を" + kind + "しました (対象: " + b.getTotalCount() + "件)");
            return "redirect:/manager/messages/broadcast/" + b.getId();
        } catch (BroadcastService.NoTargetsException e) {
            ra.addFlashAttribute("flashError", e.getMessage());
            ra.addFlashAttribute("form", form);
            return "redirect:/manager/messages/broadcast/new";
        }
    }

    @GetMapping("/{id}")
    public String progress(@PathVariable Long id, Model model, RedirectAttributes ra) {
        Optional<Broadcast> b = broadcastService.findById(id);
        if (!b.isPresent()) {
            ra.addFlashAttribute("flashError", "一斉送信が見つかりません");
            return "redirect:/manager/messages/broadcast";
        }
        model.addAttribute("broadcast", b.get());
        int done = (b.get().getSentCount() == null ? 0 : b.get().getSentCount())
                 + (b.get().getFailedCount() == null ? 0 : b.get().getFailedCount());
        int total = b.get().getTotalCount() == null ? 0 : b.get().getTotalCount();
        int pct = total == 0 ? 0 : (int) Math.round(done * 100.0 / total);
        model.addAttribute("donePct", pct);
        model.addAttribute("doneCount", done);
        boolean running = Broadcast.STATUS_SENDING.equals(b.get().getStatus())
                || Broadcast.STATUS_SCHEDULED.equals(b.get().getStatus());
        model.addAttribute("running", running);
        return "message/broadcast-progress";
    }

    @PostMapping("/{id}/cancel")
    public String cancel(@PathVariable Long id, RedirectAttributes ra) {
        broadcastService.cancel(id);
        ra.addFlashAttribute("flashSuccess", "一斉送信をキャンセルしました");
        return "redirect:/manager/messages/broadcast/" + id;
    }

    @PostMapping("/bulk-delete")
    public String bulkDelete(@RequestParam(name = "ids", required = false) java.util.List<Long> ids,
                              @RequestParam(name = "confirmPassword", required = false) String confirmPassword,
                              HttpSession session,
                              RedirectAttributes ra) {
        Long adminId = (Long) session.getAttribute(AuthInterceptor.SESSION_ADMIN_ID);
        if (!adminAuthService.verifyPassword(adminId, confirmPassword)) {
            ra.addFlashAttribute("flashError", "一括削除には管理者パスワードの確認が必要です");
            return "redirect:/manager/messages/broadcast";
        }
        int n = broadcastService.deleteByIds(ids);
        ra.addFlashAttribute("flashSuccess", n + " 件の一斉送信を削除しました");
        return "redirect:/manager/messages/broadcast/summary";
    }

    /**
     * Bulk-delete individual MESSAGE rows from the integrated /broadcast history view.
     * Separate from /bulk-delete (which deletes broadcasts by ID on the summary page).
     */
    @PostMapping("/messages-bulk-delete")
    public String messagesBulkDelete(@RequestParam(name = "ids", required = false) java.util.List<Long> ids,
                                     @RequestParam(name = "confirmPassword", required = false) String confirmPassword,
                                     HttpSession session,
                                     RedirectAttributes ra) {
        Long adminId = (Long) session.getAttribute(AuthInterceptor.SESSION_ADMIN_ID);
        if (!adminAuthService.verifyPassword(adminId, confirmPassword)) {
            ra.addFlashAttribute("flashError", "一括削除には管理者パスワードの確認が必要です");
            return "redirect:/manager/messages/broadcast";
        }
        int n = messageService.deleteByIds(ids);
        ra.addFlashAttribute("flashSuccess", n + " 件のメッセージを削除しました");
        return "redirect:/manager/messages/broadcast";
    }
}
