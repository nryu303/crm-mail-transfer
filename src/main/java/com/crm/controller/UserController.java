package com.crm.controller;

import com.crm.dto.CsvImportResult;
import com.crm.dto.PaymentForm;
import com.crm.dto.UserForm;
import com.crm.dto.UserSearchForm;
import com.crm.entity.CrmUser;
import com.crm.entity.Payment;
import com.crm.service.CarrierBindingService;
import com.crm.service.CrmUserService;
import com.crm.service.PaymentService;
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
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.validation.Valid;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Controller
@RequestMapping("/manager/users")
public class UserController {

    private static final List<String> STATUSES = Arrays.asList(CrmUser.STATUS_ACTIVE, CrmUser.STATUS_SUSPENDED);

    private final CrmUserService service;
    private final CarrierBindingService bindingService;
    private final PlaceholderService placeholderService;
    private final PaymentService paymentService;
    private final com.crm.repository.CrmUserRepository userRepository;
    private final com.crm.repository.MessageRepository messageRepository;
    private final com.crm.service.FolderSettingService folderSettingService;
    private final com.crm.service.AdminAuthService adminAuthService;
    private final com.crm.service.AdCodeService adCodeService;
    private final com.crm.service.ReplyPageSettingService replyPageSettingService;

    public UserController(CrmUserService service,
                          CarrierBindingService bindingService,
                          PlaceholderService placeholderService,
                          PaymentService paymentService,
                          com.crm.repository.CrmUserRepository userRepository,
                          com.crm.repository.MessageRepository messageRepository,
                          com.crm.service.FolderSettingService folderSettingService,
                          com.crm.service.AdminAuthService adminAuthService,
                          com.crm.service.AdCodeService adCodeService,
                          com.crm.service.ReplyPageSettingService replyPageSettingService) {
        this.service = service;
        this.bindingService = bindingService;
        this.placeholderService = placeholderService;
        this.paymentService = paymentService;
        this.userRepository = userRepository;
        this.messageRepository = messageRepository;
        this.folderSettingService = folderSettingService;
        this.adminAuthService = adminAuthService;
        this.adCodeService = adCodeService;
        this.replyPageSettingService = replyPageSettingService;
    }

    /** Active ad-code choices for autocomplete on the user-detail form. */
    @ModelAttribute("adCodeChoices")
    public List<com.crm.entity.AdCode> adCodeChoices() {
        return adCodeService.listAll();
    }

    @ModelAttribute("builtinTags")
    public List<PlaceholderService.BuiltinTag> builtinTags() {
        return PlaceholderService.BUILTIN_TAGS;
    }

    @ModelAttribute("statuses")
    public List<String> statuses() { return STATUSES; }

    @ModelAttribute("folders")
    public List<String> folderChoices() { return folderSettingService.listFolders(); }

