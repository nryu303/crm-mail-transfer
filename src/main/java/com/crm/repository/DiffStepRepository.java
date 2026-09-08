package com.crm.repository;

import com.crm.entity.DiffStep;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DiffStepRepository extends JpaRepository<DiffStep, Long> {
    List<DiffStep> findByDiffDefinitionIdOrderByStepOrderAsc(Long diffDefinitionId);
    long countByDiffDefinitionId(Long diffDefinitionId);
    void deleteByDiffDefinitionId(Long diffDefinitionId);
}
