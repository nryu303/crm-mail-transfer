package com.crm.service;

import com.crm.dto.BroadcastForm;
import com.crm.entity.Broadcast;
import com.crm.entity.CarrierAddressPool;
import com.crm.entity.CrmUser;
import com.crm.entity.Message;
import com.crm.repository.BroadcastRepository;
import com.crm.repository.CarrierAddressPoolRepository;
import com.crm.repository.CrmUserRepository;
import com.crm.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.criteria.Predicate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class BroadcastService {

    private static final Logger log = LoggerFactory.getLogger(BroadcastService.class);

    private final BroadcastRepository broadcastRepository;
    private final CrmUserRepository userRepository;
    private final CarrierAddressPoolRepository poolRepository;
    private final CarrierBindingService bindingService;
    private final MessageRepository messageRepository;
    private final PlaceholderService placeholderService;
    private final ReplyPageService replyPageService;

    public BroadcastService(BroadcastRepository broadcastRepository,
                            CrmUserRepository userRepository,
                            CarrierAddressPoolRepository poolRepository,
                            CarrierBindingService bindingService,
                            MessageRepository messageRepository,
                            PlaceholderService placeholderService,
                            ReplyPageService replyPageService) {
        this.broadcastRepository = broadcastRepository;
        this.userRepository = userRepository;
        this.poolRepository = poolRepository;
        this.bindingService = bindingService;
        this.messageRepository = messageRepository;
        this.placeholderService = placeholderService;
        this.replyPageService = replyPageService;
    }

    public Page<Broadcast> list(int page, int size) {
        return broadcastRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size));
    }

    public Optional<Broadcast> findById(Long id) {
        return broadcastRepository.findById(id);
    }

    /**
     * Create a broadcast and pre-materialise per-user MESSAGE rows with staggered SCHEDULED_AT
     * so the existing scheduler naturally throttles by rate-per-minute.
     *
     * Throws {@link NoTargetsException} if there are no deliverable users — rather than
     * silently saving an empty broadcast that looks stuck in SENDING forever.
     */
    @Transactional
    public Broadcast createAndQueue(BroadcastForm form, Long adminUserId) {
        List<CrmUser> targets = findTargetUsers(form);

        // Pre-compute which targets are actually deliverable (have at least one active pool
        // binding AND aren't blocked by an RFC-invalid local-part on a carrier whose SMTP
        // refuses such addresses). Carrier-by-carrier policy:
        //   * docomo.ne.jp — the relay (obob.jar, 2026-05-21 quoteIfDotProblematic patch)
        //     rewraps trailing-/leading-/double-dot local-parts into RFC 5321 quoted-string
        //     form ("foo."@docomo.ne.jp), which docomo's MX accepts. We let these through.
        //   * everywhere else (gmail.com / yahoo.co.jp / icloud.com / au.com / …) — their
        //     MX servers reject dot-issue addresses even in quoted form, so skip pre-dispatch.
        List<CrmUser> deliverable = new java.util.ArrayList<>();
        int unbound = 0, poolMissing = 0;
        java.util.List<Long> unsendableIds = new java.util.ArrayList<>();
        java.util.Map<Long, CarrierAddressPool> userToPool = new java.util.HashMap<>();
        for (CrmUser u : targets) {
            if (u.getAddressInvalidReason() != null && !u.getAddressInvalidReason().isEmpty()
                    && !isDocomoDotIssueRescuable(u.getEmail())) {
                unsendableIds.add(u.getId());
                continue;
            }
            Optional<CarrierAddressPool> pool = bindingService.firstBoundFor(u.getId());
            if (!pool.isPresent()) { unbound++; continue; }
            if (Boolean.FALSE.equals(pool.get().getIsActive())) { poolMissing++; continue; }
            deliverable.add(u);
            userToPool.put(u.getId(), pool.get());
        }

        if (deliverable.isEmpty()) {
            throw new NoTargetsException(
                    "条件に合致し、かつキャリアアドレスが割り当て済みのユーザーが見つかりませんでした。"
                  + " (絞り込みに合致したユーザー: " + targets.size()
                  + "件、うちアドレス形式エラー: " + unsendableIds.size()
                  + "件、キャリアアドレス未割当: " + unbound
                  + "件、プール側で無効: " + poolMissing + "件)");
        }

        Broadcast b = new Broadcast();
        b.setAdminUserId(adminUserId);
        String t = form.getTitle();
        b.setTitle((t == null || t.trim().isEmpty()) ? form.getSubject().trim() : t.trim());
        b.setSubject(form.getSubject());
        b.setBodyText(form.getBody());
        b.setChannel("EMAIL");
        b.setRatePerMinute(form.getRatePerMinute() == null || form.getRatePerMinute() < 1
                ? 60 : form.getRatePerMinute());
        b.setTargetFilter(buildFilterSummary(form, targets.size(), unbound + poolMissing));
        b.setTotalCount(deliverable.size());
        b.setUnsendableCount(unsendableIds.size());
        if (!unsendableIds.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < unsendableIds.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(unsendableIds.get(i));
            }
            b.setUnsendableUserIds(sb.toString());
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime startAt = form.getScheduledAt() != null && form.getScheduledAt().isAfter(now)
                ? form.getScheduledAt() : now;
        b.setScheduledAt(form.getScheduledAt());
        b.setStatus(startAt.isAfter(now) ? Broadcast.STATUS_SCHEDULED : Broadcast.STATUS_SENDING);
        Broadcast saved = broadcastRepository.save(b);

        long intervalMs = 60_000L / b.getRatePerMinute();
        for (int i = 0; i < deliverable.size(); i++) {
            CrmUser user = deliverable.get(i);
            CarrierAddressPool pool = userToPool.get(user.getId());

            LocalDateTime when = startAt.plusNanos(intervalMs * 1_000_000L * i);
            Message m = new Message();
            m.setUserId(user.getId());
            m.setAdminUserId(adminUserId);
            m.setDirection(Message.DIR_OUT);
            m.setChannel(Message.CHANNEL_BROADCAST);
            m.setSubject(placeholderService.substitute(form.getSubject(), user));
            String body = placeholderService.substitute(form.getBody(), user);
            m.setBodyText(body);
            m.setFromAddress(pool.getAddress());
            m.setToAddress(user.getEmail());
            m.setBroadcastId(saved.getId());
            m.setStatus(Message.STATUS_QUEUED);
            m.setScheduledAt(when);
            Message persisted = messageRepository.save(m);

            if (body.contains(MessageService.REPLY_URL_PLACEHOLDER)) {
                String url = replyPageService.createReplyPageFor(persisted);
                persisted.setBodyText(body.replace(MessageService.REPLY_URL_PLACEHOLDER, url));
                messageRepository.save(persisted);
            }
        }
        log.info("Broadcast {} created: {} queued (filter matched {}, skipped invalid-address {}, no-binding {}, pool-inactive {})",
                saved.getId(), saved.getTotalCount(), targets.size(),
                unsendableIds.size(), unbound, poolMissing);
        return saved;
    }

    public static class NoTargetsException extends RuntimeException {
        public NoTargetsException(String msg) { super(msg); }
    }

    /**
     * Called by MessageService.sendNow() (or anywhere that finalises a broadcast-linked MESSAGE)
     * to update the denormalised counters and flip the broadcast to COMPLETED when done.
     */
    @Transactional
    public void reportMessageCompleted(Long broadcastId, boolean success) {
        // Atomic counter bump — safe under concurrent senders (no read-modify-write race).
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        broadcastRepository.incrementCounters(broadcastId, success ? 1 : 0, success ? 0 : 1, now);
        // Separate atomic check-and-flip to COMPLETED; skips if CANCELLED.
        broadcastRepository.markCompletedIfDone(broadcastId, now);
    }

    /**
     * Cancel a broadcast and all remaining QUEUED messages under it. Two-part operation:
     *   1. Flip BROADCAST.status → CANCELLED so the dispatcher's per-tick cache picks it up
     *      and stops dispatching any message in this batch that hasn't been sent yet.
     *   2. Bulk UPDATE all QUEUED messages of this broadcast → CANCELLED in one statement.
     *
     * Step 1 has to happen FIRST so the dispatcher's race-condition gate (see
     * ScheduledTaskService.dispatchQueued) sees the cancelled broadcast even while the bulk
     * MESSAGE update is still in progress. Previously this method did per-row saves which
     * could take many seconds for a 5K-row broadcast — during that window the dispatcher
     * was still picking up rows that were technically queued, and they leaked to the relay.
     */
    @Transactional
    public void cancel(Long broadcastId) {
        Optional<Broadcast> opt = broadcastRepository.findById(broadcastId);
        if (!opt.isPresent()) return;
        Broadcast b = opt.get();
        if (Broadcast.STATUS_COMPLETED.equals(b.getStatus())
                || Broadcast.STATUS_CANCELLED.equals(b.getStatus())) return;
        b.setStatus(Broadcast.STATUS_CANCELLED);
        broadcastRepository.save(b);
        // Flush the broadcast status flip immediately so the dispatcher's race gate sees it
        // before we start the (potentially slow) bulk message update.
        broadcastRepository.flush();

        int flipped = messageRepository.cancelQueuedByBroadcastId(broadcastId, java.time.LocalDateTime.now());
        log.info("Broadcast {} cancelled, flipped {} QUEUED messages to CANCELLED", broadcastId, flipped);
    }

    @Transactional
    public int deleteByIds(java.util.List<Long> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        int n = 0;
        for (Long id : ids) {
            if (id == null) continue;
            try {
                cancel(id);
                broadcastRepository.deleteById(id);
                n++;
            } catch (Exception ignored) {}
        }
        return n;
    }

    /**
     * Targeting precedence:
     *   1. Explicit targetUserIds (from "選択一斉送信" on the user list) — when present, ONLY
     *      these users are matched. Filters are ignored.
     *   2. emailDomain / status filters from the form.
     */
    private List<CrmUser> findTargetUsers(BroadcastForm form) {
        java.util.List<Long> ids = form.getTargetUserIds();
        if (ids != null && !ids.isEmpty()) {
            // Explicit list path — drop nulls and unknown ids silently, deterministic order.
            java.util.List<CrmUser> picked = new ArrayList<>();
            for (CrmUser u : userRepository.findAllById(ids)) picked.add(u);
            picked.sort(java.util.Comparator.comparing(CrmUser::getId));
            return picked;
        }
        Specification<CrmUser> spec = (root, q, cb) -> {
            List<Predicate> preds = new ArrayList<>();
            if (hasText(form.getTargetCarrierCode())) {
                preds.add(cb.like(root.get("email"), "%@%" + form.getTargetCarrierCode() + "%"));
            }
            if (hasText(form.getTargetStatus())) {
                preds.add(cb.equal(root.get("status"), form.getTargetStatus()));
            }
            return preds.isEmpty() ? cb.conjunction() : cb.and(preds.toArray(new Predicate[0]));
        };
        return userRepository.findAll(spec);
    }

    private String buildFilterSummary(BroadcastForm form, int matched, int skipped) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"carrier_code\":\"").append(hasText(form.getTargetCarrierCode()) ? form.getTargetCarrierCode() : "").append("\",");
        sb.append("\"status\":\"").append(hasText(form.getTargetStatus()) ? form.getTargetStatus() : "").append("\",");
        sb.append("\"matched_users\":").append(matched).append(",");
        sb.append("\"skipped_users\":").append(skipped).append(",");
        sb.append("\"rate_per_minute\":").append(form.getRatePerMinute() == null ? 60 : form.getRatePerMinute());
        sb.append("}");
        return sb.toString();
    }

    private static boolean hasText(String s) { return s != null && !s.trim().isEmpty(); }

    /**
     * True if the address is a docomo dot-issue local-part that the relay can rescue via
     * RFC 5321 quoted-string ("foo."@docomo.ne.jp). docomo's MX accepts the quoted form;
     * other providers (gmail/yahoo/icloud/au) do not, so they remain unsendable.
     */
    private static boolean isDocomoDotIssueRescuable(String email) {
        if (email == null) return false;
        int at = email.lastIndexOf('@');
        if (at < 1 || at == email.length() - 1) return false;
        String domain = email.substring(at + 1).toLowerCase();
        return "docomo.ne.jp".equals(domain);
    }
}
