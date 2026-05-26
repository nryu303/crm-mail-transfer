package com.crm.service;

import com.crm.entity.CarrierAddressPool;
import com.crm.entity.Message;
import com.crm.repository.CarrierAddressPoolRepository;
import com.crm.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Polls the MESSAGE table for QUEUED rows whose SCHEDULED_AT or NEXT_RETRY_AT has arrived
 * (original schedule + TEMPFAIL retries) and dispatches them through OutboundMailService.
 *
 * Runs every 30 seconds.
 *
 * <p><b>Deployment constraint:</b> this scheduler assumes a single JVM instance. The poll
 * loads rows then calls {@code sendNow()} in-process without claiming them via a conditional
 * UPDATE, so running multiple instances in parallel would cause double-sends.
 * {@link #SCHEDULER_LOCK_KEY} provides a simple best-effort mutex against accidental double
 * deployments on the same DB. For true multi-instance scaling, replace with a conditional
 * {@code UPDATE MESSAGE SET status='DISPATCHING' WHERE id=? AND status='QUEUED'} claim.
 */
@Service
public class ScheduledTaskService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskService.class);

    /** DB-level advisory lock to detect accidental double-deployments. */
    private static final String SCHEDULER_LOCK_KEY = "scheduler.dispatcher.instance";
    /** Stale lock lease in seconds — if a holder didn't refresh in this window we consider it dead. */
    private static final long LOCK_LEASE_SECONDS = 120L;

    /** Per-instance UUID written into the lock row so a restart can reclaim its own lease. */
    private final String instanceId = java.util.UUID.randomUUID().toString();

    private final MessageRepository messageRepository;
    private final CarrierAddressPoolRepository poolRepository;
    private final MessageService messageService;
    private final com.crm.repository.CrmSettingRepository settingRepo;
    private final com.crm.repository.CarrierUserBindingRepository bindingRepo;
    private final DomainSettingService domainSettings;
    private final com.crm.repository.BroadcastRepository broadcastRepo;
    private final FolderSettingService folderSettingService;
    private final FolderRetentionService folderRetentionService;

    public ScheduledTaskService(MessageRepository messageRepository,
                                CarrierAddressPoolRepository poolRepository,
                                MessageService messageService,
                                com.crm.repository.CrmSettingRepository settingRepo,
                                com.crm.repository.CarrierUserBindingRepository bindingRepo,
                                DomainSettingService domainSettings,
                                com.crm.repository.BroadcastRepository broadcastRepo,
                                FolderSettingService folderSettingService,
                                FolderRetentionService folderRetentionService) {
        this.messageRepository = messageRepository;
        this.poolRepository = poolRepository;
        this.messageService = messageService;
        this.settingRepo = settingRepo;
        this.bindingRepo = bindingRepo;
        this.domainSettings = domainSettings;
        this.broadcastRepo = broadcastRepo;
        this.folderSettingService = folderSettingService;
        this.folderRetentionService = folderRetentionService;
    }

    /**
     * Daily at 00:00 — for every configured folder with retention_days > 0, drop MESSAGE
     * rows older than that many days for users currently in the folder. Operator-requested
     * 2026-05-26 to keep DB and memory pressure under control.
     *
     * <p>Carrier bindings are NOT auto-purged here — that's a heavier consequence (the
     * user can no longer send/receive) and stays on the manual button only.
     */
    @Scheduled(cron = "0 0 0 * * *")
    public void purgePerFolderRetention() {
        if (!acquireOrRefreshLock()) return;
        java.util.List<String> folders = folderSettingService.listFolders();
        int totalDeleted = 0;
        for (String folder : folders) {
            int days = folderRetentionService.getRetentionDays(folder);
            if (days <= 0) continue;
            try {
                int n = folderRetentionService.purgeOldMessagesForFolder(folder, days);
                totalDeleted += n;
            } catch (Exception e) {
                log.warn("Retention purge failed for folder {}: {}", folder, e.toString());
            }
        }
        if (totalDeleted > 0) {
            log.info("Daily folder-retention purge: {} MESSAGE rows deleted across {} folders",
                    totalDeleted, folders.size());
        }
    }

    /**
     * Daily at 03:00 — purge CARRIER_USER_BINDING rows older than the configured cutoff.
     * Off by default; admins enable + set the day count from the settings page. The cron is
     * fixed (not configurable) because the only knob that matters is "how many days to keep".
     */
    @Scheduled(cron = "0 0 3 * * *")
    public void purgeExpiredBindings() {
        if (!domainSettings.isBindingExpireEnabled()) {
            log.debug("Binding auto-expire is disabled; skipping daily purge");
            return;
        }
        int days = domainSettings.getBindingExpireDays();
        java.time.LocalDateTime cutoff = java.time.LocalDateTime.now().minusDays(days);
        long willDelete = bindingRepo.countOlderThan(cutoff);
        if (willDelete == 0) {
            log.info("Binding auto-expire: 0 rows older than {} days, nothing to do", days);
            return;
        }
        int deleted = bindingRepo.deleteOlderThan(cutoff);
        log.info("Binding auto-expire: removed {} rows older than {} days (cutoff={})",
                deleted, days, cutoff);
    }

    /**
     * Worker pool used to parallelise the per-tick dispatch loop. Each task represents one
     * QUEUED message — the worker re-fetches it, runs the cancel/exclusion gates, then calls
     * {@code messageService.sendNow}. Sized via {@code app.scheduler.parallel-workers}
     * (default 8). 8 workers × ~700ms per send ≈ 700 msgs/min, well above the 600/min cap.
     *
     * <p>Why parallel? Each send opens an SSH + HTTP round-trip to the relay (~700ms) so a
     * sequential loop tops out at ~85 msgs/min — far below the configured rate cap. With 8
     * workers we restore the 600/min ceiling without changing the relay or the rate logic.
     *
     * <p>Bounded by:
     *   * HikariCP pool size (default 10; 8 workers + main + handlers fits)
     *   * Relay capacity per host (the relays each spawn their own short-lived ssh process
     *     per upload, so 8 concurrent uploads is well within their typical fanout)
     */
    private final int parallelWorkers = Integer.parseInt(
            System.getProperty("app.scheduler.parallel-workers", "8"));
    private final java.util.concurrent.ExecutorService workerPool =
            java.util.concurrent.Executors.newFixedThreadPool(parallelWorkers, r -> {
                Thread t = new Thread(r, "crm-dispatch");
                t.setDaemon(true);
                return t;
            });

    /**
     * Every 5 minutes — find broadcasts stuck in SCHEDULED/SENDING with no remaining
     * QUEUED messages and flip them to COMPLETED. Belt-and-braces for the (now-fixed)
     * exclusion path that historically forgot to bump the parent counters; also covers
     * any other race that could leave a parent row in a non-terminal status.
     */
    @Scheduled(fixedRateString = "${app.scheduler.stuck-broadcast-sweeper-ms:300000}",
               initialDelayString = "${app.scheduler.stuck-broadcast-sweeper-init-ms:60000}")
    public void sweepStuckBroadcasts() {
        if (!acquireOrRefreshLock()) return;
        java.util.List<com.crm.entity.Broadcast> stuck = broadcastRepo.findByStatusInOrderByCreatedAtDesc(
                java.util.Arrays.asList(com.crm.entity.Broadcast.STATUS_SCHEDULED,
                                        com.crm.entity.Broadcast.STATUS_SENDING));
        int flipped = 0;
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        for (com.crm.entity.Broadcast b : stuck) {
            long remaining = messageRepository.countByBroadcastIdAndStatus(b.getId(), Message.STATUS_QUEUED);
            if (remaining > 0) continue; // still actively sending or scheduled
            // Idempotent flip — same predicate as markCompletedIfDone but doesn't require
            // sent+failed to equal total (the operator-cancelled cases also land here).
            int n = broadcastRepo.flipToCompletedIfNoQueued(b.getId(), now);
            if (n > 0) flipped++;
        }
        if (flipped > 0) {
            log.info("Sweeper: flipped {} stuck broadcast(s) to COMPLETED", flipped);
        }
    }

    @Scheduled(fixedRateString = "${app.scheduler.queued-poll-rate-ms:30000}",
               initialDelayString = "${app.scheduler.queued-poll-initial-delay-ms:15000}")
    public void dispatchQueued() {
        if (!acquireOrRefreshLock()) {
            log.debug("Scheduler: another instance holds the dispatcher lock; skipping this tick");
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        List<Message> due = messageRepository.findDueForDispatch(Message.STATUS_QUEUED, now);
        if (due.isEmpty()) return;
        log.info("Scheduler: {} queued messages due, dispatching across {} workers", due.size(), parallelWorkers);
        // Cache broadcast cancel-state lookups per tick: a 5K-message batch hits the same
        // broadcast row 5K times, so we want one DB read per broadcast, not per message.
        // Concurrent because the parallel workers race on it.
        final java.util.concurrent.ConcurrentMap<Long, Boolean> broadcastCancelled =
                new java.util.concurrent.ConcurrentHashMap<>();
        java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>(due.size());
        for (final Message initialMsg : due) {
            futures.add(workerPool.submit(() -> dispatchOne(initialMsg, broadcastCancelled)));
        }
        // Wait for every task in this tick to finish before returning. Bounds tick wall-time
        // to ~ (batch_size / workers) × per_send_latency, which keeps backpressure visible
        // (if a tick can't finish in 30s the next @Scheduled fire is queued by Spring's
        // single-threaded TaskScheduler — we don't double-dispatch).
        for (java.util.concurrent.Future<?> f : futures) {
            try { f.get(); }
            catch (Exception e) { log.warn("Scheduler: worker task failed: {}", e.toString()); }
        }
    }

    /** One message → re-fetch + gate + send. Runs on the worker pool. */
    private void dispatchOne(Message msg,
                             java.util.concurrent.ConcurrentMap<Long, Boolean> broadcastCancelled) {
        try {
            // CRITICAL race-condition gate: re-fetch the row from DB before each send so
            // a cancel that arrived AFTER findDueForDispatch loaded this batch into memory
            // catches us before we dispatch. Without this re-fetch, an operator who hit
            // キャンセル at "送信済み 167" still saw 645+ deliveries continue to the relay —
            // the dispatcher was iterating an in-memory list of QUEUED snapshots that had
            // already been flipped to CANCELLED in the DB.
            java.util.Optional<Message> freshOpt = messageRepository.findById(msg.getId());
            Message effective = freshOpt.orElse(msg);
            if (!Message.STATUS_QUEUED.equals(effective.getStatus())) {
                return;
            }
            msg = effective;

            // Broadcast-level cancel: the parent's status flipped to CANCELLED but the
            // bulk MESSAGE update inside cancel() may not have reached this row yet.
            if (msg.getBroadcastId() != null) {
                Boolean isCancelled = broadcastCancelled.computeIfAbsent(msg.getBroadcastId(), bid -> {
                    com.crm.entity.Broadcast b = broadcastRepo.findById(bid).orElse(null);
                    return (b != null && com.crm.entity.Broadcast.STATUS_CANCELLED.equals(b.getStatus()));
                });
                if (Boolean.TRUE.equals(isCancelled)) {
                    msg.setStatus(Message.STATUS_CANCELLED);
                    msg.setErrorMessage("broadcast cancelled while message was in dispatcher batch");
                    messageRepository.save(msg);
                    return;
                }
            }

            // Scheduled-broadcast exclusion: if this row belongs to a broadcast and the
            // user has ALREADY received some other OUT message between the broadcast
            // creation time and this message's scheduled time, drop this row.
            if (msg.getBroadcastId() != null && msg.getScheduledAt() != null
                    && msg.getScheduledAt().isAfter(msg.getCreatedAt())) {
                com.crm.entity.Broadcast b = broadcastRepo.findById(msg.getBroadcastId()).orElse(null);
                if (b != null && b.getCreatedAt() != null) {
                    long otherSends = messageRepository.countOutboundFinalisedSince(
                            msg.getUserId(), b.getCreatedAt(), msg.getId());
                    if (otherSends > 0) {
                        msg.setStatus(Message.STATUS_CANCELLED);
                        msg.setErrorMessage("excluded: user received " + otherSends
                                + " other send(s) after broadcast was scheduled");
                        messageRepository.save(msg);
                        // Count the exclusion toward failed_count so the parent broadcast's
                        // markCompletedIfDone gate fires. Without this the broadcast stays
                        // SCHEDULED/SENDING forever even when every message has finalised
                        // (broadcast 108 / 121 hit this on 2026-05-23/24).
                        java.time.LocalDateTime now = java.time.LocalDateTime.now();
                        broadcastRepo.incrementCounters(msg.getBroadcastId(), 0, 1, now);
                        broadcastRepo.markCompletedIfDone(msg.getBroadcastId(), now);
                        log.info("Scheduler: excluded msg {} (user {} got {} other sends after broadcast {})",
                                msg.getId(), msg.getUserId(), otherSends, msg.getBroadcastId());
                        return;
                    }
                }
            }

            CarrierAddressPool pool = poolRepository.findByAddress(msg.getFromAddress()).orElse(null);
            messageService.sendNow(msg, pool);
        } catch (Exception e) {
            log.warn("Scheduler: dispatch failed for message {}: {}", msg.getId(), e.toString());
            try {
                msg.setStatus(Message.STATUS_FAILED);
                msg.setErrorMessage("scheduler error: " + e.toString());
                messageRepository.save(msg);
            } catch (Exception inner) {
                log.warn("Scheduler: failed to record FAILED status for {}: {}", msg.getId(), inner.toString());
            }
        }
    }

    /**
     * Best-effort advisory lock via CRM_SETTING. The value is "{instanceId}|{epochSeconds}".
     * Returns true if we own the lock now (either we refreshed our own lease, or the previous
     * holder's lease expired and we took over). Not a hard guarantee under race, but protects
     * against accidental double-deployment.
     */
    private boolean acquireOrRefreshLock() {
        try {
            long nowSec = System.currentTimeMillis() / 1000L;
            java.util.Optional<com.crm.entity.CrmSetting> opt = settingRepo.findBySettingKey(SCHEDULER_LOCK_KEY);
            com.crm.entity.CrmSetting s = opt.orElseGet(() -> {
                com.crm.entity.CrmSetting n = new com.crm.entity.CrmSetting();
                n.setSettingKey(SCHEDULER_LOCK_KEY);
                n.setDescription("Scheduler dispatcher lease — do not edit");
                return n;
            });
            String cur = s.getSettingValue();
            if (cur != null && !cur.isEmpty()) {
                int pipe = cur.indexOf('|');
                if (pipe > 0) {
                    String holder = cur.substring(0, pipe);
                    long leaseSec;
                    try { leaseSec = Long.parseLong(cur.substring(pipe + 1)); }
                    catch (NumberFormatException e) { leaseSec = 0; }
                    if (!holder.equals(instanceId) && (nowSec - leaseSec) < LOCK_LEASE_SECONDS) {
                        return false; // another instance holds a live lease
                    }
                }
            }
            s.setSettingValue(instanceId + "|" + nowSec);
            s.setUpdatedAt(LocalDateTime.now());
            settingRepo.save(s);
            return true;
        } catch (Exception e) {
            // Fail-open: if the DB hiccups, keep dispatching rather than silently stopping.
            log.warn("Scheduler: lock check failed — proceeding without lock: {}", e.toString());
            return true;
        }
    }
}