    @GetMapping
    public String list(@ModelAttribute("searchForm") UserSearchForm searchForm, Model model) {
        Page<CrmUser> users = service.search(searchForm);
        model.addAttribute("users", users);

        // Display rank: 1..N by creation order (not the DB PK).
        java.util.List<Long> orderedIds = userRepository.findAllIdsByCreatedAsc();
        java.util.Map<Long, Integer> rankById = new java.util.HashMap<>(orderedIds.size());
        for (int i = 0; i < orderedIds.size(); i++) rankById.put(orderedIds.get(i), i + 1);
        model.addAttribute("rankById", rankById);

        // Email-domain filter choices (distinct "@domain" values currently in the DB).
        model.addAttribute("emailDomains", userRepository.findDistinctEmailDomains());
        // Configurable folder choices for the filter + bulk-move control.
        model.addAttribute("folders", folderSettingService.listFolders());
        // Active carrier-pool addresses for the bulk-bind dropdown.
        model.addAttribute("activePoolAddresses", bindingService.listActivePool());
        // Per-user last INBOUND timestamp — the column header reads 最終送信 but per operator
        // request it shows the user's most recent reply to us, not our outbound to them.
        java.util.Map<Long, java.time.LocalDateTime> lastSent = new java.util.HashMap<>();
        for (Object[] row : messageRepository.lastInboundAtByUser()) {
            if (row[0] instanceof Number && row[1] instanceof java.time.LocalDateTime) {
                lastSent.put(((Number) row[0]).longValue(), (java.time.LocalDateTime) row[1]);
            }
        }
        model.addAttribute("lastSentByUserId", lastSent);

        // Default calendar values for the period filters: 1st of current month → today.
        // Defaults for the datetime-local pickers (yyyy-MM-ddTHH:mm). "From" anchors at
        // 00:00 on the first of this month; "To" at 23:59 now.
        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.LocalDate firstOfMonth = today.withDayOfMonth(1);
        model.addAttribute("defaultPeriodFrom", firstOfMonth.atStartOfDay()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")));
        model.addAttribute("defaultPeriodTo", today.atTime(23, 59)
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")));
        return "user/list";
    }

    /**
     * Background bulk-bind-all state. Holds the latest run's progress + total + flag.
     * Single global slot — we assume one operator at a time triggers 全ユーザー割り当て.
     */
    private final java.util.concurrent.atomic.AtomicLong bindAllProgress = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong bindAllTotal    = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong bindAllCreated  = new java.util.concurrent.atomic.AtomicLong(0);
    private volatile boolean bindAllRunning = false;
    private volatile String  bindAllResultMsg = "";

    /**
     * Bind every active pool address to EVERY user (15K+ users × 15+ addresses).
     * Runs asynchronously on a background thread because the operation is expected
     * to take minutes on the 2GB-RAM tier. The frontend polls /bulk-bind-all/progress
     * for X / Y display.
     */
    @PostMapping("/bulk-bind-all")
    public String bulkBindAllUsers(RedirectAttributes ra) {
        if (bindAllRunning) {
            ra.addFlashAttribute("flashError", "全ユーザー割り当て処理は既に実行中です。完了まで少々お待ちください。");
            return "redirect:/manager/users";
        }
        bindAllRunning = true;
        bindAllProgress.set(0);
        bindAllTotal.set(0);
        bindAllCreated.set(0);
        bindAllResultMsg = "";
        new Thread(() -> {
            try {
                int created = bindingService.bindAllAvailableToAllUsers(500, bindAllProgress, bindAllTotal);
                bindAllCreated.set(created);
                bindAllResultMsg = bindAllTotal.get() + " 名へ全キャリアアドレスを割り当て (新規 " + created + " 件)";
            } catch (Exception e) {
                bindAllResultMsg = "エラー: " + e.getClass().getSimpleName() + " — " + e.getMessage();
            } finally {
                bindAllRunning = false;
            }
        }, "bulk-bind-all").start();
        ra.addFlashAttribute("flashSuccess",
                "全ユーザーへの一括割り当てを開始しました。進捗はこのページのバナーで確認できます。");
        return "redirect:/manager/users";
    }

    /** Polled by user-list JS for live progress display. */
    @GetMapping("/bulk-bind-all/progress")
    @org.springframework.web.bind.annotation.ResponseBody
    public java.util.Map<String, Object> bulkBindAllProgress() {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put("running",  bindAllRunning);
        m.put("progress", bindAllProgress.get());
        m.put("total",    bindAllTotal.get());
        m.put("created",  bindAllCreated.get());
        m.put("message",  bindAllResultMsg);
        return m;
    }

    /**
     * Dismiss the completion banner from /manager/users. Clears the cached result
     * message so the live-progress poller stops re-rendering the green '完了' notice.
     * Refuses to clear if a run is still in flight.
     */
    @PostMapping("/bulk-bind-all/clear")
    @org.springframework.web.bind.annotation.ResponseBody
    public java.util.Map<String, Object> bulkBindAllClear() {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        if (bindAllRunning) {
            m.put("ok", false);
            m.put("reason", "still_running");
            return m;
        }
        bindAllResultMsg = "";
        bindAllProgress.set(0);
        bindAllTotal.set(0);
        bindAllCreated.set(0);
        m.put("ok", true);
        return m;
    }

    /**
     * Bulk-bind a single carrier-pool address (or all active pool addresses) to the selected users.
     * Called by the 「キャリア登録割り当て」 button on the user list page.
     * If poolId == null or empty, binds ALL active pool addresses to each selected user.
     */
    @PostMapping("/bulk-bind-carrier")
    public String bulkBindCarrier(@RequestParam(name = "ids", required = false) java.util.List<Long> ids,
                                  @RequestParam(name = "poolId", required = false) Long poolId,
                                  RedirectAttributes ra) {
        if (ids == null || ids.isEmpty()) {
            ra.addFlashAttribute("flashError", "ユーザーが選択されていません");
            return "redirect:/manager/users";
        }
        int n;
        if (poolId == null) {
            n = bindingService.bindAllAvailableToMany(ids);
            ra.addFlashAttribute("flashSuccess",
                    ids.size() + " 名へ全キャリアアドレスを一括割当（新規 " + n + " 件）しました");
        } else {
            n = bindingService.bindOneToMany(poolId, ids);
            ra.addFlashAttribute("flashSuccess",
                    ids.size() + " 名へキャリアアドレスを一括割当（新規 " + n + " 件）しました");
        }
        return "redirect:/manager/users";
    }

    /**
     * Bulk-unbind: remove the (selected pool, selected users) bindings, or — when poolId is
     * empty — remove ALL bindings for each selected user. Symmetric counterpart to the
     * bulk-bind action on the user list page.
     */
    @PostMapping("/bulk-unbind-carrier")
    public String bulkUnbindCarrier(@RequestParam(name = "ids", required = false) java.util.List<Long> ids,
                                    @RequestParam(name = "poolId", required = false) Long poolId,
                                    @RequestParam(name = "scope", required = false) String scope,
                                    @RequestParam(name = "sourceFolder", required = false) String sourceFolder,
                                    @RequestParam(name = "scopeLimit", required = false) Integer scopeLimit,
                                    RedirectAttributes ra) {
        // Scope override: unbind every carrier-pool address from users in a folder.
        // scopeLimit caps the batch size so the operator can clear large folders
        // in 1K/2K chunks.
        if ("allInFolder".equals(scope)) {
            String src = sourceFolder == null ? null : sourceFolder.trim();
            if (src != null && src.isEmpty()) src = null;
            int removed = bindingService.unbindAllInFolder(src, scopeLimit);
            String srcLabel = (src == null) ? "（未設定）" : src;
            String chunkNote = (scopeLimit != null && scopeLimit > 0) ? "（上限 " + scopeLimit + " 件）" : "";
            ra.addFlashAttribute("flashSuccess",
                    "フォルダ「" + srcLabel + "」内ユーザーのキャリア割り当てを解除しました (" + removed + " 件)" + chunkNote);
            return "redirect:/manager/users";
        }

        if (ids == null || ids.isEmpty()) {
            ra.addFlashAttribute("flashError", "ユーザーが選択されていません");
            return "redirect:/manager/users";
        }
        int removed = 0;
        if (poolId == null) {
            for (Long uid : ids) {
                if (uid != null) removed += bindingService.unbindAll(uid);
            }
            ra.addFlashAttribute("flashSuccess",
                    ids.size() + " 名のキャリア割り当てを全解除（" + removed + " 件削除）しました");
        } else {
            for (Long uid : ids) {
                if (uid != null && bindingService.unbindOne(uid, poolId)) removed++;
            }
            ra.addFlashAttribute("flashSuccess",
                    ids.size() + " 名から指定キャリアアドレスの割り当てを解除（" + removed + " 件削除）しました");
        }
        return "redirect:/manager/users";
    }

    /**
     * Bulk-move the selected users into a single target folder. Called by the "選択フォルダ移動"
     * button on the user list page.
     */
    @PostMapping("/bulk-move-folder")
    public String bulkMoveFolder(@RequestParam(name = "ids", required = false) java.util.List<Long> ids,
                                 @RequestParam(name = "folder", required = false) String folder,
                                 @RequestParam(name = "scope", required = false) String scope,
                                 @RequestParam(name = "sourceFolder", required = false) String sourceFolder,
                                 @RequestParam(name = "scopeLimit", required = false) Integer scopeLimit,
                                 RedirectAttributes ra) {
        String target = folder == null ? null : folder.trim();
        if (target != null && target.isEmpty()) target = null;
        String label = (target == null) ? "（フォルダ解除）" : target;

        // Scope override: when scope=allInFolder, move EVERY user currently in
        // sourceFolder. If scopeLimit > 0 is set, only the first N (by id ASC) are
        // moved — lets operators run 15K-user reclassifies in 1K/2K-sized chunks.
        if ("allInFolder".equals(scope)) {
            String src = sourceFolder == null ? null : sourceFolder.trim();
            if (src != null && src.isEmpty()) src = null;
            String srcLabel = (src == null) ? "（未設定）" : src;
            int n;
            if (scopeLimit != null && scopeLimit > 0) {
                java.util.List<Long> limitedIds = (src == null)
                        ? userRepository.findIdsByFolderIsNull()
                        : userRepository.findIdsByFolder(src);
                if (limitedIds.size() > scopeLimit) limitedIds = limitedIds.subList(0, scopeLimit);
                n = limitedIds.isEmpty() ? 0 : userRepository.bulkUpdateFolderForIds(limitedIds, target);
            } else {
                n = userRepository.bulkUpdateFolderByFolder(src, target);
            }
            ra.addFlashAttribute("flashSuccess",
                    "フォルダ「" + srcLabel + "」内の " + n + " 名を " + label + " に移動しました");
            return "redirect:/manager/users";
        }

        if (ids == null || ids.isEmpty()) {
            ra.addFlashAttribute("flashError", "ユーザーが選択されていません");
            return "redirect:/manager/users";
        }
        int n = 0;
        for (CrmUser u : userRepository.findAllById(ids)) {
            u.setFolder(target);
            userRepository.save(u);
            n++;
        }
        ra.addFlashAttribute("flashSuccess", n + " 件のユーザーを " + label + " に移動しました");
        return "redirect:/manager/users";
    }

    @GetMapping("/new")
    public String createForm(Model model) {
        model.addAttribute("form", new UserForm());
        model.addAttribute("editing", false);
        return "user/form";
    }

    @PostMapping
    public String create(@Valid @ModelAttribute("form") UserForm form,
                         BindingResult br, RedirectAttributes ra, Model model) {
        if (br.hasErrors()) {
            model.addAttribute("editing", false);
            return "user/form";
        }
        try {
            CrmUserService.CreationResult result = service.create(form);
            ra.addFlashAttribute("flashSuccess", "ユーザーを作成しました");
            ra.addFlashAttribute("issuedCredentials", result.getCredentials());
            return "redirect:/manager/users/" + result.getUser().getId();
        } catch (CrmUserService.DuplicateEmailException e) {
            br.rejectValue("email", "duplicate", "このメールアドレスは既に登録されています");
            model.addAttribute("editing", false);
            return "user/form";
        }
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model, RedirectAttributes ra) {
        Optional<CrmUser> user = service.findById(id);
        if (!user.isPresent()) {
            ra.addFlashAttribute("flashError", "ユーザーが見つかりません");
            return "redirect:/manager/users";
        }
        model.addAttribute("user", user.get());
        // Form for inline-editing on the detail page (eliminates the separate /edit route).
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", UserForm.from(user.get()));
        }
        model.addAttribute("userId", id);
        model.addAttribute("payments", paymentService.listForUser(id));
        model.addAttribute("boundAddresses", bindingService.listBoundFor(id));
        model.addAttribute("paymentForm", paymentFormFor(id));
        model.addAttribute("paymentMethods", PAYMENT_METHODS);
        model.addAttribute("paymentStatuses", PAYMENT_STATUSES);
        // User stats for detail sidebar
        model.addAttribute("statTotalOut",   messageRepository.countByUserIdAndDirection(id, com.crm.entity.Message.DIR_OUT));
        model.addAttribute("statTotalIn",    messageRepository.countByUserIdAndDirection(id, com.crm.entity.Message.DIR_IN));
        model.addAttribute("statLastReply",  messageRepository.maxCreatedAtByUserIdAndDirection(id, com.crm.entity.Message.DIR_IN));
        java.math.BigDecimal totalPaid = paymentService.sumPaidByUser(id);
        model.addAttribute("statTotalPaid",  totalPaid != null ? totalPaid : java.math.BigDecimal.ZERO);
        return "user/detail";
    }

    private static final List<String> PAYMENT_METHODS = Arrays.asList(
            Payment.METHOD_BANK_TRANSFER, Payment.METHOD_CREDIT_CARD, Payment.METHOD_CASH);
    /** PAID listed first so the most-common selection is the default in dropdowns. */
    private static final List<String> PAYMENT_STATUSES = Arrays.asList(
            Payment.STATUS_PAID, Payment.STATUS_PENDING, Payment.STATUS_OVERDUE,
            Payment.STATUS_REFUNDED, Payment.STATUS_CANCELLED);

    private PaymentForm paymentFormFor(Long userId) {
        PaymentForm f = new PaymentForm();
        f.setUserId(userId);
        f.setStatus(Payment.STATUS_PAID);
        return f;
    }

    @PostMapping("/{id}/payments")
    public String addPayment(@PathVariable Long id,
                              @Valid @ModelAttribute("paymentForm") PaymentForm form,
                              BindingResult br, RedirectAttributes ra) {
        form.setUserId(id);
        if (br.hasErrors()) {
            String msg = br.getAllErrors().stream()
                    .map(e -> e.getDefaultMessage())
                    .filter(m -> m != null && !m.isEmpty())
                    .findFirst()
                    .orElse("入金情報の入力に誤りがあります");
            ra.addFlashAttribute("flashError", msg);
            return "redirect:/manager/users/" + id;
        }
        paymentService.create(form);
        ra.addFlashAttribute("flashSuccess", "入金を登録しました");
        return "redirect:/manager/users/" + id;
    }

    @PostMapping("/{id}/payments/{pid}/mark-paid")
    public String markPaymentPaid(@PathVariable Long id, @PathVariable Long pid, RedirectAttributes ra) {
        paymentService.markPaid(pid);
        ra.addFlashAttribute("flashSuccess", "入金済にしました");
        return "redirect:/manager/users/" + id;
    }

    @PostMapping("/{id}/payments/{pid}/delete")
    public String deletePayment(@PathVariable Long id, @PathVariable Long pid,
                                @RequestParam(name = "confirmPassword", required = false) String confirmPassword,
                                HttpSession session,
                                RedirectAttributes ra) {
        // Require admin-password confirmation: payment deletes are audit-sensitive.
        Long adminId = (Long) session.getAttribute(com.crm.interceptor.AuthInterceptor.SESSION_ADMIN_ID);
        if (!adminAuthService.verifyPassword(adminId, confirmPassword)) {
            ra.addFlashAttribute("flashError", "入金削除には管理者パスワードの確認が必要です");
            return "redirect:/manager/users/" + id;
        }
        paymentService.delete(pid);
        ra.addFlashAttribute("flashSuccess", "入金を削除しました");
        return "redirect:/manager/users/" + id;
    }

    /** Legacy path — now redirects to the detail page which has inline editing. */
    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable Long id) {
        return "redirect:/manager/users/" + id;
    }

