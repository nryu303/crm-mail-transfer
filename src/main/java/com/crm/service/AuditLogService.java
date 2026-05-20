package com.crm.service;

import com.crm.entity.AuditLog;
import com.crm.interceptor.AuthInterceptor;
import com.crm.repository.AuditLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

/**
 * Records a row in AUDIT_LOG for each sensitive admin action.
 * Called from controllers/services that need to leave a trail.
 * Pulls the admin identity and IP from the current request if available.
 */
@Service
public class AuditLogService {

    // action constants (kept as strings to keep the log schema-free)
    public static final String ACTION_LOGIN_OK = "LOGIN_OK";
    public static final String ACTION_LOGIN_FAIL = "LOGIN_FAIL";
    public static final String ACTION_LOGOUT = "LOGOUT";
    public static final String ACTION_ADMIN_PASSWORD_CHANGE = "ADMIN_PASSWORD_CHANGE";
    public static final String ACTION_USER_CREATE = "USER_CREATE";
    public static final String ACTION_USER_UPDATE = "USER_UPDATE";
    public static final String ACTION_USER_DELETE = "USER_DELETE";
    public static final String ACTION_USER_CREDENTIAL_RESET = "USER_CREDENTIAL_RESET";
    public static final String ACTION_CARRIER_POOL_CREATE = "CARRIER_POOL_CREATE";
    public static final String ACTION_CARRIER_POOL_DELETE = "CARRIER_POOL_DELETE";
    public static final String ACTION_CARRIER_BIND = "CARRIER_BIND";
    public static final String ACTION_CARRIER_UNBIND = "CARRIER_UNBIND";
    public static final String ACTION_MESSAGE_SEND = "MESSAGE_SEND";
    public static final String ACTION_BROADCAST_CREATE = "BROADCAST_CREATE";
    public static final String ACTION_BROADCAST_CANCEL = "BROADCAST_CANCEL";
    public static final String ACTION_PAYMENT_CREATE = "PAYMENT_CREATE";
    public static final String ACTION_PAYMENT_UPDATE = "PAYMENT_UPDATE";
    public static final String ACTION_PAYMENT_DELETE = "PAYMENT_DELETE";
    public static final String ACTION_PAYMENT_MARK_PAID = "PAYMENT_MARK_PAID";
    public static final String ACTION_SETTINGS_UPDATE = "SETTINGS_UPDATE";

    private final AuditLogRepository repository;

    public AuditLogService(AuditLogRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void record(String action, String entityType, Object entityId, String detail) {
        AuditLog log = new AuditLog();
        log.setAction(action);
        log.setEntityType(entityType);
        log.setEntityId(entityId == null ? null : String.valueOf(entityId));
        log.setDetail(truncate(detail, 1024));

        HttpServletRequest req = currentRequest();
        if (req != null) {
            HttpSession session = req.getSession(false);
            if (session != null) {
                Object id = session.getAttribute(AuthInterceptor.SESSION_ADMIN_ID);
                Object name = session.getAttribute(AuthInterceptor.SESSION_ADMIN_NAME);
                if (id instanceof Long) log.setAdminUserId((Long) id);
                if (name != null) log.setAdminName(String.valueOf(name));
            }
            log.setIpAddress(clientIp(req));
        }
        try {
            repository.save(log);
        } catch (Exception ignore) {
            // Audit logging must never break the user-facing request.
        }
    }

    public Page<AuditLog> recent(int page, int size) {
        return repository.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size));
    }

    /**
     * Daily-at-04:00 cleanup of audit rows older than 30 days. Operator-visible policy: the
     * audit log UI claims "1ヶ月の履歴が残ります", and this enforces it so the table doesn't grow
     * unbounded on high-traffic deployments.
     */
    @org.springframework.scheduling.annotation.Scheduled(cron = "0 0 4 * * *")
    public void purgeOld() {
        java.time.LocalDateTime cutoff = java.time.LocalDateTime.now().minusDays(30);
        try {
            int n = repository.deleteByCreatedAtBefore(cutoff);
            if (n > 0) {
                org.slf4j.LoggerFactory.getLogger(AuditLogService.class)
                        .info("Audit-log purge: removed {} row(s) older than {}", n, cutoff);
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(AuditLogService.class)
                    .warn("Audit-log purge failed", e);
        }
    }

    private static String truncate(String s, int max) {
        return s == null ? null : (s.length() <= max ? s : s.substring(0, max));
    }

    private static HttpServletRequest currentRequest() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            return attrs == null ? null : attrs.getRequest();
        } catch (Exception e) { return null; }
    }

    private static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return req.getRemoteAddr();
    }
}
