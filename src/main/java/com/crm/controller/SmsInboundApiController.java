package com.crm.controller;

import com.crm.service.SmsInboundService;
import com.crm.service.SmsSettingService;
import com.crm.util.LogSafe;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Webhook for BytePlus SMS to push mobile-originated (inbound reply) messages into the CRM.
 *
 * Auth: BytePlus's MO webhook has no documented request-signing scheme, so the callback URL
 * itself carries a random shared-secret path segment (see {@link SmsSettingService#getOrCreateInboundToken()}).
 * Register {@code https://<host>/api/inbound/sms/<token>} as the "Default uplink address" in the
 * BytePlus console under Settings > Webhook Settings.
 *
 * Always responds 200 except on a bad token or an unexpected server error, per BytePlus's
 * retry policy (non-200 triggers up to 9 retries) — an unmatched phone number is not
 * something a retry would fix, so it's still acknowledged with 200.
 */
@RestController
@RequestMapping("/api/inbound")
public class SmsInboundApiController {

    private static final Logger log = LoggerFactory.getLogger(SmsInboundApiController.class);

    private final SmsInboundService smsInboundService;
    private final SmsSettingService smsSettingService;
    private final ObjectMapper objectMapper;

    public SmsInboundApiController(SmsInboundService smsInboundService,
                                    SmsSettingService smsSettingService,
                                    ObjectMapper objectMapper) {
        this.smsInboundService = smsInboundService;
        this.smsSettingService = smsSettingService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/sms/{token}")
    public ResponseEntity<Map<String, Object>> receive(@PathVariable String token,
                                                         @RequestBody(required = false) String rawBody) {
        String expected = smsSettingService.getOrCreateInboundToken();
        if (expected == null || !expected.equals(token)) {
            log.warn("Rejected inbound SMS webhook call with invalid token");
            Map<String, Object> body = new HashMap<>();
            body.put("accepted", false);
            body.put("reason", "INVALID_TOKEN");
            return ResponseEntity.status(403).body(body);
        }

        Map<String, Object> resp = new HashMap<>();
        try {
            JsonNode root = (rawBody == null || rawBody.trim().isEmpty())
                    ? null : objectMapper.readTree(rawBody);
            List<SmsInboundService.Result> results = smsInboundService.process(root);
            resp.put("accepted", true);
            resp.put("processed", results.size());
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.error("[BYTEPLUS SMS] inbound webhook processing error: {}", LogSafe.of(e.toString()), e);
            resp.put("accepted", false);
            resp.put("reason", "SERVER_ERROR");
            return ResponseEntity.status(500).body(resp);
        }
    }
}