    @PostMapping("/{id}")
    public String update(@PathVariable Long id,
                         @Valid @ModelAttribute("form") UserForm form,
                         BindingResult br,
                         @RequestParam(name = "confirmPassword", required = false) String confirmPassword,
                         HttpSession session,
                         RedirectAttributes ra) {
        if (br.hasErrors()) {
            String msg = br.getAllErrors().stream()
                    .map(e -> e.getDefaultMessage()).filter(m -> m != null && !m.isEmpty())
                    .findFirst().orElse("入力に誤りがあります");
            ra.addFlashAttribute("flashError", msg);
            ra.addFlashAttribute("form", form);
            return "redirect:/manager/users/" + id;
        }
        // Email change is gated by the admin's own login password.
        Optional<CrmUser> existing = service.findById(id);
        boolean emailChanging = existing.isPresent()
                && form.getEmail() != null
                && !form.getEmail().trim().equalsIgnoreCase(existing.get().getEmail());
        if (emailChanging) {
            Long adminId = (Long) session.getAttribute(com.crm.interceptor.AuthInterceptor.SESSION_ADMIN_ID);
            if (!adminAuthService.verifyPassword(adminId, confirmPassword)) {
                ra.addFlashAttribute("flashError", "メールアドレス変更には管理者パスワードの確認が必要です");
                ra.addFlashAttribute("form", form);
                return "redirect:/manager/users/" + id;
            }
        }
        try {
            service.update(id, form);
            ra.addFlashAttribute("flashSuccess", "ユーザーを更新しました");
            return "redirect:/manager/users/" + id;
        } catch (CrmUserService.DuplicateEmailException e) {
            ra.addFlashAttribute("flashError", "このメールアドレスは既に登録されています");
            ra.addFlashAttribute("form", form);
            return "redirect:/manager/users/" + id;
        } catch (CrmUserService.UserNotFoundException e) {
            ra.addFlashAttribute("flashError", "ユーザーが見つかりません");
            return "redirect:/manager/users";
        }
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id,
                         @RequestParam(name = "confirmPassword", required = false) String confirmPassword,
                         HttpSession session,
                         RedirectAttributes ra) {
        Long adminId = (Long) session.getAttribute(com.crm.interceptor.AuthInterceptor.SESSION_ADMIN_ID);
        if (!adminAuthService.verifyPassword(adminId, confirmPassword)) {
            ra.addFlashAttribute("flashError", "削除には管理者パスワードの確認が必要です");
            return "redirect:/manager/users/" + id;
        }
        service.delete(id);
        ra.addFlashAttribute("flashSuccess", "ユーザーを削除しました");
        return "redirect:/manager/users";
    }

