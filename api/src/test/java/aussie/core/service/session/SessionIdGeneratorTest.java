package aussie.core.service.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("SessionIdGenerator")
class SessionIdGeneratorTest {

    private SessionIdGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new SessionIdGenerator();
    }

    @Nested
    @DisplayName("generate")
    class Generate {

        @Test
        @DisplayName("should generate ID with length 43")
        void idLength() {
            var id = generator.generate();

            assertEquals(43, id.length());
        }

        @Test
        @DisplayName("should generate URL-safe Base64 characters only")
        void urlSafeBase64Chars() {
            var id = generator.generate();

            // URL-safe Base64 without padding uses: A-Z, a-z, 0-9, -, _
            var pattern = Pattern.compile("^[A-Za-z0-9_-]+$");
            assertTrue(
                    pattern.matcher(id).matches(), "Session ID should contain only URL-safe Base64 characters: " + id);
        }

        @Test
        @DisplayName("should generate unique IDs across calls")
        void uniqueness() {
            var ids = new HashSet<String>();
            final var count = 100;

            for (var i = 0; i < count; i++) {
                ids.add(generator.generate());
            }

            assertEquals(count, ids.size(), "All generated IDs should be unique");
        }

        @Test
        @DisplayName("should generate different IDs on consecutive calls")
        void consecutiveCallsDiffer() {
            var id1 = generator.generate();
            var id2 = generator.generate();

            assertNotEquals(id1, id2);
        }
    }
}
