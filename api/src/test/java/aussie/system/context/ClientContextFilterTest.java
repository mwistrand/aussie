package aussie.system.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.ws.rs.container.ContainerRequestContext;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import aussie.adapter.in.context.ClientContextResolver;
import aussie.common.context.ClientContext;
import aussie.core.service.common.TrustedProxyValidator;

@DisplayName("ClientContextFilter")
class ClientContextFilterTest {

    @Test
    @DisplayName("filter() resolves a context and stashes it on the request property bag")
    void resolvesAndStashesContext() {
        final var trustedProxyValidator = mock(TrustedProxyValidator.class);
        final var requestContext = mock(ContainerRequestContext.class);
        final var vertxRequest = mock(HttpServerRequest.class);
        final var socketAddress = mock(SocketAddress.class);
        final var routingContext = mock(RoutingContext.class);

        when(socketAddress.host()).thenReturn("10.0.0.1");
        when(vertxRequest.remoteAddress()).thenReturn(socketAddress);
        when(routingContext.request()).thenReturn(vertxRequest);
        when(trustedProxyValidator.shouldTrustForwardingHeaders("10.0.0.1")).thenReturn(false);

        final var filter = new ClientContextFilter(new ClientContextResolver(trustedProxyValidator), routingContext);

        filter.filter(requestContext, vertxRequest);

        final var captor = org.mockito.ArgumentCaptor.forClass(ClientContext.class);
        verify(requestContext)
                .setProperty(org.mockito.ArgumentMatchers.eq(ClientContextResolver.CONTEXT_PROPERTY), captor.capture());
        assertEquals("10.0.0.1", captor.getValue().resolvedIp());
    }
}
