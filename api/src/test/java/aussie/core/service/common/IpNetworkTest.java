package aussie.core.service.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("IpNetwork")
class IpNetworkTest {

    @Test
    @DisplayName("parses exact IPv4 and IPv6 literals without accepting host names")
    void parsesOnlyIpLiterals() {
        assertTrue(IpNetwork.parse("192.0.2.10").isPresent());
        assertTrue(IpNetwork.parse("2001:db8::10").isPresent());
        assertFalse(IpNetwork.parse("localhost").isPresent());
        assertFalse(IpNetwork.parse("example.com").isPresent());
        assertFalse(IpNetwork.parse("fe80::1%eth0").isPresent());
    }

    @Test
    @DisplayName("normalizes IPv4-mapped IPv6 literals to the IPv4 address family")
    void normalizesIpv4MappedIpv6() {
        var ipv4 = IpNetwork.parse("192.0.2.10").orElseThrow();
        var mapped = IpNetwork.parse("::ffff:192.0.2.10").orElseThrow();

        assertEquals(ipv4, mapped);
        assertTrue(IpNetwork.parse("192.0.2.0/24").orElseThrow().contains(mapped));
    }

    @Test
    @DisplayName("rejects malformed addresses and invalid prefix lengths")
    void rejectsInvalidInput() {
        assertFalse(IpNetwork.parse("999.1.1.1").isPresent());
        assertFalse(IpNetwork.parse("10.0.0.0/-1").isPresent());
        assertFalse(IpNetwork.parse("10.0.0.0/33").isPresent());
        assertFalse(IpNetwork.parse("2001:db8::/129").isPresent());
        assertFalse(IpNetwork.parse("10.0.0.0/8/1").isPresent());
    }

    @Test
    @DisplayName("matches non-byte-aligned IPv4 and IPv6 prefixes")
    void matchesNonByteAlignedPrefixes() {
        var ipv4 = IpNetwork.parse("192.168.16.0/20").orElseThrow();
        var ipv6 = IpNetwork.parse("2001:db8:8000::/33").orElseThrow();

        assertTrue(ipv4.contains("192.168.31.255"));
        assertFalse(ipv4.contains("192.168.32.0"));
        assertTrue(ipv6.contains("2001:db8:ffff::1"));
        assertFalse(ipv6.contains("2001:db9::1"));
    }

    @Test
    @DisplayName("determines complete network containment")
    void determinesNetworkContainment() {
        var global = IpNetwork.parse("10.0.0.0/8").orElseThrow();

        assertTrue(global.contains(IpNetwork.parse("10.10.0.0/16").orElseThrow()));
        assertTrue(global.contains(IpNetwork.parse("10.1.2.3").orElseThrow()));
        assertFalse(global.contains(IpNetwork.parse("0.0.0.0/0").orElseThrow()));
        assertFalse(global.contains(IpNetwork.parse("11.0.0.0/8").orElseThrow()));
        assertFalse(global.contains(IpNetwork.parse("::/0").orElseThrow()));
    }
}
