package aussie.core.config;

import java.time.Duration;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Configuration mapping for WebSocket connection management.
 *
 * <p>Configuration prefix: {@code aussie.websocket}
 *
 * <p>All connection limits are <b>per Aussie instance</b>, not cluster-wide.
 * In a 3-instance cluster with max-connections=10000, the cluster can handle
 * up to 30,000 concurrent WebSocket connections.
 */
@ConfigMapping(prefix = "aussie.websocket")
public interface WebSocketConfig {

    /**
     * Idle timeout - close connection if no messages in either direction.
     *
     * @return Idle duration (default: 5 minutes)
     */
    @WithDefault("PT5M")
    Duration idleTimeout();

    /**
     * Maximum connection lifetime regardless of activity.
     *
     * <p>This is a hard limit - even active connections are closed when reached.
     *
     * @return Max lifetime duration (default: 24 hours)
     */
    @WithDefault("PT24H")
    Duration maxLifetime();

    /**
     * Ping/pong heartbeat configuration.
     */
    PingConfig ping();

    /**
     * Maximum concurrent WebSocket connections per instance.
     *
     * <p><b>Note:</b> This is per Aussie instance, not cluster-wide.
     *
     * @return Max connections (default: 10000)
     */
    @WithDefault("10000")
    int maxConnections();

    /**
     * Ping/pong heartbeat configuration.
     */
    interface PingConfig {

        /**
         * Enable ping/pong heartbeats to detect stale client connections.
         *
         * @return true if pings are enabled (default: true)
         */
        @WithDefault("true")
        boolean enabled();

        /**
         * How often to send ping frames to the client.
         *
         * @return Ping interval (default: 30 seconds)
         */
        @WithDefault("PT30S")
        Duration interval();

        /**
         * Close connection if pong not received within this time.
         *
         * @return Pong timeout (default: 10 seconds)
         */
        @WithDefault("PT10S")
        Duration timeout();
    }
}