    @PostMapping("/bulk-delete")
    public String bulkDelete(@RequestParam(name = "ids", required = false) List<Long> ids,
                              @RequestParam(name = "scope", required = false) String scope,
                              @RequestParam(name = "sourceFolder", required = false) String sourceFolder,
                              @RequestParam(name = "scopeLimit", required = false) Integer scopeLimit,
                              @RequestParam(name = "confirmPassword", required = false) String confirmPassword,
                              javax.servlet.http.HttpSession session,
                              RedirectAttributes ra) {
        Long adminId = (Long) session.getAttribute(com.crm.interceptor.AuthInterceptor.SESSION_ADMIN_ID);
        if (!adminAuthService.verifyPassword(adminId, confirmPassword)) {
            ra.addFlashAttribute("flashError", "一括削除には管理者パスワードの確認が必要です");
            return "redirect:/manager/users";
        }

        // Scope override: delete users in a folder. scopeLimit caps the batch size
        // so a 15K-user folder can be drained in 1K/2K chunks.
        if ("allInFolder".equals(scope)) {
            String src = sourceFolder == null ? null : sourceFolder.trim();
            if (src != null && src.isEmpty()) src = null;
            int n = service.deleteAllInFolder(src, scopeLimit);
            String srcLabel = (src == null) ? "（未設定）" : src;
            String chunkNote = (scopeLimit != null && scopeLimit > 0) ? "（上限 " + scopeLimit + " 件）" : "";
            ra.addFlashAttribute("flashSuccess",
                    "フォルダ「" + srcLabel + "」内の " + n + " 名を削除しました" + chunkNote);
            return "redirect:/manager/users";
        }

        int n = 0;
        java.util.List<String> failures = new java.util.ArrayList<>();
        if (ids != null) {
            for (Long id : ids) {
                if (id == null) continue;
                try { service.delete(id); n++; }
                catch (Exception e) { failures.add("ID=" + id + ": " + e.getMessage()); }
            }
        }
        ra.addFlashAttribute("flashSuccess", n + " 件のユーザーを削除しました");
        if (!failures.isEmpty()) {
            ra.addFlashAttribute("flashError",
                    failures.size() + " 件失敗: " + String.join("; ", failures.subList(0, Math.min(5, failures.size()))));
        }
        return "redirect:/manager/users";
    }

