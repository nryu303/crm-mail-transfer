package com.crm.service;

import com.crm.dto.BillingPlanForm;
import com.crm.entity.BillingPlan;
import com.crm.entity.CrmUser;
import com.crm.entity.Payment;
import com.crm.entity.UserBilling;
import com.crm.repository.BillingPlanRepository;
import com.crm.repository.CrmUserRepository;
import com.crm.repository.PaymentRepository;
import com.crm.repository.UserBillingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);

    private final BillingPlanRepository planRepository;
    private final UserBillingRepository userBillingRepository;
    private final PaymentRepository paymentRepository;
    private final CrmUserRepository userRepository;

    public BillingService(BillingPlanRepository planRepository,
                          UserBillingRepository userBillingRepository,
                          PaymentRepository paymentRepository,
                          CrmUserRepository userRepository) {
        this.planRepository = planRepository;
        this.userBillingRepository = userBillingRepository;
        this.paymentRepository = paymentRepository;
        this.userRepository = userRepository;
    }

    // ---------- Plan CRUD ----------
    public List<BillingPlan> listPlans() { return planRepository.findAllByOrderByNameAsc(); }

    public List<BillingPlan> listActivePlans() { return planRepository.findByIsActiveTrueOrderByNameAsc(); }

    public Optional<BillingPlan> findPlan(Long id) { return planRepository.findById(id); }

    @Transactional
    public BillingPlan createPlan(BillingPlanForm form) {
        BillingPlan p = new BillingPlan();
        form.applyTo(p);
        return planRepository.save(p);
    }

    @Transactional
    public BillingPlan updatePlan(Long id, BillingPlanForm form) {
        BillingPlan p = planRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("plan not found: " + id));
        form.applyTo(p);
        return planRepository.save(p);
    }

    @Transactional
    public void deletePlan(Long id) { planRepository.deleteById(id); }

    // ---------- User subscription ----------
    public List<UserBilling> listSubscriptionsForUser(Long userId) {
        return userBillingRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @Transactional
    public UserBilling subscribe(Long userId, Long planId, LocalDate startDate) {
        if (userRepository.findById(userId).isPresent() && planRepository.findById(planId).isPresent()) {
            UserBilling ub = new UserBilling();
            ub.setUserId(userId);
            ub.setPlanId(planId);
            ub.setStartDate(startDate == null ? LocalDate.now() : startDate);
            ub.setStatus(UserBilling.STATUS_ACTIVE);
            return userBillingRepository.save(ub);
        }
        throw new IllegalArgumentException("user or plan not found");
    }

    @Transactional
    public void cancelSubscription(Long userBillingId) {
        userBillingRepository.findById(userBillingId).ifPresent(ub -> {
            ub.setStatus(UserBilling.STATUS_CANCELLED);
            ub.setEndDate(LocalDate.now());
            userBillingRepository.save(ub);
        });
    }

    /**
     * Daily job: generate a PENDING PAYMENT for each active subscription when the billing cycle
     * has rolled over and there isn't already a PAYMENT covering the current period.
     *
     * Heuristic: we look at the latest PAYMENT for the user that references this plan's amount
     * and skip creating a new one if the most-recent created_at is within the cycle window.
     * This is a conservative approach that avoids duplicate bills on scheduler restart.
     */
    @Scheduled(cron = "${app.scheduler.billing-cron:0 15 2 * * *}")
    @Transactional
    public void generateRecurringPayments() {
        LocalDate today = LocalDate.now();
        List<UserBilling> actives = userBillingRepository.findByStatusOrderByCreatedAtAsc(UserBilling.STATUS_ACTIVE);
        int generated = 0;
        for (UserBilling ub : actives) {
            Optional<BillingPlan> planOpt = planRepository.findById(ub.getPlanId());
            if (!planOpt.isPresent() || !Boolean.TRUE.equals(planOpt.get().getIsActive())) continue;
            BillingPlan plan = planOpt.get();

            // Skip if the subscription hasn't started yet or has already ended
            if (ub.getStartDate() != null && ub.getStartDate().isAfter(today)) continue;
            if (ub.getEndDate() != null && ub.getEndDate().isBefore(today)) continue;

            // Determine cycle window start
            LocalDate windowStart;
            switch (plan.getBillingCycle() == null ? BillingPlan.CYCLE_MONTHLY : plan.getBillingCycle()) {
                case BillingPlan.CYCLE_MONTHLY: windowStart = today.withDayOfMonth(1); break;
                case BillingPlan.CYCLE_YEARLY:  windowStart = today.withDayOfYear(1); break;
                case BillingPlan.CYCLE_ONE_TIME: windowStart = ub.getStartDate(); break;
                default: windowStart = today.withDayOfMonth(1);
            }

            // Has a payment for this user already been created in this window with the plan's amount?
            List<Payment> userPayments = paymentRepository.findByUserIdOrderByCreatedAtDesc(ub.getUserId());
            LocalDateTime windowStartTs = windowStart.atStartOfDay();
            boolean alreadyBilled = userPayments.stream().anyMatch(p ->
                    p.getCreatedAt() != null
                    && !p.getCreatedAt().isBefore(windowStartTs)
                    && p.getAmount() != null
                    && p.getAmount().compareTo(plan.getAmount()) == 0);
            if (alreadyBilled) continue;
            // For ONE_TIME: only bill once ever
            if (BillingPlan.CYCLE_ONE_TIME.equals(plan.getBillingCycle()) && !userPayments.isEmpty()) {
                boolean haveOneTime = userPayments.stream().anyMatch(p ->
                        p.getAmount() != null && p.getAmount().compareTo(plan.getAmount()) == 0);
                if (haveOneTime) continue;
            }

            Payment pay = new Payment();
            pay.setUserId(ub.getUserId());
            pay.setAmount(plan.getAmount());
            pay.setStatus(Payment.STATUS_PENDING);
            pay.setDueDate(today.plusDays(14));
            pay.setMemo("定期: " + plan.getName() + " (" + plan.getBillingCycle() + ")");
            paymentRepository.save(pay);
            generated++;
        }
        if (generated > 0) {
            log.info("Billing scheduler: generated {} recurring PENDING payments", generated);
        }
    }
}
