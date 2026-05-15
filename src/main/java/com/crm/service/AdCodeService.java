package com.crm.service;

import com.crm.entity.AdCode;
import com.crm.repository.AdCodeRepository;
import com.crm.repository.CrmUserRepository;
import com.crm.repository.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Manage advertising / agency codes plus the per-code aggregate stats that drive both
 * the internal admin list and the external partner dashboard.
 *
 * URL contract for the external view: {@code /media/index/{code}/{access_token}}.
 * Both segments are random URL-safe strings generated at row creation; if either is
 * wrong the dashboard returns 404.
 */
@Service
public class AdCodeService {

    private static final char[] URL_ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();
    private static final int CODE_LEN = 12;
    private static final int TOKEN_LEN = 12;
    private static final int MAX_GENERATE_ATTEMPTS = 8;

    private final AdCodeRepository repository;
    private final CrmUserRepository userRepository;
    private final PaymentRepository paymentRepository;
    private final AdGroupCredentialService groupCredentialService;
    private final SecureRandom random = new SecureRandom();

    public AdCodeService(AdCodeRepository repository,
                         CrmUserRepository userRepository,
                         PaymentRepository paymentRepository,
                         AdGroupCredentialService groupCredentialService) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.paymentRepository = paymentRepository;
        this.groupCredentialService = groupCredentialService;
    }

    public List<AdCode> listAll() { return repository.findAllByOrderByCreatedAtDesc(); }

    /** Filtered list. {@code q} matches against name / code / memo (case-insensitive contains). */
    public List<AdCode> search(String q) {
        List<AdCode> all = listAll();
        if (q == null || q.trim().isEmpty()) return all;
        final String needle = q.trim().toLowerCase();
        List<AdCode> out = new ArrayList<>();
        for (AdCode a : all) {
            if (matches(a.getName(), needle) || matches(a.getCode(), needle) || matches(a.getMemo(), needle)) {
                out.add(a);
            }
        }
        return out;
    }

    private static boolean matches(String s, String needleLower) {
        return s != null && s.toLowerCase().contains(needleLower);
    }

    public Optional<AdCode> findById(Long id) { return repository.findById(id); }

    public Optional<AdCode> findByCode(String code) {
        return code == null ? Optional.empty() : repository.findByCode(code);
    }

    /** Look up the code AND verify the access_token. Used by the external dashboard. */
    public Optional<AdCode> findForExternalView(String code, String accessToken) {
        if (code == null || accessToken == null) return Optional.empty();
        return repository.findByCode(code)
                .filter(a -> Boolean.TRUE.equals(a.getIsActive()))
                .filter(a -> accessToken.equals(a.getAccessToken()));
    }

    @Transactional
    public AdCode create(String name, String memo) { return create(name, null, memo); }

    @Transactional
    public AdCode create(String name, String manualCode, String memo) {
        AdCode a = new AdCode();
        a.setName(name == null ? "" : name.trim());
        a.setMemo(memo);
        if (manualCode != null && !manualCode.trim().isEmpty()) {
            String c = manualCode.trim();
            if (!c.matches("[A-Za-z0-9_\\-]{1,64}")) {
                throw new IllegalArgumentException(
                        "広告コードは半角英数字とアンダースコア・ハイフンのみ、64文字以内で指定してください");
            }
            if (repository.existsByCode(c)) {
                throw new DuplicateCodeException(c);
            }
            a.setCode(c);
        } else {
            a.setCode(generateUniqueCode());
        }
        // accessToken column is retained for backward-compat; current /media/** auth is per-group
        // Basic Auth so this value is unused. Set to a random string anyway.
        a.setAccessToken(randomString(TOKEN_LEN));
        a.setIsActive(Boolean.TRUE);
        AdCode saved = repository.save(a);
        // Auto-create per-group Basic Auth credentials on first AD_CODE in this group.
        // Idempotent — a second code with the same name re-uses the same credentials.
        groupCredentialService.ensureFor(saved.getName());
        return saved;
    }

    public static class DuplicateCodeException extends RuntimeException {
        public DuplicateCodeException(String code) { super(code); }
    }

    @Transactional
    public Optional<AdCode> update(Long id, String name, String memo, Boolean isActive) {
        return repository.findById(id).map(a -> {
            if (name != null) a.setName(name.trim());
            a.setMemo(memo);
            if (isActive != null) a.setIsActive(isActive);
            return repository.save(a);
        });
    }

    /** Issue a fresh access_token (e.g. when the partner's URL has leaked). */
    @Transactional
    public Optional<AdCode> rotateToken(Long id) {
        return repository.findById(id).map(a -> {
            a.setAccessToken(randomString(TOKEN_LEN));
            return repository.save(a);
        });
    }

    @Transactional
    public boolean delete(Long id) {
        AdCode existing = repository.findById(id).orElse(null);
        if (existing == null) return false;
        userRepository.clearAdCodeForCode(existing.getCode());
        String groupName = existing.getName();
        repository.deleteById(id);
        // If this was the last code in the group, clean up the group's Basic Auth row too —
        // otherwise the orphaned credentials sit forever and the group key recycles into a
        // new ad code that would inherit unexpected creds.
        if (groupName != null && repository.findAll().stream().noneMatch(a -> groupName.equals(a.getName()))) {
            groupCredentialService.deleteByGroupName(groupName);
        }
        return true;
    }

    /**
     * Aggregate stats for the admin list — one row per ad-code with totals + M/F + payment split.
     * {@code q} filters by name / code / memo (case-insensitive contains).
     * {@code groupName} restricts to a single group when set.
     */
    public List<CodeSummary> listWithSummaries(String q, String groupName) {
        List<AdCode> filtered = search(q);
        if (groupName != null && !groupName.trim().isEmpty()) {
            String g = groupName.trim();
            List<AdCode> grouped = new ArrayList<>();
            for (AdCode a : filtered) {
                if (g.equals(a.getName())) grouped.add(a);
            }
            filtered = grouped;
        }
        // Build cumulative-totals view from the all-time signup/payment queries (no time bound).
        List<CodeSummary> result = new ArrayList<>(filtered.size());
        // Pre-fetch M/F splits within an open-ended window (year 1970 → year 9999) so the admin
        // sees lifetime totals; the agency dashboard does month-bound aggregations separately.
        java.time.LocalDateTime allStart = java.time.LocalDateTime.of(1970, 1, 1, 0, 0);
        java.time.LocalDateTime allEnd   = java.time.LocalDateTime.of(9999, 12, 31, 23, 59, 59);
        java.util.Map<String, long[]> signupsByCode = new java.util.HashMap<>();   // [M, F, other]
        java.util.Map<String, java.math.BigDecimal[]> paidByCode = new java.util.HashMap<>(); // [M, F, other]
        for (Object[] r : userRepository.countByAdCodeAndGenderBetween(allStart, allEnd)) {
            String code = (String) r[0];
            String gender = (String) r[1];
            long n = ((Number) r[2]).longValue();
            long[] arr = signupsByCode.computeIfAbsent(code, k -> new long[3]);
            if ("M".equalsIgnoreCase(gender)) arr[0] += n;
            else if ("F".equalsIgnoreCase(gender)) arr[1] += n;
            else arr[2] += n;
        }
        for (Object[] r : paymentRepository.sumByAdCodeAndGenderBetween(allStart, allEnd)) {
            String code = (String) r[0];
            String gender = (String) r[1];
            java.math.BigDecimal sum = (java.math.BigDecimal) r[2];
            if (sum == null) sum = java.math.BigDecimal.ZERO;
            java.math.BigDecimal[] arr = paidByCode.computeIfAbsent(code,
                    k -> new java.math.BigDecimal[]{java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO});
            if ("M".equalsIgnoreCase(gender)) arr[0] = arr[0].add(sum);
            else if ("F".equalsIgnoreCase(gender)) arr[1] = arr[1].add(sum);
            else arr[2] = arr[2].add(sum);
        }
        for (AdCode a : filtered) {
            long[] s = signupsByCode.getOrDefault(a.getCode(), new long[3]);
            java.math.BigDecimal[] p = paidByCode.getOrDefault(a.getCode(),
                    new java.math.BigDecimal[]{java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO});
            result.add(new CodeSummary(a, s[0], s[1], s[2], p[0], p[1], p[2]));
        }
        return result;
    }

    /** Backward-compat overloads. */
    public List<CodeSummary> listWithSummaries(String q) { return listWithSummaries(q, null); }
    public List<CodeSummary> listWithSummaries() { return listWithSummaries(null, null); }

    /**
     * Per-day stats for a given month. Used by the external dashboard.
     * Returns 28-31 entries (one per day in the month), all with non-null counts.
     */
    public List<DailyStat> dailyStats(String code, YearMonth month) {
        LocalDateTime start = month.atDay(1).atStartOfDay();
        LocalDateTime end = month.atEndOfMonth().atTime(23, 59, 59);
        List<Object[]> userRows = userRepository.countByAdCodeGroupedByDay(code, start, end);
        List<Object[]> payRows = paymentRepository.sumByAdCodeGroupedByDay(code, start, end);

        java.util.Map<String, Long> usersByDay = new java.util.HashMap<>();
        for (Object[] r : userRows) usersByDay.put(String.valueOf(r[0]), ((Number) r[1]).longValue());
        java.util.Map<String, java.math.BigDecimal> paidByDay = new java.util.HashMap<>();
        for (Object[] r : payRows) paidByDay.put(String.valueOf(r[0]),
                r[1] == null ? java.math.BigDecimal.ZERO : (java.math.BigDecimal) r[1]);

        List<DailyStat> out = new ArrayList<>();
        for (int d = 1; d <= month.lengthOfMonth(); d++) {
            LocalDate date = month.atDay(d);
            String key = date.format(DateTimeFormatter.ISO_LOCAL_DATE);
            long signups = usersByDay.getOrDefault(key, 0L);
            java.math.BigDecimal paid = paidByDay.getOrDefault(key, java.math.BigDecimal.ZERO);
            out.add(new DailyStat(date, signups, paid));
        }
        return out;
    }

    /**
     * Per-month stats for a given year (12 entries, Jan–Dec). Used by the external dashboard.
     */
    public List<MonthlyStat> monthlyStats(String code, int year) {
        LocalDateTime start = LocalDate.of(year, 1, 1).atStartOfDay();
        LocalDateTime end = LocalDate.of(year, 12, 31).atTime(23, 59, 59);
        List<Object[]> userRows = userRepository.countByAdCodeGroupedByMonth(code, start, end);
        List<Object[]> payRows = paymentRepository.sumByAdCodeGroupedByMonth(code, start, end);

        java.util.Map<String, Long> usersByMonth = new java.util.HashMap<>();
        for (Object[] r : userRows) usersByMonth.put(String.valueOf(r[0]), ((Number) r[1]).longValue());
        java.util.Map<String, java.math.BigDecimal> paidByMonth = new java.util.HashMap<>();
        for (Object[] r : payRows) paidByMonth.put(String.valueOf(r[0]),
                r[1] == null ? java.math.BigDecimal.ZERO : (java.math.BigDecimal) r[1]);

        List<MonthlyStat> out = new ArrayList<>();
        for (int m = 1; m <= 12; m++) {
            String key = String.format("%04d-%02d", year, m);
            long signups = usersByMonth.getOrDefault(key, 0L);
            java.math.BigDecimal paid = paidByMonth.getOrDefault(key, java.math.BigDecimal.ZERO);
            out.add(new MonthlyStat(year, m, signups, paid));
        }
        return out;
    }

    private String generateUniqueCode() {
        for (int i = 0; i < MAX_GENERATE_ATTEMPTS; i++) {
            String candidate = randomString(CODE_LEN);
            if (!repository.existsByCode(candidate)) return candidate;
        }
        throw new IllegalStateException("could not generate unique ad code after "
                + MAX_GENERATE_ATTEMPTS + " attempts");
    }

    private String randomString(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(URL_ALPHABET[random.nextInt(URL_ALPHABET.length)]);
        return sb.toString();
    }

    public static class CodeSummary {
        public final AdCode adCode;
        public final long signupMale, signupFemale, signupOther;
        public final java.math.BigDecimal paidMale, paidFemale, paidOther;
        public CodeSummary(AdCode adCode,
                           long signupMale, long signupFemale, long signupOther,
                           java.math.BigDecimal paidMale, java.math.BigDecimal paidFemale,
                           java.math.BigDecimal paidOther) {
            this.adCode = adCode;
            this.signupMale = signupMale;
            this.signupFemale = signupFemale;
            this.signupOther = signupOther;
            this.paidMale = paidMale;
            this.paidFemale = paidFemale;
            this.paidOther = paidOther;
        }
        public AdCode getAdCode() { return adCode; }
        public long getSignupMale() { return signupMale; }
        public long getSignupFemale() { return signupFemale; }
        public long getSignupCount() { return signupMale + signupFemale + signupOther; }
        public java.math.BigDecimal getPaidMale() { return paidMale; }
        public java.math.BigDecimal getPaidFemale() { return paidFemale; }
        public java.math.BigDecimal getPaidTotal() { return paidMale.add(paidFemale).add(paidOther); }
    }

    public static class DailyStat {
        public final LocalDate date;
        public final long signupCount;
        public final java.math.BigDecimal paidTotal;
        public DailyStat(LocalDate date, long signupCount, java.math.BigDecimal paidTotal) {
            this.date = date;
            this.signupCount = signupCount;
            this.paidTotal = paidTotal;
        }
        public LocalDate getDate() { return date; }
        public long getSignupCount() { return signupCount; }
        public java.math.BigDecimal getPaidTotal() { return paidTotal; }
    }

    /**
     * Per-ad-code monthly summary with M/F/total split — drives the agency dashboard list view.
     * Returns one row per ad_code, restricted to a code list and/or a group name when given.
     */
    public List<AgencyRow> agencySummary(YearMonth month,
                                          java.util.Set<String> codeFilter,
                                          String groupName) {
        LocalDateTime start = month.atDay(1).atStartOfDay();
        LocalDateTime end = month.atEndOfMonth().atTime(23, 59, 59);

        java.util.Map<String, AgencyRow> byCode = new java.util.LinkedHashMap<>();
        // Seed from the AD_CODE table so codes with zero activity still appear.
        for (AdCode a : repository.findAllByOrderByCreatedAtDesc()) {
            if (groupName != null && !groupName.equals(a.getName())) continue;
            if (codeFilter != null && !codeFilter.contains(a.getCode())) continue;
            byCode.put(a.getCode(), new AgencyRow(a.getCode(), a.getName()));
        }

        for (Object[] r : userRepository.countByAdCodeAndGenderBetween(start, end)) {
            String code = (String) r[0];
            String gender = (String) r[1];
            long count = ((Number) r[2]).longValue();
            AgencyRow row = byCode.get(code);
            if (row == null) continue;
            if ("M".equalsIgnoreCase(gender)) row.signupMale += count;
            else if ("F".equalsIgnoreCase(gender)) row.signupFemale += count;
            else row.signupOther += count;
        }
        for (Object[] r : paymentRepository.sumByAdCodeAndGenderBetween(start, end)) {
            String code = (String) r[0];
            String gender = (String) r[1];
            java.math.BigDecimal sum = (java.math.BigDecimal) r[2];
            AgencyRow row = byCode.get(code);
            if (row == null) continue;
            if (sum == null) sum = java.math.BigDecimal.ZERO;
            if ("M".equalsIgnoreCase(gender)) row.paidMale = row.paidMale.add(sum);
            else if ("F".equalsIgnoreCase(gender)) row.paidFemale = row.paidFemale.add(sum);
            else row.paidOther = row.paidOther.add(sum);
        }

        return new ArrayList<>(byCode.values());
    }

    /** Backward-compat overload (no group filter). */
    public List<AgencyRow> agencySummary(YearMonth month, java.util.Set<String> codeFilter) {
        return agencySummary(month, codeFilter, null);
    }

    /** Distinct group names (= AD_CODE.name) for the admin search dropdown. */
    public java.util.List<String> listGroupNames() {
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (AdCode a : repository.findAllByOrderByCreatedAtDesc()) {
            if (a.getName() != null && !a.getName().trim().isEmpty()) seen.add(a.getName().trim());
        }
        return new ArrayList<>(seen);
    }

    /** Group-level summary — one row per group with totals across all codes in that group. */
    public List<GroupSummary> listGroupSummaries(String q) {
        // Build per-code summaries first then fold into groups.
        List<CodeSummary> codes = listWithSummaries(q, null);
        java.util.Map<String, GroupSummary> byGroup = new java.util.LinkedHashMap<>();
        for (CodeSummary c : codes) {
            String groupName = c.adCode.getName() == null ? "" : c.adCode.getName();
            GroupSummary g = byGroup.computeIfAbsent(groupName,
                    k -> new GroupSummary(k, slugify(k)));
            g.codeCount++;
            g.signupMale   += c.signupMale;
            g.signupFemale += c.signupFemale;
            g.signupOther  += c.signupOther;
            g.paidMale   = g.paidMale.add(c.paidMale);
            g.paidFemale = g.paidFemale.add(c.paidFemale);
            g.paidOther  = g.paidOther.add(c.paidOther);
            if (g.firstCreatedAt == null
                    || (c.adCode.getCreatedAt() != null && c.adCode.getCreatedAt().isBefore(g.firstCreatedAt))) {
                g.firstCreatedAt = c.adCode.getCreatedAt();
            }
        }
        return new ArrayList<>(byGroup.values());
    }

    public static class GroupSummary {
        public final String name;
        public final String slug;
        public int codeCount;
        public long signupMale, signupFemale, signupOther;
        public java.math.BigDecimal paidMale = java.math.BigDecimal.ZERO;
        public java.math.BigDecimal paidFemale = java.math.BigDecimal.ZERO;
        public java.math.BigDecimal paidOther = java.math.BigDecimal.ZERO;
        public LocalDateTime firstCreatedAt;
        public GroupSummary(String name, String slug) { this.name = name; this.slug = slug; }
        public String getName() { return name; }
        public String getSlug() { return slug; }
        public int getCodeCount() { return codeCount; }
        public long getSignupMale() { return signupMale; }
        public long getSignupFemale() { return signupFemale; }
        public long getSignupTotal() { return signupMale + signupFemale + signupOther; }
        public java.math.BigDecimal getPaidMale() { return paidMale; }
        public java.math.BigDecimal getPaidFemale() { return paidFemale; }
        public java.math.BigDecimal getPaidTotal() { return paidMale.add(paidFemale).add(paidOther); }
        public LocalDateTime getFirstCreatedAt() { return firstCreatedAt; }
    }

    /** URL-safe slug for a group name. Reversible enough for our 1:1 lookup needs. */
    public static String slugify(String groupName) {
        if (groupName == null) return "";
        // Keep ASCII alnum and dash; encode everything else as %XX (URL-encoded).
        try {
            return java.net.URLEncoder.encode(groupName, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return groupName;
        }
    }

    public static String unslugify(String slug) {
        if (slug == null) return null;
        try {
            return java.net.URLDecoder.decode(slug, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return slug;
        }
    }

    /** Drill-down — daily M/F split for one ad_code in one month. */
    public List<DailyGenderStat> dailyGenderStats(String code, YearMonth month) {
        LocalDateTime start = month.atDay(1).atStartOfDay();
        LocalDateTime end = month.atEndOfMonth().atTime(23, 59, 59);

        java.util.Map<String, DailyGenderStat> byDay = new java.util.HashMap<>();
        for (int d = 1; d <= month.lengthOfMonth(); d++) {
            LocalDate date = month.atDay(d);
            byDay.put(date.format(DateTimeFormatter.ISO_LOCAL_DATE), new DailyGenderStat(date));
        }

        for (Object[] r : userRepository.countByDayAndGenderForCode(code, start, end)) {
            DailyGenderStat row = byDay.get(String.valueOf(r[0]));
            if (row == null) continue;
            String gender = (String) r[1];
            long count = ((Number) r[2]).longValue();
            if ("M".equalsIgnoreCase(gender)) row.signupMale += count;
            else if ("F".equalsIgnoreCase(gender)) row.signupFemale += count;
            else row.signupOther += count;
        }
        for (Object[] r : paymentRepository.sumByDayAndGenderForCode(code, start, end)) {
            DailyGenderStat row = byDay.get(String.valueOf(r[0]));
            if (row == null) continue;
            String gender = (String) r[1];
            java.math.BigDecimal sum = (java.math.BigDecimal) r[2];
            if (sum == null) sum = java.math.BigDecimal.ZERO;
            if ("M".equalsIgnoreCase(gender)) row.paidMale = row.paidMale.add(sum);
            else if ("F".equalsIgnoreCase(gender)) row.paidFemale = row.paidFemale.add(sum);
            else row.paidOther = row.paidOther.add(sum);
        }

        List<DailyGenderStat> out = new ArrayList<>(byDay.values());
        out.sort(java.util.Comparator.comparing(DailyGenderStat::getDate));
        return out;
    }

    public static class AgencyRow {
        public final String code;
        public final String name;
        public long signupMale, signupFemale, signupOther;
        public java.math.BigDecimal paidMale = java.math.BigDecimal.ZERO;
        public java.math.BigDecimal paidFemale = java.math.BigDecimal.ZERO;
        public java.math.BigDecimal paidOther = java.math.BigDecimal.ZERO;
        public AgencyRow(String code, String name) { this.code = code; this.name = name; }
        public String getCode() { return code; }
        public String getName() { return name; }
        public long getSignupMale() { return signupMale; }
        public long getSignupFemale() { return signupFemale; }
        public long getSignupTotal() { return signupMale + signupFemale + signupOther; }
        public java.math.BigDecimal getPaidMale() { return paidMale; }
        public java.math.BigDecimal getPaidFemale() { return paidFemale; }
        public java.math.BigDecimal getPaidTotal() { return paidMale.add(paidFemale).add(paidOther); }
    }

    public static class DailyGenderStat {
        public final LocalDate date;
        public long signupMale, signupFemale, signupOther;
        public java.math.BigDecimal paidMale = java.math.BigDecimal.ZERO;
        public java.math.BigDecimal paidFemale = java.math.BigDecimal.ZERO;
        public java.math.BigDecimal paidOther = java.math.BigDecimal.ZERO;
        public DailyGenderStat(LocalDate date) { this.date = date; }
        public LocalDate getDate() { return date; }
        public long getSignupMale() { return signupMale; }
        public long getSignupFemale() { return signupFemale; }
        public long getSignupTotal() { return signupMale + signupFemale + signupOther; }
        public java.math.BigDecimal getPaidMale() { return paidMale; }
        public java.math.BigDecimal getPaidFemale() { return paidFemale; }
        public java.math.BigDecimal getPaidTotal() { return paidMale.add(paidFemale).add(paidOther); }
    }

    public static class MonthlyStat {
        public final int year;
        public final int month;
        public final long signupCount;
        public final java.math.BigDecimal paidTotal;
        public MonthlyStat(int year, int month, long signupCount, java.math.BigDecimal paidTotal) {
            this.year = year;
            this.month = month;
            this.signupCount = signupCount;
            this.paidTotal = paidTotal;
        }
        public int getYear() { return year; }
        public int getMonth() { return month; }
        public long getSignupCount() { return signupCount; }
        public java.math.BigDecimal getPaidTotal() { return paidTotal; }
    }
}
