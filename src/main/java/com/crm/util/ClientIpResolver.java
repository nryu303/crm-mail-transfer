package com.crm.util;

import javax.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Resolve the real client IP, honouring {@code X-Forwarded-For} only when the
 * immediate peer is a trusted reverse proxy.
 *
 * Without this gate, a request that bypasses nginx and hits the Spring server
 * directly can spoof XFF and impersonate any IP — defeating the AMG IP allow-list
 * on the inbound webhook and the per-IP login throttle.
 *
 * Trust set is intentionally small: only loopback. The Spring server is bound to
 * 127.0.0.1 so non-trusted peers cannot reach us in the first place; this is
 * defence-in-depth in case that bind is ever changed or in dev/test setups.
 */
public final class ClientIpResolver {

    private static final Set<String> TRUSTED_PROXIES;
    static {
        Set<String> s = new HashSet<>();
        s.add("127.0.0.1");
        s.add("0:0:0:0:0:0:0:1");
        s.add("::1");
        TRUSTED_PROXIES = Collections.unmodifiableSet(s);
    }

    public static String resolve(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (!TRUSTED_PROXIES.contains(remoteAddr)) {
            return remoteAddr;
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (xff == null || xff.trim().isEmpty()) {
            String xri = request.getHeader("X-Real-IP");
            if (xri != null && !xri.trim().isEmpty()) return xri.trim();
            return remoteAddr;
        }
        int comma = xff.indexOf(',');
        return (comma > 0 ? xff.substring(0, comma) : xff).trim();
    }

    private ClientIpResolver() {}
}
