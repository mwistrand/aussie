package aussie.system.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import jakarta.ws.rs.container.ContainerRequestContext;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.SocketAddress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.adapter.in.context.ClientContextResolver;
import aussie.common.context.ClientContext;
import aussie.core.service.common.TrustedProxyValidator;

@DisplayName("ClientContextResolver")
class ClientContextResolverTest {

    private TrustedProxyValidator trustedProxyValidator;
    private HttpServerRequest request;
    private SocketAddress socketAddress;
    private ClientContextResolver resolver;

    @BeforeEach
    void setUp() {
        trustedProxyValidator = mock(TrustedProxyValidator.class);
        request = mock(HttpServerRequest.class);
        socketAddress = mock(SocketAddress.class);
        when(request.remoteAddress()).thenReturn(socketAddress);
        resolver = new ClientContextResolver(trustedProxyValidator);
    }

    @Nested
    @DisplayName("resolve()")
    class ResolveTests {

        @Test
        @DisplayName("ignores forwarded headers when proxy is not trusted")
        void ignoresForwardedHeadersWhenProxyNotTrusted() {
            when(socketAddress.host()).thenReturn("203.0.113.99");
            when(socketAddress.port()).thenReturn(43123);
            when(trustedProxyValidator.shouldTrustForwardingHeaders("203.0.113.99"))
                    .thenReturn(false);
            when(request.getHeader("Host")).thenReturn("Gateway.Example:8443");
            when(request.getHeader("X-Request-ID")).thenReturn("request-123");
            when(request.getHeader("Forwarded")).thenReturn("for=1.2.3.4");
            when(request.getHeader("X-Forwarded-For")).thenReturn("1.2.3.4");
            when(request.getHeader("X-Real-IP")).thenReturn("1.2.3.4");
            when(request.getHeader("X-Forwarded-Proto")).thenReturn("https");

            final var ctx = resolver.resolve(request);

            assertEquals("203.0.113.99", ctx.socketIp());
            assertFalse(ctx.trustForwardingHeaders());
            assertNull(ctx.forwardedClientIp());
            assertEquals("http", ctx.externalScheme());
            assertEquals(43123, ctx.socketPort());
            assertEquals("gateway.example", ctx.externalHost());
            assertEquals(8443, ctx.externalPort());
            assertEquals("request-123", ctx.correlationId());
            assertEquals("203.0.113.99", ctx.resolvedIp());
        }

        @Test
        @DisplayName("prefers RFC 7239 Forwarded over X-Forwarded-For")
        void prefersRfc7239Forwarded() {
            when(socketAddress.host()).thenReturn("10.0.0.1");
            when(trustedProxyValidator.shouldTrustForwardingHeaders("10.0.0.1")).thenReturn(true);
            when(request.getHeader("Forwarded")).thenReturn("for=203.0.113.195");
            when(request.getHeader("X-Forwarded-For")).thenReturn("10.0.0.1");

            final var ctx = resolver.resolve(request);

            assertTrue(ctx.trustForwardingHeaders());
            assertEquals("203.0.113.195", ctx.forwardedClientIp());
            assertEquals("203.0.113.195", ctx.resolvedIp());
        }

        @Test
        @DisplayName("strips IPv4 port from Forwarded for=")
        void stripsIpv4Port() {
            when(socketAddress.host()).thenReturn("10.0.0.1");
            when(trustedProxyValidator.shouldTrustForwardingHeaders(anyString()))
                    .thenReturn(true);
            when(request.getHeader("Forwarded")).thenReturn("for=192.0.2.60:8080");

            final var ctx = resolver.resolve(request);

            assertEquals("192.0.2.60", ctx.forwardedClientIp());
        }

        @Test
        @DisplayName("preserves IPv6 address from quoted Forwarded for=")
        void preservesIpv6() {
            when(socketAddress.host()).thenReturn("10.0.0.1");
            when(trustedProxyValidator.shouldTrustForwardingHeaders(anyString()))
                    .thenReturn(true);
            when(request.getHeader("Forwarded")).thenReturn("for=\"[2001:db8:cafe::17]\"");

            final var ctx = resolver.resolve(request);

            assertEquals("2001:db8:cafe::17", ctx.forwardedClientIp());
        }

        @Test
        @DisplayName("rejects X-Forwarded-For fallback when Forwarded is malformed")
        void rejectsXForwardedForFallback() {
            when(socketAddress.host()).thenReturn("10.0.0.1");
            when(trustedProxyValidator.shouldTrustForwardingHeaders(anyString()))
                    .thenReturn(true);
            when(request.getHeader("Forwarded")).thenReturn("proto=https");
            when(request.getHeader("X-Forwarded-For")).thenReturn("198.51.100.1, 10.0.0.5");

            final var ctx = resolver.resolve(request);

            assertNull(ctx.forwardedClientIp());
            assertEquals("10.0.0.1", ctx.resolvedIp());
        }

