package com.crm.repository;

import com.crm.entity.SharedMemo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SharedMemoRepository extends JpaRepository<SharedMemo, Long> {
    List<SharedMemo> findAllByOrderByUpdatedAtDesc();
}
