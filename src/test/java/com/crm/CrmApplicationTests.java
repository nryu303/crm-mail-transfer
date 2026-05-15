package com.crm;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Spring-context smoke test. Disabled by default because it needs a live MySQL +
 * AES_ENCRYPTION_KEY env var to satisfy production bean wiring — running it from the
 * regular unit-test JVM would fail before the real tests get a chance.
 *
 * To enable locally:
 *   1. Start the MySQL container ({@code podman start crm-mysql})
 *   2. Export {@code AES_ENCRYPTION_KEY} + the other env vars from {@code crm.service}
 *   3. Remove the {@link Disabled} annotation
 *
 * Day-to-day regression coverage is provided by the per-class unit tests under
 * {@code com.crm.service} and {@code com.crm.util}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Disabled("requires live MySQL + AES_ENCRYPTION_KEY — see class comment")
class CrmApplicationTests {

    @Test
    void contextLoads() {
    }
}