    @PostMapping("/{id}/reset-credentials")
    public String resetCredentials(@PathVariable Long id, RedirectAttributes ra) {
        try {
            CrmUserService.IssuedCredentials cred = service.resetCredentials(id);
            ra.addFlashAttribute("flashSuccess", "認証情報を再発行しました");
            ra.addFlashAttribute("issuedCredentials", cred);
        } catch (CrmUserService.UserNotFoundException e) {
            ra.addFlashAttribute("flashError", "ユーザーが見つかりません");
            return "redirect:/manager/users";
        }
        return "redirect:/manager/users/" + id;
    }

    @PostMapping("/{id}/bind-carrier")
    public String bindCarrier(@PathVariable Long id, RedirectAttributes ra) {
        Optional<CrmUser> u = service.findById(id);
        if (!u.isPresent()) {
            ra.addFlashAttribute("flashError", "ユーザーが見つかりません");
            return "redirect:/manager/users";
        }
        if (u.get().getCarrierDomain() == null) {
            ra.addFlashAttribute("flashError",
                    "キャリアドメインが未設定のため同キャリアの自動割当はできません。「全AMGアドレスを割り当て」で全キャリアを一括割当することは可能です。");
            return "redirect:/manager/users/" + id;
        }
        boolean bound = bindingService.autoBind(u.get()).isPresent();
        if (bound) {
            ra.addFlashAttribute("flashSuccess",
                    "キャリアアドレスを追加で割り当てました (同キャリアの空きから1つ)");
        } else {
            ra.addFlashAttribute("flashError",
                    "該当キャリアの空きアドレスがありません。キャリアプールに追加するか「全AMGアドレスを割り当て」をご利用ください。");
        }
        return "redirect:/manager/users/" + id;
    }

