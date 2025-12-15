package aussie.core.model.ratelimit;

import io.smallrye.mutiny.Uni;

/**
 * Functional interface for checking message rate limits.
 *
 * <p>Called before forwarding each WebSocket message to check if the message
 * should be allowed. If rate limited, the handler triggers connection closure.
 */
@FunctionalInterface
public interface MessageRateLimitHandler {

    /**
     * Check if a message is allowed and execute the appropriate action.
     *
     * <p>If allowed, the onAllowed callback is invoked immediately.
     * If rate limited, the implementation should close the connection.
     *
     * @param onAllowed callback to invoke if message is allowed
     * @return completion signal (may complete after onAllowed)
     */
    Uni<Void> checkAndProceed(Runnable onAllowed);

    /**
     * No-op handler that always allows messages.
     */
    static MessageRateLimitHandler noOp() {
        return onAllowed -> {
            onAllowed.run();
            return Uni.createFrom().voidItem();
        };
    }
}
