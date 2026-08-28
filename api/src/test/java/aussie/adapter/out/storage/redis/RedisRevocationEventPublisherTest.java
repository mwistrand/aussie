package aussie.adapter.out.storage.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.pubsub.PubSubCommands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import aussie.core.config.TokenRevocationConfig;
import aussie.core.model.auth.RevocationEvent;

@DisplayName("RedisRevocationEventPublisher.MessageHandler")
class RedisRevocationEventPublisherTest {

    private RedisRevocationEventPublisher.MessageHandler handler;
    private final java.util.List<RevocationEvent> received = new ArrayList<>();

    @BeforeEach
    void setUp() {
        handler = new RedisRevocationEventPublisher.MessageHandler();
        handler.events().subscribe().with(received::add);
    }

    @Nested
    @DisplayName("JTI revocation messages")
    class JtiMessages {

        @Test
        @DisplayName("parses simple JTI")
        void parsesSimpleJti() {
            handler.accept("jti:abc-123:1700000000000");

            assertEquals(1, received.size());
            var event = assertInstanceOf(RevocationEvent.JtiRevoked.class, received.getFirst());
            assertEquals("abc-123", event.jti());
            assertEquals(Instant.ofEpochMilli(1700000000000L), event.expiresAt());
        }

        @Test
        @DisplayName("parses JTI containing colons")
        void parsesJtiWithColons() {
            handler.accept("jti:urn:uuid:abc-123:1700000000000");

            assertEquals(1, received.size());
            var event = assertInstanceOf(RevocationEvent.JtiRevoked.class, received.getFirst());
            assertEquals("urn:uuid:abc-123", event.jti());
            assertEquals(Instant.ofEpochMilli(1700000000000L), event.expiresAt());
        }

        @Test
        @DisplayName("returns null for JTI message with no value")
        void rejectsJtiWithNoValue() {
            handler.accept("jti");

            assertEquals(0, received.size());
        }

        @Test
        @DisplayName("returns null for malformed JTI (non-numeric epoch)")
        void rejectsMalformedJtiEpoch() {
            handler.accept("jti:abc:notanumber");

            assertEquals(0, received.size());
        }
    }

    @Nested
    @DisplayName("User revocation messages")
    class UserMessages {

        @Test
        @DisplayName("parses simple user revocation")
        void parsesSimpleUser() {
            handler.accept("user:user42:1700000000000:1700100000000");

            assertEquals(1, received.size());
            var event = assertInstanceOf(RevocationEvent.UserRevoked.class, received.getFirst());
            assertEquals("user42", event.userId());
            assertEquals(Instant.ofEpochMilli(1700000000000L), event.issuedBefore());
            assertEquals(Instant.ofEpochMilli(1700100000000L), event.expiresAt());
        }

        @Test
        @DisplayName("parses userId containing colons")
        void parsesUserWithColons() {
            handler.accept("user:auth0|ns:id:1700000000000:1700100000000");

            assertEquals(1, received.size());
            var event = assertInstanceOf(RevocationEvent.UserRevoked.class, received.getFirst());
            assertEquals("auth0|ns:id", event.userId());
            assertEquals(Instant.ofEpochMilli(1700000000000L), event.issuedBefore());
            assertEquals(Instant.ofEpochMilli(1700100000000L), event.expiresAt());
        }

        @Test
        @DisplayName("parses userId with many colons")
        void parsesUserWithManyColons() {
            handler.accept("user:a:b:c:d:1700000000000:1700100000000");

            assertEquals(1, received.size());
            var event = assertInstanceOf(RevocationEvent.UserRevoked.class, received.getFirst());
            assertEquals("a:b:c:d", event.userId());
        }

        @Test
        @DisplayName("returns null for user message with too few fields")
        void rejectsUserWithTooFewFields() {
            handler.accept("user:onlyid:1700000000000");

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
            final var config = mock(TokenRevocationConfig.class);
            final var pubsubConfig = mock(TokenRevocationConfig.PubSubConfig.class);
            when(config.enabled()).thenReturn(true);
            when(config.pubsub()).thenReturn(pubsubConfig);
            when(pubsubConfig.enabled()).thenReturn(true);
            when(pubsubConfig.channel()).thenReturn("test:revocations");
            final var dataSource = mock(RedisDataSource.class);
            final var pubsub = mock(PubSubCommands.class);
            final var subscriber = mock(PubSubCommands.RedisSubscriber.class);
            when(dataSource.pubsub(String.class)).thenReturn(pubsub);
            when(pubsub.subscribe(anyString(), any())).thenReturn(subscriber);
            final var publisher = new RedisRevocationEventPublisher(dataSource, config);
            final var completed = new AtomicBoolean();
            publisher.init();
            publisher.subscribe().subscribe().with(ignored -> {}, ignored -> {}, () -> completed.set(true));

            publisher.cleanup();
            publisher.cleanup();

            assertTrue(completed.get());
            verify(subscriber).unsubscribe();
        }

