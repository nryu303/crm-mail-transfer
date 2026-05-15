package com.crm.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Period filter parsers — accept both {@code yyyy-MM-dd} dates and {@code yyyy-MM-ddTHH:mm}
 * datetime-local values so the search form works whether the operator pasted in a date or
 * picked a precise minute.
 */
class CrmUserServiceParsePeriodTest {

    private static LocalDateTime parseStart(String s) throws Exception {
        Method m = CrmUserService.class.getDeclaredMethod("parseStart", String.class);
        m.setAccessible(true);
        return (LocalDateTime) m.invoke(null, s);
    }

    private static LocalDateTime parseEndExclusive(String s) throws Exception {
        Method m = CrmUserService.class.getDeclaredMethod("parseEndExclusive", String.class);
        m.setAccessible(true);
        return (LocalDateTime) m.invoke(null, s);
    }

    @Test
    void parseStart_dateOnly_returnsStartOfDay() throws Exception {
        assertThat(parseStart("2026-05-12")).isEqualTo(LocalDateTime.of(2026, 5, 12, 0, 0));
    }

    @Test
    void parseStart_datetimeLocal_returnsExactInstant() throws Exception {
        assertThat(parseStart("2026-05-12T14:30")).isEqualTo(LocalDateTime.of(2026, 5, 12, 14, 30));
    }

    @Test
    void parseEndExclusive_dateOnly_returnsStartOfNextDay() throws Exception {
        // Date-only: bounds the day inclusively — use start of next day as exclusive upper.
        assertThat(parseEndExclusive("2026-05-12")).isEqualTo(LocalDateTime.of(2026, 5, 13, 0, 0));
    }

    @Test
    void parseEndExclusive_datetimeLocal_returnsExactInstant() throws Exception {
        // Operator picked a precise cut-off minute — honour it exactly, no +1 day.
        assertThat(parseEndExclusive("2026-05-12T18:45"))
                .isEqualTo(LocalDateTime.of(2026, 5, 12, 18, 45));
    }

    @Test
    void parseStart_blank_returnsNull() throws Exception {
        assertThat(parseStart(null)).isNull();
        assertThat(parseStart("")).isNull();
        assertThat(parseStart("   ")).isNull();
    }

    @Test
    void parseEndExclusive_blank_returnsNull() throws Exception {
        assertThat(parseEndExclusive(null)).isNull();
        assertThat(parseEndExclusive("")).isNull();
        assertThat(parseEndExclusive("   ")).isNull();
    }

    @Test
    void parseStart_garbage_returnsNullNotException() throws Exception {
        assertThat(parseStart("not-a-date")).isNull();
        assertThat(parseStart("2026-13-99")).isNull();
        assertThat(parseStart("2026-05-12TYY:ZZ")).isNull();
    }

    @Test
    void parseStart_whitespace_isTrimmed() throws Exception {
        assertThat(parseStart("  2026-05-12  "))
                .isEqualTo(LocalDateTime.of(2026, 5, 12, 0, 0));
        assertThat(parseStart("  2026-05-12T09:30  "))
                .isEqualTo(LocalDateTime.of(2026, 5, 12, 9, 30));
    }
}
