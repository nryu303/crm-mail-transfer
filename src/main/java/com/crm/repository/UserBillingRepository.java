package com.crm.repository;

import com.crm.entity.UserBilling;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserBillingRepository extends JpaRepository<UserBilling, Long> {
    List<UserBilling> findByUserIdOrderByCreatedAtDesc(Long userId);
    List<UserBilling> findByStatusOrderByCreatedAtAsc(String status);
}
