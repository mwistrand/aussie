package aussie.adapter.out.storage;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.core.service.lifecycle.StartupState;
import aussie.core.service.routing.ServiceRegistry;

@DisplayName("StorageInitializer")
@ExtendWith(MockitoExtension.class)
class StorageInitializerTest {

    @Mock
    private ServiceRegistry serviceRegistry;

    @Mock
    private StartupState startupState;

    @InjectMocks
    private StorageInitializer storageInitializer;

    @Nested
    @DisplayName("onStart()")
    class OnStartTests {

        @Test
        @DisplayName("should call serviceRegistry.initialize() on startup")
        void shouldInitializeServiceRegistry() {
            when(serviceRegistry.initialize()).thenReturn(Uni.createFrom().voidItem());

            storageInitializer.onStart(new StartupEvent());

            verify(serviceRegistry).initialize();
            verify(startupState).complete(StartupState.Phase.DEPENDENCIES_CONNECTED);
            verify(startupState).complete(StartupState.Phase.SNAPSHOT_LOADED);
        }

        @Test
        @DisplayName("should fail startup when initialization fails")
        void shouldFailStartupOnInitializationFailure() {
            when(serviceRegistry.initialize())
                    .thenReturn(Uni.createFrom().failure(new RuntimeException("Storage unavailable")));

            assertThrows(IllegalStateException.class, () -> storageInitializer.onStart(new StartupEvent()));

            verify(serviceRegistry).initialize();
            verify(startupState).fail(StartupState.Failure.ROUTE_SNAPSHOT_UNAVAILABLE);
        }
    }
}
