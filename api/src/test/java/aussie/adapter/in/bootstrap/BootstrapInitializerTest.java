package aussie.adapter.in.bootstrap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.core.config.BootstrapConfig;
import aussie.core.model.common.BootstrapResult;
import aussie.core.port.in.BootstrapManagement;
import aussie.core.port.in.BootstrapManagement.BootstrapException;

@ExtendWith(MockitoExtension.class)
@DisplayName("BootstrapInitializer")
class BootstrapInitializerTest {

    @Mock
    private BootstrapManagement bootstrapService;

    @Mock
    private BootstrapConfig config;

    @Mock
    private StartupEvent startupEvent;

    private BootstrapInitializer initializer;

    @BeforeEach
    void setUp() {
        initializer = new BootstrapInitializer(bootstrapService, config);
    }

    @Nested
    @DisplayName("Bootstrap disabled")
    class BootstrapDisabledTests {

        @Test
        @DisplayName("Should skip bootstrap when disabled")
        void shouldSkipWhenDisabled() {
            when(config.enabled()).thenReturn(false);

            assertDoesNotThrow(() -> initializer.onStart(startupEvent));

            verifyNoInteractions(bootstrapService);
        }
    }

    @Nested
    @DisplayName("Bootstrap enabled but no key")
    class NoKeyTests {

        @Test
        @DisplayName("Should throw when key is empty optional")
        void shouldThrowWhenKeyIsEmptyOptional() {
            when(config.enabled()).thenReturn(true);
            when(config.key()).thenReturn(Optional.empty());

            var ex = assertThrows(BootstrapException.class, () -> initializer.onStart(startupEvent));
            assertEquals("Bootstrap is enabled but no key provided. Set AUSSIE_BOOTSTRAP_KEY.", ex.getMessage());
        }

        @Test
        @DisplayName("Should throw when key is blank")
        void shouldThrowWhenKeyIsBlank() {
            when(config.enabled()).thenReturn(true);
            when(config.key()).thenReturn(Optional.of("   "));

            var ex = assertThrows(BootstrapException.class, () -> initializer.onStart(startupEvent));
            assertEquals("Bootstrap is enabled but no key provided. Set AUSSIE_BOOTSTRAP_KEY.", ex.getMessage());
        }
    }

    @Nested
    @DisplayName("Bootstrap skipped because admin keys exist")
    class SkippedTests {

        @Test
        @DisplayName("Should skip when shouldBootstrap returns false")
        void shouldSkipWhenShouldBootstrapReturnsFalse() {
            when(config.enabled()).thenReturn(true);
            when(config.key()).thenReturn(Optional.of("a-valid-key-that-is-long-enough-for-bootstrap"));
            when(config.recoveryMode()).thenReturn(false);
            when(bootstrapService.shouldBootstrap()).thenReturn(Uni.createFrom().item(false));

            assertDoesNotThrow(() -> initializer.onStart(startupEvent));

            verify(bootstrapService, never()).bootstrap();
        }
    }

    @Nested
    @DisplayName("Successful bootstrap")
    class SuccessTests {

        @Test
        @DisplayName("Should bootstrap successfully with standard result")
        void shouldBootstrapSuccessfully() {
            when(config.enabled()).thenReturn(true);
            when(config.key()).thenReturn(Optional.of("a-valid-key-that-is-long-enough-for-bootstrap"));
            when(bootstrapService.shouldBootstrap()).thenReturn(Uni.createFrom().item(true));

            final var result = BootstrapResult.standard("key-123", Instant.now().plusSeconds(86400));
            when(bootstrapService.bootstrap()).thenReturn(Uni.createFrom().item(result));

            assertDoesNotThrow(() -> initializer.onStart(startupEvent));

            verify(bootstrapService).bootstrap();
        }

        @Test
        @DisplayName("Should bootstrap successfully with recovery result")
        void shouldBootstrapSuccessfullyInRecoveryMode() {
            when(config.enabled()).thenReturn(true);
            when(config.key()).thenReturn(Optional.of("a-valid-key-that-is-long-enough-for-bootstrap"));
            when(bootstrapService.shouldBootstrap()).thenReturn(Uni.createFrom().item(true));

            final var result =
                    BootstrapResult.recovery("recovery-key-456", Instant.now().plusSeconds(86400));
            when(bootstrapService.bootstrap()).thenReturn(Uni.createFrom().item(result));

            assertDoesNotThrow(() -> initializer.onStart(startupEvent));

            verify(bootstrapService).bootstrap();
        }
    }

    @Nested
    @DisplayName("Bootstrap failure")
    class FailureTests {

        @Test
        @DisplayName("Should rethrow BootstrapException")
        void shouldRethrowBootstrapException() {
            when(config.enabled()).thenReturn(true);
            when(config.key()).thenReturn(Optional.of("a-valid-key-that-is-long-enough-for-bootstrap"));
            when(bootstrapService.shouldBootstrap()).thenReturn(Uni.createFrom().item(true));
            when(bootstrapService.bootstrap())
                    .thenReturn(Uni.createFrom().failure(new BootstrapException("Key too short")));

            var ex = assertThrows(BootstrapException.class, () -> initializer.onStart(startupEvent));
            assertEquals("Key too short", ex.getMessage());
        }

        @Test
        @DisplayName("Should wrap unexpected exception in BootstrapException")
        void shouldWrapUnexpectedException() {
            when(config.enabled()).thenReturn(true);
            when(config.key()).thenReturn(Optional.of("a-valid-key-that-is-long-enough-for-bootstrap"));
            when(bootstrapService.shouldBootstrap()).thenReturn(Uni.createFrom().item(true));
            when(bootstrapService.bootstrap())
                    .thenReturn(Uni.createFrom().failure(new RuntimeException("DB connection failed")));

            var ex = assertThrows(BootstrapException.class, () -> initializer.onStart(startupEvent));
            assertEquals("Bootstrap failed unexpectedly", ex.getMessage());
        }
    }
}
