package com.crm.service;

import com.crm.entity.Message;
import com.crm.repository.CarrierUserBindingRepository;
import com.crm.repository.CrmUserRepository;
import com.crm.repository.MessageRepository;
import com.crm.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Dashboard analytics buckets — daily / monthly slices feed the Chart.js series on the
 * dashboard's 送信数 card.
 */
class DashboardServiceTest {

    private MessageRepository msgRepo;
    private DashboardService svc;

    @BeforeEach
    void setUp() {
        msgRepo = mock(MessageRepository.class);
        PaymentRepository payRepo = mock(PaymentRepository.class);
        CarrierUserBindingRepository bindingRepo = mock(CarrierUserBindingRepository.class);
        CrmUserRepository userRepo = mock(CrmUserRepository.class);
        svc = new DashboardService(msgRepo, payRepo, bindingRepo, userRepo);
        // Defaults so every range returns 0 unless we override per-test.
        when(msgRepo.countByDirectionBetween(any(), any(), any())).thenReturn(0L);
        when(msgRepo.countByDirectionAndStatusBetween(any(), any(), any(), any())).thenReturn(0L);
    }

    @Test
    void dailyBuckets_emitsOneBucketPerDayInRange() {
        List<DashboardService.HourlySend> out = svc.dailyBuckets(
                LocalDate.of(2026, 5, 10), LocalDate.of(2026, 5, 12));
        assertThat(out).hasSize(3);
        assertThat(out.get(0).getLabel()).isEqualTo("05-10");
        assertThat(out.get(1).getLabel()).isEqualTo("05-11");
        assertThat(out.get(2).getLabel()).isEqualTo("05-12");
    }

    @Test
    void dailyBuckets_queryWindowIsHalfOpenStartOfDay() {
        // Each day's window must be [day 00:00, next-day 00:00).
        when(msgRepo.countByDirectionBetween(eq(Message.DIR_OUT), any(), any())).thenReturn(7L);
        svc.dailyBuckets(LocalDate.of(2026, 5, 10), LocalDate.of(2026, 5, 10));

        ArgumentCaptor<LocalDateTime> fromCap = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> toCap = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(msgRepo).countByDirectionBetween(eq(Message.DIR_OUT), fromCap.capture(), toCap.capture());
        assertThat(fromCap.getValue()).isEqualTo(LocalDateTime.of(2026, 5, 10, 0, 0));
        assertThat(toCap.getValue()).isEqualTo(LocalDateTime.of(2026, 5, 11, 0, 0));
    }

    @Test
    void dailyBuckets_swappedRange_clampsToStartDayOnly() {
        // end before start → service must not throw; clamp to single-day at start.
        List<DashboardService.HourlySend> out = svc.dailyBuckets(
                LocalDate.of(2026, 5, 12), LocalDate.of(2026, 5, 10));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getLabel()).isEqualTo("05-12");
    }

    @Test
    void dailyBuckets_nullStartDefaultsToFirstOfCurrentMonth() {
        // When called with both null, the service should produce a non-empty range.
        // (Span is "this month" — exact size depends on the calendar, but >= 28 days.)
        List<DashboardService.HourlySend> out = svc.dailyBuckets(null, null);
        assertThat(out.size()).isBetween(28, 31);
    }

    /**
     * The dashboard shows SMS送信数 and メール送信数 as two separate, non-summed series
     * (operator request 2026-07-14 — previously メール送信数 silently included SMS counts,
     * making the combined "送信数" bar look inflated relative to the SMS-only bar next to it).
     */
    @Test
    void hourlySend_emailSent_excludesSmsFromCombinedTotal() {
        when(msgRepo.countByDirectionBetween(eq(Message.DIR_OUT), any(), any())).thenReturn(10L);
        when(msgRepo.countByDirectionAndChannelBetween(eq(Message.DIR_OUT), eq(Message.CHANNEL_SMS), any(), any()))
                .thenReturn(4L);

        List<DashboardService.HourlySend> out = svc.dailyBuckets(
                LocalDate.of(2026, 5, 10), LocalDate.of(2026, 5, 10));

        assertThat(out).hasSize(1);
        assertThat(out.get(0).getSent()).isEqualTo(10L);
        assertThat(out.get(0).getSmsSent()).isEqualTo(4L);
        assertThat(out.get(0).getEmailSent()).isEqualTo(6L);
    }

    @Test
    void monthlyBuckets_emitsOneBucketPerMonthIncludingEnd() {
        List<DashboardService.HourlySend> out = svc.monthlyBuckets(
                YearMonth.of(2026, 3), YearMonth.of(2026, 6));
        assertThat(out).hasSize(4);
        assertThat(out.get(0).getLabel()).isEqualTo("2026-03");
        assertThat(out.get(3).getLabel()).isEqualTo("2026-06");
    }

    @Test
    void monthlyBuckets_queryWindowsAreCalendarMonths() {
        when(msgRepo.countByDirectionBetween(eq(Message.DIR_OUT), any(), any())).thenReturn(3L);
        svc.monthlyBuckets(YearMonth.of(2026, 2), YearMonth.of(2026, 3));

        ArgumentCaptor<LocalDateTime> fromCap = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> toCap = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(msgRepo, times(2)).countByDirectionBetween(eq(Message.DIR_OUT), fromCap.capture(), toCap.capture());
        // Feb 2026
        assertThat(fromCap.getAllValues().get(0)).isEqualTo(LocalDateTime.of(2026, 2, 1, 0, 0));
        assertThat(toCap.getAllValues().get(0)).isEqualTo(LocalDateTime.of(2026, 3, 1, 0, 0));
        // Mar 2026
        assertThat(fromCap.getAllValues().get(1)).isEqualTo(LocalDateTime.of(2026, 3, 1, 0, 0));
        assertThat(toCap.getAllValues().get(1)).isEqualTo(LocalDateTime.of(2026, 4, 1, 0, 0));
    }

    @Test
    void monthlyBuckets_sentAndNgAreAggregatedPerBucket() {
        when(msgRepo.countByDirectionBetween(any(), any(), any())).thenReturn(42L);
        when(msgRepo.countByDirectionAndStatusBetween(any(), any(), any(), any())).thenReturn(5L);
        List<DashboardService.HourlySend> out = svc.monthlyBuckets(
                YearMonth.of(2026, 4), YearMonth.of(2026, 4));
        assertThat(out).hasSize(1);
        assertThat(out.get(0).getSent()).isEqualTo(42L);
        assertThat(out.get(0).getNg()).isEqualTo(5L);
    }
}
