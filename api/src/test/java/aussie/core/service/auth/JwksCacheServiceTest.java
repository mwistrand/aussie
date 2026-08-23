package aussie.core.service.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.runtime.LaunchMode;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.core.buffer.Buffer;
import io.vertx.mutiny.core.net.SocketAddress;
import io.vertx.mutiny.ext.web.client.HttpRequest;
import io.vertx.mutiny.ext.web.client.HttpResponse;
import io.vertx.mutiny.ext.web.client.WebClient;
import org.jose4j.jwk.JsonWebKeySet;
import org.jose4j.jwk.RsaJwkGenerator;
import org.jose4j.lang.JoseException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.core.config.ResiliencyConfig;
import aussie.core.port.out.Metrics;
import aussie.core.port.out.OutboundHttpClients;
import aussie.core.service.routing.UpstreamAddressResolver;

@DisplayName("JwksCacheService")
@ExtendWith(MockitoExtension.class)
class JwksCacheServiceTest {

    private static final URI JWKS_URI = URI.create("https://auth.example.com/.well-known/jwks.json");
    private static final URI JWKS_URI_HTTP = URI.create("http://auth.example.com/.well-known/jwks.json");

    private static String singleKeyJwks;
    private static String multiKeyJwks;
    private static String singleKeyId;
    private static String secondKeyId;

    @Mock
    private OutboundHttpClients outboundClient;

    @Mock
    private WebClient webClient;

    @Mock
    private ResiliencyConfig resiliencyConfig;

    @Mock
    private ResiliencyConfig.JwksConfig jwksConfig;

    @Mock
    private Metrics metrics;

    @Mock
    private UpstreamAddressResolver addressResolver;

    private JwksCacheService service;

    @BeforeAll
    static void generateTestKeys() throws JoseException {
        singleKeyId = "key-1";
        secondKeyId = "key-2";

        var key1 = RsaJwkGenerator.generateJwk(2048);
        key1.setKeyId(singleKeyId);

        var key2 = RsaJwkGenerator.generateJwk(2048);
        key2.setKeyId(secondKeyId);

        var singleKeySet = new JsonWebKeySet(key1);
        singleKeyJwks = singleKeySet.toJson();

        var multiKeySet = new JsonWebKeySet(key1, key2);
        multiKeyJwks = multiKeySet.toJson();
    }

    @BeforeEach
    void setUp() {
        lenient().when(jwksConfig.fetchTimeout()).thenReturn(Duration.ofSeconds(5));
        lenient().when(jwksConfig.maxCacheEntries()).thenReturn(100);
        lenient().when(jwksConfig.cacheTtl()).thenReturn(Duration.ofHours(1));
        lenient().when(jwksConfig.maxResponseBytes()).thenReturn(262_144);
        lenient().when(jwksConfig.maxKeys()).thenReturn(32);
        lenient().when(jwksConfig.maximumStale()).thenReturn(Duration.ofMinutes(15));
        lenient().when(resiliencyConfig.jwks()).thenReturn(jwksConfig);
        lenient().when(outboundClient.jwksWebClient()).thenReturn(webClient);
        lenient()
                .when(addressResolver.resolve(any(URI.class)))
                .thenReturn(Uni.createFrom().item(io.vertx.core.net.SocketAddress.inetSocketAddress(443, "192.0.2.1")));

        service = new JwksCacheService(
                outboundClient, resiliencyConfig, new SimpleMeterRegistry(), metrics, addressResolver);
    }

    @SuppressWarnings("unchecked")
    private void mockFetchResponse(int statusCode, String body) {
        mockFetchResponse(statusCode, body, "application/json");
    }

    @SuppressWarnings("unchecked")
    private void mockFetchResponse(int statusCode, String body, String contentType) {
        HttpRequest<Buffer> request = (HttpRequest<Buffer>) org.mockito.Mockito.mock(HttpRequest.class);
        HttpResponse<Buffer> response = (HttpResponse<Buffer>) org.mockito.Mockito.mock(HttpResponse.class);

        when(webClient.requestAbs(any(), any(SocketAddress.class), anyString())).thenReturn(request);
        when(request.ssl(anyBoolean())).thenReturn(request);
        when(request.followRedirects(false)).thenReturn(request);
        when(request.send()).thenReturn(Uni.createFrom().item(response));
        when(response.statusCode()).thenReturn(statusCode);
        lenient().when(response.getHeader("Content-Type")).thenReturn(contentType);
        if (body != null) {
            lenient().when(response.bodyAsString()).thenReturn(body);
        }
    }

