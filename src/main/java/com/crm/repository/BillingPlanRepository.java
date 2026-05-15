package com.crm.repository;

import com.crm.entity.BillingPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BillingPlanRepository extends JpaRepository<BillingPlan, Long> {
    List<BillingPlan> findAllByOrderByNameAsc();
    List<BillingPlan> findByIsActiveTrueOrderByNameAsc();
}