        @Test
        @DisplayName("completes the event stream")
        void completesEventStream() {
            final var completed = new AtomicBoolean();
            handler.events().subscribe().with(received::add, ignored -> {}, () -> completed.set(true));

            handler.complete();

            assertTrue(completed.get());
        }

        @Test
        @DisplayName("returns null for unknown message type")
        void rejectsUnknownType() {
            handler.accept("unknown:data:123");

            assertEquals(0, received.size());
        }

        @Test
        @DisplayName("returns null for message with no separator")
        void rejectsNoSeparator() {
            handler.accept("noseparator");

            assertEquals(0, received.size());
        }

        @Test
        @DisplayName("returns null for empty string")
        void rejectsEmptyString() {
            handler.accept("");

            assertEquals(0, received.size());
        }

        @Test
        @DisplayName("does not log malformed revocation messages")
        void redactsMalformedMessage() {
            final var secret = "sensitive-jti";
            final var logger = Logger.getLogger(RedisRevocationEventPublisher.MessageHandler.class.getName());
            final var previousLevel = logger.getLevel();
            final var logHandler = new CapturingHandler();
            logger.setLevel(Level.ALL);
            logHandler.setLevel(Level.ALL);
            logger.addHandler(logHandler);
            try {
                handler.accept("jti:" + secret + ":not-a-timestamp");

                assertFalse(logHandler.text().isBlank(), "expected the parse failure to be logged");
                assertFalse(logHandler.text().contains(secret), "log output leaked the malformed message");
            } finally {
                logger.removeHandler(logHandler);
                logger.setLevel(previousLevel);
            }
        }
    }

    @Nested
    @DisplayName("Roundtrip consistency")
    class Roundtrip {

        private static final String MESSAGE_SEPARATOR = ":";

        @Test
        @DisplayName("JTI with colons roundtrips through publish format and parse")
        void jtiRoundtrip() {
            final var jti = "urn:uuid:550e8400-e29b-41d4-a716-446655440000";
            final var expiresAt = Instant.ofEpochMilli(1700000000000L);
            final var message = "jti" + MESSAGE_SEPARATOR + jti + MESSAGE_SEPARATOR + expiresAt.toEpochMilli();

            handler.accept(message);

            assertEquals(1, received.size());
            var event = assertInstanceOf(RevocationEvent.JtiRevoked.class, received.getFirst());
            assertEquals(jti, event.jti());
            assertEquals(expiresAt, event.expiresAt());
        }

        @Test
        @DisplayName("User with colons roundtrips through publish format and parse")
        void userRoundtrip() {
            final var userId = "auth0|tenant:user:123";
            final var issuedBefore = Instant.ofEpochMilli(1700000000000L);
            final var expiresAt = Instant.ofEpochMilli(1700100000000L);
            final var message = "user" + MESSAGE_SEPARATOR + userId + MESSAGE_SEPARATOR + issuedBefore.toEpochMilli()
                    + MESSAGE_SEPARATOR + expiresAt.toEpochMilli();

            handler.accept(message);

            assertEquals(1, received.size());
            var event = assertInstanceOf(RevocationEvent.UserRevoked.class, received.getFirst());
            assertEquals(userId, event.userId());
            assertEquals(issuedBefore, event.issuedBefore());
            assertEquals(expiresAt, event.expiresAt());
        }
    }

    private static final class CapturingHandler extends Handler {
        private final java.util.List<String> records = new ArrayList<>();

        @Override
        public synchronized void publish(LogRecord record) {
            var text = new StringBuilder(record.getMessage());
            if (record.getParameters() != null) {
                for (var parameter : record.getParameters()) {
                    text.append('|').append(parameter);
                }
            }
            if (record.getThrown() != null) {
                text.append('|').append(record.getThrown().getMessage());
            }
            records.add(text.toString());
        }

        synchronized String text() {
            return String.join("\n", records);
        }

        @Override
        public void flush() {}

        @Override
        public void close() throws SecurityException {}
    }
}
