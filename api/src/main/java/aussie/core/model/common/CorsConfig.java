package aussie.core.model.common;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * CORS (Cross-Origin Resource Sharing) configuration.
 *
 * <p>Used for both global gateway defaults and per-service overrides.
 */
public record CorsConfig(
        List<String> allowedOrigins,
        Set<String> allowedMethods,
        Set<String> allowedHeaders,
        Set<String> exposedHeaders,
        boolean allowCredentials,
        Optional<Long> maxAge) {

    public CorsConfig {
        if (allowedOrigins == null) {
            allowedOrigins = List.of();
        }
        if (allowedMethods == null) {
            allowedMethods = Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS");
        }
        if (allowedHeaders == null) {
            allowedHeaders = Set.of("Content-Type", "Authorization", "X-Requested-With");
        }
        if (exposedHeaders == null) {
            exposedHeaders = Set.of();
        }
        if (maxAge == null) {
            maxAge = Optional.empty();
        }
    }

    /**
     * Creates default CORS config that allows all origins.
     */
    public static CorsConfig allowAll() {
        return new CorsConfig(
                List.of("*"),
                Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD"),
                Set.of("*"),
                Set.of(),
                true,
                Optional.of(3600L));
    }

    /**
     * Creates CORS config that denies all cross-origin requests.
     */
    public static CorsConfig denyAll() {
        return new CorsConfig(List.of(), Set.of(), Set.of(), Set.of(), false, Optional.empty());
    }

    /**
     * Checks if the given origin is allowed.
     *
     * @param origin The Origin header value
     * @return true if the origin is allowed
     */
    public boolean isOriginAllowed(String origin) {
        if (origin == null || origin.isBlank()) {
            return false;
        }
        if (allowedOrigins.isEmpty()) {
            return false;
        }
        if (allowedOrigins.contains("*")) {
            return true;
        }
        return allowedOrigins.stream().anyMatch(allowed -> matchesOrigin(allowed, origin));
    }

    /**
     * Checks if the given method is allowed.
     *
     * @param method The HTTP method
     * @return true if the method is allowed
     */
    public boolean isMethodAllowed(String method) {
        if (method == null || method.isBlank()) {
            return false;
        }
        if (allowedMethods.contains("*")) {
            return true;
        }
        return allowedMethods.contains(method.toUpperCase());
    }

    /**
     * Gets the allowed headers as a comma-separated string for the response header.
     */
    public String getAllowedHeadersString() {
        if (allowedHeaders.contains("*")) {
            return "*";
        }
        return String.join(", ", allowedHeaders);
    }

    /**
     * Gets the allowed methods as a comma-separated string for the response header.
     */
    public String getAllowedMethodsString() {
        if (allowedMethods.contains("*")) {
            return "GET, POST, PUT, DELETE, PATCH, OPTIONS, HEAD";
        }
        return String.join(", ", allowedMethods);
    }

    /**
     * Gets the exposed headers as a comma-separated string for the response header.
     */
    public String getExposedHeadersString() {
        if (exposedHeaders.isEmpty()) {
            return null;
        }
        return String.join(", ", exposedHeaders);
    }

    private boolean matchesOrigin(String pattern, String origin) {
        if (pattern.equals(origin)) {
            return true;
        }
        // Support wildcard subdomains like *.example.com
        // This should only match subdomains, not the main domain itself
        if (pattern.startsWith("*.")) {
            String domain = pattern.substring(2);
            return origin.endsWith("." + domain);
        }
        return false;
    }

    /**
     * Merges this config with another, using the other config's values where present.
     * This config acts as the default, other acts as the override.
     *
     * @param override The config to merge with (overrides this config)
     * @return Merged config
     */
    public CorsConfig mergeWith(CorsConfig override) {
        if (override == null) {
            return this;
        }
        return new CorsConfig(
                override.allowedOrigins().isEmpty() ? this.allowedOrigins : override.allowedOrigins(),
                override.allowedMethods().isEmpty() ? this.allowedMethods : override.allowedMethods(),
                override.allowedHeaders().isEmpty() ? this.allowedHeaders : override.allowedHeaders(),
                override.exposedHeaders().isEmpty() ? this.exposedHeaders : override.exposedHeaders(),
                override.allowCredentials(),
                override.maxAge().isPresent() ? override.maxAge() : this.maxAge());
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private List<String> allowedOrigins = List.of();
        private Set<String> allowedMethods = Set.of();
        private Set<String> allowedHeaders = Set.of();
        private Set<String> exposedHeaders = Set.of();
        private boolean allowCredentials = false;
        private Long maxAge;

        public Builder allowedOrigins(List<String> origins) {
            this.allowedOrigins = origins;
            return this;
        }

        public Builder allowedOrigins(String... origins) {
            this.allowedOrigins = List.of(origins);
            return this;
        }

        public Builder allowedMethods(Set<String> methods) {
            this.allowedMethods = methods;
            return this;
        }

        public Builder allowedMethods(String... methods) {
            this.allowedMethods = Set.of(methods);
            return this;
        }

        public Builder allowedHeaders(Set<String> headers) {
            this.allowedHeaders = headers;
            return this;
        }

        public Builder allowedHeaders(String... headers) {
            this.allowedHeaders = Set.of(headers);
            return this;
        }

        public Builder exposedHeaders(Set<String> headers) {
            this.exposedHeaders = headers;
            return this;
        }

        public Builder exposedHeaders(String... headers) {
            this.exposedHeaders = Set.of(headers);
            return this;
        }

        public Builder allowCredentials(boolean allow) {
            this.allowCredentials = allow;
            return this;
        }

        public Builder maxAge(long seconds) {
            this.maxAge = seconds;
            return this;
        }

        public CorsConfig build() {
            return new CorsConfig(
                    allowedOrigins,
                    allowedMethods,
                    allowedHeaders,
                    exposedHeaders,
                    allowCredentials,
                    Optional.ofNullable(maxAge));
        }
    }
}
