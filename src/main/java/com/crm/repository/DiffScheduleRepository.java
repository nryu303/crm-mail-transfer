package com.crm.repository;

import com.crm.entity.DiffSchedule;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface DiffScheduleRepository extends JpaRepository<DiffSchedule, Long> {

    List<DiffSchedule> findByStatusOrderByScheduledForAsc(String status);

    /** Dispatcher poll — same "due" shape as MessageRepository.findDueForDispatch. */
    @Query("SELECT s FROM DiffSchedule s WHERE s.status = :status AND s.scheduledFor <= :now ORDER BY s.scheduledFor ASC")
    List<DiffSchedule> findDueForExecution(@Param("status") String status, @Param("now") LocalDateTime now);

    /** History filter — any combination of status/targetType/definition may be null (= no filter on that field). */
    @Query("SELECT s FROM DiffSchedule s WHERE " +
           "(:status IS NULL OR s.status = :status) AND " +
           "(:targetType IS NULL OR s.targetType = :targetType) AND " +
           "(:diffDefinitionId IS NULL OR s.diffDefinitionId = :diffDefinitionId) " +
           "ORDER BY s.setAt DESC")
    Page<DiffSchedule> search(@Param("status") String status,
                              @Param("targetType") String targetType,
                              @Param("diffDefinitionId") Long diffDefinitionId,
                              Pageable pageable);

    /** Bulk-cancel-by-dimension support: PENDING rows matching one target dimension. */
    List<DiffSchedule> findByStatusAndTargetTypeAndTargetValue(String status, String targetType, String targetValue);

    List<DiffSchedule> findByStatusAndDiffDefinitionId(String status, Long diffDefinitionId);
}
