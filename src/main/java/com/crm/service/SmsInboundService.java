package com.crm.service;

import com.crm.entity.CrmUser;
import com.crm.entity.Message;
import com.crm.repository.CrmUserRepository;
import com.crm.repository.MessageRepository;
import com.crm.util.JapanesePhoneNumbers;
import com.crm.util.LogSafe;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Processes BytePlus SMS mobile-originated (MO / inbound reply) webhook payloads.
 *
 * BytePlus's public docs (as of 2026-07-08) describe how to configure the callback URL
 * ("Default uplink address") but not the exact MO JSON field names — only the sibling
 * delivery-receipt payload is documented in detail. We therefore parse defensively: try
 * several plausible field-name aliases (informed by the documented DLR schema, which uses
 * "mobile" for the phone number), and always log the raw payload so the real field names
 * can be confirmed/adjusted against the first live webhook call.
 */
@Service
public class SmsInboundService {

    private static final Logger log = LoggerFactory.getLogger(SmsInboundService.class);

    private static final String[] SENDER_KEYS = {"mobile", "from_mobile", "sender", "msisdn", "phone", "from"};
    private static final String[] CONTENT_KEYS = {"content", "text", "msg", "message", "body"};
    private static final String[] MSGID_KEYS = {"message_id", "msg_id", "id"};

    private final CrmUserRepository userRepository;
    private final MessageRepository messageRepository;
    private final SmsSettingService smsSettingService;

    public SmsInboundService(CrmUserRepository userRepository,
                              MessageRepository messageRepository,
                              SmsSettingService smsSettingService) {
        this.userRepository = userRepository;
        this.messageRepository = messageRepository;
        this.smsSettingService = smsSettingService;
    }

    public static class Result {
        public final boolean accepted;
        public final String reason;
        private Result(boolean accepted, String reason) { this.accepted = accepted; this.reason = reason; }
        static Result ok() { return new Result(true, null); }
        static Result skip(String reason) { return new Result(false, reason); }
    }

    /** {@code root} may be a single JSON object or an array of them (BytePlus batches DLRs this way). */
    @Transactional
    public List<Result> process(JsonNode root) {
        List<Result> results = new ArrayList<>();
        if (root == null || root.isMissingNode() || root.isNull()) {
            results.add(Result.skip("empty_payload"));
            return results;
        }
        if (root.isArray()) {
            for (JsonNode node : root) results.add(processOne(node));
        } else {
            results.add(processOne(root));
        }
        return results;
    }

    private Result processOne(JsonNode node) {
        String rawJson = node.toString();
        String senderRaw = firstNonBlank(node, SENDER_KEYS);
        String contentRaw = firstNonBlank(node, CONTENT_KEYS);
        String msgId = firstNonBlank(node, MSGID_KEYS);

        if (senderRaw == null) {
            log.warn("[BYTEPLUS SMS] inbound payload missing a recognisable sender field, raw={}",
                    LogSafe.of(truncate(rawJson, 1000)));
            return Result.skip("missing_sender");
        }

        if (msgId != null && messageRepository.existsByMessageIdHeader("byteplus-mo:" + msgId)) {
            log.info("[BYTEPLUS SMS] inbound skipped as duplicate: msgId={}", LogSafe.of(msgId));
            return Result.skip("duplicate");
        }

        String domestic = JapanesePhoneNumbers.toDomestic(senderRaw);
        Optional<CrmUser> userOpt = userRepository.findByPhoneNumber(domestic);
        if (!userOpt.isPresent()) {
            log.warn("[BYTEPLUS SMS] inbound from unregistered number={} raw={}",
                    LogSafe.of(domestic), LogSafe.of(truncate(rawJson, 1000)));
            return Result.skip("phone_not_registered");
        }

        CrmUser user = userOpt.get();
        Message m = new Message();
        m.setUserId(user.getId());
        m.setDirection(Message.DIR_IN);
        m.setChannel(Message.CHANNEL_SMS);
        m.setFromAddress(domestic);
        m.setToAddress(smsSettingService.getSenderName());
        m.setBodyText(contentRaw == null ? "" : contentRaw);
        m.setStatus(Message.STATUS_SENT);
        if (msgId != null) m.setMessageIdHeader("byteplus-mo:" + msgId);
        messageRepository.save(m);

        log.info("[BYTEPLUS SMS] inbound matched: user={} from={}", user.getId(), LogSafe.of(domestic));
        return Result.ok();
    }

    private static String firstNonBlank(JsonNode node, String[] keys) {
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v != null && !v.isNull() && !v.asText().trim().isEmpty()) return v.asText().trim();
        }
        return null;
    }

    private static String truncate(String s, int n) {
        return s == null ? "" : (s.length() <= n ? s : s.substring(0, n) + "...");
    }
}
