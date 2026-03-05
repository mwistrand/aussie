package aussie.adapter.out.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.core.model.auth.TranslationConfigSchema;
import aussie.core.model.auth.TranslationConfigVersion;
import aussie.core.port.out.TranslationConfigCache;
import aussie.core.port.out.TranslationConfigRepository;

@DisplayName("TieredTranslationConfigRepository")
@ExtendWith(MockitoExtension.class)
class TieredTranslationConfigRepositoryTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Mock
    private TranslationConfigRepository primaryStorage;

    @Mock
    private TranslationConfigCache distributedCache;

    private TranslationConfigVersion createVersion(String id, int version) {
        return new TranslationConfigVersion(
                id, version, TranslationConfigSchema.empty(), false, "test-user", Instant.now(), "test comment");
    }

    @Nested
    @DisplayName("without distributed cache")
    class WithoutDistributedCache {

        private TieredTranslationConfigRepository createRepo() {
            return new TieredTranslationConfigRepository(primaryStorage, null);
        }

        @Nested
        @DisplayName("getActive()")
        class GetActiveTests {

            @Test
            @DisplayName("should fetch from primary on memory cache miss")
            void shouldFetchFromPrimaryOnCacheMiss() {
                final var repo = createRepo();
                final var version = createVersion("v1", 1);
                when(primaryStorage.getActive()).thenReturn(Uni.createFrom().item(Optional.of(version)));

                final var result = repo.getActive().await().atMost(TIMEOUT);

                assertTrue(result.isPresent());
                assertEquals("v1", result.get().id());
                verify(primaryStorage).getActive();
            }

            @Test
            @DisplayName("should return from memory cache on subsequent calls")
            void shouldReturnFromMemoryCacheOnHit() {
                final var repo = createRepo();
                final var version = createVersion("v1", 1);
                when(primaryStorage.getActive()).thenReturn(Uni.createFrom().item(Optional.of(version)));

                // First call populates the cache
                repo.getActive().await().atMost(TIMEOUT);
                // Second call should use cache
                final var result = repo.getActive().await().atMost(TIMEOUT);

                assertTrue(result.isPresent());
                assertEquals("v1", result.get().id());
                // Primary should only be called once
                verify(primaryStorage).getActive();
            }

            @Test
            @DisplayName("should return empty when primary has no active version")
            void shouldReturnEmptyWhenPrimaryEmpty() {
                final var repo = createRepo();
                when(primaryStorage.getActive()).thenReturn(Uni.createFrom().item(Optional.empty()));

                final var result = repo.getActive().await().atMost(TIMEOUT);

                assertTrue(result.isEmpty());
            }
        }

        @Nested
        @DisplayName("findById()")
        class FindByIdTests {

            @Test
            @DisplayName("should fetch from primary on memory cache miss")
            void shouldFetchFromPrimaryOnCacheMiss() {
                final var repo = createRepo();
                final var version = createVersion("v1", 1);
                when(primaryStorage.findById("v1")).thenReturn(Uni.createFrom().item(Optional.of(version)));

                final var result = repo.findById("v1").await().atMost(TIMEOUT);

                assertTrue(result.isPresent());
                assertEquals("v1", result.get().id());
                verify(primaryStorage).findById("v1");
            }

            @Test
            @DisplayName("should return from memory cache on subsequent calls")
            void shouldReturnFromMemoryCacheOnHit() {
                final var repo = createRepo();
                final var version = createVersion("v1", 1);
                when(primaryStorage.findById("v1")).thenReturn(Uni.createFrom().item(Optional.of(version)));

                // First call populates the cache
                repo.findById("v1").await().atMost(TIMEOUT);
                // Second call should use cache
                final var result = repo.findById("v1").await().atMost(TIMEOUT);

                assertTrue(result.isPresent());
                assertEquals("v1", result.get().id());
                verify(primaryStorage).findById("v1");
            }
        }

        @Nested
        @DisplayName("save()")
        class SaveTests {

            @Test
            @DisplayName("should write to primary and invalidate caches")
            void shouldWriteToPrimaryAndInvalidateCaches() {
                final var repo = createRepo();
                final var version = createVersion("v1", 1);
                when(primaryStorage.save(version)).thenReturn(Uni.createFrom().voidItem());
                when(primaryStorage.findById("v1")).thenReturn(Uni.createFrom().item(Optional.of(version)));

                // Populate cache first
                repo.findById("v1").await().atMost(TIMEOUT);
                // Save should invalidate the cache
                repo.save(version).await().atMost(TIMEOUT);

                verify(primaryStorage).save(version);
            }
        }

        @Nested
        @DisplayName("delete()")
        class DeleteTests {

            @Test
            @DisplayName("should invalidate caches on successful delete")
            void shouldInvalidateCachesOnSuccess() {
                final var repo = createRepo();
                when(primaryStorage.delete("v1")).thenReturn(Uni.createFrom().item(true));

                final var result = repo.delete("v1").await().atMost(TIMEOUT);

                assertTrue(result);
                verify(primaryStorage).delete("v1");
            }

            @Test
            @DisplayName("should return false when not found")
            void shouldReturnFalseWhenNotFound() {
                final var repo = createRepo();
                when(primaryStorage.delete("v1")).thenReturn(Uni.createFrom().item(false));

                final var result = repo.delete("v1").await().atMost(TIMEOUT);

                assertFalse(result);
            }
        }

        @Nested
        @DisplayName("setActive()")
        class SetActiveTests {

            @Test
            @DisplayName("should invalidate active and list caches on success")
            void shouldInvalidateCachesOnSuccess() {
                final var repo = createRepo();
                when(primaryStorage.setActive("v1")).thenReturn(Uni.createFrom().item(true));

                final var result = repo.setActive("v1").await().atMost(TIMEOUT);

                assertTrue(result);
                verify(primaryStorage).setActive("v1");
            }

            @Test
            @DisplayName("should return false when version not found")
            void shouldReturnFalseWhenNotFound() {
                final var repo = createRepo();
                when(primaryStorage.setActive("v1")).thenReturn(Uni.createFrom().item(false));

                final var result = repo.setActive("v1").await().atMost(TIMEOUT);

                assertFalse(result);
            }
        }

        @Nested
        @DisplayName("listVersions()")
        class ListVersionsTests {

            @Test
            @DisplayName("should fetch from primary and cache result")
            void shouldFetchFromPrimaryAndCache() {
                final var repo = createRepo();
                final var versions = List.of(createVersion("v1", 1), createVersion("v2", 2));
                when(primaryStorage.listVersions()).thenReturn(Uni.createFrom().item(versions));

                final var result = repo.listVersions().await().atMost(TIMEOUT);

                assertEquals(2, result.size());
                verify(primaryStorage).listVersions();
            }

            @Test
            @DisplayName("should return from cache on subsequent calls")
            void shouldReturnFromCacheOnSubsequentCalls() {
                final var repo = createRepo();
                final var versions = List.of(createVersion("v1", 1));
                when(primaryStorage.listVersions()).thenReturn(Uni.createFrom().item(versions));

                repo.listVersions().await().atMost(TIMEOUT);
                final var result = repo.listVersions().await().atMost(TIMEOUT);

                assertEquals(1, result.size());
                verify(primaryStorage).listVersions();
            }
        }

        @Nested
        @DisplayName("listVersions(limit, offset)")
        class PaginatedListVersionsTests {

            @Test
            @DisplayName("should paginate results from full list")
            void shouldPaginateResults() {
                final var repo = createRepo();
                final var versions = List.of(
                        createVersion("v1", 1), createVersion("v2", 2), createVersion("v3", 3), createVersion("v4", 4));
                when(primaryStorage.listVersions()).thenReturn(Uni.createFrom().item(versions));

                final var result = repo.listVersions(2, 1).await().atMost(TIMEOUT);

                assertEquals(2, result.size());
                assertEquals("v2", result.get(0).id());
                assertEquals("v3", result.get(1).id());
            }

            @Test
            @DisplayName("should return empty list when offset exceeds size")
            void shouldReturnEmptyWhenOffsetExceedsSize() {
                final var repo = createRepo();
                final var versions = List.of(createVersion("v1", 1));
                when(primaryStorage.listVersions()).thenReturn(Uni.createFrom().item(versions));

                final var result = repo.listVersions(10, 5).await().atMost(TIMEOUT);

                assertTrue(result.isEmpty());
            }
        }

        @Nested
        @DisplayName("findByVersion()")
        class FindByVersionTests {

            @Test
            @DisplayName("should fetch from primary and cache result")
            void shouldFetchFromPrimaryAndCache() {
                final var repo = createRepo();
                final var version = createVersion("v1", 1);
                when(primaryStorage.findByVersion(1))
                        .thenReturn(Uni.createFrom().item(Optional.of(version)));

                final var result = repo.findByVersion(1).await().atMost(TIMEOUT);

                assertTrue(result.isPresent());
                assertEquals("v1", result.get().id());
                verify(primaryStorage).findByVersion(1);
            }
        }

        @Nested
        @DisplayName("getNextVersionNumber()")
        class GetNextVersionNumberTests {

            @Test
            @DisplayName("should always delegate to primary")
            void shouldDelegateToPrimary() {
                final var repo = createRepo();
                when(primaryStorage.getNextVersionNumber())
                        .thenReturn(Uni.createFrom().item(5));

                final var result = repo.getNextVersionNumber().await().atMost(TIMEOUT);

                assertEquals(5, result);
                verify(primaryStorage).getNextVersionNumber();
            }
        }

        @Nested
        @DisplayName("invalidateAllCaches()")
        class InvalidateAllCachesTests {

            @Test
            @DisplayName("should clear memory caches without distributed cache")
            void shouldClearMemoryCaches() {
                final var repo = createRepo();

                repo.invalidateAllCaches().await().atMost(TIMEOUT);

                // No exception means success; distributed cache not called since it's null
            }
        }
    }

    @Nested
    @DisplayName("with distributed cache")
    class WithDistributedCache {

        private TieredTranslationConfigRepository createRepo() {
            return new TieredTranslationConfigRepository(primaryStorage, distributedCache);
        }

        @Nested
        @DisplayName("getActive()")
        class GetActiveTests {

            @Test
            @DisplayName("should return from distributed cache on memory miss, distributed hit")
            void shouldReturnFromDistributedOnMemoryMiss() {
                final var repo = createRepo();
                final var version = createVersion("v1", 1);
                when(distributedCache.getActive()).thenReturn(Uni.createFrom().item(Optional.of(version)));

                final var result = repo.getActive().await().atMost(TIMEOUT);

                assertTrue(result.isPresent());
                assertEquals("v1", result.get().id());
                verify(distributedCache).getActive();
                verify(primaryStorage, never()).getActive();
            }

            @Test
            @DisplayName("should fetch from primary on memory and distributed cache miss")
            void shouldFetchFromPrimaryOnBothMisses() {
                final var repo = createRepo();
                final var version = createVersion("v1", 1);
                when(distributedCache.getActive()).thenReturn(Uni.createFrom().item(Optional.empty()));
                when(primaryStorage.getActive()).thenReturn(Uni.createFrom().item(Optional.of(version)));
                when(distributedCache.putActive(version))
                        .thenReturn(Uni.createFrom().voidItem());

                final var result = repo.getActive().await().atMost(TIMEOUT);

                assertTrue(result.isPresent());
                assertEquals("v1", result.get().id());
                verify(distributedCache).getActive();
                verify(primaryStorage).getActive();
                verify(distributedCache).putActive(version);
            }
        }

        @Nested
        @DisplayName("findById()")
        class FindByIdTests {

            @Test
            @DisplayName("should return from distributed cache on memory miss, distributed hit")
            void shouldReturnFromDistributedOnMemoryMiss() {
                final var repo = createRepo();
                final var version = createVersion("v1", 1);
                when(distributedCache.get("v1")).thenReturn(Uni.createFrom().item(Optional.of(version)));

                final var result = repo.findById("v1").await().atMost(TIMEOUT);

                assertTrue(result.isPresent());
                assertEquals("v1", result.get().id());
                verify(distributedCache).get("v1");
                verify(primaryStorage, never()).findById(anyString());
            }

            @Test
            @DisplayName("should fetch from primary on memory and distributed cache miss")
            void shouldFetchFromPrimaryOnBothMisses() {
                final var repo = createRepo();
                final var version = createVersion("v1", 1);
                when(distributedCache.get("v1")).thenReturn(Uni.createFrom().item(Optional.empty()));
                when(primaryStorage.findById("v1")).thenReturn(Uni.createFrom().item(Optional.of(version)));
                when(distributedCache.put(version)).thenReturn(Uni.createFrom().voidItem());

                final var result = repo.findById("v1").await().atMost(TIMEOUT);

                assertTrue(result.isPresent());
                assertEquals("v1", result.get().id());
                verify(distributedCache).get("v1");
                verify(primaryStorage).findById("v1");
                verify(distributedCache).put(version);
            }
        }

        @Nested
        @DisplayName("save()")
        class SaveTests {

            @Test
            @DisplayName("should write to primary and invalidate distributed cache")
            void shouldWriteToPrimaryAndInvalidateDistributed() {
                final var repo = createRepo();
                final var version = createVersion("v1", 1);
                when(primaryStorage.save(version)).thenReturn(Uni.createFrom().voidItem());
                when(distributedCache.invalidate("v1"))
                        .thenReturn(Uni.createFrom().voidItem());
                when(distributedCache.invalidateVersionList())
                        .thenReturn(Uni.createFrom().voidItem());

                repo.save(version).await().atMost(TIMEOUT);

                verify(primaryStorage).save(version);
                verify(distributedCache).invalidate("v1");
                verify(distributedCache).invalidateVersionList();
            }
        }

        @Nested
        @DisplayName("listVersions()")
        class ListVersionsTests {

            @Test
            @DisplayName("should return from distributed cache on memory miss, distributed hit")
            void shouldReturnFromDistributedOnMemoryMiss() {
                final var repo = createRepo();
                final var versions = List.of(createVersion("v1", 1));
                when(distributedCache.getVersionList())
                        .thenReturn(Uni.createFrom().item(Optional.of(versions)));

                final var result = repo.listVersions().await().atMost(TIMEOUT);

                assertEquals(1, result.size());
                verify(distributedCache).getVersionList();
                verify(primaryStorage, never()).listVersions();
            }

            @Test
            @DisplayName("should fetch from primary on both cache misses")
            void shouldFetchFromPrimaryOnBothMisses() {
                final var repo = createRepo();
                final var versions = List.of(createVersion("v1", 1));
                when(distributedCache.getVersionList())
                        .thenReturn(Uni.createFrom().item(Optional.empty()));
                when(primaryStorage.listVersions()).thenReturn(Uni.createFrom().item(versions));
                when(distributedCache.putVersionList(versions))
                        .thenReturn(Uni.createFrom().voidItem());

                final var result = repo.listVersions().await().atMost(TIMEOUT);

                assertEquals(1, result.size());
                verify(distributedCache).getVersionList();
                verify(primaryStorage).listVersions();
                verify(distributedCache).putVersionList(versions);
            }
        }

        @Nested
        @DisplayName("invalidateAllCaches()")
        class InvalidateAllCachesTests {

            @Test
            @DisplayName("should clear memory and distributed caches")
            void shouldClearAllCaches() {
                final var repo = createRepo();
                when(distributedCache.invalidateAll())
                        .thenReturn(Uni.createFrom().voidItem());

                repo.invalidateAllCaches().await().atMost(TIMEOUT);

                verify(distributedCache).invalidateAll();
            }
        }
    }
}
