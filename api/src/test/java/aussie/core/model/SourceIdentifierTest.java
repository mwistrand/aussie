package aussie.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("SourceIdentifier")
class SourceIdentifierTest {

    @Nested
    @DisplayName("Construction")
    class ConstructionTests {

        @Test
        @DisplayName("Should create with all fields")
        void shouldCreateWithAllFields() {
            var source = new SourceIdentifier(
                    "192.168.1.100", Optional.of("api.example.com"), Optional.of("203.0.113.50, 192.168.1.1"));

            assertEquals("192.168.1.100", source.ipAddress());
            assertTrue(source.host().isPresent());
            assertEquals("api.example.com", source.host().get());
            assertTrue(source.forwardedFor().isPresent());
        }

        @Test
        @DisplayName("Should default ipAddress to 'unknown' when null")
        void shouldDefaultIpAddressWhenNull() {
            var source = new SourceIdentifier(null, Optional.empty(), Optional.empty());

            assertEquals("unknown", source.ipAddress());
        }

        @Test
        @DisplayName("Should default host to empty when null")
        void shouldDefaultHostWhenNull() {
            var source = new SourceIdentifier("192.168.1.1", null, Optional.empty());

            assertFalse(source.host().isPresent());
        }

        @Test
        @DisplayName("Should default forwardedFor to empty when null")
        void shouldDefaultForwardedForWhenNull() {
            var source = new SourceIdentifier("192.168.1.1", Optional.empty(), null);

            assertFalse(source.forwardedFor().isPresent());
        }
    }

    @Nested
    @DisplayName("Factory Methods")
    class FactoryMethodTests {

        @Test
        @DisplayName("Should create with IP only")
        void shouldCreateWithIpOnly() {
            var source = SourceIdentifier.of("192.168.1.100");

            assertEquals("192.168.1.100", source.ipAddress());
            assertFalse(source.host().isPresent());
            assertFalse(source.forwardedFor().isPresent());
        }

        @Test
        @DisplayName("Should create with IP and host")
        void shouldCreateWithIpAndHost() {
            var source = SourceIdentifier.of("192.168.1.100", "api.example.com");

            assertEquals("192.168.1.100", source.ipAddress());
            assertTrue(source.host().isPresent());
            assertEquals("api.example.com", source.host().get());
            assertFalse(source.forwardedFor().isPresent());
        }

        @Test
        @DisplayName("Should handle null host in factory method")
        void shouldHandleNullHostInFactory() {
            var source = SourceIdentifier.of("192.168.1.100", null);

            assertEquals("192.168.1.100", source.ipAddress());
            assertFalse(source.host().isPresent());
        }
    }
}
