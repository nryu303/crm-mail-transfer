package com.crm.interceptor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.crm.util.ClientIpResolver;
import com.crm.util.LogSafe;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Lightweight CSRF guard.
 *
 * <p>Defense layers:
 * <ul>
 *   <li>Session cookie already uses {@code SameSite=Strict}, which is the primary barrier.</li>
 *   <li>This interceptor adds a per-session token stored on the session; state-changing
 *       requests (POST/PUT/DELETE/PATCH) must include the same token in the {@code _csrf}
 *       form parameter or the {@code X-CSRF-Token} header.</li>
 * </ul>
 *
 * <p>On GET requests the token is exposed as request attribute {@code _csrf} so Thymeleaf
 * templates can render it into hidden form inputs (see {@code layout/default.html}).
 *
 * <p>Exemptions:
 * <ul>
 *   <li>The login POST (no session yet — token is bootstrapped on first GET).</li>
 *   <li>The inbound webhook {@code /api/inbound/receive-raw} (IP-allow-listed).</li>
 * </ul>
 */
@Component
public class CsrfInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(CsrfInterceptor.class);
    private static final String SESSION_KEY = "csrf.token";
    private static final String PARAM = "_csrf";
    private static final String HEADER = "X-CSRF-Token";
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Set<String> SAFE_METHODS = new HashSet<>(Arrays.asList("GET", "HEAD", "OPTIONS"));
    /** Paths exempted from CSRF (bootstrap login + IP-restricted webhook). */
    private static final Set<String> EXEMPT_PATHS = new HashSet<>(Arrays.asList(
            "/manager/login",
            "/api/inbound/receive-raw"
    ));

    /**
     * Reply-page exemption — only the bootstrap GET and the single submit POST.
     * Tightened from {@code path.startsWith("/reply/")} so any new endpoint added
     * under /reply/ does NOT silently inherit the CSRF bypass.
     */
    private static final Pattern REPLY_EXEMPT = Pattern.compile(
            "^/reply/[A-Za-z0-9_-]+(/send|/attachment)?$");

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        HttpSession session = request.getSession(true);
        String token = (String) session.getAttribute(SESSION_KEY);
        if (token == null) {
            token = generateToken();
            session.setAttribute(SESSION_KEY, token);
        }
        // Expose to templates for hidden-input rendering.
        request.setAttribute(PARAM, token);

        String method = request.getMethod();
        if (SAFE_METHODS.contains(method)) return true;

        // Exempt bootstrap routes. Compare on the servlet path (not the full URL).
        String path = request.getServletPath();
        if (EXEMPT_PATHS.contains(path)) return true;

        // Reply-page submissions come from public unauthenticated users → no session CSRF is
        // meaningful there. Rate-limiting guards that endpoint instead. Match exact patterns
        // only (not a prefix) so future /reply/* endpoints don't auto-inherit the bypass.
        if (path != null && REPLY_EXEMPT.matcher(path).matches()) return true;
        // /media/** is the agency dashboard — gated by Basic Auth, no admin session, GET-only.
        if (path != null && path.startsWith("/media/")) return true;

        String submitted = request.getParameter(PARAM);
        if (submitted == null || submitted.isEmpty()) submitted = request.getHeader(HEADER);
        if (submitted == null || !constantTimeEquals(token, submitted)) {
            log.warn("CSRF rejected: method={} path={} ip={}",
                    LogSafe.of(method), LogSafe.of(path),
                    LogSafe.of(ClientIpResolver.resolve(request)));
            try {
                response.sendError(HttpServletResponse.SC_FORBIDDEN,
                        "CSRF token missing or invalid");
            } catch (Exception ignored) { /* best-effort */ }
            return false;
        }
        return true;
    }

    private static String generateToken() {
        byte[] buf = new byte[24];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        int r = 0;
        for (int i = 0; i < a.length(); i++) r |= a.charAt(i) ^ b.charAt(i);
        return r == 0;
    }
}
