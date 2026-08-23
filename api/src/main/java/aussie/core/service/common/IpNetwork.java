package aussie.core.service.common;

import java.util.Arrays;
import java.util.Optional;

import com.google.common.net.InetAddresses;

/**
 * An immutable IP address or CIDR network parsed without DNS resolution.
 *
 * <p>Exact addresses are represented as full-width networks ({@code /32} for IPv4 and
 * {@code /128} for IPv6). This keeps trusted-proxy and access-control matching on one
 * rigorously validated implementation.
 */
public final class IpNetwork {

    private final byte[] networkBytes;
    private final int prefixLength;
    private final String canonicalAddress;

    private IpNetwork(byte[] networkBytes, int prefixLength, String canonicalAddress) {
        this.networkBytes = networkBytes;
        this.prefixLength = prefixLength;
        this.canonicalAddress = canonicalAddress;
    }

    /**
     * Parse an IP literal or CIDR. Host names, zone identifiers, malformed addresses,
     * and out-of-range prefixes are rejected.
     */
    public static Optional<IpNetwork> parse(String pattern) {
        if (pattern == null || pattern.isBlank() || pattern.indexOf('%') >= 0) {
            return Optional.empty();
        }

        final var parts = pattern.trim().split("/", -1);
        if (parts.length > 2 || parts[0].isBlank() || !InetAddresses.isInetAddress(parts[0])) {
            return Optional.empty();
        }

        final var address = InetAddresses.forString(parts[0]);
        final var addressBytes = address.getAddress();
        final var maxPrefixLength = addressBytes.length * Byte.SIZE;
        final int prefixLength;
        if (parts.length == 1) {
            prefixLength = maxPrefixLength;
        } else {
            try {
                prefixLength = Integer.parseInt(parts[1]);
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
            if (prefixLength < 0 || prefixLength > maxPrefixLength) {
                return Optional.empty();
            }
        }

        return Optional.of(new IpNetwork(addressBytes, prefixLength, InetAddresses.toAddrString(address)));
    }

    /** Return whether this network contains the supplied IP literal. */
    public boolean contains(String address) {
        return parse(address)
                .filter(candidate -> candidate.isExactAddress() && contains(candidate))
                .isPresent();
    }

    /** Return whether this network completely contains another address or network. */
    public boolean contains(IpNetwork candidate) {
        if (networkBytes.length != candidate.networkBytes.length || prefixLength > candidate.prefixLength) {
            return false;
        }

        final var fullBytes = prefixLength / Byte.SIZE;
        final var remainingBits = prefixLength % Byte.SIZE;
        for (var i = 0; i < fullBytes; i++) {
            if (networkBytes[i] != candidate.networkBytes[i]) {
                return false;
            }
        }
        if (remainingBits == 0) {
            return true;
        }

        final var mask = 0xFF << (Byte.SIZE - remainingBits);
        return ((networkBytes[fullBytes] & 0xFF) & mask) == ((candidate.networkBytes[fullBytes] & 0xFF) & mask);
    }

    public boolean isExactAddress() {
        return prefixLength == networkBytes.length * Byte.SIZE;
    }

    /** Return the address in a stable textual form without DNS resolution. */
    public String canonicalAddress() {
        return canonicalAddress;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof IpNetwork that)) {
            return false;
        }
        return prefixLength == that.prefixLength && Arrays.equals(networkBytes, that.networkBytes);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(networkBytes) + prefixLength;
    }
}
