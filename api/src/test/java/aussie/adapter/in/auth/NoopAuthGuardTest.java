package aussie.adapter.in.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("NoopAuthGuard")
class NoopAuthGuardTest {

    private final StartupEvent event = new StartupEvent();

    private NoopAuthGuard guardWith(boolean noopEnabled, LaunchMode mode) {
        return new NoopAuthGuard() {
            @Override
            boolean isDangerousNoopEnabled() {
                return noopEnabled;
            }

            @Override
            LaunchMode currentLaunchMode() {
                return mode;
            }
        };
    }

    @Nested
    @DisplayName("when dangerous-noop is enabled in production")
    class WhenEnabledInProduction {

        @Test
        @DisplayName("should throw IllegalStateException")
        void shouldThrowInProductionMode() {
            var guard = guardWith(true, LaunchMode.NORMAL);

            var ex = assertThrows(IllegalStateException.class, () -> guard.onStart(event));
            assertTrue(ex.getMessage().contains("not allowed in production mode"));
        }
    }

    @Nested
    @DisplayName("when dangerous-noop is enabled in non-production")
    class WhenEnabledInNonProduction {

        @Test
        @DisplayName("should allow startup in test mode")
        void shouldAllowTestMode() {
            var guard = guardWith(true, LaunchMode.TEST);
            assertDoesNotThrow(() -> guard.onStart(event));
        }

        @Test
        @DisplayName("should allow startup in dev mode")
        void shouldAllowDevMode() {
            var guard = guardWith(true, LaunchMode.DEVELOPMENT);
            assertDoesNotThrow(() -> guard.onStart(event));
        }
    }

    @Nested
    @DisplayName("when dangerous-noop is disabled")
    class WhenDisabled {

        @Test
        @DisplayName("should allow startup regardless of launch mode")
        void shouldAllowStartup() {
            var guard = guardWith(false, LaunchMode.NORMAL);
            assertDoesNotThrow(() -> guard.onStart(event));
        }
    }
}
