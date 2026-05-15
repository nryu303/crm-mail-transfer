package com.crm.repository;

import com.crm.entity.CarrierAddressPool;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CarrierAddressPoolRepository
        extends JpaRepository<CarrierAddressPool, Long>, JpaSpecificationExecutor<CarrierAddressPool> {

    Optional<CarrierAddressPool> findByAddress(String address);

    /**
     * Look up a pool row by the local part of its address only — used by inbound matching
     * when the TO domain was re-written by the FROM domain override.
     * E.g. a reply addressed to {@code rifc6h1c65@avu74g.jp} maps back to the actual pool
     * row {@code rifc6h1c65@docomo.ne.jp}.
     */
    @Query(value = "SELECT * FROM CARRIER_ADDRESS_POOL " +
                   "WHERE LOWER(SUBSTRING_INDEX(ADDRESS, '@', 1)) = LOWER(:localPart) AND IS_ACTIVE = 1 " +
                   "ORDER BY ID ASC LIMIT 1", nativeQuery = true)
    Optional<CarrierAddressPool> findByLocalPart(@Param("localPart") String localPart);

    boolean existsByAddress(String address);

    List<CarrierAddressPool> findByIsActiveTrueOrderByIdAsc();

    @Query("SELECT p FROM CarrierAddressPool p " +
           "WHERE p.carrierCode = :code AND p.isActive = true ORDER BY p.id ASC")
    List<CarrierAddressPool> findActiveByCarrierCode(@Param("code") String carrierCode);

    @Query("SELECT p FROM CarrierAddressPool p " +
           "WHERE p.carrierDomain = :domain AND p.isActive = true ORDER BY p.id ASC")
    List<CarrierAddressPool> findByCarrierDomainAndIsActiveTrue(@Param("domain") String domain);
}
