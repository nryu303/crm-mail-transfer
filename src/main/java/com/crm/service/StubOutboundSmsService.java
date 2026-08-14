package com.crm.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * No-op SMS adapter. Logs the attempted send and always reports success.
 * Active when {@code app.sms.adapter=stub} (the default until BytePlus credentials are set up).
 */
@Service
@Primary
@ConditionalOnProperty(name = "app.sms.adapter", havingValue = "stub", matchIfMissing = true)
public class StubOutboundSmsService implements OutboundSmsService {

    private static final Logger log = LoggerFactory.getLogger(StubOutboundSmsService.class);

    @Override
    public SendResult send(SmsSendRequest req) {
        log.info("[STUB SMS] to={} sender={} user={} body={}",
                req.toPhoneNumber, req.senderName, req.username, req.body);
        return SendResult.ok();
    }
}