    @SuppressWarnings("unchecked")
    private void mockFetchFailure(Throwable error) {
        HttpRequest<Buffer> request = (HttpRequest<Buffer>) org.mockito.Mockito.mock(HttpRequest.class);

        when(webClient.requestAbs(any(), any(SocketAddress.class), anyString())).thenReturn(request);
        when(request.ssl(anyBoolean())).thenReturn(request);
        when(request.followRedirects(false)).thenReturn(request);
        when(request.send()).thenReturn(Uni.createFrom().failure(error));
    }

    @Nested
    @DisplayName("getKeySet()")
    class GetKeySetTests {

        @Test
        @DisplayName("should fetch and cache JWKS on first call")
        void shouldFetchOnFirstCall() {
            mockFetchResponse(200, singleKeyJwks);

            var result = service.getKeySet(JWKS_URI).await().atMost(Duration.ofSeconds(5));

            assertNotNull(result);
            assertEquals(1, result.getJsonWebKeys().size());
            verify(webClient).requestAbs(any(), any(SocketAddress.class), eq(JWKS_URI.toString()));
        }

        @Test
        @DisplayName("should return cached JWKS on subsequent calls without re-fetching")
        void shouldReturnCachedOnSubsequentCalls() {
            mockFetchResponse(200, singleKeyJwks);

            service.getKeySet(JWKS_URI).await().atMost(Duration.ofSeconds(5));
            var result = service.getKeySet(JWKS_URI).await().atMost(Duration.ofSeconds(5));

            assertNotNull(result);
            assertEquals(1, result.getJsonWebKeys().size());
            verify(webClient, times(1)).requestAbs(any(), any(SocketAddress.class), anyString());
        }

        @Test
        @DisplayName("should cache multiple keys from JWKS endpoint")
        void shouldCacheMultipleKeys() {
            mockFetchResponse(200, multiKeyJwks);

            var result = service.getKeySet(JWKS_URI).await().atMost(Duration.ofSeconds(5));

            assertEquals(2, result.getJsonWebKeys().size());
        }

        @Test
        @DisplayName("should throw JwksFetchException on non-200 response")
        void shouldThrowOnNon200Response() {
            mockFetchResponse(500, null);

            var exception = assertThrows(
                    JwksCacheService.JwksFetchException.class,
                    () -> service.getKeySet(JWKS_URI).await().atMost(Duration.ofSeconds(5)));

            assertTrue(exception.getMessage().contains("500"));
        }

        @Test
        @DisplayName("should throw JwksFetchException on 403 response")
        void shouldThrowOn403Response() {
            mockFetchResponse(403, null);

            var exception = assertThrows(
                    JwksCacheService.JwksFetchException.class,
                    () -> service.getKeySet(JWKS_URI).await().atMost(Duration.ofSeconds(5)));

            assertTrue(exception.getMessage().contains("403"));
        }

        @Test
        @DisplayName("should throw JwksFetchException on invalid JWKS JSON")
        void shouldThrowOnInvalidJson() {
            mockFetchResponse(200, "not valid json");

            var exception = assertThrows(
                    JwksCacheService.JwksFetchException.class,
                    () -> service.getKeySet(JWKS_URI).await().atMost(Duration.ofSeconds(5)));

            assertTrue(exception.getMessage().contains("Failed to parse"));
        }

        @Test
        @DisplayName("should reject non-JWKS content types")
        void shouldRejectUnexpectedContentType() {
            mockFetchResponse(200, singleKeyJwks, "text/html");

            final var exception = assertThrows(
                    JwksCacheService.JwksFetchException.class,
                    () -> service.getKeySet(JWKS_URI).await().atMost(Duration.ofSeconds(5)));

            assertTrue(exception.getMessage().contains("content type"));
        }

