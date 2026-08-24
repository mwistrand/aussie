package aussie.adapter.out.storage.memory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("InMemoryPkceStorageProvider")
class InMemoryPkceStorageProviderTest {

    private InMemoryPkceStorageProvider provider;

    @BeforeEach
    void setUp() {
        provider = new InMemoryPkceStorageProvider();
    }

    @AfterEach
    void tearDown() {
        provider.close();
    }

    @Test
    @DisplayName("should close idempotently")
    void shouldCloseIdempotently() {
        provider.createRepository();

        assertDoesNotThrow(provider::close);
        assertDoesNotThrow(provider::close);
    }

    @Nested
    @DisplayName("name()")
    class NameTests {

        @Test
        @DisplayName("should return memory")
        void shouldReturnMemory() {
            assertEquals("memory", provider.name());
        }
    }

    @Nested
    @DisplayName("priority()")
    class PriorityTests {

        @Test
        @DisplayName("should return 0 as lowest priority fallback")
        void shouldReturnZeroAsLowestPriorityFallback() {
            assertEquals(0, provider.priority());
        }
    }

    @Nested
    @DisplayName("isAvailable()")
    class IsAvailableTests {

        @Test
        @DisplayName("should always return true")
        void shouldAlwaysReturnTrue() {
            assertTrue(provider.isAvailable());
        }
    }

    @Nested
    @DisplayName("createRepository()")
    class CreateRepositoryTests {

        @Test
        @DisplayName("should return InMemoryPkceChallengeRepository")
        void shouldReturnInMemoryPkceChallengeRepository() {
            var repository = provider.createRepository();

            assertNotNull(repository);
            assertInstanceOf(InMemoryPkceChallengeRepository.class, repository);
        }

        @Test
        @DisplayName("should return same instance on subsequent calls")
        void shouldReturnSameInstanceOnSubsequentCalls() {
            var repo1 = provider.createRepository();
            var repo2 = provider.createRepository();

            assertSame(repo1, repo2);
        }
    }

    @Nested
    @DisplayName("healthCheck()")
    class HealthCheckTests {

        @Test
        @DisplayName("should return health check response")
        void shouldReturnHealthCheckResponse() {
            var health = provider.healthCheck();

            assertTrue(health.isPresent());
            assertEquals("pkce-storage-memory", health.get().getName());
            assertEquals(
                    org.eclipse.microprofile.health.HealthCheckResponse.Status.UP,
                    health.get().getStatus());
        }

        @Test
        @DisplayName("should report zero challenges when repository not yet created")
        void shouldReportZeroChallengesWhenRepositoryNotYetCreated() {
            var health = provider.healthCheck();

            assertTrue(health.isPresent());
            var data = health.get().getData().orElseThrow();
            assertEquals("in-memory", data.get("type"));
            assertEquals(0L, data.get("challenges"));
        }

        @Test
        @DisplayName("should report challenge count after repository is created")
        void shouldReportChallengeCountAfterRepositoryIsCreated() {
            // Create the repository first
            provider.createRepository();

            var health = provider.healthCheck();

            assertTrue(health.isPresent());
            var data = health.get().getData().orElseThrow();
            assertEquals(0L, data.get("challenges"));
        }
    }
}
