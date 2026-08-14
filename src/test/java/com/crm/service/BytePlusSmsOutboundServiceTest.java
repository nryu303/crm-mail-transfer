package com.crm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Guard-clause coverage for the relay-delivery mode (2026-07-13): when
 * sms.local_delivery_enabled=false, send() must route via the relay's /api/sms/send
 * instead of calling BytePlus directly. The actual HTTP call isn't mocked here (no
 * HTTP-mocking library in this project) — these tests cover the fail-fast config
 * checks that don't require a live network call.
 */
class BytePlusSmsOutboundServiceTest {

    private SmsSettingService smsSettingService;
    private BytePlusSmsOutboundService svc;

    @BeforeEach
    void setUp() {
        smsSettingService = mock(SmsSettingService.class);
        svc = new BytePlusSmsOutboundService(new ObjectMapper(), smsSettingService, 3000, 3000);
    }

    private static OutboundSmsService.SmsSendRequest req() {
        return new OutboundSmsService.SmsSendRequest("user", "pass", "CRM01", "09012345678", "hello");
    }

    @Test
    void send_missingCredentials_failsWithoutCallingAnything() {
        OutboundSmsService.SmsSendRequest r =
                new OutboundSmsService.SmsSendRequest("", "", "CRM01", "09012345678", "hello");
        OutboundSmsService.SendResult result = svc.send(r);
        assertThat(result.success).isFalse();
        assertThat(result.errorMessage).contains("設定");
    }

    @Test
    void send_relayMode_missingRelayIp_failsFast() {
        when(smsSettingService.isLocalDelivery()).thenReturn(false);
        when(smsSettingService.getRelayIp()).thenReturn(null);

        OutboundSmsService.SendResult result = svc.send(req());

        assertThat(result.success).isFalse();
        assertThat(result.errorMessage).contains("転送機IP");
    }

    @Test
    void send_relayMode_missingRelayToken_failsFast() {
        when(smsSettingService.isLocalDelivery()).thenReturn(false);
        when(smsSettingService.getRelayIp()).thenReturn("133.88.116.190");
        when(smsSettingService.getRelayToken()).thenReturn("");

        OutboundSmsService.SendResult result = svc.send(req());

        assertThat(result.success).isFalse();
        assertThat(result.errorMessage).contains("転送機トークン");
    }
}
