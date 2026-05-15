package com.crm.interceptor;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String SESSION_ADMIN_ID = "adminUserId";
    public static final String SESSION_ADMIN_NAME = "adminUserName";
    public static final String SESSION_ADMIN_ROLE = "adminRole";

    private static final Set<String> PUBLIC_PATHS = new HashSet<>(Arrays.asList(
            "/manager/login",
            "/manager/logout"
    ));

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String path = request.getServletPath();
        if (PUBLIC_PATHS.contains(path)) {
            return true;
        }
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute(SESSION_ADMIN_ID) == null) {
            response.sendRedirect(request.getContextPath() + "/manager/login");
            return false;
        }
        return true;
    }
}
