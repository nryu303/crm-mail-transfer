package com.crm.repository;

import com.crm.entity.DiffScheduleStep;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface DiffScheduleStepRepository extends JpaRepository<DiffScheduleStep, Long> {

    List<DiffScheduleStep> findByDiffScheduleIdOrderByStepOrderAsc(Long diffScheduleId);

    /** Dispatcher poll — same "due" shape as MessageRepository.findDueForDispatch. */
    @Query("SELECT s FROM DiffScheduleStep s WHERE s.status = :status AND s.scheduledFor <= :now ORDER BY s.scheduledFor ASC")
    List<DiffScheduleStep> findDueForExecution(@Param("status") String status, @Param("now") LocalDateTime now);

    /** Pending-list view: every still-PENDING step, joined to its parent for display, newest schedule first. */
    @Query("SELECT s FROM DiffScheduleStep s WHERE s.status = 'PENDING' ORDER BY s.diffScheduleId DESC, s.stepOrder ASC")
    List<DiffScheduleStep> findAllPending();

    List<DiffScheduleStep> findByStatusAndDiffScheduleId(String status, Long diffScheduleId);

    /** History filter — any combination of status/targetType/definition may be null (= no filter). */
    @Query("SELECT s FROM DiffScheduleStep s JOIN DiffSchedule ds ON s.diffScheduleId = ds.id WHERE " +
           "(:status IS NULL OR s.status = :status) AND " +
           "(:targetType IS NULL OR ds.targetType = :targetType) AND " +
           "(:diffDefinitionId IS NULL OR ds.diffDefinitionId = :diffDefinitionId) " +
           "ORDER BY ds.setAt DESC, s.stepOrder ASC")
    Page<DiffScheduleStep> search(@Param("status") String status,
                                   @Param("targetType") String targetType,
                                   @Param("diffDefinitionId") Long diffDefinitionId,
                                   Pageable pageable);

    /** Bulk-cancel-by-dimension support: PENDING steps whose parent DiffSchedule matches one target dimension. */
    @Query("SELECT s FROM DiffScheduleStep s JOIN DiffSchedule ds ON s.diffScheduleId = ds.id " +
           "WHERE s.status = 'PENDING' AND ds.targetType = :targetType AND ds.targetValue = :targetValue")
    List<DiffScheduleStep> findPendingByTarget(@Param("targetType") String targetType, @Param("targetValue") String targetValue);

    @Query("SELECT s FROM DiffScheduleStep s JOIN DiffSchedule ds ON s.diffScheduleId = ds.id " +
           "WHERE s.status = 'PENDING' AND ds.diffDefinitionId = :diffDefinitionId")
    List<DiffScheduleStep> findPendingByDiffDefinition(@Param("diffDefinitionId") Long diffDefinitionId);

    /** Daily auto-purge support: drop finished (non-PENDING) step rows older than a cutoff,
     *  keyed off updatedAt since that's set on every status transition (executed/cancelled/failed). */
    long deleteByStatusNotAndUpdatedAtBefore(String notStatus, LocalDateTime cutoff);

    /** Dashboard 予約 (reservation) graph support — mirrors
     *  MessageRepository.countQueuedByDirectionAndChannelBetweenEffective's bucket shape so a
     *  pending diff MESSAGE step's send counts toward the same hourly reservation bar as a
     *  normal scheduled broadcast, even though it won't materialise a real Message row until
     *  it actually fires. HTML_SWITCH steps never count here — they don't send anything. */
    @Query("SELECT COUNT(s) FROM DiffScheduleStep s WHERE s.status = 'PENDING' AND s.stepType = 'MESSAGE' " +
           "AND s.channel = :channel AND s.scheduledFor >= :from AND s.scheduledFor < :to")
    long countPendingMessageStepsByChannelBetween(@Param("channel") String channel,
                                                   @Param("from") LocalDateTime from,
                                                   @Param("to") LocalDateTime to);
}
