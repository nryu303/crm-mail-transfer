package com.crm.repository;

import com.crm.entity.Broadcast;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BroadcastRepository extends JpaRepository<Broadcast, Long> {
    Page<Broadcast> findAllByOrderByCreatedAtDesc(Pageable pageable);
    java.util.List<Broadcast> findByStatusInOrderByCreatedAtDesc(java.util.Collection<String> statuses);

    /** Atomic increment of counters — avoids read-modify-write races when multiple senders finalise concurrently. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(
            "UPDATE Broadcast b SET b.sentCount = b.sentCount + :sentInc, b.failedCount = b.failedCount + :failInc, b.updatedAt = :now " +
            "WHERE b.id = :id AND b.status <> 'CANCELLED'")
    int incrementCounters(@org.springframework.data.repository.query.Param("id") Long id,
                          @org.springframework.data.repository.query.Param("sentInc") int sentInc,
                          @org.springframework.data.repository.query.Param("failInc") int failInc,
                          @org.springframework.data.repository.query.Param("now") java.time.LocalDateTime now);

    /** Claim the broadcast as COMPLETED when all messages have finalised. Atomic. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(
            "UPDATE Broadcast b SET b.status = 'COMPLETED', b.updatedAt = :now " +
            "WHERE b.id = :id AND b.status NOT IN ('COMPLETED','CANCELLED') " +
            "AND (b.sentCount + b.failedCount) >= b.totalCount")
    int markCompletedIfDone(@org.springframework.data.repository.query.Param("id") Long id,
                            @org.springframework.data.repository.query.Param("now") java.time.LocalDateTime now);
}
