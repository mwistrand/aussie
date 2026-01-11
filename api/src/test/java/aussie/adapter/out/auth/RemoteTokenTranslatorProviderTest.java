package aussie.adapter.out.auth;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.vertx.mutiny.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.adapter.out.telemetry.TokenTranslationMetrics;
import aussie.core.config.TokenTranslationConfig;
import aussie.core.config.TokenTranslationConfig.Remote.FailMode;

/**
 * Unit tests for RemoteTokenTranslatorProvider.
 */
@DisplayName("RemoteTokenTranslatorProvider")
@ExtendWith(MockitoExtension.class)
class RemoteTokenTranslatorProviderTest {

    private static final String ISSUER = "https://issuer.example.com";
    private static final String SUBJECT = "user-123";

    @Mock
    private TokenTranslationConfig config;

    @Mock
    private TokenTranslationConfig.Remote remoteConfig;

    @Mock
    private TokenTranslationMetrics metrics;

    private WireMockServer wireMockServer;
    private Vertx vertx;
    private RemoteTokenTranslatorProvider provider;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMockServer.start();

        lenient().when(config.remote()).thenReturn(remoteConfig);
        lenient().when(remoteConfig.timeout()).thenReturn(Duration.ofSeconds(5));
        lenient().when(remoteConfig.failMode()).thenReturn(FailMode.deny);
    }

    @AfterEach
    void tearDown() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
        if (vertx != null) {
            vertx.close().await().indefinitely();
        }
    }

    private void initProvider(String url) {
        lenient().when(remoteConfig.url()).thenReturn(Optional.ofNullable(url));
        provider = new RemoteTokenTranslatorProvider(vertx, config, metrics);
    }

    @Nested
    @DisplayName("Provider metadata")
    class ProviderMetadata {

        @Test
        @DisplayName("should have name 'remote'")
        void shouldHaveRemoteName() {
            initProvider(null);
            assertEquals("remote", provider.name());
        }

        @Test
        @DisplayName("should have priority 25")
        void shouldHavePriority25() {
            initProvider(null);
            assertEquals(25, provider.priority());
        }

        @Test
        @DisplayName("should be unavailable when URL not configured")
        void shouldBeUnavailableWhenNoUrl() {
            initProvider(null);
            assertFalse(provider.isAvailable());
        }

        @Test
        @DisplayName("should be unavailable when URL is blank")
        void shouldBeUnavailableWhenBlankUrl() {
            initProvider("   ");
            assertFalse(provider.isAvailable());
        }

        @Test
        @DisplayName("should be available when URL is configured")
        void shouldBeAvailableWithUrl() {
            initProvider("http://localhost:8080/translate");
            assertTrue(provider.isAvailable());
        }

        @Test
        @DisplayName("should provide health check when URL configured")
        void shouldProvideHealthCheckWhenAvailable() {
            initProvider("http://localhost:8080/translate");

            var response = provider.healthCheck();

            assertTrue(response.isPresent());
            assertEquals("token-translator-remote", response.get().getName());
        }

        @Test
        @DisplayName("should provide down health check when URL not configured")
        void shouldProvideDownHealthCheckWhenUnavailable() {
            initProvider(null);

            var response = provider.healthCheck();

            assertTrue(response.isPresent());
            assertEquals("token-translator-remote", response.get().getName());
        }
    }

    @Nested
    @DisplayName("Translation")
    class Translation {

        @Test
        @DisplayName("should call remote service and return translated claims")
        void shouldCallRemoteAndReturnClaims() {
            var url = wireMockServer.baseUrl() + "/translate";
            initProvider(url);

            wireMockServer.stubFor(
                    post(urlEqualTo("/translate"))
                            .willReturn(
                                    aResponse()
                                            .withStatus(200)
                                            .withHeader("Content-Type", "application/json")
                                            .withBody(
                                                    """
                                    {
                                      "roles": ["admin", "user"],
                                      "permissions": ["read", "write", "delete"]
                                    }
                                    """)));

            var claims = Map.<String, Object>of("groups", "admins");
            var result = provider.translate(ISSUER, SUBJECT, claims).await().indefinitely();

            assertEquals(Set.of("admin", "user"), result.roles());
            assertEquals(Set.of("read", "write", "delete"), result.permissions());
        }

        @Test
        @DisplayName("should send correct request body to remote service")
        void shouldSendCorrectRequestBody() {
            var url = wireMockServer.baseUrl() + "/translate";
            initProvider(url);

            wireMockServer.stubFor(post(urlEqualTo("/translate"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"roles\": [], \"permissions\": []}")));

            var claims = Map.<String, Object>of("scope", "openid profile");
            provider.translate(ISSUER, SUBJECT, claims).await().indefinitely();

            wireMockServer.verify(
                    postRequestedFor(urlEqualTo("/translate"))
                            .withRequestBody(
                                    equalToJson(
                                            """
                            {
                              "issuer": "https://issuer.example.com",
                              "subject": "user-123",
                              "claims": { "scope": "openid profile" }
                            }
                            """)));
        }

        @Test
        @DisplayName("should return empty roles when not present in response")
        void shouldReturnEmptyRolesWhenMissing() {
            var url = wireMockServer.baseUrl() + "/translate";
            initProvider(url);

            wireMockServer.stubFor(post(urlEqualTo("/translate"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"permissions\": [\"read\"]}")));

            var result = provider.translate(ISSUER, SUBJECT, Map.of()).await().indefinitely();

            assertTrue(result.roles().isEmpty());
            assertEquals(Set.of("read"), result.permissions());
        }

        @Test
        @DisplayName("should return empty permissions when not present in response")
        void shouldReturnEmptyPermissionsWhenMissing() {
            var url = wireMockServer.baseUrl() + "/translate";
            initProvider(url);

            wireMockServer.stubFor(post(urlEqualTo("/translate"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"roles\": [\"admin\"]}")));

            var result = provider.translate(ISSUER, SUBJECT, Map.of()).await().indefinitely();

            assertEquals(Set.of("admin"), result.roles());
            assertTrue(result.permissions().isEmpty());
        }
    }

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        @DisplayName("should throw exception on non-200 response with deny fail mode")
        void shouldThrowOnNon200WithDenyMode() {
            var url = wireMockServer.baseUrl() + "/translate";
            initProvider(url);

            wireMockServer.stubFor(post(urlEqualTo("/translate"))
                    .willReturn(aResponse().withStatus(500).withBody("Internal Server Error")));

            var claims = Map.<String, Object>of();
            assertThrows(
                    RuntimeException.class,
                    () -> provider.translate(ISSUER, SUBJECT, claims).await().indefinitely());
        }

        @Test
        @DisplayName("should return empty claims on failure with allow_empty fail mode")
        void shouldReturnEmptyOnFailureWithAllowEmptyMode() {
            var url = wireMockServer.baseUrl() + "/translate";
            when(remoteConfig.failMode()).thenReturn(FailMode.allow_empty);
            initProvider(url);

            wireMockServer.stubFor(post(urlEqualTo("/translate"))
                    .willReturn(aResponse().withStatus(503).withBody("Service Unavailable")));

            var result = provider.translate(ISSUER, SUBJECT, Map.of()).await().indefinitely();

            assertTrue(result.roles().isEmpty());
            assertTrue(result.permissions().isEmpty());
        }

        @Test
        @DisplayName("should handle timeout with deny fail mode")
        void shouldHandleTimeoutWithDenyMode() {
            var url = wireMockServer.baseUrl() + "/translate";
            when(remoteConfig.timeout()).thenReturn(Duration.ofMillis(100));
            initProvider(url);

            wireMockServer.stubFor(post(urlEqualTo("/translate"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withFixedDelay(500)
                            .withBody("{\"roles\": [], \"permissions\": []}")));

            assertThrows(
                    RuntimeException.class,
                    () -> provider.translate(ISSUER, SUBJECT, Map.of()).await().indefinitely());
        }

        @Test
        @DisplayName("should return empty claims on timeout with allow_empty fail mode")
        void shouldReturnEmptyOnTimeoutWithAllowEmptyMode() {
            var url = wireMockServer.baseUrl() + "/translate";
            when(remoteConfig.timeout()).thenReturn(Duration.ofMillis(100));
            when(remoteConfig.failMode()).thenReturn(FailMode.allow_empty);
            initProvider(url);

            wireMockServer.stubFor(post(urlEqualTo("/translate"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withFixedDelay(500)
                            .withBody("{\"roles\": [], \"permissions\": []}")));

            var result = provider.translate(ISSUER, SUBJECT, Map.of()).await().indefinitely();

            assertTrue(result.roles().isEmpty());
            assertTrue(result.permissions().isEmpty());
        }

        @Test
        @DisplayName("should handle 400 Bad Request")
        void shouldHandle400BadRequest() {
            var url = wireMockServer.baseUrl() + "/translate";
            when(remoteConfig.failMode()).thenReturn(FailMode.allow_empty);
            initProvider(url);

            wireMockServer.stubFor(post(urlEqualTo("/translate"))
                    .willReturn(aResponse().withStatus(400).withBody("{\"error\": \"Invalid request\"}")));

            var result = provider.translate(ISSUER, SUBJECT, Map.of()).await().indefinitely();

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should handle connection refused with allow_empty fail mode")
        void shouldHandleConnectionRefused() {
            when(remoteConfig.failMode()).thenReturn(FailMode.allow_empty);
            initProvider("http://localhost:9999/translate");

            var result = provider.translate(ISSUER, SUBJECT, Map.of()).await().indefinitely();

            assertTrue(result.isEmpty());
        }
    }
}
