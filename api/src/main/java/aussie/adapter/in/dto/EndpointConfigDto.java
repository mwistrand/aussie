package aussie.adapter.in.dto;

import java.util.Optional;
import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;

import com.fasterxml.jackson.annotation.JsonProperty;

import aussie.core.model.ratelimit.EndpointRateLimitConfig;
import aussie.core.model.routing.EndpointConfig;
import aussie.core.model.routing.EndpointType;
import aussie.core.model.routing.EndpointVisibility;
import aussie.core.model.sampling.EndpointSamplingConfig;

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
 * @param samplingConfig  optional endpoint-specific OTel sampling configuration
 */
public record EndpointConfigDto(
        @JsonProperty("path") @NotBlank(message = "path is required") String path,
        @JsonProperty("methods") @NotEmpty(message = "at least one HTTP method is required") Set<String> methods,
        @JsonProperty("visibility")
                @Pattern(regexp = "^(PUBLIC|PRIVATE)$", message = "visibility must be PUBLIC or PRIVATE")
                String visibility,
        @JsonProperty("pathRewrite") String pathRewrite,
        @JsonProperty("authRequired") Boolean authRequired,
        @JsonProperty("type") @Pattern(regexp = "^(HTTP|WEBSOCKET)$", message = "type must be HTTP or WEBSOCKET")
                String type,
        @JsonProperty("audience") String audience,
        @JsonProperty("rateLimitConfig") @Valid EndpointRateLimitConfigDto rateLimitConfig,
        @JsonProperty("samplingConfig") @Valid EndpointSamplingConfigDto samplingConfig) {

    /**
     * DTO for endpoint-specific rate limit configuration.
     */
    public record EndpointRateLimitConfigDto(
            @JsonProperty("requestsPerWindow") @Min(value = 1, message = "requestsPerWindow must be at least 1")
                    Long requestsPerWindow,
            @JsonProperty("windowSeconds") @Min(value = 1, message = "windowSeconds must be at least 1")
                    Long windowSeconds,
            @JsonProperty("burstCapacity") @Min(value = 1, message = "burstCapacity must be at least 1")
                    Long burstCapacity) {

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

    /**
     * DTO for endpoint-specific sampling configuration.
     *
     * @param samplingRate sampling rate (0.0 to 1.0), where 1.0 means no sampling
     */
    public record EndpointSamplingConfigDto(
            @JsonProperty("samplingRate")
                    @DecimalMin(value = "0.0", message = "samplingRate must be at least 0.0")
                    @DecimalMax(value = "1.0", message = "samplingRate must be at most 1.0")
                    Double samplingRate) {

        /**
         * Convert this DTO to an EndpointSamplingConfig model.
         *
         * @return the domain model
         */
        public EndpointSamplingConfig toModel() {
            return new EndpointSamplingConfig(Optional.ofNullable(samplingRate));
        }

        /**
         * Create a DTO from an EndpointSamplingConfig model.
         *
         * @param model the domain model
         * @return the DTO representation
         */
        public static EndpointSamplingConfigDto fromModel(EndpointSamplingConfig model) {
            return new EndpointSamplingConfigDto(model.samplingRate().orElse(null));
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
        var sampling = samplingConfig != null
                ? Optional.of(samplingConfig.toModel())
                : Optional.<EndpointSamplingConfig>empty();

        return new EndpointConfig(
                path,
                methods,
                vis,
                Optional.ofNullable(pathRewrite),
                auth,
                endpointType,
                rateLimit,
                sampling,
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
                        .orElse(null),
                model.samplingConfig().map(EndpointSamplingConfigDto::fromModel).orElse(null));
    }
}
