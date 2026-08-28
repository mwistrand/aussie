package aussie.adapter.out.storage.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.pubsub.PubSubCommands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.config.ServiceConfigPubSubConfig;
import aussie.core.model.service.ServiceConfigEvent;

@DisplayName("RedisServiceConfigEventPublisher")
class RedisServiceConfigEventPublisherTest {

    private RedisServiceConfigEventPublisher.MessageHandler handler;
    private final java.util.List<ServiceConfigEvent> received = new ArrayList<>();

    @BeforeEach
    void setUp() {
        handler = new RedisServiceConfigEventPublisher.MessageHandler();
        handler.events().subscribe().with(received::add);
    }

    @Nested
    @DisplayName("Service changed messages")
    class ChangedMessages {

        @Test
        @DisplayName("parses simple service changed event")
        void parsesSimpleChanged() {
            handler.accept("changed:my-service");

            assertEquals(1, received.size());
            var event = assertInstanceOf(ServiceConfigEvent.ServiceChanged.class, received.getFirst());
            assertEquals("my-service", event.serviceId());
        }

        @Test
        @DisplayName("parses service ID containing colons")
        void parsesChangedWithColons() {
            handler.accept("changed:org:team:my-service");

            assertEquals(1, received.size());
            var event = assertInstanceOf(ServiceConfigEvent.ServiceChanged.class, received.getFirst());
            assertEquals("org:team:my-service", event.serviceId());
        }

        @Test
        @DisplayName("preserves legacy service ID with numeric prefix")
        void preservesLegacyNumericPrefix() {
            handler.accept("changed:123:my-service");

            var event = assertInstanceOf(ServiceConfigEvent.ServiceChanged.class, received.getFirst());
            assertEquals("123:my-service", event.serviceId());
            assertEquals(0L, event.generation());
        }

        @Test
        @DisplayName("parses versioned generation")
        void parsesVersionedGeneration() {
            handler.accept("changed-v1:42:org:team:my-service");

            var event = assertInstanceOf(ServiceConfigEvent.ServiceChanged.class, received.getFirst());
            assertEquals("org:team:my-service", event.serviceId());
            assertEquals(42L, event.generation());
        }

        @Test
        @DisplayName("rejects changed message with empty service ID")
        void rejectsChangedWithEmptyId() {
            handler.accept("changed:");

            assertEquals(0, received.size());
        }
    }

    @Nested
    @DisplayName("Service removed messages")
    class RemovedMessages {

        @Test
        @DisplayName("parses simple service removed event")
        void parsesSimpleRemoved() {
            handler.accept("removed:my-service");

            assertEquals(1, received.size());
            var event = assertInstanceOf(ServiceConfigEvent.ServiceRemoved.class, received.getFirst());
            assertEquals("my-service", event.serviceId());
        }

        @Test
        @DisplayName("parses service ID containing colons")
        void parsesRemovedWithColons() {
            handler.accept("removed:org:team:my-service");

            assertEquals(1, received.size());
            var event = assertInstanceOf(ServiceConfigEvent.ServiceRemoved.class, received.getFirst());
            assertEquals("org:team:my-service", event.serviceId());
        }

        @Test
        @DisplayName("rejects removed message with empty service ID")
        void rejectsRemovedWithEmptyId() {
            handler.accept("removed:");

            assertEquals(0, received.size());
        }
    }

    @Nested
    @DisplayName("General edge cases")
    class EdgeCases {

        @Test
        @DisplayName("cleanup completes subscribers and unsubscribes once")
        @SuppressWarnings("unchecked")
        void cleanupIsIdempotent() {
            final var config = mock(ServiceConfigPubSubConfig.class);
            when(config.enabled()).thenReturn(true);
            when(config.topic()).thenReturn("test:services");
            final var dataSource = mock(RedisDataSource.class);
            final var pubsub = mock(PubSubCommands.class);
            final var subscriber = mock(PubSubCommands.RedisSubscriber.class);
            when(dataSource.pubsub(String.class)).thenReturn(pubsub);
            when(pubsub.subscribe(anyString(), any())).thenReturn(subscriber);
            final var publisher = new RedisServiceConfigEventPublisher(dataSource, config);
            final var completed = new AtomicBoolean();
            publisher.init();
            publisher.subscribe().subscribe().with(ignored -> {}, ignored -> {}, () -> completed.set(true));

            publisher.cleanup();
            publisher.cleanup();

            assertTrue(completed.get());
            verify(subscriber).unsubscribe();
        }