        @Test
        @DisplayName("rejects out-of-range ports in Forwarded nodes")
        void rejectsOutOfRangePort() {
            when(socketAddress.host()).thenReturn("10.0.0.1");
            when(trustedProxyValidator.shouldTrustForwardingHeaders(anyString()))
                    .thenReturn(true);
            when(request.getHeader("Forwarded")).thenReturn("for=192.0.2.60:65536");

            final var ctx = resolver.resolve(request);

            assertNull(ctx.forwardedClientIp());
            assertEquals("10.0.0.1", ctx.resolvedIp());
        }

        @Test
        @DisplayName("walks from the trusted edge and ignores a spoofed leftmost hop")
        void resolvesRightmostUntrustedHop() {
            when(socketAddress.host()).thenReturn("10.0.0.1");
            when(trustedProxyValidator.shouldTrustForwardingHeaders("10.0.0.1")).thenReturn(true);
            when(trustedProxyValidator.isTrustedProxy("10.0.0.2")).thenReturn(true);
            when(trustedProxyValidator.isTrustedProxy("203.0.113.10")).thenReturn(false);
            when(request.getHeader("X-Forwarded-For")).thenReturn("198.51.100.66, 203.0.113.10, 10.0.0.2");

            final var ctx = resolver.resolve(request);

            assertEquals("203.0.113.10", ctx.forwardedClientIp());
            assertEquals("203.0.113.10", ctx.resolvedIp());
            assertEquals(
                    List.of(
                            new ClientContext.ForwardingHop("198.51.100.66", false),
                            new ClientContext.ForwardingHop("203.0.113.10", false),
                            new ClientContext.ForwardingHop("10.0.0.2", true)),
                    ctx.forwardingChain());
        }

        @Test
        @DisplayName("uses X-Real-IP as the final trusted single-hop fallback")
        void usesXRealIpFallback() {
            when(socketAddress.host()).thenReturn("10.0.0.1");
            when(trustedProxyValidator.shouldTrustForwardingHeaders("10.0.0.1")).thenReturn(true);
            when(request.getHeader("X-Real-IP")).thenReturn("198.51.100.44");

            final var ctx = resolver.resolve(request);

            assertEquals("198.51.100.44", ctx.forwardedClientIp());
            assertEquals("198.51.100.44", ctx.resolvedIp());
        }

        @Test
        @DisplayName("prefers X-Forwarded-For over X-Real-IP")
        void prefersXForwardedForOverXRealIp() {
            when(socketAddress.host()).thenReturn("10.0.0.1");
            when(trustedProxyValidator.shouldTrustForwardingHeaders("10.0.0.1")).thenReturn(true);
            when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.20");
            when(request.getHeader("X-Real-IP")).thenReturn("198.51.100.44");

            final var ctx = resolver.resolve(request);

            assertEquals("203.0.113.20", ctx.resolvedIp());
        }

        @Test
        @DisplayName("rejects a non-literal X-Real-IP value")
        void rejectsInvalidXRealIp() {
            when(socketAddress.host()).thenReturn("10.0.0.1");
            when(trustedProxyValidator.shouldTrustForwardingHeaders("10.0.0.1")).thenReturn(true);
            when(request.getHeader("X-Real-IP")).thenReturn("client.example.com");

            final var ctx = resolver.resolve(request);

            assertNull(ctx.forwardedClientIp());
            assertEquals("10.0.0.1", ctx.resolvedIp());
        }

        @Test
        @DisplayName("captures a validated scheme from Forwarded")
        void capturesForwardedScheme() {
            when(socketAddress.host()).thenReturn("10.0.0.1");
            when(trustedProxyValidator.shouldTrustForwardingHeaders("10.0.0.1")).thenReturn(true);
            when(request.getHeader("Forwarded")).thenReturn("for=198.51.100.5;proto=HTTPS");
            when(request.getHeader("X-Forwarded-Proto")).thenReturn("http");

            final var ctx = resolver.resolve(request);

            assertEquals("https", ctx.externalScheme());
        }

        @Test
        @DisplayName("uses X-Forwarded-Proto when Forwarded is absent")
        void capturesXForwardedProto() {
            when(socketAddress.host()).thenReturn("10.0.0.1");
            when(trustedProxyValidator.shouldTrustForwardingHeaders("10.0.0.1")).thenReturn(true);
            when(request.getHeader("X-Forwarded-Proto")).thenReturn("https");

            final var ctx = resolver.resolve(request);

            assertEquals("https", ctx.externalScheme());
        }

        @Test
        @DisplayName("rejects unsupported forwarded schemes")
        void rejectsUnsupportedScheme() {
            when(socketAddress.host()).thenReturn("10.0.0.1");
            when(trustedProxyValidator.shouldTrustForwardingHeaders("10.0.0.1")).thenReturn(true);
            when(request.getHeader("X-Forwarded-Proto")).thenReturn("javascript");

            final var ctx = resolver.resolve(request);

            assertEquals("http", ctx.externalScheme());
        }

