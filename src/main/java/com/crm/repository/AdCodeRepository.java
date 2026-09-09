package com.crm.repository;

import com.crm.entity.AdCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AdCodeRepository extends JpaRepository<AdCode, Long> {

    Optional<AdCode> findByCode(String code);

    List<AdCode> findAllByOrderByCreatedAtDesc();

    /** Alphabetical (by display name) — used for the user-list filter dropdown so operators
     *  can find a code without scanning creation-order chaos. */
    List<AdCode> findAllByOrderByNameAsc();

    boolean existsByCode(String code);
}
