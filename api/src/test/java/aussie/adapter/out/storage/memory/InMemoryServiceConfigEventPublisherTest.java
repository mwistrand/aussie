package aussie.adapter.out.storage.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.model.service.ServiceConfigEvent;

@DisplayName("InMemoryServiceConfigEventPublisher")
class InMemoryServiceConfigEventPublisherTest {

    private InMemoryServiceConfigEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new InMemoryServiceConfigEventPublisher();
    }

    @Nested
    @DisplayName("publishServiceChanged()")
    class PublishServiceChangedTests {

        @Test
        @DisplayName("should publish ServiceChanged event to subscribers")
        void shouldPublishServiceChangedEventToSubscribers() throws InterruptedException {
            var events = new ArrayList<ServiceConfigEvent>();
            var latch = new CountDownLatch(1);

            publisher.subscribe().subscribe().with(event -> {
                events.add(event);
                latch.countDown();
            });

            publisher.publishServiceChanged("my-service").await().atMost(Duration.ofSeconds(1));

            assertTrue(latch.await(2, TimeUnit.SECONDS));
            assertEquals(1, events.size());
            assertInstanceOf(ServiceConfigEvent.ServiceChanged.class, events.get(0));
            assertEquals("my-service", events.get(0).serviceId());
        }
    }

    @Nested
    @DisplayName("publishServiceRemoved()")
    class PublishServiceRemovedTests {

        @Test
        @DisplayName("should publish ServiceRemoved event to subscribers")
        void shouldPublishServiceRemovedEventToSubscribers() throws InterruptedException {
            var events = new ArrayList<ServiceConfigEvent>();
            var latch = new CountDownLatch(1);

            publisher.subscribe().subscribe().with(event -> {
                events.add(event);
                latch.countDown();
            });

            publisher.publishServiceRemoved("my-service").await().atMost(Duration.ofSeconds(1));

            assertTrue(latch.await(2, TimeUnit.SECONDS));
            assertEquals(1, events.size());
            assertInstanceOf(ServiceConfigEvent.ServiceRemoved.class, events.get(0));
            assertEquals("my-service", events.get(0).serviceId());
        }
    }

    @Nested
    @DisplayName("subscribe()")
    class SubscribeTests {

        @Test
        @DisplayName("should receive multiple events in order")
        void shouldReceiveMultipleEventsInOrder() throws InterruptedException {
            var events = new ArrayList<ServiceConfigEvent>();
            var latch = new CountDownLatch(3);

            publisher.subscribe().subscribe().with(event -> {
                events.add(event);
                latch.countDown();
            });

            publisher.publishServiceChanged("svc-1").await().atMost(Duration.ofSeconds(1));
            publisher.publishServiceRemoved("svc-2").await().atMost(Duration.ofSeconds(1));
            publisher.publishServiceChanged("svc-3").await().atMost(Duration.ofSeconds(1));

            assertTrue(latch.await(2, TimeUnit.SECONDS));
            assertEquals(3, events.size());
            assertEquals("svc-1", events.get(0).serviceId());
            assertEquals("svc-2", events.get(1).serviceId());
            assertEquals("svc-3", events.get(2).serviceId());
        }
    }

    @Nested
    @DisplayName("close()")
    class CloseTests {

        @Test
        @DisplayName("should not publish events after close")
        void shouldNotPublishEventsAfterClose() {
            publisher.close();

            // Should not throw
            publisher.publishServiceChanged("my-service").await().atMost(Duration.ofSeconds(1));
            publisher.publishServiceRemoved("my-service").await().atMost(Duration.ofSeconds(1));
        }

        @Test
        @DisplayName("should be idempotent")
        void shouldBeIdempotent() {
            publisher.close();
            publisher.close(); // Should not throw
        }
    }

    @Nested
    @DisplayName("no subscribers")
    class NoSubscribersTests {

        @Test
        @DisplayName("should not throw when publishing with no subscribers")
        void shouldNotThrowWhenPublishingWithNoSubscribers() {
            // Should not throw
            publisher.publishServiceChanged("my-service").await().atMost(Duration.ofSeconds(1));
            publisher.publishServiceRemoved("my-service").await().atMost(Duration.ofSeconds(1));
        }
    }
}