        @Test
        @DisplayName("should reject oversized JWKS responses")
        void shouldRejectOversizedResponse() {
            when(jwksConfig.maxResponseBytes()).thenReturn(10);
            service = new JwksCacheService(
                    outboundClient, resiliencyConfig, new SimpleMeterRegistry(), metrics, addressResolver);
            mockFetchResponse(200, singleKeyJwks);

            final var exception = assertThrows(
                    JwksCacheService.JwksFetchException.class,
                    () -> service.getKeySet(JWKS_URI).await().atMost(Duration.ofSeconds(5)));

            assertTrue(exception.getMessage().contains("size limit"));
        }

        @Test
        @DisplayName("should propagate fetch failure when no stale cache exists")
        void shouldPropagateFailureWhenNoStaleCache() {
            mockFetchFailure(new RuntimeException("Connection refused"));

            assertThrows(
                    RuntimeException.class,
                    () -> service.getKeySet(JWKS_URI).await().atMost(Duration.ofSeconds(5)));
        }

        @Test
        @DisplayName("should use SSL for https URIs")
        @SuppressWarnings("unchecked")
        void shouldUseSslForHttpsUri() {
            HttpRequest<Buffer> request = (HttpRequest<Buffer>) org.mockito.Mockito.mock(HttpRequest.class);
            HttpResponse<Buffer> response = (HttpResponse<Buffer>) org.mockito.Mockito.mock(HttpResponse.class);

            when(webClient.requestAbs(any(), any(SocketAddress.class), eq(JWKS_URI.toString())))
                    .thenReturn(request);
            when(request.ssl(true)).thenReturn(request);
            when(request.followRedirects(false)).thenReturn(request);
            when(request.send()).thenReturn(Uni.createFrom().item(response));
            when(response.statusCode()).thenReturn(200);
            when(response.getHeader("Content-Type")).thenReturn("application/json");
            when(response.bodyAsString()).thenReturn(singleKeyJwks);

            service.getKeySet(JWKS_URI).await().atMost(Duration.ofSeconds(5));

            verify(request).ssl(true);
        }

        @Test
        @DisplayName("should use SSL for case-insensitive HTTPS schemes")
        @SuppressWarnings("unchecked")
        void shouldUseSslForUppercaseHttpsUri() {
            final var uri = URI.create("HTTPS://auth.example.com/.well-known/jwks.json");
            HttpRequest<Buffer> request = (HttpRequest<Buffer>) org.mockito.Mockito.mock(HttpRequest.class);
            HttpResponse<Buffer> response = (HttpResponse<Buffer>) org.mockito.Mockito.mock(HttpResponse.class);

            when(webClient.requestAbs(any(), any(SocketAddress.class), eq(uri.toString())))
                    .thenReturn(request);
            when(request.ssl(true)).thenReturn(request);
            when(request.followRedirects(false)).thenReturn(request);
            when(request.send()).thenReturn(Uni.createFrom().item(response));
            when(response.statusCode()).thenReturn(200);
            when(response.getHeader("Content-Type")).thenReturn("application/json");
            when(response.bodyAsString()).thenReturn(singleKeyJwks);

            service.getKeySet(uri).await().atMost(Duration.ofSeconds(5));

            verify(request).ssl(true);
        }

        @Test
        @DisplayName("should reject plaintext JWKS outside dev/test")
        void shouldRejectHttpUri() {
            assertThrows(
                    JwksCacheService.JwksFetchException.class,
                    () -> JwksCacheService.validateUri(JWKS_URI_HTTP, LaunchMode.NORMAL, List.of("prod")));

            verify(webClient, never()).requestAbs(any(), any(SocketAddress.class), anyString());
        }

        @Test
        @DisplayName("should allow plaintext JWKS for a dev profile in a packaged application")
        void shouldAllowHttpUriForPackagedDevProfile() {
            JwksCacheService.validateUri(JWKS_URI_HTTP, LaunchMode.NORMAL, List.of("dev"));
        }
    }

