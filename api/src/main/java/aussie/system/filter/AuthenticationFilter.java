package aussie.system.filter;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import jakarta.annotation.Priority;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

import aussie.core.model.auth.AuthenticationResult;
import aussie.spi.AuthenticationProvider;

/**
 * JAX-RS filter that performs authentication for protected endpoints.
 *
 * <p><strong>DEPRECATED:</strong> This filter is superseded by Quarkus Security
 * integration via {@code ApiKeyAuthenticationMechanism} and {@code ApiKeyIdentityProvider}.
 * It is retained for backward compatibility with custom {@link AuthenticationProvider}
 * implementations but is disabled by default.
 *
 * <p>To enable this filter (e.g., for custom providers), set:
 * <pre>
 * aussie.auth.use-legacy-filter=true
 * </pre>
 *
 * <p>This filter tries each available {@link AuthenticationProvider} in priority
 * order (highest first) until one returns a non-Skip result.
 *
 * <p>By default, only admin paths ({@code /admin/*}) require authentication.
 * Gateway proxy paths remain open to allow public traffic.
 *
 * <p>The authenticated context is stored as a request property and can be
 * accessed by downstream filters and resources via:
 * <pre>
 * AuthenticationContext ctx = (AuthenticationContext)
 *     requestContext.getProperty(AuthenticationFilter.AUTH_CONTEXT_PROPERTY);
 * </pre>
 *
 * @deprecated Use Quarkus Security with {@code @RolesAllowed} annotations instead.
 */
@Deprecated
@Provider
@Priority(Priorities.AUTHENTICATION)
public class AuthenticationFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(AuthenticationFilter.class);

    /**
     * Request property key for the AuthenticationContext.
     */
    public static final String AUTH_CONTEXT_PROPERTY = "aussie.auth.context";

    @Inject
    Instance<AuthenticationProvider> providers;

    private boolean isAuthEnabled() {
        return ConfigProvider.getConfig()
                .getOptionalValue("aussie.auth.enabled", Boolean.class)
                .orElse(true);
    }

    private boolean isAdminPathsOnly() {
        return ConfigProvider.getConfig()
                .getOptionalValue("aussie.auth.admin-paths-only", Boolean.class)
                .orElse(true);
    }

    private boolean useLegacyFilter() {
        return ConfigProvider.getConfig()
                .getOptionalValue("aussie.auth.use-legacy-filter", Boolean.class)
                .orElse(false);
    }

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        // Skip if legacy filter is disabled (Quarkus Security handles auth)
        if (!useLegacyFilter()) {
            return;
        }

        String path = requestContext.getUriInfo().getPath();

        // Skip auth for non-admin paths if configured
        if (isAdminPathsOnly() && !path.startsWith("admin")) {
            return;
        }

        // Skip if auth is disabled entirely
        if (!isAuthEnabled()) {
            LOG.warn("Authentication is disabled (aussie.auth.enabled=false)");
            return;
        }

        // Get available providers sorted by priority (highest first)
        List<AuthenticationProvider> sortedProviders = providers.stream()
                .filter(AuthenticationProvider::isAvailable)
                .sorted(Comparator.comparingInt(AuthenticationProvider::priority)
                        .reversed())
                .toList();

        if (sortedProviders.isEmpty()) {
            LOG.error("No authentication providers available!");
            requestContext.abortWith(Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Map.of("error", "Authentication not configured"))
                    .build());
            return;
        }

        // Try each provider in priority order
        for (AuthenticationProvider provider : sortedProviders) {
            AuthenticationResult result = provider.authenticate(requestContext.getHeaders(), path);

            switch (result) {
                case AuthenticationResult.Success success -> {
                    // Store context for downstream use
                    requestContext.setProperty(AUTH_CONTEXT_PROPERTY, success.context());
                    LOG.debugf(
                            "Authenticated via %s: %s",
                            provider.name(), success.context().principal().name());
                    return;
                }
                case AuthenticationResult.Failure failure -> {
                    LOG.debugf("Authentication failed via %s: %s", provider.name(), failure.reason());
                    requestContext.abortWith(Response.status(failure.statusCode())
                            .entity(Map.of("error", failure.reason()))
                            .build());
                    return;
                }
                case AuthenticationResult.Skip skip -> {
                    // Try next provider
                    LOG.tracef("Provider %s skipped", provider.name());
                }
            }
        }

        // No provider authenticated the request
        requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                .entity(Map.of("error", "Authentication required"))
                .build());
    }
}
