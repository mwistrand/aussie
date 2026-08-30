package aussie.system.filter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import java.util.List;

import jakarta.ws.rs.container.ContainerRequestContext;

import io.quarkiverse.httpproblem.HttpProblem;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.adapter.in.auth.SessionCookieManager;
import aussie.adapter.in.context.ClientContextResolver;
import aussie.adapter.in.http.GatewayCorsConfig;
import aussie.common.context.ClientContext;
import aussie.core.config.SessionConfig;

@ExtendWith(MockitoExtension.class)
class CsrfProtectionFilterTest {

    @Mock
    SessionConfig sessionConfig;

    @Mock
    SessionCookieManager cookieManager;

    @Mock
    ContainerRequestContext context;

    @Mock
    GatewayCorsConfig corsConfig;

    @Mock
    HttpServerRequest request;

    @Mock
    ClientContextResolver clientContextResolver;

    @Mock
    RoutingContext routingContext;

    private CsrfProtectionFilter filter;

    @BeforeEach
    void setUp() {
        filter = new CsrfProtectionFilter(
                sessionConfig, cookieManager, corsConfig, clientContextResolver, routingContext);
    }

    private void stubSessionMutation() {
        when(sessionConfig.enabled()).thenReturn(true);
        when(request.method()).thenReturn(HttpMethod.POST);
        when(cookieManager.hasSessionCookie(request)).thenReturn(true);
        when(cookieManager.csrfCookieName()).thenReturn("aussie_session_csrf");
        when(request.getCookie("aussie_session_csrf")).thenReturn(Cookie.cookie("aussie_session_csrf", "token"));
        when(clientContextResolver.getOrCompute(routingContext))
                .thenReturn(new ClientContext(
                        "127.0.0.1",
                        false,
                        null,
                        "https",
                        null,
                        List.of(),
                        "gateway.example",
                        null,
                        null,
                        null,
                        "request-id"));
        when(context.getHeaderString("Origin")).thenReturn("https://gateway.example");
        when(context.getHeaderString("X-CSRF-Token")).thenReturn("token");
    }

    @Test
    void acceptsSameOriginMutationWithMatchingToken() {
        stubSessionMutation();

        assertDoesNotThrow(() -> filter.filter(context, request));
    }

    @Test
    void rejectsMutationWithoutMatchingTokenOrOrigin() {
        stubSessionMutation();
        when(context.getHeaderString("Origin")).thenReturn("https://evil.example");

        assertThrows(HttpProblem.class, () -> filter.filter(context, request));
    }

    @Test
    void acceptsCredentialedCorsOrigin() {
        stubSessionMutation();
        when(context.getHeaderString("Origin")).thenReturn("https://app.example");
        when(corsConfig.enabled()).thenReturn(true);
        when(corsConfig.allowCredentials()).thenReturn(true);
        when(corsConfig.allowedOrigins()).thenReturn(List.of("https://app.example"));

        assertDoesNotThrow(() -> filter.filter(context, request));
    }

    @Test
    void publicSessionCreationIgnoresAStaleSessionCookie() {
        when(sessionConfig.enabled()).thenReturn(true);
        when(sessionConfig.publicCreationEnabled()).thenReturn(true);
        when(request.method()).thenReturn(HttpMethod.POST);
        when(request.path()).thenReturn("/auth/session");

        assertDoesNotThrow(() -> filter.filter(context, request));
    }
}
