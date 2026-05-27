package com.crm.service;

import com.crm.dto.InboundMailDto;
import com.crm.entity.CarrierAddressPool;
import com.crm.entity.CrmUser;
import com.crm.entity.InboundMailLog;
import com.crm.entity.Message;
import com.crm.repository.CarrierAddressPoolRepository;
import com.crm.repository.CrmUserRepository;
import com.crm.repository.InboundMailLogRepository;
import com.crm.repository.MessageRepository;
import com.crm.util.LogSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Processes inbound mail forwarded by the transmitter/AMG.
 *
 * Matching logic (M:N binding model — AMG picks any of its registered addresses
 * as the FROM when sending, so we cannot assume TO → user is 1:1):
 *
 *   1. Message-ID dedup — if we've already saved this Message-ID, skip.
 *   2. Sanity: both FROM and TO present, FROM is not a bounce/autoreply address.
 *   3. TO must exist in CARRIER_ADDRESS_POOL (confirms it's one of our AMG addresses).
 *   4. Look up CRM user by FROM address.
 *   5. Verify the user is bound to this pool entry (CARRIER_USER_BINDING row exists).
 *   6. If all pass, create an inbound MESSAGE on the user's thread.
 *
 * Everything else is logged to INBOUND_MAIL_LOG with IS_REJECTED=1 and a reason.
 */
@Service
public class InboundMailService {

    private static final Logger log = LoggerFactory.getLogger(InboundMailService.class);

    /** Local parts that indicate system / bounce mail, not a real user reply. */
    private static final Pattern BOUNCE_LOCAL_PART = Pattern.compile(
            "^(postmaster|mailer-daemon|daemon|no[-_]?reply|noreply|donotreply|do[-_]?not[-_]?reply|bounce|bounces)(\\+.*)?$",
            Pattern.CASE_INSENSITIVE);

    public static final String REASON_TO_NOT_IN_POOL = "to_address_not_in_pool";
    public static final String REASON_FROM_NOT_A_USER = "from_address_not_registered_user";
    public static final String REASON_NOT_BOUND      = "user_not_bound_to_this_carrier_address";
    public static final String REASON_BOUNCE         = "from_looks_like_system_bounce";
    public static final String REASON_MISSING_FIELDS = "missing_from_or_to";
    public static final String REASON_DUPLICATE      = "duplicate_message_id";
    public static final String REASON_USER_NOT_ACTIVE = "user_status_not_active";
    /** Reject inbound when the matched user has zero outbound history from our system —
     *  protects against stale historical mail that was sitting in a re-used SoftBank
     *  inbox before we onboarded the address. The FROM happens to match a registered
     *  user, but we never actually sent them anything, so the "reply" is meaningless. */
    public static final String REASON_NO_OUT_HISTORY = "user_has_no_outbound_history";

    /** Soft hold (NOT a final rejection) — the to_address has no pool row right now,
     *  BUT the pool table was modified within the last few minutes, so this is most likely
     *  a delete-then-recreate operator action in flight. We park the row in INBOUND_MAIL_LOG
     *  with is_processed=false / is_rejected=false / reject_reason=PENDING_POOL, and a
     *  separate scheduled task ({@code ScheduledTaskService.retryDeferredInbounds}) re-evaluates
     *  every 2 minutes. Final rejection only happens if the row is still un-resolvable after
     *  {@link #DEFERRAL_EXPIRY_MINUTES} minutes. */
    public static final String REASON_PENDING_POOL = "pending_pool_retry";

    /** Operator-controlled actions like CSV pool re-import touch the pool table; any new
     *  pool row inserted within this many minutes counts as "churn in progress". */
    private static final int POOL_CHURN_MINUTES = 5;
    /** Give up on retry after this many minutes — anything older is unlikely to ever match. */
    public static final int DEFERRAL_EXPIRY_MINUTES = 30;

    private final InboundMailLogRepository logRepository;
    private final CarrierAddressPoolRepository poolRepository;
    private final CrmUserRepository userRepository;
    private final MessageRepository messageRepository;
    private final CarrierBindingService bindingService;
    private final UserActivityService userActivityService;
    private final DomainSettingService settingService;

    public InboundMailService(InboundMailLogRepository logRepository,
                              CarrierAddressPoolRepository poolRepository,
                              CrmUserRepository userRepository,
                              MessageRepository messageRepository,
                              CarrierBindingService bindingService,
                              UserActivityService userActivityService,
                              DomainSettingService settingService) {
        this.logRepository = logRepository;
        this.poolRepository = poolRepository;
        this.userRepository = userRepository;
        this.messageRepository = messageRepository;
        this.bindingService = bindingService;
        this.userActivityService = userActivityService;
        this.settingService = settingService;
    }

    @Transactional
    public ProcessResult process(InboundMailDto dto) {
        // The bash pipe script (postfix-to-crm) extracts Subject/body from the raw RFC822 with
        // simple awk patterns — i.e. it leaves RFC2047 encoded-words and base64/quoted-printable
        // bodies intact. Decode here so the CRM stores readable Japanese.
        DecodedMail decoded = decodeFromRaw(dto.getRaw(), dto.getSubject(), dto.getBody());

        InboundMailLog entry = new InboundMailLog();
        entry.setFromAddress(safe(dto.getFrom()));
        entry.setToAddress(safe(dto.getTo()));
        entry.setSubject(decoded.subject);
        entry.setBodyText(decoded.body);
        entry.setRawContent(truncate(dto.getRaw(), 65535));

        // Always compute a dedup key. If the mail has a Message-ID header, use it;
        // otherwise fingerprint the envelope + the current minute, so two genuinely-distinct
        // identical-content replies arriving more than ~60 seconds apart aren't merged. The
        // tradeoff: a true duplicate retried within the same minute IS deduped (good); two
        // legitimately identical mails sent in the same minute would be merged (rare).
        String rawMsgId = normaliseMessageId(dto.getMessageId());
        String dedupKey;
        if (rawMsgId != null) {
            dedupKey = "mid:" + rawMsgId;
        } else {
            String minuteBucket = java.time.LocalDateTime.now()
                    .truncatedTo(java.time.temporal.ChronoUnit.MINUTES).toString();
            dedupKey = "fp:" + fingerprint(dto.getFrom(), dto.getTo(),
                    dto.getSubject(), dto.getBody(), minuteBucket);
        }
        entry.setMessageIdHeader(dedupKey);

        if (messageRepository.existsByMessageIdHeader(dedupKey)) {
            entry.setIsProcessed(true);
            entry.setIsRejected(true);
            entry.setRejectReason(REASON_DUPLICATE);
            logRepository.save(entry);
            log.info("Inbound mail skipped as duplicate: key={}", LogSafe.of(dedupKey));
            return ProcessResult.rejected(REASON_DUPLICATE);
        }

        if (isBlank(dto.getFrom()) || isBlank(dto.getTo())) {
            return reject(entry, REASON_MISSING_FIELDS);
        }

        // The Python fetcher (postfix-to-crm and docomo-inbound-fetcher) hands us the raw
        // From header verbatim, which is often a name-addr form like
        //   "tt tt <user@gmail.com>"  /  "<MAILER-DAEMON@wdy.docomo.ne.jp>"  /  "User <a@b>"
        // Strip down to the bare addr-spec before any matching/lookup so
        //   • CRM_USER.email comparisons hit registered users
        //   • the bounce-localpart guard still fires on "<MAILER-DAEMON@...>"
        String fromAddr = extractEmailAddress(dto.getFrom().trim());
        String toAddr = extractEmailAddress(dto.getTo().trim());

        String localPart = localPartOf(fromAddr);
        if (localPart != null && BOUNCE_LOCAL_PART.matcher(localPart).matches()) {
            return reject(entry, REASON_BOUNCE);
        }

        // 1) TO must map to one of our pool addresses. Three-step lookup so a reply addressed
        //    to a FROM-domain-override variant (e.g. "rifc6h1c65@avu74g.jp") still resolves
        //    back to the real pool row ("rifc6h1c65@docomo.ne.jp"):
        //      a) full address (lowercase)
        //      b) full address (case-preserved)
        //      c) local-part match — only when the TO domain matches the configured
        //         from.base_domain setting (so we don't weaken matching on actual carrier mail)
        Optional<CarrierAddressPool> pool = poolRepository.findByAddress(toAddr.toLowerCase());
        if (!pool.isPresent()) pool = poolRepository.findByAddress(toAddr);
        if (!pool.isPresent()) {
            String fromDomain = settingService == null ? null : settingService.getFromBaseDomain();
            String toDomainPart = toAddr.contains("@") ? toAddr.substring(toAddr.indexOf('@') + 1) : "";
            if (fromDomain != null && !fromDomain.trim().isEmpty()
                    && fromDomain.trim().equalsIgnoreCase(toDomainPart)) {
                String toLocalPart = toAddr.substring(0, toAddr.indexOf('@'));
                pool = poolRepository.findByLocalPart(toLocalPart);
            }
        }
        if (!pool.isPresent()) {
            // Soft-hold during pool churn so a "delete-then-recreate" operator action
            // doesn't silently drop in-flight replies (2026-05-27 incident: 1 user reply
            // lost during a 4-minute pool re-creation gap). The deferred row is picked
            // up later by ScheduledTaskService.retryDeferredInbounds.
            if (recentPoolChurn()) {
                entry.setIsProcessed(false);
                entry.setIsRejected(false);
                entry.setRejectReason(REASON_PENDING_POOL);
                logRepository.save(entry);
                log.info("Inbound mail deferred during pool churn: to={} (will retry)",
                        LogSafe.of(toAddr));
                return ProcessResult.rejected(REASON_PENDING_POOL);
            }
            return reject(entry, REASON_TO_NOT_IN_POOL);
        }

        // 2) FROM must map to a registered CRM user (case-insensitive email match)
        Optional<CrmUser> userOpt = userRepository.findByEmail(fromAddr.toLowerCase());
        if (!userOpt.isPresent()) userOpt = userRepository.findByEmail(fromAddr);
        if (!userOpt.isPresent()) {
            return reject(entry, REASON_FROM_NOT_A_USER);
        }
        CrmUser user = userOpt.get();
        entry.setMatchedUserId(user.getId());

        // H2: suspended / non-active users should not get inbound rows on their thread.
        // Operationally an inbound from a suspended user is a no-op — it cannot drive an
        // outbound reply (the dispatcher would not reach them) and pollutes the thread
        // view used for live-account triage. Log it instead.
        if (!CrmUser.STATUS_ACTIVE.equals(user.getStatus())) {
            return reject(entry, REASON_USER_NOT_ACTIVE);
        }

        // Stale-mail guard: if we have never sent this user anything, this inbound
        // is not actually a reply to us. The most common cause is a re-used SoftBank
        // inbox that still has unread mail from the address's previous owner. Our
        // operator policy is "always send first" so a user with zero OUT history
        // shouldn't have any IN traffic — silently drop and log the reason.
        long outCount = messageRepository.countByUserIdAndDirection(user.getId(), Message.DIR_OUT);
        if (outCount == 0) {
            return reject(entry, REASON_NO_OUT_HISTORY);
        }

        // 3) Pool/user binding must exist. Client confirmed 2026-05-27 that the receive-only
        //    relaxation was wrong for their workflow — unbound users (e.g. a user whose
        //    docomo addr they never assigned a softbank inbox to) were having stray inbound
        //    mail glued onto their thread. Re-enforce strict binding. During the brief window
        //    after an operator re-imports the pool (CASCADE drops bindings) we defer so the
        //    operator's follow-up bind ops aren't raced by inbound delivery.
        if (!bindingService.isBound(pool.get().getId(), user.getId())) {
            if (recentPoolChurn()) {
                entry.setMatchedUserId(user.getId());
                entry.setIsProcessed(false);
                entry.setIsRejected(false);
                entry.setRejectReason(REASON_PENDING_POOL);
                logRepository.save(entry);
                log.info("Inbound mail deferred — binding pending during pool churn: user={} to={}",
                        user.getId(), LogSafe.of(toAddr));
                return ProcessResult.rejected(REASON_PENDING_POOL);
            }
            return reject(entry, REASON_NOT_BOUND);
        }

        // Matched — create the inbound MESSAGE record on the user's thread.
        Message msg = new Message();
        msg.setUserId(user.getId());
        msg.setDirection(Message.DIR_IN);
        msg.setChannel(Message.CHANNEL_EMAIL);
        msg.setSubject(decoded.subject);
        msg.setBodyText(decoded.body);
        msg.setFromAddress(fromAddr);
        msg.setToAddress(toAddr);
        msg.setMessageIdHeader(dedupKey);
        msg.setStatus(Message.STATUS_SENT);
        msg.setSentAt(LocalDateTime.now());
        messageRepository.save(msg);

        userActivityService.touchLastLogin(user);

        entry.setIsProcessed(true);
        entry.setIsRejected(false);
        logRepository.save(entry);
        log.info("Inbound mail matched user {} ({}): subject=[{}]",
                user.getId(), LogSafe.of(user.getEmail()), LogSafe.of(msg.getSubject()));
        return ProcessResult.accepted(msg.getId(), user.getId());
    }

    private ProcessResult reject(InboundMailLog entry, String reason) {
        entry.setIsProcessed(false);
        entry.setIsRejected(true);
        entry.setRejectReason(reason);
        logRepository.save(entry);
        log.info("Inbound mail rejected: from={} to={} reason={}",
                LogSafe.of(entry.getFromAddress()), LogSafe.of(entry.getToAddress()), LogSafe.of(reason));
        return ProcessResult.rejected(reason);
    }

    /** True if the pool table has any row inserted within the last
     *  {@link #POOL_CHURN_MINUTES} minutes — taken as evidence that an operator
     *  delete-then-recreate (e.g. CSV pool re-import) is in progress. */
    private boolean recentPoolChurn() {
        java.time.LocalDateTime since = java.time.LocalDateTime.now().minusMinutes(POOL_CHURN_MINUTES);
        return poolRepository.existsByCreatedAtAfter(since);
    }

    /**
     * Re-evaluate a previously-deferred INBOUND_MAIL_LOG row now that the pool may have
     * settled. Called by the scheduled retry task. Operates on the EXISTING row (we don't
     * create a new one — the dedup key is preserved). Returns true if the row reached
     * a terminal state (matched OR expired), false if still pending.
     */
    @Transactional
    public boolean retryDeferred(InboundMailLog entry) {
        if (entry == null || !REASON_PENDING_POOL.equals(entry.getRejectReason())) return true;

        // Expired? Convert to final reject.
        java.time.LocalDateTime cutoff = java.time.LocalDateTime.now()
                .minusMinutes(DEFERRAL_EXPIRY_MINUTES);
        if (entry.getCreatedAt() != null && entry.getCreatedAt().isBefore(cutoff)) {
            entry.setIsProcessed(false);
            entry.setIsRejected(true);
            entry.setRejectReason(REASON_TO_NOT_IN_POOL);
            logRepository.save(entry);
            log.info("Inbound deferred entry expired after {}min: to={} (final reject)",
                    DEFERRAL_EXPIRY_MINUTES, LogSafe.of(entry.getToAddress()));
            return true;
        }

        // Re-attempt pool lookup with the same to_address.
        String toAddr = extractEmailAddress(safe(entry.getToAddress()).trim());
        Optional<CarrierAddressPool> pool = poolRepository.findByAddress(toAddr.toLowerCase());
        if (!pool.isPresent()) pool = poolRepository.findByAddress(toAddr);
        if (!pool.isPresent()) {
            // Still no match — leave deferred for next tick (unless we hit expiry above).
            return false;
        }

        // Pool now exists — finish the same downstream flow as process().
        String fromAddr = extractEmailAddress(safe(entry.getFromAddress()).trim());
        Optional<CrmUser> userOpt = userRepository.findByEmail(fromAddr.toLowerCase());
        if (!userOpt.isPresent()) userOpt = userRepository.findByEmail(fromAddr);
        if (!userOpt.isPresent()) {
            entry.setIsProcessed(false);
            entry.setIsRejected(true);
            entry.setRejectReason(REASON_FROM_NOT_A_USER);
            logRepository.save(entry);
            return true;
        }
        CrmUser user = userOpt.get();
        entry.setMatchedUserId(user.getId());
        if (!CrmUser.STATUS_ACTIVE.equals(user.getStatus())) {
            entry.setIsProcessed(false);
            entry.setIsRejected(true);
            entry.setRejectReason(REASON_USER_NOT_ACTIVE);
            logRepository.save(entry);
            return true;
        }
        long outCount = messageRepository.countByUserIdAndDirection(user.getId(), Message.DIR_OUT);
        if (outCount == 0) {
            entry.setIsProcessed(false);
            entry.setIsRejected(true);
            entry.setRejectReason(REASON_NO_OUT_HISTORY);
            logRepository.save(entry);
            return true;
        }

        if (!bindingService.isBound(pool.get().getId(), user.getId())) {
            entry.setIsProcessed(false);
            entry.setIsRejected(true);
            entry.setRejectReason(REASON_NOT_BOUND);
            logRepository.save(entry);
            return true;
        }

        Message msg = new Message();
        msg.setUserId(user.getId());
        msg.setDirection(Message.DIR_IN);
        msg.setChannel(Message.CHANNEL_EMAIL);
        msg.setSubject(entry.getSubject());
        msg.setBodyText(entry.getBodyText());
        msg.setFromAddress(fromAddr);
        msg.setToAddress(toAddr);
        msg.setMessageIdHeader(entry.getMessageIdHeader());
        msg.setStatus(Message.STATUS_SENT);
        msg.setSentAt(java.time.LocalDateTime.now());
        messageRepository.save(msg);

        userActivityService.touchLastLogin(user);

        entry.setIsProcessed(true);
        entry.setIsRejected(false);
        entry.setRejectReason(null);
        logRepository.save(entry);
        log.info("Inbound deferred entry recovered: from={} to={} user={} (msg {})",
                LogSafe.of(entry.getFromAddress()), LogSafe.of(entry.getToAddress()),
                user.getId(), msg.getId());
        return true;
    }

    /** Public lister so the scheduler can find what's pending. */
    public java.util.List<InboundMailLog> listPendingPool() {
        return logRepository.findByRejectReasonAndIsProcessedFalse(REASON_PENDING_POOL);
    }

    private static String localPartOf(String email) {
        if (email == null) return null;
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : null;
    }

    /**
     * Extract the bare {@code local@domain} address from a possibly-decorated header value.
     * Accepts forms like:
     *   {@code "Display Name <user@example.com>"} → {@code "user@example.com"}
     *   {@code "<MAILER-DAEMON@docomo.ne.jp>"}     → {@code "MAILER-DAEMON@docomo.ne.jp"}
     *   {@code "  user@example.com  "}             → {@code "user@example.com"}
     *   {@code "tt tt <user@gmail.com>, alt@x"}    → {@code "user@gmail.com"} (first addr only)
     * Falls back to the trimmed input when no angle brackets are present and no whitespace
     * is detected — keeps already-clean addresses intact.
     */
    static String extractEmailAddress(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        // Prefer the contents of the LAST <...> in the value (RFC5322 angle-addr form).
        int gt = s.lastIndexOf('>');
        int lt = gt > 0 ? s.lastIndexOf('<', gt) : -1;
        if (lt >= 0 && gt > lt) {
            s = s.substring(lt + 1, gt).trim();
        }
        // Strip surrounding quotes (some clients quote the addr itself).
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            s = s.substring(1, s.length() - 1).trim();
        }
        // If a comma-separated list slipped through, keep only the first address.
        int comma = s.indexOf(',');
        if (comma > 0) s = s.substring(0, comma).trim();
        // Drop any stray whitespace inside (e.g. "user @example.com").
        s = s.replaceAll("\\s+", "");
        return s;
    }

    private static String normaliseMessageId(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.startsWith("<") && v.endsWith(">")) v = v.substring(1, v.length() - 1).trim();
        if (v.isEmpty()) return null;
        if (v.length() > 255) v = v.substring(0, 255);
        return v;
    }

    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
    private static String safe(String s) { return s == null ? "" : s; }
    private static String truncate(String s, int n) {
        return s == null ? null : (s.length() <= n ? s : s.substring(0, n));
    }

    /** SHA-256 hex of "from|to|subject|body|bucket" — used as dedup key when Message-ID is absent. */
    private static String fingerprint(String from, String to, String subject, String body, String timeBucket) {
        String in = safe(from) + "|" + safe(to) + "|" + safe(subject) + "|" + safe(body) + "|" + safe(timeBucket);
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(in.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return Integer.toHexString(in.hashCode());
        }
    }

    /** Subject + body after RFC2047 / MIME-aware decoding. */
    private static final class DecodedMail {
        final String subject;
        final String body;
        DecodedMail(String s, String b) { this.subject = s; this.body = b; }
    }

    /**
     * Parse the raw RFC822 message via jakarta.mail and return a decoded Subject + body.
     * Handles RFC2047 encoded-words in Subject and Content-Transfer-Encoding (base64 / qp)
     * + Content-Type charset on the body. For multipart/alternative the text/plain part wins.
     * Falls back to the pre-extracted bash-pipe values if {@code raw} is empty or parsing fails.
     */
    private static DecodedMail decodeFromRaw(String raw, String fallbackSubject, String fallbackBody) {
        if (raw == null || raw.isEmpty()) {
            return new DecodedMail(fallbackSubject, fallbackBody);
        }
        try {
            javax.mail.Session session = javax.mail.Session.getInstance(new java.util.Properties());
            javax.mail.internet.MimeMessage msg = new javax.mail.internet.MimeMessage(
                    session, new java.io.ByteArrayInputStream(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            String subj = msg.getSubject();
            String body = extractTextBody(msg);
            return new DecodedMail(
                    subj == null ? fallbackSubject : subj,
                    body == null ? fallbackBody : body);
        } catch (Exception e) {
            log.debug("Inbound: raw MIME decode failed, using bash-extracted fallback. err={}", e.toString());
            return new DecodedMail(fallbackSubject, fallbackBody);
        }
    }

    /** Walk the MIME tree and return the first text/plain (preferred) or text/html body. */
    private static String extractTextBody(javax.mail.Part part) throws Exception {
        if (part.isMimeType("text/plain")) {
            Object c = part.getContent();
            return c == null ? null : c.toString();
        }
        if (part.isMimeType("multipart/*")) {
            javax.mail.Multipart mp = (javax.mail.Multipart) part.getContent();
            // text/plain preferred — search children twice, first for plain then for html.
            for (int i = 0; i < mp.getCount(); i++) {
                javax.mail.BodyPart bp = mp.getBodyPart(i);
                if (bp.isMimeType("text/plain")) {
                    Object c = bp.getContent();
                    if (c != null) return c.toString();
                }
            }
            for (int i = 0; i < mp.getCount(); i++) {
                javax.mail.BodyPart bp = mp.getBodyPart(i);
                String s = extractTextBody(bp);
                if (s != null) return s;
            }
        }
        if (part.isMimeType("text/html")) {
            Object c = part.getContent();
            return c == null ? null : stripHtml(c.toString());
        }
        return null;
    }

    /** Last-resort HTML → plaintext (preserves line breaks). Used only if the MIME has no text/plain. */
    private static String stripHtml(String html) {
        if (html == null) return null;
        return html.replaceAll("(?is)<br\\s*/?\\s*>", "\n")
                   .replaceAll("(?is)</p\\s*>", "\n")
                   .replaceAll("(?is)<[^>]+>", "")
                   .replace("&nbsp;", " ")
                   .replace("&lt;", "<")
                   .replace("&gt;", ">")
                   .replace("&quot;", "\"")
                   .replace("&#39;", "'")
                   .replace("&amp;", "&");
    }

    public static class ProcessResult {
        public final boolean accepted;
        public final String reason;
        public final Long messageId;
        public final Long userId;
        private ProcessResult(boolean a, String r, Long mid, Long uid) {
            accepted = a; reason = r; messageId = mid; userId = uid;
        }
        public static ProcessResult accepted(Long messageId, Long userId) {
            return new ProcessResult(true, null, messageId, userId);
        }
        public static ProcessResult rejected(String reason) {
            return new ProcessResult(false, reason, null, null);
        }
    }
}
