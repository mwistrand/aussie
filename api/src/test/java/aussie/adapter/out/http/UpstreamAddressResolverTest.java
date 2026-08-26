package aussie.adapter.out.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.vertx.mutiny.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import aussie.adapter.out.http.UpstreamAddressResolver.EgressPolicyException;

@DisplayName("UpstreamAddressResolver")
class UpstreamAddressResolverTest {

    private Vertx vertx;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
    }

    @AfterEach
    void tearDown() {
        vertx.close().await().atMost(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("pins the authorized address returned by the lookup")
    void pinsAuthorizedAddress() throws Exception {
        final var resolver = resolver(false, address("93.184.216.34"));

        final var result = resolver.resolve(URI.create("https://example.com/resource"))
                .await()
                .atMost(Duration.ofSeconds(5));

        assertEquals("93.184.216.34", result.hostAddress());
        assertEquals(443, result.port());
    }

    @Test
    @DisplayName("rejects a mixed public and forbidden DNS answer")
    void rejectsMixedAnswers() throws Exception {
        final var resolver = resolver(false, address("93.184.216.34"), address("127.0.0.1"));

        assertThrows(EgressPolicyException.class, () -> resolver.resolve(URI.create("https://example.com"))
                .await()
                .atMost(Duration.ofSeconds(5)));
    }

    @Test
    @DisplayName("rejects IPv4-mapped forbidden addresses")
    void rejectsIpv4MappedAddress() throws Exception {
        final var bytes = new byte[16];
        bytes[10] = (byte) 0xff;
        bytes[11] = (byte) 0xff;
        bytes[12] = 127;
        bytes[15] = 1;
        final var resolver = resolver(false, InetAddress.getByAddress(bytes));

        assertThrows(EgressPolicyException.class, () -> resolver.resolve(URI.create("https://example.com"))
                .await()
                .atMost(Duration.ofSeconds(5)));
    }

    @Test
    @DisplayName("rejects non-global reserved IPv6 addresses")
    void rejectsReservedIpv6Address() throws Exception {
        final var resolver = resolver(false, address("4000::1"));

        assertThrows(EgressPolicyException.class, () -> resolver.resolve(URI.create("https://example.com"))
                .await()
                .atMost(Duration.ofSeconds(5)));
    }

    @Test
    @DisplayName("rejects cloud metadata addresses even when private upstreams are allowed")
    void rejectsCloudMetadataAddresses() throws Exception {
        for (final var value : List.of("fd00:ec2::254", "fd20:ce::254")) {
            final var resolver = resolver(true, address(value));

            assertThrows(EgressPolicyException.class, () -> resolver.resolve(URI.create("http://metadata.example"))
                    .await()
                    .atMost(Duration.ofSeconds(5)));
        }
    }

    @Test
    @DisplayName("rejects non-global IPv6 transition and protocol-assignment addresses")
    void rejectsNonGlobalIpv6Assignments() throws Exception {
        for (final var value : List.of("2001:100::1", "2002:7f00:1::")) {
            final var resolver = resolver(false, address(value));

            assertThrows(EgressPolicyException.class, () -> resolver.resolve(URI.create("https://example.com"))
                    .await()
                    .atMost(Duration.ofSeconds(5)));
        }
    }

    @Test
    @DisplayName("allows globally reachable special-purpose addresses")
    void allowsGloballyReachableSpecialPurposeAddresses() throws Exception {
        for (final var value : List.of("192.0.0.9", "64:ff9b::808:808", "2001:3::1")) {
            final var resolver = resolver(false, address(value));

            resolver.resolve(URI.create("https://example.com")).await().atMost(Duration.ofSeconds(5));
        }
    }

    @Test
    @DisplayName("accepts DNS responses with more than 32 authorized addresses")
    void acceptsLargeAuthorizedAnswer() throws Exception {
        final var addresses = new InetAddress[33];
        Arrays.fill(addresses, address("93.184.216.34"));
        final var resolver = resolver(false, addresses);

        final var result =
                resolver.resolve(URI.create("https://example.com")).await().atMost(Duration.ofSeconds(5));

        assertEquals("93.184.216.34", result.hostAddress());
    }

    @Test
    @DisplayName("uses one authorized DNS answer without resolving again")
    void pinsSingleLookupResult() throws Exception {
        final var lookups = new AtomicInteger();
        final var authorizedAddress = address("93.184.216.34");
        final var resolver = new UpstreamAddressResolver(vertx, false, ignored -> {
            lookups.incrementAndGet();
            return List.of(authorizedAddress);
        });

        final var result =
                resolver.resolve(URI.create("https://example.com")).await().atMost(Duration.ofSeconds(5));

        assertEquals("93.184.216.34", result.hostAddress());
        assertEquals(1, lookups.get());
    }

    @Test
    @DisplayName("allows private addresses only when explicitly configured")
    void allowsConfiguredPrivateAddress() throws Exception {
        final var resolver = resolver(true, address("10.0.0.7"));

        final var result = resolver.resolve(URI.create("http://internal.example:8080"))
                .await()
                .atMost(Duration.ofSeconds(5));

        assertEquals("10.0.0.7", result.hostAddress());
        assertEquals(8080, result.port());
    }

    private UpstreamAddressResolver resolver(boolean allowPrivate, InetAddress... addresses) {
        return new UpstreamAddressResolver(vertx, allowPrivate, ignored -> List.of(addresses));
    }

    private static InetAddress address(String value) throws Exception {
        return InetAddress.getByName(value);
    }
}
