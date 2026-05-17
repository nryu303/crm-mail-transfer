package com.crm.config;

import com.crm.dto.CsvImportResult;
import com.crm.service.CarrierPoolService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * One-shot bootstrap importer for carrier-pool entries. When
 * {@code /tmp/carrier-import.csv} exists at application startup, this runner
 * pipes it through {@link CarrierPoolService#importCsv(java.io.InputStream)}
 * and then deletes the file. Self-cleaning: a subsequent restart with no
 * file is a no-op.
 *
 * The path is hardcoded and root-owned-via-systemd to limit the surface — a
 * non-privileged process cannot drop a file the runner will accept. Intended
 * for one-off operational imports (e.g. re-importing after a BOM/format fix).
 */
@Component
public class CarrierPoolBootstrapImporter implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(CarrierPoolBootstrapImporter.class);
    private static final Path IMPORT_FILE = Paths.get("/tmp/carrier-import.csv");

    private final CarrierPoolService service;

    public CarrierPoolBootstrapImporter(CarrierPoolService service) {
        this.service = service;
    }

    @Override
    public void run(String... args) {
        if (!Files.exists(IMPORT_FILE)) {
            return;
        }
        log.info("[BOOTSTRAP-IMPORT] found {} — importing carrier-pool entries", IMPORT_FILE);
        try (FileInputStream in = new FileInputStream(IMPORT_FILE.toFile())) {
            CsvImportResult r = service.importCsv(in);
            log.info("[BOOTSTRAP-IMPORT] total={} success={} duplicate={} errors={}",
                    r.getTotalRows(), r.getSuccessCount(), r.getDuplicateCount(), r.getErrorCount());
            if (r.getErrorCount() > 0) {
                r.getErrors().forEach(e ->
                        log.warn("[BOOTSTRAP-IMPORT] row {}: {}", e.getRowNumber(), e.getReason()));
            }
        } catch (Exception e) {
            log.error("[BOOTSTRAP-IMPORT] failed: {}", e.toString(), e);
            return; // keep the file so the operator can retry after fixing
        }
        try {
            Files.delete(IMPORT_FILE);
            log.info("[BOOTSTRAP-IMPORT] deleted {}", IMPORT_FILE);
        } catch (Exception e) {
            log.warn("[BOOTSTRAP-IMPORT] could not delete {}: {}", IMPORT_FILE, e.toString());
        }
    }
}
