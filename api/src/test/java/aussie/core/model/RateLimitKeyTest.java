package aussie.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.ratelimit.*;

@DisplayName("RateLimitKey")
class RateLimitKeyTest {

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("should create key with all parameters")
        void shouldCreateKeyWithAllParameters() {
            var key = new RateLimitKey(RateLimitKeyType.HTTP, "client-123", "service-1", Optional.of("endpoint-1"));

            assertEquals(RateLimitKeyType.HTTP, key.keyType());
            assertEquals("client-123", key.clientId());
            assertEquals("service-1", key.serviceId());
            assertTrue(key.endpointId().isPresent());
            assertEquals("endpoint-1", key.endpointId().get());
        }

        @Test
        @DisplayName("should throw when keyType is null")
        void shouldThrowWhenKeyTypeNull() {
            assertThrows(
                    NullPointerException.class, () -> new RateLimitKey(null, "client", "service", Optional.empty()));
        }

        @Test
        @DisplayName("should throw when clientId is null")
        void shouldThrowWhenClientIdNull() {
            assertThrows(
                    NullPointerException.class,
                    () -> new RateLimitKey(RateLimitKeyType.HTTP, null, "service", Optional.empty()));
        }

        @Test
        @DisplayName("should throw when serviceId is null")
        void shouldThrowWhenServiceIdNull() {
            assertThrows(
                    NullPointerException.class,
                    () -> new RateLimitKey(RateLimitKeyType.HTTP, "client", null, Optional.empty()));
        }

