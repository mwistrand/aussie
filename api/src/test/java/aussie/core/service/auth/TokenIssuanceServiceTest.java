package aussie.core.service.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import jakarta.enterprise.inject.Instance;

import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.Vertx;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import aussie.core.config.RouteAuthConfig;
import aussie.core.model.auth.AussieToken;
import aussie.core.model.auth.TokenValidationResult;
import aussie.core.port.in.RoleManagement;
import aussie.spi.TokenIssuerProvider;

@DisplayName("TokenIssuanceService")
@SuppressWarnings("unchecked")
class TokenIssuanceServiceTest {

    private Instance<TokenIssuerProvider> issuerInstances;
    private RoleManagement roleManagement;
    private RouteAuthConfig routeAuthConfig;
    private RouteAuthConfig.JwsProperties jwsProperties;
    private TokenIssuerProvider issuer;

    @BeforeEach
    void setUp() {
        issuerInstances = mock(Instance.class);
        roleManagement = mock(RoleManagement.class);
        routeAuthConfig = mock(RouteAuthConfig.class);
        jwsProperties = mock(RouteAuthConfig.JwsProperties.class);
        issuer = mock(TokenIssuerProvider.class);

        when(routeAuthConfig.jws()).thenReturn(jwsProperties);
        when(routeAuthConfig.enabled()).thenReturn(true);
        when(jwsProperties.issuer()).thenReturn("aussie-gateway");
        when(jwsProperties.keyId()).thenReturn("v1");
        when(jwsProperties.tokenTtl()).thenReturn(Duration.ofMinutes(5));
        when(jwsProperties.maxTokenTtl()).thenReturn(Duration.ofHours(24));
        when(jwsProperties.forwardedClaims()).thenReturn(Set.of("sub", "email", "name", "roles"));
        when(jwsProperties.defaultAudience()).thenReturn(Optional.empty());
        when(jwsProperties.requireAudience()).thenReturn(false);
        when(jwsProperties.signingKey()).thenReturn(Optional.empty());

        when(issuer.isAvailable()).thenReturn(true);
        when(issuer.name()).thenReturn("test-issuer");
        when(issuerInstances.stream()).thenReturn(Stream.of(issuer));
    }

    private TokenIssuanceService createService() {
        return new TokenIssuanceService(issuerInstances, roleManagement, routeAuthConfig);
    }

    private TokenValidationResult.Valid validToken() {
        return new TokenValidationResult.Valid(
                "user-1",
                "https://idp.example.com",
                Map.of("sub", "user-1", "email", "user@test.com"),
                Instant.now().plusSeconds(3600));
    }

    @Nested
    @DisplayName("isEnabled()")
    class IsEnabledTests {

        @Test
        @DisplayName("should return true when enabled with available issuers")
        void shouldReturnTrueWhenEnabledWithIssuers() {
            var service = createService();
            assertTrue(service.isEnabled());
        }

        @Test
        @DisplayName("should return false when route auth disabled")
        void shouldReturnFalseWhenDisabled() {
            when(routeAuthConfig.enabled()).thenReturn(false);
            var service = createService();
            assertFalse(service.isEnabled());
        }

        @Test
        @DisplayName("should return false when no issuers available")
        void shouldReturnFalseWhenNoIssuers() {
            when(issuer.isAvailable()).thenReturn(false);
            when(issuerInstances.stream()).thenReturn(Stream.of(issuer));
            var service = createService();
            assertFalse(service.isEnabled());
        }
    }

    @Nested
    @DisplayName("issue()")
    class IssueTests {

        @Test
        @DisplayName("should issue token using first available issuer")
        void shouldIssueTokenUsingFirstIssuer() {
            var service = createService();
            var validated = validToken();
            var expectedToken =
                    new AussieToken("signed", "user-1", Instant.now().plusSeconds(300), Map.of());

            when(issuer.issue(eq(validated), any(), any())).thenReturn(expectedToken);

            var result = service.issue(validated);

            assertTrue(result.isPresent());
            assertEquals("signed", result.get().jws());
        }

