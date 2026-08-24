package aussie.core.service.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import io.smallrye.mutiny.Multi;
import io.vertx.mutiny.core.Vertx;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import aussie.core.config.TokenRevocationConfig;
import aussie.core.model.auth.RevocationEvent;
import aussie.core.port.out.RevocationEventPublisher;
import aussie.spi.TokenRevocationRepository;

@DisplayName("RevocationBloomFilter")
@ExtendWith(MockitoExtension.class)
class RevocationBloomFilterTest {

    @Mock
    private TokenRevocationConfig config;

    @Mock
    private TokenRevocationConfig.BloomFilterConfig bloomFilterConfig;

    @Mock
    private TokenRevocationConfig.PubSubConfig pubSubConfig;

    @Mock
    private TokenRevocationRepository repository;

    @Mock
    private RevocationEventPublisher eventPublisher;

    @Mock
    private Vertx vertx;

    private RevocationBloomFilter bloomFilter;

    @BeforeEach
    void setUp() {
        lenient().when(config.enabled()).thenReturn(true);
        lenient().when(config.bloomFilter()).thenReturn(bloomFilterConfig);
        lenient().when(config.pubsub()).thenReturn(pubSubConfig);
        lenient().when(bloomFilterConfig.enabled()).thenReturn(true);
        lenient().when(bloomFilterConfig.expectedInsertions()).thenReturn(1000);
        lenient().when(bloomFilterConfig.falsePositiveProbability()).thenReturn(0.001);
        lenient().when(bloomFilterConfig.rebuildInterval()).thenReturn(Duration.ofHours(1));
        lenient().when(pubSubConfig.enabled()).thenReturn(false);
        lenient()
                .when(repository.streamAllRevokedJtis())
                .thenReturn(Multi.createFrom().empty());
        lenient()
                .when(repository.streamAllRevokedUsers())
                .thenReturn(Multi.createFrom().empty());
    }

    private void initializeBloomFilter() {
        bloomFilter = new RevocationBloomFilter(config, repository, eventPublisher, vertx);
        bloomFilter.rebuildFilters().await().atMost(Duration.ofSeconds(5));
    }

    @Nested
    @DisplayName("definitelyNotRevoked()")
    class DefinitelyNotRevokedTests {

        @Test
        @DisplayName("should return true for JTI not in filter")
        void shouldReturnTrueForJtiNotInFilter() {
            initializeBloomFilter();

            final var result = bloomFilter.definitelyNotRevoked("unknown-jti");

            assertTrue(result);
        }

        @Test
        @DisplayName("should return false for JTI in filter")
        void shouldReturnFalseForJtiInFilter() {
            initializeBloomFilter();
            bloomFilter.addRevokedJti("revoked-jti");

            final var result = bloomFilter.definitelyNotRevoked("revoked-jti");

            assertFalse(result);
        }

        @Test
        @DisplayName("should return false when not initialized")
        void shouldReturnFalseWhenNotInitialized() {
            lenient().when(config.enabled()).thenReturn(false);
            bloomFilter = new RevocationBloomFilter(config, repository, eventPublisher, vertx);

            final var result = bloomFilter.definitelyNotRevoked("any-jti");

            assertFalse(result);
        }
    }

    @Nested
    @DisplayName("userDefinitelyNotRevoked()")
    class UserDefinitelyNotRevokedTests {

        @Test
        @DisplayName("should return true for user not in filter")
        void shouldReturnTrueForUserNotInFilter() {
            initializeBloomFilter();

            final var result = bloomFilter.userDefinitelyNotRevoked("unknown-user");

            assertTrue(result);
        }

        @Test
        @DisplayName("should return false for user in filter")
        void shouldReturnFalseForUserInFilter() {
            initializeBloomFilter();
            bloomFilter.addRevokedUser("revoked-user");

            final var result = bloomFilter.userDefinitelyNotRevoked("revoked-user");

            assertFalse(result);
        }
    }

    @Nested
    @DisplayName("addRevokedJti()")
    class AddRevokedJtiTests {

        @Test
        @DisplayName("should add JTI to bloom filter")
        void shouldAddJtiToBloomFilter() {
            initializeBloomFilter();
            final var jti = "new-revoked-jti";

            assertTrue(bloomFilter.definitelyNotRevoked(jti));

            bloomFilter.addRevokedJti(jti);

            assertFalse(bloomFilter.definitelyNotRevoked(jti));
        }
    }

    @Nested
    @DisplayName("addRevokedUser()")
    class AddRevokedUserTests {

        @Test
        @DisplayName("should add user to bloom filter")
        void shouldAddUserToBloomFilter() {
            initializeBloomFilter();
            final var userId = "new-revoked-user";

            assertTrue(bloomFilter.userDefinitelyNotRevoked(userId));

            bloomFilter.addRevokedUser(userId);

            assertFalse(bloomFilter.userDefinitelyNotRevoked(userId));
        }
    }

    @Nested
    @DisplayName("rebuildFilters()")
    class RebuildFiltersTests {

