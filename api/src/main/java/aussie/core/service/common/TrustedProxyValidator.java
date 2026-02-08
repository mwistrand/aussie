package aussie.core.service.common;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;

import aussie.core.model.common.TrustedProxyConfig;

/**
 * Validates whether a socket IP belongs to a trusted proxy.
 *
 * <p>Caches parsed CIDR networks for efficient repeated lookups.
 */
@ApplicationScoped
public class TrustedProxyValidator {

    private static final Logger LOG = Logger.getLogger(TrustedProxyValidator.class);

    /** Sentinel for CIDRs that failed to parse (ConcurrentHashMap cannot store null). */
    private static final ParsedCidr INVALID_CIDR = new ParsedCidr(new byte[0], -1);

    /** Sentinel for IPs that failed to parse. */
    private static final byte[] INVALID_IP = new byte[0];

    private final TrustedProxyConfig config;
    private final Map<String, ParsedCidr> cidrCache = new ConcurrentHashMap<>();
    private final Map<String, byte[]> proxyIpCache = new ConcurrentHashMap<>();

    private record ParsedCidr(byte[] networkBytes, int prefixLength) {}

    @Inject
    public TrustedProxyValidator(TrustedProxyConfig config) {
        this.config = config;
    }

    /**
     * Check if forwarding headers should be trusted for the given socket IP.
     *
     * @param socketIp the direct connection's remote IP address
     * @return true if forwarding headers should be trusted
     */
    public boolean shouldTrustForwardingHeaders(String socketIp) {
        if (!config.enabled()) {
            return true;
        }
        if (socketIp == null || socketIp.isEmpty()) {
            return false;
        }
        final var proxies = config.proxies();
        if (proxies.isEmpty() || proxies.get().isEmpty()) {
            return false;
        }
        return matchesAny(socketIp, proxies.get());
    }

    private boolean matchesAny(String sourceIp, List<String> patterns) {
        final var sourceBytes = parseIpAddress(sourceIp);
        if (sourceBytes == null) {
            return false;
        }

        for (final var pattern : patterns) {
            if (pattern.contains("/")) {
                if (matchesCidr(sourceBytes, pattern)) {
                    return true;
                }
            } else {
                if (matchesExact(sourceBytes, pattern)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean matchesExact(byte[] sourceBytes, String pattern) {
        final var patternBytes = proxyIpCache.computeIfAbsent(pattern, TrustedProxyValidator::parseIpAddressLiteral);
        if (patternBytes == INVALID_IP) {
            return false;
        }
        return Arrays.equals(sourceBytes, patternBytes);
    }

    private boolean matchesCidr(byte[] sourceBytes, String cidr) {
        final var parsedCidr = cidrCache.computeIfAbsent(cidr, this::parseCidr);
        if (parsedCidr == INVALID_CIDR) {
            return false;
        }

        final var networkBytes = parsedCidr.networkBytes();
        final var prefixLength = parsedCidr.prefixLength();

        if (networkBytes.length != sourceBytes.length) {
            return false;
        }

        final var fullBytes = prefixLength / 8;
        final var remainingBits = prefixLength % 8;

        for (var i = 0; i < fullBytes; i++) {
            if (networkBytes[i] != sourceBytes[i]) {
                return false;
            }
        }

        if (remainingBits > 0) {
            final var mask = (byte) (0xFF << (8 - remainingBits));
            if ((networkBytes[fullBytes] & mask) != (sourceBytes[fullBytes] & mask)) {
                return false;
            }
        }

        return true;
    }

    private ParsedCidr parseCidr(String cidr) {
        final var parts = cidr.split("/");
        if (parts.length != 2) {
            LOG.warnf("Invalid CIDR format (expected ip/prefix): %s", cidr);
            return INVALID_CIDR;
        }
        final var addressBytes = parseIpAddressLiteral(parts[0]);
        if (addressBytes == INVALID_IP) {
            LOG.warnf("Invalid network address in CIDR: %s", cidr);
            return INVALID_CIDR;
        }
        try {
            final var prefixLength = Integer.parseInt(parts[1]);
            final var maxPrefix = addressBytes.length * 8;
            if (prefixLength < 0 || prefixLength > maxPrefix) {
                LOG.warnf("Invalid prefix length %d in CIDR (max %d): %s", prefixLength, maxPrefix, cidr);
                return INVALID_CIDR;
            }
            return new ParsedCidr(addressBytes, prefixLength);
        } catch (NumberFormatException e) {
            LOG.warnf("Invalid prefix length in CIDR: %s", cidr);
            return INVALID_CIDR;
        }
    }

    /**
     * Parse an IP address without DNS resolution. Returns null for
     * non-IP inputs so that hostnames are never resolved.
     */
    private static byte[] parseIpAddress(String ip) {
        if (!isIpAddressLiteral(ip)) {
            return null;
        }
        return parseIpAddressLiteral(ip);
    }

    private static byte[] parseIpAddressLiteral(String ip) {
        try {
            // InetAddress.getByName resolves hostnames, so only call it for IP literals
            if (!isIpAddressLiteral(ip)) {
                return INVALID_IP;
            }
            return InetAddress.getByName(ip).getAddress();
        } catch (UnknownHostException e) {
            return INVALID_IP;
        }
    }

    private static boolean isIpAddressLiteral(String input) {
        if (input == null || input.isEmpty()) {
            return false;
        }
        // IPv6 addresses contain colons
        if (input.contains(":")) {
            return true;
        }
        // IPv4: must start with a digit and contain only digits and dots
        if (!Character.isDigit(input.charAt(0))) {
            return false;
        }
        for (var i = 0; i < input.length(); i++) {
            final var c = input.charAt(i);
            if (c != '.' && !Character.isDigit(c)) {
                return false;
            }
        }
        return true;
    }
}
