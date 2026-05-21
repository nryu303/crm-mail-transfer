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

    public ScheduledTaskService(MessageRepository messageRepository,
                                CarrierAddressPoolRepository poolRepository,
                                MessageService messageService,
                                com.crm.repository.CrmSettingRepository settingRepo,
                                com.crm.repository.CarrierUserBindingRepository bindingRepo,
                                DomainSettingService domainSettings,
                                com.crm.repository.BroadcastRepository broadcastRepo) {
        this.messageRepository = messageRepository;
        this.poolRepository = poolRepository;
        this.messageService = messageService;
        this.settingRepo = settingRepo;
        this.bindingRepo = bindingRepo;
        this.domainSettings = domainSettings;
        this.broadcastRepo = broadcastRepo;
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
        log.info("Scheduler: {} queued messages due, dispatching", due.size());
        // Cache broadcast cancel-state lookups per tick: a 5K-message batch hits the same
        // broadcast row 5K times, so we want one DB read per broadcast, not per message.
        java.util.Map<Long, Boolean> broadcastCancelled = new java.util.HashMap<>();
        for (Message msg : due) {
            try {
                // CRITICAL race-condition gate: re-fetch the row from DB before each send so
                // a cancel that arrived AFTER findDueForDispatch loaded this batch into memory
                // catches us before we dispatch. Without this re-fetch, an operator who hit
                // キャンセル at "送信済み 167" still saw 645+ deliveries continue to the relay —
                // the dispatcher was iterating an in-memory list of QUEUED snapshots that had
                // already been flipped to CANCELLED in the DB.
                // Falls back to the in-memory snapshot if findById can't see the row (Mockito
                // tests don't stub findById, and an actual missing row is a different
                // failure that the downstream save() will surface).
                java.util.Optional<Message> freshOpt = messageRepository.findById(msg.getId());
                Message effective = freshOpt.orElse(msg);
                if (!Message.STATUS_QUEUED.equals(effective.getStatus())) {
                    continue;
                }
                msg = effective;

                // Broadcast-level cancel: the parent's status flipped to CANCELLED but the
                // bulk MESSAGE update inside cancel() may not have reached this row yet (e.g.
                // the dispatcher began a 5K-row tick at the same instant the cancel started).
                // Detect it, flip this message ourselves, and skip the send.
                if (msg.getBroadcastId() != null) {
                    Boolean isCancelled = broadcastCancelled.get(msg.getBroadcastId());
                    if (isCancelled == null) {
                        com.crm.entity.Broadcast b = broadcastRepo.findById(msg.getBroadcastId()).orElse(null);
                        isCancelled = (b != null && com.crm.entity.Broadcast.STATUS_CANCELLED.equals(b.getStatus()));
                        broadcastCancelled.put(msg.getBroadcastId(), isCancelled);
                    }
                    if (isCancelled) {
                        msg.setStatus(Message.STATUS_CANCELLED);
                        msg.setErrorMessage("broadcast cancelled while message was in dispatcher batch");
                        messageRepository.save(msg);
                        continue;
                    }
                }

                // Scheduled-broadcast exclusion: if this row belongs to a broadcast and the
                // user has ALREADY received some other OUT message between the broadcast
                // creation time and this message's scheduled time, drop this row. The operator
                // explicitly requested this so a manually-sent intermediate message removes
                // the user from the still-pending scheduled batch.
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
                            log.info("Scheduler: excluded msg {} (user {} got {} other sends after broadcast {})",
                                    msg.getId(), msg.getUserId(), otherSends, msg.getBroadcastId());
                            continue;
                        }
                    }
                }

                // Pool lookup is best-effort. As of the 2026-05 dispatcher refactor, outbound
                // transport is chosen by the active RELAY_SERVER row, so a missing pool entry
                // (e.g. the FROM uses the avu74g.jp base domain rather than a carrier address)
                // is no longer a fatal condition.
                CarrierAddressPool pool = poolRepository.findByAddress(msg.getFromAddress()).orElse(null);
                messageService.sendNow(msg, pool);
            } catch (Exception e) {
                log.warn("Scheduler: dispatch failed for message {}: {}", msg.getId(), e.toString());
                msg.setStatus(Message.STATUS_FAILED);
                msg.setErrorMessage("scheduler error: " + e.toString());
                messageRepository.save(msg);
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
