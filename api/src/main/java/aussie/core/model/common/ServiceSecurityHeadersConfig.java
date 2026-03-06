package aussie.core.model.common;

import java.util.Map;
import java.util.Optional;

/**
 * Per-service security header configuration.
 *
 * <p>All fields are {@link Optional} so that services only override the headers
 * they need. Absent fields fall through to the global gateway defaults.
 *
 * <p><b>Suppression semantics:</b> An empty string {@code ""} means "do not
 * emit this header for this service." A present non-empty value overrides the
 * global default.
 *
 * <p>{@code customHeaders} allows services to declare arbitrary additional
 * response headers that are not part of the standard set.
 *
 * @param contentTypeOptions           override for X-Content-Type-Options
 * @param frameOptions                 override for X-Frame-Options
 * @param contentSecurityPolicy        override for Content-Security-Policy
 * @param referrerPolicy               override for Referrer-Policy
 * @param permittedCrossDomainPolicies override for X-Permitted-Cross-Domain-Policies
 * @param strictTransportSecurity      override for Strict-Transport-Security
 * @param permissionsPolicy            override for Permissions-Policy
 * @param customHeaders                arbitrary additional response headers
 */
public record ServiceSecurityHeadersConfig(
        Optional<String> contentTypeOptions,
        Optional<String> frameOptions,
        Optional<String> contentSecurityPolicy,
        Optional<String> referrerPolicy,
        Optional<String> permittedCrossDomainPolicies,
        Optional<String> strictTransportSecurity,
        Optional<String> permissionsPolicy,
        Map<String, String> customHeaders) {

    public ServiceSecurityHeadersConfig {
        if (contentTypeOptions == null) {
            contentTypeOptions = Optional.empty();
        }
        if (frameOptions == null) {
            frameOptions = Optional.empty();
        }
        if (contentSecurityPolicy == null) {
            contentSecurityPolicy = Optional.empty();
        }
        if (referrerPolicy == null) {
            referrerPolicy = Optional.empty();
        }
        if (permittedCrossDomainPolicies == null) {
            permittedCrossDomainPolicies = Optional.empty();
        }
        if (strictTransportSecurity == null) {
            strictTransportSecurity = Optional.empty();
        }
        if (permissionsPolicy == null) {
            permissionsPolicy = Optional.empty();
        }
        if (customHeaders == null) {
            customHeaders = Map.of();
        } else {
            customHeaders = Map.copyOf(customHeaders);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String contentTypeOptions;
        private String frameOptions;
        private String contentSecurityPolicy;
        private String referrerPolicy;
        private String permittedCrossDomainPolicies;
        private String strictTransportSecurity;
        private String permissionsPolicy;
        private Map<String, String> customHeaders = Map.of();

        public Builder contentTypeOptions(String contentTypeOptions) {
            this.contentTypeOptions = contentTypeOptions;
            return this;
        }

        public Builder frameOptions(String frameOptions) {
            this.frameOptions = frameOptions;
            return this;
        }

        public Builder contentSecurityPolicy(String contentSecurityPolicy) {
            this.contentSecurityPolicy = contentSecurityPolicy;
            return this;
        }

        public Builder referrerPolicy(String referrerPolicy) {
            this.referrerPolicy = referrerPolicy;
            return this;
        }

        public Builder permittedCrossDomainPolicies(String permittedCrossDomainPolicies) {
            this.permittedCrossDomainPolicies = permittedCrossDomainPolicies;
            return this;
        }

        public Builder strictTransportSecurity(String strictTransportSecurity) {
            this.strictTransportSecurity = strictTransportSecurity;
            return this;
        }

        public Builder permissionsPolicy(String permissionsPolicy) {
            this.permissionsPolicy = permissionsPolicy;
            return this;
        }

        public Builder customHeaders(Map<String, String> customHeaders) {
            this.customHeaders = customHeaders;
            return this;
        }

        public ServiceSecurityHeadersConfig build() {
            return new ServiceSecurityHeadersConfig(
                    Optional.ofNullable(contentTypeOptions),
                    Optional.ofNullable(frameOptions),
                    Optional.ofNullable(contentSecurityPolicy),
                    Optional.ofNullable(referrerPolicy),
                    Optional.ofNullable(permittedCrossDomainPolicies),
                    Optional.ofNullable(strictTransportSecurity),
                    Optional.ofNullable(permissionsPolicy),
                    customHeaders);
        }
    }
}
