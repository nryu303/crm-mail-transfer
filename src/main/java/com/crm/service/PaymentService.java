package com.crm.service;

import com.crm.dto.PaymentForm;
import com.crm.dto.PaymentSearchForm;
import com.crm.entity.CrmUser;
import com.crm.entity.Payment;
import com.crm.repository.CrmUserRepository;
import com.crm.repository.PaymentRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Subquery;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Service
public class PaymentService {

    public static final List<String> STATUSES = Arrays.asList(
            Payment.STATUS_PENDING, Payment.STATUS_PAID,
            Payment.STATUS_OVERDUE, Payment.STATUS_CANCELLED, Payment.STATUS_REFUNDED);

    public static final List<String> METHODS = Arrays.asList(
            Payment.METHOD_BANK_TRANSFER, Payment.METHOD_CREDIT_CARD, Payment.METHOD_CASH);

    private final PaymentRepository paymentRepository;
    private final CrmUserRepository userRepository;

    public PaymentService(PaymentRepository paymentRepository, CrmUserRepository userRepository) {
        this.paymentRepository = paymentRepository;
        this.userRepository = userRepository;
    }

    public Page<Payment> search(PaymentSearchForm form) {
        Pageable pageable = PageRequest.of(form.getPage(), form.getSize(),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return paymentRepository.findAll(buildSpec(form), pageable);
    }

    /** CSV export with UTF-8 BOM. Same filters as the list page. */
    public void exportCsv(PaymentSearchForm form, java.io.Writer writer) throws java.io.IOException {
        writer.write('\uFEFF');
        try (com.opencsv.CSVWriter csv = new com.opencsv.CSVWriter(writer)) {
            csv.writeNext(new String[]{
                    "id", "user_id", "user_email", "amount", "payment_method",
                    "status", "due_date", "paid_at", "invoice_number", "memo",
                    "created_at", "updated_at"});
            java.util.List<Payment> rows = paymentRepository.findAll(buildSpec(form),
                    Sort.by(Sort.Direction.DESC, "createdAt"));
            java.util.Set<Long> ids = new java.util.HashSet<>();
            for (Payment p : rows) ids.add(p.getUserId());
            java.util.Map<Long, String> email = new java.util.HashMap<>();
            for (CrmUser u : userRepository.findAllById(ids)) email.put(u.getId(), u.getEmail());
            java.time.format.DateTimeFormatter dt = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME;
            java.time.format.DateTimeFormatter d = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE;
            for (Payment p : rows) {
                csv.writeNext(new String[]{
                        String.valueOf(p.getId()),
                        String.valueOf(p.getUserId()),
                        nul(email.get(p.getUserId())),
                        p.getAmount() == null ? "" : p.getAmount().toPlainString(),
                        nul(p.getPaymentMethod()),
                        nul(p.getStatus()),
                        p.getDueDate() == null ? "" : p.getDueDate().format(d),
                        p.getPaidAt() == null ? "" : p.getPaidAt().format(dt),
                        nul(p.getInvoiceNumber()),
                        nul(p.getMemo()),
                        p.getCreatedAt() == null ? "" : p.getCreatedAt().format(dt),
                        p.getUpdatedAt() == null ? "" : p.getUpdatedAt().format(dt)
                });
            }
        }
    }

    private static String nul(String v) { return v == null ? "" : v; }

    public List<Payment> listForUser(Long userId) {
        return paymentRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public java.math.BigDecimal sumPaidByUser(Long userId) {
        return paymentRepository.sumPaidByUser(userId);
    }

    public Optional<Payment> findById(Long id) {
        return paymentRepository.findById(id);
    }

    @Transactional
    public Payment create(PaymentForm form) {
        CrmUser user = userRepository.findById(form.getUserId())
                .orElseThrow(() -> new NotFoundException("user not found: " + form.getUserId()));
        Payment p = new Payment();
        form.applyTo(p);
        if (Payment.STATUS_PAID.equals(p.getStatus()) && p.getPaidAt() == null) {
            p.setPaidAt(LocalDateTime.now());
        }
        Payment saved = paymentRepository.save(p);
        if (Payment.STATUS_PAID.equals(saved.getStatus())) {
            refreshUserLastPaymentAt(user.getId());
        }
        return saved;
    }

    @Transactional
    public Payment update(Long id, PaymentForm form) {
        Payment p = paymentRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("payment not found: " + id));
        String oldStatus = p.getStatus();
        form.applyTo(p);
        // Transition to PAID: stamp paid_at if missing
        if (Payment.STATUS_PAID.equals(p.getStatus()) && p.getPaidAt() == null) {
            p.setPaidAt(LocalDateTime.now());
        }
        // Transition away from PAID: clear paid_at
        if (!Payment.STATUS_PAID.equals(p.getStatus()) && Payment.STATUS_PAID.equals(oldStatus)) {
            p.setPaidAt(null);
        }
        Payment saved = paymentRepository.save(p);
        refreshUserLastPaymentAt(saved.getUserId());
        return saved;
    }

    @Transactional
    public Payment markPaid(Long id) {
        Payment p = paymentRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("payment not found: " + id));
        p.setStatus(Payment.STATUS_PAID);
        p.setPaidAt(LocalDateTime.now());
        Payment saved = paymentRepository.save(p);
        refreshUserLastPaymentAt(saved.getUserId());
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        Optional<Payment> p = paymentRepository.findById(id);
        if (!p.isPresent()) return;
        Long uid = p.get().getUserId();
        paymentRepository.deleteById(id);
        refreshUserLastPaymentAt(uid);
    }

    /** Recompute and cache the most-recent paid-at on the user row, for list-view display. */
    private void refreshUserLastPaymentAt(Long userId) {
        LocalDateTime latest = paymentRepository.maxPaidAtForUser(userId);
        userRepository.findById(userId).ifPresent(u -> {
            u.setLastPaymentAt(latest);
            userRepository.save(u);
        });
    }

    private Specification<Payment> buildSpec(PaymentSearchForm form) {
        return (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (hasText(form.getStatus())) {
                p.add(cb.equal(root.get("status"), form.getStatus()));
            }
            if (form.getDueFrom() != null) {
                p.add(cb.greaterThanOrEqualTo(root.get("dueDate"), form.getDueFrom()));
            }
            if (form.getDueTo() != null) {
                p.add(cb.lessThanOrEqualTo(root.get("dueDate"), form.getDueTo()));
            }
            if (hasText(form.getUserEmail())) {
                // Subquery: userId IN (SELECT u.id FROM CrmUser u WHERE u.email LIKE ?)
                Subquery<Long> sub = query.subquery(Long.class);
                javax.persistence.criteria.Root<CrmUser> u = sub.from(CrmUser.class);
                sub.select(u.get("id")).where(
                        cb.like(u.get("email"), "%" + form.getUserEmail().trim() + "%"));
                p.add(root.get("userId").in(sub));
            }
            return p.isEmpty() ? cb.conjunction() : cb.and(p.toArray(new Predicate[0]));
        };
    }

    private static boolean hasText(String s) { return s != null && !s.trim().isEmpty(); }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String msg) { super(msg); }
    }
}
