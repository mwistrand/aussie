package aussie.common.context;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ClientContextTest {

    @Test
    void usesForwardedIpForPreAuthenticationRateLimitIdentity() {
        final var context = new ClientContext("10.0.0.1", true, "203.0.113.10");

        assertEquals("ip:203.0.113.10", context.rateLimitClientId());
    }

    @Test
    void ignoresForwardedIpWhenForwardingHeadersAreUntrusted() {
        final var context = new ClientContext("10.0.0.1", false, "203.0.113.10");

        assertEquals("ip:10.0.0.1", context.rateLimitClientId());
    }

    @Test
    void usesUnknownSentinelWhenPeerAddressIsUnavailable() {
        assertEquals("ip:unknown", new ClientContext(null, false, null).rateLimitClientId());
    }
}
