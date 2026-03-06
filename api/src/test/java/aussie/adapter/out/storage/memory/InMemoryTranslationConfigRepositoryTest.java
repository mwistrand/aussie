package aussie.adapter.out.storage.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.auth.TranslationConfigSchema;
import aussie.core.model.auth.TranslationConfigVersion;

@DisplayName("InMemoryTranslationConfigRepository")
class InMemoryTranslationConfigRepositoryTest {

    private InMemoryTranslationConfigRepository repository;
    private TranslationConfigSchema testConfig;

    @BeforeEach
    void setUp() {
        repository = new InMemoryTranslationConfigRepository();
        testConfig = new TranslationConfigSchema(
                1,
                List.of(new TranslationConfigSchema.ClaimSource(
                        "roles", "roles", TranslationConfigSchema.ClaimSource.ClaimType.ARRAY)),
                List.of(),
                new TranslationConfigSchema.Mappings(Map.of(), Map.of()),
                null);
    }

    @Nested
    @DisplayName("save and findById")
    class SaveAndFindById {

        @Test
        @DisplayName("should save and retrieve version by id")
        void shouldSaveAndRetrieve() {
            var version = TranslationConfigVersion.create("test-id", 1, testConfig, "user", "comment");

            repository.save(version).await().atMost(Duration.ofSeconds(5));
            var result = repository.findById("test-id").await().atMost(Duration.ofSeconds(5));

            assertTrue(result.isPresent());
            assertEquals("test-id", result.get().id());
            assertEquals(1, result.get().version());
        }

