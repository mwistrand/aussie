package aussie.adapter.in.dto;

import java.util.Optional;

import aussie.core.model.ServiceRateLimitConfig;
import aussie.core.model.ServiceWebSocketRateLimitConfig;
import aussie.core.model.ServiceWebSocketRateLimitConfig.RateLimitValues;

/**
 * DTO for service rate limit configuration in service registration.
 *
 * <p>Maps between the JSON representation and the domain model.
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
                Optional.ofNullable(burstCapacity),
                Optional.ofNullable(websocket).map(WebSocketRateLimitConfigDto::toModel));
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
                model.websocket().map(WebSocketRateLimitConfigDto::fromModel).orElse(null));
    }

    /**
     * WebSocket-specific rate limit configuration.
     */
    public record WebSocketRateLimitConfigDto(RateLimitValuesDto connection, RateLimitValuesDto message) {

        /**
         * Converts this DTO to a ServiceWebSocketRateLimitConfig model.
         */
        public ServiceWebSocketRateLimitConfig toModel() {
            return new ServiceWebSocketRateLimitConfig(
                    Optional.ofNullable(connection).map(RateLimitValuesDto::toModel),
                    Optional.ofNullable(message).map(RateLimitValuesDto::toModel));
        }

        /**
         * Creates a DTO from a ServiceWebSocketRateLimitConfig model.
         */
        public static WebSocketRateLimitConfigDto fromModel(ServiceWebSocketRateLimitConfig model) {
            if (model == null) {
                return null;
            }
            return new WebSocketRateLimitConfigDto(
                    model.connection().map(RateLimitValuesDto::fromModel).orElse(null),
                    model.message().map(RateLimitValuesDto::fromModel).orElse(null));
        }
    }

    /**
     * Rate limit values for a specific context.
     */
    public record RateLimitValuesDto(Long requestsPerWindow, Long windowSeconds, Long burstCapacity) {

        /**
         * Converts this DTO to a RateLimitValues model.
         */
        public RateLimitValues toModel() {
            return new RateLimitValues(
                    Optional.ofNullable(requestsPerWindow),
                    Optional.ofNullable(windowSeconds),
                    Optional.ofNullable(burstCapacity));
        }

        /**
         * Creates a DTO from a RateLimitValues model.
         */
        public static RateLimitValuesDto fromModel(RateLimitValues model) {
            if (model == null) {
                return null;
            }
            return new RateLimitValuesDto(
                    model.requestsPerWindow().orElse(null),
                    model.windowSeconds().orElse(null),
                    model.burstCapacity().orElse(null));
        }
    }
}
