package aussie.core.service.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("GlobPatternMatcher")
class GlobPatternMatcherTest {

    private GlobPatternMatcher matcher;

    @BeforeEach
    void setUp() {
        matcher = new GlobPatternMatcher();
    }

    @Nested
    @DisplayName("Exact matching")
    class ExactMatching {

        @Test
        @DisplayName("should match exact path")
        void exactMatch() {
            assertTrue(matcher.matches("/api/health", "/api/health"));
        }

        @Test
        @DisplayName("should not match different path")
        void noMatch() {
            assertFalse(matcher.matches("/api/health", "/api/users"));
        }
    }

    @Nested
    @DisplayName("Glob wildcards")
    class GlobWildcards {

        @Test
        @DisplayName("should match ** wildcard across path segments")
        void doubleStarWildcard() {
            assertTrue(matcher.matches("/api/**", "/api/users/123"));
        }

        @Test
        @DisplayName("should match ** wildcard at root level")
        void doubleStarAtRoot() {
            assertTrue(matcher.matches("/api/**", "/api/users"));
        }

        @Test
        @DisplayName("should match * wildcard within single segment")
        void singleStarWildcard() {
            assertTrue(matcher.matches("/api/*", "/api/users"));
        }

        @Test
        @DisplayName("should not match * wildcard across segments")
        void singleStarDoesNotCrossSegments() {
            assertFalse(matcher.matches("/api/*", "/api/users/123"));
        }
    }

    @Nested
    @DisplayName("Path normalization")
    class PathNormalization {

        @Test
        @DisplayName("should normalize trailing slash")
        void trailingSlash() {
            assertTrue(matcher.matches("/api/users", "/api/users/"));
        }

        @Test
        @DisplayName("should collapse multiple slashes")
        void multipleSlashes() {
            assertTrue(matcher.matches("/api/users", "/api//users"));
        }

        @Test
        @DisplayName("should throw NullPointerException for null path")
        void nullPath() {
            assertThrows(NullPointerException.class, () -> matcher.matches("/", null));
        }

        @Test
        @DisplayName("should treat empty path as root")
        void emptyPath() {
            assertTrue(matcher.matches("/", ""));
        }

        @Test
        @DisplayName("should not retain attacker-controlled request paths")
        void doesNotRetainRequestPaths() throws IllegalAccessException {
            assertFalse(matcher.matches("/configured/**", "/attacker/one"));
            assertFalse(matcher.matches("/configured/**", "/attacker/two"));

            for (final var field : GlobPatternMatcher.class.getDeclaredFields()) {
                if (Map.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    assertEquals(1, ((Map<?, ?>) field.get(matcher)).size());
                }
            }
        }
    }
}
