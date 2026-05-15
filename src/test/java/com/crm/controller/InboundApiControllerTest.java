package com.crm.controller;

import com.crm.dto.InboundMailDto;
import com.crm.service.InboundMailService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * IP allow-list semantics on the inbound webhook. The CRM is publicly reachable, so
 * skipping this check would let anyone push a forged "reply" into a user's thread.
 */
class InboundApiControllerTest {

    private InboundMailService svc = mock(InboundMailService.class);

    private static InboundMailDto sampleDto() {
        InboundMailDto d = new InboundMailDto();
        d.setFrom("alice@example.com");
        d.setTo("rifc6h1c65@avu74g.jp");
        d.setSubject("hi");
        d.setBody("body");
        return d;
    }

    @Test
    void rejectsConnectionFromIpNotInAllowList() {
        InboundApiController c = new InboundApiController(svc, "157.7.89.36, 133.88.116.190");
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("203.0.113.5");  // not in allow-list

        ResponseEntity<?> resp = c.receiveRaw(sampleDto(), req);
        assertThat(resp.getStatusCodeValue()).isEqualTo(403);
        verify(svc, never()).process(any());
    }

    @Test
    void acceptsConnectionFromAllowedIp() {
        InboundApiController c = new InboundApiController(svc, "157.7.89.36");
        when(svc.process(any())).thenReturn(InboundMailService.ProcessResult.accepted(1L, 7L));
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("157.7.89.36");

        ResponseEntity<?> resp = c.receiveRaw(sampleDto(), req);
        assertThat(resp.getStatusCodeValue()).isEqualTo(200);
        verify(svc).process(any());
    }

    @Test
    void emptyAllowList_acceptsAnyIp_devConvenience() {
        // Production should always have a populated allow-list; the empty fallback exists for
        // local dev/test convenience and is intentional. We assert it stays permissive so this
        // contract is explicit.
        InboundApiController c = new InboundApiController(svc, "");
        when(svc.process(any())).thenReturn(InboundMailService.ProcessResult.accepted(1L, 7L));
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("203.0.113.99");

        assertThat(c.receiveRaw(sampleDto(), req).getStatusCodeValue()).isEqualTo(200);
    }

    @Test
    void allowList_trimsWhitespaceAroundCsvEntries() {
        InboundApiController c = new InboundApiController(svc, "  157.7.89.36  ,  133.88.116.190  ");
        when(svc.process(any())).thenReturn(InboundMailService.ProcessResult.accepted(1L, 7L));
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("157.7.89.36");

        assertThat(c.receiveRaw(sampleDto(), req).getStatusCodeValue()).isEqualTo(200);
    }

    @Test
    void rejectionPayload_includesIpNotAllowedReason() {
        InboundApiController c = new InboundApiController(svc, "157.7.89.36");
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("203.0.113.99");

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> body =
                (java.util.Map<String, Object>) c.receiveRaw(sampleDto(), req).getBody();
        assertThat(body).containsEntry("accepted", false);
        assertThat(body).containsEntry("reason", "IP_NOT_ALLOWED");
    }

    @Test
    void inboundService_rejectionReason_propagatedToResponse() {
        InboundApiController c = new InboundApiController(svc, "");
        when(svc.process(any())).thenReturn(
                InboundMailService.ProcessResult.rejected(InboundMailService.REASON_TO_NOT_IN_POOL));
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("1.2.3.4");

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> body =
                (java.util.Map<String, Object>) c.receiveRaw(sampleDto(), req).getBody();
        assertThat(body)
                .containsEntry("accepted", false)
                .containsEntry("reason", InboundMailService.REASON_TO_NOT_IN_POOL);
    }
}
