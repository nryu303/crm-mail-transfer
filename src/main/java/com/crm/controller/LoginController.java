package com.crm.controller;

import com.crm.entity.AdminUser;
import com.crm.interceptor.AuthInterceptor;
import com.crm.service.AdminAuthService;
import com.crm.service.AuditLogService;
import com.crm.service.LoginThrottleService;
import com.crm.util.ClientIpResolver;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

@Controller
public class LoginController {

    private final AdminAuthService authService;
    private final LoginThrottleService throttle;
    private final AuditLogService auditLog;

    public LoginController(AdminAuthService authService,
                           LoginThrottleService throttle,
                           AuditLogService auditLog) {
        this.authService = authService;
        this.throttle = throttle;
        this.auditLog = auditLog;
    }

    @GetMapping("/manager/login")
    public String showLogin(HttpSession session) {
        if (session.getAttribute(AuthInterceptor.SESSION_ADMIN_ID) != null) {
            return "redirect:/manager/dashboard";
        }
        return "login";
    }

    @PostMapping("/manager/login")
    public String doLogin(@RequestParam(value = "loginId", required = false) String loginId,
                          @RequestParam(value = "password", required = false) String password,
                          HttpServletRequest request,
                          HttpSession session,
                          Model model) {
        String ip = ClientIpResolver.resolve(request);
        if (throttle.isBlocked(ip)) {
            model.addAttribute("errorMessage", "ログイン試行回数が多すぎます。しばらくお待ちください。");
            model.addAttribute("loginId", loginId);
            return "login";
        }
        Optional<AdminUser> user = authService.authenticate(loginId, password);
        if (!user.isPresent()) {
            throttle.recordFailure(ip);
            // Don't record the attempted loginId in plain text. A noisy attacker would otherwise
            // pile up valid-looking IDs in the audit log and hint at which accounts exist.
            // We keep a short hash so two failed attempts on the same ID can still be correlated.
            auditLog.record(AuditLogService.ACTION_LOGIN_FAIL, "AdminUser", null, "loginId=" + hashForLog(loginId));
            model.addAttribute("errorMessage", "ログインIDまたはパスワードが正しくありません");
            model.addAttribute("loginId", loginId);
            return "login";
        }
        throttle.recordSuccess(ip);
        // Session fixation defense: rotate the session ID on auth success so any
        // pre-existing JSESSIONID an attacker may have planted in the victim's browser
        // becomes useless. Servlet 3.1+: changeSessionId() is a no-op if no session yet,
        // and preserves attributes (we currently have none we need to keep, but be safe).
        request.changeSessionId();
        HttpSession freshSession = request.getSession();
        freshSession.setAttribute(AuthInterceptor.SESSION_ADMIN_ID, user.get().getId());
        freshSession.setAttribute(AuthInterceptor.SESSION_ADMIN_NAME, user.get().getName());
        freshSession.setAttribute(AuthInterceptor.SESSION_ADMIN_ROLE, user.get().getRole());
        auditLog.record(AuditLogService.ACTION_LOGIN_OK, "AdminUser", user.get().getId(), null);
        return "redirect:/manager/dashboard";
    }

    private static String hashForLog(String loginId) {
        if (loginId == null) return "null";
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(loginId.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("id#");
            for (int i = 0; i < 4; i++) sb.append(String.format("%02x", hash[i]));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "id#err";
        }
    }

    @GetMapping("/manager/logout")
    public String logout(HttpSession session) {
        auditLog.record(AuditLogService.ACTION_LOGOUT, "AdminUser",
                session.getAttribute(AuthInterceptor.SESSION_ADMIN_ID), null);
        session.invalidate();
        return "redirect:/manager/login";
    }
}