        @Test
        @DisplayName("should return empty for unknown id")
        void shouldReturnEmptyForUnknownId() {
            var result = repository.findById("unknown").await().atMost(Duration.ofSeconds(5));
            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("findByVersion")
    class FindByVersion {

        @Test
        @DisplayName("should find version by number")
        void shouldFindByNumber() {
            var version = TranslationConfigVersion.create("id", 5, testConfig, "user", null);
            repository.save(version).await().atMost(Duration.ofSeconds(5));

            var result = repository.findByVersion(5).await().atMost(Duration.ofSeconds(5));

            assertTrue(result.isPresent());
            assertEquals("id", result.get().id());
        }

        @Test
        @DisplayName("should return empty for unknown version number")
        void shouldReturnEmptyForUnknownVersion() {
            var result = repository.findByVersion(999).await().atMost(Duration.ofSeconds(5));
            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("getNextVersionNumber")
    class GetNextVersionNumber {

        @Test
        @DisplayName("should start at 1")
        void shouldStartAtOne() {
            var next = repository.getNextVersionNumber().await().atMost(Duration.ofSeconds(5));
            assertEquals(1, next);
        }

        @Test
        @DisplayName("should increment atomically")
        void shouldIncrementAtomically() {
            var first = repository.getNextVersionNumber().await().atMost(Duration.ofSeconds(5));
            var second = repository.getNextVersionNumber().await().atMost(Duration.ofSeconds(5));
            var third = repository.getNextVersionNumber().await().atMost(Duration.ofSeconds(5));

            assertEquals(1, first);
            assertEquals(2, second);
            assertEquals(3, third);
        }
    }

    @Nested
    @DisplayName("setActive and getActive")
    class SetActiveAndGetActive {

        @Test
        @DisplayName("should return empty when no active version")
        void shouldReturnEmptyWhenNoActive() {
            var result = repository.getActive().await().atMost(Duration.ofSeconds(5));
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should set and get active version")
        void shouldSetAndGetActive() {
            var version = TranslationConfigVersion.create("active-id", 1, testConfig, "user", null);
            repository.save(version).await().atMost(Duration.ofSeconds(5));
            repository.setActive("active-id").await().atMost(Duration.ofSeconds(5));

            var result = repository.getActive().await().atMost(Duration.ofSeconds(5));

            assertTrue(result.isPresent());
            assertEquals("active-id", result.get().id());
            assertTrue(result.get().active());
        }

        @Test
        @DisplayName("should return false for unknown version id")
        void shouldReturnFalseForUnknownId() {
            var result = repository.setActive("unknown").await().atMost(Duration.ofSeconds(5));
            assertFalse(result);
        }

        @Test
        @DisplayName("should change active version")
        void shouldChangeActiveVersion() {
            var v1 = TranslationConfigVersion.create("v1", 1, testConfig, "user", null);
            var v2 = TranslationConfigVersion.create("v2", 2, testConfig, "user", null);

            repository.save(v1).await().atMost(Duration.ofSeconds(5));
            repository.save(v2).await().atMost(Duration.ofSeconds(5));
            repository.setActive("v1").await().atMost(Duration.ofSeconds(5));

            var active1 = repository.getActive().await().atMost(Duration.ofSeconds(5));
            assertEquals("v1", active1.get().id());

            repository.setActive("v2").await().atMost(Duration.ofSeconds(5));
            var active2 = repository.getActive().await().atMost(Duration.ofSeconds(5));
            assertEquals("v2", active2.get().id());
        }
    }

    @Nested
    @DisplayName("listVersions")
    class ListVersions {

        @Test
        @DisplayName("should return empty list when no versions")
        void shouldReturnEmptyList() {
            var result = repository.listVersions().await().atMost(Duration.ofSeconds(5));
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return versions sorted by version desc")
        void shouldReturnSortedByVersionDesc() {
            repository
                    .save(TranslationConfigVersion.create("a", 1, testConfig, "user", null))
                    .await()
                    .atMost(Duration.ofSeconds(5));
            repository
                    .save(TranslationConfigVersion.create("b", 3, testConfig, "user", null))
                    .await()
                    .atMost(Duration.ofSeconds(5));
            repository
                    .save(TranslationConfigVersion.create("c", 2, testConfig, "user", null))
                    .await()
                    .atMost(Duration.ofSeconds(5));

            var result = repository.listVersions().await().atMost(Duration.ofSeconds(5));

            assertEquals(3, result.size());
            assertEquals(3, result.get(0).version());
            assertEquals(2, result.get(1).version());
            assertEquals(1, result.get(2).version());
        }

        @Test
        @DisplayName("should respect limit and offset")
        void shouldRespectLimitAndOffset() {
            for (int i = 1; i <= 5; i++) {
                repository
                        .save(TranslationConfigVersion.create("v" + i, i, testConfig, "user", null))
                        .await()
                        .atMost(Duration.ofSeconds(5));
            }

            var result = repository.listVersions(2, 1).await().atMost(Duration.ofSeconds(5));

            assertEquals(2, result.size());
            assertEquals(4, result.get(0).version());
            assertEquals(3, result.get(1).version());
        }

        @Test
        @DisplayName("should mark active version")
        void shouldMarkActiveVersion() {
            repository
                    .save(TranslationConfigVersion.create("v1", 1, testConfig, "user", null))
                    .await()
                    .atMost(Duration.ofSeconds(5));
            repository
                    .save(TranslationConfigVersion.create("v2", 2, testConfig, "user", null))
                    .await()
                    .atMost(Duration.ofSeconds(5));
            repository.setActive("v1").await().atMost(Duration.ofSeconds(5));

            var result = repository.listVersions().await().atMost(Duration.ofSeconds(5));

            var v1 = result.stream().filter(v -> v.version() == 1).findFirst().get();
            var v2 = result.stream().filter(v -> v.version() == 2).findFirst().get();

            assertTrue(v1.active());
            assertFalse(v2.active());
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("should delete version")
        void shouldDeleteVersion() {
            var version = TranslationConfigVersion.create("id", 1, testConfig, "user", null);
            repository.save(version).await().atMost(Duration.ofSeconds(5));

            var deleted = repository.delete("id").await().atMost(Duration.ofSeconds(5));

            assertTrue(deleted);
            assertTrue(repository
                    .findById("id")
                    .await()
                    .atMost(Duration.ofSeconds(5))
                    .isEmpty());
        }

        @Test
        @DisplayName("should not delete active version")
        void shouldNotDeleteActive() {
            var version = TranslationConfigVersion.create("id", 1, testConfig, "user", null);
            repository.save(version).await().atMost(Duration.ofSeconds(5));
            repository.setActive("id").await().atMost(Duration.ofSeconds(5));

            var deleted = repository.delete("id").await().atMost(Duration.ofSeconds(5));

            assertFalse(deleted);
            assertTrue(repository
                    .findById("id")
                    .await()
                    .atMost(Duration.ofSeconds(5))
                    .isPresent());
        }

        @Test
        @DisplayName("should return false for unknown id")
        void shouldReturnFalseForUnknown() {
            var deleted = repository.delete("unknown").await().atMost(Duration.ofSeconds(5));
            assertFalse(deleted);
        }

        @Test
        @DisplayName("should remove from version index")
        void shouldRemoveFromVersionIndex() {
            var version = TranslationConfigVersion.create("id", 5, testConfig, "user", null);
            repository.save(version).await().atMost(Duration.ofSeconds(5));
            repository.delete("id").await().atMost(Duration.ofSeconds(5));

            var result = repository.findByVersion(5).await().atMost(Duration.ofSeconds(5));
            assertTrue(result.isEmpty());
        }
    }
}
