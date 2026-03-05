package aussie.adapter.out.storage;

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

import aussie.core.service.routing.ServiceRegistry;

@DisplayName("StorageInitializer")
@ExtendWith(MockitoExtension.class)
class StorageInitializerTest {

    @Mock
    private ServiceRegistry serviceRegistry;

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
        }

        @Test
        @DisplayName("should not throw when initialization fails")
        void shouldNotThrowOnInitializationFailure() {
            when(serviceRegistry.initialize())
                    .thenReturn(Uni.createFrom().failure(new RuntimeException("Storage unavailable")));

            storageInitializer.onStart(new StartupEvent());

            verify(serviceRegistry).initialize();
        }
    }
}
