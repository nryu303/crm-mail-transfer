package com.crm.dto;

import com.crm.entity.BillingPlan;

import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.math.BigDecimal;

public class BillingPlanForm {

    @NotBlank(message = "プラン名を入力してください")
    @Size(max = 255)
    private String name;

    @NotNull(message = "金額を入力してください")
    @DecimalMin(value = "0.00", message = "金額は0以上で入力してください")
    private BigDecimal amount;

    @Size(max = 16)
    private String billingCycle = BillingPlan.CYCLE_MONTHLY;

    private String description;

    private Boolean isActive = Boolean.TRUE;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getBillingCycle() { return billingCycle; }
    public void setBillingCycle(String billingCycle) { this.billingCycle = billingCycle; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }

    public static BillingPlanForm from(BillingPlan p) {
        BillingPlanForm f = new BillingPlanForm();
        f.name = p.getName();
        f.amount = p.getAmount();
        f.billingCycle = p.getBillingCycle();
        f.description = p.getDescription();
        f.isActive = p.getIsActive();
        return f;
    }

    public void applyTo(BillingPlan p) {
        p.setName(name == null ? null : name.trim());
        p.setAmount(amount);
        p.setBillingCycle(billingCycle);
        p.setDescription(description);
        p.setIsActive(isActive == null ? Boolean.TRUE : isActive);
    }
}
