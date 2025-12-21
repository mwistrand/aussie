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
import org.jboss.logging.Logger;

import aussie.core.config.TokenRevocationConfig;
import aussie.core.model.auth.RevocationEvent;
import aussie.core.port.out.RevocationEventPublisher;

/**
 * Redis pub/sub implementation of RevocationEventPublisher.
 *
 * <p>Uses Redis pub/sub to notify other Aussie instances of revocation events,
 * allowing them to update their bloom filters without waiting for scheduled rebuilds.
 *
 * <p>Message format:
 * <ul>
 *   <li>JTI revocation: {@code jti:{jti}:{expiresAtMillis}}</li>
 *   <li>User revocation: {@code user:{userId}:{issuedBeforeMillis}:{expiresAtMillis}}</li>
 * </ul>
 */
@ApplicationScoped
@DefaultBean
@Startup // Ensure eager initialization to avoid blocking on event loop during lazy creation
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
    void cleanup() {
        if (subscriber != null) {
            try {
                subscriber.unsubscribe();
                LOG.info("Unsubscribed from revocation events");
            } catch (Exception e) {
                LOG.warnf(e, "Error unsubscribing from revocation events");
            }
        }
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
                    LOG.debugf("Published JTI revocation event: %s", jti);
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
                    LOG.debugf("Published user revocation event: %s", userId);
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
    private static class MessageHandler implements java.util.function.Consumer<String> {

        private final io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor<RevocationEvent> processor =
                io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor.create();

        @Override
        public void accept(String message) {
            try {
                var event = parseMessage(message);
                if (event != null) {
                    processor.onNext(event);
                }
            } catch (Exception e) {
                LOG.warnf(e, "Failed to parse revocation event: %s", message);
            }
        }

        Multi<RevocationEvent> events() {
            return processor;
        }

        private RevocationEvent parseMessage(String message) {
            var parts = message.split(MESSAGE_SEPARATOR);

            if (parts.length < 3) {
                LOG.warnf("Invalid revocation event format: %s", message);
                return null;
            }

            var type = parts[0];

            return switch (type) {
                case "jti" -> {
                    var jti = parts[1];
                    var expiresAt = Instant.ofEpochMilli(Long.parseLong(parts[2]));
                    yield new RevocationEvent.JtiRevoked(jti, expiresAt);
                }
                case "user" -> {
                    if (parts.length < 4) {
                        LOG.warnf("Invalid user revocation event format: %s", message);
                        yield null;
                    }
                    var userId = parts[1];
                    var issuedBefore = Instant.ofEpochMilli(Long.parseLong(parts[2]));
                    var expiresAt = Instant.ofEpochMilli(Long.parseLong(parts[3]));
                    yield new RevocationEvent.UserRevoked(userId, issuedBefore, expiresAt);
                }
                default -> {
                    LOG.warnf("Unknown revocation event type: %s", type);
                    yield null;
                }
            };
        }

        private static final Logger LOG = Logger.getLogger(MessageHandler.class);
    }
}
