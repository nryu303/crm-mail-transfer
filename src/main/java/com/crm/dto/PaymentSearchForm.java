package com.crm.dto;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

public class PaymentSearchForm {
    private String userEmail;
    private String status;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dueFrom;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dueTo;
    private int page = 0;
    private int size = 20;

    public String getUserEmail() { return userEmail; }
    public void setUserEmail(String userEmail) { this.userEmail = userEmail; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDate getDueFrom() { return dueFrom; }
    public void setDueFrom(LocalDate dueFrom) { this.dueFrom = dueFrom; }
    public LocalDate getDueTo() { return dueTo; }
    public void setDueTo(LocalDate dueTo) { this.dueTo = dueTo; }
    public int getPage() { return page; }
    public void setPage(int page) { this.page = Math.max(page, 0); }
    public int getSize() { return size; }
    public void setSize(int size) { this.size = (size < 1 || size > 200) ? 20 : size; }
}
