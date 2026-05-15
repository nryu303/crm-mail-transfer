package com.crm.service;

import com.crm.dto.MessageComposeForm;
import com.crm.entity.CarrierAddressPool;
import com.crm.entity.CrmUser;
import com.crm.entity.Message;
import com.crm.repository.CarrierAddressPoolRepository;
import com.crm.repository.CrmUserRepository;
import com.crm.repository.MessageRepository;
import com.crm.util.AesEncryptionUtil;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class MessageService {

    public static final String REPLY_URL_PLACEHOLDER = "%reply_url%";

    private final MessageRepository messageRepository;
    private final CrmUserRepository userRepository;
    private final CarrierAddressPoolRepository poolRepository;
    private final CarrierBindingService bindingService;
    private final PlaceholderService placeholderService;
    private final OutboundMailService outboundMailService;
    private final AesEncryptionUtil aes;
    private final ReplyPageService replyPageService;
    /** Lazy reference — broadcast counter update is optional and avoids a circular dependency. */
    private final org.springframework.context.ApplicationContext ctx;

    public MessageService(MessageRepository messageRepository,
                          CrmUserRepository userRepository,
                          CarrierAddressPoolRepository poolRepository,
                          CarrierBindingService bindingService,
                          PlaceholderService placeholderService,
                          OutboundMailService outboundMailService,
                          AesEncryptionUtil aes,
                          ReplyPageService replyPageService,
                          org.springframework.context.ApplicationContext ctx) {
        this.messageRepository = messageRepository;
        this.userRepository = userRepository;
        this.poolRepository = poolRepository;
        this.bindingService = bindingService;
        this.placeholderService = placeholderService;
        this.outboundMailService = outboundMailService;
        this.aes = aes;
        this.replyPageService = replyPageService;
        this.ctx = ctx;
    }

    /** Thread display for the user-detail pane: newest at the top. */
    public List<Message> threadFor(Long userId) {
        return messageRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    /** One row per user that has inbound messages, newest first. For /manager/inbox. */
    public List<InboxRow> inboxByUser(boolean unreadOnly) {
        List<Object[]> groups = messageRepository.inboxGroupByUser();
        if (groups.isEmpty()) return java.util.Collections.emptyList();

        // Index userIds, latestIds; skip users with zero unread when filter is on
        java.util.List<Long> userIds = new java.util.ArrayList<>();
        java.util.List<Long> latestIds = new java.util.ArrayList<>();
        java.util.List<Object[]> kept = new java.util.ArrayList<>();
        for (Object[] g : groups) {
            long unread = ((Number) g[2]).longValue();
            if (unreadOnly && unread == 0L) continue;
            kept.add(g);
            userIds.add(((Number) g[0]).longValue());
            latestIds.add(((Number) g[1]).longValue());
        }
        if (kept.isEmpty()) return java.util.Collections.emptyList();

        java.util.Map<Long, CrmUser> userById = new java.util.HashMap<>();
        for (CrmUser u : userRepository.findAllById(userIds)) userById.put(u.getId(), u);

        java.util.Map<Long, Message> msgById = new java.util.HashMap<>();
        for (Message m : messageRepository.findAllById(latestIds)) msgById.put(m.getId(), m);

        java.util.List<InboxRow> out = new java.util.ArrayList<>(kept.size());
        for (Object[] g : kept) {
            InboxRow r = new InboxRow();
            r.userId = ((Number) g[0]).longValue();
            r.unreadCount = ((Number) g[2]).longValue();
            r.webReplyCount = ((Number) g[3]).longValue();
            r.mailReplyCount = ((Number) g[4]).longValue();
            r.outCount = ((Number) g[5]).longValue();
            CrmUser u = userById.get(r.userId);
            if (u != null) {
                r.displayName = (u.getDisplayName() == null || u.getDisplayName().isEmpty())
                        ? u.getEmail() : u.getDisplayName();
                r.email = u.getEmail();
            } else {
                r.displayName = "ID=" + r.userId;
                r.email = "";
            }
            Message m = msgById.get(((Number) g[1]).longValue());
            if (m != null) {
                r.latestSubject = m.getSubject();
                r.latestPreview = preview(m.getBodyText(), 80);
                r.latestAt = m.getCreatedAt();
                r.latestMessageId = m.getId();
                r.latestRead = m.getReadAt() != null;
            }
            out.add(r);
        }
        return out;
    }

    private static String preview(String body, int max) {
        if (body == null) return "";
        String s = body.replaceAll("\\s+", " ").trim();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** Flat row for the inbox triage list. */
    public static class InboxRow {
        public Long userId;
        public String displayName;
        public String email;
        public long unreadCount;
        public long webReplyCount;
        public long mailReplyCount;
        public long outCount;
        public Long latestMessageId;
        public String latestSubject;
        public String latestPreview;
        public LocalDateTime latestAt;
        public boolean latestRead;

        public Long getUserId() { return userId; }
        public String getDisplayName() { return displayName; }
        public String getEmail() { return email; }
        public long getUnreadCount() { return unreadCount; }
        public long getWebReplyCount() { return webReplyCount; }
        public long getMailReplyCount() { return mailReplyCount; }
        public long getOutCount() { return outCount; }
        public Long getLatestMessageId() { return latestMessageId; }
        public String getLatestSubject() { return latestSubject; }
        public String getLatestPreview() { return latestPreview; }
        public LocalDateTime getLatestAt() { return latestAt; }
        public boolean isLatestRead() { return latestRead; }
    }

    /** Mark all inbound messages for this user as read. Called when admin opens the thread. */
    @Transactional
    public int markThreadAsRead(Long userId) {
        return messageRepository.markReadByUserAndDirection(userId, Message.DIR_IN, LocalDateTime.now());
    }

    /**
     * Predicate excluding broadcast-related messages from /manager/messages.
     *   \u2022 OUT dispatched by a broadcast (broadcastId NOT NULL) \u2014 handled by /broadcast page
     *   \u2022 IN replying to such an OUT (replyToMessageId points to a broadcast OUT) \u2014 same
     *
     * Inverse of {@code MessageRepository.findBroadcastRelated}, used as a runtime filter so
     * the two list pages have non-overlapping content without a DB migration.
     */
    private static javax.persistence.criteria.Predicate notBroadcastRelated(
            javax.persistence.criteria.Root<Message> root,
            javax.persistence.criteria.AbstractQuery<?> q,
            javax.persistence.criteria.CriteriaBuilder cb) {
        javax.persistence.criteria.Subquery<Long> broadcastOutIds = q.subquery(Long.class);
        javax.persistence.criteria.Root<Message> bRoot = broadcastOutIds.from(Message.class);
        broadcastOutIds.select(bRoot.get("id"));
        broadcastOutIds.where(cb.isNotNull(bRoot.get("broadcastId")));
        return cb.and(
                cb.isNull(root.get("broadcastId")),
                cb.or(
                        cb.isNull(root.get("replyToMessageId")),
                        cb.not(root.get("replyToMessageId").in(broadcastOutIds))
                )
        );
    }

    /** CSV export for the messages list, optionally filtered by tab. UTF-8 BOM for Excel. */
    public void exportCsv(String tab, java.io.Writer writer) throws java.io.IOException {
        writer.write('\uFEFF');
        try (com.opencsv.CSVWriter csv = new com.opencsv.CSVWriter(writer)) {
            csv.writeNext(new String[]{
                    "id", "direction", "channel", "status",
                    "user_id", "to_address", "from_address",
                    "subject", "body_text", "scheduled_at", "sent_at", "created_at"});
            org.springframework.data.jpa.domain.Specification<Message> spec = (root, q, cb) -> {
                java.util.List<javax.persistence.criteria.Predicate> preds = new java.util.ArrayList<>();
                preds.add(notBroadcastRelated(root, q, cb));
                if ("sent".equals(tab)) {
                    preds.add(cb.equal(root.get("direction"), Message.DIR_OUT));
                    preds.add(cb.isNull(root.get("replyToMessageId")));
                    preds.add(root.get("status").in(Message.STATUS_SENT, Message.STATUS_DELIVERED, Message.STATUS_FAILED));
                } else if ("scheduled".equals(tab)) {
                    preds.add(cb.equal(root.get("direction"), Message.DIR_OUT));
                    preds.add(cb.isNull(root.get("replyToMessageId")));
                    preds.add(root.get("status").in(Message.STATUS_QUEUED, Message.STATUS_CANCELLED));
                } else if ("reply".equals(tab)) {
                    preds.add(cb.equal(root.get("direction"), Message.DIR_OUT));
                    preds.add(cb.isNotNull(root.get("replyToMessageId")));
                    preds.add(root.get("status").in(Message.STATUS_SENT, Message.STATUS_DELIVERED, Message.STATUS_FAILED));
                } else if ("scheduled-reply".equals(tab)) {
                    preds.add(cb.equal(root.get("direction"), Message.DIR_OUT));
                    preds.add(cb.isNotNull(root.get("replyToMessageId")));
                    preds.add(root.get("status").in(Message.STATUS_QUEUED, Message.STATUS_CANCELLED));
                } else if ("inbound".equals(tab)) {
                    preds.add(cb.equal(root.get("direction"), Message.DIR_IN));
                }
                return preds.isEmpty() ? cb.conjunction() : cb.and(preds.toArray(new javax.persistence.criteria.Predicate[0]));
            };
            java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME;
            for (Message m : messageRepository.findAll(spec, Sort.by(Sort.Direction.DESC, "createdAt"))) {
                csv.writeNext(new String[]{
                        String.valueOf(m.getId()),
                        s(m.getDirection()), s(m.getChannel()), s(m.getStatus()),
                        String.valueOf(m.getUserId()),
                        s(m.getToAddress()), s(m.getFromAddress()),
                        s(m.getSubject()), s(m.getBodyText()),
                        m.getScheduledAt() == null ? "" : m.getScheduledAt().format(fmt),
                        m.getSentAt() == null ? "" : m.getSentAt().format(fmt),
                        m.getCreatedAt() == null ? "" : m.getCreatedAt().format(fmt)
                });
            }
        }
    }
    private static String s(String v) { return v == null ? "" : v; }

    public Page<Message> recentMessages(int page, int size) {
        return recentMessages(page, size, null);
    }

    @org.springframework.transaction.annotation.Transactional
    public int deleteByIds(java.util.List<Long> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        int n = 0;
        for (Long id : ids) {
            if (id == null) continue;
            try { messageRepository.deleteById(id); n++; } catch (Exception ignored) {}
        }
        return n;
    }

    /**
     * Queue per-user reply messages for each selected user. Subject/body are passed
     * through placeholder substitution per recipient (%name%, %email%, %amount%, …).
     * Skips users with no carrier-pool binding or no active pool. Returns the count
     * actually queued for dispatch.
     */
    @org.springframework.transaction.annotation.Transactional
    public int bulkReplyToUsers(java.util.List<Long> userIds, String subject, String body) {
        if (userIds == null || userIds.isEmpty()) return 0;
        if (body == null) body = "";
        if (subject == null) subject = "";

        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        int queued = 0;
        for (Long uid : userIds) {
            if (uid == null) continue;
            java.util.Optional<CrmUser> uOpt = userRepository.findById(uid);
            if (!uOpt.isPresent()) continue;
            CrmUser user = uOpt.get();

            java.util.Optional<CarrierAddressPool> poolOpt = bindingService.firstBoundFor(uid);
            if (!poolOpt.isPresent()) continue;
            CarrierAddressPool pool = poolOpt.get();
            if (Boolean.FALSE.equals(pool.getIsActive())) continue;

            String renderedSubject = placeholderService.substitute(subject, user);
            String renderedBody    = placeholderService.substitute(body, user);

            Message m = new Message();
            m.setUserId(uid);
            m.setDirection(Message.DIR_OUT);
            m.setChannel(Message.CHANNEL_EMAIL);
            m.setSubject(renderedSubject);
            m.setBodyText(renderedBody);
            m.setFromAddress(pool.getAddress());
            m.setToAddress(user.getEmail());
            m.setStatus(Message.STATUS_QUEUED);
            m.setScheduledAt(now);
            messageRepository.save(m);
            queued++;
        }
        return queued;
    }

    /** Delete all inbound (DIR_IN) messages for the given users. Used by 受信管理 bulk delete. */
    @org.springframework.transaction.annotation.Transactional
    public int deleteInboundForUsers(java.util.List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) return 0;
        int total = 0;
        for (Long uid : userIds) {
            if (uid == null) continue;
            for (Message m : messageRepository.findByUserIdOrderByCreatedAtAsc(uid)) {
                if (Message.DIR_IN.equals(m.getDirection())) {
                    try { messageRepository.delete(m); total++; } catch (Exception ignored) {}
                }
            }
        }
        return total;
    }

    /**
     * Tab filters for /manager/messages:
     *   "sent"           — new outbound that has been dispatched (not a reply)
     *   "scheduled"      — new outbound awaiting scheduler (not a reply)
     *   "reply"          — admin replies that have been dispatched
     *   "scheduled-reply"— admin replies awaiting scheduler
     *   "inbound"        — replies from users (DIRECTION=IN)
     *   null/other       — everything
     */
    public Page<Message> recentMessages(int page, int size, String tab) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        org.springframework.data.jpa.domain.Specification<Message> spec = (root, q, cb) -> {
            java.util.List<javax.persistence.criteria.Predicate> preds = new java.util.ArrayList<>();
            preds.add(notBroadcastRelated(root, q, cb));
            if ("sent".equals(tab)) {
                preds.add(cb.equal(root.get("direction"), Message.DIR_OUT));
                preds.add(cb.isNull(root.get("replyToMessageId")));
                preds.add(root.get("status").in(
                        Message.STATUS_SENT, Message.STATUS_DELIVERED, Message.STATUS_FAILED));
            } else if ("scheduled".equals(tab)) {
                preds.add(cb.equal(root.get("direction"), Message.DIR_OUT));
                preds.add(cb.isNull(root.get("replyToMessageId")));
                preds.add(root.get("status").in(
                        Message.STATUS_QUEUED, Message.STATUS_CANCELLED));
            } else if ("reply".equals(tab)) {
                preds.add(cb.equal(root.get("direction"), Message.DIR_OUT));
                preds.add(cb.isNotNull(root.get("replyToMessageId")));
                preds.add(root.get("status").in(
                        Message.STATUS_SENT, Message.STATUS_DELIVERED, Message.STATUS_FAILED));
            } else if ("scheduled-reply".equals(tab)) {
                preds.add(cb.equal(root.get("direction"), Message.DIR_OUT));
                preds.add(cb.isNotNull(root.get("replyToMessageId")));
                preds.add(root.get("status").in(
                        Message.STATUS_QUEUED, Message.STATUS_CANCELLED));
            } else if ("inbound".equals(tab)) {
                preds.add(cb.equal(root.get("direction"), Message.DIR_IN));
            }
            return preds.isEmpty() ? cb.conjunction() : cb.and(preds.toArray(new javax.persistence.criteria.Predicate[0]));
        };
        return messageRepository.findAll(spec, pageable);
    }

    /**
     * Send immediately or queue a scheduled send. Subject + body are substituted
     * against the user's placeholder bindings before storage/sending.
     */
    @Transactional
    public Message compose(Long userId, Long adminUserId, MessageComposeForm form) {
        CrmUser user = userRepository.findById(userId)
                .orElseThrow(() -> new MessageException("ユーザーが見つかりません"));
        CarrierAddressPool pool = bindingService.firstBoundFor(userId)
                .orElseThrow(() -> new MessageException("このユーザーにはキャリアアドレスが未割当です"));
        if (Boolean.FALSE.equals(pool.getIsActive())) {
            throw new MessageException("割当中のキャリアアドレスが無効化されています");
        }

        String renderedSubject = placeholderService.substitute(form.getSubject(), user);
        String renderedBody = placeholderService.substitute(form.getBody(), user);

        Message msg = new Message();
        msg.setUserId(userId);
        msg.setAdminUserId(adminUserId);
        msg.setDirection(Message.DIR_OUT);
        msg.setChannel(Message.CHANNEL_EMAIL);
        msg.setSubject(renderedSubject);
        msg.setBodyText(renderedBody);
        msg.setFromAddress(pool.getAddress());
        msg.setToAddress(user.getEmail());
        msg.setReplyToMessageId(form.getReplyToMessageId());

        // Persist first so we have an ID for the reply-page binding if needed.
        boolean needsReplyUrl = renderedBody != null && renderedBody.contains(REPLY_URL_PLACEHOLDER);
        if (needsReplyUrl) {
            // Temporary body/status so we can save; we'll rewrite after the page is created.
            msg.setStatus(Message.STATUS_DRAFT);
            msg = messageRepository.save(msg);
            String url = replyPageService.createReplyPageFor(msg);
            msg.setBodyText(renderedBody.replace(REPLY_URL_PLACEHOLDER, url));
            // fall through — subsequent save() / sendNow() path will update this row
        }

        LocalDateTime scheduled = form.getScheduledAt();
        LocalDateTime now = LocalDateTime.now();

        if (scheduled != null && scheduled.isAfter(now)) {
            msg.setStatus(Message.STATUS_QUEUED);
            msg.setScheduledAt(scheduled);
            return messageRepository.save(msg);
        }

        // Immediate send
        msg.setStatus(Message.STATUS_QUEUED); // transient; updated below
        Message saved = messageRepository.save(msg);
        sendNow(saved, pool);
        return saved;
    }

    /**
     * Dispatch a saved outbound message via the relay. Called on immediate send
     * and (future) by the scheduler for QUEUED entries whose scheduled time has arrived.
     *
     * {@code pool} is now informational — kept for legacy callers but not required.
     * Outbound transport is decided entirely by the active RELAY_SERVER row in
     * {@link HttpRelayOutboundMailService}, so smtpHost/Port/User/Password from the
     * pool are no longer used. Pass {@code null} when the message has no associated pool
     * (the carrier-pool became receive-only as of the 2026-05 dispatcher refactor).
     */
    @Transactional
    public void sendNow(Message msg, CarrierAddressPool pool) {
        String smtpPwd = pool == null ? null : aes.decrypt(pool.getSmtpPassword());
        String smtpHost = pool == null ? null : pool.getSmtpHost();
        Integer smtpPort = pool == null ? null : pool.getSmtpPort();
        String smtpUser = pool == null ? null : pool.getSmtpUsername();
        OutboundMailService.OutboundRequest req = new OutboundMailService.OutboundRequest(
                msg.getFromAddress(),
                msg.getToAddress(),
                msg.getSubject() == null ? "" : msg.getSubject(),
                msg.getBodyText() == null ? "" : msg.getBodyText(),
                smtpHost,
                smtpPort == null ? 587 : smtpPort,
                smtpUser,
                smtpPwd);
        OutboundMailService.SendResult result = outboundMailService.send(req);
        int attempts = (msg.getSendAttempts() == null ? 0 : msg.getSendAttempts()) + 1;
        msg.setSendAttempts(attempts);

        boolean finalOutcome;
        if (result.success) {
            msg.setStatus(Message.STATUS_SENT);
            msg.setSentAt(LocalDateTime.now());
            msg.setErrorMessage(null);
            msg.setNextRetryAt(null);
            finalOutcome = true;
        } else if (result.retriable && attempts < MAX_SEND_ATTEMPTS) {
            // Transient failure — put back on the queue with exponential backoff.
            msg.setStatus(Message.STATUS_QUEUED);
            msg.setErrorMessage(result.errorMessage);
            msg.setNextRetryAt(LocalDateTime.now().plus(backoffFor(attempts)));
            // Counter update is deferred: broadcast counter only moves on final outcome.
            messageRepository.save(msg);
            return;
        } else {
            msg.setStatus(Message.STATUS_FAILED);
            msg.setErrorMessage(result.errorMessage);
            msg.setNextRetryAt(null);
            finalOutcome = false;
        }
        messageRepository.save(msg);
        if (msg.getBroadcastId() != null) {
            try {
                ctx.getBean(BroadcastService.class).reportMessageCompleted(msg.getBroadcastId(), finalOutcome);
            } catch (Exception e) {
                // defensive — don't fail a send because counter update failed
            }
        }
    }

    /** Max transient-retry attempts before giving up and marking FAILED. */
    private static final int MAX_SEND_ATTEMPTS = 6;

    /** Exponential backoff: 1min, 2min, 4min, 8min, 16min, 30min cap. */
    private static java.time.Duration backoffFor(int attempt) {
        long minutes = Math.min(30L, 1L << Math.max(0, attempt - 1));
        return java.time.Duration.ofMinutes(minutes);
    }

    @Transactional
    public boolean cancelScheduled(Long messageId) {
        Optional<Message> opt = messageRepository.findById(messageId);
        if (!opt.isPresent()) return false;
        Message m = opt.get();
        if (!Message.STATUS_QUEUED.equals(m.getStatus())) return false;
        m.setStatus(Message.STATUS_CANCELLED);
        messageRepository.save(m);
        return true;
    }

    /**
     * Re-dispatch a FAILED (or CANCELLED) outbound message. Looks up the pool by the
     * original from-address. Returns true if re-send was attempted.
     */
    @Transactional
    public boolean retrySend(Long messageId) {
        Optional<Message> opt = messageRepository.findById(messageId);
        if (!opt.isPresent()) return false;
        Message m = opt.get();
        if (!Message.DIR_OUT.equals(m.getDirection())) return false;
        // Only allow retry for terminal failure-ish states
        if (!Message.STATUS_FAILED.equals(m.getStatus())
                && !Message.STATUS_CANCELLED.equals(m.getStatus())) return false;
        if (m.getFromAddress() == null) return false;
        // Pool is best-effort post-refactor: outbound transport doesn't require it any more.
        CarrierAddressPool pool = poolRepository.findByAddress(m.getFromAddress()).orElse(null);
        // Reset to QUEUED state so the send path runs cleanly
        m.setStatus(Message.STATUS_QUEUED);
        m.setErrorMessage(null);
        messageRepository.save(m);
        sendNow(m, pool);
        return true;
    }

    public int unreadInboundCount(Long userId) {
        return messageRepository.countByUserIdAndDirectionAndReadAtIsNull(userId, Message.DIR_IN);
    }

    public static class MessageException extends RuntimeException {
        public MessageException(String msg) { super(msg); }
    }
}
