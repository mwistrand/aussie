package aussie.adapter.out.storage.redis;

import java.time.Instant;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.arc.DefaultBean;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.pubsub.PubSubCommands;
import io.quarkus.runtime.Startup;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import org.jboss.logging.Logger;

import aussie.core.config.TokenRevocationConfig;
import aussie.core.model.auth.RevocationEvent;
import aussie.core.port.out.RevocationEventPublisher;
import aussie.core.util.SafeLogging;

/**
 * Redis pub/sub implementation of RevocationEventPublisher.
 *
 * <p>
 * Uses Redis pub/sub to notify other Aussie instances of revocation events,
 * allowing them to update their bloom filters without waiting for scheduled
 * rebuilds.
 *
 * <p>
 * Message format:
 * <ul>
 * <li>JTI revocation: {@code jti:{jti}:{expiresAtMillis}}</li>
 * <li>User revocation:
 * {@code user:{userId}:{issuedBeforeMillis}:{expiresAtMillis}}</li>
 * </ul>
 *
 * <p>
 * Identifiers (JTI, userId) may contain the separator character ({@code :}).
 * Parsing splits from the right since trailing fields are always numeric epoch
 * millis.
 */
@ApplicationScoped
@DefaultBean
@Startup // Ensure eager initialization to avoid blocking on event loop during lazy
// creation
public class RedisRevocationEventPublisher implements RevocationEventPublisher {

    private static final Logger LOG = Logger.getLogger(RedisRevocationEventPublisher.class);
    private static final String MESSAGE_SEPARATOR = ":";

    private final TokenRevocationConfig config;
    private final PubSubCommands<String> pubsub;
    private final String channel;

    private volatile PubSubCommands.RedisSubscriber subscriber;
    private volatile MessageHandler messageHandler;

    public RedisRevocationEventPublisher(RedisDataSource redisDataSource, TokenRevocationConfig config) {
        this.config = config;
        this.pubsub = redisDataSource.pubsub(String.class);
        this.channel = config.pubsub().channel();
        LOG.infof("Initialized Redis revocation event publisher (channel: %s)", channel);
    }

    @PostConstruct
    void init() {
        if (!config.enabled() || !config.pubsub().enabled()) {
            LOG.info("Revocation pub/sub disabled");
            return;
        }

        // Create message handler and subscribe
        this.messageHandler = new MessageHandler();
        this.subscriber = pubsub.subscribe(channel, messageHandler);
        LOG.infof("Subscribed to revocation events on channel: %s", channel);
    }

    @PreDestroy
    synchronized void cleanup() {
        final var handler = messageHandler;
        messageHandler = null;
        if (handler != null) {
            handler.complete();
        }
        final var currentSubscriber = subscriber;
        subscriber = null;
        if (currentSubscriber != null) {
            try {
                currentSubscriber.unsubscribe();
                LOG.info("Unsubscribed from revocation events");
            } catch (Exception e) {
                if (isConnectionClosed(e)) {
                    LOG.debugf(
                            "Revocation event unsubscribe skipped during teardown: error_type=%s",
                            SafeLogging.errorType(e));
                } else {
                    LOG.warnf("Error unsubscribing from revocation events: error_type=%s", SafeLogging.errorType(e));
                }
            }
        }
    }

