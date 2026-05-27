package com.crm.repository;

import com.crm.entity.InboundMailLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InboundMailLogRepository extends JpaRepository<InboundMailLog, Long> {
    Page<InboundMailLog> findAllByOrderByCreatedAtDesc(Pageable pageable);
    Page<InboundMailLog> findByIsRejectedTrueOrderByCreatedAtDesc(Pageable pageable);

    /** Deferred inbounds waiting for pool re-creation to settle. The scheduler picks these up
     *  every couple of minutes and re-evaluates the pool lookup; rows older than the deferral
     *  expiry are converted to a final REASON_TO_NOT_IN_POOL reject. */
    java.util.List<InboundMailLog> findByRejectReasonAndIsProcessedFalse(String rejectReason);

    /** Delete every rejected row whose reject_reason matches one of {@code reasons}.
     *  Used by the daily inbound-spam purge — these categories have no operational
     *  value (unregistered-sender third-party mail, postmaster bounces, IMAP duplicate
     *  artifacts) and only accumulate raw MIME on disk + buffer-pool pressure. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query(
            "DELETE FROM InboundMailLog l WHERE l.isRejected = true AND l.rejectReason IN :reasons")
    int deleteByRejectReasonIn(
            @org.springframework.data.repository.query.Param("reasons") java.util.List<String> reasons);

    /** Same as deleteByRejectReasonIn but only rows older than the cutoff. Used by the
     *  daily auto-purge so the operator keeps a short audit window (default 7 days). */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Query(
            "DELETE FROM InboundMailLog l WHERE l.isRejected = true " +
            "AND l.rejectReason IN :reasons AND l.createdAt < :cutoff")
    int deleteOldByRejectReasonIn(
            @org.springframework.data.repository.query.Param("reasons") java.util.List<String> reasons,
            @org.springframework.data.repository.query.Param("cutoff") java.time.LocalDateTime cutoff);
}