    @Nested
    @DisplayName("getKey()")
    class GetKeyTests {

        @Test
        @DisplayName("should find key by ID")
        void shouldFindKeyById() {
            mockFetchResponse(200, multiKeyJwks);

            var result = service.getKey(JWKS_URI, singleKeyId).await().atMost(Duration.ofSeconds(5));

            assertTrue(result.isPresent());
            assertEquals(singleKeyId, result.get().getKeyId());
        }

        @Test
        @DisplayName("should return empty for non-existent key ID")
        void shouldReturnEmptyForNonExistentKeyId() {
            mockFetchResponse(200, multiKeyJwks);

            var result = service.getKey(JWKS_URI, "non-existent").await().atMost(Duration.ofSeconds(5));

            assertFalse(result.isPresent());
        }

        @Test
        @DisplayName("should return single key when no key ID specified and only one key exists")
        void shouldReturnSingleKeyWhenNoKeyIdSpecified() {
            mockFetchResponse(200, singleKeyJwks);

            var result = service.getKey(JWKS_URI, null).await().atMost(Duration.ofSeconds(5));

            assertTrue(result.isPresent());
            assertEquals(singleKeyId, result.get().getKeyId());
        }

        @Test
        @DisplayName("should return empty when no key ID specified and multiple keys exist")
        void shouldReturnEmptyWhenNoKeyIdAndMultipleKeys() {
            mockFetchResponse(200, multiKeyJwks);

            var result = service.getKey(JWKS_URI, null).await().atMost(Duration.ofSeconds(5));

            assertFalse(result.isPresent());
        }

        @Test
        @DisplayName("should find second key by ID")
        void shouldFindSecondKeyById() {
            mockFetchResponse(200, multiKeyJwks);

            var result = service.getKey(JWKS_URI, secondKeyId).await().atMost(Duration.ofSeconds(5));

            assertTrue(result.isPresent());
            assertEquals(secondKeyId, result.get().getKeyId());
        }
    }

    @Nested
    @DisplayName("refresh()")
    class RefreshTests {

        @Test
        @DisplayName("should invalidate cache and re-fetch")
        void shouldInvalidateCacheAndRefetch() {
            mockFetchResponse(200, singleKeyJwks);

            // Initial fetch
            service.getKeySet(JWKS_URI).await().atMost(Duration.ofSeconds(5));

            // Re-mock for the refresh fetch
            mockFetchResponse(200, multiKeyJwks);

            // Refresh should re-fetch
            var result = service.refresh(JWKS_URI).await().atMost(Duration.ofSeconds(5));

            assertEquals(2, result.getJsonWebKeys().size());
            verify(webClient, times(2)).requestAbs(any(), any(SocketAddress.class), anyString());
        }

        @Test
        @DisplayName("should return fresh keys after refresh")
        void shouldReturnFreshKeysAfterRefresh() {
            mockFetchResponse(200, singleKeyJwks);
            service.getKeySet(JWKS_URI).await().atMost(Duration.ofSeconds(5));

            mockFetchResponse(200, multiKeyJwks);
            service.refresh(JWKS_URI).await().atMost(Duration.ofSeconds(5));

            // Subsequent getKeySet should use refreshed cache
            var result = service.getKeySet(JWKS_URI).await().atMost(Duration.ofSeconds(5));

            assertEquals(2, result.getJsonWebKeys().size());
            // Should not fetch again (cached from refresh)
            verify(webClient, times(2)).requestAbs(any(), any(SocketAddress.class), anyString());
        }
    }

    @Nested
    @DisplayName("stale fallback")
    class StaleFallbackTests {

        @Test
        @DisplayName("uses expired keys only inside maximum-stale")
        void staleFallbackIsBounded() throws Exception {
            when(jwksConfig.cacheTtl()).thenReturn(Duration.ofMillis(5));
            when(jwksConfig.maximumStale()).thenReturn(Duration.ofSeconds(1));
            service = new JwksCacheService(
                    outboundClient, resiliencyConfig, new SimpleMeterRegistry(), metrics, addressResolver);
            mockFetchResponse(200, singleKeyJwks);
            service.getKeySet(JWKS_URI).await().atMost(Duration.ofSeconds(5));
            Thread.sleep(20);
            mockFetchFailure(new RuntimeException("provider unavailable"));

            final var stale = service.getKeySet(JWKS_URI).await().atMost(Duration.ofSeconds(5));

            assertEquals(singleKeyId, stale.getJsonWebKeys().getFirst().getKeyId());
        }
    }

