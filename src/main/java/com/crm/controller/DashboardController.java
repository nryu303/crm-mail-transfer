package com.crm.controller;

import com.crm.entity.CrmUser;
import com.crm.repository.CrmUserRepository;
import com.crm.service.DashboardService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/manager")
public class DashboardController {

    private final DashboardService dashboardService;
    private final CrmUserRepository userRepository;
    private final ObjectMapper objectMapper;

    public DashboardController(DashboardService dashboardService,
                               CrmUserRepository userRepository,
                               ObjectMapper objectMapper) {
        this.dashboardService = dashboardService;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    @GetMapping({"", "/", "/dashboard"})
    public String dashboard(@RequestParam(name = "nyukinMonth", required = false) String nyukinMonth,
                            @RequestParam(name = "nyukinDate", required = false)
                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate nyukinDate,
                            @RequestParam(name = "sendDate", required = false)
                            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate sendDate,
                            @RequestParam(name = "sendScope", required = false, defaultValue = "hour") String sendScope,
                            @RequestParam(name = "sendMonth", required = false) String sendMonth,
                            @RequestParam(name = "sendYear", required = false) Integer sendYear,
                            Model model) {
        YearMonth ym = null;
        if (nyukinMonth != null && !nyukinMonth.isEmpty()) {
            try {
                ym = YearMonth.parse(nyukinMonth);
            } catch (DateTimeParseException ignore) {
                ym = null;
            }
        }
        DashboardService.Snapshot stats = dashboardService.snapshot(ym, nyukinDate, sendDate);
        model.addAttribute("stats", stats);
        String detailJson;
        try {
            detailJson = objectMapper.writeValueAsString(stats.getNyukinDetailByUserId());
        } catch (JsonProcessingException e) {
            detailJson = "{}";
        }
        model.addAttribute("nyukinDetailJson", detailJson);

        // ---- Send chart series. The operator can flip between 時間別 / 日別 / 月別 via
        //      ?sendScope=hour|day|month. Hourly uses stats.hourlySends (the existing
        //      24-bucket view for the selected sendDate). Day/Month re-query the repo.
        java.util.List<DashboardService.HourlySend> buckets;
        if ("day".equalsIgnoreCase(sendScope)) {
            YearMonth m = parseYearMonthOrNull(sendMonth);
            if (m == null) m = YearMonth.now();
            buckets = dashboardService.dailyBuckets(m.atDay(1), m.atEndOfMonth());
            model.addAttribute("sendMonthValue",
                    String.format("%04d-%02d", m.getYear(), m.getMonthValue()));
        } else if ("month".equalsIgnoreCase(sendScope)) {
            int y = sendYear != null ? sendYear : LocalDate.now().getYear();
            buckets = dashboardService.monthlyBuckets(YearMonth.of(y, 1), YearMonth.of(y, 12));
            model.addAttribute("sendYearValue", y);
        } else {
            sendScope = "hour";
            buckets = stats.getHourlySends();
        }
        model.addAttribute("sendScope", sendScope);
        model.addAttribute("sendBuckets", buckets);
        long sendTotal = 0, ngTotal = 0;
        Map<String, Object> hourlyChart = new HashMap<>();
        List<String> labels = new java.util.ArrayList<>();
        List<Long> sentSeries = new java.util.ArrayList<>();
        List<Long> ngSeries = new java.util.ArrayList<>();
        if (buckets != null) {
            for (DashboardService.HourlySend h : buckets) {
                labels.add(h.getLabel());
                sentSeries.add(h.getSent());
                ngSeries.add(h.getNg());
                sendTotal += h.getSent();
                ngTotal   += h.getNg();
            }
        }
        hourlyChart.put("labels", labels);
        hourlyChart.put("sent", sentSeries);
        hourlyChart.put("ng", ngSeries);
        String hourlyJson;
        try { hourlyJson = objectMapper.writeValueAsString(hourlyChart); }
        catch (JsonProcessingException e) { hourlyJson = "{\"labels\":[],\"sent\":[],\"ng\":[]}"; }
        model.addAttribute("hourlyChartJson", hourlyJson);
        // Totals adjust to the active scope so the cards above the chart stay in sync.
        if (!"hour".equals(sendScope)) {
            // Override the day-only totals from stats with the day/month aggregated ones.
            model.addAttribute("sendTotalForScope", sendTotal);
            model.addAttribute("ngTotalForScope", ngTotal);
        } else {
            model.addAttribute("sendTotalForScope", stats.getTotalSend());
            model.addAttribute("ngTotalForScope", stats.getTotalNg());
        }

        // userId → display label for the bottom "直近のメッセージ" table
        Map<Long, String> userLabels = new HashMap<>();
        if (stats.getRecentMessages() != null && !stats.getRecentMessages().isEmpty()) {
            List<Long> ids = stats.getRecentMessages().stream()
                    .map(m -> m.getUserId()).distinct().collect(Collectors.toList());
            for (CrmUser u : userRepository.findAllById(ids)) {
                String label = (u.getDisplayName() != null && !u.getDisplayName().isEmpty())
                        ? u.getDisplayName() : u.getEmail();
                userLabels.put(u.getId(), label);
            }
        }
        model.addAttribute("userLabels", userLabels);
        return "dashboard";
    }

    /** Lenient yyyy-MM parser used by the dashboard scope-switcher. Returns null on bad input. */
    private static YearMonth parseYearMonthOrNull(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return YearMonth.parse(s); }
        catch (DateTimeParseException e) { return null; }
    }
}
