package com.crm.service;

import com.crm.entity.CarrierAddressPool;
import com.crm.entity.CarrierUserBinding;
import com.crm.entity.CrmUser;
import com.crm.repository.CarrierAddressPoolRepository;
import com.crm.repository.CarrierUserBindingRepository;
import com.crm.repository.CrmUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * M:N binding between CRM users and carrier pool addresses.
 *
 * Since AMG rotates through its registered addresses at send time and any of them
 * may be used as the FROM for any user, each user is typically bound to every
 * active pool entry. The binding is what lets inbound routing know "a reply from
 * user X arriving at pool address Y belongs on X's thread".
 *
 * Source of truth: CARRIER_USER_BINDING (pool_id, user_id) with UNIQUE(pool_id, user_id).
 */
@Service
public class CarrierBindingService {

    private final CarrierAddressPoolRepository poolRepository;
    private final CarrierUserBindingRepository bindingRepository;
    private final CrmUserRepository userRepository;

    public CarrierBindingService(CarrierAddressPoolRepository poolRepository,
                                 CarrierUserBindingRepository bindingRepository,
                                 CrmUserRepository userRepository) {
        this.poolRepository = poolRepository;
        this.bindingRepository = bindingRepository;
        this.userRepository = userRepository;
    }

    /** Bind every active pool entry to the user. Returns count of newly-created bindings. */
    @Transactional
    public int bindAllAvailable(Long userId) {
        requireUser(userId);
        List<CarrierAddressPool> all = poolRepository.findByIsActiveTrueOrderByIdAsc();
        int created = 0;
        for (CarrierAddressPool p : all) {
            if (!bindingRepository.existsByPoolIdAndUserId(p.getId(), userId)) {
                bindingRepository.save(new CarrierUserBinding(p.getId(), userId));
                created++;
            }
        }
        return created;
    }

    /** All active pool addresses, ordered by ID. Used by the user-list bulk-bind dropdown. */
    public java.util.List<CarrierAddressPool> listActivePool() {
        return poolRepository.findByIsActiveTrueOrderByIdAsc();
    }

    /** Bind a single specific pool entry to many users in one shot. Returns total bindings created. */
    @Transactional
    public int bindOneToMany(Long poolId, java.util.List<Long> userIds) {
        if (poolId == null || userIds == null || userIds.isEmpty()) return 0;
        if (!poolRepository.existsById(poolId)) return 0;
        int created = 0;
        for (Long uid : userIds) {
            if (uid == null) continue;
            if (!userRepository.existsById(uid)) continue;
            if (!bindingRepository.existsByPoolIdAndUserId(poolId, uid)) {
                bindingRepository.save(new CarrierUserBinding(poolId, uid));
                created++;
            }
        }
        return created;
    }

    /**
     * Delete every carrier binding for every user currently in {@code folder}.
     * When {@code limit} is null or <= 0, all bindings for that folder are removed
     * in one DELETE. When limit is set, the first N user IDs (by id ASC) are
     * pre-fetched and only their bindings are removed — lets the operator unbind
     * in 1K/2K-sized batches.
     */
    @Transactional
    public int unbindAllInFolder(String folder, Integer limit) {
        if (limit == null || limit <= 0) {
            return bindingRepository.deleteByUserFolder(folder);
        }
        java.util.List<Long> ids = (folder == null)
                ? userRepository.findIdsByFolderIsNull()
                : userRepository.findIdsByFolder(folder);
        if (ids.size() > limit) ids = ids.subList(0, limit);
        if (ids.isEmpty()) return 0;
        return bindingRepository.deleteByUserIdIn(ids);
    }