    @PostMapping("/{id}/bind-all-carriers")
    public String bindAllCarriers(@PathVariable Long id, RedirectAttributes ra) {
        int bound = bindingService.bindAllAvailable(id);
        if (bound > 0) {
            ra.addFlashAttribute("flashSuccess",
                    "AMGに登録されている空きキャリアアドレスを " + bound + " 件、このユーザーに割り当てました");
        } else {
            ra.addFlashAttribute("flashError",
                    "割り当て可能な空きキャリアアドレスがありませんでした。キャリアプールに登録が必要です。");
        }
        return "redirect:/manager/users/" + id;
    }

    @PostMapping("/{id}/unbind-carrier/{poolId}")
    public String unbindOneCarrier(@PathVariable Long id, @PathVariable Long poolId, RedirectAttributes ra) {
        if (bindingService.unbindOne(id, poolId)) {
            ra.addFlashAttribute("flashSuccess", "キャリアアドレスを解除しました");
        } else {
            ra.addFlashAttribute("flashError", "解除できませんでした");
        }
        return "redirect:/manager/users/" + id;
    }

    @PostMapping("/{id}/unbind-carrier")
    public String unbindCarrier(@PathVariable Long id, RedirectAttributes ra) {
        int n = bindingService.unbindAll(id);
        ra.addFlashAttribute("flashSuccess", n + " 件のキャリアアドレスを解除しました");
        return "redirect:/manager/users/" + id;
    }

