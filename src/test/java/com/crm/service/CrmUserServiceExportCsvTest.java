package com.crm.service;

import com.crm.dto.UserSearchForm;
import com.crm.entity.CrmUser;
import com.crm.repository.CrmUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.StringWriter;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CSV export must blank the memo column (2026-08-05, operator request): MEMO stores full
 * HTML documents (the per-user reply-page header) that read as unreadable noise
 * ("ハッシュコードのようなもの、htmlコードが多い") once dumped into a spreadsheet cell.
 * The column stays in the header (so a re-import of the exported file still matches
 * CSV_HEADER_REQUIRED) but every row's memo cell is empty.
 */
class CrmUserServiceExportCsvTest {

    @Test
    void exportCsv_memoColumnIsAlwaysBlank_evenWhenUserHasHtmlMemo() throws Exception {
        CrmUserRepository repo = mock(CrmUserRepository.class);
        PasswordEncoder enc = mock(PasswordEncoder.class);
        CarrierBindingService binding = mock(CarrierBindingService.class);
        CrmUserService svc = new CrmUserService(repo, enc, binding);

        CrmUser u = new CrmUser();
        u.setEmail("user@example.com");
        u.setDisplayName("Taro");
        u.setMemo("<!DOCTYPE html><html><head><title>大抽選会</title></head><body>...</body></html>");
        u.setPhoneNumber("09012345678");

        when(repo.findAll(org.mockito.ArgumentMatchers.<Specification<CrmUser>>any(), any(Sort.class)))
                .thenReturn(Arrays.asList(u));

        StringWriter out = new StringWriter();
        svc.exportCsv(new UserSearchForm(), out);
        String csv = out.toString();

        assertThat(csv).doesNotContain("DOCTYPE").doesNotContain("大抽選会");
        // Header still has 7 columns including "memo" — re-import compatibility preserved.
        assertThat(csv).contains("\"email\",\"display_name\",\"carrier_domain\",\"memo\",\"ad_code\",\"gender\",\"phone\"");
        // Data row: email/display_name/phone present, memo cell (4th field) empty.
        assertThat(csv).contains("\"user@example.com\",\"Taro\",\"\",\"\",\"\",\"\",\"09012345678\"");
    }
}
