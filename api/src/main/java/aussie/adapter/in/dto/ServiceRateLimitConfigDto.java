package aussie.adapter.in.dto;

import java.util.Optional;

import aussie.core.model.ServiceRateLimitConfig;

/**
 * DTO for service rate limit configuration in service registration.
 *
 * <p>Maps between the JSON representation and the domain model.
 * WebSocket rate limit configuration is included for forward compatibility
 * but is not currently used by the rate limiting system.
 */
public record ServiceRateLimitConfigDto(
        Long requestsPerWindow, Long windowSeconds, Long burstCapacity, WebSocketRateLimitConfigDto websocket) {

    /**
     * Converts this DTO to a ServiceRateLimitConfig model.
     */
    public ServiceRateLimitConfig toModel() {
        return new ServiceRateLimitConfig(
                Optional.ofNullable(requestsPerWindow),
                Optional.ofNullable(windowSeconds),
                Optional.ofNullable(burstCapacity));
    }

    /**
     * Creates a DTO from a ServiceRateLimitConfig model.
     */
    public static ServiceRateLimitConfigDto fromModel(ServiceRateLimitConfig model) {
        if (model == null) {
            return null;
        }
        return new ServiceRateLimitConfigDto(
                model.requestsPerWindow().orElse(null),
                model.windowSeconds().orElse(null),
                model.burstCapacity().orElse(null),
                null); // WebSocket config not stored in model yet
    }

    /**
     * WebSocket-specific rate limit configuration.
     *
     * <p>Included for forward compatibility. These settings are parsed
     * but not currently applied by the rate limiting system.
     */
    public record WebSocketRateLimitConfigDto(RateLimitValuesDto connection, RateLimitValuesDto message) {}

    /**
     * Rate limit values for a specific context.
     */
    public record RateLimitValuesDto(Long requestsPerWindow, Long windowSeconds, Long burstCapacity) {}
}