        @Test
        @DisplayName("should return empty when not enabled")
        void shouldReturnEmptyWhenNotEnabled() {
            when(routeAuthConfig.enabled()).thenReturn(false);
            var service = createService();

            var result = service.issue(validToken());

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return empty when issuer throws exception")
        void shouldReturnEmptyOnException() {
            var service = createService();
            when(issuer.issue(any(), any(), any())).thenThrow(new RuntimeException("signing failed"));

            var result = service.issue(validToken());

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should pass audience to issuer")
        void shouldPassAudienceToIssuer() {
            var service = createService();
            var validated = validToken();
            var expectedToken =
                    new AussieToken("signed", "user-1", Instant.now().plusSeconds(300), Map.of());
            var audience = Optional.of("my-service");

            when(issuer.issue(eq(validated), any(), eq(audience))).thenReturn(expectedToken);

            var result = service.issue(validated, audience);

            assertTrue(result.isPresent());
            verify(issuer).issue(eq(validated), any(), eq(audience));
        }
    }

    @Nested
    @DisplayName("issueAsync()")
    class IssueAsyncTests {

        @Test
        @DisplayName("should return empty when not enabled")
        void shouldReturnEmptyWhenNotEnabled() {
            when(routeAuthConfig.enabled()).thenReturn(false);
            var service = createService();

            var result = service.issueAsync(validToken()).await().atMost(Duration.ofSeconds(1));

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should issue synchronously when no roles in claims")
        void shouldIssueSynchronouslyWhenNoRoles() {
            var service = createService();
            var validated = validToken();
            var expectedToken =
                    new AussieToken("signed", "user-1", Instant.now().plusSeconds(300), Map.of());

            when(issuer.issue(eq(validated), any(), any())).thenReturn(expectedToken);

            var result = service.issueAsync(validated, Optional.empty(), "test-service")
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertTrue(result.isPresent());
            verify(roleManagement, never()).expandRoles(any());
        }

        @Test
        @DisplayName("runs signing off the Vert.x event loop")
        void runsSigningOffEventLoop() throws Exception {
            var service = createService();
            var validated = new TokenValidationResult.Valid(
                    "user-1",
                    "issuer",
                    Map.of("sub", "user-1", "roles", List.of("admin")),
                    Instant.now().plusSeconds(3600));
            var expectedToken =
                    new AussieToken("signed", "user-1", Instant.now().plusSeconds(300), Map.of());
            when(roleManagement.expandRoles(Set.of("admin")))
                    .thenReturn(Uni.createFrom().item(Set.of("read")));
            final var signingThread = new CompletableFuture<String>();
            when(issuer.issue(any(TokenValidationResult.Valid.class), any(), any()))
                    .thenAnswer(invocation -> {
                        signingThread.complete(Thread.currentThread().getName());
                        return expectedToken;
                    });
            final var vertx = Vertx.vertx();

            vertx.runOnContext(() -> service.issueAsync(validated, Optional.empty(), "test-service")
                    .subscribe()
                    .with(result -> {}, signingThread::completeExceptionally));

            try {
                assertFalse(signingThread.get(5, TimeUnit.SECONDS).contains("vert.x-eventloop"));
            } finally {
                vertx.close().await().atMost(Duration.ofSeconds(5));
            }
        }

        @Test
        @DisplayName("should expand roles and enrich token claims")
        void shouldExpandRolesAndEnrichClaims() {
            var service = createService();
            var validated = new TokenValidationResult.Valid(
                    "user-1",
                    "issuer",
                    Map.of("sub", "user-1", "roles", List.of("admin", "editor")),
                    Instant.now().plusSeconds(3600));
            var expectedToken =
                    new AussieToken("signed", "user-1", Instant.now().plusSeconds(300), Map.of());

            when(roleManagement.expandRoles(Set.of("admin", "editor")))
                    .thenReturn(Uni.createFrom().item(Set.of("read", "write", "delete")));
            when(issuer.issue(any(TokenValidationResult.Valid.class), any(), any()))
                    .thenReturn(expectedToken);

            var result = service.issueAsync(validated, Optional.empty(), "test-service")
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertTrue(result.isPresent());
            verify(roleManagement).expandRoles(Set.of("admin", "editor"));
            final var validatedCaptor = ArgumentCaptor.forClass(TokenValidationResult.Valid.class);
            verify(issuer).issue(validatedCaptor.capture(), any(), any());
            assertEquals(Set.of("read", "write", "delete"), Set.copyOf((List<String>)
                    validatedCaptor.getValue().claims().get("effective_permissions")));
        }

        @Test
        @DisplayName("should extract single string role")
        void shouldExtractSingleStringRole() {
            var service = createService();
            var validated = new TokenValidationResult.Valid(
                    "user-1",
                    "issuer",
                    Map.of("sub", "user-1", "roles", "admin"),
                    Instant.now().plusSeconds(3600));
            var expectedToken =
                    new AussieToken("signed", "user-1", Instant.now().plusSeconds(300), Map.of());

            when(roleManagement.expandRoles(Set.of("admin")))
                    .thenReturn(Uni.createFrom().item(Set.of("read", "write")));
            when(issuer.issue(any(TokenValidationResult.Valid.class), any(), any()))
                    .thenReturn(expectedToken);

            var result = service.issueAsync(validated, Optional.empty(), null)
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertTrue(result.isPresent());
            verify(roleManagement).expandRoles(Set.of("admin"));
        }

        @Test
        @DisplayName("should return empty when issuer fails during async flow")
        void shouldReturnEmptyWhenIssuerFails() {
            var service = createService();
            var validated = new TokenValidationResult.Valid(
                    "user-1",
                    "issuer",
                    Map.of("sub", "user-1", "roles", List.of("admin")),
                    Instant.now().plusSeconds(3600));

            when(roleManagement.expandRoles(any())).thenReturn(Uni.createFrom().item(Set.of("read")));
            when(issuer.issue(any(TokenValidationResult.Valid.class), any(), any()))
                    .thenThrow(new RuntimeException("signing failed"));

            var result = service.issueAsync(validated, Optional.empty(), "test-service")
                    .await()
                    .atMost(Duration.ofSeconds(1));

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("getJwsConfig()")
    class GetJwsConfigTests {

        @Test
        @DisplayName("should return JWS config when enabled")
        void shouldReturnJwsConfig() {
            var service = createService();
            var jwsConfig = service.getJwsConfig();

            assertEquals("aussie-gateway", jwsConfig.issuer());
            assertEquals("v1", jwsConfig.keyId());
        }

        @Test
        @DisplayName("should return null when disabled")
        void shouldReturnNullWhenDisabled() {
            when(routeAuthConfig.enabled()).thenReturn(false);
            var service = createService();

            assertNull(service.getJwsConfig());
        }
    }
}
