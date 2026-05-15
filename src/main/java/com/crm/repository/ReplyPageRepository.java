package com.crm.repository;

import com.crm.entity.ReplyPage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReplyPageRepository extends JpaRepository<ReplyPage, Long> {
    Optional<ReplyPage> findByToken(String token);
    boolean existsByToken(String token);
}
