package aussie.adapter.out.storage.redis;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.pubsub.PubSubCommands;
import io.quarkus.runtime.Startup;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import org.jboss.logging.Logger;

import aussie.core.config.ServiceConfigPubSubConfig;
import aussie.core.model.service.ServiceConfigEvent;
import aussie.core.port.out.ServiceConfigEventPublisher;

/**
 * Redis pub/sub implementation of ServiceConfigEventPublisher.
 *
 * <p>Uses Redis pub/sub to notify other Aussie instances of service
 * configuration changes, allowing them to immediately refresh their
 * local caches instead of waiting for TTL expiration.
 *
 * <p>Message format:
 * <ul>
 *   <li>Service changed: {@code changed-v1:{generation}:{serviceId}}</li>
 *   <li>Service removed: {@code removed-v1:{generation}:{serviceId}}</li>
 * </ul>
 *
 * <p>Legacy messages without a generation remain readable during rolling upgrades.
 */
@ApplicationScoped
@Startup
public class RedisServiceConfigEventPublisher implements ServiceConfigEventPublisher {

    private static final Logger LOG = Logger.getLogger(RedisServiceConfigEventPublisher.class);
    static final String MESSAGE_SEPARATOR = ":";

    private final ServiceConfigPubSubConfig config;
    private final PubSubCommands<String> pubsub;
    private final String channel;

    private volatile PubSubCommands.RedisSubscriber subscriber;
    private volatile MessageHandler messageHandler;

    public RedisServiceConfigEventPublisher(RedisDataSource redisDataSource, ServiceConfigPubSubConfig config) {
        this.config = config;
        this.pubsub = redisDataSource.pubsub(String.class);
        this.channel = config.topic();
        LOG.infof("Initialized Redis service config event publisher (topic: %s)", channel);
    }

    @PostConstruct
    void init() {
        if (!config.enabled()) {
            LOG.info("Service config pub/sub disabled");
            return;
        }

        this.messageHandler = new MessageHandler();
        this.subscriber = pubsub.subscribe(channel, messageHandler);
        LOG.infof("Subscribed to service config events on topic: %s", channel);
    }

    @PreDestroy
    void cleanup() {
        if (messageHandler != null) {
            messageHandler.complete();
        }
        if (subscriber != null) {
            try {
                subscriber.unsubscribe();
                LOG.info("Unsubscribed from service config events");
            } catch (Exception e) {
                LOG.warnf(e, "Error unsubscribing from service config events");
            }
        }
    }

    /** {@inheritDoc} */
    @Override
    public Uni<Void> publishServiceChanged(String serviceId) {
        return publishServiceChanged(serviceId, 0L);
    }

    @Override
    public Uni<Void> publishServiceChanged(String serviceId, long generation) {
        if (!config.enabled()) {
            return Uni.createFrom().voidItem();
        }

        var message = "changed-v1" + MESSAGE_SEPARATOR + generation + MESSAGE_SEPARATOR + serviceId;

        return Uni.createFrom()
                .voidItem()
                .emitOn(Infrastructure.getDefaultWorkerPool())
                .invoke(() -> {
                    pubsub.publish(channel, message);
                    LOG.debugf("Published service changed event: %s", serviceId);
                });
    }

    /** {@inheritDoc} */
    @Override
    public Uni<Void> publishServiceRemoved(String serviceId) {
        return publishServiceRemoved(serviceId, 0L);
    }

    @Override
    public Uni<Void> publishServiceRemoved(String serviceId, long generation) {
        if (!config.enabled()) {
            return Uni.createFrom().voidItem();
        }

        var message = "removed-v1" + MESSAGE_SEPARATOR + generation + MESSAGE_SEPARATOR + serviceId;

        return Uni.createFrom()
                .voidItem()
                .emitOn(Infrastructure.getDefaultWorkerPool())
                .invoke(() -> {
                    pubsub.publish(channel, message);
                    LOG.debugf("Published service removed event: %s", serviceId);
                });
    }

    /** {@inheritDoc} */
    @Override
    public Multi<ServiceConfigEvent> subscribe() {
        if (messageHandler == null) {
            return Multi.createFrom().empty();
        }
        return messageHandler.events();
    }

    /**
     * Message handler that converts Redis messages to ServiceConfigEvents.
     */
    static class MessageHandler implements java.util.function.Consumer<String> {

        private static final Logger LOG = Logger.getLogger(MessageHandler.class);

        private final BroadcastProcessor<ServiceConfigEvent> processor = BroadcastProcessor.create();

        @Override
        public void accept(String message) {
            try {
                var event = parseMessage(message);
                if (event != null) {
                    processor.onNext(event);
                }
            } catch (Exception e) {
                LOG.warnf(e, "Failed to parse service config event: %s", message);
            }
        }

        Multi<ServiceConfigEvent> events() {
            return processor;
        }

        void complete() {
            processor.onComplete();
        }

        /**
         * Parse a pub/sub message into a ServiceConfigEvent.
         *
         * <p>Versioned format: {@code type-v1:generation:serviceId}. Legacy
         * {@code type:serviceId} messages are also accepted.
         */
        ServiceConfigEvent parseMessage(String message) {
            if (message == null || message.isBlank()) {
                LOG.warnf("Received null or blank service config event message");
                return null;
            }

            final var firstSep = message.indexOf(MESSAGE_SEPARATOR);
            if (firstSep < 0) {
                LOG.warnf("Invalid service config event format: %s", message);
                return null;
            }

            final var type = message.substring(0, firstSep);
            final var payload = message.substring(firstSep + 1);
            long generation = 0L;
            String serviceId = payload;
            if (type.endsWith("-v1")) {
                final var secondSep = payload.indexOf(MESSAGE_SEPARATOR);
                if (secondSep <= 0) {
                    LOG.warnf("Invalid versioned service config event format: %s", message);
                    return null;
                }
                try {
                    generation = Long.parseLong(payload.substring(0, secondSep));
                    serviceId = payload.substring(secondSep + 1);
                } catch (NumberFormatException invalidGeneration) {
                    LOG.warnf("Invalid service config event generation: %s", message);
                    return null;
                }
                if (generation < 0L) {
                    LOG.warnf("Negative service config event generation: %s", message);
                    return null;
                }
            }

            if (serviceId.isEmpty()) {
                LOG.warnf("Empty service ID in config event: %s", message);
                return null;
            }

            return switch (type) {
                case "changed", "changed-v1" -> new ServiceConfigEvent.ServiceChanged(serviceId, generation);
                case "removed", "removed-v1" -> new ServiceConfigEvent.ServiceRemoved(serviceId, generation);
                default -> {
                    LOG.warnf("Unknown service config event type: %s", type);
                    yield null;
                }
            };
        }
    }
}