    private static boolean isConnectionClosed(Throwable error) {
        for (var cause = error; cause != null; cause = cause.getCause()) {
            if ("Connection is closed".equals(cause.getMessage()) || "CONNECTION_CLOSED".equals(cause.getMessage())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Uni<Void> publishJtiRevoked(String jti, Instant expiresAt) {
        if (!config.pubsub().enabled()) {
            return Uni.createFrom().voidItem();
        }

        var message = "jti" + MESSAGE_SEPARATOR + jti + MESSAGE_SEPARATOR + expiresAt.toEpochMilli();

        return Uni.createFrom()
                .item(() -> {
                    pubsub.publish(channel, message);
                    LOG.debugf("Published JTI revocation event: jti_hash=%s", SafeLogging.identifier(jti));
                    return null;
                })
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .replaceWithVoid();
    }

    @Override
    public Uni<Void> publishUserRevoked(String userId, Instant issuedBefore, Instant expiresAt) {
        if (!config.pubsub().enabled()) {
            return Uni.createFrom().voidItem();
        }

        var message = "user" + MESSAGE_SEPARATOR + userId + MESSAGE_SEPARATOR + issuedBefore.toEpochMilli()
                + MESSAGE_SEPARATOR + expiresAt.toEpochMilli();

        return Uni.createFrom()
                .item(() -> {
                    pubsub.publish(channel, message);
                    LOG.debugf("Published user revocation event: user_hash=%s", SafeLogging.identifier(userId));
                    return null;
                })
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .replaceWithVoid();
    }

    @Override
    public Multi<RevocationEvent> subscribe() {
        if (messageHandler == null) {
            return Multi.createFrom().empty();
        }
        return messageHandler.events();
    }

    /**
     * Message handler that converts Redis messages to RevocationEvents.
     */
    static class MessageHandler implements java.util.function.Consumer<String> {

        private static final Logger LOG = Logger.getLogger(MessageHandler.class);

        private final BroadcastProcessor<RevocationEvent> processor = BroadcastProcessor.create();

        @Override
        public void accept(String message) {
            try {
                var event = parseMessage(message);
                if (event != null) {
                    processor.onNext(event);
                }
            } catch (Exception e) {
                LOG.warnf("Failed to parse revocation event: error_type=%s", SafeLogging.errorType(e));
            }
        }

        Multi<RevocationEvent> events() {
            return processor;
        }

        void complete() {
            processor.onComplete();
        }

        private RevocationEvent parseMessage(String message) {
            // Type is everything before the first separator
            final var firstSep = message.indexOf(MESSAGE_SEPARATOR);
            if (firstSep < 0) {
                LOG.warn("Invalid revocation event format");
                return null;
            }

            final var type = message.substring(0, firstSep);

            // Parse fields from the right so that identifiers (JTI, userId)
            // can safely contain colons (e.g. "urn:uuid:..." or "auth0|ns:id").
            // The trailing fields are always numeric epoch millis.
            return switch (type) {
                case "jti" -> {
                    // Format: jti:<jti>:<expiresAtMillis>
                    final var lastSep = message.lastIndexOf(MESSAGE_SEPARATOR);
                    if (lastSep <= firstSep) {
                        LOG.warn("Invalid JTI revocation event format");
                        yield null;
                    }
                    final var jti = message.substring(firstSep + 1, lastSep);
                    final var expiresAt = Instant.ofEpochMilli(Long.parseLong(message.substring(lastSep + 1)));
                    yield new RevocationEvent.JtiRevoked(jti, expiresAt);
                }
                case "user" -> {
                    // Format: user:<userId>:<issuedBeforeMillis>:<expiresAtMillis>
                    final var lastSep = message.lastIndexOf(MESSAGE_SEPARATOR);
                    final var secondLastSep = message.lastIndexOf(MESSAGE_SEPARATOR, lastSep - 1);
                    if (secondLastSep <= firstSep) {
                        LOG.warn("Invalid user revocation event format");
                        yield null;
                    }
                    final var userId = message.substring(firstSep + 1, secondLastSep);
                    final var issuedBefore =
                            Instant.ofEpochMilli(Long.parseLong(message.substring(secondLastSep + 1, lastSep)));
                    final var expiresAt = Instant.ofEpochMilli(Long.parseLong(message.substring(lastSep + 1)));
                    yield new RevocationEvent.UserRevoked(userId, issuedBefore, expiresAt);
                }
                default -> {
                    LOG.warn("Unknown revocation event type");
                    yield null;
                }
            };
        }
    }
}
