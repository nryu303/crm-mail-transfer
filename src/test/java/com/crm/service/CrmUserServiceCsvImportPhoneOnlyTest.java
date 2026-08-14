package com.crm.service;

import com.crm.dto.CsvImportResult;
import com.crm.entity.CrmUser;
import com.crm.repository.CrmUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CSV import must accept rows with a phone number but a blank email — SMS-only users.
 * Regression test for the client-reported bug: 2,571 phone-filled rows all failed with
 * 「メールアドレスが空です」 because email was unconditionally required regardless of phone.
 */
class CrmUserServiceCsvImportPhoneOnlyTest {

    private CrmUserRepository repo;
    private PasswordEncoder enc;
    private CarrierBindingService binding;
    private CrmUserService svc;

    @BeforeEach
    void setUp() {
        repo = mock(CrmUserRepository.class);
        enc = mock(PasswordEncoder.class);
        binding = mock(CarrierBindingService.class);
        when(repo.existsByEmail(anyString())).thenReturn(false);
        when(repo.existsByPhoneNumber(anyString())).thenReturn(false);
        when(repo.existsByLoginId(anyString())).thenReturn(false);
        when(enc.encode(anyString())).thenReturn("$2a$10$fake-bcrypt-hash");
        when(repo.save(any(CrmUser.class))).thenAnswer(inv -> inv.getArgument(0));
        svc = new CrmUserService(repo, enc, binding);
    }

    private CsvImportResult runImport(String csv) throws IOException {
        return svc.importCsv(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void phoneOnlyRow_emailBlank_importsSuccessfully() throws IOException {
        String csv = "email,display_name,carrier_domain,memo,ad_code,gender,phone\n"
                + ",Taro,,,,,09012345678\n";

        CsvImportResult r = runImport(csv);

        assertThat(r.getSuccessCount()).isEqualTo(1);
        assertThat(r.getErrorCount()).isEqualTo(0);

        ArgumentCaptor<CrmUser> saved = ArgumentCaptor.forClass(CrmUser.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isNull();
        assertThat(saved.getValue().getPhoneNumber()).isEqualTo("09012345678");
    }

    @Test
    void bothEmailAndPhoneBlank_isAnError() throws IOException {
        String csv = "email,display_name,carrier_domain,memo,ad_code,gender,phone\n"
                + ",Taro,,,,,\n";

        CsvImportResult r = runImport(csv);

        assertThat(r.getSuccessCount()).isEqualTo(0);
        assertThat(r.getErrorCount()).isEqualTo(1);
        assertThat(r.getErrors().get(0).getReason()).contains("どちらも空");
        verify(repo, never()).save(any(CrmUser.class));
    }

    @Test
    void phoneOnlyRow_duplicatePhone_isSkippedAsDuplicate_notError() throws IOException {
        when(repo.existsByPhoneNumber("09012345678")).thenReturn(true);
        String csv = "email,display_name,carrier_domain,memo,ad_code,gender,phone\n"
                + ",Taro,,,,,09012345678\n";

        CsvImportResult r = runImport(csv);

        assertThat(r.getDuplicateCount()).isEqualTo(1);
        assertThat(r.getSuccessCount()).isEqualTo(0);
        assertThat(r.getErrorCount()).isEqualTo(0);
        verify(repo, never()).save(any(CrmUser.class));
    }

    @Test
    void emailRow_stillValidatesFormatAndDedupesByEmail_unaffectedByPhoneChange() throws IOException {
        String csv = "email,display_name,carrier_domain,memo,ad_code,gender,phone\n"
                + "not-an-email,Taro,,,,,\n";

        CsvImportResult r = runImport(csv);

        assertThat(r.getErrorCount()).isEqualTo(1);
        assertThat(r.getErrors().get(0).getReason()).contains("形式が不正");
    }

    @Test
    void issuedCredentialLabel_fallsBackToPhoneWhenEmailBlank() throws IOException {
        String csv = "email,display_name,carrier_domain,memo,ad_code,gender,phone\n"
                + ",Taro,,,,,09012345678\n";

        CsvImportResult r = runImport(csv);

        assertThat(r.getIssuedCredentials()).hasSize(1);
        assertThat(r.getIssuedCredentials().get(0).getEmail()).isEqualTo("09012345678");
    }
}
