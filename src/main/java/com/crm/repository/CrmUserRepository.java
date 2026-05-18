package com.crm.repository;

import com.crm.entity.CrmUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CrmUserRepository extends JpaRepository<CrmUser, Long>, JpaSpecificationExecutor<CrmUser> {
    Optional<CrmUser> findByEmail(String email);
    boolean existsByEmail(String email);
    boolean existsByLoginId(String loginId);
    long countByStatus(String status);

    /** Distinct sorted list of the "@domain" part of all user emails — for the search dropdown. */
    @Query(value = "SELECT DISTINCT SUBSTRING_INDEX(EMAIL, '@', -1) AS d " +
                   "FROM CRM_USER WHERE EMAIL LIKE '%@%' ORDER BY d", nativeQuery = true)
    List<String> findDistinctEmailDomains();

    /** All user IDs ordered by creation asc — used to compute display rank (1..N). */
    @Query("SELECT u.id FROM CrmUser u ORDER BY u.createdAt ASC, u.id ASC")
    List<Long> findAllIdsByCreatedAsc();

    long countByAdCode(String adCode);

    /** Bulk-clear ad_code on every user that referenced a now-deleted AD_CODE row. */
    @org.springframework.data.jpa.repository.Modifying
    @Query(value = "UPDATE CRM_USER SET AD_CODE = NULL WHERE AD_CODE = :code", nativeQuery = true)
    int clearAdCodeForCode(@org.springframework.data.repository.query.Param("code") String code);

    /**
     * Bulk-move every user currently in {@code fromFolder} into {@code toFolder}.
     * Pass {@code null} for either to mean '未設定' (FOLDER IS NULL). One UPDATE
     * statement — no paging, no entity load — so 100K-row moves stay fast even
     * on the 4GB tier.
     */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @Query(value =
            "UPDATE CRM_USER " +
            "SET FOLDER = :toFolder, UPDATED_AT = NOW() " +
            "WHERE (:fromFolder IS NULL AND FOLDER IS NULL) " +
            "   OR (:fromFolder IS NOT NULL AND FOLDER = :fromFolder)",
            nativeQuery = true)
    int bulkUpdateFolderByFolder(@org.springframework.data.repository.query.Param("fromFolder") String fromFolder,
                                 @org.springframework.data.repository.query.Param("toFolder") String toFolder);

    /** Per-day signup counts for an ad code, returned as [yyyy-MM-dd, count] rows. */
    @Query(value = "SELECT DATE_FORMAT(CREATED_AT, '%Y-%m-%d') AS d, COUNT(*) " +
                   "FROM CRM_USER WHERE AD_CODE = :code AND CREATED_AT BETWEEN :start AND :end " +
                   "GROUP BY d ORDER BY d", nativeQuery = true)
    List<Object[]> countByAdCodeGroupedByDay(@org.springframework.data.repository.query.Param("code") String code,
                                              @org.springframework.data.repository.query.Param("start") java.time.LocalDateTime start,
                                              @org.springframework.data.repository.query.Param("end") java.time.LocalDateTime end);

    /** Per-month signup counts for an ad code, returned as [yyyy-MM, count] rows. */
    @Query(value = "SELECT DATE_FORMAT(CREATED_AT, '%Y-%m') AS m, COUNT(*) " +
                   "FROM CRM_USER WHERE AD_CODE = :code AND CREATED_AT BETWEEN :start AND :end " +
                   "GROUP BY m ORDER BY m", nativeQuery = true)
    List<Object[]> countByAdCodeGroupedByMonth(@org.springframework.data.repository.query.Param("code") String code,
                                                @org.springframework.data.repository.query.Param("start") java.time.LocalDateTime start,
                                                @org.springframework.data.repository.query.Param("end") java.time.LocalDateTime end);

    /**
     * Per-(ad_code, gender) signup count within a window.
     * Returned as [ad_code, gender ('M'|'F'|null), count].
     * Used by the agency dashboard to render the 男性/女性/合計 columns.
     */
    @Query(value = "SELECT AD_CODE, GENDER, COUNT(*) FROM CRM_USER " +
                   "WHERE AD_CODE IS NOT NULL AND CREATED_AT BETWEEN :start AND :end " +
                   "GROUP BY AD_CODE, GENDER", nativeQuery = true)
    List<Object[]> countByAdCodeAndGenderBetween(@org.springframework.data.repository.query.Param("start") java.time.LocalDateTime start,
                                                  @org.springframework.data.repository.query.Param("end") java.time.LocalDateTime end);

    /** Per-(day, gender) signup count for one ad_code within a window — drill-down view. */
    @Query(value = "SELECT DATE_FORMAT(CREATED_AT, '%Y-%m-%d'), GENDER, COUNT(*) FROM CRM_USER " +
                   "WHERE AD_CODE = :code AND CREATED_AT BETWEEN :start AND :end " +
                   "GROUP BY DATE_FORMAT(CREATED_AT, '%Y-%m-%d'), GENDER", nativeQuery = true)
    List<Object[]> countByDayAndGenderForCode(@org.springframework.data.repository.query.Param("code") String code,
                                               @org.springframework.data.repository.query.Param("start") java.time.LocalDateTime start,
                                               @org.springframework.data.repository.query.Param("end") java.time.LocalDateTime end);
}
