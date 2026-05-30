package com.crm.repository;

import com.crm.entity.CarrierUserBinding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CarrierUserBindingRepository extends JpaRepository<CarrierUserBinding, Long> {

    boolean existsByPoolIdAndUserId(Long poolId, Long userId);

    Optional<CarrierUserBinding> findByPoolIdAndUserId(Long poolId, Long userId);

    List<CarrierUserBinding> findByUserIdOrderByIdAsc(Long userId);

    List<CarrierUserBinding> findByPoolIdOrderByIdAsc(Long poolId);

    long countByUserId(Long userId);

    long countByPoolId(Long poolId);

    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("DELETE FROM CarrierUserBinding b WHERE b.userId = :userId")
    int deleteAllByUserId(@Param("userId") Long userId);

    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("DELETE FROM CarrierUserBinding b WHERE b.poolId = :poolId")
    int deleteAllByPoolId(@Param("poolId") Long poolId);

    /**
     * Delete every binding belonging to users in {@code folder}. Pass null to mean
     * '未設定' (FOLDER IS NULL). Single DELETE with a correlated subquery — no
     * entity load, no JPA cascade, fast even at 100K rows.
     */
    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query(value =
            "DELETE FROM CARRIER_USER_BINDING " +
            "WHERE USER_ID IN (" +
            "  SELECT id FROM CRM_USER " +
            "  WHERE (:folder IS NULL AND FOLDER IS NULL) " +
            "     OR (:folder IS NOT NULL AND FOLDER = :folder)" +
            ")",
            nativeQuery = true)
    int deleteByUserFolder(@Param("folder") String folder);

    /** Bulk delete bindings for a fixed list of user IDs. Used by the limited-scope
     *  variant of unbindAllInFolder so the operator can process 1K/2K at a time. */
    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("DELETE FROM CarrierUserBinding b WHERE b.userId IN :userIds")
    int deleteByUserIdIn(@Param("userIds") java.util.List<Long> userIds);

    /** Pool IDs that have zero bindings. */
    @Query("SELECT p.id FROM CarrierAddressPool p " +
           "WHERE p.isActive = true AND " +
           "NOT EXISTS (SELECT 1 FROM CarrierUserBinding b WHERE b.poolId = p.id)")
    List<Long> findActivePoolsWithNoBindings();

    @Query("SELECT COUNT(p) FROM CarrierAddressPool p " +
           "WHERE p.isActive = true AND " +
           "NOT EXISTS (SELECT 1 FROM CarrierUserBinding b WHERE b.poolId = p.id)")
    long countActivePoolsWithNoBindings();

    /** Bindings that were created on or before the cutoff (used by the auto-expire scheduler).
     *  Self-transactional: caller is ScheduledTaskService.purgeExpiredBindings which is NOT
     *  inside a Spring tx, and without this annotation the cron-fired delete throws
     *  TransactionRequiredException the moment the operator enables binding.auto_expire
     *  (same pattern as the broadcast counter bug fixed 2026-05-29). */
    @Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query("DELETE FROM CarrierUserBinding b WHERE b.createdAt <= :cutoff")
    int deleteOlderThan(@Param("cutoff") java.time.LocalDateTime cutoff);

    @Query("SELECT COUNT(b) FROM CarrierUserBinding b WHERE b.createdAt <= :cutoff")
    long countOlderThan(@Param("cutoff") java.time.LocalDateTime cutoff);
}
