package com.crm.service;

import com.crm.dto.CarrierPoolForm;
import com.crm.dto.CarrierPoolSearchForm;
import com.crm.dto.CsvImportResult;
import com.crm.entity.CarrierAddressPool;
import com.crm.repository.CarrierAddressPoolRepository;
import com.crm.util.AesEncryptionUtil;
import com.crm.util.CsvUtil;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.CSVParserBuilder;
import com.opencsv.exceptions.CsvValidationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.criteria.Predicate;
import java.io.BufferedReader;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class CarrierPoolService {

    private static final Set<String> CARRIER_CODES_SET =
            new HashSet<>(CrmUserService.CARRIER_CODES);

    private static final String[] CSV_HEADER = {
            "address", "carrier_code", "carrier_domain",
            "smtp_host", "smtp_port", "smtp_username", "smtp_password"
    };

    private final CarrierAddressPoolRepository repository;
    private final com.crm.repository.CarrierUserBindingRepository bindingRepository;
    private final AesEncryptionUtil aes;
    private final ImapEnvSyncService imapEnvSyncService;

    public CarrierPoolService(CarrierAddressPoolRepository repository,
                              com.crm.repository.CarrierUserBindingRepository bindingRepository,
                              AesEncryptionUtil aes,
                              ImapEnvSyncService imapEnvSyncService) {
        this.repository = repository;
        this.bindingRepository = bindingRepository;
        this.aes = aes;
        this.imapEnvSyncService = imapEnvSyncService;
    }

    public Page<CarrierAddressPool> search(CarrierPoolSearchForm form) {
        Pageable pageable = PageRequest.of(form.getPage(), form.getSize(),
                Sort.by(Sort.Direction.ASC, "carrierCode").and(Sort.by(Sort.Direction.ASC, "address")));
        return repository.findAll(buildSpec(form), pageable);
    }

    public long countUnbound() {
        return bindingRepository.countActivePoolsWithNoBindings();
    }

    public long countBindingsForPool(Long poolId) {
        return bindingRepository.countByPoolId(poolId);
    }

    public Optional<CarrierAddressPool> findById(Long id) {
        return repository.findById(id);
    }

    @Transactional
    public CarrierAddressPool create(CarrierPoolForm form) {
        String addr = form.getAddress() == null ? null : form.getAddress().trim();
        if (addr != null && repository.existsByAddress(addr)) {
            throw new DuplicateAddressException(addr);
        }
        CarrierAddressPool p = new CarrierAddressPool();
        p.setAddress(addr);
        p.setCarrierCode(form.getCarrierCode().trim());
        p.setCarrierDomain(form.getCarrierDomain().trim());
        p.setSmtpHost(form.getSmtpHost().trim());
        p.setSmtpPort(form.getSmtpPort());
        p.setSmtpUsername(form.getSmtpUsername().trim());
        p.setSmtpPassword(aes.encrypt(form.getSmtpPassword()));
        p.setIsActive(true);
        CarrierAddressPool saved = repository.save(p);
        imapEnvSyncService.triggerAfterCommit();
        return saved;
    }

    @Transactional
    public CarrierAddressPool update(Long id, CarrierPoolForm form) {
        CarrierAddressPool p = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("carrier pool entry not found: " + id));
        String newAddr = form.getAddress() == null ? null : form.getAddress().trim();
        if (newAddr != null && !newAddr.equals(p.getAddress()) && repository.existsByAddress(newAddr)) {
            throw new DuplicateAddressException(newAddr);
        }
        p.setAddress(newAddr);
        p.setCarrierCode(form.getCarrierCode().trim());
        p.setCarrierDomain(form.getCarrierDomain().trim());
        p.setSmtpHost(form.getSmtpHost().trim());
        p.setSmtpPort(form.getSmtpPort());
        p.setSmtpUsername(form.getSmtpUsername().trim());
        // Only re-encrypt + replace the password when the operator typed a new value.
        // Empty input means "keep the existing password".
        if (form.getSmtpPassword() != null && !form.getSmtpPassword().isEmpty()) {
            p.setSmtpPassword(aes.encrypt(form.getSmtpPassword()));
        }
        if (form.getIsActive() != null) p.setIsActive(form.getIsActive());
        CarrierAddressPool saved = repository.save(p);
        imapEnvSyncService.triggerAfterCommit();
        return saved;
    }

    /**
     * Soft-delete only: a hard DELETE cascades to CARRIER_USER_BINDING and permanently
     * breaks inbound reply-matching for any message already sent from this address
     * (InboundMailService looks the address up by row existence, not by isActive).
     * Deactivating instead keeps the row (and its bindings) so old replies still route.
     */
    @Transactional
    public void delete(Long id) {
        CarrierAddressPool p = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("carrier pool entry not found: " + id));
        p.setIsActive(false);
        repository.save(p);
        imapEnvSyncService.triggerAfterCommit();
    }

    @Transactional
    public int deleteByIds(java.util.List<Long> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        int n = 0;
        for (Long id : ids) {
            if (id == null) continue;
            try {
                CarrierAddressPool p = repository.findById(id).orElse(null);
                if (p == null) continue;
                p.setIsActive(false);
                repository.save(p);
                n++;
            } catch (Exception ignored) {}
        }
        if (n > 0) imapEnvSyncService.triggerAfterCommit();
        return n;
    }

    @Transactional
    public CsvImportResult importCsv(InputStream in) throws IOException {
        CsvImportResult result = new CsvImportResult();
        try (CSVReader reader = openReader(in)) {
            String[] header = reader.readNext();
            if (header == null) {
                result.addError(0, "CSVが空です");
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
            }
        } catch (CsvValidationException e) {
            result.addError(0, "CSVの読み込みに失敗しました: " + e.getMessage());
        }
        // CSV import is a multi-row pool mutation; fire one sync at end after commit.
        if (result.getSuccessCount() > 0) {
            imapEnvSyncService.triggerAfterCommit();
        }
        return result;
    }

    private void processRow(String[] row, int rowNum, CsvImportResult result) {
        if (row.length < CSV_HEADER.length) {
            row = Arrays.copyOf(row, CSV_HEADER.length);
        }
        String address = CsvUtil.trimOrNull(row[0]);
        String carrierCode = normaliseCarrierCode(CsvUtil.trimOrNull(row[1]));
        String carrierDomain = CsvUtil.trimOrNull(row[2]);
        String smtpHost = CsvUtil.trimOrNull(row[3]);
        String smtpPortStr = CsvUtil.trimOrNull(row[4]);
        String smtpUser = CsvUtil.trimOrNull(row[5]);
        String smtpPass = CsvUtil.trimOrNull(row[6]);

        if (address == null || !CsvUtil.isValidEmail(address)) {
            result.addError(rowNum, "キャリアアドレスが不正です: " + address);
            return;
        }
        if (carrierCode == null || !CARRIER_CODES_SET.contains(carrierCode)) {
            result.addError(rowNum, "キャリアコードが不正です: " + carrierCode + " (softbank|docomo|au)");
            return;
        }
        if (carrierDomain == null) {
            result.addError(rowNum, "キャリアドメインが空です");
            return;
        }
        if (smtpHost == null || smtpUser == null || smtpPass == null) {
            result.addError(rowNum, "SMTP接続情報が不足しています (host/username/password)");
            return;
        }
        Integer port = 587;
        if (smtpPortStr != null) {
            try { port = Integer.parseInt(smtpPortStr); }
            catch (NumberFormatException nfe) {
                result.addError(rowNum, "SMTPポートが数値ではありません: " + smtpPortStr);
                return;
            }
            if (port < 1 || port > 65535) {
                result.addError(rowNum, "SMTPポートが範囲外です: " + port);
                return;
            }
        }
        if (repository.existsByAddress(address)) {
            result.incrementDuplicate();
            return;
        }

        CarrierAddressPool p = new CarrierAddressPool();
        p.setAddress(address);
        p.setCarrierCode(carrierCode);
        p.setCarrierDomain(carrierDomain);
        p.setSmtpHost(smtpHost);
        p.setSmtpPort(port);
        p.setSmtpUsername(smtpUser);
        p.setSmtpPassword(aes.encrypt(smtpPass));
        p.setIsActive(true);
        repository.save(p);
        result.incrementSuccess();
    }

    /**
     * Accept common carrier_code variants from operator-prepared CSVs and normalise
     * to the canonical short code ({@code softbank}, {@code docomo}, {@code au}).
     * Without this, an Excel sheet that wrote {@code "softbank.jp"} or {@code "@docomo.ne.jp"}
     * in the carrier_code column would fail validation row-by-row even though the
     * intent was obvious.
     */
    static String normaliseCarrierCode(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase();
        if (s.isEmpty()) return null;
        // Drop a leading '@' (someone might have copied the domain with the at sign)
        if (s.startsWith("@")) s = s.substring(1);
        // Already canonical? keep.
        if (CARRIER_CODES_SET.contains(s)) return s;
        // Domain-form mappings — anything matching a known carrier's mail domain
        // collapses to the short code.
        if (s.contains("softbank")) return "softbank";
        if (s.contains("docomo")) return "docomo";
        if (s.equals("ezweb.ne.jp") || s.equals("au.com") || s.contains("ezweb") || s.equals("au")) return "au";
        return raw;  // return unchanged so the caller's validation surfaces the error
    }

    /**
     * Open the import stream as a CSV reader, stripping the UTF-8 BOM (﻿) if
     * present and autodetecting comma vs. tab as the delimiter. Mirrors
     * CrmUserService.openReader so both import surfaces accept the same input shapes:
     *   - Excel "Save as CSV UTF-8" → comma-separated with a BOM ← was the bug
     *   - Plain text-editor save     → comma or tab, no BOM
     */
    private static CSVReader openReader(InputStream in) throws IOException {
        Reader base = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        PushbackReader pbr = new PushbackReader(base, 4096);
        int first = pbr.read();
        if (first != -1 && first != 0xFEFF) {
            pbr.unread(first);
        }
        char[] buf = new char[2048];
        int n = pbr.read(buf, 0, buf.length);
        char sep = ',';
        if (n > 0) {
            int newline = -1;
            for (int i = 0; i < n; i++) {
                if (buf[i] == '\n' || buf[i] == '\r') { newline = i; break; }
            }
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
        if (header.length < CSV_HEADER.length) return false;
        for (int i = 0; i < CSV_HEADER.length; i++) {
            if (header[i] == null || !CSV_HEADER[i].equalsIgnoreCase(header[i].trim())) {
                return false;
            }
        }
        return true;
    }

    private static Specification<CarrierAddressPool> buildSpec(CarrierPoolSearchForm form) {
        return (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            java.util.List<String> addressTokens = form.addressTokens();
            if (!addressTokens.isEmpty()) {
                List<Predicate> ors = new ArrayList<>(addressTokens.size());
                for (String t : addressTokens) ors.add(cb.like(root.get("address"), "%" + t + "%"));
                p.add(cb.or(ors.toArray(new Predicate[0])));
            }
            if (hasText(form.getCarrierCode())) {
                p.add(cb.equal(root.get("carrierCode"), form.getCarrierCode()));
            }
            if (!form.isShowInactive()) {
                p.add(cb.equal(root.get("isActive"), true));
            }
            if ("UNBOUND".equals(form.getBoundFilter())) {
                // Sub-select: pools that have zero bindings
                javax.persistence.criteria.Subquery<Long> sub = query.subquery(Long.class);
                javax.persistence.criteria.Root<com.crm.entity.CarrierUserBinding> b = sub.from(com.crm.entity.CarrierUserBinding.class);
                sub.select(b.get("poolId"))
                   .where(cb.equal(b.get("poolId"), root.get("id")));
                p.add(cb.not(cb.exists(sub)));
            } else if ("BOUND".equals(form.getBoundFilter())) {
                javax.persistence.criteria.Subquery<Long> sub = query.subquery(Long.class);
                javax.persistence.criteria.Root<com.crm.entity.CarrierUserBinding> b = sub.from(com.crm.entity.CarrierUserBinding.class);
                sub.select(b.get("poolId"))
                   .where(cb.equal(b.get("poolId"), root.get("id")));
                p.add(cb.exists(sub));
            }
            return p.isEmpty() ? cb.conjunction() : cb.and(p.toArray(new Predicate[0]));
        };
    }

    private static boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }

    public static class DuplicateAddressException extends RuntimeException {
        public DuplicateAddressException(String address) { super("duplicate address: " + address); }
    }
}
