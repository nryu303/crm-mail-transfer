package com.crm.repository;

import com.crm.entity.FolderAutoMoveRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FolderAutoMoveRuleRepository extends JpaRepository<FolderAutoMoveRule, Long> {
    List<FolderAutoMoveRule> findByEnabledTrue();
    List<FolderAutoMoveRule> findAllByOrderByMoveTimeAsc();
}