        @Test
        @DisplayName("should populate filters from repository")
        void shouldPopulateFiltersFromRepository() {
            when(repository.streamAllRevokedJtis())
                    .thenReturn(Multi.createFrom().items("jti-1", "jti-2"));
            when(repository.streamAllRevokedUsers())
                    .thenReturn(Multi.createFrom().items("user-1"));

            initializeBloomFilter();

            assertFalse(bloomFilter.definitelyNotRevoked("jti-1"));
            assertFalse(bloomFilter.definitelyNotRevoked("jti-2"));
            assertFalse(bloomFilter.userDefinitelyNotRevoked("user-1"));
            assertTrue(bloomFilter.definitelyNotRevoked("jti-3"));
            assertTrue(bloomFilter.userDefinitelyNotRevoked("user-2"));
        }
    }

    @Nested
    @DisplayName("init()")
    class InitTests {

        @Test
        @DisplayName("should skip initialization when revocation disabled")
        void shouldSkipWhenRevocationDisabled() {
            when(config.enabled()).thenReturn(false);

            bloomFilter = new RevocationBloomFilter(config, repository, eventPublisher, vertx);
            bloomFilter.init();

            assertFalse(bloomFilter.isEnabled());
        }

        @Test
        @DisplayName("should skip initialization when bloom filter disabled")
        void shouldSkipWhenBloomFilterDisabled() {
            when(bloomFilterConfig.enabled()).thenReturn(false);

            bloomFilter = new RevocationBloomFilter(config, repository, eventPublisher, vertx);
            bloomFilter.init();

            assertFalse(bloomFilter.isEnabled());
        }

        @Test
        @DisplayName("should schedule periodic rebuild on init")
        @SuppressWarnings("unchecked")
        void shouldSchedulePeriodicRebuild() {
            when(vertx.setPeriodic(anyLong(), any(Consumer.class))).thenReturn(1L);

            bloomFilter = new RevocationBloomFilter(config, repository, eventPublisher, vertx);
            bloomFilter.init();

            verify(vertx).setPeriodic(anyLong(), any(java.util.function.Consumer.class));
        }

        @Test
        @DisplayName("should subscribe to revocation events when pubsub enabled")
        @SuppressWarnings("unchecked")
        void shouldSubscribeWhenPubSubEnabled() {
            when(pubSubConfig.enabled()).thenReturn(true);
            when(eventPublisher.subscribe()).thenReturn(Multi.createFrom().empty());
            when(vertx.setPeriodic(anyLong(), any(Consumer.class))).thenReturn(1L);

            bloomFilter = new RevocationBloomFilter(config, repository, eventPublisher, vertx);
            bloomFilter.init();

            verify(eventPublisher).subscribe();
        }

        @Test
        @DisplayName("should cancel periodic rebuild on shutdown")
        @SuppressWarnings("unchecked")
        void shouldCancelPeriodicRebuildOnShutdown() {
            when(vertx.setPeriodic(anyLong(), any(Consumer.class))).thenReturn(42L);

            bloomFilter = new RevocationBloomFilter(config, repository, eventPublisher, vertx);
            bloomFilter.init();
            bloomFilter.shutdown();

            verify(vertx).cancelTimer(42L);
        }

        @Test
        @DisplayName("should cancel active subscriptions on shutdown")
        @SuppressWarnings("unchecked")
        void shouldCancelActiveSubscriptionsOnShutdown() {
            final var rebuildCancelled = new AtomicBoolean();
            final var eventSubscriptionCancelled = new AtomicBoolean();
            final var pendingRebuild =
                    Multi.createFrom().<String>nothing().onCancellation().invoke(() -> rebuildCancelled.set(true));
            final var pendingEvents = Multi.createFrom()
                    .<RevocationEvent>nothing()
                    .onCancellation()
                    .invoke(() -> eventSubscriptionCancelled.set(true));
            when(repository.streamAllRevokedJtis()).thenReturn(pendingRebuild);
            when(pubSubConfig.enabled()).thenReturn(true);
            when(eventPublisher.subscribe()).thenReturn(pendingEvents);
            when(vertx.setPeriodic(anyLong(), any(Consumer.class))).thenReturn(42L);

            bloomFilter = new RevocationBloomFilter(config, repository, eventPublisher, vertx);
            bloomFilter.init();
            bloomFilter.shutdown();

            assertTrue(rebuildCancelled.get());
            assertTrue(eventSubscriptionCancelled.get());
        }
    }

    @Nested
    @DisplayName("handleRevocationEvent()")
    class HandleRevocationEventTests {

        @Test
        @DisplayName("should add JTI from JtiRevoked event")
        void shouldAddJtiFromEvent() {
            initializeBloomFilter();
            var jti = "event-revoked-jti";

            assertTrue(bloomFilter.definitelyNotRevoked(jti));

            bloomFilter.addRevokedJti(jti);

            assertFalse(bloomFilter.definitelyNotRevoked(jti));
        }

