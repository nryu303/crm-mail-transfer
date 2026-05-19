package com.crm.repository;

import com.crm.entity.HomeHtml;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HomeHtmlRepository extends JpaRepository<HomeHtml, Long> {

    /** Newest first so the management list shows recent variants near the top. */
    List<HomeHtml> findAllByOrderByUpdatedAtDesc();

    Optional<HomeHtml> findFirstByIsActiveTrue();

    /** Used by {@link com.crm.service.HomeHtmlService#activate} to clear other rows in one shot. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(
            "UPDATE HomeHtml h SET h.isActive = false WHERE h.id <> :exceptId AND h.isActive = true")
    int clearActiveExcept(@org.springframework.data.repository.query.Param("exceptId") Long exceptId);
}
