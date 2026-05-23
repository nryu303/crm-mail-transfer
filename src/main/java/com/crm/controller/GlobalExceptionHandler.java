package com.crm.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.NoHandlerFoundException;

import javax.servlet.http.HttpServletRequest;

/**
 * Catches anything uncaught by controller-local handlers. The user-facing pages here
 * deliberately do NOT echo the requested URI back to the browser and do NOT link to
 * any admin URL — those were both information leaks an attacker probing for paths
 * like /html or /admin could use to confirm a CRM panel exists at this host. The
 * stack trace and any path/method details still go to the log, just not to the page.
 *
 * Spring's own BasicErrorController is replaced by {@link MinimalErrorController},
 * which covers the path-not-handler case (404s that don't reach this advice).
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NoHandlerFoundException.class)
    public ModelAndView handle404(NoHandlerFoundException ex, HttpServletRequest req) {
        // The path is logged (for ops debugging) but never reflected to the browser.
        log.warn("404 NoHandlerFound: {} {}", req.getMethod(), req.getRequestURI());
        ModelAndView mv = new ModelAndView("error/404");
        mv.setStatus(HttpStatus.NOT_FOUND);
        return mv;
    }

    /**
     * Bot scanners constantly POST/PUT/DELETE/PROPFIND at "/" looking for misconfigured
     * apps. Spring's default surfaces these as HttpRequestMethodNotSupportedException,
     * which the catch-all below would log at ERROR with a full stack trace — drowning
     * real errors. Demote to a single-line WARN and return 405 cleanly.
     */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ModelAndView handleMethodNotAllowed(
            org.springframework.web.HttpRequestMethodNotSupportedException ex,
            HttpServletRequest req) {
        log.warn("405 Method not supported: {} {}", req.getMethod(), req.getRequestURI());
        ModelAndView mv = new ModelAndView("error/404"); // reuse the no-info page
        mv.setStatus(HttpStatus.METHOD_NOT_ALLOWED);
        return mv;
    }

    @ExceptionHandler(Exception.class)
    public ModelAndView handleAll(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception processing {} {}: {}",
                req.getMethod(), req.getRequestURI(), ex.toString(), ex);
        ModelAndView mv = new ModelAndView("error/500");
        mv.setStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        return mv;
    }
}