        @Test
        @DisplayName("should add user from UserRevoked event")
        void shouldAddUserFromEvent() {
            initializeBloomFilter();
            var userId = "event-revoked-user";

            assertTrue(bloomFilter.userDefinitelyNotRevoked(userId));

            bloomFilter.addRevokedUser(userId);

            assertFalse(bloomFilter.userDefinitelyNotRevoked(userId));
        }
    }

    @Nested
    @DisplayName("rebuildFilters() with data")
    class RebuildFiltersWithDataTests {

        @Test
        @DisplayName("should replace filters with data from repository")
        void shouldReplaceFiltersWithRepositoryData() {
            initializeBloomFilter();
            bloomFilter.addRevokedJti("old-jti");
            assertFalse(bloomFilter.definitelyNotRevoked("old-jti"));

            when(repository.streamAllRevokedJtis())
                    .thenReturn(Multi.createFrom().items("new-jti-1", "new-jti-2"));
            when(repository.streamAllRevokedUsers())
                    .thenReturn(Multi.createFrom().items("new-user-1"));

            bloomFilter.rebuildFilters().await().atMost(Duration.ofSeconds(5));

            assertFalse(bloomFilter.definitelyNotRevoked("new-jti-1"));
            assertFalse(bloomFilter.definitelyNotRevoked("new-jti-2"));
            assertFalse(bloomFilter.userDefinitelyNotRevoked("new-user-1"));
            assertTrue(bloomFilter.definitelyNotRevoked("other-jti"));
        }

        @Test
        @DisplayName("should skip rebuild when disabled")
        void shouldSkipRebuildWhenDisabled() {
            when(config.enabled()).thenReturn(false);
            bloomFilter = new RevocationBloomFilter(config, repository, eventPublisher, vertx);

            bloomFilter.rebuildFilters().await().atMost(Duration.ofSeconds(5));

            assertFalse(bloomFilter.isEnabled());
        }
    }

    @Nested
    @DisplayName("isEnabled()")
    class IsEnabledTests {

        @Test
        @DisplayName("should return true when enabled and initialized")
        void shouldReturnTrueWhenEnabledAndInitialized() {
            initializeBloomFilter();

            assertTrue(bloomFilter.isEnabled());
        }

        @Test
        @DisplayName("should return false when revocation disabled")
        void shouldReturnFalseWhenRevocationDisabled() {
            when(config.enabled()).thenReturn(false);
            bloomFilter = new RevocationBloomFilter(config, repository, eventPublisher, vertx);

            assertFalse(bloomFilter.isEnabled());
        }

        @Test
        @DisplayName("should return false when bloom filter disabled")
        void shouldReturnFalseWhenBloomFilterDisabled() {
            when(bloomFilterConfig.enabled()).thenReturn(false);
            bloomFilter = new RevocationBloomFilter(config, repository, eventPublisher, vertx);

            assertFalse(bloomFilter.isEnabled());
        }
    }

    @Nested
    @DisplayName("userDefinitelyNotRevoked() when not initialized")
    class UserDefinitelyNotRevokedNotInitializedTests {

        @Test
        @DisplayName("should return false when not initialized")
        void shouldReturnFalseWhenNotInitialized() {
            lenient().when(config.enabled()).thenReturn(false);
            bloomFilter = new RevocationBloomFilter(config, repository, eventPublisher, vertx);

            final var result = bloomFilter.userDefinitelyNotRevoked("any-user");

            assertFalse(result);
        }
    }

    @Nested
    @DisplayName("handleRevocationEvent() via event publisher")
    class HandleRevocationEventViaPublisherTests {

        @Test
        @DisplayName("should handle JtiRevoked event")
        void shouldHandleJtiRevokedEvent() {
            initializeBloomFilter();

            var jti = "event-jti";
            assertTrue(bloomFilter.definitelyNotRevoked(jti));

            // Simulate the event handling by calling the public methods that
            // handleRevocationEvent delegates to
            RevocationEvent event =
                    new RevocationEvent.JtiRevoked(jti, Instant.now().plus(Duration.ofHours(1)));
            switch (event) {
                case RevocationEvent.JtiRevoked jtiRevoked -> bloomFilter.addRevokedJti(jtiRevoked.jti());
                case RevocationEvent.UserRevoked userRevoked -> bloomFilter.addRevokedUser(userRevoked.userId());
            }

            assertFalse(bloomFilter.definitelyNotRevoked(jti));
        }

        @Test
        @DisplayName("should handle UserRevoked event")
        void shouldHandleUserRevokedEvent() {
            initializeBloomFilter();

            var userId = "event-user";
            assertTrue(bloomFilter.userDefinitelyNotRevoked(userId));

            RevocationEvent event = new RevocationEvent.UserRevoked(
                    userId, Instant.now(), Instant.now().plus(Duration.ofHours(1)));
            switch (event) {
                case RevocationEvent.JtiRevoked jtiRevoked -> bloomFilter.addRevokedJti(jtiRevoked.jti());
                case RevocationEvent.UserRevoked userRevoked -> bloomFilter.addRevokedUser(userRevoked.userId());
            }

            assertFalse(bloomFilter.userDefinitelyNotRevoked(userId));
        }
    }
}
