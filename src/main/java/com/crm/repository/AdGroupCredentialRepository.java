package com.crm.repository;

import com.crm.entity.AdGroupCredential;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AdGroupCredentialRepository extends JpaRepository<AdGroupCredential, Long> {

    Optional<AdGroupCredential> findByGroupName(String groupName);

    Optional<AdGroupCredential> findByAuthUser(String authUser);

    boolean existsByAuthUser(String authUser);
}
