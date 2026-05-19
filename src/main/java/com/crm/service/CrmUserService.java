package com.crm.service;

import com.crm.dto.CsvImportResult;
import com.crm.dto.UserForm;
import com.crm.dto.UserSearchForm;
import com.crm.entity.CrmUser;
import com.crm.repository.CrmUserRepository;
import com.crm.util.CredentialGenerator;
import com.crm.util.CsvUtil;
import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.CSVWriter;
import com.opencsv.exceptions.CsvValidationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.criteria.Predicate;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class CrmUserService {

    /**
     * Canonical carrier codes. Kept here as a shared constant for the CARRIER_ADDRESS_POOL
     * (each pool row still has a carrier code). The CRM_USER table no longer stores a
     * carrier code — users are identified by their email domain instead.
     */
    public static final List<String> CARRIER_CODES = Arrays.asList("softbank", "docomo", "au");

    /** Required columns the CSV header MUST include (in this order). */
    private static final String[] CSV_HEADER_REQUIRED =
            {"email", "display_name", "carrier_domain", "memo"};
    /** Full canonical header. {@code ad_code} (col 5) and {@code gender} (col 6) are optional
     *  for backwards-compat with CSVs created before those features shipped. */
    private static final String[] CSV_HEADER =
            {"email", "display_name", "carrier_domain", "memo", "ad_code", "gender"};

    // Retry limit for generating a unique loginId.
    private static final int LOGIN_ID_MAX_ATTEMPTS = 10;

    private final CrmUserRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final CarrierBindingService bindingService;

    /**
     * Live CSV-import progress counter. Reset to 0 at the start of each importCsv()
     * call, incremented per processed row. Read by the GET /manager/users/import/progress
     * endpoint so the frontend can poll and show "X / Y件 登録中..." during a long
     * import. Not transactional — informational only. Single global counter assumes
     * only one operator imports at a time (matches actual usage).
     */
    private final java.util.concurrent.atomic.AtomicLong importProgress =
            new java.util.concurrent.atomic.AtomicLong(0);
    private volatile boolean importRunning = false;

    public long getImportProgress() { return importProgress.get(); }
    public boolean isImportRunning() { return importRunning; }

    public CrmUserService(CrmUserRepository repository,
                          PasswordEncoder passwordEncoder,
                          CarrierBindingService bindingService) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.bindingService = bindingService;
    }

    public Page<CrmUser> search(UserSearchForm form) {
        // Default sort: most recent lastLoginAt at the top, nulls last. Fall back to createdAt
        // so brand-new users (lastLoginAt == null) still order by their registration time.
        Sort sort = Sort.by(
                new Sort.Order(Sort.Direction.DESC, "lastLoginAt", Sort.NullHandling.NULLS_LAST),
                new Sort.Order(Sort.Direction.DESC, "createdAt"));
        Pageable pageable = PageRequest.of(form.getPage(), form.getSize(), sort);
        return repository.findAll(buildSpecification(form), pageable);
    }

    public Optional<CrmUser> findById(Long id) {
        return repository.findById(id);
    }

    public List<CrmUser> findAllByIds(java.util.Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) return java.util.Collections.emptyList();
        return repository.findAllById(ids);
    }

    @Transactional
    public CreationResult create(UserForm form) {
        String email = form.getEmail() == null ? null : form.getEmail().trim();
        if (email != null && repository.existsByEmail(email)) {
            throw new DuplicateEmailException(email);
        }
        CrmUser u = new CrmUser();
        form.applyTo(u);
        u.setCarrierDomain(deriveCarrierDomainIfBlank(u.getCarrierDomain(), u.getEmail()));
        IssuedCredentials cred = issueFreshCredentials(u);
        CrmUser saved = repository.save(u);
        // auto-bind to an available pool address if carrier_domain is set
        if (saved.getCarrierDomain() != null) {
            bindingService.autoBind(saved);
        }
        return new CreationResult(saved, cred);
    }

    @Transactional
    public CrmUser update(Long id, UserForm form) {
        CrmUser u = repository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(id));
        String newEmail = form.getEmail() == null ? null : form.getEmail().trim();
        if (newEmail != null && !newEmail.equalsIgnoreCase(u.getEmail())
                && repository.existsByEmail(newEmail)) {
            throw new DuplicateEmailException(newEmail);
        }
        form.applyTo(u);
        u.setCarrierDomain(deriveCarrierDomainIfBlank(u.getCarrierDomain(), u.getEmail()));
        return repository.save(u);
    }

    /**
     * If {@code carrierDomain} is null/blank, derive it from the email's @-suffix.
     * "kahou6969@icloud.com" → "icloud.com". Used so both the CSV import and the
     * single-user form leave the carrier display populated even when the admin
     * didn't fill the field. The relay only routes a subset of these (real JP
     * carrier domains); non-routable values are still kept for display purposes.
     */
    /** Accept "M"/"F"/"男"/"男性"/"女"/"女性"/"male"/"female" and normalise to "M"/"F"/null. */
    private static String normalizeCsvGender(String s) {
        if (s == null) return null;
        String t = s.trim().toUpperCase();
        if (t.isEmpty()) return null;
        if (t.equals("M") || t.equals("MALE") || t.equals("男") || t.equals("男性")) return "M";
        if (t.equals("F") || t.equals("FEMALE") || t.equals("女") || t.equals("女性")) return "F";
        return null;
    }

    private static String deriveCarrierDomainIfBlank(String carrierDomain, String email) {
        if (carrierDomain != null && !carrierDomain.trim().isEmpty()) {
            return carrierDomain.trim();
        }
        if (email == null) return null;
        int at = email.indexOf('@');
        if (at < 0 || at >= email.length() - 1) return null;
        return email.substring(at + 1).trim().toLowerCase();
    }

    @Transactional
    public void delete(Long id) {
        repository.deleteById(id);
    }

    /**
     * Delete users in {@code folder} (null = '未設定'). When {@code limit} is null
     * or <= 0, every user in the folder is processed; otherwise only the first
     * {@code limit} users (sorted by ID) are processed, so the operator can run
     * a 15K-user reclassify in 1K/2K-sized batches. Each delete is its own short
     * transaction so JPA cascades fire correctly and a single failure doesn't
     * roll back the rest. Returns the number of users actually deleted.
     */
    /**
     * Resolve a folder filter (null = '未設定') to a list of user IDs, optionally capped to
     * the first {@code limit} (by id ASC). Shared by the user list "対象件数一斉送信" path:
     * the broadcast controller resolves the folder snapshot at click time and stashes the
     * IDs in the session so the broadcast form behaves identically to the row-selected case.
     */
    /**
     * folder → user-count map across CRM_USER. The empty-string key bucket carries the
     * count of users with FOLDER IS NULL. Used by the folder settings page to display the
     * current distribution and to surface "stranded" folder names (values present here but
     * no longer in the configured FolderSetting list).
     */
    public java.util.Map<String, Long> countByFolder() {
        java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
        for (Object[] row : repository.countGroupByFolder()) {
            String folder = row[0] == null ? "" : (String) row[0];
            long n = ((Number) row[1]).longValue();
            out.put(folder, n);
        }
        return out;
    }

    /**
     * Bulk-rename the folder value on every user currently in {@code oldFolder} so that
     * they show up under {@code newFolder} instead. Used by the stranded-folder rescue UI
     * on the folder settings page — when an admin renames a folder name in the configured
     * list, the per-user CRM_USER.FOLDER strings stay on the OLD value (5,001 users were
     * stranded on 2026-05-19 in folders "5000件切り分け" and "フォルダD" because of this).
     * Empty string maps to NULL on both sides so 未設定 ↔ named-folder migrations work too.
     */
    @org.springframework.transaction.annotation.Transactional
    public int renameFolderValue(String oldFolder, String newFolder) {
        String from = (oldFolder == null || oldFolder.isEmpty()) ? null : oldFolder;
        String to   = (newFolder == null || newFolder.isEmpty()) ? null : newFolder;
        return repository.bulkUpdateFolderByFolder(from, to);
    }

    public java.util.List<Long> findIdsInFolder(String folder, Integer limit) {
        java.util.List<Long> ids = (folder == null)
                ? repository.findIdsByFolderIsNull()
                : repository.findIdsByFolder(folder);
        if (limit != null && limit > 0 && ids.size() > limit) {
            return new java.util.ArrayList<>(ids.subList(0, limit));
        }
        return ids;
    }

    @org.springframework.transaction.annotation.Transactional(propagation =
            org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public int deleteAllInFolder(String folder, Integer limit) {
        java.util.List<Long> ids = (folder == null)
                ? repository.findIdsByFolderIsNull()
                : repository.findIdsByFolder(folder);
        if (limit != null && limit > 0 && ids.size() > limit) {
            ids = ids.subList(0, limit);
        }
        int n = 0;
        for (Long id : ids) {
            try { repository.deleteById(id); n++; } catch (Exception ignored) {}
            // Yield every 200 deletes so the dispatcher / web threads stay responsive.
            if (n % 200 == 0) {
                try { Thread.sleep(20); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        return n;
    }

    /**
     * Issue (or re-issue) credentials for an existing user.
     * - If loginId is missing, generates a new unique loginId.
     * - Always generates a new password.
     * Returns the plaintext password so it can be shown once to the admin.
     */
    @Transactional
    public IssuedCredentials resetCredentials(Long id) {
        CrmUser u = repository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(id));
        IssuedCredentials cred = issueFreshCredentials(u);
        repository.save(u);
        return cred;
    }

    private IssuedCredentials issueFreshCredentials(CrmUser u) {
        if (u.getLoginId() == null || u.getLoginId().isEmpty()) {
            u.setLoginId(generateUniqueLoginId());
        }
        String plain = CredentialGenerator.generatePassword();
        u.setLoginPassword(passwordEncoder.encode(plain));
        return new IssuedCredentials(u.getLoginId(), plain);
    }

    private String generateUniqueLoginId() {
        for (int attempt = 0; attempt < LOGIN_ID_MAX_ATTEMPTS; attempt++) {
            String candidate = CredentialGenerator.generateLoginId();
            if (!repository.existsByLoginId(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("could not generate unique login id after "
                + LOGIN_ID_MAX_ATTEMPTS + " attempts");
    }

    // DO NOT add @Transactional here. Same rationale as deleteAllInFolder above: a
    // 10K-row CSV import takes ~75 minutes (BCrypt-dominated). With a single wrapping
    // transaction, every row stays uncommitted/invisible to other DB connections (and
    // operators querying CRM_USER) until the WHOLE run finishes — making the import
    // look stuck for over an hour even though it is actually progressing. Each
    // repository.save() inside processRow() has its own short Spring Data transaction,
    // so progress becomes visible row-by-row. NOT_SUPPORTED makes this explicit and
    // suspends any enclosing tx if one ever gets introduced higher up the call chain.
    @org.springframework.transaction.annotation.Transactional(propagation =
            org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public CsvImportResult importCsv(InputStream in) throws IOException {
        // Reset the live progress counter so the frontend poller starts from 0.
        importProgress.set(0);
        importRunning = true;
        try {
        CsvImportResult result = new CsvImportResult();
        try (CSVReader reader = openReader(in)) {
            String[] header = reader.readNext();
            if (header == null) {
                result.addError(0, "CSVが空です");
                return result;
            }
            // Cap the column count so a malicious upload with millions of columns can't
            // allocate huge per-row arrays inside OpenCSV. Real headers fit easily in 32.
            if (header.length > 32) {
                result.addError(1, "CSVの列数が多すぎます (" + header.length + " 列、上限32)");
                return result;
            }
            if (!isExpectedHeader(header)) {
                result.addError(1, "ヘッダー行の列が想定と異なります (期待: " + String.join(",", CSV_HEADER) + ")");
                return result;
            }

            String[] row;
            int rowNum = 1;
            while ((row = reader.readNext()) != null) {
                rowNum++;
                result.incrementTotal();
                processRow(row, rowNum, result);
                importProgress.set(rowNum - 1);  // live counter for /import/progress poller
            }
        } catch (CsvValidationException e) {
            result.addError(0, "CSVの読み込みに失敗しました: " + e.getMessage());
        }
        return result;
        } finally {
            importRunning = false;
        }
    }

    private void processRow(String[] row, int rowNum, CsvImportResult result) {
        if (row.length < CSV_HEADER.length) {
            row = Arrays.copyOf(row, CSV_HEADER.length);
        }
        String email = CsvUtil.trimOrNull(row[0]);
        String displayName = CsvUtil.trimOrNull(row[1]);
        String carrierDomain = CsvUtil.trimOrNull(row[2]);
        String memo = CsvUtil.trimOrNull(row[3]);
        // ad_code (col 5) and gender (col 6) are both optional. Padding above guarantees access.
        String adCode = CsvUtil.trimOrNull(row[4]);
        String gender = normalizeCsvGender(CsvUtil.trimOrNull(row[5]));

        if (email == null) {
            result.addError(rowNum, "メールアドレスが空です");
            return;
        }
        if (!CsvUtil.isValidEmail(email)) {
            result.addError(rowNum, "メールアドレスの形式が不正です: " + email);
            return;
        }
        if (repository.existsByEmail(email)) {
            result.incrementDuplicate();
            return;
        }

        CrmUser u = new CrmUser();
        u.setEmail(email);
        u.setDisplayName(displayName);
        u.setCarrierDomain(deriveCarrierDomainIfBlank(carrierDomain, email));
        u.setMemo(memo);
        u.setAdCode(adCode);
        u.setGender(gender);
        IssuedCredentials cred = issueFreshCredentials(u);
        repository.save(u);
        result.addIssuedCredential(email, cred.getLoginId(), cred.getPlainPassword());
        result.incrementSuccess();
    }

    public void exportCsv(UserSearchForm form, Writer writer) throws IOException {
        writer.write('\uFEFF'); // UTF-8 BOM so Excel opens as UTF-8
        try (CSVWriter csv = new CSVWriter(writer)) {
            csv.writeNext(CSV_HEADER);
            List<CrmUser> users = repository.findAll(buildSpecification(form),
                    Sort.by(Sort.Direction.DESC, "createdAt"));
            for (CrmUser u : users) {
                csv.writeNext(new String[]{
                        safe(u.getEmail()),
                        safe(u.getDisplayName()),
                        safe(u.getCarrierDomain()),
                        safe(u.getMemo()),
                        safe(u.getAdCode()),
                        safe(u.getGender())
                });
            }
        }
    }

    /**
     * Open the import stream as a CSV reader, autodetecting the column delimiter
     * (comma vs. tab) and stripping a UTF-8 BOM if present.
     *
     * Excel "Save as CSV UTF-8" produces comma-separated with a BOM.
     * Excel "Save as Tab-Separated" or copy-paste-into-text-editor produces tabs.
     * Both formats are now accepted.
     */
    private static CSVReader openReader(InputStream in) throws IOException {
        Reader base = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        PushbackReader pbr = new PushbackReader(base, 4096);

        // Strip UTF-8 BOM (﻿) if present.
        int first = pbr.read();
        if (first != -1 && first != 0xFEFF) {
            pbr.unread(first);
        }

        // Peek the first line to choose between comma and tab as the delimiter.
        char[] buf = new char[2048];
        int n = pbr.read(buf, 0, buf.length);
        char sep = ',';
        if (n > 0) {
            int newline = -1;
            for (int i = 0; i < n; i++) { if (buf[i] == '\n' || buf[i] == '\r') { newline = i; break; } }
            int end = newline >= 0 ? newline : n;
            int tabs = 0, commas = 0;
            for (int i = 0; i < end; i++) {
                if (buf[i] == '\t') tabs++;
                else if (buf[i] == ',') commas++;
            }
            if (tabs > commas) sep = '\t';
            pbr.unread(buf, 0, n);
        }

        return new CSVReaderBuilder(pbr)
                .withCSVParser(new CSVParserBuilder().withSeparator(sep).build())
                .build();
    }

    private static boolean isExpectedHeader(String[] header) {
        // The first 4 columns must match exactly (in order). Extra trailing columns
        // (e.g. "ad_code") are accepted; missing trailing columns are also accepted to
        // preserve compatibility with CSVs created before the 広告設定 feature shipped.
        if (header.length < CSV_HEADER_REQUIRED.length) return false;
        for (int i = 0; i < CSV_HEADER_REQUIRED.length; i++) {
            if (header[i] == null || !CSV_HEADER_REQUIRED[i].equalsIgnoreCase(header[i].trim())) {
                return false;
            }
        }
        return true;
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static Specification<CrmUser> buildSpecification(UserSearchForm form) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            List<String> emailTokens = form.emailTokens();
            if (!emailTokens.isEmpty()) {
                List<Predicate> ors = new ArrayList<>(emailTokens.size());
                for (String t : emailTokens) ors.add(cb.like(root.get("email"), "%" + t + "%"));
                predicates.add(cb.or(ors.toArray(new Predicate[0])));
            }
            List<String> nameTokens = form.displayNameTokens();
            if (!nameTokens.isEmpty()) {
                List<Predicate> ors = new ArrayList<>(nameTokens.size());
                for (String t : nameTokens) ors.add(cb.like(root.get("displayName"), "%" + t + "%"));
                predicates.add(cb.or(ors.toArray(new Predicate[0])));
            }
            if (hasText(form.getStatus())) {
                predicates.add(cb.equal(root.get("status"), form.getStatus()));
            }
            // carrierCode field on CrmUser was removed; the form field is preserved for binding
            // compatibility but ignored here (filter by emailDomain instead).
            if (hasText(form.getEmailDomain())) {
                // Match the part after '@' on the email: LIKE '%@<domain>' (right-anchored).
                String d = form.getEmailDomain().trim();
                predicates.add(cb.like(root.get("email"), "%@" + d));
            }
            if (hasText(form.getFolder())) {
                if ("__NONE__".equals(form.getFolder())) {
                    predicates.add(cb.isNull(root.get("folder")));
                } else {
                    predicates.add(cb.equal(root.get("folder"), form.getFolder()));
                }
            }
            if (hasText(form.getAdCode())) {
                if ("__NONE__".equals(form.getAdCode())) {
                    predicates.add(cb.isNull(root.get("adCode")));
                } else {
                    predicates.add(cb.equal(root.get("adCode"), form.getAdCode()));
                }
            }
            if (hasText(form.getGender())) {
                if ("__NONE__".equals(form.getGender())) {
                    predicates.add(cb.isNull(root.get("gender")));
                } else {
                    predicates.add(cb.equal(root.get("gender"), form.getGender()));
                }
            }

            // Period filters — any combination can be set.
            java.time.LocalDateTime loginFrom = parseStart(form.getLoginFrom());
            java.time.LocalDateTime loginTo   = parseEndExclusive(form.getLoginTo());
            if (loginFrom != null) predicates.add(cb.greaterThanOrEqualTo(root.get("lastLoginAt"), loginFrom));
            if (loginTo   != null) predicates.add(cb.lessThan(root.get("lastLoginAt"), loginTo));

            java.time.LocalDateTime sendFrom = parseStart(form.getSendFrom());
            java.time.LocalDateTime sendTo   = parseEndExclusive(form.getSendTo());
            if (sendFrom != null || sendTo != null) {
                // Filter by users whose LAST inbound timestamp (MAX createdAt, direction=IN)
                // falls within the window — matches what the 最終送信 column displays.
                // Using GROUP BY + HAVING so that a user who also sent after the end date is
                // correctly excluded (the prior ANY-message subquery approach showed them).
                javax.persistence.criteria.Subquery<Long> sq = query.subquery(Long.class);
                javax.persistence.criteria.Root<com.crm.entity.Message> mRoot = sq.from(com.crm.entity.Message.class);
                sq.select(mRoot.get("userId"));
                sq.groupBy(mRoot.get("userId"));
                sq.where(cb.equal(mRoot.get("direction"), com.crm.entity.Message.DIR_IN));
                javax.persistence.criteria.Expression<java.time.LocalDateTime> maxAt =
                        cb.greatest(mRoot.<java.time.LocalDateTime>get("createdAt"));
                java.util.List<Predicate> havingPreds = new java.util.ArrayList<>();
                if (sendFrom != null) havingPreds.add(cb.greaterThanOrEqualTo(maxAt, sendFrom));
                if (sendTo   != null) havingPreds.add(cb.lessThan(maxAt, sendTo));
                sq.having(cb.and(havingPreds.toArray(new Predicate[0])));
                predicates.add(root.get("id").in(sq));
            }

            // Registration period — direct CRM_USER.createdAt filter.
            java.time.LocalDateTime regFrom = parseStart(form.getRegisterFrom());
            java.time.LocalDateTime regTo   = parseEndExclusive(form.getRegisterTo());
            if (regFrom != null) predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), regFrom));
            if (regTo   != null) predicates.add(cb.lessThan(root.get("createdAt"), regTo));

            java.time.LocalDateTime payFrom = parseStart(form.getPaymentFrom());
            java.time.LocalDateTime payTo   = parseEndExclusive(form.getPaymentTo());
            if (payFrom != null || payTo != null) {
                // 最終入金日 (LAST PAID payment) must fall inside the period — not just *any*
                // payment in range. A user with a 5/6 PAID and a 5/13 PAID is filtered OUT for
                // a 5/1–5/6 range, because their last payment (5/13) is outside the window.
                javax.persistence.criteria.Subquery<Long> sq = query.subquery(Long.class);
                javax.persistence.criteria.Root<com.crm.entity.Payment> pRoot = sq.from(com.crm.entity.Payment.class);
                sq.select(pRoot.get("userId"));
                sq.where(cb.equal(pRoot.get("status"), com.crm.entity.Payment.STATUS_PAID));
                sq.groupBy(pRoot.get("userId"));
                javax.persistence.criteria.Expression<java.time.LocalDateTime> maxPaidAt =
                        cb.greatest(pRoot.<java.time.LocalDateTime>get("paidAt"));
                java.util.List<Predicate> havingPreds = new java.util.ArrayList<>();
                if (payFrom != null) havingPreds.add(cb.greaterThanOrEqualTo(maxPaidAt, payFrom));
                if (payTo   != null) havingPreds.add(cb.lessThan(maxPaidAt, payTo));
                sq.having(cb.and(havingPreds.toArray(new Predicate[0])));
                predicates.add(root.get("id").in(sq));
            }

            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private static boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }

    /** Parse either a {@code yyyy-MM-dd} date (start-of-day) or a {@code yyyy-MM-ddTHH:mm}
     *  datetime-local value into a {@link java.time.LocalDateTime}. Returns null when blank
     *  or unparseable. The two input shapes coexist so the same backend works for either
     *  the {@code type="date"} or {@code type="datetime-local"} HTML inputs. */
    private static java.time.LocalDateTime parseStart(String iso) {
        if (!hasText(iso)) return null;
        String s = iso.trim();
        try {
            if (s.length() >= 16 && s.charAt(10) == 'T') return java.time.LocalDateTime.parse(s);
            return java.time.LocalDate.parse(s).atStartOfDay();
        } catch (Exception e) { return null; }
    }

    /** Exclusive upper bound.
     *  • date-only  "2026-05-11"       → 2026-05-12T00:00 (start of next day)
     *  • datetime   "2026-05-11T23:59" → 2026-05-12T00:00 (start of next minute,
     *    so 23:59:xx seconds are included and the display value matches the cutoff) */
    private static java.time.LocalDateTime parseEndExclusive(String iso) {
        if (!hasText(iso)) return null;
        String s = iso.trim();
        try {
            if (s.length() >= 16 && s.charAt(10) == 'T') {
                return java.time.LocalDateTime.parse(s).plusMinutes(1);
            }
            return java.time.LocalDate.parse(s).plusDays(1).atStartOfDay();
        } catch (Exception e) { return null; }
    }

    public static class DuplicateEmailException extends RuntimeException {
        public DuplicateEmailException(String email) { super("duplicate email: " + email); }
    }

    public static class UserNotFoundException extends RuntimeException {
        public UserNotFoundException(Long id) { super("user not found: " + id); }
    }

    public static class IssuedCredentials {
        private final String loginId;
        private final String plainPassword;

        public IssuedCredentials(String loginId, String plainPassword) {
            this.loginId = loginId;
            this.plainPassword = plainPassword;
        }

        public String getLoginId() { return loginId; }
        public String getPlainPassword() { return plainPassword; }
    }

    public static class CreationResult {
        private final CrmUser user;
        private final IssuedCredentials credentials;

        public CreationResult(CrmUser user, IssuedCredentials credentials) {
            this.user = user;
            this.credentials = credentials;
        }

        public CrmUser getUser() { return user; }
        public IssuedCredentials getCredentials() { return credentials; }
    }
}