        @Test
        @DisplayName("rejects unknown message type")
        void rejectsUnknownType() {
            handler.accept("unknown:my-service");

            assertEquals(0, received.size());
        }

        @Test
        @DisplayName("rejects message with no separator")
        void rejectsNoSeparator() {
            handler.accept("noseparator");

            assertEquals(0, received.size());
        }

        @Test
        @DisplayName("rejects empty string")
        void rejectsEmptyString() {
            handler.accept("");

            assertEquals(0, received.size());
        }

        @Test
        @DisplayName("rejects null message")
        void rejectsNullMessage() {
            handler.accept(null);

            assertEquals(0, received.size());
        }

        @Test
        @DisplayName("rejects whitespace-only message")
        void rejectsWhitespaceMessage() {
            handler.accept("   ");

            assertEquals(0, received.size());
        }

        @Test
        @DisplayName("rejects message with leading separator")
        void rejectsLeadingSeparator() {
            handler.accept(":my-service");

            assertEquals(0, received.size());
        }

        @Test
        @DisplayName("rejects message with double separator prefix")
        void rejectsDoubleSeparatorPrefix() {
            handler.accept("changed::my-service");

            // "changed" type with ":my-service" as serviceId (starts with colon)
            // This should still parse since serviceId is non-empty
            assertEquals(1, received.size());
        }
    }

    @Nested
    @DisplayName("Roundtrip consistency")
    class Roundtrip {

        @Test
        @DisplayName("changed event roundtrips through publish format and parse")
        void changedRoundtrip() {
            final var serviceId = "my-service";
            final var message = "changed" + RedisServiceConfigEventPublisher.MESSAGE_SEPARATOR + serviceId;

            handler.accept(message);

            assertEquals(1, received.size());
            var event = assertInstanceOf(ServiceConfigEvent.ServiceChanged.class, received.getFirst());
            assertEquals(serviceId, event.serviceId());
        }

        @Test
        @DisplayName("removed event roundtrips through publish format and parse")
        void removedRoundtrip() {
            final var serviceId = "my-service";
            final var message = "removed" + RedisServiceConfigEventPublisher.MESSAGE_SEPARATOR + serviceId;

            handler.accept(message);

            assertEquals(1, received.size());
            var event = assertInstanceOf(ServiceConfigEvent.ServiceRemoved.class, received.getFirst());
            assertEquals(serviceId, event.serviceId());
        }
    }

    @Nested
    @DisplayName("Disabled mode")
    class DisabledMode {

        private static final Duration TIMEOUT = Duration.ofSeconds(2);

        @SuppressWarnings("unchecked")
        private RedisServiceConfigEventPublisher createDisabledPublisher() {
            var config = mock(ServiceConfigPubSubConfig.class);
            when(config.enabled()).thenReturn(false);
            when(config.topic()).thenReturn("test:topic");

            var dataSource = mock(RedisDataSource.class);
            var pubsub = mock(PubSubCommands.class);
            when(dataSource.pubsub(String.class)).thenReturn(pubsub);

            var publisher = new RedisServiceConfigEventPublisher(dataSource, config);
            publisher.init();
            return publisher;
        }

        @Test
        @DisplayName("publishServiceChanged completes without error when disabled")
        void publishChangedNoOpWhenDisabled() {
            var publisher = createDisabledPublisher();

            // Should complete without throwing
            publisher.publishServiceChanged("my-service").await().atMost(TIMEOUT);
        }

        @Test
        @DisplayName("publishServiceRemoved completes without error when disabled")
        void publishRemovedNoOpWhenDisabled() {
            var publisher = createDisabledPublisher();

            // Should complete without throwing
            publisher.publishServiceRemoved("my-service").await().atMost(TIMEOUT);
        }

        @Test
        @DisplayName("subscribe returns empty Multi when disabled")
        void subscribeReturnsEmptyWhenDisabled() {
            var publisher = createDisabledPublisher();

            final var events = new ArrayList<ServiceConfigEvent>();
            publisher.subscribe().subscribe().with(events::add);

            assertTrue(events.isEmpty());
        }
    }
}
