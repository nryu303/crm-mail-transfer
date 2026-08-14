package com.crm.service;

import com.crm.entity.CrmUser;
import com.crm.entity.Message;
import com.crm.entity.Payment;
import com.crm.repository.CarrierUserBindingRepository;
import com.crm.repository.CrmUserRepository;
import com.crm.repository.MessageRepository;
import com.crm.repository.PaymentRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DashboardService {

    private final MessageRepository messageRepository;
    private final PaymentRepository paymentRepository;
    private final CarrierUserBindingRepository bindingRepository;
    private final CrmUserRepository userRepository;

    public DashboardService(MessageRepository messageRepository,
                            PaymentRepository paymentRepository,
                            CarrierUserBindingRepository bindingRepository,
                            CrmUserRepository userRepository) {
        this.messageRepository = messageRepository;
        this.paymentRepository = paymentRepository;
        this.bindingRepository = bindingRepository;
        this.userRepository = userRepository;
    }

    /**
     * Build the dashboard snapshot.
     *
     * @param nyukinMonth optional YearMonth — if present, 入金レポート covers the full month
     * @param nyukinDate  optional LocalDate — if present (and month is absent), 入金レポート covers that single day
     * @param sendDate    Date used for the hourly 送信/NG table (defaults to today)
     *
     * Default (both nyukin params null): 入金レポート shows *today only*.
     */
    public Snapshot snapshot(YearMonth nyukinMonth, LocalDate nyukinDate, LocalDate sendDate) {
        Snapshot s = new Snapshot();
        LocalDate today = LocalDate.now();
        LocalDate day = sendDate != null ? sendDate : today;
        s.sendDate = day;

        // 入金 window. Month wins if explicitly provided; otherwise fall back to the selected day.
        LocalDateTime nyukinStart;
        LocalDateTime nyukinEnd;
        if (nyukinMonth != null) {
            s.nyukinScope = "month";
            s.nyukinMonth = nyukinMonth;
            s.nyukinDate = null;
            nyukinStart = nyukinMonth.atDay(1).atStartOfDay();
            nyukinEnd = nyukinMonth.plusMonths(1).atDay(1).atStartOfDay();
        } else {
            LocalDate nd = nyukinDate != null ? nyukinDate : today;
            s.nyukinScope = "day";
            s.nyukinMonth = null;
            s.nyukinDate = nd;
            nyukinStart = nd.atStartOfDay();
            nyukinEnd = nyukinStart.plusDays(1);
        }

        s.unboundPoolCount = bindingRepository.countActivePoolsWithNoBindings();

        // ---- 入金レポート (per-user totals for selected window) ----
        // Build the per-user nyukin map first so we can show ONLY users with payments in the window.
        Map<Long, BigDecimal> nyukinByUser = new HashMap<>();
        for (Object[] row : paymentRepository.sumPaidByUserBetween(nyukinStart, nyukinEnd)) {
            Long uid = (Long) row[0];
            BigDecimal sum = (BigDecimal) row[1];
            if (sum == null || sum.signum() <= 0) continue; // skip zero/negative totals
            nyukinByUser.put(uid, sum);
        }
        s.nyukinByUserId = nyukinByUser;

        // Filter the user list to those who actually had paid payments in this window,
        // preserving the natural ID order. Suspended users are still shown if they paid.
        List<CrmUser> usersInWindow = new ArrayList<>();
        if (!nyukinByUser.isEmpty()) {
            for (CrmUser u : userRepository.findAllById(nyukinByUser.keySet())) {
                usersInWindow.add(u);
            }
            usersInWindow.sort(java.util.Comparator.comparing(CrmUser::getId));
        }
        s.users = usersInWindow;

        BigDecimal totalNyukin = BigDecimal.ZERO;
        for (BigDecimal v : nyukinByUser.values()) totalNyukin = totalNyukin.add(v);
        s.totalNyukin = totalNyukin;

        Map<Long, List<PaymentRow>> detailByUser = new LinkedHashMap<>();
        for (Payment p : paymentRepository.findPaidBetween(nyukinStart, nyukinEnd)) {
            detailByUser.computeIfAbsent(p.getUserId(), k -> new ArrayList<>())
                    .add(PaymentRow.from(p));
        }
        s.nyukinDetailByUserId = detailByUser;

        // ---- 時間別送信数 (hourly send / NG for selected day) ----
        List<HourlySend> hourly = new ArrayList<>(24);
        long totalSend = 0, totalNg = 0, totalSmsSent = 0;
        for (int h = 0; h < 24; h++) {
            LocalDateTime from = day.atTime(h, 0);
            LocalDateTime to = from.plusHours(1);
            long sent = messageRepository.countByDirectionBetween(Message.DIR_OUT, from, to);
            long ng = messageRepository.countByDirectionAndStatusBetween(
                    Message.DIR_OUT, Message.STATUS_FAILED, from, to);
            long smsSent = messageRepository.countByDirectionAndChannelBetween(
                    Message.DIR_OUT, Message.CHANNEL_SMS, from, to);
            HourlySend hb = new HourlySend();
            hb.label = String.format("%02d:00", h);
            hb.sent = sent;
            hb.ng = ng;
            hb.smsSent = smsSent;
            hourly.add(hb);
            totalSend += sent;
            totalNg += ng;
            totalSmsSent += smsSent;
        }
        s.hourlySends = hourly;
        s.totalSend = totalSend;
        s.totalNg = totalNg;
        s.totalSmsSent = totalSmsSent;

        // ---- Recent messages (latest 10) ----
        s.recentMessages = messageRepository.findAll(
                PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt"))).getContent();

        return s;
    }

    // ---- DTOs ----

    public static class HourlySend {
        public String label;
        /** All outbound (email + SMS + broadcast) — kept for backward-compat callers; the
         *  dashboard UI itself shows getEmailSent()/getSmsSent() as separate, non-summed series. */
        public long sent;
        public long ng;
        public long smsSent;
        public String getLabel() { return label; }
        public long getSent() { return sent; }
        public long getNg() { return ng; }
        public long getSmsSent() { return smsSent; }
        /** Non-SMS outbound (email replies + email broadcasts) — sent minus smsSent. */
        public long getEmailSent() { return sent - smsSent; }
    }

    /**
     * Daily aggregate for one calendar day in the [start, end] window (inclusive of both).
     * Used by the dashboard "日別" view. label = "MM-dd".
     */
    public List<HourlySend> dailyBuckets(LocalDate start, LocalDate end) {
        if (start == null) start = LocalDate.now().withDayOfMonth(1);
        if (end == null)   end   = start.withDayOfMonth(start.lengthOfMonth());
        if (end.isBefore(start)) end = start;
        List<HourlySend> out = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            LocalDateTime from = d.atStartOfDay();
            LocalDateTime to   = from.plusDays(1);
            HourlySend hb = new HourlySend();
            hb.label = String.format("%02d-%02d", d.getMonthValue(), d.getDayOfMonth());
            hb.sent = messageRepository.countByDirectionBetween(Message.DIR_OUT, from, to);
            hb.ng   = messageRepository.countByDirectionAndStatusBetween(
                    Message.DIR_OUT, Message.STATUS_FAILED, from, to);
            hb.smsSent = messageRepository.countByDirectionAndChannelBetween(
                    Message.DIR_OUT, Message.CHANNEL_SMS, from, to);
            out.add(hb);
        }
        return out;
    }

    /**
     * Monthly aggregate for each month in [startInclusive, endInclusive]. label = "yyyy-MM".
     * Used by the dashboard "月別" view.
     */
    public List<HourlySend> monthlyBuckets(YearMonth start, YearMonth end) {
        if (start == null) start = YearMonth.now().minusMonths(11);
        if (end == null)   end   = YearMonth.now();
        if (end.isBefore(start)) end = start;
        List<HourlySend> out = new ArrayList<>();
        for (YearMonth ym = start; !ym.isAfter(end); ym = ym.plusMonths(1)) {
            LocalDateTime from = ym.atDay(1).atStartOfDay();
            LocalDateTime to   = ym.plusMonths(1).atDay(1).atStartOfDay();
            HourlySend hb = new HourlySend();
            hb.label = String.format("%04d-%02d", ym.getYear(), ym.getMonthValue());
            hb.sent = messageRepository.countByDirectionBetween(Message.DIR_OUT, from, to);
            hb.ng   = messageRepository.countByDirectionAndStatusBetween(
                    Message.DIR_OUT, Message.STATUS_FAILED, from, to);
            hb.smsSent = messageRepository.countByDirectionAndChannelBetween(
                    Message.DIR_OUT, Message.CHANNEL_SMS, from, to);
            out.add(hb);
        }
        return out;
    }

    /** Flat view of a Payment row for the detail panel (avoids exposing the JPA entity to the view). */
    public static class PaymentRow {
        public Long id;
        public Long userId;
        public BigDecimal amount;
        public String paymentMethod;
        /** Pre-formatted "yyyy-MM-dd HH:mm" — keeps JSON serialization independent of Jackson JSR-310 config. */
        public String paidAt;

        private static final java.time.format.DateTimeFormatter FMT =
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

        public static PaymentRow from(Payment p) {
            PaymentRow r = new PaymentRow();
            r.id = p.getId();
            r.userId = p.getUserId();
            r.amount = p.getAmount();
            r.paymentMethod = p.getPaymentMethod();
            r.paidAt = p.getPaidAt() == null ? null : p.getPaidAt().format(FMT);
            return r;
        }

        public Long getId() { return id; }
        public Long getUserId() { return userId; }
        public BigDecimal getAmount() { return amount; }
        public String getPaymentMethod() { return paymentMethod; }
        public String getPaidAt() { return paidAt; }
    }

    public static class Snapshot {
        /** "day" or "month" — which scope the 入金 report is using. */
        public String nyukinScope;
        public YearMonth nyukinMonth;
        public LocalDate nyukinDate;
        public LocalDate sendDate;
        public long unboundPoolCount;

        public List<CrmUser> users;
        public Map<Long, BigDecimal> nyukinByUserId;
        public Map<Long, List<PaymentRow>> nyukinDetailByUserId;
        public BigDecimal totalNyukin = BigDecimal.ZERO;

        public List<HourlySend> hourlySends;
        public long totalSend;
        public long totalNg;
        public long totalSmsSent;

        public List<Message> recentMessages;

        public String getNyukinScope() { return nyukinScope; }
        public YearMonth getNyukinMonth() { return nyukinMonth; }
        public LocalDate getNyukinDate() { return nyukinDate; }
        public LocalDate getSendDate() { return sendDate; }
        public long getUnboundPoolCount() { return unboundPoolCount; }
        public List<CrmUser> getUsers() { return users; }
        public Map<Long, BigDecimal> getNyukinByUserId() { return nyukinByUserId; }
        public Map<Long, List<PaymentRow>> getNyukinDetailByUserId() { return nyukinDetailByUserId; }
        public BigDecimal getTotalNyukin() { return totalNyukin; }
        public List<HourlySend> getHourlySends() { return hourlySends; }
        public long getTotalSend() { return totalSend; }
        public long getTotalNg() { return totalNg; }
        public long getTotalSmsSent() { return totalSmsSent; }
        public List<Message> getRecentMessages() { return recentMessages; }

        /** For the <input type="month"> default. Empty when day scope is active. */
        public String getNyukinMonthValue() {
            return nyukinMonth == null ? "" : String.format("%04d-%02d",
                    nyukinMonth.getYear(), nyukinMonth.getMonthValue());
        }
    }
}
