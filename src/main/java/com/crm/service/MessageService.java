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

    /** 15-char clip rule: when a %reply_url% is present, only this many characters of the
     *  substituted body precede the expanded URL in what's actually transmitted. The full
     *  substituted body is always kept in BODY_TEXT for メッセージボックス display. */
    public static final int REPLY_URL_CLIP_LENGTH = 15;

    private final MessageRepository messageRepository;
    private final CrmUserRepository userRepository;
    private final CarrierAddressPoolRepository poolRepository;
    private final CarrierBindingService bindingService;
    private final PlaceholderService placeholderService;
    private final OutboundMailService outboundMailService;
    private final OutboundSmsService outboundSmsService;
    private final SmsSettingService smsSettingService;
    private final AesEncryptionUtil aes;
    private final ReplyPageService replyPageService;
    private final DomainSettingService domainSettingService;
    /** Lazy reference — broadcast counter update is optional and avoids a circular dependency. */
    private final org.springframework.context.ApplicationContext ctx;

    public MessageService(MessageRepository messageRepository,
                          CrmUserRepository userRepository,
                          CarrierAddressPoolRepository poolRepository,
                          CarrierBindingService bindingService,
                          PlaceholderService placeholderService,
                          OutboundMailService outboundMailService,
                          OutboundSmsService outboundSmsService,
                          SmsSettingService smsSettingService,
                          AesEncryptionUtil aes,
                          ReplyPageService replyPageService,
                          DomainSettingService domainSettingService,
                          org.springframework.context.ApplicationContext ctx) {
        this.messageRepository = messageRepository;
        this.userRepository = userRepository;
        this.poolRepository = poolRepository;
        this.bindingService = bindingService;
        this.placeholderService = placeholderService;
        this.outboundMailService = outboundMailService;
        this.outboundSmsService = outboundSmsService;
        this.smsSettingService = smsSettingService;
        this.aes = aes;
        this.replyPageService = replyPageService;
        this.domainSettingService = domainSettingService;
        this.ctx = ctx;
    }

    /**
     * Thread display for the user-detail pane. Future-scheduled outbound messages
     * (status=QUEUED with scheduledAt > now) are pinned to the top in scheduled-time
     * DESC order, so an operator sees "what's about to fire" before the historical
     * sent/received trail. Everything else falls below, sorted by createdAt DESC.
     * Operator request 2026-05-23.
     */
    public List<Message> threadFor(Long userId) {
        List<Message> all = messageRepository.findByUserIdOrderByCreatedAtDesc(userId);
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        List<Message> future = new java.util.ArrayList<>();
        List<Message> rest = new java.util.ArrayList<>();
        for (Message m : all) {
            if (Message.STATUS_QUEUED.equals(m.getStatus())
                    && m.getScheduledAt() != null && m.getScheduledAt().isAfter(now)) {
                future.add(m);
            } else {
                rest.add(m);
            }
        }
        future.sort((a, b) -> b.getScheduledAt().compareTo(a.getScheduledAt()));
        List<Message> out = new java.util.ArrayList<>(all.size());
        out.addAll(future);
        out.addAll(rest);
        return out;
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
            // 未返信 = there is at least one non-dismissed IN row and no OUT row arrived after it.
            // g[6]/g[7] are java.sql.Timestamp from the native query; coerce safely.
            java.time.LocalDateTime latestIn = toLdt(g[6]);
            java.time.LocalDateTime latestOut = toLdt(g[7]);
            r.unreplied = (latestIn != null) && (latestOut == null || latestOut.isBefore(latestIn));
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

    /** Coerce native-query MAX(CREATED_AT) result (Timestamp or LocalDateTime depending on driver) to LocalDateTime. */
    private static java.time.LocalDateTime toLdt(Object v) {
        if (v == null) return null;
        if (v instanceof java.time.LocalDateTime) return (java.time.LocalDateTime) v;
        if (v instanceof java.sql.Timestamp) return ((java.sql.Timestamp) v).toLocalDateTime();
        return null;
    }

    /**
     * Dismiss every non-dismissed inbound message for {@code userId}. The user disappears
     * from the left-upper 受信 list on thread.html; Message rows are NOT deleted so the
     * 過去のやり取り pane and per-user thread history stay intact. Used by the per-row × button.
     */
    @Transactional
    public int dismissInboxForUser(Long userId) {
        return messageRepository.dismissInboxByUserId(userId, LocalDateTime.now());
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
        /** true when the user has at least one IN message and we haven't sent any OUT after it. */
        public boolean unreplied;

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
        public boolean isUnreplied() { return unreplied; }
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
            if (user.getEmail() == null || user.getEmail().isEmpty()) continue; // SMS-only user

            // 2026-05-24: fall back to base-domain FROM when no active pool binding so
            // operators can still bulk-reply to users they haven't carrier-bound yet.
            java.util.Optional<CarrierAddressPool> poolOpt = bindingService.firstBoundFor(uid);
            CarrierAddressPool pool = (poolOpt.isPresent()
                    && !Boolean.FALSE.equals(poolOpt.get().getIsActive())) ? poolOpt.get() : null;
            String fromAddr = (pool != null) ? pool.getAddress() : domainSettingService.buildFromAddress();
            if (fromAddr == null || fromAddr.isEmpty()) continue;

            String renderedSubject = placeholderService.substitute(subject, user);
            String renderedBody    = placeholderService.substitute(body, user);

            Message m = new Message();
            m.setUserId(uid);
            m.setDirection(Message.DIR_OUT);
            m.setChannel(Message.CHANNEL_EMAIL);
            m.setSubject(renderedSubject);
            m.setBodyText(renderedBody);
            m.setFromAddress(fromAddr);
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
        if (user.getEmail() == null || user.getEmail().isEmpty()) {
            throw new MessageException("このユーザーはメールアドレスが未登録です（電話番号のみ登録）。SMS送信をご利用ください");
        }
        // 2026-05-24: unbound users used to be rejected here, but the operator can still
        // send via the base-domain FROM (from.base_domain setting) — the reply-page URL in
        // the body handles round-tripping. Fall back when no active pool binding exists.
        CarrierAddressPool pool = bindingService.firstBoundFor(userId).orElse(null);
        if (pool != null && Boolean.FALSE.equals(pool.getIsActive())) pool = null;
        String fromAddr = (pool != null) ? pool.getAddress() : domainSettingService.buildFromAddress();
        if (fromAddr == null || fromAddr.isEmpty()) {
            throw new MessageException("送信元アドレスが解決できません (キャリア未割当かつ from.base_domain 未設定)");
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
        msg.setFromAddress(fromAddr);
        msg.setToAddress(user.getEmail());
        msg.setReplyToMessageId(form.getReplyToMessageId());

        // Persist first so we have an ID for the reply-page binding if needed.
        boolean needsReplyUrl = renderedBody != null && renderedBody.contains(REPLY_URL_PLACEHOLDER);
        if (needsReplyUrl) {
            // Temporary body/status so we can save; we'll rewrite after the page is created.
            msg.setStatus(Message.STATUS_DRAFT);
            msg = messageRepository.save(msg);
            String url = replyPageService.createReplyPageFor(msg);
            // BODY_TEXT always keeps the FULL substituted body — メッセージボックス reads this.
            // SENT_BODY_TEXT is the 15-char-clipped text actually transmitted (see clipForTransmission).
            msg.setBodyText(renderedBody.replace(REPLY_URL_PLACEHOLDER, url));
            msg.setSentBodyText(clipForTransmission(renderedBody, url));
            // Historical/audit record only — records what the domain's landing mode was AT
            // SEND TIME. メッセージボックス no longer reads this column to decide visibility;
            // that decision is now made at VIEW time (see MessageBoxService#listFor), since
            // toggling 使用中 after send should retroactively change reachability too.
            msg.setExcludedFromBox(domainSettingService.isActiveLinkDomainExternalLanding());
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
     * SMS reply from the thread page — deliberately separate from {@link #compose}, which
     * resolves a carrier-pool FROM address and has no meaning for the SMS channel. Only
     * available when the user has a registered phone number.
     */
    @Transactional
    public Message composeSms(Long userId, Long adminUserId, com.crm.dto.SmsComposeForm form) {
        CrmUser user = userRepository.findById(userId)
                .orElseThrow(() -> new MessageException("ユーザーが見つかりません"));
        String phone = user.getPhoneNumber();
        if (phone == null || phone.trim().isEmpty()) {
            throw new MessageException("このユーザーには電話番号が登録されていません");
        }

        String renderedBody = placeholderService.substitute(form.getBody(), user);

        Message msg = new Message();
        msg.setUserId(userId);
        msg.setAdminUserId(adminUserId);
        msg.setDirection(Message.DIR_OUT);
        msg.setChannel(Message.CHANNEL_SMS);
        msg.setBodyText(renderedBody);
        msg.setFromAddress(smsSettingService.resolveSenderName());
        msg.setToAddress(phone);
        msg.setReplyToMessageId(form.getReplyToMessageId());

        // Same %reply_url% handling as compose() — the tag panel is shared between the
        // email and SMS reply forms, but this substitution was missing here, so an SMS
        // reply containing %reply_url% went out with the literal placeholder text intact.
        // Uses the short (10-char) token, not the 64-char email one — SMS is billed per
        // ~65-char segment, so the long form alone would consume the whole budget.
        boolean needsReplyUrl = renderedBody != null && renderedBody.contains(REPLY_URL_PLACEHOLDER);
        if (needsReplyUrl) {
            msg.setStatus(Message.STATUS_DRAFT);
            msg = messageRepository.save(msg);
            String url = replyPageService.createShortReplyPageFor(msg);
            // Same full-body-vs-clipped-transmit split as compose() — see clipForTransmission().
            msg.setBodyText(renderedBody.replace(REPLY_URL_PLACEHOLDER, url));
            msg.setSentBodyText(clipForTransmission(renderedBody, url));
            // Historical/audit record only — see the matching comment in compose() above.
            msg.setExcludedFromBox(domainSettingService.isActiveLinkDomainExternalLanding());
        }

        LocalDateTime scheduled = form.getScheduledAt();
        LocalDateTime now = LocalDateTime.now();
        if (scheduled != null && scheduled.isAfter(now)) {
            msg.setStatus(Message.STATUS_QUEUED);
            msg.setScheduledAt(scheduled);
            return messageRepository.save(msg);
        }

        msg.setStatus(Message.STATUS_QUEUED); // transient; updated below
        Message saved = messageRepository.save(msg);
        sendNow(saved, null);
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
        boolean success;
        boolean retriable;
        String errorMessage;

        // SENT_BODY_TEXT holds the 15-char-clipped text when %reply_url% was present at compose
        // time (see clipForTransmission()); BODY_TEXT is always the full text for メッセージボックス.
        // Falls back to BODY_TEXT when SENT_BODY_TEXT was never set — unchanged behaviour for the
        // no-%reply_url% case. Works for both immediate send and scheduler-dispatched QUEUED rows,
        // since both columns are persisted together at compose/queue time.
        String transmitBody = msg.getSentBodyText() != null ? msg.getSentBodyText() : msg.getBodyText();

        if (Message.CHANNEL_SMS.equals(msg.getChannel())) {
            // Use the sender name already resolved and stored on the row at compose/queue time
            // (msg.getFromAddress()) — NOT smsSettingService.resolveSenderName() again here.
            // With a random sender-name mode, calling resolve() a second time would send BytePlus
            // a different name than the one recorded in our own history.
            OutboundSmsService.SmsSendRequest req = new OutboundSmsService.SmsSendRequest(
                    smsSettingService.getUsername(),
                    smsSettingService.getPassword(),
                    msg.getFromAddress(),
                    msg.getToAddress(),
                    transmitBody == null ? "" : transmitBody);
            OutboundSmsService.SendResult result = outboundSmsService.send(req);
            success = result.success;
            retriable = result.retriable;
            errorMessage = result.errorMessage;
        } else {
            String smtpPwd = pool == null ? null : aes.decrypt(pool.getSmtpPassword());
            String smtpHost = pool == null ? null : pool.getSmtpHost();
            Integer smtpPort = pool == null ? null : pool.getSmtpPort();
            String smtpUser = pool == null ? null : pool.getSmtpUsername();
            OutboundMailService.OutboundRequest req = new OutboundMailService.OutboundRequest(
                    msg.getFromAddress(),
                    msg.getToAddress(),
                    msg.getSubject() == null ? "" : msg.getSubject(),
                    transmitBody == null ? "" : transmitBody,
                    smtpHost,
                    smtpPort == null ? 587 : smtpPort,
                    smtpUser,
                    smtpPwd);
            OutboundMailService.SendResult result = outboundMailService.send(req);
            success = result.success;
            retriable = result.retriable;
            errorMessage = result.errorMessage;
        }
        int attempts = (msg.getSendAttempts() == null ? 0 : msg.getSendAttempts()) + 1;
        msg.setSendAttempts(attempts);

        boolean finalOutcome;
        if (success) {
            msg.setStatus(Message.STATUS_SENT);
            msg.setSentAt(LocalDateTime.now());
            msg.setErrorMessage(null);
            msg.setNextRetryAt(null);
            finalOutcome = true;
        } else if (retriable && attempts < MAX_SEND_ATTEMPTS) {
            // Transient failure — put back on the queue with exponential backoff.
            msg.setStatus(Message.STATUS_QUEUED);
            msg.setErrorMessage(errorMessage);
            msg.setNextRetryAt(LocalDateTime.now().plus(backoffFor(attempts)));
            // Counter update is deferred: broadcast counter only moves on final outcome.
            messageRepository.save(msg);
            return;
        } else {
            msg.setStatus(Message.STATUS_FAILED);
            msg.setErrorMessage(errorMessage);
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

    /**
     * 15-char clip rule (メッセージボックス feature): what's actually TRANSMITTED when the body
     * contains %reply_url% is the first {@link #REPLY_URL_CLIP_LENGTH} characters of the text
     * BEFORE the tag, plus the expanded URL once — the tag itself is located first and removed
     * whole, then the remaining text is clipped, rather than clipping first and hoping the tag
     * happens to still be intact inside the clipped window. The naive "clip first, then try to
     * remove the literal %reply_url% string" approach broke whenever the 15-char boundary fell
     * INSIDE the tag (e.g. body = "本日まで\n%reply_url%", where "本日まで\n" is only 6 chars,
     * so the clip window ends mid-tag at "...%reply_ur") — the substring no longer contained the
     * exact token, so replace() silently did nothing and the mangled tag fragment
     * ("%reply_urhttps://...") was transmitted to the user. Locating the tag first and clipping
     * only the text around it makes this correct regardless of where the tag falls.
     */
    public static String clipForTransmission(String renderedBodyBeforeUrlSwap, String expandedUrl) {
        String body = renderedBodyBeforeUrlSwap == null ? "" : renderedBodyBeforeUrlSwap;
        String url = expandedUrl == null ? "" : expandedUrl;
        int tagIndex = body.indexOf(REPLY_URL_PLACEHOLDER);
        if (tagIndex < 0) {
            // No literal tag present (shouldn't normally happen — callers only invoke this
            // when the tag was detected — but stay correct if it's absent): clip the whole body.
            return body.length() > REPLY_URL_CLIP_LENGTH ? body.substring(0, REPLY_URL_CLIP_LENGTH) : body;
        }
        String beforeTag = body.substring(0, tagIndex);
        String prefix = beforeTag.length() > REPLY_URL_CLIP_LENGTH
                ? beforeTag.substring(0, REPLY_URL_CLIP_LENGTH) : beforeTag;
        return prefix + url;
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
