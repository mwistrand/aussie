package aussie.core.service.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.common.TrustedProxyConfig;

@DisplayName("TrustedProxyValidator")
class TrustedProxyValidatorTest {

    @Nested
    @DisplayName("When disabled")
    class DisabledTests {

        @Test
        @DisplayName("should always trust forwarding headers")
        void shouldAlwaysTrust() {
            var validator = createValidator(false, null);

            assertTrue(validator.shouldTrustForwardingHeaders("1.2.3.4"));
            assertTrue(validator.shouldTrustForwardingHeaders("192.168.1.1"));
            assertTrue(validator.shouldTrustForwardingHeaders(null));
        }
    }

    @Nested
    @DisplayName("When enabled")
    class EnabledTests {

        @Test
        @DisplayName("should reject null socket IP")
        void shouldRejectNullSocketIp() {
            var validator = createValidator(true, List.of("10.0.0.0/8"));

            assertFalse(validator.shouldTrustForwardingHeaders(null));
        }

        @Test
        @DisplayName("should reject empty socket IP")
        void shouldRejectEmptySocketIp() {
            var validator = createValidator(true, List.of("10.0.0.0/8"));

            assertFalse(validator.shouldTrustForwardingHeaders(""));
        }

        @Test
        @DisplayName("should reject when no proxies configured")
        void shouldRejectWhenNoProxies() {
            var validator = createValidator(true, List.of());

            assertFalse(validator.shouldTrustForwardingHeaders("10.0.0.1"));
        }

        @Test
        @DisplayName("should reject when proxies list is absent")
        void shouldRejectWhenProxiesAbsent() {
            var validator = createValidator(true, null);

            assertFalse(validator.shouldTrustForwardingHeaders("10.0.0.1"));
        }

        @Test
        @DisplayName("should trust exact IP match")
        void shouldTrustExactIpMatch() {
            var validator = createValidator(true, List.of("10.0.0.1"));

            assertTrue(validator.shouldTrustForwardingHeaders("10.0.0.1"));
        }

        @Test
        @DisplayName("should reject non-matching exact IP")
        void shouldRejectNonMatchingExactIp() {
            var validator = createValidator(true, List.of("10.0.0.1"));

            assertFalse(validator.shouldTrustForwardingHeaders("10.0.0.2"));
        }

        @Test
        @DisplayName("should trust IP within CIDR range")
        void shouldTrustIpWithinCidr() {
            var validator = createValidator(true, List.of("10.0.0.0/8"));

            assertTrue(validator.shouldTrustForwardingHeaders("10.255.255.255"));
            assertTrue(validator.shouldTrustForwardingHeaders("10.0.0.1"));
        }

        @Test
        @DisplayName("should reject IP outside CIDR range")
        void shouldRejectIpOutsideCidr() {
            var validator = createValidator(true, List.of("10.0.0.0/8"));

            assertFalse(validator.shouldTrustForwardingHeaders("11.0.0.1"));
            assertFalse(validator.shouldTrustForwardingHeaders("192.168.1.1"));
        }

        @Test
        @DisplayName("should match /24 subnet correctly")
        void shouldMatchSubnet24() {
            var validator = createValidator(true, List.of("192.168.1.0/24"));

            assertTrue(validator.shouldTrustForwardingHeaders("192.168.1.1"));
            assertTrue(validator.shouldTrustForwardingHeaders("192.168.1.254"));
            assertFalse(validator.shouldTrustForwardingHeaders("192.168.2.1"));
        }

        @Test
        @DisplayName("should support multiple proxy patterns")
        void shouldSupportMultiplePatterns() {
            var validator = createValidator(true, List.of("10.0.0.0/8", "172.16.0.0/12", "192.168.0.1"));

            assertTrue(validator.shouldTrustForwardingHeaders("10.0.0.1"));
            assertTrue(validator.shouldTrustForwardingHeaders("172.16.0.1"));
            assertTrue(validator.shouldTrustForwardingHeaders("192.168.0.1"));
            assertFalse(validator.shouldTrustForwardingHeaders("203.0.113.50"));
        }

        @Test
        @DisplayName("should handle invalid CIDR gracefully")
        void shouldHandleInvalidCidr() {
            var validator = createValidator(true, List.of("not-a-cidr/invalid"));

            assertFalse(validator.shouldTrustForwardingHeaders("10.0.0.1"));
        }

        @Test
        @DisplayName("should handle loopback addresses")
        void shouldHandleLoopback() {
            var validator = createValidator(true, List.of("127.0.0.1"));

            assertTrue(validator.shouldTrustForwardingHeaders("127.0.0.1"));
            assertFalse(validator.shouldTrustForwardingHeaders("127.0.0.2"));
        }

        @Test
        @DisplayName("should reject invalid prefix length for IPv4")
        void shouldRejectInvalidPrefixLengthIpv4() {
            var validator = createValidator(true, List.of("10.0.0.0/33"));

            assertFalse(validator.shouldTrustForwardingHeaders("10.0.0.1"));
        }

        @Test
        @DisplayName("should not resolve hostnames in proxy list")
        void shouldNotResolveHostnames() {
            var validator = createValidator(true, List.of("localhost"));

            // "localhost" is not an IP literal, so it should not match anything
            assertFalse(validator.shouldTrustForwardingHeaders("127.0.0.1"));
        }

        @Test
        @DisplayName("should not resolve hostname socket IPs")
        void shouldNotResolveHostnameSocketIps() {
            var validator = createValidator(true, List.of("127.0.0.1"));

            assertFalse(validator.shouldTrustForwardingHeaders("localhost"));
        }
    }

    @Nested
    @DisplayName("IPv6 support")
    class Ipv6Tests {

        @Test
        @DisplayName("should trust exact IPv6 match")
        void shouldTrustExactIpv6Match() {
            var validator = createValidator(true, List.of("::1"));

            assertTrue(validator.shouldTrustForwardingHeaders("::1"));
        }

        @Test
        @DisplayName("should trust IPv6 within CIDR range")
        void shouldTrustIpv6WithinCidr() {
            var validator = createValidator(true, List.of("fd00::/8"));

            assertTrue(validator.shouldTrustForwardingHeaders("fd12::1"));
            assertTrue(validator.shouldTrustForwardingHeaders("fd00::1"));
        }

        @Test
        @DisplayName("should reject IPv6 outside CIDR range")
        void shouldRejectIpv6OutsideCidr() {
            var validator = createValidator(true, List.of("fd00::/8"));

            assertFalse(validator.shouldTrustForwardingHeaders("fe80::1"));
        }

        @Test
        @DisplayName("should not match IPv4 source against IPv6 CIDR")
        void shouldNotMatchIpv4AgainstIpv6Cidr() {
            var validator = createValidator(true, List.of("fd00::/8"));

            assertFalse(validator.shouldTrustForwardingHeaders("10.0.0.1"));
        }

        @Test
        @DisplayName("should reject invalid prefix length for IPv6")
        void shouldRejectInvalidPrefixLengthIpv6() {
            var validator = createValidator(true, List.of("fd00::/129"));

            assertFalse(validator.shouldTrustForwardingHeaders("fd00::1"));
        }
    }

    private static TrustedProxyValidator createValidator(boolean enabled, List<String> proxies) {
        var config = new TestTrustedProxyConfig(enabled, proxies);
        return new TrustedProxyValidator(config);
    }

    private record TestTrustedProxyConfig(boolean enabled, List<String> proxyList) implements TrustedProxyConfig {
        @Override
        public Optional<List<String>> proxies() {
            return Optional.ofNullable(proxyList);
        }
    }
}
