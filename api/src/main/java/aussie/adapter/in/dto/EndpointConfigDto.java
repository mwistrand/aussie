package aussie.adapter.in.dto;

import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonProperty;

import aussie.core.model.ratelimit.EndpointRateLimitConfig;
import aussie.core.model.routing.EndpointConfig;
import aussie.core.model.routing.EndpointType;
import aussie.core.model.routing.EndpointVisibility;

/**
 * DTO for endpoint configuration in service registration requests.
 *
 * @param path            the endpoint path pattern
 * @param methods         HTTP methods this endpoint accepts
 * @param visibility      PUBLIC or PRIVATE
 * @param pathRewrite     optional path transformation
 * @param authRequired    whether authentication is required
 * @param type            HTTP or WEBSOCKET
 * @param audience        optional audience claim for tokens issued to this endpoint
 * @param rateLimitConfig optional endpoint-specific rate limiting
 */
public record EndpointConfigDto(
        @JsonProperty("path") String path,
        @JsonProperty("methods") Set<String> methods,
        @JsonProperty("visibility") String visibility,
        @JsonProperty("pathRewrite") String pathRewrite,
        @JsonProperty("authRequired") Boolean authRequired,
        @JsonProperty("type") String type,
        @JsonProperty("audience") String audience,
        @JsonProperty("rateLimitConfig") EndpointRateLimitConfigDto rateLimitConfig) {

    /**
     * DTO for endpoint-specific rate limit configuration.
     */
    public record EndpointRateLimitConfigDto(
            @JsonProperty("requestsPerWindow") Long requestsPerWindow,
            @JsonProperty("windowSeconds") Long windowSeconds,
            @JsonProperty("burstCapacity") Long burstCapacity) {

        public EndpointRateLimitConfig toModel() {
            return new EndpointRateLimitConfig(
                    Optional.ofNullable(requestsPerWindow),
                    Optional.ofNullable(windowSeconds),
                    Optional.ofNullable(burstCapacity));
        }

        public static EndpointRateLimitConfigDto fromModel(EndpointRateLimitConfig model) {
            return new EndpointRateLimitConfigDto(
                    model.requestsPerWindow().orElse(null),
                    model.windowSeconds().orElse(null),
                    model.burstCapacity().orElse(null));
        }
    }

    public EndpointConfig toModel() {
        return toModel(false);
    }

    public EndpointConfig toModel(boolean defaultAuthRequired) {
        var vis = visibility != null ? EndpointVisibility.valueOf(visibility.toUpperCase()) : EndpointVisibility.PUBLIC;
        var auth = authRequired != null ? authRequired : defaultAuthRequired;
        var endpointType = type != null ? EndpointType.valueOf(type.toUpperCase()) : EndpointType.HTTP;
        var rateLimit = rateLimitConfig != null
                ? Optional.of(rateLimitConfig.toModel())
                : Optional.<EndpointRateLimitConfig>empty();

        return new EndpointConfig(
                path,
                methods,
                vis,
                Optional.ofNullable(pathRewrite),
                auth,
                endpointType,
                rateLimit,
                Optional.ofNullable(audience));
    }

    public static EndpointConfigDto fromModel(EndpointConfig model) {
        return new EndpointConfigDto(
                model.path(),
                model.methods(),
                model.visibility().name(),
                model.pathRewrite().orElse(null),
                model.authRequired(),
                model.type().name(),
                model.audience().orElse(null),
                model.rateLimitConfig()
                        .map(EndpointRateLimitConfigDto::fromModel)
                        .orElse(null));
    }
}
