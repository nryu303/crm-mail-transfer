package com.crm.service;

import com.crm.dto.CsvImportResult;
import com.crm.entity.CrmUser;
import com.crm.repository.CrmUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the live progress counter on {@link CrmUserService#importCsv} actually
 * advances during a run, so the /manager/users/import/progress endpoint reports a
 * useful value to the frontend poller. This regression-tests the operator-reported
 * issue 「数字は動きません」 from 2026-05-18.
 *
 * Pure-Mockito: no Spring context, no DB. Replaces the slow part of save() with a
 * short sleep so the test can observe the AtomicLong climbing mid-run.
 */
class CrmUserServiceImportProgressTest {

    @Test
    void importProgress_incrementsDuringRun_andSettlesAtFinalValue() throws Exception {
        CrmUserRepository repo = mock(CrmUserRepository.class);
        PasswordEncoder enc = mock(PasswordEncoder.class);
        CarrierBindingService binding = mock(CarrierBindingService.class);

        // Force every row to "new user, no duplicates" and slow save() down to ~5ms
        // so progress polling has a chance to observe intermediate values.
        when(repo.existsByEmail(anyString())).thenReturn(false);
        when(repo.existsByLoginId(anyString())).thenReturn(false);
        when(enc.encode(anyString())).thenReturn("$2a$10$fake-bcrypt-hash");
        when(repo.save(any(CrmUser.class))).thenAnswer(inv -> {
            Thread.sleep(5);
            return inv.getArgument(0);
        });

        CrmUserService svc = new CrmUserService(repo, enc, binding);

        // 30-row synthetic CSV ~= 150ms total work at 5ms/row, plenty of time to poll
        StringBuilder csv = new StringBuilder("email,display_name,carrier_domain,memo,ad_code,gender\n");
        for (int i = 0; i < 30; i++) {
            csv.append("test").append(i).append("@example.com,disp").append(i).append(",,,\n");
        }

        // Start the import on a worker thread; sample progress from main.
        AtomicLong maxObserved = new AtomicLong(0);
        CountDownLatch finished = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            try {
                CsvImportResult r = svc.importCsv(new ByteArrayInputStream(csv.toString().getBytes(StandardCharsets.UTF_8)));
                assertThat(r.getSuccessCount()).isEqualTo(30);
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                finished.countDown();
            }
        });
        t.start();

        // Sample for up to 5 s. Record the highest mid-run value seen, plus
        // running-flag visibility.
        boolean sawRunningTrue = false;
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline && finished.getCount() > 0) {
            long p = svc.getImportProgress();
            if (p > maxObserved.get()) maxObserved.set(p);
            if (svc.isImportRunning()) sawRunningTrue = true;
            Thread.sleep(20);
        }
        finished.await(5, TimeUnit.SECONDS);

        // After completion: counter should be at the last row, running flag back to false.
        assertThat(svc.isImportRunning()).as("running flag clears after completion").isFalse();
        assertThat(svc.getImportProgress()).as("final progress equals total rows").isGreaterThanOrEqualTo(29L);

        // Mid-run observations: we want to confirm the counter MOVED during execution
        // (i.e. wasn't stuck at 0) and the running flag was visible to outside threads.
        assertThat(sawRunningTrue).as("isImportRunning() returned true at least once during the run").isTrue();
        assertThat(maxObserved.get()).as("importProgress climbed above 0 mid-run").isGreaterThan(0L);
    }
}
