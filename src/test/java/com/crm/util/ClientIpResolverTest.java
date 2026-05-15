package com.crm.util;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security-relevant: an untrusted peer must NOT be able to spoof XFF/X-Real-IP. Trust is
 * granted only when the immediate connection comes from loopback (nginx on same host).
 */
class ClientIpResolverTest {

    @Test
    void untrustedPeer_xffHeaderIgnored() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("203.0.113.5");
        req.addHeader("X-Forwarded-For", "1.2.3.4");
        assertThat(ClientIpResolver.resolve(req)).isEqualTo("203.0.113.5");
    }

    @Test
    void untrustedPeer_xRealIpIgnored() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("203.0.113.5");
        req.addHeader("X-Real-IP", "9.9.9.9");
        assertThat(ClientIpResolver.resolve(req)).isEqualTo("203.0.113.5");
    }

    @Test
    void loopbackPeer_honoursXForwardedFor() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("127.0.0.1");
        req.addHeader("X-Forwarded-For", "1.2.3.4");
        assertThat(ClientIpResolver.resolve(req)).isEqualTo("1.2.3.4");
    }

    @Test
    void loopbackPeer_xForwardedForCanCarryClientChain_firstHopIsClient() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("127.0.0.1");
        req.addHeader("X-Forwarded-For", "1.2.3.4, 10.0.0.5");
        // Leftmost is the original client (per the contract enforced by upstream nginx).
        assertThat(ClientIpResolver.resolve(req)).isEqualTo("1.2.3.4");
    }

    @Test
    void loopbackPeer_fallsBackToXRealIpWhenXffMissing() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("127.0.0.1");
        req.addHeader("X-Real-IP", "8.8.8.8");
        assertThat(ClientIpResolver.resolve(req)).isEqualTo("8.8.8.8");
    }

    @Test
    void loopbackPeer_noHeaders_returnsLoopback() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("127.0.0.1");
        assertThat(ClientIpResolver.resolve(req)).isEqualTo("127.0.0.1");
    }

    @Test
    void ipv6LoopbackIsTrustedToo() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("::1");
        req.addHeader("X-Forwarded-For", "5.5.5.5");
        assertThat(ClientIpResolver.resolve(req)).isEqualTo("5.5.5.5");
    }
}
