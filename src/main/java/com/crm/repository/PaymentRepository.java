package com.crm.repository;

import com.crm.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface PaymentRepository extends JpaRepository<Payment, Long>, JpaSpecificationExecutor<Payment> {

    List<Payment> findByUserIdOrderByCreatedAtDesc(Long userId);

    @Query("SELECT MAX(p.paidAt) FROM Payment p WHERE p.userId = :userId AND p.status = 'PAID'")
    LocalDateTime maxPaidAtForUser(@Param("userId") Long userId);

    long countByStatus(String status);

    @Query("SELECT COUNT(p) FROM Payment p WHERE p.status = 'OVERDUE' " +
           "OR (p.status = 'PENDING' AND p.dueDate < :today)")
    long countOverdue(@Param("today") java.time.LocalDate today);

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p WHERE p.status = 'PAID'")
    java.math.BigDecimal sumTotalPaid();

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p WHERE p.status = 'PAID' AND p.paidAt >= :from")
    java.math.BigDecimal sumPaidSince(@Param("from") LocalDateTime from);

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p WHERE p.status IN ('PENDING', 'OVERDUE')")
    java.math.BigDecimal sumPending();

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p " +
           "WHERE p.status = 'PAID' AND p.paidAt >= :from AND p.paidAt < :to")
    java.math.BigDecimal sumPaidBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p WHERE p.userId = :userId AND p.status = 'PAID'")
    java.math.BigDecimal sumPaidByUser(@Param("userId") Long userId);

    /** Paid totals grouped by user within a time window. Result rows: [userId (Long), sum (BigDecimal)]. */
    @Query("SELECT p.userId, COALESCE(SUM(p.amount), 0) FROM Payment p " +
           "WHERE p.status = 'PAID' AND p.paidAt >= :from AND p.paidAt < :to " +
           "GROUP BY p.userId")
    List<Object[]> sumPaidByUserBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** Individual paid payments within a time window, for per-user detail panels. */
    @Query("SELECT p FROM Payment p WHERE p.status = 'PAID' AND p.paidAt >= :from AND p.paidAt < :to " +
           "ORDER BY p.paidAt ASC")
    List<Payment> findPaidBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /** Total paid amount for users carrying the given ad_code. */
    @Query(value = "SELECT COALESCE(SUM(p.AMOUNT), 0) FROM PAYMENT p " +
                   "JOIN CRM_USER u ON u.ID = p.USER_ID " +
                   "WHERE p.STATUS = 'PAID' AND u.AD_CODE = :code", nativeQuery = true)
    java.math.BigDecimal sumPaidByAdCode(@Param("code") String code);

    /** Per-day paid amounts for the given ad_code, returned as [yyyy-MM-dd, sum] rows. */
    @Query(value = "SELECT DATE_FORMAT(p.PAID_AT, '%Y-%m-%d') AS d, COALESCE(SUM(p.AMOUNT), 0) " +
                   "FROM PAYMENT p JOIN CRM_USER u ON u.ID = p.USER_ID " +
                   "WHERE p.STATUS = 'PAID' AND u.AD_CODE = :code " +
                   "AND p.PAID_AT BETWEEN :start AND :end " +
                   "GROUP BY d ORDER BY d", nativeQuery = true)
    List<Object[]> sumByAdCodeGroupedByDay(@Param("code") String code,
                                            @Param("start") LocalDateTime start,
                                            @Param("end") LocalDateTime end);

    /** Per-month paid amounts for the given ad_code, returned as [yyyy-MM, sum] rows. */
    @Query(value = "SELECT DATE_FORMAT(p.PAID_AT, '%Y-%m') AS m, COALESCE(SUM(p.AMOUNT), 0) " +
                   "FROM PAYMENT p JOIN CRM_USER u ON u.ID = p.USER_ID " +
                   "WHERE p.STATUS = 'PAID' AND u.AD_CODE = :code " +
                   "AND p.PAID_AT BETWEEN :start AND :end " +
                   "GROUP BY m ORDER BY m", nativeQuery = true)
    List<Object[]> sumByAdCodeGroupedByMonth(@Param("code") String code,
                                              @Param("start") LocalDateTime start,
                                              @Param("end") LocalDateTime end);

    /** Per-(ad_code, gender) paid total within a window. Returns [ad_code, gender, sum]. */
    @Query(value = "SELECT u.AD_CODE, u.GENDER, COALESCE(SUM(p.AMOUNT), 0) " +
                   "FROM PAYMENT p JOIN CRM_USER u ON u.ID = p.USER_ID " +
                   "WHERE p.STATUS = 'PAID' AND u.AD_CODE IS NOT NULL " +
                   "AND p.PAID_AT BETWEEN :start AND :end " +
                   "GROUP BY u.AD_CODE, u.GENDER", nativeQuery = true)
    List<Object[]> sumByAdCodeAndGenderBetween(@Param("start") LocalDateTime start,
                                                @Param("end") LocalDateTime end);

    /** Per-(day, gender) paid total for one ad_code within a window. */
    @Query(value = "SELECT DATE_FORMAT(p.PAID_AT, '%Y-%m-%d'), u.GENDER, COALESCE(SUM(p.AMOUNT), 0) " +
                   "FROM PAYMENT p JOIN CRM_USER u ON u.ID = p.USER_ID " +
                   "WHERE p.STATUS = 'PAID' AND u.AD_CODE = :code " +
                   "AND p.PAID_AT BETWEEN :start AND :end " +
                   "GROUP BY DATE_FORMAT(p.PAID_AT, '%Y-%m-%d'), u.GENDER", nativeQuery = true)
    List<Object[]> sumByDayAndGenderForCode(@Param("code") String code,
                                             @Param("start") LocalDateTime start,
                                             @Param("end") LocalDateTime end);
}
