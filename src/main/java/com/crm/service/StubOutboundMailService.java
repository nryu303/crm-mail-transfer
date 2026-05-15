package com.crm.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * No-op adapter. Logs the attempted send and always reports success.
 * Active when {@code app.outbound.adapter=stub} (the default in dev/test).
 */
@Service
@Primary
@ConditionalOnProperty(name = "app.outbound.adapter", havingValue = "stub", matchIfMissing = true)
public class StubOutboundMailService implements OutboundMailService {

    private static final Logger log = LoggerFactory.getLogger(StubOutboundMailService.class);

    @Override
    public SendResult send(OutboundRequest req) {
        log.info("[STUB MAIL] from={} to={} host={}:{} user={} subject={}",
                req.fromAddress, req.toAddress, req.smtpHost, req.smtpPort,
                req.smtpUsername, req.subject);
        return SendResult.ok();
    }
}