    @Nested
    @DisplayName("invalidate()")
    class InvalidateTests {

        @Test
        @DisplayName("should clear cached entry requiring re-fetch on next access")
        void shouldClearCachedEntry() {
            mockFetchResponse(200, singleKeyJwks);

            // Populate cache
            service.getKeySet(JWKS_URI).await().atMost(Duration.ofSeconds(5));

            // Invalidate
            service.invalidate(JWKS_URI);

            // Re-mock for next fetch
            mockFetchResponse(200, multiKeyJwks);

            // Next access should re-fetch
            var result = service.getKeySet(JWKS_URI).await().atMost(Duration.ofSeconds(5));

            assertEquals(2, result.getJsonWebKeys().size());
            verify(webClient, times(2)).requestAbs(any(), any(SocketAddress.class), anyString());
        }
    }

    @Nested
    @DisplayName("JwksFetchException")
    class JwksFetchExceptionTests {

        @Test
        @DisplayName("should preserve message")
        void shouldPreserveMessage() {
            var exception = new JwksCacheService.JwksFetchException("test message");

            assertEquals("test message", exception.getMessage());
            assertInstanceOf(RuntimeException.class, exception);
        }

        @Test
        @DisplayName("should preserve cause")
        void shouldPreserveCause() {
            var cause = new RuntimeException("root cause");
            var exception = new JwksCacheService.JwksFetchException("test message", cause);

            assertEquals("test message", exception.getMessage());
            assertEquals(cause, exception.getCause());
        }
    }

    @Nested
    @DisplayName("request coalescing")
    class RequestCoalescingTests {

        @Test
        @DisplayName("should fetch independently for different URIs")
        void shouldFetchIndependentlyForDifferentUris() {
            mockFetchResponse(200, singleKeyJwks);

            var uri1 = URI.create("https://provider-a.example.com/.well-known/jwks.json");
            var uri2 = URI.create("https://provider-b.example.com/.well-known/jwks.json");

            service.getKeySet(uri1).await().atMost(Duration.ofSeconds(5));
            service.getKeySet(uri2).await().atMost(Duration.ofSeconds(5));

            verify(webClient).requestAbs(any(), any(SocketAddress.class), eq(uri1.toString()));
            verify(webClient).requestAbs(any(), any(SocketAddress.class), eq(uri2.toString()));
        }
    }

    @Nested
    @DisplayName("timeout handling")
    class TimeoutHandlingTests {

        @Test
        @DisplayName("should record metrics on fetch timeout")
        void shouldRecordMetricsOnTimeout() {
            // Use a very short timeout config
            when(jwksConfig.fetchTimeout()).thenReturn(Duration.ofMillis(1));

            service = new JwksCacheService(
                    outboundClient, resiliencyConfig, new SimpleMeterRegistry(), metrics, addressResolver);

            // Mock a response that never arrives (Uni that never emits)
            @SuppressWarnings("unchecked")
            HttpRequest<Buffer> request = (HttpRequest<Buffer>) org.mockito.Mockito.mock(HttpRequest.class);

            when(webClient.requestAbs(any(), any(SocketAddress.class), anyString()))
                    .thenReturn(request);
            when(request.ssl(anyBoolean())).thenReturn(request);
            when(request.followRedirects(false)).thenReturn(request);
            when(request.send()).thenReturn(Uni.createFrom().nothing());

            var exception = assertThrows(
                    JwksCacheService.JwksFetchException.class,
                    () -> service.getKeySet(JWKS_URI).await().atMost(Duration.ofSeconds(10)));

            assertTrue(exception.getMessage().contains("Timeout"));
            verify(metrics).recordJwksFetchTimeout(JWKS_URI.getHost());
        }
    }
}
