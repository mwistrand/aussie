package aussie.core.model.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.ratelimit.EndpointRateLimitConfig;
import aussie.core.model.sampling.EndpointSamplingConfig;

@DisplayName("EndpointConfig")
class EndpointConfigTest {

    @Nested
    @DisplayName("canonical constructor validation")
    class CanonicalConstructorValidation {

        @Test
        @DisplayName("shouldThrowWhenPathIsNull")
        void shouldThrowWhenPathIsNull() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new EndpointConfig(
                            null,
                            Set.of("GET"),
                            EndpointVisibility.PUBLIC,
                            Optional.empty(),
                            false,
                            EndpointType.HTTP,
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty()));
        }

        @Test
        @DisplayName("shouldThrowWhenPathIsBlank")
        void shouldThrowWhenPathIsBlank() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new EndpointConfig(
                            "   ",
                            Set.of("GET"),
                            EndpointVisibility.PUBLIC,
                            Optional.empty(),
                            false,
                            EndpointType.HTTP,
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty()));
        }

        @Test
        @DisplayName("shouldThrowWhenVisibilityIsNull")
        void shouldThrowWhenVisibilityIsNull() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new EndpointConfig(
                            "/api/test",
                            Set.of("GET"),
                            null,
                            Optional.empty(),
                            false,
                            EndpointType.HTTP,
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty()));
        }

        @Test
        @DisplayName("shouldDefaultPathRewriteToEmptyWhenNull")
        void shouldDefaultPathRewriteToEmptyWhenNull() {
            var config = new EndpointConfig(
                    "/api/test",
                    Set.of("GET"),
                    EndpointVisibility.PUBLIC,
                    null,
                    false,
                    EndpointType.HTTP,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty());

            assertTrue(config.pathRewrite().isEmpty());
        }

        @Test
        @DisplayName("shouldDefaultTypeToHttpWhenNull")
        void shouldDefaultTypeToHttpWhenNull() {
            var config = new EndpointConfig(
                    "/api/test",
                    Set.of("GET"),
                    EndpointVisibility.PUBLIC,
                    Optional.empty(),
                    false,
                    null,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty());

            assertEquals(EndpointType.HTTP, config.type());
        }

        @Test
        @DisplayName("shouldDefaultRateLimitConfigToEmptyWhenNull")
        void shouldDefaultRateLimitConfigToEmptyWhenNull() {
            var config = new EndpointConfig(
                    "/api/test",
                    Set.of("GET"),
                    EndpointVisibility.PUBLIC,
                    Optional.empty(),
                    false,
                    EndpointType.HTTP,
                    null,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty());

            assertTrue(config.rateLimitConfig().isEmpty());
        }

        @Test
        @DisplayName("shouldDefaultSamplingConfigToEmptyWhenNull")
        void shouldDefaultSamplingConfigToEmptyWhenNull() {
            var config = new EndpointConfig(
                    "/api/test",
                    Set.of("GET"),
                    EndpointVisibility.PUBLIC,
                    Optional.empty(),
                    false,
                    EndpointType.HTTP,
                    Optional.empty(),
                    null,
                    Optional.empty(),
                    Optional.empty());

            assertTrue(config.samplingConfig().isEmpty());
        }

        @Test
        @DisplayName("shouldDefaultAudienceToEmptyWhenNull")
        void shouldDefaultAudienceToEmptyWhenNull() {
            var config = new EndpointConfig(
                    "/api/test",
                    Set.of("GET"),
                    EndpointVisibility.PUBLIC,
                    Optional.empty(),
                    false,
                    EndpointType.HTTP,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    null);

            assertTrue(config.audience().isEmpty());
        }

        @Test
        @DisplayName("shouldDefaultWebSocketMethodsToGetWhenNull")
        void shouldDefaultWebSocketMethodsToGetWhenNull() {
            var config = new EndpointConfig(
                    "/ws/test",
                    null,
                    EndpointVisibility.PUBLIC,
                    Optional.empty(),
                    false,
                    EndpointType.WEBSOCKET,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty());

            assertEquals(Set.of("GET"), config.methods());
        }

        @Test
        @DisplayName("shouldDefaultWebSocketMethodsToGetWhenEmpty")
        void shouldDefaultWebSocketMethodsToGetWhenEmpty() {
            var config = new EndpointConfig(
                    "/ws/test",
                    Set.of(),
                    EndpointVisibility.PUBLIC,
                    Optional.empty(),
                    false,
                    EndpointType.WEBSOCKET,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty());

            assertEquals(Set.of("GET"), config.methods());
        }

        @Test
        @DisplayName("shouldThrowWhenHttpMethodsAreNull")
        void shouldThrowWhenHttpMethodsAreNull() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new EndpointConfig(
                            "/api/test",
                            null,
                            EndpointVisibility.PUBLIC,
                            Optional.empty(),
                            false,
                            EndpointType.HTTP,
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty()));
        }

        @Test
        @DisplayName("shouldThrowWhenHttpMethodsAreEmpty")
        void shouldThrowWhenHttpMethodsAreEmpty() {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new EndpointConfig(
                            "/api/test",
                            Set.of(),
                            EndpointVisibility.PUBLIC,
                            Optional.empty(),
                            false,
                            EndpointType.HTTP,
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty()));
        }

        @Test
        @DisplayName("shouldKeepExplicitWebSocketMethods")
        void shouldKeepExplicitWebSocketMethods() {
            var config = new EndpointConfig(
                    "/ws/test",
                    Set.of("GET", "POST"),
                    EndpointVisibility.PUBLIC,
                    Optional.empty(),
                    false,
                    EndpointType.WEBSOCKET,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty());

            assertEquals(Set.of("GET", "POST"), config.methods());
        }

        @Test
        @DisplayName("shouldAcceptValidConfigWithAllFields")
        void shouldAcceptValidConfigWithAllFields() {
            final var rateLimitConfig = EndpointRateLimitConfig.of(100, 60);
            final var samplingConfig = EndpointSamplingConfig.of(0.5);

            var config = new EndpointConfig(
                    "/api/test",
                    Set.of("GET", "POST"),
                    EndpointVisibility.PRIVATE,
                    Optional.of("/rewritten"),
                    true,
                    EndpointType.HTTP,
                    Optional.of(rateLimitConfig),
                    Optional.of(samplingConfig),
                    Optional.empty(),
                    Optional.of("my-audience"));

            assertEquals("/api/test", config.path());
            assertEquals(Set.of("GET", "POST"), config.methods());
            assertEquals(EndpointVisibility.PRIVATE, config.visibility());
            assertEquals("/rewritten", config.pathRewrite().orElseThrow());
            assertTrue(config.authRequired());
            assertEquals(EndpointType.HTTP, config.type());
            assertTrue(config.rateLimitConfig().isPresent());
            assertTrue(config.samplingConfig().isPresent());
            assertEquals("my-audience", config.audience().orElseThrow());
        }
    }

    @Nested
    @DisplayName("convenience constructors")
    class ConvenienceConstructors {

        @Test
        @DisplayName("shouldCreateWithSevenArgConstructor")
        void shouldCreateWithSevenArgConstructor() {
            final var rateLimitConfig = EndpointRateLimitConfig.of(50, 30);
            var config = new EndpointConfig(
                    "/api/test",
                    Set.of("GET"),
                    EndpointVisibility.PUBLIC,
                    Optional.empty(),
                    false,
                    EndpointType.HTTP,
                    Optional.of(rateLimitConfig));

            assertTrue(config.samplingConfig().isEmpty());
            assertTrue(config.audience().isEmpty());
            assertTrue(config.rateLimitConfig().isPresent());
        }

        @Test
        @DisplayName("shouldCreateWithSixArgConstructor")
        void shouldCreateWithSixArgConstructor() {
            var config = new EndpointConfig(
                    "/api/test", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty(), false, EndpointType.HTTP);

            assertTrue(config.rateLimitConfig().isEmpty());
            assertTrue(config.samplingConfig().isEmpty());
            assertTrue(config.audience().isEmpty());
        }

        @Test
        @DisplayName("shouldCreateWithFiveArgConstructor")
        void shouldCreateWithFiveArgConstructor() {
            var config =
                    new EndpointConfig("/api/test", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.empty(), true);

            assertEquals(EndpointType.HTTP, config.type());
            assertTrue(config.authRequired());
        }

        @Test
        @DisplayName("shouldCreateWithFourArgConstructor")
        void shouldCreateWithFourArgConstructor() {
            var config = new EndpointConfig(
                    "/api/test", Set.of("GET"), EndpointVisibility.PUBLIC, Optional.of("/rewritten"));

            assertFalse(config.authRequired());
            assertEquals(EndpointType.HTTP, config.type());
            assertEquals("/rewritten", config.pathRewrite().orElseThrow());
        }
    }

    @Nested
    @DisplayName("static factory methods")
    class StaticFactoryMethods {

        @Test
        @DisplayName("shouldCreatePublicEndpoint")
        void shouldCreatePublicEndpoint() {
            var config = EndpointConfig.publicEndpoint("/api/data", Set.of("GET"));

            assertEquals(EndpointVisibility.PUBLIC, config.visibility());
            assertEquals("/api/data", config.path());
            assertFalse(config.authRequired());
        }

        @Test
        @DisplayName("shouldCreatePrivateEndpoint")
        void shouldCreatePrivateEndpoint() {
            var config = EndpointConfig.privateEndpoint("/api/internal", Set.of("POST"));

            assertEquals(EndpointVisibility.PRIVATE, config.visibility());
            assertEquals("/api/internal", config.path());
        }

        @Test
        @DisplayName("shouldCreatePublicWebSocket")
        void shouldCreatePublicWebSocket() {
            var config = EndpointConfig.publicWebSocket("/ws/events", true);

            assertEquals(EndpointVisibility.PUBLIC, config.visibility());
            assertEquals(EndpointType.WEBSOCKET, config.type());
            assertTrue(config.authRequired());
            assertEquals(Set.of("GET"), config.methods());
        }

        @Test
        @DisplayName("shouldCreatePrivateWebSocket")
        void shouldCreatePrivateWebSocket() {
            var config = EndpointConfig.privateWebSocket("/ws/internal", false);

            assertEquals(EndpointVisibility.PRIVATE, config.visibility());
            assertEquals(EndpointType.WEBSOCKET, config.type());
            assertFalse(config.authRequired());
            assertEquals(Set.of("GET"), config.methods());
        }
    }

    @Nested
    @DisplayName("isWebSocket()")
    class IsWebSocket {

        @Test
        @DisplayName("shouldReturnTrueForWebSocketType")
        void shouldReturnTrueForWebSocketType() {
            var config = EndpointConfig.publicWebSocket("/ws/test", false);

            assertTrue(config.isWebSocket());
        }

        @Test
        @DisplayName("shouldReturnFalseForHttpType")
        void shouldReturnFalseForHttpType() {
            var config = EndpointConfig.publicEndpoint("/api/test", Set.of("GET"));

            assertFalse(config.isWebSocket());
        }
    }
}
