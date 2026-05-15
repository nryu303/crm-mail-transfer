package com.crm.controller;

import com.crm.entity.AdCode;
import com.crm.service.AdCodeService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Public agency dashboard. Path-mapped to {@code /media/**} which is gated by
 * {@link com.crm.interceptor.MediaAuthInterceptor} (HTTP Basic Auth) — credentials
 * are stored in CRM_SETTING and editable from the 広告設定 admin page.
 *
 * Two views:
 *   GET /media/index/                         — list of all ad codes for a given month
 *   GET /media/index/{code}/                  — daily breakdown for one ad code
 *   GET /media/index/csv?ym=YYYY-MM           — CSV download of the list view
 */
@Controller
@RequestMapping("/media/index")
public class AdMediaController {

    private final AdCodeService service;

    public AdMediaController(AdCodeService service) { this.service = service; }

    @GetMapping({"", "/"})
    public String list(@RequestParam(name = "ym", required = false) String ymParam,
                       @RequestParam(name = "codes", required = false) String codesParam,
                       @RequestParam(name = "g", required = false) String groupSlugParam,
                       Model model) {
        YearMonth target = parseYm(ymParam).orElse(YearMonth.now());
        Set<String> filter = parseCodeFilter(codesParam);
        // {?g=slug} carries the URL-encoded group name. Empty/missing → cross-group view.
        String groupName = (groupSlugParam == null || groupSlugParam.isEmpty())
                ? null : AdCodeService.unslugify(groupSlugParam);

        List<AdCodeService.AgencyRow> rows = service.agencySummary(target, filter, groupName);
        int totalRows = rows.size();

        model.addAttribute("rows", rows);
        model.addAttribute("totalRows", totalRows);
        model.addAttribute("targetMonth", target);
        model.addAttribute("prevMonth", target.minusMonths(1));
        model.addAttribute("nextMonth", target.plusMonths(1));
        model.addAttribute("codesFilter", codesParam == null ? "" : codesParam);
        model.addAttribute("groupName", groupName);
        model.addAttribute("groupSlug", groupSlugParam == null ? "" : groupSlugParam);
        return "ad-code/external-list";
    }

    @GetMapping("/csv")
    public void csv(@RequestParam(name = "ym", required = false) String ymParam,
                    @RequestParam(name = "codes", required = false) String codesParam,
                    @RequestParam(name = "g", required = false) String groupSlugParam,
                    HttpServletResponse response) throws IOException {
        YearMonth target = parseYm(ymParam).orElse(YearMonth.now());
        Set<String> filter = parseCodeFilter(codesParam);
        String groupName = (groupSlugParam == null || groupSlugParam.isEmpty())
                ? null : AdCodeService.unslugify(groupSlugParam);
        List<AdCodeService.AgencyRow> rows = service.agencySummary(target, filter, groupName);

        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"agency_" + target + ".csv\"");
        try (PrintWriter w = new PrintWriter(
                new java.io.OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8))) {
            w.write('﻿'); // BOM for Excel
            w.println("代理店コード,代理店名,登録(男),登録(女),合計登録,入金(男),入金(女),入金合計");
            long mTot=0, fTot=0, totTot=0;
            java.math.BigDecimal pM = java.math.BigDecimal.ZERO,
                                 pF = java.math.BigDecimal.ZERO,
                                 pT = java.math.BigDecimal.ZERO;
            for (AdCodeService.AgencyRow r : rows) {
                w.printf("%s,%s,%d,%d,%d,%d,%d,%d%n",
                        csv(r.code), csv(r.name),
                        r.signupMale, r.signupFemale, r.getSignupTotal(),
                        r.paidMale.longValue(), r.paidFemale.longValue(), r.getPaidTotal().longValue());
                mTot += r.signupMale; fTot += r.signupFemale; totTot += r.getSignupTotal();
                pM = pM.add(r.paidMale); pF = pF.add(r.paidFemale); pT = pT.add(r.getPaidTotal());
            }
            w.printf("合計,,%d,%d,%d,%d,%d,%d%n", mTot, fTot, totTot, pM.longValue(), pF.longValue(), pT.longValue());
        }
    }

    @GetMapping("/{code}")
    public String detail(@PathVariable String code,
                         @RequestParam(name = "ym", required = false) String ymParam,
                         Model model) {
        // Cap the path variable so an attacker can't push 100KB into a DB SELECT or
        // amplify hash bucket lookups. AD_CODE.CODE is VARCHAR(64); anything longer
        // can't possibly match an existing row.
        if (code == null || code.length() > 64) return "ad-code/external-notfound";
        Optional<AdCode> opt = service.findByCode(code);
        if (!opt.isPresent()) return "ad-code/external-notfound";
        AdCode ad = opt.get();
        YearMonth target = parseYm(ymParam).orElse(YearMonth.now());
        model.addAttribute("adCode", ad);
        model.addAttribute("daily", service.dailyGenderStats(ad.getCode(), target));
        model.addAttribute("targetMonth", target);
        model.addAttribute("prevMonth", target.minusMonths(1));
        model.addAttribute("nextMonth", target.plusMonths(1));
        // Pass through the group slug so the "back to list" link preserves agency scope.
        model.addAttribute("groupSlug", AdCodeService.slugify(ad.getName()));
        model.addAttribute("groupName", ad.getName());
        return "ad-code/external-detail";
    }

    @GetMapping("/{code}/")
    public String detailTrailingSlash(@PathVariable String code,
                                      @RequestParam(name = "ym", required = false) String ymParam,
                                      Model model) {
        return detail(code, ymParam, model);
    }

    private static Optional<YearMonth> parseYm(String raw) {
        if (raw == null || raw.trim().isEmpty()) return Optional.empty();
        try { return Optional.of(YearMonth.parse(raw.trim())); }
        catch (Exception e) { return Optional.empty(); }
    }

    private static Set<String> parseCodeFilter(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        Set<String> out = new HashSet<>();
        for (String token : raw.split("[\\r\\n,]+")) {
            String t = token.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * CSV cell escape with formula-injection (CWE-1236) protection.
     *
     * Excel / LibreOffice / Google Sheets treat a cell starting with =, +, -, @ or TAB as a
     * formula on open. An admin who creates an ad_code with name "=cmd|'/c calc'!A1" would
     * trigger code execution on whoever downloads this CSV. We prefix any such cell with a
     * single-quote (the documented Excel "this is text, not a formula" sentinel) and quote
     * the field so the prefix isn't stripped.
     */
    private static String csv(String s) {
        if (s == null) return "";
        boolean leadsAsFormula = !s.isEmpty()
                && (s.charAt(0) == '=' || s.charAt(0) == '+' || s.charAt(0) == '-'
                 || s.charAt(0) == '@' || s.charAt(0) == '\t');
        boolean needsQuote = leadsAsFormula
                || s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r");
        if (!needsQuote) return s;
        String body = (leadsAsFormula ? "'" : "") + s.replace("\"", "\"\"");
        return "\"" + body + "\"";
    }
}
