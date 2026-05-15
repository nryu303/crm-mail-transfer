package com.crm.dto;

import com.crm.entity.Payment;
import org.springframework.format.annotation.DateTimeFormat;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

public class PaymentForm {

    /** Populated by controller from the path variable, so no @NotNull here — it is set after @Valid runs. */
    private Long userId;

    @NotNull(message = "金額を入力してください")
    @DecimalMin(value = "0.00", message = "金額は0以上で入力してください")
    private BigDecimal amount;

    @Size(max = 32)
    private String paymentMethod;

    @Size(max = 16)
    private String status = Payment.STATUS_PAID;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dueDate;

    @Size(max = 64)
    private String invoiceNumber;

    private String memo;

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }
    public String getInvoiceNumber() { return invoiceNumber; }
    public void setInvoiceNumber(String invoiceNumber) { this.invoiceNumber = invoiceNumber; }
    public String getMemo() { return memo; }
    public void setMemo(String memo) { this.memo = memo; }

    public static PaymentForm from(Payment p) {
        PaymentForm f = new PaymentForm();
        f.userId = p.getUserId();
        f.amount = p.getAmount();
        f.paymentMethod = p.getPaymentMethod();
        f.status = p.getStatus();
        f.dueDate = p.getDueDate();
        f.invoiceNumber = p.getInvoiceNumber();
        f.memo = p.getMemo();
        return f;
    }

    public void applyTo(Payment p) {
        p.setUserId(userId);
        p.setAmount(amount);
        p.setPaymentMethod(trim(paymentMethod));
        p.setStatus(trim(status));
        p.setDueDate(dueDate);
        p.setInvoiceNumber(trim(invoiceNumber));
        p.setMemo(memo);
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
