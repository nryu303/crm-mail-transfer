package com.crm.interceptor;

import com.crm.entity.AdCode;
import com.crm.entity.AdGroupCredential;
import com.crm.repository.AdCodeRepository;
import com.crm.service.AdCodeService;
import com.crm.service.AdGroupCredentialService;
import com.crm.service.DomainSettingService;
import com.crm.util.ClientIpResolver;
import com.crm.util.LogSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * HTTP Basic Auth gate for the public agency dashboard at {@code /media/**}.
 *
 * Defenses (this version):
 *   • Constant-time credential compare via {@link MessageDigest#isEqual}.
 *   • 30-second in-memory cache of the credentials so a flood of /media/** requests
 *     doesn't translate 1:1 into DB hits on CRM_SETTING.
 *   • Per-IP failure budget — after 10 wrong-credential attempts within 60 sec the
 *     IP is blocked for 5 minutes (HTTP 429). Resets on successful auth.
 *   • If the credentials are unset in CRM_SETTING the dashboard returns 503 so the
 *     operator notices the misconfig instead of accidentally exposing zero auth.
 */
@Component
public class MediaAuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(MediaAuthInterceptor.class);
    private static final String REALM = "kaiun-agency";

    /** How long credentials read from DB are reused before re-fetching. */
    private static final long CACHE_TTL_MS = 30_000L;

    /** Per-IP failure budget. */
    private static final int  FAILURE_LIMIT_PER_WINDOW = 10;
    private static final long FAILURE_WINDOW_MS        = 60_000L;
    private static final long BLOCK_MS                 = 300_000L;
    /** Hard cap on the failure-tracking map so a flood of unique IPs can't OOM us. */
    private static final int  MAX_TRACKED_IPS = 10_000;

    private final DomainSettingService settings;
    private final AdGroupCredentialService groupCredentialService;
    private final AdCodeRepository adCodeRepository;

    /** Cached global credentials. Volatile via AtomicReference so swap is atomic across threads. */
    private final AtomicReference<CredentialsCache> credCache = new AtomicReference<>();

    /** Failure tracking by client IP. */
    private final Map<String, FailureBucket> failuresByIp = new ConcurrentHashMap<>();

    public MediaAuthInterceptor(DomainSettingService settings,
                                 AdGroupCredentialService groupCredentialService,
                                 AdCodeRepository adCodeRepository) {
        this.settings = settings;
        this.groupCredentialService = groupCredentialService;
        this.adCodeRepository = adCodeRepository;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String clientIp = ClientIpResolver.resolve(request);

        // Block first — a hammered IP doesn't get to read settings.
        if (isBlocked(clientIp)) {
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(BLOCK_MS / 1000));
            response.setContentType("text/plain; charset=UTF-8");
            response.getWriter().write("試行回数が多すぎます。しばらくしてから再度お試しください。");
            log.warn("/media/** rate-limited ip={}", LogSafe.of(clientIp));
            return false;
        }

        // Determine the group context this request is for. /media/index/ (no params) is the
        // cross-group "all groups" view → only the global admin creds let you in.
        // /media/index/?g=foo or /media/index/{code} narrows to a specific group → that
        // group's per-group creds (or the global admin creds, which override).
        String requestedGroup = resolveRequestedGroup(request);

        // Parse the Authorization header.
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Basic ")) {
            return challenge(response, requestedGroup);
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(authHeader.substring(6).trim()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            recordFailure(clientIp);
            return challenge(response, requestedGroup);
        }
        int colon = decoded.indexOf(':');
        if (colon < 0) {
            recordFailure(clientIp);
            return challenge(response, requestedGroup);
        }
        String user = decoded.substring(0, colon);
        String pass = decoded.substring(colon + 1);

        // Role-based scoping:
        //   • Admin creds (global) work on every URL — cross-group view AND any group URL.
        //     This keeps the admin-side preview workflow unchanged.
        //   • Per-group creds work ONLY on that group's URLs. Agency A's credentials cannot
        //     unlock Agency B's URL (cross-tenant separation preserved).
        //   • Cross-group view (/media/index/ with no ?g=) is admin-only — agencies should
        //     never share that URL.
        Credentials globalCreds = loadCachedCredentials();

        if (requestedGroup == null) {
            // Cross-group view — admin creds only.
            if (globalCreds == null) {
                response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                response.setContentType("text/plain; charset=UTF-8");
                response.getWriter().write("代理店ダッシュボードの管理者ID/パスワードが未設定です。管理画面 > 広告設定 で設定してください。");
                return false;
            }
            if (constantTimeEquals(user, globalCreds.user)
                    && constantTimeEquals(pass, globalCreds.password)) {
                failuresByIp.remove(clientIp);
                return true;
            }
            recordFailure(clientIp);
            return challenge(response, null);
        }

        // Group URL: admin creds OR that group's creds.
        if (globalCreds != null
                && constantTimeEquals(user, globalCreds.user)
                && constantTimeEquals(pass, globalCreds.password)) {
            failuresByIp.remove(clientIp);
            return true;
        }
        AdGroupCredential groupCreds = groupCredentialService.findByGroupName(requestedGroup).orElse(null);
        if (groupCreds != null
                && constantTimeEquals(user, groupCreds.getAuthUser())
                && constantTimeEquals(pass, groupCreds.getAuthPassword())) {
            failuresByIp.remove(clientIp);
            return true;
        }
        recordFailure(clientIp);
        return challenge(response, requestedGroup);
    }

    /**
     * Figure out which group this request is asking for. Returns null for the cross-group
     * landing view (/media/index/ with no params). Sources, in order:
     *   • {@code ?g=slug} query param  (slug = URL-encoded group name)
     *   • path {@code /media/index/{code}} → look up AD_CODE.code → its group name
     */
    private String resolveRequestedGroup(HttpServletRequest request) {
        String slug = request.getParameter("g");
        if (slug != null && !slug.isEmpty()) {
            return AdCodeService.unslugify(slug);
        }
        String path = request.getServletPath();
        if (path != null && path.startsWith("/media/index/")) {
            String tail = path.substring("/media/index/".length());
            // Strip trailing slash, ignore reserved sub-paths like "csv".
            if (tail.endsWith("/")) tail = tail.substring(0, tail.length() - 1);
            if (!tail.isEmpty() && !"csv".equals(tail)) {
                return adCodeRepository.findByCode(tail)
                        .map(AdCode::getName)
                        .orElse(null);
            }
        }
        return null;
    }

    /** Pull credentials from the cache; refresh from DB when stale. Returns null if unset. */
    private Credentials loadCachedCredentials() {
        CredentialsCache cached = credCache.get();
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.loadedAtMs < CACHE_TTL_MS) {
            return cached.value;
        }
        String u = settings.getMediaAuthUser();
        String p = settings.getMediaAuthPassword();
        Credentials value = (isBlank(u) || isBlank(p)) ? null : new Credentials(u, p);
        credCache.set(new CredentialsCache(value, now));
        return value;
    }

    /** Force-invalidate the cache. Call from settings UI when an admin saves new creds. */
    public void invalidateCache() { credCache.set(null); }

    private boolean isBlocked(String ip) {
        FailureBucket b = failuresByIp.get(ip);
        if (b == null) return false;
        synchronized (b) { return b.blockedUntilMs > System.currentTimeMillis(); }
    }

    private void recordFailure(String ip) {
        if (ip == null || ip.isEmpty()) return;
        // Best-effort guard against unbounded growth from an attacker rotating IPs.
        if (failuresByIp.size() >= MAX_TRACKED_IPS) {
            // Drop the whole table; legitimate users get a clean slate, attacker keeps starting over.
            failuresByIp.clear();
        }
        FailureBucket b = failuresByIp.computeIfAbsent(ip, k -> new FailureBucket());
        synchronized (b) {
            long now = System.currentTimeMillis();
            if (now - b.windowStartMs > FAILURE_WINDOW_MS) {
                b.windowStartMs = now;
                b.count = 0;
            }
            b.count++;
            if (b.count >= FAILURE_LIMIT_PER_WINDOW) {
                b.blockedUntilMs = now + BLOCK_MS;
                log.warn("/media/** blocking ip={} after {} failures", LogSafe.of(ip), b.count);
            }
        }
    }

    private static boolean challenge(HttpServletResponse response, String groupName) throws java.io.IOException {
        // Different realm per group so browsers don't auto-reuse one group's saved password
        // for another. HTTP headers are ISO-8859-1 — non-ASCII chars (Japanese group names
        // like "レーキ") would throw IllegalArgumentException, so we use the URL-safe slug
        // which is pure ASCII.
        String realm = (groupName == null || groupName.isEmpty())
                ? REALM
                : (REALM + ":" + AdCodeService.slugify(groupName).replaceAll("[\\r\\n\"]", ""));
        response.setHeader("WWW-Authenticate", "Basic realm=\"" + realm + "\", charset=\"UTF-8\"");
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        return false;
    }

    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] ab = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(ab, bb);
    }

    private static final class Credentials {
        final String user, password;
        Credentials(String u, String p) { this.user = u; this.password = p; }
    }

    private static final class CredentialsCache {
        final Credentials value;
        final long loadedAtMs;
        CredentialsCache(Credentials v, long t) { this.value = v; this.loadedAtMs = t; }
    }

    private static final class FailureBucket {
        long windowStartMs = System.currentTimeMillis();
        long blockedUntilMs;
        int count;
    }
}