    @GetMapping("/import")
    public String importForm() {
        return "user/import";
    }

    @PostMapping("/import")
    public String doImport(@RequestParam("file") MultipartFile file, Model model) throws IOException {
        if (file == null || file.isEmpty()) {
            model.addAttribute("flashError", "ファイルを選択してください");
            return "user/import";
        }
        CsvImportResult result = service.importCsv(file.getInputStream());
        model.addAttribute("result", result);
        return "user/import";
    }

    /**
     * Lightweight JSON endpoint polled by the import page during a long upload so the
     * operator sees "現在 X件 登録中..." instead of staring at a blank tab for minutes.
     * Returns the live row counter maintained by CrmUserService.importCsv().
     */
    @GetMapping("/import/progress")
    @org.springframework.web.bind.annotation.ResponseBody
    public java.util.Map<String, Object> importProgress() {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("progress", service.getImportProgress());
        body.put("running",  service.isImportRunning());
        return body;
    }

    /**
     * Admin-only preview of what the user would see on their reply page —
     * the memo, with placeholder tags substituted against this user's data.
     */
    @GetMapping("/{id}/reply-preview")
    public String replyPreview(@PathVariable Long id, Model model, RedirectAttributes ra) {
        Optional<CrmUser> user = service.findById(id);
        if (!user.isPresent()) {
            ra.addFlashAttribute("flashError", "ユーザーが見つかりません");
            return "redirect:/manager/users";
        }
        String rawMemo = user.get().getMemo();
        boolean usingDefault = rawMemo == null || rawMemo.trim().isEmpty();
        if (usingDefault) {
            // No per-user HTML; fall back to the site-wide reply page HTML from settings.
            rawMemo = replyPageSettingService.getOrCreate().getDefaultHeaderHtml();
        }
        String memoText = placeholderService.substitute(rawMemo, user.get());
        String repaired = repairHtml(memoText);
        boolean wasRepaired = memoText != null && !memoText.equals(repaired);
        model.addAttribute("user", user.get());
        model.addAttribute("memoText", repaired);
        model.addAttribute("repaired", wasRepaired);
        model.addAttribute("usingDefaultHtml", usingDefault);
        model.addAttribute("bindings", placeholderService.buildBindings(user.get()));
        return "user/reply-preview";
    }

