package aussie.adapter.out.storage.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.service.ServiceRegistration;

@DisplayName("InMemoryServiceRegistrationRepository")
class InMemoryServiceRegistrationRepositoryTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private InMemoryServiceRegistrationRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryServiceRegistrationRepository();
    }

    private ServiceRegistration createService(String serviceId) {
        return ServiceRegistration.builder(serviceId)
                .baseUrl("http://localhost:8080")
                .build();
    }

    @Nested
    @DisplayName("save()")
    class SaveTests {

        @Test
        @DisplayName("Should advance generation only for applied writes")
        void shouldAdvanceGenerationOnlyForAppliedWrites() {
            var version1 = createService("test-service");

            assertEquals(0L, repository.currentGeneration().await().atMost(TIMEOUT));
            assertTrue(
                    repository.createIfAbsent(version1).await().atMost(TIMEOUT).applied());
            assertFalse(
                    repository.createIfAbsent(version1).await().atMost(TIMEOUT).applied());
            assertEquals(1L, repository.currentGeneration().await().atMost(TIMEOUT));
            assertTrue(repository
                    .deleteIfVersion("test-service", 1)
                    .await()
                    .atMost(TIMEOUT)
                    .applied());
            assertEquals(2L, repository.currentGeneration().await().atMost(TIMEOUT));
        }

        @Test
        @DisplayName("Should apply only matching conditional writes")
        void shouldApplyOnlyMatchingConditionalWrites() {
            var version1 = createService("test-service");
            var version2 = version1.withIncrementedVersion();

            assertTrue(
                    repository.createIfAbsent(version1).await().atMost(TIMEOUT).applied());
            assertFalse(
                    repository.createIfAbsent(version1).await().atMost(TIMEOUT).applied());
            assertFalse(repository
                    .replaceIfVersion(version2, 2)
                    .await()
                    .atMost(TIMEOUT)
                    .applied());
            assertTrue(repository
                    .replaceIfVersion(version2, 1)
                    .await()
                    .atMost(TIMEOUT)
                    .applied());
            assertFalse(repository
                    .deleteIfVersion("test-service", 1)
                    .await()
                    .atMost(TIMEOUT)
                    .applied());
            assertTrue(repository
                    .deleteIfVersion("test-service", 2)
                    .await()
                    .atMost(TIMEOUT)
                    .applied());
        }

        @Test
        @DisplayName("Should allow exactly one concurrent replacement")
        void shouldAllowExactlyOneConcurrentReplacement() {
            var version1 = createService("test-service");
            var version2 = version1.withIncrementedVersion();
            repository.save(version1).await().atMost(TIMEOUT);

            try (var executor = Executors.newFixedThreadPool(2)) {
                var first = CompletableFuture.supplyAsync(
                        () -> repository.replaceIfVersion(version2, 1).await().atMost(TIMEOUT), executor);
                var second = CompletableFuture.supplyAsync(
                        () -> repository.replaceIfVersion(version2, 1).await().atMost(TIMEOUT), executor);

                assertEquals(
                        1,
                        List.of(first.join(), second.join()).stream()
                                .filter(result -> result.applied())
                                .count());
            }
        }

        @Test
        @DisplayName("Should save a new registration")
        void shouldSaveNewRegistration() {
            var service = createService("test-service");

            repository.save(service).await().atMost(TIMEOUT);

            var result = repository.findById("test-service").await().atMost(TIMEOUT);
            assertTrue(result.isPresent());
            assertEquals("test-service", result.get().serviceId());
        }

        @Test
        @DisplayName("Should overwrite existing registration")
        void shouldOverwriteExistingRegistration() {
            var service1 = ServiceRegistration.builder("test-service")
                    .baseUrl("http://old-url:8080")
                    .build();
            var service2 = ServiceRegistration.builder("test-service")
                    .baseUrl("http://new-url:8080")
                    .build();

            repository.save(service1).await().atMost(TIMEOUT);
            repository.save(service2).await().atMost(TIMEOUT);

            var result = repository.findById("test-service").await().atMost(TIMEOUT);
            assertTrue(result.isPresent());
            assertEquals("http://new-url:8080", result.get().baseUrl().toString());
        }
    }

    @Nested
    @DisplayName("findById()")
    class FindByIdTests {

        @Test
        @DisplayName("Should return empty for non-existent service")
        void shouldReturnEmptyForNonExistent() {
            var result = repository.findById("non-existent").await().atMost(TIMEOUT);

            assertFalse(result.isPresent());
        }

        @Test
        @DisplayName("Should return existing service")
        void shouldReturnExistingService() {
            var service = createService("my-service");
            repository.save(service).await().atMost(TIMEOUT);

            var result = repository.findById("my-service").await().atMost(TIMEOUT);

            assertTrue(result.isPresent());
            assertEquals("my-service", result.get().serviceId());
        }
    }

    @Nested
    @DisplayName("delete()")
    class DeleteTests {

        @Test
        @DisplayName("Should return true when deleting existing service")
        void shouldReturnTrueWhenDeletingExisting() {
            var service = createService("to-delete");
            repository.save(service).await().atMost(TIMEOUT);

            var result = repository.delete("to-delete").await().atMost(TIMEOUT);

            assertTrue(result);
            assertFalse(repository.findById("to-delete").await().atMost(TIMEOUT).isPresent());
        }

        @Test
        @DisplayName("Should return false when deleting non-existent service")
        void shouldReturnFalseWhenDeletingNonExistent() {
            var result = repository.delete("non-existent").await().atMost(TIMEOUT);

            assertFalse(result);
        }
    }

    @Nested
    @DisplayName("findAll()")
    class FindAllTests {

        @Test
        @DisplayName("Should return empty list when no services")
        void shouldReturnEmptyListWhenNoServices() {
            var result = repository.findAll().await().atMost(TIMEOUT);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should return all saved services")
        void shouldReturnAllSavedServices() {
            repository.save(createService("service-1")).await().atMost(TIMEOUT);
            repository.save(createService("service-2")).await().atMost(TIMEOUT);
            repository.save(createService("service-3")).await().atMost(TIMEOUT);

            var result = repository.findAll().await().atMost(TIMEOUT);

            assertEquals(3, result.size());
            var ids = result.stream().map(ServiceRegistration::serviceId).toList();
            assertTrue(ids.containsAll(List.of("service-1", "service-2", "service-3")));
        }
    }

    @Nested
    @DisplayName("exists()")
    class ExistsTests {

        @Test
        @DisplayName("Should return true for existing service")
        void shouldReturnTrueForExisting() {
            repository.save(createService("exists")).await().atMost(TIMEOUT);

            var result = repository.exists("exists").await().atMost(TIMEOUT);

            assertTrue(result);
        }

        @Test
        @DisplayName("Should return false for non-existent service")
        void shouldReturnFalseForNonExistent() {
            var result = repository.exists("non-existent").await().atMost(TIMEOUT);

            assertFalse(result);
        }
    }

    @Nested
    @DisplayName("count()")
    class CountTests {

        @Test
        @DisplayName("Should return 0 when empty")
        void shouldReturnZeroWhenEmpty() {
            var result = repository.count().await().atMost(TIMEOUT);

            assertEquals(0L, result);
        }

        @Test
        @DisplayName("Should return correct count")
        void shouldReturnCorrectCount() {
            repository.save(createService("service-1")).await().atMost(TIMEOUT);
            repository.save(createService("service-2")).await().atMost(TIMEOUT);

            var result = repository.count().await().atMost(TIMEOUT);

            assertEquals(2L, result);
        }

        @Test
        @DisplayName("Should update count after delete")
        void shouldUpdateCountAfterDelete() {
            repository.save(createService("service-1")).await().atMost(TIMEOUT);
            repository.save(createService("service-2")).await().atMost(TIMEOUT);
            repository.delete("service-1").await().atMost(TIMEOUT);

            var result = repository.count().await().atMost(TIMEOUT);

            assertEquals(1L, result);
        }
    }
}