        @Test
        @DisplayName("should default endpointId to empty Optional when null")
        void shouldDefaultEndpointIdToEmptyWhenNull() {
            var key = new RateLimitKey(RateLimitKeyType.HTTP, "client", "service", null);

            assertTrue(key.endpointId().isEmpty());
        }
    }

    @Nested
    @DisplayName("Factory methods")
    class FactoryMethodTests {

        @Test
        @DisplayName("http() should create HTTP key type")
        void httpShouldCreateHttpKeyType() {
            var key = RateLimitKey.http("client-id", "my-service", "/api/users");

            assertEquals(RateLimitKeyType.HTTP, key.keyType());
            assertEquals("client-id", key.clientId());
            assertEquals("my-service", key.serviceId());
            assertEquals("/api/users", key.endpointId().orElse(null));
        }

        @Test
        @DisplayName("http() should handle null endpoint")
        void httpShouldHandleNullEndpoint() {
            var key = RateLimitKey.http("client-id", "my-service", null);

            assertEquals(RateLimitKeyType.HTTP, key.keyType());
            assertTrue(key.endpointId().isEmpty());
        }

        @Test
        @DisplayName("wsConnection() should create WS_CONNECTION key type")
        void wsConnectionShouldCreateWsConnectionKeyType() {
            var key = RateLimitKey.wsConnection("client-id", "my-service");

            assertEquals(RateLimitKeyType.WS_CONNECTION, key.keyType());
            assertEquals("client-id", key.clientId());
            assertEquals("my-service", key.serviceId());
            assertTrue(key.endpointId().isEmpty());
        }

        @Test
        @DisplayName("wsMessage() should create WS_MESSAGE key type with connection ID")
        void wsMessageShouldCreateWsMessageKeyTypeWithConnectionId() {
            var key = RateLimitKey.wsMessage("client-id", "my-service", "conn-123");

            assertEquals(RateLimitKeyType.WS_MESSAGE, key.keyType());
            assertEquals("client-id", key.clientId());
            assertEquals("my-service", key.serviceId());
            assertEquals("conn-123", key.endpointId().orElse(null));
        }
    }

    @Nested
    @DisplayName("Cache key generation")
    class CacheKeyTests {

        @Test
        @DisplayName("HTTP key with endpoint should include all parts")
        void httpKeyWithEndpointShouldIncludeAllParts() {
            var key = RateLimitKey.http("ip:192.168.1.1", "user-service", "/api/users");

            var cacheKey = key.toCacheKey();

            assertEquals("aussie:ratelimit:http:user-service:/api/users:ip:192.168.1.1", cacheKey);
        }

        @Test
        @DisplayName("HTTP key without endpoint should use wildcard")
        void httpKeyWithoutEndpointShouldUseWildcard() {
            var key = RateLimitKey.http("bearer:abc123", "user-service", null);

            var cacheKey = key.toCacheKey();

            assertEquals("aussie:ratelimit:http:user-service:*:bearer:abc123", cacheKey);
        }

        @Test
        @DisplayName("WS_CONNECTION key should use conn prefix")
        void wsConnectionKeyShouldUseConnPrefix() {
            var key = RateLimitKey.wsConnection("session:xyz789", "chat-service");

            var cacheKey = key.toCacheKey();

            assertEquals("aussie:ratelimit:ws:conn:chat-service:session:xyz789", cacheKey);
        }

        @Test
        @DisplayName("WS_MESSAGE key should include connection ID")
        void wsMessageKeyShouldIncludeConnectionId() {
            var key = RateLimitKey.wsMessage("apikey:key123", "chat-service", "conn-456");

            var cacheKey = key.toCacheKey();

            assertEquals("aussie:ratelimit:ws:msg:chat-service:apikey:key123:conn-456", cacheKey);
        }

        @Test
        @DisplayName("WS_MESSAGE key without connection ID should use unknown")
        void wsMessageKeyWithoutConnectionIdShouldUseUnknown() {
            var key = new RateLimitKey(RateLimitKeyType.WS_MESSAGE, "client-id", "my-service", Optional.empty());

            var cacheKey = key.toCacheKey();

            assertEquals("aussie:ratelimit:ws:msg:my-service:client-id:unknown", cacheKey);
        }
    }

    @Nested
    @DisplayName("Key uniqueness")
    class KeyUniquenessTests {

        @Test
        @DisplayName("different clients should have different keys")
        void differentClientsShouldHaveDifferentKeys() {
            var key1 = RateLimitKey.http("client-1", "service", "/api");
            var key2 = RateLimitKey.http("client-2", "service", "/api");

            assertTrue(!key1.toCacheKey().equals(key2.toCacheKey()));
        }

        @Test
        @DisplayName("different services should have different keys")
        void differentServicesShouldHaveDifferentKeys() {
            var key1 = RateLimitKey.http("client", "service-1", "/api");
            var key2 = RateLimitKey.http("client", "service-2", "/api");

            assertTrue(!key1.toCacheKey().equals(key2.toCacheKey()));
        }

        @Test
        @DisplayName("different endpoints should have different keys")
        void differentEndpointsShouldHaveDifferentKeys() {
            var key1 = RateLimitKey.http("client", "service", "/api/users");
            var key2 = RateLimitKey.http("client", "service", "/api/posts");

            assertTrue(!key1.toCacheKey().equals(key2.toCacheKey()));
        }

        @Test
        @DisplayName("different key types should have different keys")
        void differentKeyTypesShouldHaveDifferentKeys() {
            var httpKey = RateLimitKey.http("client", "service", null);
            var wsConnKey = RateLimitKey.wsConnection("client", "service");

            assertTrue(!httpKey.toCacheKey().equals(wsConnKey.toCacheKey()));
        }

        @Test
        @DisplayName("same parameters should produce same key")
        void sameParametersShouldProduceSameKey() {
            var key1 = RateLimitKey.http("client", "service", "/api");
            var key2 = RateLimitKey.http("client", "service", "/api");

            assertEquals(key1.toCacheKey(), key2.toCacheKey());
        }
    }

    @Nested
    @DisplayName("Client ID formats")
    class ClientIdFormatTests {

        @Test
        @DisplayName("should handle session-prefixed client ID")
        void shouldHandleSessionPrefixedClientId() {
            var key = RateLimitKey.http("session:abc123def456", "service", "/api");

            assertTrue(key.toCacheKey().contains("session:abc123def456"));
        }

        @Test
        @DisplayName("should handle bearer-prefixed client ID")
        void shouldHandleBearerPrefixedClientId() {
            var key = RateLimitKey.http("bearer:7f3b2a1c", "service", "/api");

            assertTrue(key.toCacheKey().contains("bearer:7f3b2a1c"));
        }

        @Test
        @DisplayName("should handle apikey-prefixed client ID")
        void shouldHandleApikeyPrefixedClientId() {
            var key = RateLimitKey.http("apikey:key-id-123", "service", "/api");

            assertTrue(key.toCacheKey().contains("apikey:key-id-123"));
        }

        @Test
        @DisplayName("should handle ip-prefixed client ID")
        void shouldHandleIpPrefixedClientId() {
            var key = RateLimitKey.http("ip:10.0.0.1", "service", "/api");

            assertTrue(key.toCacheKey().contains("ip:10.0.0.1"));
        }
    }
}
