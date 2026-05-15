package com.crm.repository;

import com.crm.entity.InboundMailLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InboundMailLogRepository extends JpaRepository<InboundMailLog, Long> {
    Page<InboundMailLog> findAllByOrderByCreatedAtDesc(Pageable pageable);
    Page<InboundMailLog> findByIsRejectedTrueOrderByCreatedAtDesc(Pageable pageable);
}