    /**
     * Best-effort repair of a few common HTML breakages we've seen in the wild on
     * pasted memos. Currently:
     *   - missing &lt;/style&gt; for an open &lt;style&gt; block (everything after gets eaten as CSS)
     *   - orphan &lt;/script&gt; with no matching &lt;script&gt; opening (JS code dumped into HTML)
     * Returns the input unchanged if no obvious repair is needed.
     */
    static String repairHtml(String html) {
        if (html == null) return null;
        String out = html;

        // 1) <style> ... (no </style>): the user's source typically has a stray ">" on its own
        // line where "</style>" belonged. Convert that stray ">" between <style> and </head>
        // back into "</style>"; otherwise inject </style> before </head>/<body>.
        boolean hasStyleOpen = indexOfIgnoreCase(out, "<style") >= 0;
        boolean hasStyleClose = indexOfIgnoreCase(out, "</style>") >= 0;
        if (hasStyleOpen && !hasStyleClose) {
            int styleStart = indexOfIgnoreCase(out, "<style");
            int headEnd = indexOfIgnoreCase(out, "</head>");
            int bodyStart = indexOfIgnoreCase(out, "<body");
            int boundary = headEnd >= 0 ? headEnd : bodyStart;
            int strayIdx = findStrayGtLine(out, styleStart, boundary >= 0 ? boundary : out.length());
            if (strayIdx >= 0) {
                int strayEnd = out.indexOf('\n', strayIdx);
                if (strayEnd < 0) strayEnd = out.length();
                out = out.substring(0, strayIdx) + "</style>" + out.substring(strayEnd);
            } else if (boundary >= 0) {
                out = out.substring(0, boundary) + "</style>\n" + out.substring(boundary);
            }
        }

        // 2) </script> exists but <script> does not: same trick — the missing "<script>" usually
        // shows up as a stray ">" on its own line right before the JS body.
        boolean hasScriptOpen = indexOfIgnoreCase(out, "<script") >= 0;
        boolean hasScriptClose = indexOfIgnoreCase(out, "</script>") >= 0;
        if (!hasScriptOpen && hasScriptClose) {
            int closeIdx = indexOfIgnoreCase(out, "</script>");
            int footerEnd = indexOfIgnoreCase(out, "</footer>");
            int searchFrom = footerEnd >= 0 ? footerEnd : 0;
            int strayIdx = findStrayGtLine(out, searchFrom, closeIdx);
            if (strayIdx >= 0) {
                int strayEnd = out.indexOf('\n', strayIdx);
                if (strayEnd < 0) strayEnd = out.length();
                out = out.substring(0, strayIdx) + "<script>" + out.substring(strayEnd);
            } else if (footerEnd >= 0 && footerEnd < closeIdx) {
                int afterFooter = footerEnd + "</footer>".length();
                out = out.substring(0, afterFooter) + "\n<script>" + out.substring(afterFooter);
            } else {
                out = out.replaceAll("(?i)</script>", "");
            }
        }

        return out;
    }

    /** Find the LAST line in s[from..to] whose only non-whitespace content is a single '>'. */
    private static int findStrayGtLine(String s, int from, int to) {
        if (s == null || from < 0 || to <= from || to > s.length()) return -1;
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("(?m)^[ \\t]*>[ \\t]*$");
        java.util.regex.Matcher m = p.matcher(s);
        m.region(from, to);
        int found = -1;
        while (m.find()) found = m.start();
        return found;
    }

    private static int indexOfIgnoreCase(String s, String needle) {
        return s.toLowerCase().indexOf(needle.toLowerCase());
    }

    @GetMapping("/export.csv")
    public void exportCsv(@ModelAttribute("searchForm") UserSearchForm searchForm,
                          HttpServletResponse response) throws IOException {
        response.setContentType("text/csv; charset=UTF-8");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Content-Disposition",
                "attachment; filename=\"users.csv\"; filename*=UTF-8''users.csv");
        PrintWriter writer = response.getWriter();
        service.exportCsv(searchForm, writer);
        writer.flush();
    }
}