    /** Bind every active pool entry to many users. Used by the user-list bulk action. */
    @Transactional
    public int bindAllAvailableToMany(java.util.List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) return 0;
        int created = 0;
        for (Long uid : userIds) {
            if (uid != null && userRepository.existsById(uid)) {
                created += bindAllAvailable(uid);
            }
        }
        return created;
    }

    /**
     * Bind every active pool entry to EVERY user, processed in chunks so a 15K-user
     * × 15-pool-address job (~225K rows) doesn't blow up the 2GB-RAM JVM in one big
     * transaction. Each chunk runs in its own transaction via bindAllAvailable() —
     * the @Transactional there scopes per-user, so a chunk commit boundary is N users.
     * Updates a shared progress counter so the frontend can poll status.
     */
    public int bindAllAvailableToAllUsers(int chunkSize,
                                          java.util.concurrent.atomic.AtomicLong progress,
                                          java.util.concurrent.atomic.AtomicLong total) {
        java.util.List<Long> ids = userRepository.findAllIdsByCreatedAsc();
        if (ids.isEmpty()) return 0;
        total.set(ids.size());
        int created = 0;
        for (int i = 0; i < ids.size(); i++) {
            Long uid = ids.get(i);
            if (uid != null) {
                try { created += bindAllAvailable(uid); } catch (Exception ignored) {}
            }
            progress.set(i + 1);
            // Yield the JVM/DB between chunks so other requests get scheduled
            if ((i + 1) % chunkSize == 0) {
                try { Thread.sleep(50); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
            }
        }
        return created;
    }

    /** Bind one active pool entry matching the user's carrier domain, if not already bound. */
    @Transactional
    public Optional<CarrierAddressPool> autoBind(CrmUser user) {
        if (user == null || user.getCarrierDomain() == null) return Optional.empty();
        // Find pool entries whose carrier_domain matches the user's domain.
        List<CarrierAddressPool> candidates = poolRepository.findByCarrierDomainAndIsActiveTrue(user.getCarrierDomain());
        for (CarrierAddressPool p : candidates) {
            if (!bindingRepository.existsByPoolIdAndUserId(p.getId(), user.getId())) {
                bindingRepository.save(new CarrierUserBinding(p.getId(), user.getId()));
                return Optional.of(p);
            }
        }
        return Optional.empty();
    }

    /** Bind one specific pool entry to a user (admin action). Returns false if already bound. */
    @Transactional
    public boolean bindSpecific(Long userId, Long poolId) {
        requireUser(userId);
        requirePool(poolId);
        if (bindingRepository.existsByPoolIdAndUserId(poolId, userId)) return false;
        bindingRepository.save(new CarrierUserBinding(poolId, userId));
        return true;
    }

    /** Remove one specific binding. */
    @Transactional
    public boolean unbindOne(Long userId, Long poolId) {
        Optional<CarrierUserBinding> b = bindingRepository.findByPoolIdAndUserId(poolId, userId);
        if (!b.isPresent()) return false;
        bindingRepository.delete(b.get());
        return true;
    }

    /** Remove all bindings for a user. */
    @Transactional
    public int unbindAll(Long userId) {
        return bindingRepository.deleteAllByUserId(userId);
    }

    /**
     * List pool entries bound to a user. Single batched query (no N+1):
     * fetches all bindings, then loads all pool entries in one findAllById call,
     * then re-orders to match binding order.
     */
    public List<CarrierAddressPool> listBoundFor(Long userId) {
        List<CarrierUserBinding> bindings = bindingRepository.findByUserIdOrderByIdAsc(userId);
        if (bindings.isEmpty()) return java.util.Collections.emptyList();

        java.util.List<Long> poolIds = new ArrayList<>(bindings.size());
        for (CarrierUserBinding b : bindings) poolIds.add(b.getPoolId());

        java.util.Map<Long, CarrierAddressPool> byId = new java.util.HashMap<>();
        for (CarrierAddressPool p : poolRepository.findAllById(poolIds)) byId.put(p.getId(), p);

        List<CarrierAddressPool> result = new ArrayList<>(bindings.size());
        for (CarrierUserBinding b : bindings) {
            CarrierAddressPool p = byId.get(b.getPoolId());
            if (p != null) result.add(p);
        }
        return result;
    }

    /** Count of users bound to a specific pool entry (for admin visibility). */
    public long countBoundUsers(Long poolId) {
        return bindingRepository.countByPoolId(poolId);
    }

    /** True if the user has any pool binding at all (used to gate outbound send). */
    public boolean hasAnyBinding(Long userId) {
        return bindingRepository.countByUserId(userId) > 0;
    }

    /** Check a specific (pool, user) pair exists — used by inbound matching. */
    public boolean isBound(Long poolId, Long userId) {
        return bindingRepository.existsByPoolIdAndUserId(poolId, userId);
    }

    /**
     * Get the "first" bound pool entry for a user — used as the FROM candidate when
     * composing outbound mail. The relay/AMG may override this; we just need to pass
     * something with valid SMTP credentials.
     */
    public Optional<CarrierAddressPool> firstBoundFor(Long userId) {
        List<CarrierAddressPool> bound = listBoundFor(userId);
        return bound.isEmpty() ? Optional.empty() : Optional.of(bound.get(0));
    }

    private void requireUser(Long userId) {
        userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("user not found: " + userId));
    }

    private void requirePool(Long poolId) {
        poolRepository.findById(poolId)
                .orElseThrow(() -> new IllegalArgumentException("pool entry not found: " + poolId));
    }
}
