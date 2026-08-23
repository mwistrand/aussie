package aussie.core.service.routing;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Applies the gateway's SSRF address policy to upstream hosts.
 */
public final class UpstreamAddressPolicy {

    private UpstreamAddressPolicy() {}

    /**
     * Check whether an upstream host resolves to an address the gateway must not route to.
     *
     * <p>Loopback, link-local, and wildcard addresses are always blocked. Site-local
     * addresses are blocked unless private upstreams are explicitly enabled. Hosts that
     * cannot be resolved are left to the operator-owned host allowlist and runtime DNS.
     *
     * @param host the upstream host name or IP address
     * @param allowPrivateUpstreams whether site-local addresses are allowed
     * @return true when the host is blocked by the address policy
     */
    public static boolean isBlocked(String host, boolean allowPrivateUpstreams) {
        if (host.equalsIgnoreCase("localhost") || host.equals("0.0.0.0") || host.equals("::")) {
            return true;
        }
        if (!isIpLiteral(host)) {
            return false;
        }

        try {
            final var address = InetAddress.getByName(host);
            if (address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isAnyLocalAddress()) {
                return true;
            }
            return !allowPrivateUpstreams && address.isSiteLocalAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /**
     * Check a resolved address without performing another DNS lookup.
     *
     * @param address the resolved upstream address
     * @param allowPrivateUpstreams whether private IPv4 and IPv6 ULA addresses are allowed
     * @return true when the address is not safe for upstream egress
     */
    public static boolean isBlocked(InetAddress address, boolean allowPrivateUpstreams) {
        final var bytes = address.getAddress();
        if (bytes.length == 16 && isIpv4Mapped(bytes)) {
            return isBlockedIpv4(bytes, 12, allowPrivateUpstreams);
        }
        if (bytes.length == 4) {
            return isBlockedIpv4(bytes, 0, allowPrivateUpstreams);
        }

        final var first = unsigned(bytes[0]);
        final var second = unsigned(bytes[1]);
        final var uniqueLocal = (first & 0xfe) == 0xfc;
        final var ietfProtocolAssignment = first == 0x20 && second == 0x01 && (unsigned(bytes[2]) & 0xfe) == 0;
        return address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()
                || isCloudMetadataAddress(bytes)
                || (isNat64WellKnownPrefix(bytes) && isBlockedIpv4(bytes, 12, false))
                || ((first & 0xe0) != 0x20 && !isNat64WellKnownPrefix(bytes) && !(allowPrivateUpstreams && uniqueLocal))
                || (ietfProtocolAssignment && !isGloballyReachableIetfAssignment(bytes))
                || (first == 0x20 && second == 0x01 && unsigned(bytes[2]) == 0x0d && unsigned(bytes[3]) == 0xb8)
                || (first == 0x20 && second == 0x02)
                || (first == 0x3f && (second & 0xf0) == 0xf0); // 3fff::/20 documentation
    }

    private static boolean isBlockedIpv4(byte[] bytes, int offset, boolean allowPrivateUpstreams) {
        final var first = unsigned(bytes[offset]);
        final var second = unsigned(bytes[offset + 1]);
        final var third = unsigned(bytes[offset + 2]);
        final var fourth = unsigned(bytes[offset + 3]);

        final var privateAddress =
                first == 10 || (first == 172 && second >= 16 && second <= 31) || (first == 192 && second == 168);
        return first == 0
                || first == 127
                || (first == 169 && second == 254)
                || (first == 100 && second >= 64 && second <= 127) // carrier-grade NAT
                || (first == 192 && second == 0 && third == 0 && fourth != 9 && fourth != 10)
                || (first == 192 && second == 0 && third == 2)
                || (first == 192 && second == 88 && third == 99)
                || (first == 198 && (second == 18 || second == 19)) // benchmarking
                || (first == 198 && second == 51 && third == 100)
                || (first == 203 && second == 0 && third == 113)
                || first >= 224
                || (!allowPrivateUpstreams && privateAddress);
    }

    private static boolean isCloudMetadataAddress(byte[] bytes) {
        final var aws = unsigned(bytes[0]) == 0xfd
                && unsigned(bytes[1]) == 0
                && unsigned(bytes[2]) == 0x0e
                && unsigned(bytes[3]) == 0xc2;
        final var google = unsigned(bytes[0]) == 0xfd
                && unsigned(bytes[1]) == 0x20
                && unsigned(bytes[2]) == 0
                && unsigned(bytes[3]) == 0xce;
        return (aws || google) && allZero(bytes, 4, 14) && unsigned(bytes[14]) == 2 && unsigned(bytes[15]) == 0x54;
    }

    private static boolean isNat64WellKnownPrefix(byte[] bytes) {
        return unsigned(bytes[0]) == 0
                && unsigned(bytes[1]) == 0x64
                && unsigned(bytes[2]) == 0xff
                && unsigned(bytes[3]) == 0x9b
                && allZero(bytes, 4, 12);
    }

    private static boolean isGloballyReachableIetfAssignment(byte[] bytes) {
        final var secondHextet = unsigned(bytes[2]) << 8 | unsigned(bytes[3]);
        return (secondHextet == 1 && allZero(bytes, 4, 15) && unsigned(bytes[15]) >= 1 && unsigned(bytes[15]) <= 3)
                || secondHextet == 3
                || (secondHextet == 4 && unsigned(bytes[4]) == 1 && unsigned(bytes[5]) == 0x12)
                || (secondHextet & 0xfff0) == 0x20
                || (secondHextet & 0xfff0) == 0x30;
    }

    private static boolean allZero(byte[] bytes, int start, int end) {
        for (var i = start; i < end; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        for (var i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
    }

    private static int unsigned(byte value) {
        return Byte.toUnsignedInt(value);
    }

    private static boolean isIpLiteral(String host) {
        return host.indexOf(':') >= 0
                || host.chars().allMatch(character -> character == '.' || (character >= '0' && character <= '9'));
    }
}