        @Test
        @DisplayName("captures canonical external authority only from a trusted proxy")
        void capturesCanonicalExternalAuthority() {
            when(socketAddress.host()).thenReturn("10.0.0.1");
            when(trustedProxyValidator.shouldTrustForwardingHeaders("10.0.0.1")).thenReturn(true);
            when(request.getHeader("Forwarded")).thenReturn("for=198.51.100.5;proto=https;host=Api.Example:9443");

            final var ctx = resolver.resolve(request);

            assertEquals("api.example", ctx.externalHost());
            assertEquals(9443, ctx.externalPort());
            assertEquals("api.example:9443", ctx.externalAuthority());
        }

        @Test
        @DisplayName("uses the external entries from X-Forwarded host and port chains")
        void capturesXForwardedAuthorityChain() {
            when(socketAddress.host()).thenReturn("10.0.0.1");
            when(trustedProxyValidator.shouldTrustForwardingHeaders("10.0.0.1")).thenReturn(true);
            when(request.getHeader("X-Forwarded-Host")).thenReturn("api.example, internal-proxy");
            when(request.getHeader("X-Forwarded-Port")).thenReturn("443, 8080");

            final var ctx = resolver.resolve(request);

            assertEquals("api.example", ctx.externalHost());
            assertEquals(443, ctx.externalPort());
        }

        @Test
        @DisplayName("canonicalizes equivalent IPv6 forwarding identities")
        void canonicalizesIpv6Identity() {
            when(socketAddress.host()).thenReturn("10.0.0.1");
            when(trustedProxyValidator.shouldTrustForwardingHeaders("10.0.0.1")).thenReturn(true);
            when(request.getHeader("X-Forwarded-For")).thenReturn("2001:0DB8:0000:0000:0000:0000:0000:0001");

            final var ctx = resolver.resolve(request);

            assertEquals("2001:db8::1", ctx.resolvedIp());
            assertEquals(List.of(new ClientContext.ForwardingHop("2001:db8::1", false)), ctx.forwardingChain());
        }

        @Test
        @DisplayName("falls back to the socket peer for oversized forwarding headers")
        void rejectsOversizedForwardingHeader() {
            when(socketAddress.host()).thenReturn("10.0.0.1");
            when(trustedProxyValidator.shouldTrustForwardingHeaders("10.0.0.1")).thenReturn(true);
            when(request.getHeader("X-Forwarded-For")).thenReturn("1".repeat(8193));

            final var ctx = resolver.resolve(request);

            assertNull(ctx.forwardedClientIp());
            assertEquals("10.0.0.1", ctx.resolvedIp());
        }

        @Test
        @DisplayName("falls back to the socket peer for obfuscated Forwarded nodes")
        void rejectsObfuscatedForwardedNode() {
            when(socketAddress.host()).thenReturn("10.0.0.1");
            when(trustedProxyValidator.shouldTrustForwardingHeaders("10.0.0.1")).thenReturn(true);
            when(request.getHeader("Forwarded")).thenReturn("for=_hidden");

            final var ctx = resolver.resolve(request);

            assertNull(ctx.forwardedClientIp());
            assertEquals("10.0.0.1", ctx.resolvedIp());
        }

        @Test
        @DisplayName("returns 'unknown' when no socket and no forwarded headers")
        void unknownWhenNothingAvailable() {
            when(request.remoteAddress()).thenReturn(null);
            when(trustedProxyValidator.shouldTrustForwardingHeaders(null)).thenReturn(false);

            final var ctx = resolver.resolve(request);

            assertNull(ctx.socketIp());
            assertNull(ctx.forwardedClientIp());
            assertEquals("unknown", ctx.resolvedIp());
        }
    }

    @Nested
    @DisplayName("getOrCompute()")
    class GetOrComputeTests {

        @Test
        @DisplayName("computes once and reuses the cached value across subsequent calls")
        void cachesOnRequestContext() {
            when(socketAddress.host()).thenReturn("10.0.0.1");
            when(trustedProxyValidator.shouldTrustForwardingHeaders(anyString()))
                    .thenReturn(false);

            final var requestContext = mock(ContainerRequestContext.class);
            // Simulate a stateful property bag: first call returns null, then the stored value.
            final ClientContext[] stored = new ClientContext[1];
            when(requestContext.getProperty(ClientContextResolver.CONTEXT_PROPERTY))
                    .thenAnswer(inv -> stored[0]);
            doAnswer(inv -> {
                        stored[0] = inv.getArgument(1);
                        return null;
                    })
                    .when(requestContext)
                    .setProperty(eq(ClientContextResolver.CONTEXT_PROPERTY), any());

            final var first = resolver.getOrCompute(requestContext, request);
            final var second = resolver.getOrCompute(requestContext, request);

            assertSame(first, second);
            verify(trustedProxyValidator, times(1)).shouldTrustForwardingHeaders(anyString());
        }
    }
}
