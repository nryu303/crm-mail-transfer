package com.crm.repository;

import com.crm.entity.RelayServer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RelayServerRepository extends JpaRepository<RelayServer, Long> {
    List<RelayServer> findAllByOrderByNameAsc();
    boolean existsByName(String name);
}
