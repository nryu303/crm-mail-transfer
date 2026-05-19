package com.crm.controller;

import com.crm.entity.HomeHtml;
import com.crm.service.HomeHtmlService;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Optional;

/**
 * Root-path handler. If an admin has marked one of the {@link HomeHtml} variants active,
 * we serve its raw HTML directly so https://&lt;host&gt;/ shows a landing page rather than
 * exposing the management UI. Otherwise fall back to the historical redirect to the
 * admin dashboard (the operator will see the login page if not signed in).
 */
@Controller
public class HomeController {

    private final HomeHtmlService homeHtmlService;

    public HomeController(HomeHtmlService homeHtmlService) {
        this.homeHtmlService = homeHtmlService;
    }

    @GetMapping("/")
    public Object root() {
        Optional<HomeHtml> active = homeHtmlService.findActive();
        if (active.isPresent() && active.get().getHtmlContent() != null
                && !active.get().getHtmlContent().isEmpty()) {
            // Serve the raw HTML as a ResponseEntity so Spring does NOT treat it as a view
            // name. text/html UTF-8 so kanji in the landing page renders correctly.
            return ResponseEntity.ok()
                    .header("Content-Type", "text/html; charset=UTF-8")
                    .body(active.get().getHtmlContent());
        }
        return "redirect:/manager/dashboard";
    }
}
