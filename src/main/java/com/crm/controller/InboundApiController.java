package com.crm.controller;

import com.crm.dto.InboundMailDto;
import com.crm.service.InboundMailService;
import com.crm.util.ClientIpResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import javax.validation.Valid;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Webhook for AMG to push received mail into the CRM.
 *
 * Auth: not session-protected; instead enforces an IP allow-list from
 * {@code app.amg-ips} (application.yml). Nginx should also enforce the allow-list
 * defensively in front, but we do not rely on it exclusively.
 *
 * When {@code app.amg-ips} is empty the allow-list is considered unset and requests
 * are permitted (dev/test convenience only).
 */
@RestController
@RequestMapping("/api/inbound")
public class InboundApiController {

    private static final Logger log = LoggerFactory.getLogger(InboundApiController.class);

    private final InboundMailService inboundMailService;
    private final Set<String> allowedIps;

    public InboundApiController(InboundMailService inboundMailService,
                                @Value("${app.amg-ips:}") String amgIpsCsv) {
        this.inboundMailService = inboundMailService;
        Set<String> set = new HashSet<>();
        if (amgIpsCsv != null && !amgIpsCsv.trim().isEmpty()) {
            for (String ip : amgIpsCsv.split(",")) {
                if (ip != null && !ip.trim().isEmpty()) set.add(ip.trim());
            }
        }
        this.allowedIps = Collections.unmodifiableSet(set);
        if (set.isEmpty()) {
            log.error("app.amg-ips is empty — all inbound webhook calls will be REJECTED. "
                    + "Set AMG_IPS env var to a CSV of permitted source IPs (include 127.0.0.1 for local IMAP fetcher).");
        } else {
            log.info("Inbound webhook IP allow-list: {}", set);
        }
    }

    @PostMapping(value = "/receive-raw", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> receiveRaw(@Valid @RequestBody InboundMailDto dto,
                                                          HttpServletRequest request) {
        // Fail-closed: an empty allow-list (misconfiguration) now rejects all calls. The previous
        // behaviour silently permitted everything when AMG_IPS was unset — too risky in prod.
        String clientIp = ClientIpResolver.resolve(request);
        if (!allowedIps.contains(clientIp)) {
            log.warn("Rejected inbound from non-AMG IP {} (allow-list size={})", clientIp, allowedIps.size());
            Map<String, Object> body = new HashMap<>();
            body.put("accepted", false);
            body.put("reason", "IP_NOT_ALLOWED");
            return ResponseEntity.status(403).body(body);
        }
        InboundMailService.ProcessResult result = inboundMailService.process(dto);
        Map<String, Object> resp = new HashMap<>();
        resp.put("accepted", result.accepted);
        if (result.accepted) {
            resp.put("messageId", result.messageId);
            resp.put("userId", result.userId);
        } else {
            resp.put("reason", result.reason);
        }
        return ResponseEntity.ok(resp);
    }

}
