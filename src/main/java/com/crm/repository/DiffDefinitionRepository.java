package com.crm.repository;

import com.crm.entity.DiffDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DiffDefinitionRepository extends JpaRepository<DiffDefinition, Long> {
    List<DiffDefinition> findAllByOrderByDisplayOrderAscIdAsc();
}
