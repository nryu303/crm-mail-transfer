package com.crm.controller;

import com.crm.service.HomeHtmlService;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.RequestDispatcher;
import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

/**
 * Replaces Spring Boot's BasicErrorController so the public-facing /error response
 * never leaks the requested path, exception class, stack trace, or timestamp. By
 * default BasicErrorController returns a JSON like
 * {@code {"timestamp":"...","status":404,"error":"Not Found","path":"/html"}} for
 * non-HTML clients, which an attacker probing for endpoints could use to confirm
 * which URLs exist. We also strip the in-page "ダッシュボードへ戻る" link from the
 * HTML templates — pointing a 404 visitor at /manager/* disclosed the admin panel
 * to anyone hitting a wrong URL.
 *
 * When an admin has configured an active {@link com.crm.entity.HomeHtml} variant we
 * surface that as the 404 destination via forward (status stays 404, but the body is
 * the landing page) — without a configured landing page we render the minimal local
 * error/404 template instead.
 */
@Controller
@RequestMapping("${server.error.path:${error.path:/error}}")
public class MinimalErrorController implements ErrorController {

    private final HomeHtmlService homeHtmlService;

    public MinimalErrorController(HomeHtmlService homeHtmlService) {
        this.homeHtmlService = homeHtmlService;
    }

    /** HTML responses — minimal page, never echoes the path, never links to admin. */
    @RequestMapping(produces = MediaType.TEXT_HTML_VALUE)
    public ModelAndView handleHtml(HttpServletRequest req) {
        int status = resolveStatus(req);
        // On 404, prefer to forward to the active landing-page HTML if one is configured —
        // an attacker probing for wrong paths then sees the legitimate public homepage and
        // can't tell that path / didn't exist on the server. Without an active HomeHtml we
        // fall back to the minimal local 404 template.
        if (status == 404 && homeHtmlService.findActive().isPresent()) {
            return new ModelAndView("forward:/");
        }
        ModelAndView mv = new ModelAndView(status == 404 ? "error/404" : "error/500");
        mv.setStatus(org.springframework.http.HttpStatus.valueOf(status));
        return mv;
    }

    /** JSON / non-HTML responses — minimal body, no path, no timestamp. */
    @RequestMapping
    @ResponseBody
    public Map<String, Object> handleJson(HttpServletRequest req) {
        int status = resolveStatus(req);
        Map<String, Object> body = new HashMap<>();
        body.put("status", status);
        body.put("error", status == 404 ? "Not Found" : "Error");
        return body;
    }

    private static int resolveStatus(HttpServletRequest req) {
        Object code = req.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (code instanceof Integer) return (Integer) code;
        return 500;
    }
}
