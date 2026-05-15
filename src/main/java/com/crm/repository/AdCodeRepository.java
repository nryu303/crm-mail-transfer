package com.crm.repository;

import com.crm.entity.AdCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AdCodeRepository extends JpaRepository<AdCode, Long> {

    Optional<AdCode> findByCode(String code);

    List<AdCode> findAllByOrderByCreatedAtDesc();

    boolean existsByCode(String code);
}
