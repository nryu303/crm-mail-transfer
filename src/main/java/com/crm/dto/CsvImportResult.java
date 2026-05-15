package com.crm.dto;

import java.util.ArrayList;
import java.util.List;

public class CsvImportResult {

    private int totalRows;
    private int successCount;
    private int duplicateCount;
    private int errorCount;
    private final List<RowError> errors = new ArrayList<>();
    private final List<IssuedRow> issuedCredentials = new ArrayList<>();

    public void incrementTotal() { totalRows++; }
    public void incrementSuccess() { successCount++; }
    public void incrementDuplicate() { duplicateCount++; }

    public void addError(int rowNumber, String reason) {
        errorCount++;
        errors.add(new RowError(rowNumber, reason));
    }

    public void addIssuedCredential(String email, String loginId, String plainPassword) {
        issuedCredentials.add(new IssuedRow(email, loginId, plainPassword));
    }

    public int getTotalRows() { return totalRows; }
    public int getSuccessCount() { return successCount; }
    public int getDuplicateCount() { return duplicateCount; }
    public int getErrorCount() { return errorCount; }
    public List<RowError> getErrors() { return errors; }
    public List<IssuedRow> getIssuedCredentials() { return issuedCredentials; }

    public static class RowError {
        private final int rowNumber;
        private final String reason;

        public RowError(int rowNumber, String reason) {
            this.rowNumber = rowNumber;
            this.reason = reason;
        }

        public int getRowNumber() { return rowNumber; }
        public String getReason() { return reason; }
    }

    public static class IssuedRow {
        private final String email;
        private final String loginId;
        private final String plainPassword;

        public IssuedRow(String email, String loginId, String plainPassword) {
            this.email = email;
            this.loginId = loginId;
            this.plainPassword = plainPassword;
        }

        public String getEmail() { return email; }
        public String getLoginId() { return loginId; }
        public String getPlainPassword() { return plainPassword; }
    }
}
