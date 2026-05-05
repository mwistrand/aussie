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

import jakarta.ws.rs.container.ContainerRequestContext;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.SocketAddress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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
            when(trustedProxyValidator.shouldTrustForwardingHeaders("203.0.113.99"))
                    .thenReturn(false);
            when(request.getHeader("Forwarded")).thenReturn("for=1.2.3.4");
            when(request.getHeader("X-Forwarded-For")).thenReturn("1.2.3.4");

            final var ctx = resolver.resolve(request);

            assertEquals("203.0.113.99", ctx.socketIp());
            assertFalse(ctx.trustForwardingHeaders());
            assertNull(ctx.forwardedClientIp());
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
        @DisplayName("falls back to X-Forwarded-For when Forwarded has no for=")
        void fallsBackToXForwardedFor() {
            when(socketAddress.host()).thenReturn("10.0.0.1");
            when(trustedProxyValidator.shouldTrustForwardingHeaders(anyString()))
                    .thenReturn(true);
            when(request.getHeader("Forwarded")).thenReturn("proto=https");
            when(request.getHeader("X-Forwarded-For")).thenReturn("198.51.100.1, 10.0.0.5");

            final var ctx = resolver.resolve(request);

            assertEquals("198.51.100.1", ctx.forwardedClientIp());
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
