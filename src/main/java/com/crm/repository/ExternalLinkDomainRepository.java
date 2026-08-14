package com.crm.repository;

import com.crm.entity.ExternalLinkDomain;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExternalLinkDomainRepository extends JpaRepository<ExternalLinkDomain, Long> {
    List<ExternalLinkDomain> findAllByOrderByDomainUrlAsc();
    boolean existsByDomainUrl(String domainUrl);
    Optional<ExternalLinkDomain> findFirstByIsActiveTrue();

    /** DOMAIN_URL is stored with scheme (https://host); match the bare host regardless of
     *  scheme/trailing-slash so a click's Host header can be resolved back to its row. */
    List<ExternalLinkDomain> findByDomainUrlContainingIgnoreCase(String host);
}
